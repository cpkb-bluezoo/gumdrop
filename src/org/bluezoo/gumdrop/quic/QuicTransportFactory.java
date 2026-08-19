/*
 * QuicTransportFactory.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.quic;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;

import tech.kwik.agent15.engine.TlsServerEngineFactory;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.TransportFactory;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.quic.tls.PemCredentials;

/**
 * Configuration and bootstrap for QUIC transports, the pure-Java
 * replacement for the native quiche/BoringSSL-config-backed
 * implementation.
 *
 * <p>Translates the same PEM cert/key/CA file configuration the native
 * path used into an Agent15 {@link TlsServerEngineFactory} (via
 * {@link PemCredentials}) and an {@link X509TrustManager}, and the same
 * flow-control/idle-timeout limits into a {@link TransportParameters}
 * instance shared by every connection this factory's engines create.
 *
 * <p>{@link #setCipherSuites} and {@link #setNamedGroups} (both
 * inherited from {@link TransportFactory}) are both consulted, filtered
 * through what Agent15/gumdrop's own AEAD layer actually support (see
 * {@code org.bluezoo.gumdrop.quic.tls.QuicCipherSuites}/{@code
 * QuicTlsClientEngine#resolvePreferredNamedGroup}) rather than failing
 * outright on an unsupported request: an unrecognised or unimplemented
 * cipher suite is dropped from the configured list (falling back to
 * gumdrop's full default list, with a logged warning, only if nothing
 * configured survives filtering); an unsupported named group (e.g. a
 * hybrid PQC group such as {@code X25519MLKEM768} -- Agent15 has no
 * ML-KEM support at all, see its own {@code TlsConstants.NamedGroup}) is
 * similarly skipped in favour of the next configured name, with a
 * logged warning if none resolve. Named-group selection has no
 * server-side effect at all, unlike cipher suites: RFC 8446 section
 * 4.2.7 makes {@code supported_groups} a client-only extension, and
 * Agent15 exposes no server-side restriction API to narrow which of a
 * client's offered groups a server will accept; {@link
 * #createServerEngine} logs a warning if {@code namedGroups} is set on a
 * factory used for a server listener. {@link #setCongestionControl} is
 * similarly accepted but ignored -- {@code quic.recovery}'s {@code
 * CongestionController} is NewReno only.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicTransportFactory extends TransportFactory {

    private static final Logger LOGGER = Logger.getLogger(QuicTransportFactory.class.getName());

    private static final long DEFAULT_MAX_IDLE_TIMEOUT = 30000;
    private static final long DEFAULT_MAX_DATA = 10_000_000;
    private static final long DEFAULT_MAX_STREAM_DATA = 1_000_000;
    private static final long DEFAULT_MAX_STREAMS_BIDI = 100;
    private static final long DEFAULT_MAX_STREAMS_UNI = 100;

    /** Reno congestion control -- accepted but not implemented; see the class documentation. */
    public static final int CC_RENO = 0;
    /** Cubic congestion control -- accepted but not implemented; see the class documentation. */
    public static final int CC_CUBIC = 1;
    /** BBR congestion control -- accepted but not implemented; see the class documentation. */
    public static final int CC_BBR = 2;

    private String applicationProtocols;
    private Path caFile;
    private boolean verifyPeer = true;
    private boolean verifyHostname = true;
    private boolean earlyDataEnabled;
    private long maxIdleTimeout = DEFAULT_MAX_IDLE_TIMEOUT;
    private long maxData = DEFAULT_MAX_DATA;
    private long maxStreamDataBidiLocal = DEFAULT_MAX_STREAM_DATA;
    private long maxStreamDataBidiRemote = DEFAULT_MAX_STREAM_DATA;
    private long maxStreamDataUni = DEFAULT_MAX_STREAM_DATA;
    private long maxStreamsBidi = DEFAULT_MAX_STREAMS_BIDI;
    private long maxStreamsUni = DEFAULT_MAX_STREAMS_UNI;

    private TlsServerEngineFactory serverEngineFactory;
    private X509TrustManager trustManager;
    private final byte[] connectionIdStaticKey = new byte[32];
    private final byte[] retryTokenKey = new byte[32];
    private boolean requireRetry;

    public QuicTransportFactory() {
        this.secure = true;
        new SecureRandom().nextBytes(connectionIdStaticKey);
        new SecureRandom().nextBytes(retryTokenKey);
    }

    // ── QUIC-specific configuration ──

    /**
     * Sets the ALPN application protocol(s), comma-separated (e.g. {@code "h3"}, {@code "doq"}).
     *
     * @param protocols the protocol list
     */
    public void setApplicationProtocols(String protocols) {
        this.applicationProtocols = protocols;
    }

    /**
     * Returns the configured ALPN application protocol(s), comma-separated,
     * or null if none were configured.
     */
    String getApplicationProtocols() {
        return applicationProtocols;
    }

    /**
     * Sets whether 0-RTT early data (RFC 9001 section 4.6.1) is enabled.
     * Client-side, presents a cached {@link SessionTicketCache} entry (if
     * any) and fires {@link QuicEngine.EarlyDataHandler#earlyDataReady}
     * once send keys are available; server-side, gates whether {@link
     * org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine#isEarlyDataAccepted}
     * ever accepts it.
     *
     * @param enabled the new state
     */
    public void setEarlyDataEnabled(boolean enabled) {
        this.earlyDataEnabled = enabled;
    }

    /**
     * Returns whether 0-RTT early data is enabled.
     *
     * @return the current state
     */
    public boolean isEarlyDataEnabled() {
        return earlyDataEnabled;
    }

    /**
     * Sets the PEM CA certificate file used to verify peer certificates,
     * instead of the platform default trust store.
     *
     * @param path the PEM CA certificate file
     */
    public void setCaFile(Path path) {
        this.caFile = path;
    }

    /**
     * Sets the PEM CA certificate file from a string path.
     *
     * @param path the PEM CA certificate file
     */
    public void setCaFile(String path) {
        this.caFile = Path.of(path);
    }

    /**
     * Sets whether the peer certificate is verified.
     *
     * @param verify the new state
     */
    public void setVerifyPeer(boolean verify) {
        this.verifyPeer = verify;
    }

    /**
     * Sets whether the client verifies the peer certificate's hostname
     * against the server name offered in the handshake.
     *
     * <p>Enabled by default. A client with no real hostname to offer
     * (e.g. a DNS-over-QUIC client connecting directly to a resolved IP,
     * RFC 9250) has nothing meaningful for this check to match against --
     * see {@link org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine#setVerifyHostname}
     * for the full rationale. Such a caller should disable this and
     * establish trust another way instead ({@link #setCaFile} or a
     * pinned certificate fingerprint).
     *
     * @param verify false to accept any hostname/certificate pairing
     */
    public void setVerifyHostname(boolean verify) {
        this.verifyHostname = verify;
    }

    /**
     * Sets {@code max_idle_timeout} (RFC 9000 section 18.2), in milliseconds.
     *
     * @param ms the idle timeout
     */
    public void setMaxIdleTimeout(long ms) {
        this.maxIdleTimeout = ms;
    }

    /**
     * Sets {@code initial_max_data} (RFC 9000 section 18.2), in bytes.
     *
     * @param bytes the connection-level flow-control limit
     */
    public void setMaxData(long bytes) {
        this.maxData = bytes;
    }

    /**
     * Sets {@code initial_max_stream_data_bidi_local} (RFC 9000 section 18.2), in bytes.
     *
     * @param bytes the flow-control limit
     */
    public void setMaxStreamDataBidiLocal(long bytes) {
        this.maxStreamDataBidiLocal = bytes;
    }

    /**
     * Sets {@code initial_max_stream_data_bidi_remote} (RFC 9000 section 18.2), in bytes.
     *
     * @param bytes the flow-control limit
     */
    public void setMaxStreamDataBidiRemote(long bytes) {
        this.maxStreamDataBidiRemote = bytes;
    }

    /**
     * Sets {@code initial_max_stream_data_uni} (RFC 9000 section 18.2), in bytes.
     *
     * @param bytes the flow-control limit
     */
    public void setMaxStreamDataUni(long bytes) {
        this.maxStreamDataUni = bytes;
    }

    /**
     * Sets {@code initial_max_streams_bidi} (RFC 9000 section 18.2).
     *
     * @param count the concurrent stream limit
     */
    public void setMaxStreamsBidi(long count) {
        this.maxStreamsBidi = count;
    }

    /**
     * Sets {@code initial_max_streams_uni} (RFC 9000 section 18.2).
     *
     * @param count the concurrent stream limit
     */
    public void setMaxStreamsUni(long count) {
        this.maxStreamsUni = count;
    }

    /**
     * Sets the congestion control algorithm. Accepted but not
     * implemented -- see the class documentation.
     *
     * @param algorithm one of {@link #CC_RENO}, {@link #CC_CUBIC}, {@link #CC_BBR}
     */
    public void setCongestionControl(int algorithm) {
        LOGGER.fine("Congestion control algorithm selection is not implemented; always using NewReno");
    }

    /**
     * Sets whether this server requires address validation via a Retry
     * packet (RFC 9000 section 8.1.2) before accepting a new connection.
     * Default {@code false} -- Retry adds a mandatory extra round trip to
     * every handshake, so this is an opt-in DDoS-hardening posture, not a
     * default-on behaviour. Has no effect on client-mode engines.
     *
     * @param require the new state
     */
    public void setRequireRetry(boolean require) {
        this.requireRetry = require;
    }

    /**
     * Returns whether this server requires address validation via Retry.
     *
     * @return the current state
     */
    public boolean isRequireRetry() {
        return requireRetry;
    }

    // ── Package-private accessors used by QuicEngine/QuicConnection ──

    TlsServerEngineFactory getServerEngineFactory() {
        return serverEngineFactory;
    }

    X509TrustManager getTrustManager() {
        return trustManager;
    }

    boolean isVerifyHostnameEnabled() {
        return verifyHostname;
    }

    /**
     * Returns the raw, unparsed {@link #setNamedGroups} value (colon-
     * separated group names, or null) -- resolving these against what
     * Agent15 actually supports is {@link
     * org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine}'s job, not this
     * class's, to keep Agent15 types out of this package.
     */
    String getNamedGroups() {
        return namedGroups;
    }

    /**
     * Returns the raw, unparsed {@link #setCipherSuites} value (colon-
     * separated cipher suite names, or null) -- resolving these against
     * what gumdrop's own QUIC AEAD layer implements is {@code
     * org.bluezoo.gumdrop.quic.tls.QuicCipherSuites}'s job, not this
     * class's, to keep Agent15 types out of this package.
     */
    String getCipherSuites() {
        return cipherSuites;
    }

    byte[] getConnectionIdStaticKey() {
        return connectionIdStaticKey;
    }

    byte[] getRetryTokenKey() {
        return retryTokenKey;
    }

    /**
     * Builds a fresh {@link TransportParameters} from this factory's
     * configured limits, for a new connection with the given
     * {@code initial_source_connection_id}.
     *
     * @param initialSourceConnectionId the new connection's own connection ID
     * @return the transport parameters
     */
    TransportParameters buildTransportParameters(byte[] initialSourceConnectionId) {
        TransportParameters params = new TransportParameters();
        params.setInitialSourceConnectionId(initialSourceConnectionId);
        params.setMaxIdleTimeout(maxIdleTimeout);
        params.setInitialMaxData(maxData);
        params.setInitialMaxStreamDataBidiLocal(maxStreamDataBidiLocal);
        params.setInitialMaxStreamDataBidiRemote(maxStreamDataBidiRemote);
        params.setInitialMaxStreamDataUni(maxStreamDataUni);
        params.setInitialMaxStreamsBidi(maxStreamsBidi);
        params.setInitialMaxStreamsUni(maxStreamsUni);
        return params;
    }

    // ── Lifecycle ──

    @Override
    public void start() {
        super.start();
        if (certFile != null && keyFile != null) {
            try {
                serverEngineFactory = PemCredentials.loadServerEngineFactory(certFile, keyFile);
            } catch (IOException | GeneralSecurityException e) {
                throw new IllegalStateException("Failed to load QUIC server certificate/key", e);
            }
        }
        if (caFile != null) {
            try {
                trustManager = PemCredentials.loadTrustManager(caFile);
            } catch (IOException | GeneralSecurityException e) {
                throw new IllegalStateException("Failed to load QUIC CA certificate", e);
            }
        } else if (!verifyPeer) {
            trustManager = PermissiveTrustManager.INSTANCE;
        }
        LOGGER.info(getDescription());
    }

    @Override
    protected void stop() {
        serverEngineFactory = null;
        trustManager = null;
        super.stop();
    }

    @Override
    protected String getDescription() {
        StringBuilder description = new StringBuilder("QUIC");
        if (applicationProtocols != null) {
            description.append(" (ALPN: ").append(applicationProtocols).append(')');
        }
        return description.toString();
    }

    // ── Server engine creation ──

    /**
     * Binds a server-mode {@link QuicEngine} accepting new peer-initiated
     * streams via {@code acceptHandler}.
     *
     * @param bindAddress the local address to bind to
     * @param port the local port to bind to
     * @param acceptHandler the handler for new peer-initiated streams
     * @param loop the selector loop to register the engine with
     * @return the new engine
     * @throws IOException if the socket cannot be bound
     */
    public QuicEngine createServerEngine(InetAddress bindAddress, int port, StreamAcceptHandler acceptHandler,
            SelectorLoop loop) throws IOException {
        QuicEngine engine = newBoundServerEngine(bindAddress, port, loop);
        engine.setStreamAcceptHandler(acceptHandler);
        return engine;
    }

    /**
     * Binds a server-mode {@link QuicEngine} notified of each new
     * connection via {@code handler}, for connection-level protocols
     * (HTTP/3) that manage their own stream acceptance per connection.
     *
     * @param bindAddress the local address to bind to
     * @param port the local port to bind to
     * @param handler the handler notified of each new connection
     * @param loop the selector loop to register the engine with
     * @return the new engine
     * @throws IOException if the socket cannot be bound
     */
    @SuppressWarnings("overloads")
    public QuicEngine createServerEngine(InetAddress bindAddress, int port, QuicEngine.ConnectionAcceptedHandler handler,
            SelectorLoop loop) throws IOException {
        QuicEngine engine = newBoundServerEngine(bindAddress, port, loop);
        engine.setConnectionAcceptedHandler(handler);
        return engine;
    }

    private QuicEngine newBoundServerEngine(InetAddress bindAddress, int port, SelectorLoop loop) throws IOException {
        if (namedGroups != null && LOGGER.isLoggable(Level.WARNING)) {
            // Unlike setCipherSuites, Agent15 exposes no server-side named-
            // group restriction API at all (RFC 8446 section 4.2.7: only
            // the client sends supported_groups; the server just picks
            // from whatever key_share the client actually offered) --
            // setNamedGroups has no effect here. Warn rather than silently
            // ignoring it, since a caller configuring this for compliance/
            // security reasons (e.g. requiring a specific curve) deserves
            // to know it isn't enforced server-side.
            LOGGER.warning("setNamedGroups(\"" + namedGroups + "\") has no effect on a QUIC "
                    + "server listener: Agent15 has no server-side named-group restriction API, "
                    + "the server accepts whatever group the client's key_share offers.");
        }
        DatagramChannel dc = DatagramChannel.open(bindAddress instanceof Inet6Address
                ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
        dc.configureBlocking(false);
        dc.bind(new InetSocketAddress(bindAddress, port));
        QuicEngine engine = new QuicEngine(this, true);
        engine.init(dc);
        loop.registerDatagram(dc, engine);
        LOGGER.fine("Bound QUIC server engine on " + bindAddress + ":" + port);
        return engine;
    }

    // ── Client connection ──

    /**
     * Opens a client-mode {@link QuicEngine} connected to a remote host,
     * auto-opening a first stream for {@code handler} once the
     * handshake completes.
     *
     * @param host the server host
     * @param port the server port
     * @param handler the handler for the auto-opened first stream
     * @param loop the selector loop to register the engine with
     * @param serverName the SNI server name
     * @return the new engine
     * @throws IOException if the socket cannot be opened
     */
    public QuicEngine connect(InetAddress host, int port, ProtocolHandler handler, SelectorLoop loop, String serverName)
            throws IOException {
        QuicEngine engine = newClientEngine(host, loop);
        engine.connectTo(new InetSocketAddress(host, port), handler, serverName);
        return engine;
    }

    /**
     * Opens a client-mode {@link QuicEngine} connected to a remote host,
     * notified via {@code connHandler} once the handshake completes,
     * for connection-level protocols (HTTP/3) that manage their own
     * streams.
     *
     * @param host the server host
     * @param port the server port
     * @param connHandler notified once the handshake completes
     * @param loop the selector loop to register the engine with
     * @param serverName the SNI server name
     * @return the new engine
     * @throws IOException if the socket cannot be opened
     */
    public QuicEngine connect(InetAddress host, int port, QuicEngine.ConnectionAcceptedHandler connHandler,
            SelectorLoop loop, String serverName) throws IOException {
        QuicEngine engine = newClientEngine(host, loop);
        engine.connectTo(new InetSocketAddress(host, port), null, connHandler, serverName);
        return engine;
    }

    /**
     * Opens a client-mode {@link QuicEngine} connected to a remote host,
     * notified via {@code connHandler} once the handshake completes and
     * {@code earlyDataHandler} if 0-RTT (RFC 9001 section 4.6.1) becomes
     * available first -- see {@link QuicEngine#connectTo(InetSocketAddress,
     * ProtocolHandler, QuicEngine.ConnectionAcceptedHandler,
     * QuicEngine.EarlyDataHandler, String)} for how a cached session
     * ticket is consulted.
     *
     * @param host the server host
     * @param port the server port
     * @param connHandler notified once the handshake completes
     * @param earlyDataHandler notified once 0-RTT send keys are ready, may be null
     * @param loop the selector loop to register the engine with
     * @param serverName the SNI server name, or null (e.g. DoQ)
     * @return the new engine
     * @throws IOException if the socket cannot be opened
     */
    public QuicEngine connect(InetAddress host, int port, QuicEngine.ConnectionAcceptedHandler connHandler,
            final QuicEngine.EarlyDataHandler earlyDataHandler, SelectorLoop loop, String serverName) throws IOException {
        final QuicEngine engine = newClientEngine(host, loop);
        final InetSocketAddress remote = new InetSocketAddress(host, port);
        // Unlike the other connect() overloads, earlyDataHandler (if
        // present) fires synchronously from inside connectTo() -- well
        // before the handshake otherwise completes (RFC 9001 section
        // 4.6.1) -- rather than later, asynchronously, from this
        // engine's own SelectorLoop thread the way ConnectionAcceptedHandler
        // always does. Calling connect() from any other thread (the
        // normal case -- e.g. HTTPClient.connect() is typically called
        // by application code, not from a SelectorLoop thread) would
        // otherwise let earlyDataHandler's own QuicConnection-touching
        // work run concurrently with this engine's own packet processing
        // on the loop thread. Routing the whole call through the loop
        // keeps it consistent with every other QuicConnection entry
        // point: always on the connection's own thread.
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                engine.connectTo(remote, null, connHandler, earlyDataHandler, serverName);
            }
        });
        return engine;
    }

    private QuicEngine newClientEngine(InetAddress host, SelectorLoop loop) throws IOException {
        DatagramChannel dc = DatagramChannel.open(host instanceof Inet6Address
                ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
        dc.configureBlocking(false);
        dc.bind(null);
        QuicEngine engine = new QuicEngine(this, false);
        engine.init(dc);
        loop.registerDatagram(dc, engine);
        return engine;
    }

    /** Accepts any peer certificate -- used only when {@code verifyPeer} is explicitly disabled. */
    private static final class PermissiveTrustManager implements X509TrustManager {

        static final PermissiveTrustManager INSTANCE = new PermissiveTrustManager();

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
