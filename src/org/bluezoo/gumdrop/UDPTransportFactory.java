/*
 * UDPTransportFactory.java
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
import org.bluezoo.gumdrop.quic.tls.PemCredentials;
import org.bluezoo.gumdrop.tls.CipherSuite;
import org.bluezoo.gumdrop.tls.ClientAuthPolicy;
import org.bluezoo.gumdrop.tls.Dtls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.Dtls13HandshakeConfig;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.ServerCredentialsResolver;
import org.bluezoo.gumdrop.tls.Tls12CipherSuite;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.util.SniCredentialsResolver;
import org.bluezoo.gumdrop.util.TLSUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.security.KeyStore;
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
 * UDP transport factory using the in-tree DTLS 1.2/1.3 engines.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see UDPEndpoint
 */
public class UDPTransportFactory extends TransportFactory {

    private static final Logger LOGGER =
            Logger.getLogger(UDPTransportFactory.class.getName());

    private ServerCredentials serverCredentials;
    private ServerCredentialsResolver serverCredentialsResolver;
    private ServerCredentials clientCredentials;
    private X509TrustManager trustManager;
    private X509TrustManager effectiveTrustManager;

    private Map<String, String> sniHostnameToAlias;
    private String sniDefaultAlias;
    protected boolean needClientAuth = false;
    private String[] applicationProtocols;

    private List<Tls12CipherSuite> resolvedTls12CipherSuites;
    private List<CipherSuite> resolvedCipherSuites;
    private List<NamedGroup> resolvedNamedGroups;
    private DtlsVersion dtlsVersion = DtlsVersion.DTLS_1_2;

    private boolean requireCookie;
    private byte[] cookieSecret;
    private int maxFragmentSize = 1024;

    private Dtls12HandshakeConfig sharedServerConfig;
    private Dtls13HandshakeConfig sharedServerConfig13;

    public UDPTransportFactory() {
    }

    public DtlsVersion getDtlsVersion() {
        return dtlsVersion;
    }

    public void setDtlsVersion(DtlsVersion dtlsVersion) {
        this.dtlsVersion = (dtlsVersion != null) ? dtlsVersion : DtlsVersion.DTLS_1_2;
    }

    public void setServerCredentials(ServerCredentials serverCredentials) {
        this.serverCredentials = serverCredentials;
    }

    public void setServerCredentialsResolver(ServerCredentialsResolver serverCredentialsResolver) {
        this.serverCredentialsResolver = serverCredentialsResolver;
    }

    public void setClientCredentials(ServerCredentials clientCredentials) {
        this.clientCredentials = clientCredentials;
    }

    public void setTrustManager(X509TrustManager trustManager) {
        this.trustManager = trustManager;
    }

    public void setApplicationProtocols(String[] applicationProtocols) {
        this.applicationProtocols = applicationProtocols != null
                ? applicationProtocols.clone() : null;
    }

    public void setSniHostnames(Map<String, String> sniHostnameToAlias) {
        this.sniHostnameToAlias = sniHostnameToAlias;
    }

    public void setSniDefaultAlias(String sniDefaultAlias) {
        this.sniDefaultAlias = sniDefaultAlias;
    }

    public void setNeedClientAuth(boolean needClientAuth) {
        this.needClientAuth = needClientAuth;
    }

    /**
     * Requires a valid RFC 6347 cookie before allocating server-side
     * handshake state. Defaults to false.
     */
    public void setRequireCookie(boolean requireCookie) {
        this.requireCookie = requireCookie;
    }

    public void setCookieSecret(byte[] cookieSecret) {
        this.cookieSecret = cookieSecret != null ? cookieSecret.clone() : null;
    }

    public void setMaxFragmentSize(int maxFragmentSize) {
        this.maxFragmentSize = maxFragmentSize;
    }

    public boolean isSNIEnabled() {
        return sniHostnameToAlias != null && !sniHostnameToAlias.isEmpty();
    }

