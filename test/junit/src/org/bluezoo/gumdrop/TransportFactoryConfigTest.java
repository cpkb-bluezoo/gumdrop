/*
 * TransportFactoryConfigTest.java
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

import org.bluezoo.gumdrop.testsupport.TestGumdrop;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;
import org.junit.Test;

/**
 * Unit tests for the configuration and start-up logic of
 * {@link TcpTransportFactory}, {@link UdpTransportFactory} and
 * {@link TransportFactory}, plus the constructors of {@link ClientEndpoint}.
 * No sockets are opened.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TransportFactoryConfigTest {

    private static final String CERT_B64 =
            "MIIBPzCB5qADAgECAgkAq4yq548MdtwwCgYIKoZIzj0EAwMwEzERMA8GA1UEAxMI"
            + "Y29yZXRlc3QwIBcNMjYxMDAyMDcwNDU2WhgPMjEyNjA5MDgwNzA0NTZaMBMxETAP"
            + "BgNVBAMTCGNvcmV0ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEiWdYBF51"
            + "K/pRe6OXwsHiOgFphDQQZWJ8HAtqoXpJZwm7ePY9YwbQ+kv1oo+GkXZIPj4qD3Nh"
            + "0BeVkgudsuNJ2aMhMB8wHQYDVR0OBBYEFIFSgLfCliRJ2QmLooQ7Guf3R+vCMAoG"
            + "CCqGSM49BAMDA0gAMEUCIQD2dSmZ+j1JvsS7/BlxdcTs/ftxO9Nj2jVwi82Ij7gz"
            + "qwIga/njuOmiHUkCMYvZRUVnnzqLLehdKUsYCGfEU7ILztU=";

    private static final class NoopHandler implements ProtocolHandler {
        @Override public void receive(ByteBuffer data) { }
        @Override public void connected(Endpoint endpoint) { }
        @Override public void securityEstablished(SecurityInfo info) { }
        @Override public void disconnected() { }
        @Override public void error(Exception cause) { }
    }

    private static ServerCredentials credentials() throws Exception {
        byte[] der = Base64.getMimeDecoder().decode(CERT_B64);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(256);
        KeyPair kp = kpg.generateKeyPair();
        List<X509Certificate> chain = new ArrayList<X509Certificate>();
        chain.add(cert);
        return new ServerCredentials(chain, kp.getPrivate());
    }

    // TransportFactory base

    @Test
    public void baseSettersAndPinnedFingerprint() {
        TcpTransportFactory f = new TcpTransportFactory();
        assertFalse(f.isSecure());
        f.setSecure(true);
        assertTrue(f.isSecure());
        assertEquals("TCP", f.getDescription());
        assertNull(f.getPinnedCertFingerprint());
        f.setPinnedCertFingerprint("SHA-256:ABCDEF");
        assertEquals("abcdef", f.getPinnedCertFingerprint());
        f.setPinnedCertFingerprint("ABC");
        assertEquals("abc", f.getPinnedCertFingerprint());
        f.setPinnedCertFingerprint(null);
        assertNull(f.getPinnedCertFingerprint());
        assertFalse(f.isTelemetryEnabled());
        assertNull(f.getTelemetryConfig());
        f.setMaxNetInSize(5);
        f.setMaxNetOutSize(6);
        assertEquals(5, f.getMaxNetInSize());
        assertEquals(6, f.getMaxNetOutSize());
        Path p = Paths.get("x");
        f.setKeystoreFile(p);
        f.setKeystorePass("p");
        f.setTruststoreFile(p);
        f.setTruststorePass("p");
        f.setCertFile(p);
        f.setKeyFile(p);
        f.setCipherSuites("a");
        f.setNamedGroups("b");
        f.setEchConfigListFile(p);
        f.setEchPrivateKeyFile(p);
        f.setEchServerRequired(true);
        assertEquals(p, f.getEchConfigListFile());
        assertEquals(p, f.getEchPrivateKeyFile());
        assertTrue(f.isEchServerRequired());
        f.stop();
    }

    // TcpTransportFactory

    @Test
    public void tcpAccessors() throws Exception {
        TcpTransportFactory f = new TcpTransportFactory();
        assertEquals(TlsVersion.NEGOTIATE, f.getTlsVersion());
        f.setTlsVersion(null);
        assertEquals(TlsVersion.NEGOTIATE, f.getTlsVersion());
        f.setTlsVersion(TlsVersion.TLS_1_3);
        assertEquals(TlsVersion.TLS_1_3, f.getTlsVersion());
        assertNull(f.getApplicationProtocols());
        f.setApplicationProtocols();
        assertNull(f.getApplicationProtocols());
        f.setApplicationProtocols("h2", "http/1.1");
        assertEquals(2, f.getApplicationProtocols().length);
        f.setApplicationProtocols((String[]) null);
        assertNull(f.getApplicationProtocols());
        assertFalse(f.isSNIEnabled());
        Map<String, String> sni = new LinkedHashMap<String, String>();
        sni.put("a", "b");
        f.setSniHostnames(sni);
        assertTrue(f.isSNIEnabled());
        f.setSniDefaultAlias("b");
        f.setSniHostnames(null);
        assertFalse(f.isSNIEnabled());
        f.setNeedClientAuth(true);
        ServerCredentials creds = credentials();
        f.setServerCredentials(creds);
        assertSame(creds, f.getServerCredentials());
        f.setClientCredentials(creds);
        assertSame(creds, f.getClientCredentials());
        assertNull(f.getServerCredentialsResolver());
        f.setServerCredentialsResolver(null);
        EmptyX509TrustManager tm = new EmptyX509TrustManager();
        f.setTrustManager(tm);
        assertSame(tm, f.getTrustManager());
        assertNull(f.getClientEchConfig());
        f.setClientEchConfig(null);
        f.setClientEchGreaseEnabled(true);
        f.setClientEchRequired(false);
    }

    @Test
    public void tcpStartResolvesSuitesForEachTlsVersion() {
        TlsVersion[] versions = new TlsVersion[] {
            TlsVersion.NEGOTIATE, TlsVersion.TLS_1_2, TlsVersion.TLS_1_3
        };
        for (int i = 0; i < versions.length; i++) {
            TcpTransportFactory f = new TcpTransportFactory();
            f.setTlsVersion(versions[i]);
            f.setCipherSuites("TLS_AES_128_GCM_SHA256:BOGUS: :TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
            f.setNamedGroups("x25519:bogus: :secp256r1");
            f.setPinnedCertFingerprint("SHA-256:00");
            f.start();
            f.buildClientConfig("localhost");
        }
        TcpTransportFactory plain = new TcpTransportFactory();
        plain.setCipherSuites("BOGUS");
        plain.setNamedGroups("BOGUS");
        plain.setTlsVersion(TlsVersion.TLS_1_3);
        plain.setTrustManager(new EmptyX509TrustManager());
        plain.start();
        TcpTransportFactory tls12 = new TcpTransportFactory();
        tls12.setTlsVersion(TlsVersion.TLS_1_2);
        tls12.setNamedGroups("x25519");
        tls12.start();
    }

    @Test
    public void tcpStartFailsForUnreadableMaterial() {
        TcpTransportFactory certs = new TcpTransportFactory();
        certs.setCertFile(Paths.get("/nonexistent/cert.pem"));
        certs.setKeyFile(Paths.get("/nonexistent/key.pem"));
        try {
            certs.start();
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getCause());
        }
        TcpTransportFactory ks = new TcpTransportFactory();
        ks.setKeystoreFile(Paths.get("/nonexistent/ks.p12"));
        ks.setKeystorePass("pw");
        try {
            ks.start();
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getCause());
        }
        TcpTransportFactory sni = new TcpTransportFactory();
        sni.setKeystoreFile(Paths.get("/nonexistent/ks.p12"));
        sni.setKeystorePass("pw");
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("h", "a");
        sni.setSniHostnames(m);
        try {
            sni.start();
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getCause());
        }
        TcpTransportFactory ts = new TcpTransportFactory();
        ts.setTruststoreFile(Paths.get("/nonexistent/ts.p12"));
        ts.setTruststorePass("pw");
        try {
            ts.start();
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getCause());
        }
    }

    @Test
    public void tcpCreateServerEndpoint() throws Exception {
        TcpTransportFactory f = new TcpTransportFactory();
        f.start();
        TcpEndpoint plain = f.createServerEndpoint(null, new NoopHandler());
        assertFalse(plain.isSecure());
        assertSame(f, plain.getTransportFactory());
        try {
            f.createServerEndpoint(null, new NoopHandler(), true);
            fail("expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void tcpCreateSecureServerEndpointsForEachVersion() throws Exception {
        TlsVersion[] versions = new TlsVersion[] {
            TlsVersion.NEGOTIATE, TlsVersion.TLS_1_2, TlsVersion.TLS_1_3
        };
        for (int i = 0; i < versions.length; i++) {
            TcpTransportFactory f = new TcpTransportFactory();
            f.setServerCredentials(credentials());
            f.setTlsVersion(versions[i]);
            f.setNeedClientAuth(true);
            f.setApplicationProtocols("h2");
            f.start();
            TcpEndpoint secure = f.createServerEndpoint(null, new NoopHandler(), true);
            assertTrue(secure.isSecure());
            assertFalse(secure.isClientMode());
            TcpEndpoint plain = f.createServerEndpoint(null, new NoopHandler(), false);
            assertFalse(plain.isSecure());
        }
    }

    @Test
    public void tcpClientConfigsCanBeBuilt() throws Exception {
        TlsVersion[] versions = new TlsVersion[] {
            TlsVersion.NEGOTIATE, TlsVersion.TLS_1_2, TlsVersion.TLS_1_3
        };
        for (int i = 0; i < versions.length; i++) {
            TcpTransportFactory f = new TcpTransportFactory();
            f.setTlsVersion(versions[i]);
            f.setClientCredentials(credentials());
            f.setApplicationProtocols("h2");
            f.start();
            assertNotNull(f.buildClientConfig("example.com"));
        }
    }

    @Test
    public void tlsServerNameSelection() throws Exception {
        assertEquals("example.com", TcpTransportFactory.tlsServerNameFor(null, "example.com"));
        assertEquals("localhost", TcpTransportFactory.tlsServerNameFor(null, "LocalHost"));
        assertEquals("localhost", TcpTransportFactory.tlsServerNameFor(null, "0:0:0:0:0:0:0:1"));
        assertEquals("localhost", TcpTransportFactory.tlsServerNameFor(null, "127.0.0.2"));
        assertEquals("not a host name!", TcpTransportFactory.tlsServerNameFor(null, "not a host name!"));
        InetAddress remote = InetAddress.getByName("192.0.2.7");
        assertEquals("192.0.2.7", TcpTransportFactory.tlsServerNameFor(remote, null));
        assertEquals("192.0.2.7", TcpTransportFactory.tlsServerNameFor(remote, ""));
        assertEquals("localhost", TcpTransportFactory.tlsServerNameFor(InetAddress.getByName("127.0.0.1"), null));
        assertNull(TcpTransportFactory.tlsServerNameFor(null, null));
    }

    // UdpTransportFactory

    @Test
    public void udpAccessorsAndStart() throws Exception {
        UdpTransportFactory f = new UdpTransportFactory();
        assertEquals(DtlsVersion.NEGOTIATE, f.getDtlsVersion());
        f.setDtlsVersion(null);
        assertEquals(DtlsVersion.NEGOTIATE, f.getDtlsVersion());
        assertEquals(UdpTransportFactory.DEFAULT_MAX_DTLS_PEERS, f.getMaxDtlsPeers());
        f.setMaxDtlsPeers(3);
        assertEquals(3, f.getMaxDtlsPeers());
        assertFalse(f.isSNIEnabled());
        Map<String, String> sni = new LinkedHashMap<String, String>();
        sni.put("a", "b");
        f.setSniHostnames(sni);
        assertTrue(f.isSNIEnabled());
        f.setSniDefaultAlias("b");
        f.setNeedClientAuth(true);
        f.setRequireCookie(true);
        f.setCookieSecret(new byte[] {1, 2, 3});
        f.setCookieSecret(null);
        f.setMaxFragmentSize(1000);
        f.setApplicationProtocols(new String[] {"coap"});
        f.setApplicationProtocols(null);
        f.setClientCredentials(credentials());
        f.setServerCredentialsResolver(null);
        f.setTrustManager(new EmptyX509TrustManager());
        assertEquals("UDP", f.getDescription());
    }

    @Test
    public void udpStartBuildsSharedServerConfigsPerVersion() throws Exception {
        DtlsVersion[] versions = new DtlsVersion[] {
            DtlsVersion.NEGOTIATE, DtlsVersion.DTLS_1_2, DtlsVersion.DTLS_1_3
        };
        for (int i = 0; i < versions.length; i++) {
            UdpTransportFactory f = new UdpTransportFactory();
            f.setDtlsVersion(versions[i]);
            f.setSecure(true);
            f.setServerCredentials(credentials());
            f.setNeedClientAuth(true);
            f.setRequireCookie(true);
            f.setCookieSecret(new byte[] {9, 9, 9, 9});
            f.setApplicationProtocols(new String[] {"coap"});
            f.setCipherSuites("TLS_AES_128_GCM_SHA256:TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:BOGUS");
            f.setNamedGroups("x25519:bogus");
            f.start();
            if (versions[i] == DtlsVersion.DTLS_1_2) {
                assertNotNull(f.getSharedServerConfig());
            } else if (versions[i] == DtlsVersion.DTLS_1_3) {
                assertNotNull(f.getSharedServerConfig13());
            } else {
                assertNotNull(f.getSharedServerConfig());
                assertNotNull(f.getSharedServerConfig13());
            }
            assertNotNull(f.buildClientConfig12("localhost"));
            assertNotNull(f.buildClientConfig13("localhost"));
        }
        UdpTransportFactory noSecure = new UdpTransportFactory();
        noSecure.start();
        assertNull(noSecure.getSharedServerConfig());
        assertNull(noSecure.getSharedServerConfig13());
    }

    @Test
    public void udpStartFailsForUnreadableMaterial() {
        UdpTransportFactory f = new UdpTransportFactory();
        f.setCertFile(Paths.get("/nonexistent/cert.pem"));
        f.setKeyFile(Paths.get("/nonexistent/key.pem"));
        try {
            f.start();
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getCause());
        }
        UdpTransportFactory ks = new UdpTransportFactory();
        ks.setKeystoreFile(Paths.get("/nonexistent/ks.p12"));
        ks.setKeystorePass("pw");
        try {
            ks.start();
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getCause());
        }
        UdpTransportFactory sni = new UdpTransportFactory();
        sni.setKeystoreFile(Paths.get("/nonexistent/ks.p12"));
        sni.setKeystorePass("pw");
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("h", "a");
        sni.setSniHostnames(m);
        try {
            sni.start();
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getCause());
        }
    }

    // ClientEndpoint

    @Test
    public void clientEndpointConstructors() throws Exception {
        TcpTransportFactory f = new TcpTransportFactory();
        InlineSelectorLoop loop = new InlineSelectorLoop();
        InetAddress lo = InetAddress.getByName("127.0.0.1");

        ClientEndpoint byName = new ClientEndpoint(f, loop, "example.com", 80);
        assertSame(f, byName.getFactory());
        assertNull(byName.getHost());
        assertEquals(80, byName.getPort());
        assertNull(byName.getPath());
        assertSame(loop, byName.getSelectorLoop());
        assertNull(byName.getDnsResolver());
        byName.setDnsResolver(null);

        ClientEndpoint byAddr = new ClientEndpoint(f, loop, lo, 81);
        assertEquals(lo, byAddr.getHost());

        ClientEndpoint byPath = new ClientEndpoint(f, loop, "/tmp/x.sock");
        assertEquals("/tmp/x.sock", byPath.getPath());
        assertEquals(-1, byPath.getPort());

        ClientEndpoint noLoopName = new ClientEndpoint(f, "example.com", 82);
        assertNull(noLoopName.getSelectorLoop());
        ClientEndpoint noLoopAddr = new ClientEndpoint(f, lo, 83);
        assertEquals(lo, noLoopAddr.getHost());
        ClientEndpoint noLoopPath = new ClientEndpoint(f, "/tmp/y.sock");
        assertEquals("/tmp/y.sock", noLoopPath.getPath());
        noLoopPath.close();
    }

    @Test
    public void clientEndpointRejectsNulls() throws Exception {
        TcpTransportFactory f = new TcpTransportFactory();
        InlineSelectorLoop loop = new InlineSelectorLoop();
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        String nullString = null;
        InetAddress nullAddr = null;
        TransportFactory nullFactory = null;
        InlineSelectorLoop nullLoop = null;
        try {
            new ClientEndpoint(nullFactory, loop, "h", 1);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(f, nullLoop, "h", 1);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(f, loop, nullString, 1);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(nullFactory, loop, lo, 1);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(f, nullLoop, lo, 1);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(f, loop, nullAddr, 1);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(nullFactory, loop, "/p");
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(f, nullLoop, "/p");
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(f, loop, nullString);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(nullFactory, "h", 1);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(f, nullString, 1);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(nullFactory, lo, 1);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(f, nullAddr, 1);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(nullFactory, "/p");
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        try {
            new ClientEndpoint(f, nullString);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
    }

    @Test
    public void clientEndpointConnectRejectsNulls() throws Exception {
        ClientEndpoint ce = new ClientEndpoint(new TcpTransportFactory(), "example.com", 80);
        try {
            ce.connect(null, new NoopHandler());
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
        Gumdrop g = TestGumdrop.create();
        try {
            ce.connect(g, null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
    }
}
