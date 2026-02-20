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
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.TransportFactory;

/**
 * Factory for QUIC endpoints, backed by quiche and BoringSSL via JNI.
 *
 * <p>QuicTransportFactory translates the unified {@link TransportFactory}
 * configuration (cipher suites, named groups, certificate/key paths) into
 * the corresponding BoringSSL {@code SSL_CTX} and quiche configuration
 * calls.
 *
 * <p>QUIC is always secure (TLS 1.3 is built in). The
 * {@link #isSecure()} / {@link #setSecure(boolean)} methods are respected
 * but effectively always return true.
 *
 * <h3>Usage example (server)</h3>
 * <pre>
 * QuicTransportFactory factory = new QuicTransportFactory();
 * factory.setCertFile("/path/to/cert.pem");
 * factory.setKeyFile("/path/to/key.pem");
 * factory.setCipherSuites("TLS_AES_256_GCM_SHA384");
 * factory.setNamedGroups("X25519MLKEM768");
 * factory.setApplicationProtocols("h3");
 * factory.start();
 *
 * QuicEngine engine = factory.createServerEngine(
 *         InetAddress.getByName("0.0.0.0"), 443,
 *         streamAcceptHandler, selectorLoop);
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicEngine
 * @see TransportFactory
 */
public class QuicTransportFactory extends TransportFactory {

    private static final Logger LOGGER =
            Logger.getLogger(QuicTransportFactory.class.getName());

    /** QUIC version 1 (RFC 9000). */
    static final int QUICHE_PROTOCOL_VERSION_1 = 0x00000001;
    /** QUIC version 2 (RFC 9369). */
    static final int QUICHE_PROTOCOL_VERSION_2 = 0x6b3343cf;

    // quiche config defaults
    private static final long DEFAULT_MAX_IDLE_TIMEOUT = 30000;
    private static final long DEFAULT_MAX_DATA = 10_000_000;
    private static final long DEFAULT_MAX_STREAM_DATA = 1_000_000;
    private static final long DEFAULT_MAX_STREAMS_BIDI = 100;
    private static final long DEFAULT_MAX_STREAMS_UNI = 100;
    private static final long DEFAULT_MAX_RECV_PAYLOAD = 1350;
    private static final long DEFAULT_MAX_SEND_PAYLOAD = 1350;

    // Congestion control algorithms (quiche enum values)
    /** Reno congestion control. */
    public static final int CC_RENO = 0;
    /** CUBIC congestion control (default). */
    public static final int CC_CUBIC = 1;
    /** BBR congestion control. */
    public static final int CC_BBR = 2;

    // BoringSSL SSL_CTX handle (shared by all connections)
    private long sslCtx;

    // quiche config handles (one per supported QUIC version)
    private long quicheConfigV1;
    private long quicheConfigV2;

    // QUIC-specific configuration
    private String applicationProtocols;
    private String caFile;
    private boolean verifyPeer = true;
    private long maxIdleTimeout = DEFAULT_MAX_IDLE_TIMEOUT;
    private long maxData = DEFAULT_MAX_DATA;
    private long maxStreamDataBidiLocal = DEFAULT_MAX_STREAM_DATA;
    private long maxStreamDataBidiRemote = DEFAULT_MAX_STREAM_DATA;
    private long maxStreamDataUni = DEFAULT_MAX_STREAM_DATA;
    private long maxStreamsBidi = DEFAULT_MAX_STREAMS_BIDI;
    private long maxStreamsUni = DEFAULT_MAX_STREAMS_UNI;
    private int ccAlgorithm = CC_CUBIC;

    public QuicTransportFactory() {
        // QUIC is always secure
        this.secure = true;
    }

    // ── QUIC-specific configuration ──

    /**
     * Sets the application protocols for ALPN negotiation.
     *
     * <p>Comma-separated protocol identifiers. For HTTP/3, use "h3".
     * For DNS over QUIC, use "doq".
     *
     * @param protocols the ALPN protocols (e.g. "h3" or "h3,h3-29")
     */
    public void setApplicationProtocols(String protocols) {
        this.applicationProtocols = protocols;
    }

    /**
     * Sets the CA certificate file for peer verification.
     *
     * @param path the PEM file containing trusted CA certificates
     */
    public void setCaFile(String path) {
        this.caFile = path;
    }

    /**
     * Sets whether to verify the peer's certificate.
     * Default is true for server mode (verify clients if configured),
     * always true for client mode.
     *
     * @param verify true to verify the peer
     */
    public void setVerifyPeer(boolean verify) {
        this.verifyPeer = verify;
    }

    /**
     * Sets the maximum idle timeout in milliseconds.
     * Default: 30000 ms.
     *
     * @param ms the timeout in milliseconds
     */
    public void setMaxIdleTimeout(long ms) {
        this.maxIdleTimeout = ms;
    }