    @Override
    public void start() {
        super.start();

        if (secure && serverCredentials == null && serverCredentialsResolver == null
                && (keystoreFile == null || keystorePass == null)) {
            String message = Gumdrop.L10N.getString("err.no_keystore");
            throw new RuntimeException(
                    "Secure UDP factory requires keystore: " + message);
        }

        try {
            if (serverCredentials == null && serverCredentialsResolver == null) {
                if (certFile != null && keyFile != null) {
                    serverCredentials = PemCredentials.loadServerCredentials(certFile, keyFile);
                } else if (keystoreFile != null && keystorePass != null) {
                    if (isSNIEnabled()) {
                        KeyStore keyStore = TLSUtils.loadKeyStore(keystoreFile, keystorePass, keystoreFormat);
                        serverCredentialsResolver = new SniCredentialsResolver(
                                keyStore, keystorePass, sniHostnameToAlias, sniDefaultAlias);
                    } else {
                        serverCredentials = TLSUtils.loadServerCredentials(keystoreFile, keystorePass, keystoreFormat);
                    }
                }
            }
            effectiveTrustManager = resolveTrustManager();
            resolvedTls12CipherSuites = resolveTls12CipherSuites(cipherSuites);
            resolvedCipherSuites = resolveCipherSuites(cipherSuites);
            resolvedNamedGroups = resolveNamedGroups(namedGroups);
            if (namedGroups != null && !namedGroups.isEmpty() && dtlsVersion == DtlsVersion.DTLS_1_2
                    && LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("namedGroups is meaningless under DTLS_1_2; ignoring \"" + namedGroups + "\"");
            }
            if (secure && dtlsVersion == DtlsVersion.DTLS_1_2) {
                sharedServerConfig = buildServerConfig12();
            } else if (secure && dtlsVersion == DtlsVersion.DTLS_1_3) {
                sharedServerConfig13 = buildServerConfig13();
            }
        } catch (Exception e) {
            RuntimeException e2 = new RuntimeException("Failed to initialise DTLS configuration");
            e2.initCause(e);
            throw e2;
        }
    }

    Dtls12HandshakeConfig getSharedServerConfig() {
        return sharedServerConfig;
    }

    Dtls13HandshakeConfig getSharedServerConfig13() {
        return sharedServerConfig13;
    }

    Dtls12HandshakeConfig buildClientConfig12(String serverName) {
        Tls12HandshakeConfig base = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        base.setServerName(serverName);
        base.setTrustManager(effectiveTrustManager);
        ServerCredentials ownCredentials = (clientCredentials != null) ? clientCredentials : serverCredentials;
        if (ownCredentials != null) {
            base.setClientCredentials(ownCredentials);
        }
        applyCommonConfig12(base);
        Dtls12HandshakeConfig config = new Dtls12HandshakeConfig(base);
        applyDtlsSettings(config);
        return config;
    }

    Dtls13HandshakeConfig buildClientConfig13(String serverName) {
        HandshakeConfig base = new HandshakeConfig(HandshakeRole.CLIENT);
        base.setServerName(serverName);
        base.setTrustManager(effectiveTrustManager);
        ServerCredentials ownCredentials = (clientCredentials != null) ? clientCredentials : serverCredentials;
        if (ownCredentials != null) {
            base.setClientCredentials(ownCredentials);
        }
        applyCommonConfig13(base);
        Dtls13HandshakeConfig config = new Dtls13HandshakeConfig(base);
        applyDtlsSettings13(config);
        return config;
    }

    private Dtls13HandshakeConfig buildServerConfig13() {
        HandshakeConfig base = new HandshakeConfig(HandshakeRole.SERVER);
        base.setServerCredentials(serverCredentials);
        base.setServerCredentialsResolver(serverCredentialsResolver);
        if (needClientAuth) {
            base.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
            base.setClientTrustManager(effectiveTrustManager);
        }
        applyCommonConfig13(base);
        Dtls13HandshakeConfig config = new Dtls13HandshakeConfig(base);
        applyDtlsSettings13(config);
        return config;
    }

