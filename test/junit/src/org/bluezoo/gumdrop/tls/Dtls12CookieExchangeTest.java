/*
 * Dtls12CookieExchangeTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;

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
 * RFC 6347 HelloVerifyRequest cookie exchange: HMAC binding, parsing,
 * client engine rebuild, and session-level cookie gating.
 */
public class Dtls12CookieExchangeTest {

    private static final byte[] COOKIE_SECRET = "dtls12-cookie-test-secret".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final String SERVER_NAME = "test.gumdrop.local";
    private static final InetSocketAddress CLIENT_ADDR = new InetSocketAddress("127.0.0.1", 4242);
    private static final InetSocketAddress SERVER_ADDR = new InetSocketAddress("127.0.0.1", 4343);
    private static final InetSocketAddress WRONG_ADDR = new InetSocketAddress("127.0.0.1", 9999);

    private static Path certsDirectory;
    private static List<X509Certificate> ecChain;
    private static PrivateKey ecKey;

    @BeforeClass
    public static void generateCertificates() throws Exception {
        certsDirectory = Files.createTempDirectory("dtls12-cookie-exchange-test");
        Path keystorePath = certsDirectory.resolve("ec.p12");
        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair", "-alias", "ec", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", "1", "-dname", "CN=" + SERVER_NAME,
                "-ext", "san=dns:" + SERVER_NAME, "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
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
    public static void deleteCertificates() throws Exception {
        if (certsDirectory != null) {
            Files.walkFileTree(certsDirectory, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    try {
                        Files.delete(file);
                    } catch (Exception ignored) {
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) {
                    try {
                        Files.delete(dir);
                    } catch (Exception ignored) {
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
    }

    @Test
    public void computeCookieBindsToClientRandomAndSourceAddress() throws Exception {
        byte[] random = new byte[32];
        for (int i = 0; i < random.length; i++) {
            random[i] = (byte) i;
        }
        byte[] cookie = Dtls12HelloVerify.computeCookie(COOKIE_SECRET, random, CLIENT_ADDR);
        assertTrue(cookie.length > 0);
        assertTrue(Dtls12HelloVerify.validateCookie(COOKIE_SECRET, random, CLIENT_ADDR, cookie));
        assertFalse(Dtls12HelloVerify.validateCookie(COOKIE_SECRET, random, WRONG_ADDR, cookie));
        byte[] otherRandom = random.clone();
        otherRandom[0] ^= 0x01;
        assertFalse(Dtls12HelloVerify.validateCookie(COOKIE_SECRET, otherRandom, CLIENT_ADDR, cookie));
    }

    @Test
    public void validateCookieRejectsMissingOrEmptyCookie() throws Exception {
        byte[] random = new byte[32];
        assertFalse(Dtls12HelloVerify.validateCookie(COOKIE_SECRET, random, CLIENT_ADDR, null));
        assertFalse(Dtls12HelloVerify.validateCookie(COOKIE_SECRET, random, CLIENT_ADDR, new byte[0]));
    }

    @Test
    public void parseClientHelloFieldsExtractsRandomAndCookie() throws Exception {
        Tls12HandshakeConfig clientCfg = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        clientCfg.setDtlsTransport(true);
        clientCfg.setServerName(SERVER_NAME);
        clientCfg.setTrustManager(CertificateVerifier.trustManagerFromCertificates(ecChain));
        clientCfg.setDtlsCookie(new byte[] { 1, 2, 3 });

        Dtls12RecordEngine client = new Dtls12RecordEngine(clientCfg, 1024);
        RecordingSink sink = new RecordingSink();
        client.start(sink);
        assertEquals(1, sink.outbound.size());

        Dtls12HelloVerify.ClientHelloFields fields = Dtls12HelloVerify.parseClientHelloFields(sink.outbound.get(0));
        assertNotNull(fields);
        assertEquals(32, fields.random.length);
        assertArrayEquals(new byte[] { 1, 2, 3 }, fields.cookie);
    }

    @Test
    public void clientRebuildsEngineAfterHelloVerifyRequestAndCompletesHandshake() throws Exception {
        Tls12HandshakeConfig clientTemplate = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        clientTemplate.setDtlsTransport(true);
        clientTemplate.setServerName(SERVER_NAME);
        clientTemplate.setTrustManager(CertificateVerifier.trustManagerFromCertificates(ecChain));

        Tls12HandshakeConfig serverCfg = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        serverCfg.setDtlsTransport(true);
        serverCfg.setServerCredentials(new ServerCredentials(ecChain, ecKey));
        Dtls12RecordEngine server = new Dtls12RecordEngine(serverCfg, 1024);
        RecordingSink serverSink = new RecordingSink();

        AtomicReference<Dtls12RecordEngine> activeClient = new AtomicReference<Dtls12RecordEngine>();
        RecordingSink clientSink = new RecordingSink();
        activeClient.set(buildClientEngine(null, null, clientTemplate, clientSink, activeClient));
        activeClient.get().start(clientSink);

        assertFalse(clientSink.outbound.isEmpty());
        Dtls12HelloVerify.ClientHelloFields ch1 = Dtls12HelloVerify.parseClientHelloFields(clientSink.outbound.get(0));
        assertNotNull(ch1);
        clientSink.outbound.clear();

        byte[] cookie = Dtls12HelloVerify.computeCookie(COOKIE_SECRET, ch1.random, CLIENT_ADDR);
        byte[] hvr = Dtls12HelloVerify.buildHelloVerifyRequestDatagram(cookie);
        activeClient.get().feedDatagram(hvr, clientSink);

        assertFalse("ClientHello2 must follow HelloVerifyRequest", clientSink.outbound.isEmpty());
        Dtls12HelloVerify.ClientHelloFields ch2 =
                Dtls12HelloVerify.parseClientHelloFields(clientSink.outbound.get(0));
        assertNotNull(ch2);
        assertArrayEquals(cookie, ch2.cookie);

        relayUntilIdle(clientSink, activeClient.get(), serverSink, server);

        assertTrue("client events: " + clientSink.events, activeClient.get().isComplete());
        assertTrue("server events: " + serverSink.events, server.isComplete());
        assertTrue(clientSink.handshakeComplete);
        assertTrue(serverSink.handshakeComplete);
    }

    private static Dtls12RecordEngine buildClientEngine(byte[] cookie, byte[] preservedRandom,
            Tls12HandshakeConfig template, final RecordingSink sink, final AtomicReference<Dtls12RecordEngine> slot)
            throws Exception {
        Tls12HandshakeConfig cfg = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        cfg.setDtlsTransport(true);
        cfg.setServerName(template.getServerName());
        cfg.setTrustManager(template.getTrustManager());
        cfg.setDtlsCookie(cookie);
        cfg.setDtlsClientRandom(preservedRandom);
        Dtls12RecordEngine engine = new Dtls12RecordEngine(cfg, 1024);
        engine.setHelloVerifyCallback(new Dtls12RecordEngine.HelloVerifyCallback() {
            @Override
            public void onHelloVerifyRequest(byte[] receivedCookie) {
                try {
                    byte[] preservedRandom = slot.get().getClientRandom();
                    Dtls12RecordEngine rebuilt = buildClientEngine(receivedCookie, preservedRandom, template, sink, slot);
                    slot.set(rebuilt);
                    rebuilt.start(sink);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        return engine;
    }

    private static void relayUntilIdle(RecordingSink clientSink, Dtls12RecordEngine client,
            RecordingSink serverSink, Dtls12RecordEngine server) {
        for (int round = 0; round < 24; round++) {
            boolean moved = false;
            if (!clientSink.outbound.isEmpty()) {
                relayDatagrams(clientSink, server, serverSink);
                moved = true;
            }
            if (!serverSink.outbound.isEmpty()) {
                relayDatagrams(serverSink, client, clientSink);
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

    private static void relayDatagrams(RecordingSink from, Dtls12RecordEngine toEngine, RecordingSink toSink) {
        List<byte[]> pending = new ArrayList<byte[]>(from.outbound);
        from.outbound.clear();
        for (int i = 0; i < pending.size(); i++) {
            toEngine.feedDatagram(pending.get(i), toSink);
        }
    }

    private static final class RecordingSink implements TlsRecordSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();
        final List<String> events = new ArrayList<String>();
        boolean handshakeComplete;

        @Override
        public void ciphertextReady(byte[] data) {
            outbound.add(data);
        }

        @Override
        public void applicationDataReady(byte[] plaintext) {
        }

        @Override
        public void handshakeComplete() {
            handshakeComplete = true;
        }

        @Override
        public void protocolError(TlsProtocolError err) {
            events.add("protocol_error: " + err.getMessage());
        }

        @Override
        public void peerClosed() {
            events.add("peer_closed");
        }
    }
}
