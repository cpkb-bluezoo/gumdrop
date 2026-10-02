/*
 * ClientConnectApplyTest.java
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

import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import org.junit.After;
import org.junit.Test;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.dns.client.DnsResolver;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.util.EmptyX509TrustManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for the TLS-application helpers of {@link ClientConnect}, for
 * {@link ClientDial#openEndpoint} and for {@link ClientDefaults#dnsResolver}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClientConnectApplyTest {

    private static final class RecordingTcpFactory extends TcpTransportFactory {
        final List<String> calls = new ArrayList<String>();
        X509TrustManager trust;
        ServerCredentials clientCreds;
        boolean greaseSet;
        boolean requiredSet;

        @Override
        public void setTrustManager(X509TrustManager trustManager) {
            calls.add("trust");
            trust = trustManager;
            super.setTrustManager(trustManager);
        }

        @Override
        public void setClientCredentials(ServerCredentials clientCredentials) {
            calls.add("clientCreds");
            clientCreds = clientCredentials;
            super.setClientCredentials(clientCredentials);
        }

        @Override
        public void setKeystoreFile(Path file) {
            calls.add("keystoreFile");
            super.setKeystoreFile(file);
        }

        @Override
        public void setKeystorePass(String pass) {
            calls.add("keystorePass");
            super.setKeystorePass(pass);
        }

        @Override
        public void setKeystoreFormat(KeystoreFormat format) {
            calls.add("keystoreFormat");
            super.setKeystoreFormat(format);
        }

        @Override
        public void setCertFile(Path path) {
            calls.add("certFile");
            super.setCertFile(path);
        }

        @Override
        public void setKeyFile(Path path) {
            calls.add("keyFile");
            super.setKeyFile(path);
        }

        @Override
        public void setClientEchGreaseEnabled(boolean enabled) {
            greaseSet = enabled;
            super.setClientEchGreaseEnabled(enabled);
        }

        @Override
        public void setClientEchRequired(boolean required) {
            requiredSet = required;
            super.setClientEchRequired(required);
        }
    }

    private static final class RecordingQuicFactory extends QuicTransportFactory {
        final List<String> calls = new ArrayList<String>();
        boolean verify = true;
        boolean greaseSet;
        boolean requiredSet;

        @Override
        public void setTrustManager(X509TrustManager trustManager) {
            calls.add("trust");
            super.setTrustManager(trustManager);
        }

        @Override
        public void setKeystoreFile(Path file) {
            calls.add("keystoreFile");
            super.setKeystoreFile(file);
        }

        @Override
        public void setKeystorePass(String pass) {
            calls.add("keystorePass");
            super.setKeystorePass(pass);
        }

        @Override
        public void setKeystoreFormat(KeystoreFormat format) {
            calls.add("keystoreFormat");
            super.setKeystoreFormat(format);
        }

        @Override
        public void setCertFile(Path path) {
            calls.add("certFile");
            super.setCertFile(path);
        }

        @Override
        public void setKeyFile(Path path) {
            calls.add("keyFile");
            super.setKeyFile(path);
        }

        @Override
        public void setVerifyPeer(boolean v) {
            verify = v;
            super.setVerifyPeer(v);
        }

        @Override
        public void setClientEchGreaseEnabled(boolean enabled) {
            greaseSet = enabled;
            super.setClientEchGreaseEnabled(enabled);
        }

        @Override
        public void setClientEchRequired(boolean required) {
            requiredSet = required;
            super.setClientEchRequired(required);
        }
    }

    @After
    public void tearDown() {
        ClientDefaults.setDefaultTls(null);
    }

    private static X509TrustManager trustManager() {
        return new EmptyX509TrustManager();
    }

    @Test
    public void tcpApplyNullArgumentsRejected() {
        try {
            ClientConnect.applyToTcpFactory(new TlsConfig(), null);
            fail("expected NPE");
        } catch (NullPointerException expected) {
            assertEquals("factory", expected.getMessage());
        }
        try {
            ClientConnect.applyToTcpFactory(null, new RecordingTcpFactory());
            fail("expected NPE");
        } catch (NullPointerException expected) {
            assertEquals("tls", expected.getMessage());
        }
    }

    @Test
    public void quicApplyNullArgumentsRejected() {
        try {
            ClientConnect.applyToQuicFactory(new TlsConfig(), null);
            fail("expected NPE");
        } catch (NullPointerException expected) {
            assertEquals("factory", expected.getMessage());
        }
        try {
            ClientConnect.applyToQuicFactory(null, new RecordingQuicFactory());
            fail("expected NPE");
        } catch (NullPointerException expected) {
            assertEquals("tls", expected.getMessage());
        }
    }

    @Test
    public void tcpApplyDefaultConfigInstallsNothing() {
        RecordingTcpFactory factory = new RecordingTcpFactory();
        ClientConnect.applyToTcpFactory(new TlsConfig(), factory);
        assertTrue(factory.calls.toString(), factory.calls.isEmpty());
        assertNull(factory.getClientEchConfig());
        assertFalse(factory.greaseSet);
        assertFalse(factory.requiredSet);
    }

    @Test
    public void tcpApplyAllMaterialIsForwarded() {
        RecordingTcpFactory factory = new RecordingTcpFactory();
        ServerCredentials creds = new ServerCredentials(null, null);
        X509TrustManager tm = trustManager();
        TlsConfig tls = new TlsConfig()
                .serverCredentials(creds)
                .trustManager(tm)
                .keystoreFile(Paths.get("ks.p12"))
                .keystorePass("secret")
                .keystoreFormat(KeystoreFormat.JKS)
                .certFile(Paths.get("c.pem"))
                .keyFile(Paths.get("k.pem"))
                .clientEchGreaseEnabled(true)
                .clientEchRequired(true);
        ClientConnect.applyToTcpFactory(tls, factory);
        assertSame(creds, factory.clientCreds);
        assertSame(tm, factory.trust);
        assertTrue(factory.calls.toString(), factory.calls.contains("keystoreFile"));
        assertTrue(factory.calls.contains("keystorePass"));
        assertTrue(factory.calls.contains("keystoreFormat"));
        assertTrue(factory.calls.contains("certFile"));
        assertTrue(factory.calls.contains("keyFile"));
        assertTrue(factory.greaseSet);
        assertTrue(factory.requiredSet);
    }

    @Test
    public void tcpApplyVerifyPeerOffInstallsPermissiveTrustManager() {
        RecordingTcpFactory factory = new RecordingTcpFactory();
        ClientConnect.applyToTcpFactory(new TlsConfig().verifyPeer(false), factory);
        assertNotNull(factory.trust);
        assertTrue(factory.trust instanceof EmptyX509TrustManager);
    }

    @Test
    public void tcpApplyExplicitTrustManagerWinsOverVerifyPeerOff() {
        RecordingTcpFactory factory = new RecordingTcpFactory();
        X509TrustManager tm = trustManager();
        ClientConnect.applyToTcpFactory(new TlsConfig().verifyPeer(false).trustManager(tm), factory);
        assertSame(tm, factory.trust);
    }

    @Test
    public void quicApplyDefaultConfigOnlySetsVerifyPeer() {
        RecordingQuicFactory factory = new RecordingQuicFactory();
        ClientConnect.applyToQuicFactory(new TlsConfig(), factory);
        assertTrue(factory.calls.toString(), factory.calls.isEmpty());
        assertTrue(factory.verify);
    }

    @Test
    public void quicApplyAllMaterialIsForwarded() {
        RecordingQuicFactory factory = new RecordingQuicFactory();
        TlsConfig tls = new TlsConfig()
                .trustManager(trustManager())
                .keystoreFile(Paths.get("ks.p12"))
                .keystorePass("secret")
                .keystoreFormat(KeystoreFormat.JKS)
                .certFile(Paths.get("c.pem"))
                .keyFile(Paths.get("k.pem"));
        ClientConnect.applyToQuicFactory(tls, factory);
        assertEquals(6, factory.calls.size());
        assertTrue(factory.calls.contains("trust"));
        assertTrue(factory.calls.contains("keystoreFile"));
        assertTrue(factory.calls.contains("keystorePass"));
        assertTrue(factory.calls.contains("keystoreFormat"));
        assertTrue(factory.calls.contains("certFile"));
        assertTrue(factory.calls.contains("keyFile"));
    }

    @Test
    public void quicApplyVerifyPeerOff() {
        RecordingQuicFactory factory = new RecordingQuicFactory();
        ClientConnect.applyToQuicFactory(new TlsConfig().verifyPeer(false), factory);
        assertTrue(factory.calls.contains("trust"));
        assertFalse(factory.verify);
    }

    @Test
    public void quicEchNullGuardsAndFlags() {
        TlsConfig tls = new TlsConfig().clientEchGreaseEnabled(true).clientEchRequired(true);
        ClientConnect.applyQuicClientEch(null, null, tls);
        ClientConnect.applyQuicClientEch(new RecordingQuicFactory(), null, null);
        RecordingQuicFactory factory = new RecordingQuicFactory();
        ClientConnect.applyQuicClientEch(factory, null, tls);
        assertTrue(factory.greaseSet);
        assertTrue(factory.requiredSet);
        assertNull(factory.getClientEchConfig());
        RecordingQuicFactory plain = new RecordingQuicFactory();
        ClientConnect.applyQuicClientEch(plain, null, new TlsConfig());
        assertFalse(plain.greaseSet);
        assertFalse(plain.requiredSet);
    }

    @Test
    public void tcpEchNullGuards() {
        TlsConfig tls = new TlsConfig().clientEchGreaseEnabled(true);
        ClientConnect.applyTcpClientEch(null, null, tls);
        RecordingTcpFactory factory = new RecordingTcpFactory();
        ClientConnect.applyTcpClientEch(factory, null, null);
        assertFalse(factory.greaseSet);
        ClientConnect.applyTcpClientEch(factory, null, tls);
        assertTrue(factory.greaseSet);
    }

    @Test
    public void prepareTlsSetsSecureAndMergesDefault() {
        ServerCredentials creds = new ServerCredentials(null, null);
        ClientDefaults.setDefaultTls(new TlsConfig().serverCredentials(creds));
        RecordingTcpFactory factory = new RecordingTcpFactory();
        TlsConfig effective = ClientConnect.prepareTls(false, new TlsConfig(), factory);
        assertSame(creds, effective.getServerCredentials());
        assertFalse(factory.isSecure());
        assertSame(creds, factory.clientCreds);
    }

    @Test
    public void undiscoverableHosts() {
        assertTrue(ClientConnect.isUndiscoverableHost("LocalHost"));
        assertTrue(ClientConnect.isUndiscoverableHost("localhost."));
        assertTrue(ClientConnect.isUndiscoverableHost("192.168.0.1"));
        assertTrue(ClientConnect.isUndiscoverableHost("::1"));
        assertFalse(ClientConnect.isUndiscoverableHost("example.com"));
    }

    @Test
    public void dialOpensEndpointForEachTargetKind() {
        TcpTransportFactory factory = new TcpTransportFactory();
        ClientEndpoint byName =
                ClientDial.withDefaultPort(25).host("mail.example.com").openEndpoint(factory);
        assertEquals(25, byName.getPort());
        assertSame(factory, byName.getFactory());
        assertNull(byName.getPath());

        InetAddress loopback = InetAddress.getLoopbackAddress();
        ClientEndpoint byAddress =
                ClientDial.withDefaultPort(25).host(loopback).port(2525).openEndpoint(factory);
        assertEquals(loopback, byAddress.getHost());
        assertEquals(2525, byAddress.getPort());

        ClientEndpoint byPath =
                ClientDial.withDefaultPort(25).socketPath("/tmp/s.sock").openEndpoint(factory);
        assertEquals("/tmp/s.sock", byPath.getPath());
    }

    @Test
    public void dialOpensEndpointWithSelectorLoopAndResolver() {
        TcpTransportFactory factory = new TcpTransportFactory();
        SelectorLoop loop = new InlineSelectorLoop();
        DnsResolver resolver = new DnsResolver();
        ClientEndpoint byName = ClientDial.withDefaultPort(25).host("h.example")
                .selectorLoop(loop).dnsResolver(resolver).openEndpoint(factory);
        assertSame(loop, byName.getSelectorLoop());
        assertSame(resolver, byName.getDnsResolver());
        ClientEndpoint byAddress = ClientDial.withDefaultPort(25)
                .host(InetAddress.getLoopbackAddress()).selectorLoop(loop).openEndpoint(factory);
        assertSame(loop, byAddress.getSelectorLoop());
        assertNull(byAddress.getDnsResolver());
        ClientEndpoint byPath = ClientDial.withDefaultPort(25)
                .socketPath("/tmp/s.sock").selectorLoop(loop).openEndpoint(factory);
        assertSame(loop, byPath.getSelectorLoop());
    }

    @Test
    public void dialOpenEndpointValidatesInput() {
        try {
            ClientDial.withDefaultPort(25).openEndpoint(new TcpTransportFactory());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("required"));
        }
        try {
            ClientDial.withDefaultPort(25).host("h").openEndpoint(null);
            fail("expected NPE");
        } catch (NullPointerException expected) {
            assertEquals("factory", expected.getMessage());
        }
    }

    @Test
    public void dialSetterNullChecksAndMutualExclusion() {
        try {
            ClientDial.withDefaultPort(1).host((InetAddress) null);
            fail("expected NPE");
        } catch (NullPointerException expected) {
            assertEquals("hostAddress", expected.getMessage());
        }
        try {
            ClientDial.withDefaultPort(1).socketPath(null);
            fail("expected NPE");
        } catch (NullPointerException expected) {
            assertEquals("socketPath", expected.getMessage());
        }
        InetAddress loopback = InetAddress.getLoopbackAddress();
        ClientDial dial = ClientDial.withDefaultPort(1).host("a").host(loopback);
        assertNull(dial.getHost());
        assertSame(loopback, dial.getHostAddress());
        dial.host("b");
        assertNull(dial.getHostAddress());
        assertEquals("b", dial.getHost());
        dial.socketPath("/x");
        assertNull(dial.getHost());
        dial.host("c");
        assertNull(dial.getSocketPath());
    }

    @Test
    public void defaultsDnsResolverPrefersPerClient() {
        DnsResolver perClient = new DnsResolver();
        assertSame(perClient, ClientDefaults.dnsResolver(null, perClient));
        assertNull(ClientDefaults.getDefaultTls());
    }

    @Test
    public void discoverEchWithoutAnyLoopReportsNothing() {
        final int[] calls = new int[1];
        final byte[][] seen = new byte[1][];
        TlsConfig tls = new TlsConfig().clientEchDnsDiscovery(true);
        ClientConnect.discoverEch(null, true, ClientDial.withDefaultPort(443).host("mail.example.com"), tls,
                new ClientConnect.EchDiscoveryCallback() {
                    @Override
                    public void discovered(byte[] list) {
                        calls[0]++;
                        seen[0] = list;
                    }
                });
        assertEquals(1, calls[0]);
        assertNull(seen[0]);
    }
}