    /**
     * Sets the maximum data limit for the connection.
     * Default: 10 MB.
     *
     * @param bytes the limit in bytes
     */
    public void setMaxData(long bytes) {
        this.maxData = bytes;
    }

    /**
     * Sets the per-stream data limit for locally-initiated bidi streams.
     * Default: 1 MB.
     *
     * @param bytes the limit in bytes
     */
    public void setMaxStreamDataBidiLocal(long bytes) {
        this.maxStreamDataBidiLocal = bytes;
    }

    /**
     * Sets the per-stream data limit for remotely-initiated bidi streams.
     * Default: 1 MB.
     *
     * @param bytes the limit in bytes
     */
    public void setMaxStreamDataBidiRemote(long bytes) {
        this.maxStreamDataBidiRemote = bytes;
    }

    /**
     * Sets the per-stream data limit for unidirectional streams.
     * Default: 1 MB.
     *
     * @param bytes the limit in bytes
     */
    public void setMaxStreamDataUni(long bytes) {
        this.maxStreamDataUni = bytes;
    }

    /**
     * Sets the maximum number of concurrent bidirectional streams.
     * Default: 100.
     *
     * @param count the stream limit
     */
    public void setMaxStreamsBidi(long count) {
        this.maxStreamsBidi = count;
    }

    /**
     * Sets the maximum number of concurrent unidirectional streams.
     * Default: 100.
     *
     * @param count the stream limit
     */
    public void setMaxStreamsUni(long count) {
        this.maxStreamsUni = count;
    }

    /**
     * Sets the congestion control algorithm.
     * Use {@link #CC_RENO}, {@link #CC_CUBIC}, or {@link #CC_BBR}.
     * Default: {@link #CC_CUBIC}.
     *
     * @param algorithm the congestion control algorithm
     */
    public void setCongestionControl(int algorithm) {
        this.ccAlgorithm = algorithm;
    }

    // ── Native handle accessors (package-private) ──

    long getSslCtx() {
        return sslCtx;
    }

    /**
     * Returns the default (v1) quiche config.
     * Used for client-initiated connections.
     */
    long getQuicheConfig() {
        return quicheConfigV1;
    }

    /**
     * Returns the quiche config for the given QUIC version.
     *
     * @param version the QUIC version from the incoming packet
     * @return the config handle, or 0 if the version is not supported
     */
    long getQuicheConfig(int version) {
        if (version == QUICHE_PROTOCOL_VERSION_1) {
            return quicheConfigV1;
        }
        if (version == QUICHE_PROTOCOL_VERSION_2) {
            return quicheConfigV2;
        }
        return 0;
    }

    /**
     * Returns true if the given QUIC version is supported by this server.
     */
    boolean isVersionSupported(int version) {
        return getQuicheConfig(version) != 0;
    }

    // ── Lifecycle ──

