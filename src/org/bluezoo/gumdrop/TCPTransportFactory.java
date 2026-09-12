/*
 * TCPTransportFactory.java
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

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.quic.tls.PemCredentials;
import org.bluezoo.gumdrop.tls.CipherSuite;
import org.bluezoo.gumdrop.tls.ClientAuthPolicy;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.ServerCredentialsResolver;
import org.bluezoo.gumdrop.tls.Tls12CipherSuite;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.bluezoo.gumdrop.util.PinnedCertTrustManager;
import org.bluezoo.gumdrop.util.SniCredentialsResolver;
import org.bluezoo.gumdrop.util.TLSUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.security.KeyStore;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * TCP transport factory.
 *
 * <p>Creates {@link TCPEndpoint} instances for both server-side (accepted)
 * and client-side (outgoing) connections.
 *
 * <p>For TLS, this factory builds an in-tree
 * {@link org.bluezoo.gumdrop.tls.HandshakeConfig} (TLS 1.3) or
 * {@link org.bluezoo.gumdrop.tls.Tls12HandshakeConfig} (TLS 1.2, selected
 * via {@link #setTlsVersion}) -- the
 * {@link TransportFactory#setCipherSuites}/{@link TransportFactory#setNamedGroups}
 * configuration is mapped onto {@link CipherSuite}/{@link NamedGroup} (TLS
 * 1.3) or {@link Tls12CipherSuite} (TLS 1.2) directly, not through any
 * JSSE type.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TCPEndpoint
 * @see TransportFactory
 */
public class TCPTransportFactory extends TransportFactory {

    private static final Logger LOGGER =
            Logger.getLogger(TCPTransportFactory.class.getName());

    // Server identity: either a fixed value, or (SNI) a per-hostname
    // resolver -- at most one is ever set, the resolver taking priority
    // if somehow both were (matches HandshakeConfig's own precedence).
    private ServerCredentials serverCredentials;
    private ServerCredentialsResolver serverCredentialsResolver;

    // Client's own identity, presented only if a server requests client
    // certificate authentication (mTLS) -- client role only, and entirely
    // optional; most clients never set this.
    private ServerCredentials clientCredentials;

    private X509TrustManager trustManager;
    private X509TrustManager effectiveTrustManager;

    // SNI (Server Name Indication) configuration
    private Map<String, String> sniHostnameToAlias;
    private String sniDefaultAlias;

    // Client authentication (defaults to false - no client cert required)
    protected boolean needClientAuth = false;

    // ALPN (Application-Layer Protocol Negotiation) protocols
    private String[] applicationProtocols;

    // RFC 7413: TCP Fast Open for reduced connection latency
    private boolean tcpFastOpen;

    private List<CipherSuite> resolvedCipherSuites;
    private List<NamedGroup> resolvedNamedGroups;
    private List<Tls12CipherSuite> resolvedTls12CipherSuites;

    // Deployment-time TLS version pin -- see TlsVersion's own doc for why
    // this is never runtime-negotiated. Defaults to TLS_1_3, so every
    // existing caller's behaviour is unchanged.
    private TlsVersion tlsVersion = TlsVersion.TLS_1_3;

    public TCPTransportFactory() {
    }

    /**
     * Returns the TLS protocol version this factory's secure endpoints speak.
     *
     * @return the TLS version, defaulting to {@link TlsVersion#TLS_1_3}
     */
    public TlsVersion getTlsVersion() {
        return tlsVersion;
    }

    /**
     * Sets the TLS protocol version this factory's secure endpoints speak
     * -- a deployment-time choice, not negotiated per-connection. See
     * {@link TlsVersion}'s own doc comment for why a deployment wanting to
     * serve both TLS 1.3 and TLS 1.2 peers runs two listeners rather than
     * one that detects the version at runtime.
     *
     * @param tlsVersion the TLS version
     */
    public void setTlsVersion(TlsVersion tlsVersion) {
        this.tlsVersion = (tlsVersion != null) ? tlsVersion : TlsVersion.TLS_1_3;
    }

    // -- SNI configuration --

