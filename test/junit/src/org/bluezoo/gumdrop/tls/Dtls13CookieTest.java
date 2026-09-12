/*
 * Dtls13CookieTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.bluezoo.gumdrop.crypto.KeyExchange;
import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.crypto.SignatureScheme;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * RFC 9147 HelloRetryRequest cookie exchange via {@link CookieValidator}.
 */
public class Dtls13CookieTest {

    private static final String SERVER_NAME = "test.gumdrop.local";
    private static final byte[] COOKIE_SECRET = "dtls13-cookie-test-secret".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private static Path certsDirectory;
    private static List<X509Certificate> ecChain;
    private static PrivateKey ecKey;

    @BeforeClass
    public static void generateCertificates() throws Exception {
        certsDirectory = Files.createTempDirectory("dtls13-cookie-test");
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

    private static final class RecordingSink implements TlsEventSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();
        TlsProtocolError error;

        List<byte[]> drain() {
            List<byte[]> copy = new ArrayList<byte[]>(outbound);
            outbound.clear();
            return copy;
        }

        @Override
        public void handshakeDataReady(byte[] data) {
            outbound.add(data);
        }

        @Override
        public void handshakeSecretsReady() {
        }

        @Override
        public void applicationSecretsReady() {
        }

        @Override
        public void protocolError(TlsProtocolError err) {
            error = err;
        }
    }

    private static void runHandshake(HandshakeEngine client, RecordingSink clientSink,
            HandshakeEngine server, RecordingSink serverSink) {
        client.start(clientSink);
        List<byte[]> toServer = clientSink.drain();
        for (int round = 0; round < 24; round++) {
            for (byte[] message : toServer) {
                server.processMessage(message, serverSink);
                if (serverSink.error != null) {
                    return;
                }
            }
            List<byte[]> toClient = serverSink.drain();
            toServer.clear();
            for (byte[] message : toClient) {
                client.processMessage(message, clientSink);
                if (clientSink.error != null) {
                    return;
                }
            }
            toServer.addAll(clientSink.drain());
            if (client.isComplete() && server.isComplete()) {
                return;
            }
            if (toServer.isEmpty() && serverSink.outbound.isEmpty() && clientSink.outbound.isEmpty()) {
                return;
            }
        }
    }

