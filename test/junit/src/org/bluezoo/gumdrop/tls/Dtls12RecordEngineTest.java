/*
 * Dtls12RecordEngineTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;

/**
 * DTLS 1.2 record-layer loopback: datagram relay, lossy harness, GCM/ChaCha,
 * mTLS, SNI, ALPN, ticket resumption, fragmentation, and replay protection.
 */
public class Dtls12RecordEngineTest {

    private static final String SERVER_NAME = "test.gumdrop.local";

    private static Path certsDirectory;
    private static List<X509Certificate> ecChain;
    private static PrivateKey ecKey;
    private static List<X509Certificate> rsaChain;
    private static PrivateKey rsaKey;

    @BeforeClass
    public static void generateCertificates() throws Exception {
        certsDirectory = Files.createTempDirectory("dtls12-record-engine-test");
        KeyStore ecStore = generateKeyStore("ec", "EC", "secp256r1", "SHA256withECDSA");
        ecChain = Collections.singletonList((X509Certificate) ecStore.getCertificate("ec"));
        ecKey = (PrivateKey) ecStore.getKey("ec", "changeit".toCharArray());

        KeyStore rsaStore = generateRsaKeyStore("rsa");
        rsaChain = Collections.singletonList((X509Certificate) rsaStore.getCertificate("rsa"));
        rsaKey = (PrivateKey) rsaStore.getKey("rsa", "changeit".toCharArray());
    }

    private static KeyStore generateKeyStore(String alias, String keyAlg, String groupName, String sigAlg)
            throws Exception {
        Path keystorePath = certsDirectory.resolve(alias + ".p12");
        runKeytool("-genkeypair", "-alias", alias, "-keyalg", keyAlg, "-groupname", groupName,
                "-sigalg", sigAlg, "-validity", "1", "-dname", "CN=" + SERVER_NAME,
                "-ext", "san=dns:" + SERVER_NAME, "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit");
        return loadKeyStore(keystorePath);
    }