    /**
     * Sets the SNI hostname to certificate alias mapping.
     *
     * @param hostnames map of hostnames to certificate aliases
     */
    public void setSniHostnames(Map<String, String> hostnames) {
        this.sniHostnameToAlias = hostnames != null
                ? new LinkedHashMap<String, String>(hostnames) : null;
    }

    /**
     * Sets the default certificate alias when SNI does not match.
     *
     * @param alias the default certificate alias
     */
    public void setSniDefaultAlias(String alias) {
        this.sniDefaultAlias = alias;
    }

    /**
     * Returns whether SNI is configured.
     *
     * @return true if SNI hostname mappings have been configured
     */
    public boolean isSNIEnabled() {
        return sniHostnameToAlias != null && !sniHostnameToAlias.isEmpty();
    }

    /**
     * Sets whether client certificate authentication is required.
     *
     * @param needClientAuth true to require client certificates
     */
    public void setNeedClientAuth(boolean needClientAuth) {
        this.needClientAuth = needClientAuth;
    }

    /**
     * Sets the ALPN (Application-Layer Protocol Negotiation) protocols
     * to advertise during TLS handshake. Used for HTTP/2 negotiation.
     *
     * @param protocols the ALPN protocol names (e.g., "h2", "http/1.1")
     */
    public void setApplicationProtocols(String... protocols) {
        this.applicationProtocols = protocols != null && protocols.length > 0
                ? protocols.clone() : null;
    }

    /**
     * Returns the configured ALPN protocols.
     *
     * @return the ALPN protocol names, or null if not configured
     */
    public String[] getApplicationProtocols() {
        return applicationProtocols != null
                ? applicationProtocols.clone() : null;
    }

    /**
     * Enables TCP Fast Open (RFC 7413) on client connections.
     * When enabled, the kernel can send data in the SYN packet,
     * eliminating one RTT from connection setup for repeat connections.
     * RFC 7858 section 3.4 recommends TFO for DoT re-establishment.
     *
     * <p>Silently ignored on platforms that do not support TFO.
     *
     * @param tcpFastOpen true to enable TCP Fast Open
     */
    public void setTcpFastOpen(boolean tcpFastOpen) {
        this.tcpFastOpen = tcpFastOpen;
    }

    /**
     * Returns whether TCP Fast Open is enabled.
     *
     * @return true if TCP Fast Open is enabled
     */
    public boolean isTcpFastOpen() {
        return tcpFastOpen;
    }

    /**
     * Sets this server's identity (certificate chain and private key)
     * directly, bypassing keystore-file loading. Takes priority over
     * {@link #setKeystoreFile}/{@link #setCertFile}+{@link #setKeyFile}
     * when set.
     *
     * @param serverCredentials the server credentials
     */
    public void setServerCredentials(ServerCredentials serverCredentials) {
        this.serverCredentials = serverCredentials;
    }

    /**
     * Returns this server's identity.
     *
     * @return the server credentials, or null if not set
     */
    public ServerCredentials getServerCredentials() {
        return serverCredentials;
    }

    /**
     * Sets an SNI-based server credential resolver directly, bypassing
     * {@link #setSniHostnames}'s keystore-alias-based dispatch. Takes
     * priority over {@link #getServerCredentials}'s fixed value.
     *
     * @param serverCredentialsResolver the resolver
     */
    public void setServerCredentialsResolver(ServerCredentialsResolver serverCredentialsResolver) {
        this.serverCredentialsResolver = serverCredentialsResolver;
    }

    /**
     * Returns the SNI-based server credential resolver.
     *
     * @return the resolver, or null if not set
     */
    public ServerCredentialsResolver getServerCredentialsResolver() {
        return serverCredentialsResolver;
    }

    /**
     * Sets the client's own identity (certificate chain and private key)
     * to present if a server requests client certificate authentication
     * (mTLS). Client role only; most clients never need this.
     *
     * @param clientCredentials the client's own credentials
     */
    public void setClientCredentials(ServerCredentials clientCredentials) {
        this.clientCredentials = clientCredentials;
    }

    /**
     * Returns the client's own identity for mTLS.
     *
     * @return the client credentials, or null if not set
     */
    public ServerCredentials getClientCredentials() {
        return clientCredentials;
    }