    private HandshakeConfig serverConfig() {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.SERVER);
        config.setMode(HandshakeMode.DTLS);
        config.setServerCredentials(new ServerCredentials(ecChain, ecKey));
        config.setNamedGroups(Arrays.asList(NamedGroup.SECP256R1, NamedGroup.X25519));
        config.setCipherSuites(Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256));
        return config;
    }

    private HandshakeConfig clientConfig() throws Exception {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT);
        config.setMode(HandshakeMode.DTLS);
        config.setServerName(SERVER_NAME);
        config.setTrustManager(CertificateVerifier.trustManagerFromCertificates(ecChain));
        config.setNamedGroups(Arrays.asList(NamedGroup.X25519, NamedGroup.SECP256R1));
        config.setCipherSuites(Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256));
        return config;
    }

    private static final class TestCookieValidator implements CookieValidator {
        final byte[] secret;

        TestCookieValidator(byte[] secret) {
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
            byte[] expected = computeCookie(clientHelloRandom);
            return Arrays.equals(expected, cookie);
        }
    }

    @Test
    public void handshakeCompletesWithoutCookieValidatorConfigured() throws Exception {
        HandshakeEngine client = new HandshakeEngine(clientConfig());
        HandshakeEngine server = new HandshakeEngine(serverConfig());
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();
        runHandshake(client, clientSink, server, serverSink);
        assertNull(clientSink.error);
        assertNull(serverSink.error);
        assertTrue(client.isComplete());
        assertTrue(server.isComplete());
    }

    @Test
    public void serverSendsHelloRetryRequestWithCookieWhenValidatorConfigured() throws Exception {
        HandshakeConfig sc = serverConfig();
        sc.setCookieValidator(new TestCookieValidator(COOKIE_SECRET));
        HandshakeEngine server = new HandshakeEngine(sc);
        RecordingSink serverSink = new RecordingSink();

        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        HandshakeMessages.ClientHelloParams params = clientHelloParams(random, NamedGroup.X25519, false);
        byte[] ch1 = HandshakeMessages.buildClientHelloWithBinder(params, null);

        server.processMessage(ch1, serverSink);
        assertNull(serverSink.error);
        List<byte[]> flight = serverSink.drain();
        assertEquals(1, flight.size());
        HandshakeMessages.HelloRetryRequest hrr = HandshakeMessages.parseHelloRetryRequest(flight.get(0));
        assertNotNull(hrr.cookie);
        assertTrue(hrr.cookie.length > 0);
    }

    @Test
    public void cookieEchoedInFollowupClientHelloCompletesHandshake() throws Exception {
        HandshakeConfig sc = serverConfig();
        TestCookieValidator validator = new TestCookieValidator(COOKIE_SECRET);
        sc.setCookieValidator(validator);
        HandshakeEngine server = new HandshakeEngine(sc);
        RecordingSink serverSink = new RecordingSink();

        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        HandshakeMessages.ClientHelloParams ch1Params = clientHelloParams(random, NamedGroup.X25519, false);
        byte[] ch1 = HandshakeMessages.buildClientHelloWithBinder(ch1Params, null);
        server.processMessage(ch1, serverSink);
        byte[] hrrBytes = serverSink.drain().get(0);
        HandshakeMessages.HelloRetryRequest hrr = HandshakeMessages.parseHelloRetryRequest(hrrBytes);

        HandshakeMessages.ClientHelloParams ch2Params = clientHelloParams(random, NamedGroup.X25519, false);
        ch2Params.cookie = hrr.cookie;
        byte[] ch2 = HandshakeMessages.buildClientHelloWithBinder(ch2Params, null);
        server.processMessage(ch2, serverSink);
        assertNull("corrected ClientHello2 must be accepted: " + serverSink.error, serverSink.error);
        assertFalse(server.isComplete());
        assertTrue(serverSink.drain().size() >= 1);
    }

    @Test
    public void invalidCookieOnFollowupClientHelloRejected() throws Exception {
        HandshakeConfig sc = serverConfig();
        sc.setCookieValidator(new TestCookieValidator(COOKIE_SECRET));
        HandshakeEngine server = new HandshakeEngine(sc);
        RecordingSink serverSink = new RecordingSink();

        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        byte[] ch1 = HandshakeMessages.buildClientHelloWithBinder(clientHelloParams(random, NamedGroup.X25519, false), null);
        server.processMessage(ch1, serverSink);
        serverSink.drain();

        HandshakeMessages.ClientHelloParams ch2Params = clientHelloParams(random, NamedGroup.X25519, false);
        ch2Params.cookie = new byte[] { 1, 2, 3 };
        server.processMessage(HandshakeMessages.buildClientHelloWithBinder(ch2Params, null), serverSink);
        assertNotNull(serverSink.error);
        assertEquals(AlertDescription.ILLEGAL_PARAMETER, serverSink.error.getAlert());
    }

    @Test
    public void combinedCookieAndGroupMismatchProducesSingleHelloRetryRequest() throws Exception {
        HandshakeConfig sc = serverConfig();
        sc.setCookieValidator(new TestCookieValidator(COOKIE_SECRET));
        HandshakeEngine server = new HandshakeEngine(sc);
        RecordingSink serverSink = new RecordingSink();

        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        KeyExchange x25519Kx = KeyExchange.generate(NamedGroup.X25519);
        HandshakeMessages.ClientHelloParams params = new HandshakeMessages.ClientHelloParams();
        params.random = random;
        params.cipherSuites = Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256);
        params.groups = Arrays.asList(NamedGroup.X25519, NamedGroup.SECP256R1);
        params.keyShares = new LinkedHashMap<NamedGroup, byte[]>();
        params.keyShares.put(NamedGroup.X25519, x25519Kx.getShareBytes());
        params.signatureAlgorithms = Collections.singletonList(SignatureScheme.ECDSA_SECP256R1_SHA256);
        params.applicationProtocols = Collections.singletonList("h3");
        params.serverName = SERVER_NAME;

        server.processMessage(HandshakeMessages.buildClientHelloWithBinder(params, null), serverSink);
        assertNull(serverSink.error);
        List<byte[]> flight = serverSink.drain();
        assertEquals("must send exactly one HelloRetryRequest", 1, flight.size());
        HandshakeMessages.HelloRetryRequest hrr = HandshakeMessages.parseHelloRetryRequest(flight.get(0));
        assertEquals(NamedGroup.SECP256R1, hrr.selectedGroup);
        assertNotNull(hrr.cookie);
    }

    @Test
    public void groupMismatchRetryStillWorksWithoutCookieValidator() throws Exception {
        HandshakeConfig sc = serverConfig();
        HandshakeEngine server = new HandshakeEngine(sc);
        RecordingSink serverSink = new RecordingSink();

        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        KeyExchange x25519Kx = KeyExchange.generate(NamedGroup.X25519);
        HandshakeMessages.ClientHelloParams params = clientHelloParams(random, NamedGroup.X25519, true);
        params.keyShares.put(NamedGroup.X25519, x25519Kx.getShareBytes());

        server.processMessage(HandshakeMessages.buildClientHelloWithBinder(params, null), serverSink);
        assertNull(serverSink.error);
        HandshakeMessages.HelloRetryRequest hrr =
                HandshakeMessages.parseHelloRetryRequest(serverSink.drain().get(0));
        assertNull(hrr.cookie);
        assertEquals(NamedGroup.SECP256R1, hrr.selectedGroup);
    }

    private static HandshakeMessages.ClientHelloParams clientHelloParams(byte[] random, NamedGroup shareGroup,
            boolean includeSecp256r1InGroups) {
        HandshakeMessages.ClientHelloParams params = new HandshakeMessages.ClientHelloParams();
        params.random = random;
        params.cipherSuites = Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256);
        if (includeSecp256r1InGroups) {
            params.groups = Arrays.asList(NamedGroup.X25519, NamedGroup.SECP256R1);
        } else {
            params.groups = Collections.singletonList(shareGroup);
        }
        params.keyShares = new LinkedHashMap<NamedGroup, byte[]>();
        try {
            params.keyShares.put(shareGroup, KeyExchange.generate(shareGroup).getShareBytes());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        params.signatureAlgorithms = Collections.singletonList(SignatureScheme.ECDSA_SECP256R1_SHA256);
        params.applicationProtocols = Collections.singletonList("h3");
        params.serverName = SERVER_NAME;
        return params;
    }
}
