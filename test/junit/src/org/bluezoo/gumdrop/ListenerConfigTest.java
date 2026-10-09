/*
 * ListenerConfigTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.bluezoo.gumdrop.util.CidrNetwork;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;
import org.junit.Test;

/**
 * Unit tests for the configuration surface and connection admission logic
 * of {@link Listener}, {@link TcpListener}, {@link UdpListener} and
 * {@link TlsConfigSupport}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ListenerConfigTest {

    private static final class NoopHandler implements ProtocolHandler {
        @Override public void receive(ByteBuffer data) { }
        @Override public void connected(Endpoint endpoint) { }
        @Override public void securityEstablished(SecurityInfo info) { }
        @Override public void disconnected() { }
        @Override public void error(Exception cause) { }
    }

    private static final class TestTcpListener extends TcpListener {
        @Override
        protected ProtocolHandler createHandler() {
            return new NoopHandler();
        }

        @Override
        public String getDescription() {
            return "test-tcp";
        }

        @Override
        public int getPort() {
            return 0;
        }
    }

    private static final class TestUdpListener extends UdpListener {
        @Override
        protected ProtocolHandler createProtocolHandler() {
            return new NoopHandler();
        }

        @Override
        public String getDescription() {
            return "test-udp";
        }
    }

    private static final class PlainListener extends Listener {
        @Override
        public String getDescription() {
            return "plain";
        }

        boolean tlsConfigured() {
            return isTLSConfigured();
        }

        boolean metrics() {
            return isMetricsEnabled();
        }
    }

    private static InetSocketAddress addr(String host) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(host), 1234);
    }

    @Test
    public void defaultsAndSimpleSetters() {
        PlainListener l = new PlainListener();
        assertNull(l.getName());
        l.name("n");
        assertEquals("n", l.getName());
        assertEquals(Listener.DEFAULT_MAX_NET_IN_SIZE, l.getMaxNetInSize());
        assertEquals(Listener.DEFAULT_MAX_NET_OUT_SIZE, l.getMaxNetOutSize());
        l.maxNetInSize(10);
        l.maxNetOutSize(20);
        assertEquals(10, l.getMaxNetInSize());
        assertEquals(20, l.getMaxNetOutSize());
        assertEquals(Listener.DEFAULT_IDLE_TIMEOUT_MS, l.getIdleTimeoutMs());
        assertEquals(Listener.DEFAULT_READ_TIMEOUT_MS, l.getReadTimeoutMs());
        assertEquals(Listener.DEFAULT_CONNECTION_TIMEOUT_MS, l.getConnectionTimeoutMs());
        l.idleTimeoutMs(1);
        l.readTimeoutMs(2);
        l.connectionTimeoutMs(3);
        assertEquals(1, l.getIdleTimeoutMs());
        assertEquals(2, l.getReadTimeoutMs());
        assertEquals(3, l.getConnectionTimeoutMs());
        assertEquals(-1, l.getPort());
        assertNull(l.getPath());
        assertFalse(l.isSecure());
        l.secure(true);
        assertTrue(l.isSecure());
        assertSame(l, l.secure(false));
        assertFalse(l.isSecure());
        assertNull(l.getTelemetryConfig());
        assertFalse(l.metrics());
        assertNull(l.getServerCredentials());
        assertEquals(TlsVersion.NEGOTIATE, l.getTlsVersion());
        l.setTlsVersion(null);
        assertEquals(TlsVersion.NEGOTIATE, l.getTlsVersion());
        l.setTlsVersion(TlsVersion.TLS_1_2);
        assertEquals(TlsVersion.TLS_1_2, l.getTlsVersion());
        assertEquals(DtlsVersion.NEGOTIATE, l.getDtlsVersion());
        l.setDtlsVersion(null);
        assertEquals(DtlsVersion.NEGOTIATE, l.getDtlsVersion());
        assertEquals(0, l.getMaxConnections());
        l.maxConnections(5);
        assertEquals(5, l.getMaxConnections());
        l.maxDtlsPeers(7);
        assertEquals(7, l.getMaxDtlsPeers());
        assertNull(l.getTransportFactory());
        assertNull(l.getAuthRateLimiter());
        l.stop();
    }

    @Test
    public void tlsConfiguredDetection() {
        PlainListener l = new PlainListener();
        assertFalse(l.tlsConfigured());
        l.setKeystoreFile(Paths.get("ks.p12"));
        assertFalse(l.tlsConfigured());
        l.setKeystorePass("pw");
        assertTrue(l.tlsConfigured());
        PlainListener p = new PlainListener();
        p.setCertFile(Paths.get("c.pem"));
        assertFalse(p.tlsConfigured());
        p.setKeyFile(Paths.get("k.pem"));
        assertTrue(p.tlsConfigured());
    }

    @Test
    public void sniConfiguration() {
        PlainListener l = new PlainListener();
        assertFalse(l.isSNIEnabled());
        l.setSniHostnames(new LinkedHashMap<String, String>());
        assertFalse(l.isSNIEnabled());
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("example.com", "alias");
        l.setSniHostnames(m);
        assertTrue(l.isSNIEnabled());
        l.setSniHostnames(null);
        assertFalse(l.isSNIEnabled());
    }

    @Test
    public void addressSelection() throws Exception {
        PlainListener l = new PlainListener();
        Set<InetAddress> all = l.getAddresses();
        assertNotNull(all);
        assertFalse(l.isWildcard());
        l.bindWildcard();
        assertTrue(l.isWildcard());
        assertEquals(1, l.getAddresses().size());
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        l.addresses(lo);
        assertFalse(l.isWildcard());
        assertEquals(1, l.getAddresses().size());
        assertTrue(l.getAddresses().contains(lo));
        l.addresses();
        assertFalse(l.getAddresses().isEmpty());
        l.addresses((InetAddress[]) null);
        try {
            l.addresses(new InetAddress[] {null});
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        l.bindWildcard();
        assertTrue(l.isWildcard());
        TestTcpListener tcp = new TestTcpListener();
        tcp.path(Paths.get("/tmp/x.sock"));
        assertTrue(tcp.getAddresses().isEmpty());
    }

    @Test
    public void connectionCapAndCounters() throws Exception {
        PlainListener l = new PlainListener();
        l.maxConnections(2);
        SocketAddress a = addr("10.0.0.1");
        assertTrue(l.acceptConnection(a));
        l.connectionOpened(a);
        l.connectionOpened(a);
        assertEquals(2, l.getActiveConnectionCount());
        assertFalse(l.acceptConnection(a));
        l.connectionClosed(a);
        assertEquals(1, l.getActiveConnectionCount());
        assertTrue(l.acceptConnection(a));
        l.connectionClosed((InetSocketAddress) a);
        l.connectionClosed(a);
        assertEquals(0, l.getActiveConnectionCount());
    }

    @Test
    public void nonInetAddressesAlwaysPassNetworkChecks() {
        PlainListener l = new PlainListener();
        l.blockedNetworks(CidrNetwork.parseList("0.0.0.0/0"));
        SocketAddress unix = java.net.UnixDomainSocketAddress.of("/tmp/some.sock");
        assertTrue(l.acceptConnection(unix));
    }

    @Test
    public void blockedAndAllowedNetworks() throws Exception {
        PlainListener l = new PlainListener();
        l.blockedNetworks(CidrNetwork.parseList("10.0.0.0/8"));
        assertFalse(l.acceptConnection(addr("10.1.2.3")));
        assertTrue(l.acceptConnection(addr("192.168.1.1")));
        l.blockedNetworks(null);
        assertTrue(l.acceptConnection(addr("10.1.2.3")));
        l.blockedNetworks(new ArrayList<CidrNetwork>());
        l.allowedNetworks(CidrNetwork.parseList("192.168.0.0/16"));
        assertTrue(l.acceptConnection(addr("192.168.1.1")));
        assertFalse(l.acceptConnection(addr("172.16.0.1")));
        l.allowedNetworks(null);
        assertTrue(l.acceptConnection(addr("172.16.0.1")));
    }

    @Test
    public void connectionRateLimiting() throws Exception {
        PlainListener l = new PlainListener();
        l.rateLimit("2/60s");
        SocketAddress a = addr("10.9.9.9");
        assertTrue(l.acceptConnection(a));
        l.connectionOpened(a);
        assertTrue(l.acceptConnection(a));
        l.connectionOpened(a);
        assertFalse(l.acceptConnection(a));
        l.connectionClosed(a);
        l.maxConnectionsPerIP(5);
        try {
            l.rateLimit("garbage");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void authRateLimiterCreatedOnDemand() {
        PlainListener l = new PlainListener();
        l.maxAuthFailures(3);
        assertNotNull(l.getAuthRateLimiter());
        l.authLockoutTimeMs(1000);
        assertNotNull(l.getAuthRateLimiter());
    }

    @Test
    public void authLockoutFollowsFailuresAndClearsOnSuccess() {
        PlainListener l = new PlainListener();
        java.net.SocketAddress client = new java.net.InetSocketAddress("192.0.2.7", 40000);
        java.net.SocketAddress other = new java.net.InetSocketAddress("192.0.2.8", 40000);
        assertFalse("nothing is locked out until lockout is configured",
                l.isAuthLockedOut(client));
        l.recordAuthFailure(client, "alice");
        assertFalse(l.isAuthLockedOut(client));

        l.maxAuthFailures(3);
        l.recordAuthFailure(client, "alice");
        l.recordAuthFailure(client, "alice");
        assertFalse(l.isAuthLockedOut(client));
        l.recordAuthFailure(client, "alice");
        assertTrue(l.isAuthLockedOut(client));
        assertFalse("another address is unaffected", l.isAuthLockedOut(other));
    }

    @Test
    public void authSuccessResetsTheFailureCount() {
        PlainListener l = new PlainListener();
        java.net.SocketAddress client = new java.net.InetSocketAddress("192.0.2.7", 40000);
        l.maxAuthFailures(3);
        l.recordAuthFailure(client, "alice");
        l.recordAuthFailure(client, "alice");
        l.recordAuthSuccess(client, "alice");
        l.recordAuthFailure(client, "alice");
        assertFalse(l.isAuthLockedOut(client));
    }

    @Test
    public void authLockoutIgnoresAddressesWithoutAnIp() {
        PlainListener l = new PlainListener();
        l.maxAuthFailures(1);
        java.net.SocketAddress unix = java.net.UnixDomainSocketAddress.of("/tmp/x.sock");
        l.recordAuthFailure(unix, "alice");
        assertFalse(l.isAuthLockedOut(unix));
        assertFalse(l.isAuthLockedOut(null));
    }

    @Test
    public void tlsConfigApplication() {
        PlainListener byPem = new PlainListener();
        byPem.tls(TlsConfig.pem(Paths.get("c.pem"), Paths.get("k.pem")));
        assertTrue(byPem.tlsConfigured());

        PlainListener byKs = new PlainListener();
        byKs.tls(TlsConfig.keystore(Paths.get("k.p12"), "pw").verifyPeer(false));
        assertTrue(byKs.tlsConfigured());

        PlainListener trust = new PlainListener();
        TlsConfig cfg = TlsConfig.pem(Paths.get("c.pem"), Paths.get("k.pem"))
                .echConfigListFile(Paths.get("ech.list"))
                .echPrivateKeyFile(Paths.get("ech.key"))
                .echServerRequired(true);
        trust.tls(cfg);
        assertTrue(trust.tlsConfigured());

        try {
            new PlainListener().tls(new TlsConfig());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        }
        try {
            new PlainListener().tls(null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            TlsConfigSupport.apply(TlsConfig.pem(Paths.get("c"), Paths.get("k")), null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            TlsConfigSupport.apply(null, new PlainListener());
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
    }

    @Test
    public void tlsConfigCarriesCipherSuitesVersionSniAndClientAuthToTheListener() throws Exception {
        PlainListener l = new PlainListener();
        l.tls(TlsConfig.keystore(Paths.get("x.p12"), "pw")
                .cipherSuites("TLS_AES_128_GCM_SHA256").namedGroups("X25519")
                .tlsVersion(TlsVersion.TLS_1_3).dtlsVersion(DtlsVersion.DTLS_1_3)
                .sni("h.example", "a").sniDefaultAlias("d").requireClientAuth(true));
        assertEquals(TlsVersion.TLS_1_3, l.getTlsVersion());
        assertEquals(DtlsVersion.DTLS_1_3, l.getDtlsVersion());
        assertTrue(l.isSNIEnabled());
        assertTrue(l.needClientAuth);
        assertEquals("TLS_AES_128_GCM_SHA256", privateField(l, "cipherSuites"));
        assertEquals("X25519", privateField(l, "namedGroups"));
        assertEquals("d", privateField(l, "sniDefaultAlias"));
    }

    @Test
    public void tlsConfigWithoutOptionalSettingsLeavesListenerDefaults() {
        PlainListener l = new PlainListener();
        l.tls(TlsConfig.keystore(Paths.get("x.p12"), "pw"));
        assertEquals(TlsVersion.NEGOTIATE, l.getTlsVersion());
        assertFalse(l.isSNIEnabled());
        assertFalse(l.needClientAuth);
    }

    private static Object privateField(Listener l, String name) throws Exception {
        java.lang.reflect.Field f = Listener.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(l);
    }

    private static void fullyConfigure(Listener l) {
        Path p = Paths.get("x");
        l.setKeystoreFile(p);
        l.setKeystorePass("pw");
        l.setKeystoreFormat(KeystoreFormat.PKCS12);
        l.setCertFile(p);
        l.setKeyFile(p);
        l.setCipherSuites("TLS_AES_128_GCM_SHA256");
        l.setNamedGroups("x25519");
        l.setEchConfigListFile(p);
        l.setEchPrivateKeyFile(p);
        l.setEchServerRequired(true);
        l.setNeedClientAuth(true);
        l.setTrustManager(new EmptyX509TrustManager());
        Map<String, String> sni = new LinkedHashMap<String, String>();
        sni.put("h", "a");
        l.setSniHostnames(sni);
        l.setSniDefaultAlias("a");
        l.secure(true);
        l.maxNetInSize(111);
        l.maxNetOutSize(222);
    }

    @Test
    public void configureTcpFactory() {
        TestTcpListener l = new TestTcpListener();
        fullyConfigure(l);
        TcpTransportFactory f = new TcpTransportFactory();
        l.configureTransportFactory(f);
        assertTrue(f.isSecure());
        assertEquals(111, f.getMaxNetInSize());
        assertEquals(222, f.getMaxNetOutSize());
        assertTrue(f.isEchServerRequired());
        assertNotNull(f.getEchConfigListFile());
        assertNotNull(f.getEchPrivateKeyFile());
        assertTrue(l.requiresTcpAccept());
    }

    @Test
    public void configureUdpFactory() {
        TestUdpListener l = new TestUdpListener();
        fullyConfigure(l);
        l.setDtlsVersion(DtlsVersion.NEGOTIATE);
        UdpTransportFactory f = new UdpTransportFactory();
        l.configureTransportFactory(f);
        assertTrue(f.isSecure());
        assertNull(l.getEndpoint());
        l.stop();
    }

    @Test
    public void tlsConfigDtlsCookieSettingsReachTheUdpFactory() {
        TestUdpListener l = new TestUdpListener();
        l.tls(TlsConfig.pem(Paths.get("c.pem"), Paths.get("k.pem"))
                .requireCookie(true)
                .cookieSecret(new byte[] {5, 6, 7})
                .maxFragmentSize(1100));
        UdpTransportFactory f = new UdpTransportFactory();
        l.configureTransportFactory(f);
        assertTrue(f.isRequireCookie());
        assertEquals(1100, f.getMaxFragmentSize());
        org.junit.Assert.assertArrayEquals(new byte[] {5, 6, 7}, f.effectiveCookieSecret());
    }

    @Test
    public void cookiesNeedNoConfigurationByDefault() {
        TestUdpListener l = new TestUdpListener();
        l.tls(TlsConfig.pem(Paths.get("c.pem"), Paths.get("k.pem")));
        UdpTransportFactory f = new UdpTransportFactory();
        l.configureTransportFactory(f);
        assertFalse(f.isRequireCookie());
        assertEquals(1024, f.getMaxFragmentSize());
        assertNull(f.effectiveCookieSecret());
    }

    @Test
    public void requiredCookiesGetARandomSecretWhenNoneIsGiven() {
        UdpTransportFactory f = new UdpTransportFactory();
        f.setRequireCookie(true);
        byte[] first = f.effectiveCookieSecret();
        assertNotNull(first);
        assertEquals(32, first.length);
        assertSame("one secret per factory", first, f.effectiveCookieSecret());
        UdpTransportFactory other = new UdpTransportFactory();
        other.setRequireCookie(true);
        assertFalse(java.util.Arrays.equals(first, other.effectiveCookieSecret()));
    }

    @Test
    public void configureQuicFactory() {
        PlainListener l = new PlainListener();
        fullyConfigure(l);
        QuicTransportFactory f = new QuicTransportFactory();
        l.configureTransportFactory(f);
        assertTrue(f.isSecure());
        PlainListener empty = new PlainListener();
        empty.configureTransportFactory(new QuicTransportFactory());
    }

    @Test
    public void configureWithMinimalSettings() {
        PlainListener l = new PlainListener();
        l.configureTransportFactory(new TcpTransportFactory());
        l.configureTransportFactory(new UdpTransportFactory());
    }

    @Test
    public void tcpListenerPathAndPortAreMutuallyExclusive() {
        TestTcpListener l = new TestTcpListener();
        assertNull(l.getPath());
        l.path(Paths.get("/tmp/gumdrop-test.sock"));
        assertEquals(Paths.get("/tmp/gumdrop-test.sock"), l.getPath());
        l.closeServerChannels();
        assertTrue(l.getAddresses().isEmpty());
    }

    @Test
    public void udpListenerDescriptionAndStop() {
        TestUdpListener l = new TestUdpListener();
        assertEquals("test-udp", l.getDescription());
        l.stop();
    }
}
