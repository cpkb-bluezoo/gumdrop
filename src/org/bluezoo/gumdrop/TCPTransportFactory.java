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

import org.bluezoo.gumdrop.util.EmptyX509TrustManager;
import org.bluezoo.gumdrop.util.SNIKeyManager;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;

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

    // SNI (Server Name Indication) configuration
    private Map<String, String> sniHostnameToAlias;
    private String sniDefaultAlias;

    // Client authentication (defaults to false - no client cert required)
    protected boolean needClientAuth = false;

    // ALPN (Application-Layer Protocol Negotiation) protocols
    private String[] applicationProtocols;

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
                KeyStore ks = KeyStore.getInstance(keystoreFormat);
                InputStream in = new FileInputStream(keystoreFile);
                char[] pass = keystorePass.toCharArray();
                ks.load(in, pass);
                in.close();
                KeyManagerFactory f = KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm());
                f.init(ks, pass);
                sslContext = SSLContext.getInstance("TLS");
                KeyManager[] km = f.getKeyManagers();

                if (isSNIEnabled()) {
                    km = wrapWithSNIKeyManager(km);
                    if (LOGGER.isLoggable(Level.INFO)) {
                        LOGGER.info("SNI enabled with " +
                                sniHostnameToAlias.size() +
                                " hostname mapping(s)");
                    }
                }

                TrustManager[] tm = new TrustManager[1];
                tm[0] = new EmptyX509TrustManager();
                SecureRandom random = new SecureRandom();
                sslContext.init(km, tm, random);
            } catch (Exception e) {
                RuntimeException e2 = new RuntimeException(
                        "Failed to initialise SSL context");
                e2.initCause(e);
                throw e2;
            }
        }
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
     */
    protected void configureServerSSLEngine(SSLEngine engine) {
        engine.setUseClientMode(false);
        if (needClientAuth) {
            engine.setNeedClientAuth(true);
        }
        // Don't request client certificates unless explicitly configured

        SSLParameters params = engine.getSSLParameters();

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
            // TODO: SSLParameters.setNamedGroups() is available from Java 16+
            // and could be used here to configure named groups for JSSE.
            engine.setSSLParameters(params);
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