    @Override
    public void start() {
        super.start();

        QuicheNative.quiche_enable_debug_logging();

        initSslCtx();
        initQuicheConfig();

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("QuicTransportFactory started: " + getDescription());
        }
    }

    private void initSslCtx() {
        sslCtx = QuicheNative.ssl_ctx_new(true);
        if (sslCtx == 0) {
            throw new RuntimeException("Failed to create BoringSSL SSL_CTX");
        }

        int rc;

        if (certFile != null) {
            rc = QuicheNative.ssl_ctx_load_cert_chain(sslCtx, certFile);
            if (rc != 0) {
                throw new RuntimeException(
                        "Failed to load certificate chain: " + certFile);
            }
        }

        if (keyFile != null) {
            rc = QuicheNative.ssl_ctx_load_priv_key(sslCtx, keyFile);
            if (rc != 0) {
                throw new RuntimeException(
                        "Failed to load private key: " + keyFile);
            }
        }

        if (caFile != null) {
            rc = QuicheNative.ssl_ctx_load_verify_locations(sslCtx, caFile);
            if (rc != 0) {
                throw new RuntimeException(
                        "Failed to load CA certificates: " + caFile);
            }
        }

        if (cipherSuites != null) {
            rc = QuicheNative.ssl_ctx_set_ciphersuites(
                    sslCtx, cipherSuites);
            if (rc != 0) {
                throw new RuntimeException(
                        "Failed to set cipher suites: " + cipherSuites);
            }
        }

        if (namedGroups != null) {
            rc = QuicheNative.ssl_ctx_set_groups(sslCtx, namedGroups);
            if (rc != 0) {
                throw new RuntimeException(
                        "Failed to set named groups: " + namedGroups);
            }
        }

        if (applicationProtocols != null) {
            byte[] alpn = encodeAlpnProtocols(applicationProtocols);
            rc = QuicheNative.ssl_ctx_set_alpn_protos(sslCtx, alpn);
            if (rc != 0) {
                throw new RuntimeException(
                        "Failed to set ALPN protocols: "
                        + applicationProtocols);
            }
        }

        QuicheNative.ssl_ctx_set_verify_peer(sslCtx, verifyPeer);
    }

    private void initQuicheConfig() {
        quicheConfigV1 = createQuicheConfig(QUICHE_PROTOCOL_VERSION_1);
        if (quicheConfigV1 == 0) {
            throw new RuntimeException(
                    "Failed to create quiche config for QUIC v1");
        }

        quicheConfigV2 = createQuicheConfig(QUICHE_PROTOCOL_VERSION_2);
        if (quicheConfigV2 != 0) {
            LOGGER.info("QUIC v2 (RFC 9369) support enabled");
        }
    }

    private long createQuicheConfig(int version) {
        long config = QuicheNative.quiche_config_new(version);
        if (config == 0) {
            return 0;
        }

        if (applicationProtocols != null) {
            byte[] alpn = encodeAlpnProtocols(applicationProtocols);
            QuicheNative.quiche_config_set_application_protos(config, alpn);
        }

        QuicheNative.quiche_config_set_max_idle_timeout(
                config, maxIdleTimeout);
        QuicheNative.quiche_config_set_initial_max_data(
                config, maxData);
        QuicheNative.quiche_config_set_initial_max_stream_data_bidi_local(
                config, maxStreamDataBidiLocal);
        QuicheNative.quiche_config_set_initial_max_stream_data_bidi_remote(
                config, maxStreamDataBidiRemote);
        QuicheNative.quiche_config_set_initial_max_stream_data_uni(
                config, maxStreamDataUni);
        QuicheNative.quiche_config_set_initial_max_streams_bidi(
                config, maxStreamsBidi);
        QuicheNative.quiche_config_set_initial_max_streams_uni(
                config, maxStreamsUni);
        QuicheNative.quiche_config_set_cc_algorithm(
                config, ccAlgorithm);
        QuicheNative.quiche_config_set_max_recv_udp_payload_size(
                config, DEFAULT_MAX_RECV_PAYLOAD);
        QuicheNative.quiche_config_set_max_send_udp_payload_size(
                config, DEFAULT_MAX_SEND_PAYLOAD);

        return config;
    }

    @Override
    protected void stop() {
        if (quicheConfigV1 != 0) {
            QuicheNative.quiche_config_free(quicheConfigV1);
            quicheConfigV1 = 0;
        }
        if (quicheConfigV2 != 0) {
            QuicheNative.quiche_config_free(quicheConfigV2);
            quicheConfigV2 = 0;
        }
        if (sslCtx != 0) {
            QuicheNative.ssl_ctx_free(sslCtx);
            sslCtx = 0;
        }
        super.stop();
    }

    // ── Engine creation ──

    /**
     * Creates a server-mode QuicEngine bound to the specified address.
     *
     * @param bindAddress the address to bind to
     * @param port the port to listen on
     * @param acceptHandler the handler for accepting incoming streams
     * @param loop the SelectorLoop to register with
     * @return the created QuicEngine
     * @throws IOException if the channel cannot be opened or bound
     */
    public QuicEngine createServerEngine(InetAddress bindAddress, int port,
                                          StreamAcceptHandler acceptHandler,
                                          SelectorLoop loop)
            throws IOException {

        StandardProtocolFamily family = (bindAddress instanceof Inet6Address)
                ? StandardProtocolFamily.INET6
                : StandardProtocolFamily.INET;
        DatagramChannel dc = DatagramChannel.open(family);
        dc.configureBlocking(false);
        dc.bind(new InetSocketAddress(bindAddress, port));

        QuicEngine engine = new QuicEngine(this, true);
        engine.init(dc);
        engine.setStreamAcceptHandler(acceptHandler);

        loop.registerDatagram(dc, engine);

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("QUIC server listening on "
                    + bindAddress.getHostAddress() + ":" + port);
        }

        return engine;
    }

    /**
     * Creates a server-mode QuicEngine with connection-level accept handling.
     *
     * <p>Used by HTTP/3 where each QUIC connection is managed by an h3
     * handler rather than individual stream handlers.
     *
     * @param bindAddress the address to bind to
     * @param port the port to listen on
     * @param handler the handler called for each new QUIC connection
     * @param loop the SelectorLoop to register with
     * @return the created QuicEngine
     * @throws IOException if the channel cannot be opened or bound
     */
    public QuicEngine createServerEngine(
            InetAddress bindAddress, int port,
            QuicEngine.ConnectionAcceptedHandler handler,
            SelectorLoop loop) throws IOException {

        StandardProtocolFamily family = (bindAddress instanceof Inet6Address)
                ? StandardProtocolFamily.INET6
                : StandardProtocolFamily.INET;
        DatagramChannel dc = DatagramChannel.open(family);
        dc.configureBlocking(false);
        dc.bind(new InetSocketAddress(bindAddress, port));

        QuicEngine engine = new QuicEngine(this, true);
        engine.init(dc);
        engine.setConnectionAcceptedHandler(handler);

        loop.registerDatagram(dc, engine);

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("QUIC server listening on "
                    + bindAddress.getHostAddress() + ":" + port);
        }

        return engine;
    }

    /**
     * Creates a client-mode QuicEngine and initiates a connection.
     *
     * @param host the remote host
     * @param port the remote port
     * @param handler the handler for the initial endpoint
     * @param loop the SelectorLoop to register with
     * @param serverName the TLS SNI hostname, or null
     * @return the created QuicEngine
     * @throws IOException if the channel cannot be opened
     */
    public QuicEngine connect(InetAddress host, int port,
                               ProtocolHandler handler,
                               SelectorLoop loop,
                               String serverName)
            throws IOException {

        StandardProtocolFamily family = (host instanceof Inet6Address)
                ? StandardProtocolFamily.INET6
                : StandardProtocolFamily.INET;
        DatagramChannel dc = DatagramChannel.open(family);
        dc.configureBlocking(false);
        dc.bind(null);

        QuicEngine engine = new QuicEngine(this, false);
        engine.init(dc);

        loop.registerDatagram(dc, engine);

        InetSocketAddress remote = new InetSocketAddress(host, port);
        engine.connectTo(remote, handler, serverName);

        return engine;
    }

    /**
     * Creates a client-mode QuicEngine with a connection-level handler.
     *
     * <p>Used by HTTP/3 where the h3 handler needs the
     * {@link QuicConnection} rather than an individual stream endpoint.
     * The connection handler is called when the QUIC handshake completes.
     *
     * @param host the remote host
     * @param port the remote port
     * @param connHandler the connection-level handler
     * @param loop the SelectorLoop to register with
     * @param serverName the TLS SNI hostname, or null
     * @return the created QuicEngine
     * @throws IOException if the channel cannot be opened
     */
    public QuicEngine connect(InetAddress host, int port,
                               QuicEngine.ConnectionAcceptedHandler connHandler,
                               SelectorLoop loop,
                               String serverName)
            throws IOException {

        StandardProtocolFamily family = (host instanceof Inet6Address)
                ? StandardProtocolFamily.INET6
                : StandardProtocolFamily.INET;
        DatagramChannel dc = DatagramChannel.open(family);
        dc.configureBlocking(false);
        dc.bind(null);

        QuicEngine engine = new QuicEngine(this, false);
        engine.init(dc);

        loop.registerDatagram(dc, engine);

        InetSocketAddress remote = new InetSocketAddress(host, port);
        engine.connectTo(remote, null, connHandler, serverName);

        return engine;
    }

    @Override
    protected String getDescription() {
        StringBuilder sb = new StringBuilder("QUIC");
        if (applicationProtocols != null) {
            sb.append(" (ALPN: ");
            sb.append(applicationProtocols);
            sb.append(")");
        }
        if (cipherSuites != null) {
            sb.append(" ciphers=");
            sb.append(cipherSuites);
        }
        if (namedGroups != null) {
            sb.append(" groups=");
            sb.append(namedGroups);
        }
        return sb.toString();
    }

    // ── ALPN encoding ──

    /**
     * Encodes comma-separated ALPN protocol names into the wire format
     * expected by quiche/BoringSSL: length-prefixed byte sequence.
     *
     * <p>Example: "h3,h3-29" becomes [2,'h','3', 5,'h','3','-','2','9']
     *
     * @param protocols comma-separated protocol names
     * @return the encoded ALPN bytes
     */
    static byte[] encodeAlpnProtocols(String protocols) {
        // Count total length
        int totalLen = 0;
        int start = 0;
        while (start < protocols.length()) {
            int comma = protocols.indexOf(',', start);
            if (comma == -1) {
                comma = protocols.length();
            }
            int protoLen = comma - start;
            totalLen += 1 + protoLen;
            start = comma + 1;
        }

        byte[] result = new byte[totalLen];
        int pos = 0;
        start = 0;
        while (start < protocols.length()) {
            int comma = protocols.indexOf(',', start);
            if (comma == -1) {
                comma = protocols.length();
            }
            int protoLen = comma - start;
            result[pos] = (byte) protoLen;
            pos++;
            for (int i = start; i < comma; i++) {
                result[pos] = (byte) protocols.charAt(i);
                pos++;
            }
            start = comma + 1;
        }

        return result;
    }
}
