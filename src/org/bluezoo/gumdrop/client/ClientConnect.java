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
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.HttpsRecordEch;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.dns.client.HostsFile;
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
        return prepareTls(secure, tls, factory, null);
    }

    /**
     * As {@link #prepareTls(boolean, TlsConfig, TcpTransportFactory)}, also
     * applying an {@code ECHConfigList} found in DNS (see {@link #discoverEch}).
     * A DNS list takes precedence over {@link TlsConfig#getClientEchConfigListFile()}.
     *
     * @param dnsDiscoveredEchConfigList the list from DNS, or null
     */
    public static TlsConfig prepareTls(boolean secure, TlsConfig tls,
                                           TcpTransportFactory factory,
                                           byte[] dnsDiscoveredEchConfigList) {
        TlsConfig effective = ClientDefaults.effectiveTls(tls);
        factory.setSecure(secure);
        applyToTcpFactory(effective, factory);
        applyTcpClientEch(factory, dnsDiscoveredEchConfigList, effective);
        factory.start();
        return effective;
    }

    /** Receives the outcome of {@link #discoverEch}. */
    public interface EchDiscoveryCallback {
        /**
         * Called exactly once, when it is time to dial.
         *
         * @param echConfigList the {@code ECHConfigList} published in DNS, or
         *        null if discovery is off, does not apply, or found nothing
         */
        void discovered(byte[] echConfigList);
    }

    /**
     * Looks up the DNS HTTPS record of the dial target for an {@code ech}
     * SvcParam (RFC 9848) before a TCP TLS connection, when
     * {@link TlsConfig#clientEchDnsDiscovery} is enabled. The callback runs
     * at once, on the calling thread, when nothing needs looking up: the
     * dial is not secure, discovery is off, or the target is not a hostname
     * (a socket path, an address, a literal IP or {@code localhost}). A
     * failed lookup is not an error; the callback simply gets null.
     *
     * @param gumdrop supplies a selector loop when neither {@code dial} nor a
     *        resolver names one
     * @param secure whether the connection starts TLS immediately
     * @param dial the target
     * @param tls the client's TLS settings (merged with the process default)
     * @param callback told what was found, exactly once
     */
    public static void discoverEch(Gumdrop gumdrop, boolean secure, ClientDial dial, TlsConfig tls,
                                   final EchDiscoveryCallback callback) {
        String host = dial.getHost();
        if (!secure || host == null || isUndiscoverableHost(host)
                || !ClientDefaults.effectiveTls(tls).isClientEchDnsDiscoveryEnabled()) {
            callback.discovered(null);
            return;
        }
        DnsResolver resolver = dial.getDnsResolver();
        if (resolver == null) {
            SelectorLoop loop = dial.getSelectorLoop();
            if (loop == null && gumdrop != null) {
                loop = gumdrop.nextWorkerLoop();
            }
            if (loop == null) {
                callback.discovered(null);
                return;
            }
            resolver = ClientDefaults.dnsResolver(loop, null);
        }
        resolver.queryHTTPS(host, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
                callback.discovered(HttpsRecordEch.firstEchConfigListFromAnswers(response.getAnswers()));
            }

            @Override
            public void onError(String error) {
                callback.discovered(null);
            }
        });
    }

    /**
     * Whether a target name needs no DNS lookup because it is {@code localhost}
     * or a literal IP address.
     */
    public static boolean isUndiscoverableHost(String hostname) {
        if ("localhost".equalsIgnoreCase(hostname) || "localhost.".equalsIgnoreCase(hostname)) {
            return true;
        }
        return HostsFile.parseLiteralIPv4(hostname) != null
                || HostsFile.parseLiteralIPv6(hostname) != null;
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
