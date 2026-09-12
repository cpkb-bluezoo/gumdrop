/*
 * Tls12HandshakeEngineLoopbackTest.java
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
import java.util.HashMap;
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

/**
 * Drives two {@link Tls12HandshakeEngine}s -- a client and a server,
 * talking only through Java byte arrays, no transport of any kind --
 * through a complete TLS 1.2 handshake (RFC 5246 section 7.3, RFC 4492
 * ECDHE), proving the whole reactive engine (key exchange, the PRF-based
 * key schedule, certificate verification, {@code Finished} verify-data,
 * mTLS, SNI dispatch, ALPN, and RFC 5077 resumption) is internally
 * consistent before it is ever wired into a real TCP connection. Directly
 * ports hopf's own already-proven {@code tls12::engine} test module.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Tls12HandshakeEngineLoopbackTest {

    private static final String SERVER_NAME = "test.gumdrop.local";

    private static Path certsDirectory;
    private static List<X509Certificate> ecChain;
    private static PrivateKey ecKey;
    private static List<X509Certificate> rsaChain;
    private static PrivateKey rsaKey;

    @BeforeClass
    public static void generateCertificates() throws Exception {
        certsDirectory = Files.createTempDirectory("tls12-handshake-engine-loopback-test");
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

    /** Records every event a {@link Tls12HandshakeEngine} pushes, for assertions. */
    private static final class RecordingSink implements Tls12EventSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();
        boolean ccsSent;
        boolean handshakeComplete;
        TlsProtocolError error;
        Tls12CipherSuite keysReadySuite;
        DirectionalKeyMaterial keysReadyClient;
        DirectionalKeyMaterial keysReadyServer;

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
        public void keysReady(Tls12CipherSuite cipher, DirectionalKeyMaterial client, DirectionalKeyMaterial server) {
            keysReadySuite = cipher;
            keysReadyClient = client;
            keysReadyServer = server;
        }

        @Override
        public void sendChangeCipherSpec() {
            ccsSent = true;
        }

        @Override
        public void handshakeComplete() {
            handshakeComplete = true;
        }

        @Override
        public void protocolError(TlsProtocolError err) {
            error = err;
        }
    }

    /**
     * Drives {@code client} and {@code server} to completion (or failure)
     * by ping-ponging {@link Tls12EventSink#handshakeDataReady} output
     * between them -- {@code ChangeCipherSpec} is a record-layer-only
     * event neither side ever sees as a "message" (see
     * {@link Tls12EventSink#sendChangeCipherSpec}'s own doc comment), so
     * the loopback needs no wire simulation for it.
     */
    private static void runHandshake(Tls12HandshakeEngine client, RecordingSink clientSink,
            Tls12HandshakeEngine server, RecordingSink serverSink) {
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

    private Tls12HandshakeConfig serverConfig(List<X509Certificate> chain, PrivateKey key) {
        Tls12HandshakeConfig config = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        config.setServerCredentials(new ServerCredentials(chain, key));
        return config;
    }

    private Tls12HandshakeConfig clientConfig(List<X509Certificate> trustedChain, String serverName) throws Exception {
        Tls12HandshakeConfig config = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        config.setServerName(serverName);
        config.setTrustManager(CertificateVerifier.trustManagerFromCertificates(trustedChain));
        return config;
    }

    @Test
    public void fullHandshakeCompletesWithMatchingKeyMaterial() throws Exception {
        Tls12HandshakeEngine client = new Tls12HandshakeEngine(clientConfig(ecChain, SERVER_NAME));
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(serverConfig(ecChain, ecKey));
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();

        runHandshake(client, clientSink, server, serverSink);

        assertNull("client error", clientSink.error);
        assertNull("server error", serverSink.error);
        assertTrue("client complete", client.isComplete());
        assertTrue("server complete", server.isComplete());
        assertTrue(clientSink.handshakeComplete);
        assertTrue(serverSink.handshakeComplete);
        assertTrue(clientSink.ccsSent);
        assertTrue(serverSink.ccsSent);

        assertEquals(clientSink.keysReadySuite, serverSink.keysReadySuite);
        assertArrayEquals(clientSink.keysReadyClient.key, serverSink.keysReadyClient.key);
        assertArrayEquals(clientSink.keysReadyServer.key, serverSink.keysReadyServer.key);

        assertEquals(1, client.getPeerCertificateChain().size());
        assertEquals(ecChain.get(0), client.getPeerCertificateChain().get(0));
        assertFalse(client.isResumed());
        assertFalse(server.isResumed());
    }

    @Test
    public void handshakeCompletesForEveryCipherSuite() throws Exception {
        for (Tls12CipherSuite suite : Tls12CipherSuite.values()) {
            List<X509Certificate> chain = "EC".equals(suite.getKeyType()) ? ecChain : rsaChain;
            PrivateKey key = "EC".equals(suite.getKeyType()) ? ecKey : rsaKey;

            Tls12HandshakeConfig cc = clientConfig(chain, SERVER_NAME);
            cc.setCipherSuites(Collections.singletonList(suite));
            Tls12HandshakeConfig sc = serverConfig(chain, key);
            sc.setCipherSuites(Collections.singletonList(suite));

            Tls12HandshakeEngine client = new Tls12HandshakeEngine(cc);
            Tls12HandshakeEngine server = new Tls12HandshakeEngine(sc);
            RecordingSink clientSink = new RecordingSink();
            RecordingSink serverSink = new RecordingSink();
            runHandshake(client, clientSink, server, serverSink);

            assertNull(suite + " client error", clientSink.error);
            assertNull(suite + " server error", serverSink.error);
            assertTrue(suite + " client complete", client.isComplete());
            assertTrue(suite + " server complete", server.isComplete());
            assertEquals(suite, server.getNegotiatedCipherSuite());
        }
    }

    @Test
    public void handshakeCompletesWithRsaServerCredentials() throws Exception {
        Tls12HandshakeEngine client = new Tls12HandshakeEngine(clientConfig(rsaChain, SERVER_NAME));
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(serverConfig(rsaChain, rsaKey));
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();

        runHandshake(client, clientSink, server, serverSink);

        assertNull("client error", clientSink.error);
        assertTrue("client complete", client.isComplete());
        assertTrue("server complete", server.isComplete());
    }

    @Test
    public void wrongHostnameFailsClientVerification() throws Exception {
        Tls12HandshakeEngine client = new Tls12HandshakeEngine(clientConfig(ecChain, "wrong.example.com"));
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(serverConfig(ecChain, ecKey));
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();

        runHandshake(client, clientSink, server, serverSink);

        assertNotNull("expected client-side verification failure", clientSink.error);
        assertFalse(client.isComplete());
    }

    @Test
    public void untrustedChainFailsClientVerification() throws Exception {
        Tls12HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME);
        X509TrustManager wrongTrustManager = CertificateVerifier.trustManagerFromCertificates(rsaChain);
        cc.setTrustManager(wrongTrustManager);
        Tls12HandshakeEngine client = new Tls12HandshakeEngine(cc);
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(serverConfig(ecChain, ecKey));
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();

        runHandshake(client, clientSink, server, serverSink);

        assertNotNull("expected client-side trust failure", clientSink.error);
        assertFalse(client.isComplete());
    }

    // ---- client certificate authentication (mTLS) ----

    @Test
    public void clientCertificateRequiredAndPresentedCompletes() throws Exception {
        Tls12HandshakeConfig sc = serverConfig(ecChain, ecKey);
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
        sc.setClientTrustManager(CertificateVerifier.trustManagerFromCertificates(rsaChain));
        Tls12HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME);
        cc.setClientCredentials(new ServerCredentials(rsaChain, rsaKey));

        Tls12HandshakeEngine client = new Tls12HandshakeEngine(cc);
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(sc);
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
        Tls12HandshakeConfig sc = serverConfig(ecChain, ecKey);
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
        sc.setClientTrustManager(CertificateVerifier.trustManagerFromCertificates(ecChain)); // does not trust rsaChain
        Tls12HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME);
        cc.setClientCredentials(new ServerCredentials(rsaChain, rsaKey));

        Tls12HandshakeEngine client = new Tls12HandshakeEngine(cc);
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(sc);
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();
        runHandshake(client, clientSink, server, serverSink);

        assertNotNull("server must reject an untrusted client certificate under REQUIRE", serverSink.error);
        assertFalse(server.isComplete());
    }

    @Test
    public void clientCertificateRequestedButNotPresentedStillCompletesUnderRequestPolicy() throws Exception {
        Tls12HandshakeConfig sc = serverConfig(ecChain, ecKey);
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUEST);
        Tls12HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME); // no clientCredentials configured

        Tls12HandshakeEngine client = new Tls12HandshakeEngine(cc);
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(sc);
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

        Tls12HandshakeConfig sc1 = serverConfig(ecChain, ecKey);
        sc1.setServerCredentialsResolver(resolver);
        Tls12HandshakeConfig cc1 = clientConfig(ecChain, "a.gumdrop.local");
        cc1.setVerifyHostname(false);
        Tls12HandshakeEngine client1 = new Tls12HandshakeEngine(cc1);
        Tls12HandshakeEngine server1 = new Tls12HandshakeEngine(sc1);
        RecordingSink clientSink1 = new RecordingSink();
        RecordingSink serverSink1 = new RecordingSink();
        runHandshake(client1, clientSink1, server1, serverSink1);
        assertNull("client1 error", clientSink1.error);
        assertTrue(client1.isComplete());
        assertEquals("SNI \"a\" must resolve to the EC certificate",
                ecChain.get(0), client1.getPeerCertificateChain().get(0));

        Tls12HandshakeConfig sc2 = serverConfig(ecChain, ecKey);
        sc2.setServerCredentialsResolver(resolver);
        Tls12HandshakeConfig cc2 = clientConfig(rsaChain, "b.gumdrop.local");
        cc2.setVerifyHostname(false);
        Tls12HandshakeEngine client2 = new Tls12HandshakeEngine(cc2);
        Tls12HandshakeEngine server2 = new Tls12HandshakeEngine(sc2);
        RecordingSink clientSink2 = new RecordingSink();
        RecordingSink serverSink2 = new RecordingSink();
        runHandshake(client2, clientSink2, server2, serverSink2);
        assertNull("client2 error", clientSink2.error);
        assertTrue(client2.isComplete());
        assertEquals("SNI \"b\" must resolve to the RSA certificate",
                rsaChain.get(0), client2.getPeerCertificateChain().get(0));
    }

    // ---- ALPN (RFC 7301) ----

    @Test
    public void alpnNegotiatesServersMostPreferredOverlap() throws Exception {
        Tls12HandshakeConfig sc = serverConfig(ecChain, ecKey);
        sc.setApplicationProtocols(Arrays.asList("h2", "http/1.1"));
        Tls12HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME);
        cc.setApplicationProtocols(Arrays.asList("http/1.1", "h2"));

        Tls12HandshakeEngine client = new Tls12HandshakeEngine(cc);
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(sc);
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();
        runHandshake(client, clientSink, server, serverSink);

        assertNull(clientSink.error);
        assertTrue(client.isComplete());
        assertEquals("server preference (h2) wins over client's list order", "h2", client.getNegotiatedApplicationProtocol());
        assertEquals("h2", server.getNegotiatedApplicationProtocol());
    }

    @Test
    public void alpnZeroOverlapFailsHandshakeWhenServerHasProtocolsConfigured() throws Exception {
        Tls12HandshakeConfig sc = serverConfig(ecChain, ecKey);
        sc.setApplicationProtocols(Collections.singletonList("h2"));
        Tls12HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME);
        cc.setApplicationProtocols(Collections.singletonList("spdy/1"));

        Tls12HandshakeEngine client = new Tls12HandshakeEngine(cc);
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(sc);
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();
        runHandshake(client, clientSink, server, serverSink);

        assertNotNull("zero ALPN overlap must fail the handshake", serverSink.error);
        assertEquals(AlertDescription.NO_APPLICATION_PROTOCOL, serverSink.error.getAlert());
    }

    @Test
    public void emptyServerProtocolListNegotiatesNothingWithoutFailing() throws Exception {
        Tls12HandshakeConfig sc = serverConfig(ecChain, ecKey); // no protocols configured
        Tls12HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME);
        cc.setApplicationProtocols(Collections.singletonList("h2"));

        Tls12HandshakeEngine client = new Tls12HandshakeEngine(cc);
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(sc);
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();
        runHandshake(client, clientSink, server, serverSink);

        assertNull(serverSink.error);
        assertTrue(server.isComplete());
        assertNull(server.getNegotiatedApplicationProtocol());
        assertNull(client.getNegotiatedApplicationProtocol());
    }

    @Test
    public void clientWithoutAlpnExtensionCompletesWhenServerHasProtocolsConfigured() throws Exception {
        Tls12HandshakeConfig sc = serverConfig(ecChain, ecKey);
        sc.setApplicationProtocols(Arrays.asList("h2", "http/1.1"));
        Tls12HandshakeConfig cc = clientConfig(ecChain, SERVER_NAME);

        Tls12HandshakeEngine client = new Tls12HandshakeEngine(cc);
        Tls12HandshakeEngine server = new Tls12HandshakeEngine(sc);
        RecordingSink clientSink = new RecordingSink();
        RecordingSink serverSink = new RecordingSink();
        runHandshake(client, clientSink, server, serverSink);

        assertNull(serverSink.error);
        assertNull(clientSink.error);
        assertTrue(client.isComplete());
        assertTrue(server.isComplete());
        assertNull("RFC 7301: no ALPN when the client omits the extension",
                server.getNegotiatedApplicationProtocol());
        assertNull(client.getNegotiatedApplicationProtocol());
    }

    // ---- RFC 5077 session ticket resumption ----

    private static byte[] testTicketKey() {
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    @Test
    public void resumptionRoundTripsWithMatchingMasterSecret() throws Exception {
        byte[] ticketKey = testTicketKey();

        Tls12HandshakeConfig sc1 = serverConfig(ecChain, ecKey);
        sc1.setTicketKeys(new TicketKeys(ticketKey));
        Tls12HandshakeConfig cc1 = clientConfig(ecChain, SERVER_NAME);
        Tls12ClientTicketStore store = new Tls12ClientTicketStore();
        cc1.setClientTicketStore(store);
        Tls12HandshakeEngine client1 = new Tls12HandshakeEngine(cc1);
        Tls12HandshakeEngine server1 = new Tls12HandshakeEngine(sc1);
        RecordingSink clientSink1 = new RecordingSink();
        RecordingSink serverSink1 = new RecordingSink();
        runHandshake(client1, clientSink1, server1, serverSink1);

        assertNull(clientSink1.error);
        assertNull(serverSink1.error);
        assertTrue(client1.isComplete());
        assertTrue(server1.isComplete());
        assertFalse("first handshake is not resumed", client1.isResumed());
        assertNotNull("client must have cached a ticket", store.get(SERVER_NAME));

        Tls12HandshakeConfig sc2 = serverConfig(ecChain, ecKey);
        sc2.setTicketKeys(new TicketKeys(ticketKey));
        Tls12HandshakeConfig cc2 = clientConfig(ecChain, SERVER_NAME);
        cc2.setClientTicketStore(store);
        Tls12HandshakeEngine client2 = new Tls12HandshakeEngine(cc2);
        Tls12HandshakeEngine server2 = new Tls12HandshakeEngine(sc2);
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
        assertArrayEquals(clientSink2.keysReadyClient.key, serverSink2.keysReadyClient.key);
    }

    @Test
    public void tamperedTicketFallsBackToFullHandshakeRatherThanFailing() throws Exception {
        byte[] ticketKey = testTicketKey();
        byte[] differentKey = new byte[16]; // wrong key: opening will fail, treated as "no matching ticket"

        Tls12HandshakeConfig sc1 = serverConfig(ecChain, ecKey);
        sc1.setTicketKeys(new TicketKeys(ticketKey));
        Tls12HandshakeConfig cc1 = clientConfig(ecChain, SERVER_NAME);
        Tls12ClientTicketStore store = new Tls12ClientTicketStore();
        cc1.setClientTicketStore(store);
        Tls12HandshakeEngine client1 = new Tls12HandshakeEngine(cc1);
        Tls12HandshakeEngine server1 = new Tls12HandshakeEngine(sc1);
        RecordingSink clientSink1 = new RecordingSink();
        RecordingSink serverSink1 = new RecordingSink();
        runHandshake(client1, clientSink1, server1, serverSink1);
        assertNotNull(store.get(SERVER_NAME));

        // Server rotated to an unrelated key between the two connections --
        // the offered ticket can no longer be opened.
        Tls12HandshakeConfig sc2 = serverConfig(ecChain, ecKey);
        sc2.setTicketKeys(new TicketKeys(differentKey));
        Tls12HandshakeConfig cc2 = clientConfig(ecChain, SERVER_NAME);
        cc2.setClientTicketStore(store);
        Tls12HandshakeEngine client2 = new Tls12HandshakeEngine(cc2);
        Tls12HandshakeEngine server2 = new Tls12HandshakeEngine(sc2);
        RecordingSink clientSink2 = new RecordingSink();
        RecordingSink serverSink2 = new RecordingSink();
        runHandshake(client2, clientSink2, server2, serverSink2);

        assertNull("must not fail the connection, just fall back to a full handshake", serverSink2.error);
        assertTrue(client2.isComplete());
        assertTrue(server2.isComplete());
        assertFalse("must be a full handshake, not resumed", server2.isResumed());
    }

}