    private void applyCommonConfig13(HandshakeConfig config) {
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

    private void applyDtlsSettings13(Dtls13HandshakeConfig config) {
        config.setRequireCookie(requireCookie);
        config.setCookieSecret(cookieSecret);
        config.setMaxFragmentSize(maxFragmentSize);
    }

    private Dtls12HandshakeConfig buildServerConfig12() {
        Tls12HandshakeConfig base = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        base.setServerCredentials(serverCredentials);
        base.setServerCredentialsResolver(serverCredentialsResolver);
        if (needClientAuth) {
            base.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
            base.setClientTrustManager(effectiveTrustManager);
        }
        applyCommonConfig12(base);
        Dtls12HandshakeConfig config = new Dtls12HandshakeConfig(base);
        applyDtlsSettings(config);
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

    private void applyDtlsSettings(Dtls12HandshakeConfig config) {
        config.setRequireCookie(requireCookie);
        config.setCookieSecret(cookieSecret);
        config.setMaxFragmentSize(maxFragmentSize);
    }

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

    public UDPEndpoint createServerEndpoint(InetAddress bindAddress,
                                                  int port,
                                                  ProtocolHandler handler)
            throws IOException {
        return createServerEndpoint(bindAddress, port, handler, null);
    }

    public UDPEndpoint createServerEndpoint(InetAddress bindAddress,
                                                  int port,
                                                  ProtocolHandler handler,
                                                  SelectorLoop loop)
            throws IOException {
        UDPEndpoint endpoint = new UDPEndpoint(handler);
        endpoint.setFactory(this);
        endpoint.setSecure(secure);
        endpoint.setClientMode(false);

        DatagramChannel channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);

        InetSocketAddress socketAddress =
                new InetSocketAddress(bindAddress, port);
        channel.bind(socketAddress);

        endpoint.setChannel(channel);
        endpoint.init();

        Gumdrop gumdrop = Gumdrop.getInstance();
        SelectorLoop workerLoop =
                (loop != null) ? loop : gumdrop.nextWorkerLoop();
        workerLoop.registerDatagram(channel, endpoint);
        gumdrop.addChannelHandler(endpoint);

        handler.connected(endpoint);

        return endpoint;
    }

    public UDPEndpoint createServerEndpoint(DatagramChannel channel,
                                                  ProtocolHandler handler)
            throws IOException {
        UDPEndpoint endpoint = new UDPEndpoint(handler);
        endpoint.setFactory(this);
        endpoint.setSecure(secure);
        endpoint.setClientMode(false);

        endpoint.setChannel(channel);
        endpoint.init();

        Gumdrop gumdrop = Gumdrop.getInstance();
        SelectorLoop workerLoop = gumdrop.nextWorkerLoop();
        workerLoop.registerDatagram(channel, endpoint);
        gumdrop.addChannelHandler(endpoint);

        handler.connected(endpoint);

        return endpoint;
    }

    public UDPEndpoint connect(InetAddress host, int port,
                                    ProtocolHandler handler)
            throws IOException {
        return connect(host, port, handler, null);
    }

    public UDPEndpoint connect(InetAddress host, int port,
                                    ProtocolHandler handler,
                                    SelectorLoop loop)
            throws IOException {
        UDPEndpoint endpoint = new UDPEndpoint(handler);
        endpoint.setFactory(this);
        endpoint.setSecure(secure);
        endpoint.setClientMode(true);

        InetSocketAddress remoteAddress = new InetSocketAddress(host, port);
        endpoint.setRemoteAddress(remoteAddress);

        DatagramChannel channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.connect(remoteAddress);

        endpoint.setChannel(channel);
        endpoint.init();

        Gumdrop gumdrop = Gumdrop.getInstance();
        final SelectorLoop workerLoop = (loop != null) ? loop : gumdrop.nextWorkerLoop();
        workerLoop.registerDatagram(channel, endpoint);
        gumdrop.addChannelHandler(endpoint);

        final UDPEndpoint endpointForCallback = endpoint;
        workerLoop.invokeLater(new Runnable() {
            @Override
            public void run() {
                handler.connected(endpointForCallback);
                endpointForCallback.startClientDtlsHandshake();
            }
        });

        return endpoint;
    }

    @Override
    protected String getDescription() {
        return "UDP";
    }

}