    /**
     * Sets a custom trust manager for TLS certificate verification.
     *
     * <p>When set, this trust manager is used in preference to a
     * configured truststore or the JVM default trust store.
     *
     * @param trustManager the trust manager, or null to use defaults
     */
    public void setTrustManager(X509TrustManager trustManager) {
        this.trustManager = trustManager;
    }

    /**
     * Returns the configured trust manager.
     *
     * @return the trust manager, or null if using JVM defaults
     */
    public X509TrustManager getTrustManager() {
        return trustManager;
    }

    // -- Lifecycle --

    @Override
    public void start() {
        super.start();
        try {
            if (serverCredentials == null && serverCredentialsResolver == null) {
                if (certFile != null && keyFile != null) {
                    serverCredentials = PemCredentials.loadServerCredentials(certFile, keyFile);
                } else if (keystoreFile != null && keystorePass != null) {
                    if (isSNIEnabled()) {
                        KeyStore keyStore = TLSUtils.loadKeyStore(keystoreFile, keystorePass, keystoreFormat);
                        serverCredentialsResolver = new SniCredentialsResolver(
                                keyStore, keystorePass, sniHostnameToAlias, sniDefaultAlias);
                        if (LOGGER.isLoggable(Level.INFO)) {
                            LOGGER.info(MessageFormat.format(
                                    Gumdrop.L10N.getString("info.sni_enabled"),
                                    sniHostnameToAlias.size()));
                        }
                    } else {
                        serverCredentials = TLSUtils.loadServerCredentials(keystoreFile, keystorePass, keystoreFormat);
                    }
                }
            }
            effectiveTrustManager = resolveTrustManager();
        } catch (Exception e) {
            RuntimeException e2 = new RuntimeException(
                    "Failed to initialise TLS configuration");
            e2.initCause(e);
            throw e2;
        }
        if (tlsVersion == TlsVersion.TLS_1_2) {
            resolvedTls12CipherSuites = resolveTls12CipherSuites(cipherSuites);
            if (namedGroups != null && !namedGroups.isEmpty() && LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("namedGroups is meaningless under TLS_1_2 (fixed to secp256r1 ECDHE); ignoring \""
                        + namedGroups + "\"");
            }
        } else {
            resolvedCipherSuites = resolveCipherSuites(cipherSuites);
            resolvedNamedGroups = resolveNamedGroups(namedGroups);
        }
    }

    /**
     * Resolves the effective trust manager, in priority order: an
     * explicitly set {@link #trustManager}; a configured truststore file;
     * otherwise the JVM's own default trust store -- always non-null,
     * since a null trust manager must not silently fall back to platform trust)
     * {@link org.bluezoo.gumdrop.crypto.CertificateVerifier} always needs
     * a real trust manager to call. A configured
     * {@link #pinnedCertFingerprint} wraps whichever of those was chosen.
     */
    private X509TrustManager resolveTrustManager() throws Exception {
        X509TrustManager base;
        if (trustManager != null) {
            base = trustManager;
        } else if (truststoreFile != null && truststorePass != null) {
            base = firstX509TrustManager(TLSUtils.loadTrustManagers(truststoreFile, truststorePass, truststoreFormat));
        } else {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            base = firstX509TrustManager(tmf.getTrustManagers());
        }
        if (pinnedCertFingerprint != null) {
            return new PinnedCertTrustManager(base, new String[] { pinnedCertFingerprint });
        }
        return base;
    }

    private static X509TrustManager firstX509TrustManager(TrustManager[] managers) throws Exception {
        for (int i = 0; i < managers.length; i++) {
            if (managers[i] instanceof X509TrustManager) {
                return (X509TrustManager) managers[i];
            }
        }
        throw new java.security.GeneralSecurityException("No X509TrustManager available");
    }

