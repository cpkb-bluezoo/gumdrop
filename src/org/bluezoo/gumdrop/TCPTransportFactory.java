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

import org.bluezoo.gumdrop.util.PinnedCertTrustManager;
import org.bluezoo.gumdrop.util.SNIKeyManager;
import org.bluezoo.gumdrop.util.TLSUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * TCP transport factory.
 *
 * <p>Creates {@link TCPEndpoint} instances for both server-side (accepted)
 * and client-side (outgoing) connections.
 *
 * <p>For TLS, this factory creates JSSE {@link SSLContext} and
 * {@link SSLEngine} instances. The {@link TransportFactory#setCipherSuites}
 * and {@link TransportFactory#setNamedGroups} configuration is mapped to
 * JSSE {@link SSLParameters}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see TCPEndpoint
 * @see TransportFactory
 */
public class TCPTransportFactory extends TransportFactory {

    private static final Logger LOGGER =
            Logger.getLogger(TCPTransportFactory.class.getName());

    protected SSLContext sslContext;
    private X509TrustManager trustManager;

    // SNI (Server Name Indication) configuration
    private Map<String, String> sniHostnameToAlias;
    private String sniDefaultAlias;

    // Client authentication (defaults to false - no client cert required)
    protected boolean needClientAuth = false;

    // ALPN (Application-Layer Protocol Negotiation) protocols
    private String[] applicationProtocols;

    // RFC 7413: TCP Fast Open for reduced connection latency
    private boolean tcpFastOpen;

    public TCPTransportFactory() {
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
     * Sets an externally-configured SSLContext.
     *
     * @param context the SSL context
     */
    public void setSSLContext(SSLContext context) {
        this.sslContext = context;
    }

    /**
     * Returns the current SSLContext.
     *
     * @return the SSL context, or null if not configured
     */
    public SSLContext getSSLContext() {
        return sslContext;
    }

    /**
     * Sets a custom trust manager for TLS certificate verification.
     *
     * <p>When set, this trust manager is used in preference to the
     * default JVM trust store. The trust manager is injected as a
     * single-element array when building the SSL context during
     * {@link #start()}.
     *
     * @param trustManager the trust manager, or null to use defaults
     */
    public void setTrustManager(X509TrustManager trustManager) {
        this.trustManager = trustManager;
    }

    // -- Lifecycle --

    @Override
    public void start() {
        super.start();

        if (secure && sslContext == null &&
                (keystoreFile == null || keystorePass == null)) {
            String message = Gumdrop.L10N.getString("err.no_keystore");
            throw new RuntimeException(
                    "Secure TCP factory requires keystore: " + message);
        }

        if (sslContext == null && keystoreFile != null &&
                keystorePass != null) {
            try {
                sslContext = SSLContext.getInstance("TLS");
                KeyManager[] km = TLSUtils.loadKeyManagers(
                        keystoreFile, keystorePass, keystoreFormat);

                if (isSNIEnabled()) {
                    km = wrapWithSNIKeyManager(km);
                    if (LOGGER.isLoggable(Level.INFO)) {
                        LOGGER.info("SNI enabled with " +
                                sniHostnameToAlias.size() +
                                " hostname mapping(s)");
                    }
                }

                TrustManager[] tm = loadTrustManagers();
                SecureRandom random = new SecureRandom();
                sslContext.init(km, tm, random);

                // RFC 5077 / RFC 7858 section 3.4: enable TLS session
                // resumption with explicit cache sizing and timeout.
                configureTlsSessionCache(sslContext);
            } catch (Exception e) {
                RuntimeException e2 = new RuntimeException(
                        "Failed to initialise SSL context");
                e2.initCause(e);
                throw e2;
            }
        }
    }

    /**
     * Loads TrustManagers from the configured truststore.
     * If no truststore is configured, returns null (JVM default truststore).
     * When a pinned certificate fingerprint is configured, wraps the
     * trust managers to additionally verify the server's leaf certificate.
     */
    private TrustManager[] loadTrustManagers() throws Exception {
        if (trustManager != null) {
            return new TrustManager[] { trustManager };
        }
        TrustManager[] base = null;
        if (truststoreFile != null && truststorePass != null) {
            base = TLSUtils.loadTrustManagers(
                    truststoreFile, truststorePass, truststoreFormat);
        }

        if (pinnedCertFingerprint != null) {
            if (base == null) {
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                tmf.init((KeyStore) null);
                base = tmf.getTrustManagers();
            }
            TrustManager[] pinned = new TrustManager[base.length];
            for (int i = 0; i < base.length; i++) {
                if (base[i] instanceof X509TrustManager) {
                    pinned[i] = new PinnedCertTrustManager(
                            (X509TrustManager) base[i],
                            new String[] { pinnedCertFingerprint });
                } else {
                    pinned[i] = base[i];
                }
            }
            return pinned;
        }
        return base;
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
        SSLEngine engine = createServerSSLEngine(channel);
        TCPEndpoint endpoint = new TCPEndpoint(handler, engine, secure);
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
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(false);

        // RFC 7413: enable TCP Fast Open to send data in SYN.
        // Use reflection to access jdk.net.ExtendedSocketOptions which
        // may not be available in all build/runtime configurations.
        if (tcpFastOpen) {
            try {
                Class<?> extOpts = Class.forName(
                        "jdk.net.ExtendedSocketOptions");
                @SuppressWarnings("unchecked")
                java.net.SocketOption<Boolean> tfo =
                        (java.net.SocketOption<Boolean>)
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

        SSLEngine engine = null;
        if (sslContext != null) {
            engine = sslContext.createSSLEngine(
                    host.getHostAddress(), port);
            configureClientSSLEngine(engine);
        }

        TCPEndpoint endpoint = new TCPEndpoint(handler, engine, secure);
        endpoint.setFactory(this);
        endpoint.setChannel(channel);
        endpoint.setClientMode(true);
        endpoint.init();

        Gumdrop gumdrop = Gumdrop.getInstance();
        gumdrop.addChannelHandler(endpoint);

        InetSocketAddress remote = new InetSocketAddress(host, port);
        boolean connected = channel.connect(remote);

        if (connected) {
            loop.register(channel, endpoint);
        } else {
            loop.registerForConnect(channel, endpoint);
        }

        return endpoint;
    }

    // -- SSL engine creation --

    /**
     * Creates and configures an SSLEngine for a server-side connection.
     */
    private SSLEngine createServerSSLEngine(SocketChannel channel)
            throws IOException {
        if (sslContext == null) {
            return null;
        }
        InetSocketAddress peer =
                (InetSocketAddress) channel.getRemoteAddress();
        String peerHost = peer.getHostString();
        int peerPort = peer.getPort();
        SSLEngine engine = sslContext.createSSLEngine(peerHost, peerPort);
        configureServerSSLEngine(engine);
        return engine;
    }

    /**
     * Configures an SSL engine for server mode.
     * RFC 9113 section 9.2: HTTP/2 over TLS MUST use TLS 1.2 or later;
     * TLS 1.3 is RECOMMENDED.
     */
    private static final String[] SECURE_PROTOCOLS =
            { "TLSv1.2", "TLSv1.3" };

    protected void configureServerSSLEngine(SSLEngine engine) {
        engine.setUseClientMode(false);
        if (needClientAuth) {
            engine.setNeedClientAuth(true);
        }

        SSLParameters params = engine.getSSLParameters();
        params.setProtocols(SECURE_PROTOCOLS);

        if (isSNIEnabled()) {
            params.setSNIMatchers(java.util.Collections.singleton(
                    new SNIMatcher(StandardConstants.SNI_HOST_NAME) {
                        @Override
                        public boolean matches(
                                javax.net.ssl.SNIServerName serverName) {
                            if (serverName instanceof SNIHostName) {
                                String hostname =
                                        ((SNIHostName) serverName)
                                                .getAsciiName();
                                if (LOGGER.isLoggable(Level.FINE)) {
                                    LOGGER.fine("SNI hostname: " + hostname);
                                }
                            }
                            return true;
                        }
                    }));
        }

        // Configure ALPN protocols for HTTP/2 support
        if (applicationProtocols != null && applicationProtocols.length > 0) {
            java.util.List<String> protocols =
                    java.util.Arrays.asList(applicationProtocols);
            params.setApplicationProtocols(protocols.toArray(new String[0]));
        }

        engine.setSSLParameters(params);
        applyCipherConfig(engine);
    }

    /**
     * Configures an SSL engine for client mode.
     */
    protected void configureClientSSLEngine(SSLEngine engine) {
        engine.setUseClientMode(true);
        SSLParameters params = engine.getSSLParameters();
        params.setProtocols(SECURE_PROTOCOLS);
        engine.setSSLParameters(params);
        applyCipherConfig(engine);
    }

    /**
     * Applies cipher suite and named group configuration to an SSL engine.
     */
    private void applyCipherConfig(SSLEngine engine) {
        if (cipherSuites != null || namedGroups != null) {
            SSLParameters params = engine.getSSLParameters();
            if (cipherSuites != null) {
                String[] suites = splitOnColon(cipherSuites);
                params.setCipherSuites(suites);
            }
            if (namedGroups != null) {
                String[] groups = splitOnColon(namedGroups);
                applyNamedGroups(params, groups);
            }
            engine.setSSLParameters(params);
        }
    }

    /**
     * Applies named groups via reflection (available since Java 20).
     * Silently ignored on older runtimes.
     */
    private static void applyNamedGroups(SSLParameters params,
                                         String[] groups) {
        try {
            java.lang.reflect.Method m = SSLParameters.class
                    .getMethod("setNamedGroups", String[].class);
            m.invoke(params, (Object) groups);
        } catch (NoSuchMethodException e) {
            LOGGER.fine("SSLParameters.setNamedGroups() not available"
                    + " on this JDK; namedGroups setting ignored");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Failed to set named groups", e);
        }
    }

    private KeyManager[] wrapWithSNIKeyManager(KeyManager[] keyManagers) {
        KeyManager[] wrapped = new KeyManager[keyManagers.length];
        for (int i = 0; i < keyManagers.length; i++) {
            if (keyManagers[i] instanceof X509KeyManager) {
                wrapped[i] = new SNIKeyManager(
                        (X509KeyManager) keyManagers[i],
                        sniHostnameToAlias,
                        sniDefaultAlias);
            } else {
                wrapped[i] = keyManagers[i];
            }
        }
        return wrapped;
    }

    /**
     * Splits a colon-separated string into an array.
     */
    private static String[] splitOnColon(String s) {
        int count = 1;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) == ':') {
                count++;
            }
        }
        String[] result = new String[count];
        int idx = 0;
        int start = 0;
        for (int i = 0; i < len; i++) {
            if (s.charAt(i) == ':') {
                result[idx++] = s.substring(start, i);
                start = i + 1;
            }
        }
        result[idx] = s.substring(start);
        return result;
    }

    // -- TLS session resumption --

    // RFC 5077, RFC 7858 section 3.4: TLS session cache parameters
    private static final int SESSION_CACHE_SIZE = 1024;
    private static final int SESSION_TIMEOUT_SECONDS = 86400;

    /**
     * Configures TLS session caching for session resumption.
     * RFC 5077: TLS session tickets allow servers to resume sessions
     * without storing per-client state. RFC 7858 section 3.4 says DoT
     * servers SHOULD enable fast TLS session resumption.
     *
     * <p>The Java TLS stack supports both session ID caching and session
     * tickets natively. This method explicitly configures cache size and
     * timeout to ensure resumption is active on all JVM implementations.
     */
    private static void configureTlsSessionCache(SSLContext ctx) {
        javax.net.ssl.SSLSessionContext serverCtx =
                ctx.getServerSessionContext();
        if (serverCtx != null) {
            serverCtx.setSessionCacheSize(SESSION_CACHE_SIZE);
            serverCtx.setSessionTimeout(SESSION_TIMEOUT_SECONDS);
        }
        javax.net.ssl.SSLSessionContext clientCtx =
                ctx.getClientSessionContext();
        if (clientCtx != null) {
            clientCtx.setSessionCacheSize(SESSION_CACHE_SIZE);
            clientCtx.setSessionTimeout(SESSION_TIMEOUT_SECONDS);
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

    @Override
    protected String getDescription() {
        return "TCP";
    }

}