    private static KeyStore generateRsaKeyStore(String alias) throws Exception {
        Path keystorePath = certsDirectory.resolve(alias + ".p12");
        runKeytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-sigalg", "SHA256withRSA", "-validity", "1", "-dname", "CN=" + SERVER_NAME,
                "-ext", "san=dns:" + SERVER_NAME, "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit");
        return loadKeyStore(keystorePath);
    }

    private static void runKeytool(String... args) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add("keytool");
        command.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            fail("keytool failed to generate a test certificate");
        }
    }

    private static KeyStore loadKeyStore(Path path) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            keyStore.load(in, "changeit".toCharArray());
        }
        return keyStore;
    }

    @AfterClass
    public static void deleteCertificates() throws IOException {
        if (certsDirectory != null) {
            Files.walkFileTree(certsDirectory, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    deleteQuietly(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    deleteQuietly(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException ignored) {
        }
    }

    private static final class RecordingSink implements TlsRecordSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();
        final List<byte[]> appData = new ArrayList<byte[]>();
        final List<String> events = new ArrayList<String>();
        boolean handshakeComplete;
        TlsProtocolError error;

        @Override
        public void ciphertextReady(byte[] data) {
            outbound.add(data);
        }

        @Override
        public void applicationDataReady(byte[] plaintext) {
            appData.add(plaintext);
        }

        @Override
        public void handshakeComplete() {
            handshakeComplete = true;
        }

        @Override
        public void protocolError(TlsProtocolError err) {
            error = err;
            events.add("protocol_error: " + err.getMessage());
        }

        @Override
        public void peerClosed() {
            events.add("peer_closed");
        }
    }

    private enum DatagramDelivery {
        IN_ORDER {
            @Override
            void deliver(List<byte[]> datagrams, Dtls12RecordEngine to, RecordingSink sink) {
                for (int i = 0; i < datagrams.size(); i++) {
                    to.feedDatagram(datagrams.get(i), sink);
                }
            }
        },
        REVERSE_WITHIN_BATCH {
            @Override
            void deliver(List<byte[]> datagrams, Dtls12RecordEngine to, RecordingSink sink) {
                for (int i = datagrams.size() - 1; i >= 0; i--) {
                    to.feedDatagram(datagrams.get(i), sink);
                }
            }
        },
        DUPLICATE_WITHIN_BATCH {
            @Override
            void deliver(List<byte[]> datagrams, Dtls12RecordEngine to, RecordingSink sink) {
                for (int i = 0; i < datagrams.size(); i++) {
                    to.feedDatagram(datagrams.get(i), sink);
                    to.feedDatagram(datagrams.get(i), sink);
                }
            }
        };

        abstract void deliver(List<byte[]> datagrams, Dtls12RecordEngine to, RecordingSink sink);
    }

    private static final class Loopback {
        final Dtls12RecordEngine client;
        final Dtls12RecordEngine server;
        final RecordingSink clientSink = new RecordingSink();
        final RecordingSink serverSink = new RecordingSink();

        Loopback(Dtls12RecordEngine client, Dtls12RecordEngine server) {
            this.client = client;
            this.server = server;
        }
    }

    private Tls12HandshakeConfig serverBase() {
        return serverBase(1024);
    }

    private Tls12HandshakeConfig serverBase(int maxFragment) {
        Tls12HandshakeConfig config = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        config.setDtlsTransport(true);
        config.setServerCredentials(new ServerCredentials(ecChain, ecKey));
        return config;
    }

    private Tls12HandshakeConfig clientBase() throws Exception {
        return clientBase(1024);
    }

    private Tls12HandshakeConfig clientBase(int maxFragmentIgnored) throws Exception {
        Tls12HandshakeConfig config = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        config.setDtlsTransport(true);
        config.setServerName(SERVER_NAME);
        config.setTrustManager(CertificateVerifier.trustManagerFromCertificates(ecChain));
        return config;
    }

    private Tls12HandshakeConfig serverConfig(List<X509Certificate> chain, PrivateKey key) {
        Tls12HandshakeConfig config = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        config.setDtlsTransport(true);
        config.setServerCredentials(new ServerCredentials(chain, key));
        return config;
    }

    private Tls12HandshakeConfig clientConfig(List<X509Certificate> trustedChain, String serverName) throws Exception {
        Tls12HandshakeConfig config = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        config.setDtlsTransport(true);
        config.setServerName(serverName);
        config.setTrustManager(CertificateVerifier.trustManagerFromCertificates(trustedChain));
        return config;
    }

    private Loopback newLoopback(Tls12HandshakeConfig clientCfg, Tls12HandshakeConfig serverCfg, int maxFragment)
            throws Exception {
        return new Loopback(
                new Dtls12RecordEngine(clientCfg, maxFragment),
                new Dtls12RecordEngine(serverCfg, maxFragment));
    }

    private Loopback runLoopback() throws Exception {
        return runLoopback(clientBase(), serverBase(), 1024,
                DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
    }

    private Loopback runAesGcmLoopback() throws Exception {
        Tls12HandshakeConfig cc = clientBase();
        cc.setCipherSuites(Collections.singletonList(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256));
        Tls12HandshakeConfig sc = serverBase();
        sc.setCipherSuites(Collections.singletonList(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256));
        return runLoopback(cc, sc, 1024, DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
    }

    private Loopback runLoopback(Tls12HandshakeConfig clientCfg, Tls12HandshakeConfig serverCfg, int maxFragment,
            DatagramDelivery toServer, DatagramDelivery toClient) throws Exception {
        Loopback lb = newLoopback(clientCfg, serverCfg, maxFragment);
        lb.client.start(lb.clientSink);
        relayUntilIdle(lb.clientSink, lb.client, lb.serverSink, lb.server, toServer, toClient);
        assertTrue("client: " + lb.clientSink.events, lb.client.isComplete());
        assertTrue("server: " + lb.serverSink.events, lb.server.isComplete());
        return lb;
    }

    private static void relayDatagrams(RecordingSink from, Dtls12RecordEngine toEngine, RecordingSink toSink,
            DatagramDelivery delivery) {
        List<byte[]> pending = new ArrayList<byte[]>(from.outbound);
        from.outbound.clear();
        if (pending.isEmpty()) {
            return;
        }
        if (delivery != DatagramDelivery.IN_ORDER && !isEpochZeroHandshakeBatch(pending)) {
            delivery = DatagramDelivery.IN_ORDER;
        }
        delivery.deliver(pending, toEngine, toSink);
    }

    private static boolean isEpochZeroHandshakeBatch(List<byte[]> datagrams) {
        for (int i = 0; i < datagrams.size(); i++) {
            byte[] dg = datagrams.get(i);
            if (dg.length < 13 || (dg[0] & 0xff) != 22 || dg[3] != 0 || dg[4] != 0) {
                return false;
            }
        }
        return true;
    }

    private static void relayUntilIdle(RecordingSink clientSink, Dtls12RecordEngine client,
            RecordingSink serverSink, Dtls12RecordEngine server) {
        relayUntilIdle(clientSink, client, serverSink, server,
                DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
    }

    private static void relayUntilIdle(RecordingSink clientSink, Dtls12RecordEngine client,
            RecordingSink serverSink, Dtls12RecordEngine server, DatagramDelivery toServer,
            DatagramDelivery toClient) {
        for (int round = 0; round < 32; round++) {
            boolean moved = false;
            if (!clientSink.outbound.isEmpty()) {
                relayDatagrams(clientSink, server, serverSink, toServer);
                moved = true;
            }
            if (!serverSink.outbound.isEmpty()) {
                relayDatagrams(serverSink, client, clientSink, toClient);
                moved = true;
            }
            if (client.isComplete() && server.isComplete()) {
                return;
            }
            if (!moved) {
                return;
            }
        }
    }

    private static byte[] testTicketKey() {
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    @Test
    public void loopbackHandshakeCompletesOverDatagramRecords() throws Exception {
        Loopback lb = runLoopback();
        assertTrue(lb.clientSink.handshakeComplete);
        assertTrue(lb.serverSink.handshakeComplete);
        assertEquals(1L, lb.client.write.seq);
        assertEquals(1L, lb.server.write.seq);
    }

    @Test
    public void applicationDataRoundTripsAfterHandshake() throws Exception {
        Loopback lb = runLoopback();

        lb.client.sendApplicationData("ping".getBytes("US-ASCII"), lb.clientSink);
        relayUntilIdle(lb.clientSink, lb.client, lb.serverSink, lb.server);
        assertEquals(1, lb.serverSink.appData.size());
        assertArrayEquals("ping".getBytes("US-ASCII"), lb.serverSink.appData.get(0));
    }

    @Test
    public void handshakeCompletesWhenMultiFragmentServerFlightArrivesReversed() throws Exception {
        Loopback lb = runLoopback(clientBase(), serverBase(), 128,
                DatagramDelivery.IN_ORDER, DatagramDelivery.REVERSE_WITHIN_BATCH);
        assertTrue(lb.clientSink.handshakeComplete);
        assertTrue(lb.serverSink.handshakeComplete);
    }

    @Test
    public void handshakeCompletesWhenMultiFragmentServerFlightHasDuplicateFragments() throws Exception {
        Loopback lb = runLoopback(clientBase(), serverBase(), 128,
                DatagramDelivery.IN_ORDER, DatagramDelivery.DUPLICATE_WITHIN_BATCH);
        assertTrue(lb.clientSink.handshakeComplete);
        assertTrue(lb.serverSink.handshakeComplete);
    }

    @Test
    public void fragmentedCertificateHandshakeCompletes() throws Exception {
        Loopback lb = runLoopback(clientBase(), serverBase(), 64,
                DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
        assertTrue(lb.client.isComplete());
        assertTrue(lb.server.isComplete());
    }

    @Test
    public void gcmRecordCarriesAnEightByteExplicitNonceEqualToTheSequenceNumber() throws Exception {
        Tls12HandshakeConfig cc = clientBase();
        cc.setCipherSuites(Collections.singletonList(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256));
        Tls12HandshakeConfig sc = serverBase();
        sc.setCipherSuites(Collections.singletonList(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256));

        Loopback lb = runLoopback(cc, sc, 1024, DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
        assertTrue(lb.client.write.hasExplicitNonce());

        lb.client.sendApplicationData("x".getBytes("US-ASCII"), lb.clientSink);
        assertEquals(1, lb.clientSink.outbound.size());
        byte[] wire = lb.clientSink.outbound.get(0);
        byte[] explicitNonce = Arrays.copyOfRange(wire, 13, 21);
        assertArrayEquals(new byte[] { 0, 1, 0, 0, 0, 0, 0, 1 }, explicitNonce);

        lb.server.feedDatagram(wire, lb.serverSink);
        assertEquals(1, lb.serverSink.appData.size());
        assertArrayEquals("x".getBytes("US-ASCII"), lb.serverSink.appData.get(0));
    }

    @Test
    public void chaCha20Poly1305RoundTripsWithNoWireNonce() throws Exception {
        Tls12HandshakeConfig cc = clientBase();
        cc.setCipherSuites(Collections.singletonList(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256));
        Tls12HandshakeConfig sc = serverBase();
        sc.setCipherSuites(Collections.singletonList(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256));

        Loopback lb = runLoopback(cc, sc, 1024, DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
        assertFalse(lb.client.write.hasExplicitNonce());

        lb.client.sendApplicationData("chacha round trip".getBytes("US-ASCII"), lb.clientSink);
        byte[] wire = lb.clientSink.outbound.get(0);
        int len = ((wire[11] & 0xff) << 8) | (wire[12] & 0xff);
        assertEquals("chacha round trip".getBytes("US-ASCII").length + 16, len);

        lb.server.feedDatagram(wire, lb.serverSink);
        assertEquals(1, lb.serverSink.appData.size());
        assertArrayEquals("chacha round trip".getBytes("US-ASCII"), lb.serverSink.appData.get(0));
    }

    @Test
    public void sendApplicationDataClosesConnectionAtAesGcmConfidentialityLimit() throws Exception {
        Loopback lb = runAesGcmLoopback();
        assertTrue(lb.client.write.hasExplicitNonce());
        lb.client.write.seq = 23_726_566L - 1;

        lb.client.sendApplicationData("the record that crosses the limit".getBytes("US-ASCII"), lb.clientSink);
        assertNotNull(lb.clientSink.error);
    }

    @Test
    public void chaCha20Poly1305HasNoAesGcmConfidentialityLimit() throws Exception {
        Tls12HandshakeConfig cc = clientBase();
        cc.setCipherSuites(Collections.singletonList(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256));
        Tls12HandshakeConfig sc = serverBase();
        sc.setCipherSuites(Collections.singletonList(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256));

        Loopback lb = runLoopback(cc, sc, 1024, DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
        assertFalse(lb.client.write.hasExplicitNonce());
        lb.client.write.seq = 23_726_566L - 1;
        lb.client.sendApplicationData("ok".getBytes("US-ASCII"), lb.clientSink);
        assertNull(lb.clientSink.error);
    }

    @Test
    public void duplicateApplicationRecordIsSilentlyDroppedByReplayWindow() throws Exception {
        Loopback lb = runLoopback();
        lb.client.sendApplicationData("once".getBytes("US-ASCII"), lb.clientSink);
        assertEquals(1, lb.clientSink.outbound.size());
        byte[] wire = lb.clientSink.outbound.get(0);

        lb.server.feedDatagram(wire, lb.serverSink);
        lb.server.feedDatagram(wire, lb.serverSink);
        assertEquals(1, lb.serverSink.appData.size());
    }

    @Test
    public void clientCertificateRequiredAndPresentedCompletes() throws Exception {
        Tls12HandshakeConfig sc = serverConfig(ecChain, ecKey);
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
        sc.setClientTrustManager(CertificateVerifier.trustManagerFromCertificates(rsaChain));
        Tls12HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME);
        cc.setClientCredentials(new ServerCredentials(rsaChain, rsaKey));

        Loopback lb = runLoopback(cc, sc, 1024, DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
        assertNull(lb.clientSink.error);
        assertNull(lb.serverSink.error);
        assertNotNull(lb.server.getPeerCertificateChain());
        assertEquals(rsaChain.get(0), lb.server.getPeerCertificateChain().get(0));
    }

    @Test
    public void serverCredentialsResolverDispatchesBySni() throws Exception {
        final Map<String, ServerCredentials> byHost = new HashMap<String, ServerCredentials>();
        byHost.put("a.gumdrop.local", new ServerCredentials(ecChain, ecKey));
        byHost.put("b.gumdrop.local", new ServerCredentials(rsaChain, rsaKey));
        ServerCredentialsResolver resolver = new ServerCredentialsResolver() {
            @Override
            public ServerCredentials resolve(String serverName) {
                return byHost.get(serverName);
            }
        };

        Tls12HandshakeConfig sc1 = serverConfig(ecChain, ecKey);
        sc1.setServerCredentialsResolver(resolver);
        Tls12HandshakeConfig cc1 = clientConfig(ecChain, "a.gumdrop.local");
        cc1.setVerifyHostname(false);
        Loopback lb1 = runLoopback(cc1, sc1, 1024, DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
        assertEquals(ecChain.get(0), lb1.client.getPeerCertificateChain().get(0));

        Tls12HandshakeConfig sc2 = serverConfig(ecChain, ecKey);
        sc2.setServerCredentialsResolver(resolver);
        Tls12HandshakeConfig cc2 = clientConfig(rsaChain, "b.gumdrop.local");
        cc2.setVerifyHostname(false);
        Loopback lb2 = runLoopback(cc2, sc2, 1024, DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
        assertEquals(rsaChain.get(0), lb2.client.getPeerCertificateChain().get(0));
    }

    @Test
    public void alpnNegotiatesServersMostPreferredOverlap() throws Exception {
        Tls12HandshakeConfig sc = serverBase();
        sc.setApplicationProtocols(Arrays.asList("h2", "http/1.1"));
        Tls12HandshakeConfig cc = clientBase();
        cc.setApplicationProtocols(Arrays.asList("http/1.1", "h2"));

        Loopback lb = runLoopback(cc, sc, 1024, DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
        assertNull(lb.clientSink.error);
        assertEquals("h2", lb.client.getNegotiatedApplicationProtocol());
        assertEquals("h2", lb.server.getNegotiatedApplicationProtocol());
    }

    @Test
    public void resumptionRoundTripsWithMatchingMasterSecret() throws Exception {
        byte[] ticketKey = testTicketKey();

        Tls12HandshakeConfig sc1 = serverBase();
        sc1.setTicketKeys(new TicketKeys(ticketKey));
        Tls12HandshakeConfig cc1 = clientBase();
        Tls12ClientTicketStore store = new Tls12ClientTicketStore();
        cc1.setClientTicketStore(store);
        Loopback lb1 = runLoopback(cc1, sc1, 1024, DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
        assertFalse(lb1.client.isResumed());
        assertNotNull(store.get(SERVER_NAME));

        Tls12HandshakeConfig sc2 = serverBase();
        sc2.setTicketKeys(new TicketKeys(ticketKey));
        Tls12HandshakeConfig cc2 = clientBase();
        cc2.setClientTicketStore(store);
        Loopback lb2 = runLoopback(cc2, sc2, 1024, DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
        assertTrue(lb2.client.isResumed());
        assertTrue(lb2.server.isResumed());
        assertNull(lb2.client.getPeerCertificateChain());
    }
}
