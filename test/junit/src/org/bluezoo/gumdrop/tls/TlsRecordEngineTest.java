/*
 * TlsRecordEngineTest.java
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
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

import org.bluezoo.gumdrop.TlsHandshakeAsyncOffload;
import org.bluezoo.gumdrop.crypto.CertificateVerifier;

/**
 * Drives two {@link TlsRecordEngine}s -- a client and a server, talking
 * only through raw record bytes, no real socket -- through a complete TLS
 * 1.3 record-layer session: handshake, application data, KeyUpdate, and
 * failure paths. Directly ports hopf's own already-proven
 * {@code tls::record} test module.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TlsRecordEngineTest {

    private static final String SERVER_NAME = "test.gumdrop.local";

    private static Path certsDirectory;
    private static List<X509Certificate> ecChain;
    private static PrivateKey ecKey;

    @BeforeClass
    public static void generateCertificates() throws Exception {
        certsDirectory = Files.createTempDirectory("tls-record-engine-test");
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

    /** Records every event a {@link TlsRecordEngine} pushes, for assertions. */
    private static class RecordingSink implements TlsRecordSink {
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

    private HandshakeConfig serverConfig() {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.SERVER);
        config.setServerCredentials(new ServerCredentials(ecChain, ecKey));
        config.setApplicationProtocols(Collections.singletonList("test"));
        return config;
    }

    private HandshakeConfig clientConfig() throws Exception {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT);
        config.setServerName(SERVER_NAME);
        config.setTrustManager(CertificateVerifier.trustManagerFromCertificates(ecChain));
        config.setApplicationProtocols(Collections.singletonList("test"));
        return config;
    }

    /** Relays whatever {@code from} has queued into {@code toEngine}. */
    private static void relay(RecordingSink from, TlsRecordEngine toEngine, RecordingSink toSink) {
        byte[] wire = from.drainOutbound();
        if (wire.length > 0) {
            toEngine.feedCiphertext(wire, toSink);
        }
    }

    private static final class Loopback {
        final TlsRecordEngine client;
        final TlsRecordEngine server;
        final RecordingSink clientSink = new RecordingSink();
        final RecordingSink serverSink = new RecordingSink();

        Loopback(TlsRecordEngine client, TlsRecordEngine server) {
            this.client = client;
            this.server = server;
        }
    }

    private Loopback runLoopback() throws Exception {
        return runLoopback(clientConfig(), serverConfig());
    }

    private Loopback runLoopback(HandshakeConfig clientCfg, HandshakeConfig serverCfg) throws Exception {
        TlsRecordEngine client = new TlsRecordEngine(clientCfg);
        TlsRecordEngine server = new TlsRecordEngine(serverCfg);
        Loopback lb = new Loopback(client, server);

        client.start(lb.clientSink);
        relay(lb.clientSink, server, lb.serverSink); // ClientHello
        relay(lb.serverSink, client, lb.clientSink); // ServerHello..Finished -> client completes, sends its Finished
        relay(lb.clientSink, server, lb.serverSink); // client Finished -> server completes, sends NewSessionTicket
        relay(lb.serverSink, client, lb.clientSink); // post-handshake NewSessionTicket

        assertTrue("client: " + lb.clientSink.events, client.isComplete());
        assertTrue("server: " + lb.serverSink.events, server.isComplete());
        return lb;
    }

    private Loopback runAesGcmLoopback() throws Exception {
        HandshakeConfig cc = clientConfig();
        cc.setCipherSuites(Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256));
        HandshakeConfig sc = serverConfig();
        sc.setCipherSuites(Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256));
        return runLoopback(cc, sc);
    }

    @Test
    public void loopbackHandshakeCompletesAndExposesAlpn() throws Exception {
        Loopback lb = runLoopback();
        assertTrue(lb.clientSink.handshakeComplete);
        assertTrue(lb.serverSink.handshakeComplete);
        assertEquals("test", lb.client.getNegotiatedApplicationProtocol());
        assertEquals("test", lb.server.getNegotiatedApplicationProtocol());
    }

    @Test
    public void chaCha20Poly1305DirectionRoundTripsOverRealWireFraming() throws Exception {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x11);
        DirectionalKeys write = DirectionalKeys.fromSecret(CipherSuite.TLS_CHACHA20_POLY1305_SHA256, secret);
        DirectionalKeys read = DirectionalKeys.fromSecret(CipherSuite.TLS_CHACHA20_POLY1305_SHA256, secret);

        byte[] payload = "hello chacha13".getBytes("US-ASCII");
        byte[] plain = new byte[payload.length + 1];
        System.arraycopy(payload, 0, plain, 0, payload.length);
        plain[payload.length] = 23; // application_data inner content type
        int cipherLength = plain.length + 16;
        byte[] header = { 23, 0x03, 0x03, (byte) ((cipherLength >> 8) & 0xff), (byte) (cipherLength & 0xff) };
        byte[] sealed = write.sealAppendTag(write.nonce(), header, plain);

        assertEquals("no explicit nonce on the wire, unlike TLS 1.2 GCM", cipherLength, sealed.length);
        byte[] opened = read.openInPlace(read.nonce(), header, sealed);
        assertNotNull(opened);
        int end = opened.length;
        while (opened[end - 1] == 0) {
            end--;
        }
        assertEquals(23, opened[end - 1] & 0xff);
        assertArrayEquals(payload, Arrays.copyOfRange(opened, 0, end - 1));
    }

    @Test
    public void clientFinishedAndPipelinedAppDataInOneReadCompletesThenDeliversInOrder() throws Exception {
        TlsRecordEngine client = new TlsRecordEngine(clientConfig());
        TlsRecordEngine server = new TlsRecordEngine(serverConfig());
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();

        client.start(clientSink);
        relay(clientSink, server, serverSink); // ClientHello
        relay(serverSink, client, clientSink); // ServerHello..Finished -> client completes, sends its Finished
        assertTrue("client: " + clientSink.events, client.isComplete());

        // Pipeline application data right behind the still-unsent Finished --
        // both land in clientSink.outbound as separate ciphertextReady calls,
        // exactly as a fast local peer's back-to-back writes would coalesce
        // into one socket read.
        client.sendApplicationData("pipelined-hello".getBytes("US-ASCII"), clientSink);
        byte[] combined = clientSink.drainOutbound();
        assertTrue(combined.length > 0);
        server.feedCiphertext(combined, serverSink);

        assertTrue("server must complete the handshake: " + serverSink.events, server.isComplete());
        int hsIndex = serverSink.events.indexOf("handshake_complete");
        int appIndex = -1;
        for (int i = 0; i < serverSink.events.size(); i++) {
            if (serverSink.events.get(i).startsWith("application_data")) {
                appIndex = i;
                break;
            }
        }
        assertTrue("handshake_complete fired", hsIndex >= 0);
        assertTrue("application_data fired", appIndex >= 0);
        assertTrue("handshake must complete before app data is delivered: " + serverSink.events, hsIndex < appIndex);
        assertEquals(1, serverSink.appData.size());
        assertArrayEquals("pipelined-hello".getBytes("US-ASCII"), serverSink.appData.get(0));
    }

    @Test
    public void asyncOffloadServerFlightInOneReadCompletes() throws Exception {
        Executor loopExecutor = new Executor() {
            @Override
            public void execute(Runnable task) {
                task.run();
            }
        };
        HandshakeAsyncOffload clientOffload = new TlsHandshakeAsyncOffload(loopExecutor);
        HandshakeAsyncOffload serverOffload = new TlsHandshakeAsyncOffload(loopExecutor);
        TlsRecordEngine client = new TlsRecordEngine(clientConfig(), clientOffload);
        TlsRecordEngine server = new TlsRecordEngine(serverConfig(), serverOffload);
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();

        client.start(clientSink);
        relay(clientSink, server, serverSink);
        relay(serverSink, client, clientSink);
        relay(clientSink, server, serverSink);

        assertNull("client: " + clientSink.events, clientSink.error);
        assertNull("server: " + serverSink.events, serverSink.error);
        assertTrue("client must complete: " + clientSink.events, client.isComplete());
        assertTrue("server must complete: " + serverSink.events, server.isComplete());
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
    public void keyUpdateRotatesClientWriteKeyAndServerStillDecrypts() throws Exception {
        Loopback lb = runLoopback();

        lb.client.sendApplicationData("before update".getBytes("US-ASCII"), lb.clientSink);
        lb.server.feedCiphertext(lb.clientSink.drainOutbound(), lb.serverSink);
        assertEquals(1, lb.serverSink.appData.size());
        assertArrayEquals("before update".getBytes("US-ASCII"), lb.serverSink.appData.get(0));

        assertTrue(lb.client.requestKeyUpdate(lb.clientSink, false));
        byte[] wire = lb.clientSink.drainOutbound();
        assertTrue("KeyUpdate message must be emitted", wire.length > 0);
        lb.server.feedCiphertext(wire, lb.serverSink);
        assertNull("server must accept a well-formed KeyUpdate: " + lb.serverSink.events, lb.serverSink.error);

        lb.serverSink.appData.clear();
        lb.client.sendApplicationData("after update".getBytes("US-ASCII"), lb.clientSink);
        lb.server.feedCiphertext(lb.clientSink.drainOutbound(), lb.serverSink);
        assertEquals("server must decrypt under the ratcheted key: " + lb.serverSink.events,
                1, lb.serverSink.appData.size());
        assertArrayEquals("after update".getBytes("US-ASCII"), lb.serverSink.appData.get(0));
    }

    @Test
    public void keyUpdateRequestingReciprocalKeepsBothDirectionsWorking() throws Exception {
        Loopback lb = runLoopback();

        assertTrue(lb.client.requestKeyUpdate(lb.clientSink, true));
        byte[] wire = lb.clientSink.drainOutbound();
        lb.server.feedCiphertext(wire, lb.serverSink);
        assertNull("server must accept and reciprocate: " + lb.serverSink.events, lb.serverSink.error);

        byte[] reciprocal = lb.serverSink.drainOutbound();
        assertTrue("server must send a reciprocal KeyUpdate", reciprocal.length > 0);
        lb.client.feedCiphertext(reciprocal, lb.clientSink);
        assertNull("client must accept the reciprocal: " + lb.clientSink.events, lb.clientSink.error);

        lb.client.sendApplicationData("client after mutual update".getBytes("US-ASCII"), lb.clientSink);
        lb.server.feedCiphertext(lb.clientSink.drainOutbound(), lb.serverSink);
        assertEquals(1, lb.serverSink.appData.size());
        assertArrayEquals("client after mutual update".getBytes("US-ASCII"), lb.serverSink.appData.get(0));

        lb.server.sendApplicationData("server after mutual update".getBytes("US-ASCII"), lb.serverSink);
        lb.client.feedCiphertext(lb.serverSink.drainOutbound(), lb.clientSink);
        assertEquals(1, lb.clientSink.appData.size());
        assertArrayEquals("server after mutual update".getBytes("US-ASCII"), lb.clientSink.appData.get(0));
    }

    @Test
    public void sendApplicationDataAutoRotatesWriteKeyAtConfidentialityLimit() throws Exception {
        Loopback lb = runAesGcmLoopback();
        // TLS 1.3 has no explicit per-record nonce on the wire -- both
        // sides derive it from their own counted-in-lockstep sequence
        // number, so simulating "23 million records already exchanged"
        // means advancing both sides' counters together.
        lb.client.write.seq = 23_726_566L - 1;
        lb.server.read.seq = 23_726_566L - 1;

        lb.client.sendApplicationData("the record that crosses the limit".getBytes("US-ASCII"), lb.clientSink);

        assertEquals("write key must have rotated (and its sequence counter reset) once the limit was crossed",
                0L, lb.client.write.seq);
        byte[] wire = lb.clientSink.drainOutbound();
        lb.server.feedCiphertext(wire, lb.serverSink);
        assertEquals("server must still decrypt both the app data and the KeyUpdate that followed it: "
                + lb.serverSink.events, 1, lb.serverSink.appData.size());
        assertArrayEquals("the record that crosses the limit".getBytes("US-ASCII"), lb.serverSink.appData.get(0));
    }

    @Test
    public void feedCiphertextRequestsPeerRotationAtReadConfidentialityLimit() throws Exception {
        Loopback lb = runAesGcmLoopback();
        lb.client.write.seq = 23_726_566L - 1;
        lb.server.read.seq = 23_726_566L - 1;

        lb.client.sendApplicationData("one more under the old key".getBytes("US-ASCII"), lb.clientSink);
        lb.server.feedCiphertext(lb.clientSink.drainOutbound(), lb.serverSink);

        assertEquals(1, lb.serverSink.appData.size());
        assertArrayEquals("one more under the old key".getBytes("US-ASCII"), lb.serverSink.appData.get(0));
        byte[] wire = lb.serverSink.drainOutbound();
        assertTrue("server must ask the client to rotate: " + lb.serverSink.events, wire.length > 0);
        lb.client.feedCiphertext(wire, lb.clientSink);
        assertNull("client must accept the rotation request: " + lb.clientSink.events, lb.clientSink.error);
    }

    @Test
    public void requestKeyUpdateBeforeHandshakeCompleteIsANoOp() throws Exception {
        TlsRecordEngine client = new TlsRecordEngine(clientConfig());
        RecordingSink sink = new RecordingSink();
        client.start(sink);
        sink.drainOutbound();
        assertFalse(client.requestKeyUpdate(sink, false));
        assertEquals(0, sink.outbound.size());
    }

    @Test
    public void requestKeyUpdateUnderQuicModeIsANoOp() throws Exception {
        HandshakeConfig config = clientConfig();
        // TlsRecordEngine's own constructor always forces TCP_RECORD_LAYER;
        // exercise the underlying HandshakeEngine gate directly for the
        // QUIC-mode case.
        HandshakeEngine engine = new HandshakeEngine(config);
        RecordingSink sink = new RecordingSink();
        assertFalse(engine.requestKeyUpdate(new TlsEventSink() {
            @Override
            public void handshakeDataReady(byte[] data) {
            }

            @Override
            public void handshakeSecretsReady() {
            }

            @Override
            public void applicationSecretsReady() {
            }

            @Override
            public void protocolError(TlsProtocolError error) {
            }
        }, false));
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
        TlsRecordEngine client = new TlsRecordEngine(clientConfig());
        RecordingSink sink = new RecordingSink();
        client.start(sink);
        sink.drainOutbound();
        client.sendApplicationData("too early".getBytes("US-ASCII"), sink);
        assertNotNull(sink.error);
        assertEquals(0, sink.outbound.size());
    }

    @Test
    public void largeClientHelloIsFragmentedAcrossMultiplePlaintextRecords() throws Exception {
        HandshakeConfig config = clientConfig();
        // Inflate ClientHello's ALPN extension well past MAX_FRAGMENT
        // (16384 bytes) while everything is still in the Plaintext epoch
        // (before any secret exists) -- forces write_fragmented to split
        // one handshakeDataReady call into multiple TLS records.
        List<String> manyProtocols = new ArrayList<String>();
        StringBuilder proto = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            proto.append('x');
        }
        for (int i = 0; i < 400; i++) {
            manyProtocols.add(proto.toString());
        }
        config.setApplicationProtocols(manyProtocols);

        TlsRecordEngine client = new TlsRecordEngine(config);
        RecordingSink sink = new RecordingSink();
        client.start(sink);
        byte[] wire = sink.drainOutbound();
        assertTrue("ClientHello must exceed one fragment", wire.length > 16384);

        int recordCount = 0;
        int offset = 0;
        while (offset < wire.length) {
            assertTrue(wire.length - offset >= 5);
            int type = wire[offset] & 0xff;
            assertEquals(22, type); // still Plaintext epoch: real handshake content type on the wire
            int len = ((wire[offset + 3] & 0xff) << 8) | (wire[offset + 4] & 0xff);
            assertTrue("each plaintext record must respect MAX_FRAGMENT", len <= 16384);
            offset += 5 + len;
            recordCount++;
        }
        assertEquals(wire.length, offset);
        assertTrue("large ClientHello must span more than one record", recordCount > 1);
    }

}
