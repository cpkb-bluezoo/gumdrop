/*
 * ClientConnect.java
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

package org.bluezoo.gumdrop.client;

import java.io.IOException;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.tls.EchClientBootstrap;
import org.bluezoo.gumdrop.tls.EchConfig;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

/**
 * Shared TCP connect path for {@link ClientDial} + {@link TlsConfig} facades.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ClientConnect {

    private static final Logger LOGGER =
            Logger.getLogger(ClientConnect.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.L10N");

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
            LOGGER.warning(L10N.getString("warn.tls_peer_verification_disabled"));
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
        applyTcpClientEch(factory, null, tls);
    }

    /**
     * Applies DNS-discovered and file-based client ECH onto a TCP factory.
     */
    public static void applyTcpClientEch(TcpTransportFactory factory, byte[] dnsDiscoveredEchConfigList,
            TlsConfig tls) {
        if (factory == null || tls == null) {
            return;
        }
        EchConfig ech = EchClientBootstrap.selectConfig(dnsDiscoveredEchConfigList, tls.getClientEchConfigListFile());
        if (ech != null) {
            factory.setClientEchConfig(ech);
        }
        if (tls.isClientEchGreaseEnabled()) {
            factory.setClientEchGreaseEnabled(true);
        }
    }

    public static void applyQuicClientEch(QuicTransportFactory factory, byte[] dnsDiscoveredEchConfigList,
            TlsConfig tls) {
        if (factory == null || tls == null) {
            return;
        }
        EchConfig ech = EchClientBootstrap.selectConfig(dnsDiscoveredEchConfigList, tls.getClientEchConfigListFile());
        if (ech != null) {
            factory.setClientEchConfig(ech);
        }
        if (tls.isClientEchGreaseEnabled()) {
            factory.setClientEchGreaseEnabled(true);
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
            LOGGER.warning(L10N.getString("warn.tls_peer_verification_disabled"));
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

    public static ClientEndpoint openAndConnect(Gumdrop gumdrop,
                                                ClientDial dial,
                                                TcpTransportFactory factory,
                                                ProtocolHandler handler)
            throws IOException {
        ClientEndpoint endpoint = dial.openEndpoint(factory);
        endpoint.connect(gumdrop, handler);
        return endpoint;
    }

    /**
     * Full TCP dial: effective TLS, endpoint open, connect.
     *
     * @return the effective TLS config
     */
    public static TlsConfig connect(Gumdrop gumdrop,
                                          boolean secure,
                                          ClientDial dial,
                                          TlsConfig tls,
                                          TcpTransportFactory factory,
                                          ProtocolHandler handler)
            throws IOException {
        TlsConfig effective = prepareTls(secure, tls, factory);
        openAndConnect(gumdrop, dial, factory, handler);
        return effective;
    }

}
