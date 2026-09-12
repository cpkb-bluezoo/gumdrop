/*
 * HandshakeEngineLoopbackTest.java
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
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.X509TrustManager;

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
import org.bluezoo.gumdrop.crypto.KeyExchange;
import org.bluezoo.gumdrop.crypto.NamedGroup;
import org.bluezoo.gumdrop.crypto.SignatureScheme;

/**
 * Drives two {@link HandshakeEngine}s -- a client and a server, talking
 * only through Java byte arrays, no transport of any kind -- through a
 * complete TLS 1.3 1-RTT handshake, proving the whole reactive engine
 * (key exchange including the ML-KEM/X25519 hybrid group, the key
 * schedule, certificate verification, {@code Finished} verify-data) is
 * internally consistent before it is ever wired into QUIC.
 *
 * <p>This is a smaller, faster proof point than a full QUIC end-to-end
 * test needs to be: the engine owns its own message framing via
 * {@code CryptoStreamBuffer}-shaped complete messages already, so no
 * transport, reassembly, or packet protection is needed to exercise the
 * handshake logic itself. {@link org.bluezoo.gumdrop.quic.QuicHandshakeEndToEndTest}
 * covers the same engine wired into real QUIC CRYPTO frames.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HandshakeEngineLoopbackTest {

    private static final String SERVER_NAME = "test.gumdrop.local";

    private static Path certsDirectory;
    private static List<X509Certificate> ecChain;
    private static PrivateKey ecKey;
    private static List<X509Certificate> rsaChain;
    private static PrivateKey rsaKey;

    @BeforeClass
    public static void generateCertificates() throws Exception {
        certsDirectory = Files.createTempDirectory("handshake-engine-loopback-test");
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
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
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
            // best effort cleanup
        }
    }

    /** Records every event a {@link HandshakeEngine} pushes, for assertions. */
    private static final class RecordingSink implements TlsEventSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();
        boolean handshakeSecretsReady;
        boolean applicationSecretsReady;
        TlsProtocolError error;
        byte[] peerTransportParameters;
        SessionTicket sessionTicket;
        CipherSuite earlyKeysSuite;
        byte[] earlyTrafficSecret;
        Boolean earlyDataAccepted;

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
            handshakeSecretsReady = true;
        }

        @Override
        public void applicationSecretsReady() {
            applicationSecretsReady = true;
        }

        @Override
        public void peerTransportParameters(byte[] parameters) {
            peerTransportParameters = parameters;
        }

        @Override
        public void protocolError(TlsProtocolError err) {
            error = err;
        }

        @Override
        public void quicEarlyKeysReady(CipherSuite suite, byte[] secret) {
            earlyKeysSuite = suite;
            earlyTrafficSecret = secret;
        }

        @Override
        public void earlyDataAccepted(boolean accepted) {
            earlyDataAccepted = Boolean.valueOf(accepted);
        }

        @Override
        public void sessionTicketReceived(SessionTicket ticket) {
            sessionTicket = ticket;
        }
    }

    /**
     * Drives {@code client} and {@code server} to completion (or failure)
     * by ping-ponging {@link TlsEventSink#handshakeDataReady} output
     * between them, exactly as a caller reassembling a QUIC CRYPTO stream
     * into complete messages would.
     */
    private static void runHandshake(HandshakeEngine client, RecordingSink clientSink,
            HandshakeEngine server, RecordingSink serverSink) {
        client.start(clientSink);
        List<byte[]> toServer = clientSink.drain();
        int rounds = 0;
        while (!(client.isComplete() && server.isComplete()) && !client.isFailed() && !server.isFailed() && rounds < 20) {
            rounds++;
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
        }
    }

    private HandshakeConfig serverConfig(List<X509Certificate> chain, PrivateKey key) {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.SERVER);
        config.setServerCredentials(new ServerCredentials(chain, key));
        config.setApplicationProtocols(Collections.singletonList("h3"));
        config.setLocalTransportParameters(new byte[] { 1, 2, 3, 4 });
        return config;
    }

    private HandshakeConfig clientConfig(List<X509Certificate> trustedChain, String serverName) throws Exception {
        HandshakeConfig config = new HandshakeConfig(HandshakeRole.CLIENT);
        config.setServerName(serverName);
        config.setTrustManager(CertificateVerifier.trustManagerFromCertificates(trustedChain));
        config.setApplicationProtocols(Collections.singletonList("h3"));
        config.setLocalTransportParameters(new byte[] { 5, 6, 7, 8 });
        return config;
    }

    @Test
    public void fullHandshakeCompletesWithMatchingSecretsAndAlpnAndTransportParameters() throws Exception {
        HandshakeEngine client = new HandshakeEngine(clientConfig(ecChain, SERVER_NAME));
        HandshakeEngine server = new HandshakeEngine(serverConfig(ecChain, ecKey));
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();

        runHandshake(client, clientSink, server, serverSink);

        assertNull("client error", clientSink.error);
        assertNull("server error", serverSink.error);
        assertTrue("client complete", client.isComplete());
        assertTrue("server complete", server.isComplete());

        assertArrayEquals(client.getClientHandshakeTrafficSecret(), server.getClientHandshakeTrafficSecret());
        assertArrayEquals(client.getServerHandshakeTrafficSecret(), server.getServerHandshakeTrafficSecret());
        assertArrayEquals(client.getClientApplicationTrafficSecret(), server.getClientApplicationTrafficSecret());
        assertArrayEquals(client.getServerApplicationTrafficSecret(), server.getServerApplicationTrafficSecret());

        assertEquals("h3", client.getNegotiatedApplicationProtocol());
        assertEquals("h3", server.getNegotiatedApplicationProtocol());

        assertArrayEquals(new byte[] { 5, 6, 7, 8 }, serverSink.peerTransportParameters);
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, clientSink.peerTransportParameters);

        assertEquals(1, client.getPeerCertificateChain().size());
        assertEquals(ecChain.get(0), client.getPeerCertificateChain().get(0));
    }

    @Test
    public void handshakeCompletesWithRsaServerCredentials() throws Exception {
        HandshakeEngine client = new HandshakeEngine(clientConfig(rsaChain, SERVER_NAME));
        HandshakeEngine server = new HandshakeEngine(serverConfig(rsaChain, rsaKey));
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();

        runHandshake(client, clientSink, server, serverSink);

        assertNull("client error", clientSink.error);
        assertTrue("client complete", client.isComplete());
        assertTrue("server complete", server.isComplete());
    }

    @Test
    public void handshakeCompletesForEveryGroupAndCipherSuite() throws Exception {
        for (NamedGroup group : NamedGroup.values()) {
            for (CipherSuite suite : CipherSuite.values()) {
                HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME);
                cc.setNamedGroups(Collections.singletonList(group));
                cc.setCipherSuites(Collections.singletonList(suite));
                HandshakeConfig sc = serverConfig(ecChain, ecKey);
                sc.setNamedGroups(Collections.singletonList(group));
                sc.setCipherSuites(Collections.singletonList(suite));

                HandshakeEngine client = new HandshakeEngine(cc);
                HandshakeEngine server = new HandshakeEngine(sc);
                RecordingSink clientSink = new RecordingSink();
                RecordingSink serverSink = new RecordingSink();
                runHandshake(client, clientSink, server, serverSink);

                String label = group + "/" + suite;
                assertNull(label + " client error", clientSink.error);
                assertNull(label + " server error", serverSink.error);
                assertTrue(label + " client complete", client.isComplete());
                assertTrue(label + " server complete", server.isComplete());
                assertEquals(label, suite, client.getNegotiatedCipherSuite());
            }
        }
    }

    @Test
    public void wrongHostnameFailsClientVerification() throws Exception {
        HandshakeEngine client = new HandshakeEngine(clientConfig(ecChain, "wrong.example.com"));
        HandshakeEngine server = new HandshakeEngine(serverConfig(ecChain, ecKey));
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();

        runHandshake(client, clientSink, server, serverSink);

        assertNotNull("expected client-side verification failure", clientSink.error);
        assertFalse(client.isComplete());
    }

    @Test
    public void untrustedChainFailsClientVerification() throws Exception {
        HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME);
        X509TrustManager wrongTrustManager = CertificateVerifier.trustManagerFromCertificates(rsaChain);
        cc.setTrustManager(wrongTrustManager);
        HandshakeEngine client = new HandshakeEngine(cc);
        HandshakeEngine server = new HandshakeEngine(serverConfig(ecChain, ecKey));
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();

        runHandshake(client, clientSink, server, serverSink);

        assertNotNull("expected client-side trust failure", clientSink.error);
        assertFalse(client.isComplete());
    }

    private static byte[] testTicketKey() {
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    // ---- HelloRetryRequest (hand-built messages, per the phase-2 plan's own
    // testing notes: gumdrop's own client always offers a share for every
    // configured group, so a real gumdrop-to-gumdrop handshake never needs
    // a retry -- these drive each side's retry logic directly instead) ----

    @Test
    public void serverSendsHelloRetryRequestOnGroupMismatchThenCompletesAndRejectsASecondMismatch()
            throws Exception {
        HandshakeConfig sc = serverConfig(ecChain, ecKey);
        sc.setNamedGroups(Arrays.asList(NamedGroup.SECP256R1, NamedGroup.X25519));
        sc.setCipherSuites(Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256));
        HandshakeEngine server = new HandshakeEngine(sc);
        RecordingSink serverSink = new RecordingSink();

        List<SignatureScheme> sigAlgs = Collections.singletonList(SignatureScheme.ECDSA_SECP256R1_SHA256);
        List<String> alpn = Collections.singletonList("h3");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);

        KeyExchange x25519Kx = KeyExchange.generate(NamedGroup.X25519);
        HandshakeMessages.ClientHelloParams ch1Params = new HandshakeMessages.ClientHelloParams();
        ch1Params.random = random;
        ch1Params.cipherSuites = Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256);
        ch1Params.groups = Arrays.asList(NamedGroup.X25519, NamedGroup.SECP256R1);
        ch1Params.keyShares = new LinkedHashMap<NamedGroup, byte[]>();
        ch1Params.keyShares.put(NamedGroup.X25519, x25519Kx.getShareBytes());
        ch1Params.signatureAlgorithms = sigAlgs;
        ch1Params.applicationProtocols = alpn;
        ch1Params.serverName = SERVER_NAME;
        byte[] ch1 = HandshakeMessages.buildClientHelloWithBinder(ch1Params, null);

        server.processMessage(ch1, serverSink);
        assertNull("server must retry, not fail, on a recoverable group mismatch: " + serverSink.error,
                serverSink.error);
        assertFalse(server.isComplete());
        List<byte[]> firstFlight = serverSink.drain();
        assertEquals(1, firstFlight.size());
        HandshakeMessages.HelloRetryRequest hrr = HandshakeMessages.parseHelloRetryRequest(firstFlight.get(0));
        assertEquals(NamedGroup.SECP256R1, hrr.selectedGroup);

        // A second ClientHello still missing SECP256R1's share must be rejected outright.
        server.processMessage(ch1, serverSink);
        assertNotNull("still-mismatched retry must fail", serverSink.error);
        assertEquals(AlertDescription.ILLEGAL_PARAMETER, serverSink.error.getAlert());
    }

    @Test
    public void serverCompletesHandshakeAfterCorrectedClientHelloFollowingRetry() throws Exception {
        HandshakeConfig sc = serverConfig(ecChain, ecKey);
        sc.setNamedGroups(Arrays.asList(NamedGroup.SECP256R1, NamedGroup.X25519));
        sc.setCipherSuites(Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256));
        HandshakeEngine server = new HandshakeEngine(sc);
        RecordingSink serverSink = new RecordingSink();

        List<SignatureScheme> sigAlgs = Collections.singletonList(SignatureScheme.ECDSA_SECP256R1_SHA256);
        List<String> alpn = Collections.singletonList("h3");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);

        KeyExchange x25519Kx = KeyExchange.generate(NamedGroup.X25519);
        HandshakeMessages.ClientHelloParams ch1Params = new HandshakeMessages.ClientHelloParams();
        ch1Params.random = random;
        ch1Params.cipherSuites = Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256);
        ch1Params.groups = Arrays.asList(NamedGroup.X25519, NamedGroup.SECP256R1);
        ch1Params.keyShares = new LinkedHashMap<NamedGroup, byte[]>();
        ch1Params.keyShares.put(NamedGroup.X25519, x25519Kx.getShareBytes());
        ch1Params.signatureAlgorithms = sigAlgs;
        ch1Params.applicationProtocols = alpn;
        ch1Params.serverName = SERVER_NAME;
        byte[] ch1 = HandshakeMessages.buildClientHelloWithBinder(ch1Params, null);

        server.processMessage(ch1, serverSink);
        List<byte[]> firstFlight = serverSink.drain();
        byte[] hrrBytes = firstFlight.get(0);

        KeyExchange secp256r1Kx = KeyExchange.generate(NamedGroup.SECP256R1);
        HandshakeMessages.ClientHelloParams ch2Params = new HandshakeMessages.ClientHelloParams();
        ch2Params.random = random;
        ch2Params.cipherSuites = ch1Params.cipherSuites;
        ch2Params.groups = ch1Params.groups;
        ch2Params.keyShares = new LinkedHashMap<NamedGroup, byte[]>();
        ch2Params.keyShares.put(NamedGroup.SECP256R1, secp256r1Kx.getShareBytes());
        ch2Params.signatureAlgorithms = sigAlgs;
        ch2Params.applicationProtocols = alpn;
        ch2Params.serverName = SERVER_NAME;
        byte[] ch2 = HandshakeMessages.buildClientHelloWithBinder(ch2Params, null);

        server.processMessage(ch2, serverSink);
        assertNull("server must accept the corrected ClientHello2: " + serverSink.error, serverSink.error);
        List<byte[]> secondFlight = serverSink.drain();
        assertEquals("ServerHello, EncryptedExtensions, Certificate, CertificateVerify, Finished",
                5, secondFlight.size());

        // Replay the client side of the same retry by hand, using the same
        // low-level primitives HandshakeEngine itself uses, to prove the
        // server's retry-continued transcript/key schedule actually
        // matches what a real peer would compute.
        Transcript clientTranscript = Transcript.create(CipherSuite.TLS_AES_128_GCM_SHA256);
        clientTranscript.update(ch1);
        byte[] ch1Hash = clientTranscript.hash();
        clientTranscript.retry(ch1Hash);
        clientTranscript.update(hrrBytes);
        clientTranscript.update(ch2);

        byte[] serverHelloBytes = secondFlight.get(0);
        HandshakeMessages.ServerHello sh = HandshakeMessages.parseServerHello(serverHelloBytes);
        assertEquals(NamedGroup.SECP256R1, sh.keyShareGroup);
        clientTranscript.update(serverHelloBytes);

        byte[] sharedSecret = secp256r1Kx.agree(sh.keyShareData);
        KeySchedule clientKeySchedule = new KeySchedule(CipherSuite.TLS_AES_128_GCM_SHA256);
        clientKeySchedule.deriveEarlySecret(null);
        clientKeySchedule.deriveHandshakeSecret(sharedSecret, clientTranscript.hash());
        assertArrayEquals(clientKeySchedule.getServerHandshakeTrafficSecret(),
                server.getServerHandshakeTrafficSecret());

        clientTranscript.update(secondFlight.get(1)); // EncryptedExtensions
        clientTranscript.update(secondFlight.get(2)); // Certificate
        clientTranscript.update(secondFlight.get(3)); // CertificateVerify

        byte[] hashBeforeServerFinished = clientTranscript.hash();
        byte[] finishedBytes = secondFlight.get(4);
        byte[] serverVerifyData = HandshakeMessages.parseFinished(finishedBytes);
        byte[] expectedServerVerifyData = clientKeySchedule.computeFinishedVerifyData(
                clientKeySchedule.getServerHandshakeTrafficSecret(), hashBeforeServerFinished);
        assertArrayEquals(expectedServerVerifyData, serverVerifyData);
        clientTranscript.update(finishedBytes);

        byte[] hashThroughServerFinished = clientTranscript.hash();
        clientKeySchedule.deriveMasterSecret();
        clientKeySchedule.deriveApplicationTrafficSecrets(hashThroughServerFinished);
        byte[] clientVerifyData = clientKeySchedule.computeFinishedVerifyData(
                clientKeySchedule.getClientHandshakeTrafficSecret(), hashThroughServerFinished);
        byte[] clientFinished = HandshakeMessages.buildFinished(clientVerifyData);

        server.processMessage(clientFinished, serverSink);
        assertNull("server must accept the client's Finished: " + serverSink.error, serverSink.error);
        assertTrue("server must complete the handshake after a successful retry", server.isComplete());
        assertArrayEquals(clientKeySchedule.getClientApplicationTrafficSecret(),
                server.getClientApplicationTrafficSecret());
    }

    @Test
    public void clientAcceptsHelloRetryRequestAndResendsNarrowedClientHelloThenRejectsASecondOne()
            throws Exception {
        HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME);
        cc.setNamedGroups(Arrays.asList(NamedGroup.X25519, NamedGroup.SECP256R1));
        cc.setCipherSuites(Collections.singletonList(CipherSuite.TLS_AES_128_GCM_SHA256));
        HandshakeEngine client = new HandshakeEngine(cc);
        RecordingSink clientSink = new RecordingSink();

        client.start(clientSink);
        List<byte[]> ch1 = clientSink.drain();
        assertEquals(1, ch1.size());

        byte[] hrr = HandshakeMessages.buildHelloRetryRequest(new byte[0], CipherSuite.TLS_AES_128_GCM_SHA256,
                NamedGroup.SECP256R1, null);
        client.processMessage(hrr, clientSink);
        assertNull("first HelloRetryRequest must be accepted: " + clientSink.error, clientSink.error);
        assertFalse(client.isComplete());
        List<byte[]> ch2 = clientSink.drain();
        assertEquals(1, ch2.size());

        HandshakeMessages.ClientHello parsedCh2 = HandshakeMessages.parseClientHello(ch2.get(0));
        assertEquals("CH2 key_share narrowed to just the retry-requested group",
                1, parsedCh2.keyShares.size());
        assertTrue(parsedCh2.keyShares.containsKey(NamedGroup.SECP256R1));
        assertTrue("CH2 supported_groups unchanged", parsedCh2.supportedGroups.contains(NamedGroup.X25519));

        byte[] hrr2 = HandshakeMessages.buildHelloRetryRequest(new byte[0], CipherSuite.TLS_AES_128_GCM_SHA256,
                NamedGroup.X25519, null);
        client.processMessage(hrr2, clientSink);
        assertNotNull("a second HelloRetryRequest must be rejected", clientSink.error);
        assertEquals(AlertDescription.UNEXPECTED_MESSAGE, clientSink.error.getAlert());
    }

    // ---- session resumption / PSK / 0-RTT ----

    @Test
    public void resumptionWithEarlyDataRoundTripsWithMatchingSecretsBothSides() throws Exception {
        byte[] ticketKey = testTicketKey();

        HandshakeConfig sc1 = serverConfig(ecChain, ecKey);
        sc1.setTicketKeys(new TicketKeys(ticketKey));
        sc1.setEnableEarlyData(true);
        HandshakeConfig cc1 = clientConfig(ecChain, SERVER_NAME);
        HandshakeEngine client1 = new HandshakeEngine(cc1);
        HandshakeEngine server1 = new HandshakeEngine(sc1);
        RecordingSink clientSink1 = new RecordingSink();
        RecordingSink serverSink1 = new RecordingSink();
        runHandshake(client1, clientSink1, server1, serverSink1);

        assertNull(clientSink1.error);
        assertNull(serverSink1.error);
        assertTrue(client1.isComplete());
        assertTrue(server1.isComplete());
        assertFalse("first handshake is not resumed", client1.isResumed());
        assertNotNull("client must have received a session ticket", clientSink1.sessionTicket);
        SessionTicket ticket = clientSink1.sessionTicket;

        HandshakeConfig sc2 = serverConfig(ecChain, ecKey);
        sc2.setTicketKeys(new TicketKeys(ticketKey));
        sc2.setEnableEarlyData(true);
        HandshakeConfig cc2 = clientConfig(ecChain, SERVER_NAME);
        cc2.setSessionTicket(ticket);
        cc2.setEnableEarlyData(true);
        HandshakeEngine client2 = new HandshakeEngine(cc2);
        HandshakeEngine server2 = new HandshakeEngine(sc2);
        RecordingSink clientSink2 = new RecordingSink();
        RecordingSink serverSink2 = new RecordingSink();
        runHandshake(client2, clientSink2, server2, serverSink2);

        assertNull("client2 error", clientSink2.error);
        assertNull("server2 error", serverSink2.error);
        assertTrue("client2 complete", client2.isComplete());
        assertTrue("server2 complete", server2.isComplete());
        assertTrue("second handshake must be reported resumed (client)", client2.isResumed());
        assertTrue("second handshake must be reported resumed (server)", server2.isResumed());
        assertNull("a resumed handshake exchanges no certificate", client2.getPeerCertificateChain());

        assertNotNull("client 0-RTT secret", clientSink2.earlyTrafficSecret);
        assertNotNull("server 0-RTT secret", serverSink2.earlyTrafficSecret);
        assertArrayEquals("client and server must derive the same 0-RTT secret",
                clientSink2.earlyTrafficSecret, serverSink2.earlyTrafficSecret);
        assertEquals(clientSink2.earlyKeysSuite, serverSink2.earlyKeysSuite);
        assertEquals(Boolean.TRUE, clientSink2.earlyDataAccepted);
        assertTrue("server must report early data accepted", server2.wasEarlyDataAccepted());

        assertArrayEquals(client2.getClientHandshakeTrafficSecret(), server2.getClientHandshakeTrafficSecret());
        assertArrayEquals(client2.getServerHandshakeTrafficSecret(), server2.getServerHandshakeTrafficSecret());
        assertArrayEquals(client2.getClientApplicationTrafficSecret(), server2.getClientApplicationTrafficSecret());
        assertArrayEquals(client2.getServerApplicationTrafficSecret(), server2.getServerApplicationTrafficSecret());
    }

    @Test
    public void resumptionStillCompletesWhenServerDeclinesEarlyDataButClientRequestedIt() throws Exception {
        byte[] ticketKey = testTicketKey();

        HandshakeConfig sc1 = serverConfig(ecChain, ecKey);
        sc1.setTicketKeys(new TicketKeys(ticketKey));
        sc1.setEnableEarlyData(true);
        HandshakeConfig cc1 = clientConfig(ecChain, SERVER_NAME);
        HandshakeEngine client1 = new HandshakeEngine(cc1);
        HandshakeEngine server1 = new HandshakeEngine(sc1);
        RecordingSink clientSink1 = new RecordingSink();
        RecordingSink serverSink1 = new RecordingSink();
        runHandshake(client1, clientSink1, server1, serverSink1);
        SessionTicket ticket = clientSink1.sessionTicket;
        assertNotNull(ticket);

        HandshakeConfig sc2 = serverConfig(ecChain, ecKey);
        sc2.setTicketKeys(new TicketKeys(ticketKey));
        sc2.setEnableEarlyData(false); // server does not accept 0-RTT this time
        HandshakeConfig cc2 = clientConfig(ecChain, SERVER_NAME);
        cc2.setSessionTicket(ticket);
        cc2.setEnableEarlyData(true); // client still requests it, not knowing the server's policy
        HandshakeEngine client2 = new HandshakeEngine(cc2);
        HandshakeEngine server2 = new HandshakeEngine(sc2);
        RecordingSink clientSink2 = new RecordingSink();
        RecordingSink serverSink2 = new RecordingSink();
        runHandshake(client2, clientSink2, server2, serverSink2);

        assertNull(clientSink2.error);
        assertNull(serverSink2.error);
        assertTrue(client2.isComplete());
        assertTrue(server2.isComplete());
        assertTrue("resumption (skipping Certificate) must still work", client2.isResumed());
        assertTrue("resumption (skipping Certificate) must still work", server2.isResumed());
        assertNull("a resumed handshake exchanges no certificate", client2.getPeerCertificateChain());

        // The client commits to 0-RTT optimistically, before it knows the
        // server's answer (real 0-RTT semantics) -- so it still computes
        // and exports its own early secret. The server, configured not to
        // accept 0-RTT at all, must not.
        assertNotNull("client still speculatively derives 0-RTT keys", clientSink2.earlyTrafficSecret);
        assertNull("server must not export 0-RTT keys when early data is disabled", serverSink2.earlyTrafficSecret);
        assertEquals(Boolean.FALSE, clientSink2.earlyDataAccepted);
        assertFalse(server2.wasEarlyDataAccepted());
    }

    @Test
    public void antiReplayRejectsZeroRttOnASecondConnectionPresentingTheSameTicket() throws Exception {
        byte[] ticketKey = testTicketKey();

        HandshakeConfig sc1 = serverConfig(ecChain, ecKey);
        sc1.setTicketKeys(new TicketKeys(ticketKey));
        sc1.setEnableEarlyData(true);
        HandshakeConfig cc1 = clientConfig(ecChain, SERVER_NAME);
        HandshakeEngine client1 = new HandshakeEngine(cc1);
        HandshakeEngine server1 = new HandshakeEngine(sc1);
        RecordingSink clientSink1 = new RecordingSink();
        RecordingSink serverSink1 = new RecordingSink();
        runHandshake(client1, clientSink1, server1, serverSink1);
        SessionTicket ticket = clientSink1.sessionTicket;
        assertNotNull(ticket);

        AntiReplay antiReplay = new AntiReplay(30000);

        HandshakeConfig sc2 = serverConfig(ecChain, ecKey);
        sc2.setTicketKeys(new TicketKeys(ticketKey));
        sc2.setEnableEarlyData(true);
        sc2.setAntiReplay(antiReplay);
        HandshakeConfig cc2 = clientConfig(ecChain, SERVER_NAME);
        cc2.setSessionTicket(ticket);
        cc2.setEnableEarlyData(true);
        HandshakeEngine client2 = new HandshakeEngine(cc2);
        HandshakeEngine server2 = new HandshakeEngine(sc2);
        RecordingSink clientSink2 = new RecordingSink();
        RecordingSink serverSink2 = new RecordingSink();
        runHandshake(client2, clientSink2, server2, serverSink2);
        assertNull(serverSink2.error);
        assertTrue("first 0-RTT attempt with this ticket must be accepted", server2.wasEarlyDataAccepted());

        HandshakeConfig sc3 = serverConfig(ecChain, ecKey);
        sc3.setTicketKeys(new TicketKeys(ticketKey));
        sc3.setEnableEarlyData(true);
        sc3.setAntiReplay(antiReplay);
        HandshakeConfig cc3 = clientConfig(ecChain, SERVER_NAME);
        cc3.setSessionTicket(ticket);
        cc3.setEnableEarlyData(true);
        HandshakeEngine client3 = new HandshakeEngine(cc3);
        HandshakeEngine server3 = new HandshakeEngine(sc3);
        RecordingSink clientSink3 = new RecordingSink();
        RecordingSink serverSink3 = new RecordingSink();
        runHandshake(client3, clientSink3, server3, serverSink3);

        assertNull(clientSink3.error);
        assertNull(serverSink3.error);
        assertTrue("full handshake must still complete", client3.isComplete());
        assertTrue("full handshake must still complete", server3.isComplete());
        assertTrue("still resumed via PSK, just without 0-RTT", server3.isResumed());
        assertFalse("a replayed ticket identity must not be accepted for 0-RTT twice",
                server3.wasEarlyDataAccepted());
    }

    // ---- KeyUpdate (forbidden over QUIC, RFC 9001 section 4.6) ----

    @Test
    public void keyUpdateIsRejectedOverQuic() throws Exception {
        HandshakeEngine client = new HandshakeEngine(clientConfig(ecChain, SERVER_NAME));
        HandshakeEngine server = new HandshakeEngine(serverConfig(ecChain, ecKey));
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();
        runHandshake(client, clientSink, server, serverSink);
        assertTrue(client.isComplete());
        assertTrue(server.isComplete());

        assertFalse("requestKeyUpdate is always a no-op over QUIC",
                client.requestKeyUpdate(clientSink, false));

        // handshake type 24 (KeyUpdate), length 1, body {0} (update_not_requested).
        byte[] keyUpdateMessage = { 24, 0, 0, 1, 0 };

        client.processMessage(keyUpdateMessage, clientSink);
        assertNotNull("client must reject a post-handshake KeyUpdate", clientSink.error);
        assertTrue(clientSink.error.getMessage().contains("QUIC"));

        server.processMessage(keyUpdateMessage, serverSink);
        assertNotNull("server must reject a post-handshake KeyUpdate", serverSink.error);
        assertTrue(serverSink.error.getMessage().contains("QUIC"));
    }

    // ---- client certificate authentication (mTLS) ----

    @Test
    public void clientCertificateRequiredAndPresentedCompletes() throws Exception {
        HandshakeConfig sc = serverConfig(ecChain, ecKey);
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
        sc.setClientTrustManager(CertificateVerifier.trustManagerFromCertificates(rsaChain));
        HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME);
        cc.setClientCredentials(new ServerCredentials(rsaChain, rsaKey));

        HandshakeEngine client = new HandshakeEngine(cc);
        HandshakeEngine server = new HandshakeEngine(sc);
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();
        runHandshake(client, clientSink, server, serverSink);

        assertNull("client error", clientSink.error);
        assertNull("server error", serverSink.error);
        assertTrue(client.isComplete());
        assertTrue(server.isComplete());
        assertNotNull("server must see the client's certificate chain", server.getPeerCertificateChain());
        assertEquals(rsaChain.get(0), server.getPeerCertificateChain().get(0));
    }

    @Test
    public void clientCertificateRequiredButUntrustedFailsHandshake() throws Exception {
        HandshakeConfig sc = serverConfig(ecChain, ecKey);
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
        sc.setClientTrustManager(CertificateVerifier.trustManagerFromCertificates(ecChain)); // does not trust rsaChain
        HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME);
        cc.setClientCredentials(new ServerCredentials(rsaChain, rsaKey));

        HandshakeEngine client = new HandshakeEngine(cc);
        HandshakeEngine server = new HandshakeEngine(sc);
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();
        runHandshake(client, clientSink, server, serverSink);

        assertNotNull("server must reject an untrusted client certificate under REQUIRE", serverSink.error);
        assertFalse(server.isComplete());
    }

    @Test
    public void clientCertificateRequestedButNotPresentedStillCompletesUnderRequestPolicy() throws Exception {
        HandshakeConfig sc = serverConfig(ecChain, ecKey);
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUEST);
        HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME); // no clientCredentials configured

        HandshakeEngine client = new HandshakeEngine(cc);
        HandshakeEngine server = new HandshakeEngine(sc);
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();
        runHandshake(client, clientSink, server, serverSink);

        assertNull("client error", clientSink.error);
        assertNull("server error", serverSink.error);
        assertTrue(client.isComplete());
        assertTrue(server.isComplete());
        assertNull("no client certificate was presented", server.getPeerCertificateChain());
    }

    // ---- SNI-based server credential dispatch ----

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

        HandshakeConfig sc1 = serverConfig(ecChain, ecKey);
        sc1.setServerCredentialsResolver(resolver);
        HandshakeConfig cc1 = clientConfig(ecChain, "a.gumdrop.local");
        cc1.setVerifyHostname(false);
        HandshakeEngine client1 = new HandshakeEngine(cc1);
        HandshakeEngine server1 = new HandshakeEngine(sc1);
        RecordingSink clientSink1 = new RecordingSink();
        RecordingSink serverSink1 = new RecordingSink();
        runHandshake(client1, clientSink1, server1, serverSink1);
        assertNull("client1 error", clientSink1.error);
        assertTrue(client1.isComplete());
        assertEquals("SNI \"a\" must resolve to the EC certificate",
                ecChain.get(0), client1.getPeerCertificateChain().get(0));

        HandshakeConfig sc2 = serverConfig(ecChain, ecKey);
        sc2.setServerCredentialsResolver(resolver);
        HandshakeConfig cc2 = clientConfig(rsaChain, "b.gumdrop.local");
        cc2.setVerifyHostname(false);
        HandshakeEngine client2 = new HandshakeEngine(cc2);
        HandshakeEngine server2 = new HandshakeEngine(sc2);
        RecordingSink clientSink2 = new RecordingSink();
        RecordingSink serverSink2 = new RecordingSink();
        runHandshake(client2, clientSink2, server2, serverSink2);
        assertNull("client2 error", clientSink2.error);
        assertTrue(client2.isComplete());
        assertEquals("SNI \"b\" must resolve to the RSA certificate",
                rsaChain.get(0), client2.getPeerCertificateChain().get(0));
    }

}
