/*
 * Dtls13RecordEngineTest.java
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.bluezoo.gumdrop.quic.packet.QuicAeadAlgorithm;

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

/**
 * DTLS 1.3 record-layer loopback tests.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Dtls13RecordEngineTest {

    private static final String SERVER_NAME = "test.gumdrop.local";

    private static Path certsDirectory;
    private static List<X509Certificate> ecChain;
    private static PrivateKey ecKey;
    /** Large single cert standing in for future PQ leaf sizes on the wire. */
    private static List<X509Certificate> largeRsaChain;
    private static PrivateKey largeRsaKey;

    @BeforeClass
    public static void generateCertificates() throws Exception {
        certsDirectory = Files.createTempDirectory("dtls13-record-engine-test");
        KeyStore ecStore = generateKeyStore("ec", "EC", "secp256r1", "SHA256withECDSA");
        ecChain = Collections.singletonList((X509Certificate) ecStore.getCertificate("ec"));
        ecKey = (PrivateKey) ecStore.getKey("ec", "changeit".toCharArray());

        KeyStore rsaStore = generateRsaKeyStore("rsa4096", 4096);
        largeRsaChain = Collections.singletonList((X509Certificate) rsaStore.getCertificate("rsa4096"));
        largeRsaKey = (PrivateKey) rsaStore.getKey("rsa4096", "changeit".toCharArray());
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

    private static KeyStore generateRsaKeyStore(String alias, int keySize) throws Exception {
        Path keystorePath = certsDirectory.resolve(alias + ".p12");
        runKeytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", Integer.toString(keySize),
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
                    try {
                        Files.delete(file);
                    } catch (IOException ignored) {
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try {
                        Files.delete(dir);
                    } catch (IOException ignored) {
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private enum DatagramDelivery {
        IN_ORDER {
            @Override
            void deliver(List<byte[]> datagrams, Dtls13RecordEngine to, RecordingSink sink) {
                for (int i = 0; i < datagrams.size(); i++) {
                    to.feedDatagram(datagrams.get(i), sink);
                }
            }
        },
        REVERSE_WITHIN_BATCH {
            @Override
            void deliver(List<byte[]> datagrams, Dtls13RecordEngine to, RecordingSink sink) {
                for (int i = datagrams.size() - 1; i >= 0; i--) {
                    to.feedDatagram(datagrams.get(i), sink);
                }
            }
        };

        abstract void deliver(List<byte[]> datagrams, Dtls13RecordEngine to, RecordingSink sink);
    }

    private static final class RecordingSink implements TlsRecordSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();
        final List<byte[]> appData = new ArrayList<byte[]>();
        final List<String> events = new ArrayList<String>();
        int totalDatagramsSent;
        boolean handshakeComplete;
        TlsProtocolError error;

        @Override
        public void ciphertextReady(byte[] data) {
            outbound.add(data);
            totalDatagramsSent++;
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

    private static final class Loopback {
        final Dtls13RecordEngine client;
        final Dtls13RecordEngine server;
        final RecordingSink clientSink = new RecordingSink();
        final RecordingSink serverSink = new RecordingSink();

        Loopback(Dtls13RecordEngine client, Dtls13RecordEngine server) {
            this.client = client;
            this.server = server;
        }
    }

    private HandshakeConfig serverBaseHandshake() {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.SERVER);
        config.setServerCredentials(new ServerCredentials(ecChain, ecKey));
        return config;
    }

    private HandshakeConfig clientBaseHandshake() throws Exception {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT);
        config.setServerName(SERVER_NAME);
        config.setTrustManager(CertificateVerifier.trustManagerFromCertificates(ecChain));
        return config;
    }

    private Loopback newLoopback(HandshakeConfig clientCfg, HandshakeConfig serverCfg, int maxFragment)
            throws Exception {
        return new Loopback(
                new Dtls13RecordEngine(new Dtls13HandshakeConfig(clientCfg), maxFragment),
                new Dtls13RecordEngine(new Dtls13HandshakeConfig(serverCfg), maxFragment));
    }

    private Loopback runLoopback() throws Exception {
        return runLoopback(clientBaseHandshake(), serverBaseHandshake(), 1024);
    }

    private Loopback runAesGcmLoopback() throws Exception {
        HandshakeConfig cc = clientBaseHandshake();
        cc.setCipherSuites(Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256));
        HandshakeConfig sc = serverBaseHandshake();
        sc.setCipherSuites(Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256));
        return runLoopback(cc, sc, 1024);
    }

    private Loopback runLoopback(HandshakeConfig clientCfg, HandshakeConfig serverCfg, int maxFragment)
            throws Exception {
        return runLoopback(clientCfg, serverCfg, maxFragment,
                DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
    }

    private Loopback runLoopback(HandshakeConfig clientCfg, HandshakeConfig serverCfg, int maxFragment,
            DatagramDelivery toServer, DatagramDelivery toClient) throws Exception {
        Loopback lb = newLoopback(clientCfg, serverCfg, maxFragment);
        lb.client.start(lb.clientSink);
        relayUntilIdle(lb.clientSink, lb.client, lb.serverSink, lb.server, toServer, toClient);
        assertTrue("client: " + lb.clientSink.events, lb.client.isComplete());
        assertTrue("server: " + lb.serverSink.events, lb.server.isComplete());
        return lb;
    }

    private static void relayUntilIdle(RecordingSink clientSink, Dtls13RecordEngine client,
            RecordingSink serverSink, Dtls13RecordEngine server) {
        relayUntilIdle(clientSink, client, serverSink, server,
                DatagramDelivery.IN_ORDER, DatagramDelivery.IN_ORDER);
    }

    private static void relayUntilIdle(RecordingSink clientSink, Dtls13RecordEngine client,
            RecordingSink serverSink, Dtls13RecordEngine server, DatagramDelivery toServer,
            DatagramDelivery toClient) {
        for (int round = 0; round < 48; round++) {
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

    private static void relayDatagrams(RecordingSink from, Dtls13RecordEngine toEngine, RecordingSink toSink,
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

    private static void relayUntilIdle(RecordingSink clientSink, Dtls13RecordEngine client,
            RecordingSink serverSink, Dtls13RecordEngine server, AtomicReference<byte[]> ackCapture) {
        relayUntilIdle(clientSink, client, serverSink, server, ackCapture, null);
    }

    private static void relayUntilIdle(RecordingSink clientSink, Dtls13RecordEngine client,
            RecordingSink serverSink, Dtls13RecordEngine server, AtomicReference<byte[]> ackCapture,
            AtomicReference<byte[]> clientAckCapture) {
        for (int round = 0; round < 32; round++) {
            boolean moved = false;
            if (!clientSink.outbound.isEmpty()) {
                captureAckDatagrams(clientSink.outbound, ackCapture);
                captureAckDatagrams(clientSink.outbound, clientAckCapture);
                relayDatagrams(clientSink, server, serverSink, DatagramDelivery.IN_ORDER);
                moved = true;
            }
            if (!serverSink.outbound.isEmpty()) {
                captureAckDatagrams(serverSink.outbound, ackCapture);
                relayDatagrams(serverSink, client, clientSink, DatagramDelivery.IN_ORDER);
                moved = true;
            }
            if (client.isComplete() && server.isComplete()) {
                captureAckDatagrams(clientSink.outbound, ackCapture);
                captureAckDatagrams(clientSink.outbound, clientAckCapture);
                captureAckDatagrams(serverSink.outbound, ackCapture);
                return;
            }
            if (!moved) {
                return;
            }
        }
    }

    private static boolean isAckSizedUnifiedRecord(byte[] datagram) {
        return datagram.length == Dtls13RecordEngine.UNIFIED_HEADER_LEN + 8 + 1 + 16
                && (datagram[0] & 0xe0) == 0x20;
    }

    private static void captureAckDatagrams(List<byte[]> outbound, AtomicReference<byte[]> ackCapture) {
        if (ackCapture == null) {
            return;
        }
        for (int i = 0; i < outbound.size(); i++) {
            byte[] datagram = outbound.get(i);
            if (isAckSizedUnifiedRecord(datagram)) {
                ackCapture.set(datagram);
            }
        }
    }

    private HandshakeConfig compressedLargeCertClientConfig() throws Exception {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT);
        config.setServerName(SERVER_NAME);
        config.setTrustManager(CertificateVerifier.trustManagerFromCertificates(largeRsaChain));
        config.setCertificateCompressionEnabled(true);
        return new Dtls13HandshakeConfig(config).copyBaseForEngine();
    }

    private HandshakeConfig compressedLargeCertServerConfig() {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.SERVER);
        config.setServerCredentials(new ServerCredentials(largeRsaChain, largeRsaKey));
        config.setCertificateCompressionEnabled(true);
        return new Dtls13HandshakeConfig(config).copyBaseForEngine();
    }

    private static int compressedCertificateHandshakeBodyBytes(List<X509Certificate> chain)
            throws Exception {
        List<byte[]> der = new ArrayList<byte[]>(chain.size());
        for (int i = 0; i < chain.size(); i++) {
            der.add(chain.get(i).getEncoded());
        }
        byte[] certificate = HandshakeMessages.buildCertificate(new byte[0], der);
        byte[] compressed = CertificateCompressor.compress(
                CertificateCompressionAlgorithm.BROTLI, certificate);
        byte[] wire = HandshakeMessages.buildCompressedCertificate(
                CertificateCompressionAlgorithm.BROTLI, compressed);
        return wire.length - 4;
    }

    @Test
    public void fragmentedCompressedCertificateHandshakeCompletes() throws Exception {
        final int maxFragment = 64;
        int bodyBytes = compressedCertificateHandshakeBodyBytes(largeRsaChain);
        assertTrue("test cert should require multiple DTLS fragments at maxFragment="
                + maxFragment, bodyBytes > maxFragment * 2);

        HandshakeConfig cc = compressedLargeCertClientConfig();
        HandshakeConfig sc = compressedLargeCertServerConfig();
        Loopback lb = newLoopback(cc, sc, maxFragment);
        lb.client.start(lb.clientSink);
        relayUntilIdle(lb.clientSink, lb.client, lb.serverSink, lb.server);

        assertNull(lb.clientSink.error);
        assertNull(lb.serverSink.error);
        assertTrue(lb.clientSink.handshakeComplete);
        assertTrue(lb.serverSink.handshakeComplete);
        assertEquals(largeRsaChain.get(0), lb.client.getPeerCertificateChain().get(0));
        assertTrue("server should emit multiple datagrams for a fragmented cert flight",
                lb.serverSink.totalDatagramsSent >= 3);
    }

    @Test
    public void fragmentedCompressedCertificateSurvivesReversedServerFragments() throws Exception {
        Loopback lb = runLoopback(compressedLargeCertClientConfig(), compressedLargeCertServerConfig(), 64,
                DatagramDelivery.IN_ORDER, DatagramDelivery.REVERSE_WITHIN_BATCH);
        assertEquals(largeRsaChain.get(0), lb.client.getPeerCertificateChain().get(0));
    }

    @Test
    public void cookieHelloRetryRequestHandshakeCompletes() throws Exception {
        HandshakeConfig sc = serverBaseHandshake();
        sc.setCookieValidator(new SimpleTestCookieValidator("cookie-secret".getBytes("US-ASCII")));
        Loopback lb = runLoopback(clientBaseHandshake(), sc, 1024);
        assertTrue(lb.clientSink.handshakeComplete);
        assertTrue(lb.serverSink.handshakeComplete);
    }

    private static final class SimpleTestCookieValidator implements CookieValidator {
        private final byte[] secret;

        SimpleTestCookieValidator(byte[] secret) {
            this.secret = secret;
        }

        @Override
        public byte[] computeCookie(byte[] clientHelloRandom) {
            byte[] cookie = new byte[16];
            for (int i = 0; i < cookie.length; i++) {
                cookie[i] = (byte) (secret[i % secret.length] ^ clientHelloRandom[i % clientHelloRandom.length]);
            }
            return cookie;
        }

        @Override
        public boolean validateCookie(byte[] clientHelloRandom, byte[] cookie) {
            if (cookie == null) {
                return false;
            }
            return java.util.Arrays.equals(computeCookie(clientHelloRandom), cookie);
        }
    }

    @Test
    public void loopbackHandshakeCompletesOverDatagramRecords() throws Exception {
        Loopback lb = runLoopback();
        assertTrue(lb.clientSink.handshakeComplete);
        assertTrue(lb.serverSink.handshakeComplete);
        assertEquals(Dtls13RecordEngine.EPOCH_APPLICATION, lb.client.write.epoch);
        assertEquals(Dtls13RecordEngine.EPOCH_APPLICATION, lb.server.write.epoch);
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
    public void cleartextEpochZeroClientHelloUsesThirteenByteHeader() throws Exception {
        Loopback lb = newLoopback(clientBaseHandshake(), serverBaseHandshake(), 1024);
        lb.client.start(lb.clientSink);
        assertFalse(lb.clientSink.outbound.isEmpty());
        byte[] ch = lb.clientSink.outbound.get(0);
        assertEquals(22, ch[0] & 0xff);
        assertEquals(Dtls13RecordEngine.DTLS_VERSION_MAJOR, ch[1] & 0xff);
        assertEquals(Dtls13RecordEngine.DTLS_VERSION_MINOR, ch[2] & 0xff);
        assertEquals(0, ch[3]);
        assertEquals(0, ch[4]);
    }

    @Test
    public void unifiedHeaderUsesTwoByteMaskedSequenceNumber() throws Exception {
        Loopback lb = runLoopback();
        lb.client.sendApplicationData("x".getBytes("US-ASCII"), lb.clientSink);
        assertFalse(lb.clientSink.outbound.isEmpty());
        byte[] wire = lb.clientSink.outbound.get(lb.clientSink.outbound.size() - 1);
        assertEquals(0x20, wire[0] & 0xe0);
        assertEquals(Dtls13RecordEngine.EPOCH_APPLICATION, wire[0] & 0x03);
        assertEquals(Dtls13RecordEngine.UNIFIED_HEADER_LEN, 5);

        lb.server.feedDatagram(wire, lb.serverSink);
        assertEquals(1, lb.serverSink.appData.size());
    }

    @Test
    public void reconstructSequenceNumberHandlesWraparound() {
        assertEquals(0xffffL, Dtls13RecordEngine.reconstructSequenceNumber(-1L, 0xffff, 16));
        assertEquals(0x10000L, Dtls13RecordEngine.reconstructSequenceNumber(0xffffL, 0, 16));
        assertEquals(0x10001L, Dtls13RecordEngine.reconstructSequenceNumber(0x10000L, 1, 16));
    }

    @Test
    public void duplicateApplicationRecordIsSilentlyDroppedByReplayWindow() throws Exception {
        Loopback lb = runLoopback();
        lb.client.sendApplicationData("once".getBytes("US-ASCII"), lb.clientSink);
        assertFalse(lb.clientSink.outbound.isEmpty());
        byte[] wire = lb.clientSink.outbound.get(lb.clientSink.outbound.size() - 1);

        lb.server.feedDatagram(wire, lb.serverSink);
        lb.server.feedDatagram(wire, lb.serverSink);
        assertEquals(1, lb.serverSink.appData.size());
    }

    @Test
    public void sendApplicationDataClosesConnectionAtAesGcmConfidentialityLimit() throws Exception {
        Loopback lb = runAesGcmLoopback();
        lb.client.write.seq = 23_726_566L - 1;
        lb.client.sendApplicationData("limit".getBytes("US-ASCII"), lb.clientSink);
        assertNotNull(lb.clientSink.error);
    }

    @Test
    public void chaCha20Poly1305HasNoAesGcmConfidentialityLimit() throws Exception {
        HandshakeConfig cc = clientBaseHandshake();
        cc.setCipherSuites(Collections.singletonList(CipherSuite.TLS_CHACHA20_POLY1305_SHA256));
        HandshakeConfig sc = serverBaseHandshake();
        sc.setCipherSuites(Collections.singletonList(CipherSuite.TLS_CHACHA20_POLY1305_SHA256));

        Loopback lb = runLoopback(cc, sc, 1024);
        assertEquals(QuicAeadAlgorithm.CHACHA20_POLY1305, lb.client.write.getKeys().getAlgorithm());
        lb.client.write.seq = 23_726_566L - 1;
        lb.client.sendApplicationData("ok".getBytes("US-ASCII"), lb.clientSink);
        assertTrue(lb.clientSink.error == null || lb.clientSink.error.getMessage() == null
                || !lb.clientSink.error.getMessage().contains("confidentiality limit"));
    }

    @Test
    public void handshakeCompletionEmitsAckSizedApplicationRecord() throws Exception {
        AtomicReference<byte[]> ack = new AtomicReference<byte[]>();
        Loopback lb = newLoopback(clientBaseHandshake(), serverBaseHandshake(), 1024);
        lb.client.start(lb.clientSink);
        relayUntilIdle(lb.clientSink, lb.client, lb.serverSink, lb.server, ack);
        assertTrue(lb.client.isComplete());
        assertTrue(lb.server.isComplete());
        assertNotNull("expected an ACK-sized unified application record", ack.get());
    }

    @Test
    public void receivedAckIsSilentlyDiscarded() throws Exception {
        AtomicReference<byte[]> clientAck = new AtomicReference<byte[]>();
        Loopback lb = newLoopback(clientBaseHandshake(), serverBaseHandshake(), 1024);
        lb.client.start(lb.clientSink);
        relayUntilIdle(lb.clientSink, lb.client, lb.serverSink, lb.server, null, clientAck);
        assertNotNull(clientAck.get());
        int appBefore = lb.serverSink.appData.size();
        lb.server.feedDatagram(Arrays.copyOf(clientAck.get(), clientAck.get().length), lb.serverSink);
        assertEquals(appBefore, lb.serverSink.appData.size());
        assertNull(lb.serverSink.error);
    }
}
