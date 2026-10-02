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
        l.setName("n");
        assertEquals("n", l.getName());
        assertEquals(Listener.DEFAULT_MAX_NET_IN_SIZE, l.getMaxNetInSize());
        assertEquals(Listener.DEFAULT_MAX_NET_OUT_SIZE, l.getMaxNetOutSize());
        l.setMaxNetInSize(10);
        l.setMaxNetOutSize(20);
        assertEquals(10, l.getMaxNetInSize());
        assertEquals(20, l.getMaxNetOutSize());
        assertEquals(Listener.DEFAULT_IDLE_TIMEOUT_MS, l.getIdleTimeoutMs());
        assertEquals(Listener.DEFAULT_READ_TIMEOUT_MS, l.getReadTimeoutMs());
        assertEquals(Listener.DEFAULT_CONNECTION_TIMEOUT_MS, l.getConnectionTimeoutMs());
        l.setIdleTimeoutMs(1);
        l.setReadTimeoutMs(2);
        l.setConnectionTimeoutMs(3);
        assertEquals(1, l.getIdleTimeoutMs());
        assertEquals(2, l.getReadTimeoutMs());
        assertEquals(3, l.getConnectionTimeoutMs());
        assertEquals(-1, l.getPort());
        assertNull(l.getPath());
        assertFalse(l.isSecure());
        l.setSecure(true);
        assertTrue(l.isSecure());
        assertSame(l, l.secure(false));
        assertFalse(l.isSecure());
        assertNull(l.getTelemetryConfig());
        assertFalse(l.isTelemetryEnabled());
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
        l.setMaxConnections(5);
        assertEquals(5, l.getMaxConnections());
        l.setMaxDtlsPeers(7);
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
        l.setWildcard(true);
        assertTrue(l.isWildcard());
        TestTcpListener tcp = new TestTcpListener();
        tcp.setPath(Paths.get("/tmp/x.sock"));
        assertTrue(tcp.getAddresses().isEmpty());
    }

    @Test
    public void connectionCapAndCounters() throws Exception {
        PlainListener l = new PlainListener();
        l.setMaxConnections(2);
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
        l.setBlockedNetworks(CidrNetwork.parseList("0.0.0.0/0"));
        SocketAddress unix = java.net.UnixDomainSocketAddress.of("/tmp/some.sock");
        assertTrue(l.acceptConnection(unix));
    }

    @Test
    public void blockedAndAllowedNetworks() throws Exception {
        PlainListener l = new PlainListener();
        l.setBlockedNetworks(CidrNetwork.parseList("10.0.0.0/8"));
        assertFalse(l.acceptConnection(addr("10.1.2.3")));
        assertTrue(l.acceptConnection(addr("192.168.1.1")));
        l.setBlockedNetworks(null);
        assertTrue(l.acceptConnection(addr("10.1.2.3")));
        l.setBlockedNetworks(new ArrayList<CidrNetwork>());
        l.setAllowedNetworks(CidrNetwork.parseList("192.168.0.0/16"));
        assertTrue(l.acceptConnection(addr("192.168.1.1")));
        assertFalse(l.acceptConnection(addr("172.16.0.1")));
        l.setAllowedNetworks(null);
        assertTrue(l.acceptConnection(addr("172.16.0.1")));
    }

    @Test
    public void connectionRateLimiting() throws Exception {
        PlainListener l = new PlainListener();
        l.setRateLimit("2/60s");
        SocketAddress a = addr("10.9.9.9");
        assertTrue(l.acceptConnection(a));
        l.connectionOpened(a);
        assertTrue(l.acceptConnection(a));
        l.connectionOpened(a);
        assertFalse(l.acceptConnection(a));
        l.connectionClosed(a);
        l.setMaxConnectionsPerIP(5);
        try {
            l.setRateLimit("garbage");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void authRateLimiterCreatedOnDemand() {
        PlainListener l = new PlainListener();
        l.setMaxAuthFailures(3);
        assertNotNull(l.getAuthRateLimiter());
        l.setAuthLockoutTimeMs(1000);
        assertNotNull(l.getAuthRateLimiter());
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
        l.setSecure(true);
        l.setMaxNetInSize(111);
        l.setMaxNetOutSize(222);
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
        l.setPath(Paths.get("/tmp/gumdrop-test.sock"));
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
