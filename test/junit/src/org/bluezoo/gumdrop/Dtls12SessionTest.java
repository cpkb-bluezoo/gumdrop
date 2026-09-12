/*
 * Dtls12SessionTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.bluezoo.gumdrop.tls.Dtls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.Dtls12RecordEngine;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.ServerCredentials;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.TlsProtocolError;
import org.bluezoo.gumdrop.tls.TlsRecordSink;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class Dtls12SessionTest {

    private static final String PASSWORD = "testpass";
    private static final InetSocketAddress CLIENT_ADDR = new InetSocketAddress("127.0.0.1", 1);
    private static final InetSocketAddress SERVER_ADDR = new InetSocketAddress("127.0.0.1", 2);

    private static final String SERVER_NAME = "localhost";

    private static List<X509Certificate> chain;
    private static PrivateKey privateKey;

    @BeforeClass
    public static void generateKeystore() throws Exception {
        Path keystorePath = Files.createTempFile("dtls12-session-test-keystore", ".p12");
        Files.delete(keystorePath);
        runKeytool("-genkeypair",
                "-alias", "dtlstest",
                "-keyalg", "EC", "-groupname", "secp256r1",
                "-validity", "30",
                "-dname", "CN=" + SERVER_NAME,
                "-ext", "san=dns:" + SERVER_NAME,
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", PASSWORD,
                "-keypass", PASSWORD);
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        chain = Collections.singletonList((X509Certificate) keyStore.getCertificate("dtlstest"));
        privateKey = (PrivateKey) keyStore.getKey("dtlstest", PASSWORD.toCharArray());
        Files.delete(keystorePath);
    }

    private static void runKeytool(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "keytool";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool failed");
        }
    }

    private static Dtls12HandshakeConfig clientConfig() throws Exception {
        Tls12HandshakeConfig base = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        base.setServerName(SERVER_NAME);
        base.setTrustManager(CertificateVerifier.trustManagerFromCertificates(chain));
        return new Dtls12HandshakeConfig(base);
    }

    private static Dtls12HandshakeConfig serverConfig() {
        Tls12HandshakeConfig base = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        base.setServerCredentials(new ServerCredentials(chain, privateKey));
        return new Dtls12HandshakeConfig(base);
    }

    private static final class RecordingEndpoint extends UDPEndpoint {
        final Deque<ByteBuffer> sent = new ArrayDeque<ByteBuffer>();
        final List<TimerHandle> timers = new ArrayList<TimerHandle>();
        SecurityInfo securityInfo;

        RecordingEndpoint() {
            super(new ProtocolHandler() {
                @Override public void receive(ByteBuffer data) { }
                @Override public void connected(Endpoint endpoint) { }
                @Override public void disconnected() { }
                @Override public void securityEstablished(SecurityInfo info) { }
                @Override public void error(Exception cause) { }
            });
        }

        @Override
        void sendOwnedRawDatagram(ByteBuffer data, InetSocketAddress dest) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            sent.add(copy);
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            return new TimerHandle() {
                boolean cancelled;

                @Override
                public void cancel() {
                    cancelled = true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };
        }

        @Override
        void notifyDtlsHandshakeComplete(InetSocketAddress peer, SecurityInfo info) {
            this.securityInfo = info;
        }
    }

    private static void pump(RecordingEndpoint clientEp, Dtls12Session client,
            RecordingEndpoint serverEp, Dtls12Session server) {
        for (int round = 0; round < 48; round++) {
            boolean moved = false;
            while (!clientEp.sent.isEmpty()) {
                ByteBuffer buf = clientEp.sent.poll();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                server.receive(data);
                moved = true;
            }
            while (!serverEp.sent.isEmpty()) {
                ByteBuffer buf = serverEp.sent.poll();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                client.receive(data);
                moved = true;
            }
            if (client.isHandshakeComplete() && server.isHandshakeComplete()) {
                return;
            }
            if (!moved) {
                return;
            }
        }
    }

    @Test
    public void handshakeCompletesAndEstablishesSecurity() throws Exception {
        RecordingEndpoint clientEp = new RecordingEndpoint();
        RecordingEndpoint serverEp = new RecordingEndpoint();
        Dtls12Session client = new Dtls12Session(clientConfig(), clientEp, SERVER_ADDR);
        Dtls12Session server = new Dtls12Session(serverConfig(), serverEp, CLIENT_ADDR);
        client.beginHandshake();
        pump(clientEp, client, serverEp, server);
        assertTrue(client.isHandshakeComplete());
        assertTrue(server.isHandshakeComplete());
        assertNotNull(clientEp.securityInfo);
        assertEquals("DTLSv1.2", clientEp.securityInfo.getProtocol());
    }

    @Test
    public void cookieExchangeRequiredBeforeFullHandshake() throws Exception {
        RecordingEndpoint clientEp = new RecordingEndpoint();
        RecordingEndpoint serverEp = new RecordingEndpoint();
        Dtls12HandshakeConfig serverCfg = serverConfig();
        serverCfg.setRequireCookie(true);
        serverCfg.setCookieSecret("dtls12-cookie-test-secret".getBytes(StandardCharsets.US_ASCII));
        Dtls12Session client = new Dtls12Session(clientConfig(), clientEp, SERVER_ADDR);
        Dtls12Session server = new Dtls12Session(serverCfg, serverEp, CLIENT_ADDR);
        client.beginHandshake();
        pump(clientEp, client, serverEp, server);
        assertTrue(client.isHandshakeComplete());
        assertTrue(server.isHandshakeComplete());
    }

    @Test
    public void cookieExchangeRejectsInitialClientHelloWithHelloVerifyRequest() throws Exception {
        RecordingEndpoint serverEp = new RecordingEndpoint();
        Dtls12HandshakeConfig serverCfg = serverConfig();
        serverCfg.setRequireCookie(true);
        serverCfg.setCookieSecret("dtls12-cookie-test-secret".getBytes(StandardCharsets.US_ASCII));
        Dtls12Session server = new Dtls12Session(serverCfg, serverEp, CLIENT_ADDR);

        Tls12HandshakeConfig bareClient = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        bareClient.setDtlsTransport(true);
        bareClient.setServerName(SERVER_NAME);
        bareClient.setTrustManager(CertificateVerifier.trustManagerFromCertificates(chain));
        DatagramCaptureSink rogueSink = new DatagramCaptureSink();
        Dtls12RecordEngine rogueClient = new Dtls12RecordEngine(bareClient, 1024);
        rogueClient.start(rogueSink);
        assertEquals(1, rogueSink.outbound.size());

        server.receive(rogueSink.outbound.get(0));
        assertFalse(server.isHandshakeComplete());
        assertEquals(1, serverEp.sent.size());
        ByteBuffer hvrBuf = serverEp.sent.peek();
        byte[] hvr = new byte[hvrBuf.remaining()];
        hvrBuf.get(hvr);
        assertEquals(22, hvr[0] & 0xff);
    }

    private static final class DatagramCaptureSink implements TlsRecordSink {
        final List<byte[]> outbound = new ArrayList<byte[]>();

        @Override
        public void ciphertextReady(byte[] data) {
            outbound.add(data);
        }

        @Override
        public void applicationDataReady(byte[] plaintext) {
        }

        @Override
        public void handshakeComplete() {
        }

        @Override
        public void protocolError(TlsProtocolError err) {
        }

        @Override
        public void peerClosed() {
        }
    }

}
