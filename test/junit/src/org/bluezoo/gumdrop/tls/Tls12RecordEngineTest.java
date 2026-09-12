/*
 * Tls12RecordEngineTest.java
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;

/**
 * Drives two {@link Tls12RecordEngine}s -- a client and a server, talking
 * only through raw record bytes, no real socket -- through a complete TLS
 * 1.2 record-layer session: handshake (real {@code ChangeCipherSpec},
 * unlike TLS 1.3), application data, GCM's on-wire explicit nonce,
 * ChaCha20-Poly1305's implicit one, the AES-GCM confidentiality limit, and
 * failure paths. Directly ports hopf's own already-proven {@code
 * tls12::record} test module.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Tls12RecordEngineTest {

    private static final String SERVER_NAME = "test.gumdrop.local";

    private static Path certsDirectory;
    private static List<X509Certificate> ecChain;
    private static PrivateKey ecKey;

    @BeforeClass
    public static void generateCertificates() throws Exception {
        certsDirectory = Files.createTempDirectory("tls12-record-engine-test");
        Path keystorePath = certsDirectory.resolve("ec.p12");
        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair", "-alias", "ec", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", "1", "-dname", "CN=" + SERVER_NAME,
                "-ext", "san=dns:" + SERVER_NAME, "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            fail("keytool failed to generate a test certificate");
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            keyStore.load(in, "changeit".toCharArray());
        }
        ecChain = Collections.singletonList((X509Certificate) keyStore.getCertificate("ec"));
        ecKey = (PrivateKey) keyStore.getKey("ec", "changeit".toCharArray());
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
            // best effort cleanup
        }
    }

    /** Records every event a {@link Tls12RecordEngine} pushes, for assertions. */
    private static final class RecordingSink implements TlsRecordSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();
        final List<byte[]> appData = new ArrayList<byte[]>();
        final List<String> events = new ArrayList<String>();
        boolean handshakeComplete;
        TlsProtocolError error;
        boolean peerClosed;

        byte[] drainOutbound() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < outbound.size(); i++) {
                byte[] chunk = outbound.get(i);
                out.write(chunk, 0, chunk.length);
            }
            outbound.clear();
            return out.toByteArray();
        }

        @Override
        public void ciphertextReady(byte[] data) {
            outbound.add(data);
            events.add("ciphertext " + data.length + " bytes");
        }

        @Override
        public void applicationDataReady(byte[] plaintext) {
            appData.add(plaintext);
            events.add("application_data " + plaintext.length + " bytes");
        }

        @Override
        public void handshakeComplete() {
            handshakeComplete = true;
            events.add("handshake_complete");
        }

        @Override
        public void protocolError(TlsProtocolError err) {
            error = err;
            events.add("protocol_error: " + err.getMessage());
        }

        @Override
        public void peerClosed() {
            peerClosed = true;
            events.add("peer_closed");
        }
    }

    private Tls12HandshakeConfig serverConfig() {
        Tls12HandshakeConfig config = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        config.setServerCredentials(new ServerCredentials(ecChain, ecKey));
        return config;
    }

    private Tls12HandshakeConfig clientConfig() throws Exception {
        Tls12HandshakeConfig config = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        config.setServerName(SERVER_NAME);
        config.setTrustManager(CertificateVerifier.trustManagerFromCertificates(ecChain));
        return config;
    }

    /** Relays whatever {@code from} has queued into {@code toEngine}. */
    private static void relay(RecordingSink from, Tls12RecordEngine toEngine, RecordingSink toSink) {
        byte[] wire = from.drainOutbound();
        if (wire.length > 0) {
            toEngine.feedCiphertext(wire, toSink);
        }
    }

    private static final class Loopback {
        final Tls12RecordEngine client;
        final Tls12RecordEngine server;
        final RecordingSink clientSink = new RecordingSink();
        final RecordingSink serverSink = new RecordingSink();

        Loopback(Tls12RecordEngine client, Tls12RecordEngine server) {
            this.client = client;
            this.server = server;
        }
    }

    private Loopback runLoopback() throws Exception {
        Tls12RecordEngine client = new Tls12RecordEngine(clientConfig());
        Tls12RecordEngine server = new Tls12RecordEngine(serverConfig());
        Loopback lb = new Loopback(client, server);

        client.start(lb.clientSink);
        relay(lb.clientSink, server, lb.serverSink); // ClientHello
        relay(lb.serverSink, client, lb.clientSink); // ServerHello..ServerHelloDone -> client sends CKE, CCS, Finished
        relay(lb.clientSink, server, lb.serverSink); // client CCS+Finished -> server completes, sends CCS+Finished
        relay(lb.serverSink, client, lb.clientSink); // server CCS+Finished -> client completes

        assertTrue("client: " + lb.clientSink.events, client.isComplete());
        assertTrue("server: " + lb.serverSink.events, server.isComplete());
        return lb;
    }

    @Test
    public void loopbackHandshakeCompletesWithRealChangeCipherSpec() throws Exception {
        Loopback lb = runLoopback();
        assertTrue(lb.clientSink.handshakeComplete);
        assertTrue(lb.serverSink.handshakeComplete);
        // A real two-phase activation: each side's write epoch starts a
        // fresh sequence counter at 0 once its own CCS goes out -- and
        // each side's own Finished is itself the first (and, by this
        // point, only) record encrypted under the new keys, so seq is 1
        // on both write and read directions, not 0, once the handshake
        // has actually completed.
        assertEquals(1L, lb.client.write.seq);
        assertEquals(1L, lb.server.write.seq);
        assertEquals(1L, lb.client.read.seq);
        assertEquals(1L, lb.server.read.seq);
    }

    @Test
    public void applicationDataRoundTripsAfterHandshake() throws Exception {
        Loopback lb = runLoopback();

        lb.client.sendApplicationData("hello from client".getBytes("US-ASCII"), lb.clientSink);
        byte[] wire = lb.clientSink.drainOutbound();
        assertTrue(wire.length > 0);
        lb.server.feedCiphertext(wire, lb.serverSink);
        assertEquals(1, lb.serverSink.appData.size());
        assertArrayEquals("hello from client".getBytes("US-ASCII"), lb.serverSink.appData.get(0));

        lb.server.sendApplicationData("hello back".getBytes("US-ASCII"), lb.serverSink);
        wire = lb.serverSink.drainOutbound();
        lb.client.feedCiphertext(wire, lb.clientSink);
        assertEquals(1, lb.clientSink.appData.size());
        assertArrayEquals("hello back".getBytes("US-ASCII"), lb.clientSink.appData.get(0));
    }

    @Test
    public void gcmRecordCarriesAnEightByteExplicitNonceEqualToTheSequenceNumber() throws Exception {
        Tls12HandshakeConfig cc = clientConfig();
        cc.setCipherSuites(Collections.singletonList(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256));
        Tls12HandshakeConfig sc = serverConfig();
        sc.setCipherSuites(Collections.singletonList(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256));

        Tls12RecordEngine client = new Tls12RecordEngine(cc);
        Tls12RecordEngine server = new Tls12RecordEngine(sc);
        Loopback lb = new Loopback(client, server);
        client.start(lb.clientSink);
        relay(lb.clientSink, server, lb.serverSink);
        relay(lb.serverSink, client, lb.clientSink);
        relay(lb.clientSink, server, lb.serverSink);
        relay(lb.serverSink, client, lb.clientSink);
        assertTrue(client.isComplete());
        assertTrue(server.isComplete());
        assertTrue(client.write.hasExplicitNonce());

        client.sendApplicationData("x".getBytes("US-ASCII"), lb.clientSink);
        byte[] wire = lb.clientSink.drainOutbound();
        // 5-byte header, then the 8-byte explicit nonce = seq as 8
        // big-endian bytes. seq is already 1 here, not 0: the client's
        // own Finished (sent right after CCS) was itself the first
        // record encrypted under this epoch, consuming seq 0.
        byte[] explicitNonce = java.util.Arrays.copyOfRange(wire, 5, 13);
        assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 0, 0, 1 }, explicitNonce);

        server.feedCiphertext(wire, lb.serverSink);
        assertEquals(1, lb.serverSink.appData.size());
        assertArrayEquals("x".getBytes("US-ASCII"), lb.serverSink.appData.get(0));
    }

    @Test
    public void chaCha20Poly1305RoundTripsWithNoWireNonce() throws Exception {
        Tls12HandshakeConfig cc = clientConfig();
        cc.setCipherSuites(Collections.singletonList(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256));
        Tls12HandshakeConfig sc = serverConfig();
        sc.setCipherSuites(Collections.singletonList(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256));

        Tls12RecordEngine client = new Tls12RecordEngine(cc);
        Tls12RecordEngine server = new Tls12RecordEngine(sc);
        Loopback lb = new Loopback(client, server);
        client.start(lb.clientSink);
        relay(lb.clientSink, server, lb.serverSink);
        relay(lb.serverSink, client, lb.clientSink);
        relay(lb.clientSink, server, lb.serverSink);
        relay(lb.serverSink, client, lb.clientSink);
        assertTrue(client.isComplete());
        assertTrue(server.isComplete());
        assertTrue("no on-wire explicit nonce for ChaCha20-Poly1305", !client.write.hasExplicitNonce());

        client.sendApplicationData("chacha round trip".getBytes("US-ASCII"), lb.clientSink);
        byte[] wire = lb.clientSink.drainOutbound();
        // 5-byte header, then straight into ciphertext+tag -- no 8-byte explicit nonce prefix.
        int len = ((wire[3] & 0xff) << 8) | (wire[4] & 0xff);
        assertEquals("chacha round trip".getBytes("US-ASCII").length + 16, len);

        server.feedCiphertext(wire, lb.serverSink);
        assertEquals(1, lb.serverSink.appData.size());
        assertArrayEquals("chacha round trip".getBytes("US-ASCII"), lb.serverSink.appData.get(0));
    }

    @Test
    public void sendApplicationDataClosesConnectionAtAesGcmConfidentialityLimit() throws Exception {
        Loopback lb = runLoopback();
        lb.client.write.seq = 23_726_566L - 1;

        lb.client.sendApplicationData("the record that crosses the limit".getBytes("US-ASCII"), lb.clientSink);

        assertNotNull("TLS 1.2 has no KeyUpdate -- crossing the limit must fail the connection", lb.clientSink.error);
    }

    @Test
    public void feedCiphertextClosesConnectionAtReadConfidentialityLimit() throws Exception {
        Loopback lb = runLoopback();
        lb.client.write.seq = 23_726_566L - 1;
        lb.server.read.seq = 23_726_566L - 1;

        lb.client.sendApplicationData("one more under the old key".getBytes("US-ASCII"), lb.clientSink);
        byte[] wire = lb.clientSink.drainOutbound();
        lb.server.feedCiphertext(wire, lb.serverSink);

        // The limit check runs right after decrypting a record but before
        // dispatching it (matching hopf's own placement), so the record
        // that crosses the limit is itself never delivered -- the
        // connection closes instead.
        assertEquals("the crossing record is not delivered", 0, lb.serverSink.appData.size());
        assertNotNull("the read side must close the connection", lb.serverSink.error);
    }

    @Test
    public void closeNotifyReportedAsPeerClosed() throws Exception {
        Loopback lb = runLoopback();
        lb.client.sendCloseNotify(lb.clientSink);
        byte[] wire = lb.clientSink.drainOutbound();
        lb.server.feedCiphertext(wire, lb.serverSink);
        assertTrue(lb.serverSink.peerClosed);
    }

    @Test
    public void tamperedApplicationRecordReportsProtocolError() throws Exception {
        Loopback lb = runLoopback();
        lb.client.sendApplicationData("hello".getBytes("US-ASCII"), lb.clientSink);
        byte[] wire = lb.clientSink.drainOutbound();
        wire[wire.length - 1] ^= (byte) 0xff; // corrupt the AEAD tag
        lb.server.feedCiphertext(wire, lb.serverSink);
        assertNotNull(lb.serverSink.error);
        assertTrue(lb.serverSink.appData.isEmpty());
    }

    @Test
    public void aLocallyDetectedFailureSendsARealFatalAlertThePeerCanDecode() throws Exception {
        Loopback lb = runLoopback();
        lb.client.sendApplicationData("hello".getBytes("US-ASCII"), lb.clientSink);
        byte[] wire = lb.clientSink.drainOutbound();
        wire[wire.length - 1] ^= (byte) 0xff; // corrupt the AEAD tag
        lb.server.feedCiphertext(wire, lb.serverSink);

        byte[] alertWire = lb.serverSink.drainOutbound();
        assertTrue("server must send a fatal alert on the wire: " + lb.serverSink.events, alertWire.length > 0);

        lb.client.feedCiphertext(alertWire, lb.clientSink);
        assertNotNull(lb.clientSink.error);
        assertEquals(AlertDescription.BAD_RECORD_MAC, lb.clientSink.error.getAlert());
        assertEquals("client must not echo an alert back to a peer that already sent one: " + lb.clientSink.outbound,
                0, lb.clientSink.outbound.size());
    }

    @Test
    public void applicationDataBeforeHandshakeCompleteIsRejected() throws Exception {
        Tls12RecordEngine client = new Tls12RecordEngine(clientConfig());
        RecordingSink sink = new RecordingSink();
        client.start(sink);
        sink.drainOutbound();
        client.sendApplicationData("too early".getBytes("US-ASCII"), sink);
        assertNotNull(sink.error);
        assertEquals(0, sink.outbound.size());
    }

}
