/*
 * ClientConnect.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.client;

import java.io.IOException;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

/**
 * Shared TCP connect path for {@link ClientDial} + {@link TlsConfig} facades.
 */
public final class ClientConnect {

    private static final Logger LOGGER =
            Logger.getLogger(ClientConnect.class.getName());

    private ClientConnect() {
    }

    /**
     * Applies {@link ClientDefaults#effectiveTls}, sets the immediacy flag,
     * configures the factory, and starts it. Does not connect.
     *
     * @param secure whether the connection starts TLS immediately — a
     *               client-owned decision, independent of {@code tls}'s
     *               material (see the {@link TlsConfig} class javadoc)
     */
    public static TlsConfig prepareTls(boolean secure, TlsConfig tls,
                                           TcpTransportFactory factory) {
        TlsConfig effective = ClientDefaults.effectiveTls(tls);
        factory.setSecure(secure);
        applyToTcpFactory(effective, factory);
        factory.start();
        return effective;
    }

    public static void applyToTcpFactory(TlsConfig tls,
                                         TcpTransportFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        if (tls == null) {
            throw new NullPointerException("tls");
        }
        if (tls.getServerCredentials() != null) {
            factory.setClientCredentials(tls.getServerCredentials());
        }
        if (tls.getTrustManager() != null) {
            factory.setTrustManager(tls.getTrustManager());
        } else if (!tls.isVerifyPeer()) {
            LOGGER.warning("TLS peer verification disabled");
            factory.setTrustManager(new EmptyX509TrustManager());
        }
        if (tls.getKeystoreFile() != null) {
            factory.setKeystoreFile(tls.getKeystoreFile());
        }
        if (tls.getKeystorePass() != null) {
            factory.setKeystorePass(tls.getKeystorePass());
        }
        if (tls.getKeystoreFormat() != null) {
            factory.setKeystoreFormat(tls.getKeystoreFormat());
        }
        if (tls.getCertFile() != null) {
            factory.setCertFile(tls.getCertFile());
        }
        if (tls.getKeyFile() != null) {
            factory.setKeyFile(tls.getKeyFile());
        }
    }

    public static void applyToQuicFactory(TlsConfig tls,
                                          QuicTransportFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        if (tls == null) {
            throw new NullPointerException("tls");
        }
        if (tls.getTrustManager() != null) {
            factory.setTrustManager(tls.getTrustManager());
        } else if (!tls.isVerifyPeer()) {
            LOGGER.warning("TLS peer verification disabled");
            factory.setTrustManager(new EmptyX509TrustManager());
        }
        if (tls.getKeystoreFile() != null) {
            factory.setKeystoreFile(tls.getKeystoreFile());
        }
        if (tls.getKeystorePass() != null) {
            factory.setKeystorePass(tls.getKeystorePass());
        }
        if (tls.getKeystoreFormat() != null) {
            factory.setKeystoreFormat(tls.getKeystoreFormat());
        }
        if (tls.getCertFile() != null) {
            factory.setCertFile(tls.getCertFile());
        }
        if (tls.getKeyFile() != null) {
            factory.setKeyFile(tls.getKeyFile());
        }
        factory.setVerifyPeer(tls.isVerifyPeer());
    }

    public static ClientEndpoint openAndConnect(ClientDial dial,
                                                TcpTransportFactory factory,
                                                ProtocolHandler handler)
            throws IOException {
        ClientEndpoint endpoint = dial.openEndpoint(factory);
        endpoint.connect(handler);
        return endpoint;
    }

    /**
     * Full TCP dial: effective TLS, endpoint open, connect.
     *
     * @return the effective TLS config
     */
    public static TlsConfig connect(boolean secure,
                                          ClientDial dial,
                                          TlsConfig tls,
                                          TcpTransportFactory factory,
                                          ProtocolHandler handler)
            throws IOException {
        TlsConfig effective = prepareTls(secure, tls, factory);
        openAndConnect(dial, factory, handler);
        return effective;
    }

}