    private static List<CipherSuite> resolveCipherSuites(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<CipherSuite> resolved = new ArrayList<CipherSuite>();
        String[] names = raw.split(":");
        for (int i = 0; i < names.length; i++) {
            String name = names[i].trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                resolved.add(CipherSuite.valueOf(name));
            } catch (IllegalArgumentException e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning("Unrecognised cipher suite \"" + name + "\", ignoring");
                }
            }
        }
        return resolved.isEmpty() ? null : resolved;
    }

    private static List<Tls12CipherSuite> resolveTls12CipherSuites(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<Tls12CipherSuite> resolved = new ArrayList<Tls12CipherSuite>();
        String[] names = raw.split(":");
        for (int i = 0; i < names.length; i++) {
            String name = names[i].trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                resolved.add(Tls12CipherSuite.valueOf(name));
            } catch (IllegalArgumentException e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning("Unrecognised TLS 1.2 cipher suite \"" + name + "\", ignoring");
                }
            }
        }
        return resolved.isEmpty() ? null : resolved;
    }

    private static List<NamedGroup> resolveNamedGroups(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<NamedGroup> resolved = new ArrayList<NamedGroup>();
        String[] names = raw.split(":");
        for (int i = 0; i < names.length; i++) {
            String name = names[i].trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                resolved.add(NamedGroup.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning("Unrecognised named group \"" + name + "\", ignoring");
                }
            }
        }
        return resolved.isEmpty() ? null : resolved;
    }

    // -- Endpoint creation --

    /**
     * Creates a server-side TCPEndpoint for an accepted connection.
     *
     * @param channel the accepted socket channel
     * @param handler the protocol handler
     * @return the new endpoint
     * @throws IOException if initialisation fails
     */
    public TCPEndpoint createServerEndpoint(SocketChannel channel,
                                            ProtocolHandler handler)
            throws IOException {
        return createServerEndpoint(channel, handler, secure);
    }

    /**
     * Creates a server-side TCPEndpoint for an accepted connection, with
     * an explicit choice of whether TLS is active immediately —
     * independent of this factory's own {@link #setSecure(boolean)}
     * setting.
     *
     * <p>Used by protocols where a secondary connection must be
     * TLS-protected from its first byte even though the primary
     * connection this factory was configured for negotiates TLS in-band
     * (e.g. FTP's data connection under RFC 4217 §7 PROT P, opened via a
     * control connection whose own TLS was itself an explicit AUTH TLS
     * upgrade rather than implicit). The server identity is still built
     * from this factory's configured keystore/credentials.
     *
     * @param channel the accepted socket channel
     * @param handler the protocol handler
     * @param secure true if TLS should be active immediately on this
     *      endpoint, regardless of this factory's own secure setting
     * @return the new endpoint
     * @throws IOException if initialisation fails
     */
    public TCPEndpoint createServerEndpoint(SocketChannel channel,
                                            ProtocolHandler handler,
                                            boolean secure)
            throws IOException {
        // A server endpoint needs credentials to present, either directly
        // or via SNI dispatch.
        if (secure && serverCredentials == null && serverCredentialsResolver == null) {
            String message = Gumdrop.L10N.getString("err.no_keystore");
            throw new IOException(
                    "Secure TCP server endpoint requires keystore: " + message);
        }
        // Built whenever TLS is *possible* on this endpoint, not just
        // when secure -- a STARTTLS-capable server starts out plaintext
        // but still needs credentials ready for the in-band upgrade.
        boolean haveCredentials = serverCredentials != null || serverCredentialsResolver != null;
        TCPEndpoint endpoint;
        if (tlsVersion == TlsVersion.TLS_1_2) {
            Tls12HandshakeConfig config12 = haveCredentials ? buildServerConfig12() : null;
            if (secure && config12 == null) {
                throw new IOException(
                        "No TLS configuration configured on this transport factory; "
                                + "cannot create a secure endpoint");
            }
            endpoint = new TCPEndpoint(handler, config12, secure);
        } else {
            HandshakeConfig config = haveCredentials ? buildServerConfig() : null;
            if (secure && config == null) {
                throw new IOException(
                        "No TLS configuration configured on this transport factory; "
                                + "cannot create a secure endpoint");
            }
            endpoint = new TCPEndpoint(handler, config, secure);
        }
        endpoint.setFactory(this);
        endpoint.setChannel(channel);
        endpoint.setClientMode(false);
        endpoint.init();
        return endpoint;
    }

    /**
     * Creates a client-side TCPEndpoint and connects to a remote host.
     *
     * <p>The connection is initiated asynchronously. The handler's
     * {@link ProtocolHandler#connected(Endpoint)} callback is invoked
     * when the TCP connection (and optional TLS handshake) completes.
     *
     * @param host the remote host
     * @param port the remote port
     * @param handler the protocol handler
     * @param loop the SelectorLoop to register with
     * @return the new endpoint (connection is still in progress)
     * @throws IOException if the connection cannot be initiated
     */
    public TCPEndpoint connect(InetAddress host, int port,
                               ProtocolHandler handler,
                               SelectorLoop loop) throws IOException {
        return connect(host, port, null, handler, loop);
    }

    /**
     * Creates a client-side TCPEndpoint and connects to a remote host.
     *
     * @param tlsServerNameHint optional TLS/SNI/hostname-verify name; when
     *     {@code null}, derived from {@code host} (loopback literals map to
     *     {@code localhost} to match typical test PKI)
     */
    public TCPEndpoint connect(InetAddress host, int port, String tlsServerNameHint,
                               ProtocolHandler handler,
                               SelectorLoop loop) throws IOException {
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(false);

        // RFC 7413: enable TCP Fast Open to send data in SYN.
        // Use reflection to access jdk.net.ExtendedSocketOptions which
        // may not be available in all build/runtime configurations.
        if (tcpFastOpen) {
            try {
                Class<?> extOpts = Class.forName(
                        "jdk.net.ExtendedSocketOptions");
                // Unchecked: TCP_FASTOPEN_CONNECT is documented by the JDK
                // as a SocketOption<Boolean>; reflection erases that to
                // SocketOption<?>, so the cast can't be statically verified.
                @SuppressWarnings("unchecked")
                SocketOption<Boolean> tfo =
                        (SocketOption<Boolean>)
                                extOpts.getField("TCP_FASTOPEN_CONNECT")
                                        .get(null);
                channel.setOption(tfo, Boolean.TRUE);
            } catch (UnsupportedOperationException e) {
                LOGGER.fine("TCP Fast Open not supported on this "
                        + "platform");
            } catch (ClassNotFoundException e) {
                LOGGER.fine("jdk.net.ExtendedSocketOptions not available");
            } catch (Exception e) {
                LOGGER.log(Level.FINE,
                        "Failed to enable TCP Fast Open", e);
            }
        }

        TCPEndpoint endpoint;
        String tlsServerName = tlsServerNameFor(host, tlsServerNameHint);
        if (tlsVersion == TlsVersion.TLS_1_2) {
            Tls12HandshakeConfig config12 = secure ? buildClientConfig12(tlsServerName) : null;
            endpoint = new TCPEndpoint(handler, config12, secure);
        } else {
            HandshakeConfig config = secure ? buildClientConfig(tlsServerName) : null;
            endpoint = new TCPEndpoint(handler, config, secure);
        }
        endpoint.setFactory(this);
        endpoint.setChannel(channel);
        endpoint.setClientMode(true);
        endpoint.init();

        Gumdrop gumdrop = Gumdrop.getInstance();
        gumdrop.addChannelHandler(endpoint);

        InetSocketAddress remote = new InetSocketAddress(host, port);
        boolean connected = channel.connect(remote);

        if (connected) {
            // The OS completed the connect synchronously (common for
            // loopback/localhost, especially to a port the peer only just
            // started listening on -- e.g. an FTP data connection to a
            // freshly-opened PASV port). SelectorLoop.doTcpEndpointConnect()
            // never runs in this case (there is no OP_CONNECT event to
            // fire), so its two calls have to happen here instead --
            // otherwise handler.connected() and the client TLS handshake
            // never start at all, silently hanging any protocol that
            // waits for either (issue: PROT P data connections to a local
            // broker/server would connect but never progress).
            //
            // This connect() method can itself be called off the
            // SelectorLoop thread (e.g. AMQPClientRecovery's reconnect
            // runs on its own scheduled-executor thread, not any
            // SelectorLoop), so the ProtocolHandler callbacks below --
            // which the framework's contract guarantees always run on the
            // Endpoint's own SelectorLoop thread -- must be dispatched via
            // invokeLater() rather than called inline; invokeLater() runs
            // them immediately only when already on that thread.
            endpoint.setSelectorLoop(loop);
            loop.invokeLater(new Runnable() {
                @Override
                public void run() {
                    endpoint.connected();
                    endpoint.initiateClientTLSHandshake();
                }
            });
            loop.register(channel, endpoint);
        } else {
            loop.registerForConnect(channel, endpoint);
        }

        return endpoint;
    }

    /**
     * Creates a client-side TCPEndpoint and connects to a UNIX domain
     * socket, mirroring {@link TCPListener#setPath} on the server side:
     * {@link StandardProtocolFamily#UNIX} instead of a TCP port.
     *
     * <p>The connection is initiated asynchronously, with the same
     * endpoint setup/registration as {@link #connect(InetAddress, int,
     * ProtocolHandler, SelectorLoop)}. TLS remains orthogonal to the
     * addressing mode -- if this factory is secure, the TLS handshake
     * proceeds the same way over the connected UNIX domain socket channel
     * as it would over TCP; since a filesystem path has no meaningful
     * hostname/port for SNI, the configuration is built without one.
     *
     * @param path the UNIX domain socket path
     * @param handler the protocol handler
     * @param loop the SelectorLoop to register with
     * @return the new endpoint (connection is still in progress)
     * @throws IOException if the connection cannot be initiated
     */
    public TCPEndpoint connect(String path, ProtocolHandler handler,
                               SelectorLoop loop) throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.configureBlocking(false);

        TCPEndpoint endpoint;
        if (tlsVersion == TlsVersion.TLS_1_2) {
            Tls12HandshakeConfig config12 = secure ? buildClientConfig12(null) : null;
            endpoint = new TCPEndpoint(handler, config12, secure);
        } else {
            HandshakeConfig config = secure ? buildClientConfig(null) : null;
            endpoint = new TCPEndpoint(handler, config, secure);
        }
        endpoint.setFactory(this);
        endpoint.setChannel(channel);
        endpoint.setClientMode(true);
        endpoint.init();

        Gumdrop gumdrop = Gumdrop.getInstance();
        gumdrop.addChannelHandler(endpoint);

        UnixDomainSocketAddress remote = UnixDomainSocketAddress.of(Path.of(path));
        boolean connected = channel.connect(remote);

        if (connected) {
            // See the InetAddress overload above for why this must be
            // dispatched via invokeLater() rather than called inline --
            // the same OS-completed-synchronously/off-loop-thread
            // reasoning applies identically to a UNIX domain socket.
            endpoint.setSelectorLoop(loop);
            loop.invokeLater(new Runnable() {
                @Override
                public void run() {
                    endpoint.connected();
                    endpoint.initiateClientTLSHandshake();
                }
            });
            loop.register(channel, endpoint);
        } else {
            loop.registerForConnect(channel, endpoint);
        }

        return endpoint;
    }

    // -- HandshakeConfig construction --

    private HandshakeConfig buildServerConfig() {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.SERVER);
        config.setServerCredentials(serverCredentials);
        config.setServerCredentialsResolver(serverCredentialsResolver);
        if (needClientAuth) {
            // JSSE's own setNeedClientAuth had no "want but don't require"
            // mode either, so this maps onto exactly the same observable
            // behavior as before.
            config.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
            config.setClientTrustManager(effectiveTrustManager);
        }
        applyCommonConfig(config);
        return config;
    }

    private HandshakeConfig buildClientConfig(String serverName) {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT);
        config.setServerName(serverName);
        config.setTrustManager(effectiveTrustManager);
        // A keystore/PEM identity configured via setKeystoreFile/setCertFile
        // is loaded into serverCredentials regardless of which role this
        // factory ends up used for (start() has no way to know in advance).
        // When this factory is actually used as a client and no explicit
        // clientCredentials was set, present that loaded identity as the
        // client's own certificate -- matching the old JSSE behaviour,
        // where a single KeyManager built from the same keystore served
        // either role depending on which side the SSLEngine was created
        // for.
        ServerCredentials ownCredentials = (clientCredentials != null) ? clientCredentials : serverCredentials;
        if (ownCredentials != null) {
            config.setClientCredentials(ownCredentials);
        }
        applyCommonConfig(config);
        return config;
    }

    private void applyCommonConfig(HandshakeConfig config) {
        if (applicationProtocols != null && applicationProtocols.length > 0) {
            config.setApplicationProtocols(Arrays.asList(applicationProtocols));
        }
        if (resolvedCipherSuites != null) {
            config.setCipherSuites(resolvedCipherSuites);
        }
        if (resolvedNamedGroups != null) {
            config.setNamedGroups(resolvedNamedGroups);
        }
    }

    // -- Tls12HandshakeConfig construction --

    private Tls12HandshakeConfig buildServerConfig12() {
        Tls12HandshakeConfig config = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        config.setServerCredentials(serverCredentials);
        config.setServerCredentialsResolver(serverCredentialsResolver);
        if (needClientAuth) {
            config.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
            config.setClientTrustManager(effectiveTrustManager);
        }
        applyCommonConfig12(config);
        return config;
    }

    private Tls12HandshakeConfig buildClientConfig12(String serverName) {
        Tls12HandshakeConfig config = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        config.setServerName(serverName);
        config.setTrustManager(effectiveTrustManager);
        // See buildClientConfig's identical comment -- the same
        // keystore/PEM-identity-serves-either-role fallback applies here.
        ServerCredentials ownCredentials = (clientCredentials != null) ? clientCredentials : serverCredentials;
        if (ownCredentials != null) {
            config.setClientCredentials(ownCredentials);
        }
        applyCommonConfig12(config);
        return config;
    }

    private void applyCommonConfig12(Tls12HandshakeConfig config) {
        if (applicationProtocols != null && applicationProtocols.length > 0) {
            config.setApplicationProtocols(Arrays.asList(applicationProtocols));
        }
        if (resolvedTls12CipherSuites != null) {
            config.setCipherSuites(resolvedTls12CipherSuites);
        }
    }

    // -- Registration helpers --

    /**
     * Registers a TCPEndpoint with a SelectorLoop for OP_READ.
     */
    void registerEndpoint(SocketChannel channel, TCPEndpoint endpoint,
                          SelectorLoop loop) {
        loop.register(channel, endpoint);
    }

    /**
     * Registers a TCPEndpoint with a SelectorLoop for OP_CONNECT.
     */
    void registerForConnect(SocketChannel channel, TCPEndpoint endpoint,
                            SelectorLoop loop) {
        loop.registerForConnect(channel, endpoint);
    }

    /**
     * Derives the TLS server name for hostname verification and SNI.
     *
     * <p>Loopback address literals ({@code ::1}, {@code 127.0.0.1}) and the
     * same strings passed as a hostname hint map to {@code localhost}, matching
     * the integration-test PKI and common RFC 6125 practice when dialing
     * loopback by address.
     */
    static String tlsServerNameFor(InetAddress peer, String preferred) {
        if (preferred != null && !preferred.isEmpty()) {
            return normalizeLoopbackTlsName(preferred);
        }
        if (peer != null) {
            if (peer.isLoopbackAddress()) {
                return "localhost";
            }
            return peer.getHostAddress();
        }
        return null;
    }

    private static String normalizeLoopbackTlsName(String name) {
        if ("localhost".equalsIgnoreCase(name)) {
            return "localhost";
        }
        if ("::1".equals(name) || "127.0.0.1".equals(name) || "0:0:0:0:0:0:0:1".equals(name)) {
            return "localhost";
        }
        try {
            if (InetAddress.getByName(name).isLoopbackAddress()) {
                return "localhost";
            }
        } catch (UnknownHostException e) {
            // Not a resolvable literal -- use as-is for SNI / hostname verify.
        }
        return name;
    }

    @Override
    protected String getDescription() {
        return "TCP";
    }

}
