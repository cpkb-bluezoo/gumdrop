/*
 * Dtls13SessionTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.bluezoo.gumdrop.tls.Dtls13HandshakeConfig;
import org.bluezoo.gumdrop.tls.DtlsVersion;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.HandshakeRole;
import org.bluezoo.gumdrop.tls.ServerCredentials;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
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

public class Dtls13SessionTest {

    private static final String PASSWORD = "testpass";
    private static final InetSocketAddress CLIENT_ADDR = new InetSocketAddress("127.0.0.1", 1);
    private static final InetSocketAddress SERVER_ADDR = new InetSocketAddress("127.0.0.1", 2);
    private static final InetSocketAddress WRONG_ADDR = new InetSocketAddress("127.0.0.1", 9999);
    private static final byte[] COOKIE_SECRET = "dtls13-session-cookie-secret".getBytes(StandardCharsets.US_ASCII);
    private static final String SERVER_NAME = "localhost";

    private static List<X509Certificate> chain;
    private static PrivateKey privateKey;

    @BeforeClass
    public static void generateKeystore() throws Exception {
        Path keystorePath = Files.createTempFile("dtls13-session-test-keystore", ".p12");
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

    private static Dtls13HandshakeConfig clientConfig() throws Exception {
        HandshakeConfig base = new HandshakeConfig(HandshakeRole.CLIENT);
        base.setServerName(SERVER_NAME);
        base.setTrustManager(CertificateVerifier.trustManagerFromCertificates(chain));
        return new Dtls13HandshakeConfig(base);
    }

    private static Dtls13HandshakeConfig serverConfig() {
        HandshakeConfig base = new HandshakeConfig(HandshakeRole.SERVER);
        base.setServerCredentials(new ServerCredentials(chain, privateKey));
        return new Dtls13HandshakeConfig(base);
    }

    private static final class RecordingEndpoint extends UDPEndpoint {
        final Deque<ByteBuffer> sent = new ArrayDeque<ByteBuffer>();
        SecurityInfo securityInfo;
        boolean usesDtls13;

        RecordingEndpoint(boolean dtls13) {
            super(new ProtocolHandler() {
                @Override public void receive(ByteBuffer data) { }
                @Override public void connected(Endpoint endpoint) { }
                @Override public void disconnected() { }
                @Override public void securityEstablished(SecurityInfo info) { }
                @Override public void error(Exception cause) { }
            });
            usesDtls13 = dtls13;
        }

        @Override
        void setFactory(TransportFactory factory) {
            super.setFactory(factory);
        }

        void initDtls13() {
            UDPTransportFactory factory = new UDPTransportFactory() {
                @Override
                public DtlsVersion getDtlsVersion() {
                    return usesDtls13 ? DtlsVersion.DTLS_1_3 : DtlsVersion.DTLS_1_2;
                }
            };
            setFactory(factory);
            setSecure(true);
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

    private static void pump(RecordingEndpoint clientEp, Dtls13Session client,
            RecordingEndpoint serverEp, Dtls13Session server) {
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
        RecordingEndpoint clientEp = new RecordingEndpoint(true);
        clientEp.initDtls13();
        RecordingEndpoint serverEp = new RecordingEndpoint(true);
        serverEp.initDtls13();
        Dtls13Session client = new Dtls13Session(clientConfig(), clientEp, SERVER_ADDR);
        Dtls13Session server = new Dtls13Session(serverConfig(), serverEp, CLIENT_ADDR);
        client.beginHandshake();
        pump(clientEp, client, serverEp, server);
        assertTrue(client.isHandshakeComplete());
        assertTrue(server.isHandshakeComplete());
        assertNotNull(clientEp.securityInfo);
        assertEquals("DTLSv1.3", clientEp.securityInfo.getProtocol());
    }

    @Test
    public void cookieExchangeCompletesViaHelloRetryRequest() throws Exception {
        RecordingEndpoint clientEp = new RecordingEndpoint(true);
        clientEp.initDtls13();
        RecordingEndpoint serverEp = new RecordingEndpoint(true);
        serverEp.initDtls13();
        Dtls13HandshakeConfig serverCfg = serverConfig();
        serverCfg.setRequireCookie(true);
        serverCfg.setCookieSecret(COOKIE_SECRET);
        Dtls13Session client = new Dtls13Session(clientConfig(), clientEp, SERVER_ADDR);
        Dtls13Session server = new Dtls13Session(serverCfg, serverEp, CLIENT_ADDR);
        client.beginHandshake();
        pump(clientEp, client, serverEp, server);
        assertTrue(client.isHandshakeComplete());
        assertTrue(server.isHandshakeComplete());
    }

    @Test
    public void cookieValidatorBindsToSourceAddress() throws Exception {
        byte[] random = new byte[32];
        for (int i = 0; i < random.length; i++) {
            random[i] = (byte) i;
        }
        Dtls13CookieValidator validator = new Dtls13CookieValidator(COOKIE_SECRET, CLIENT_ADDR);
        byte[] cookie = validator.computeCookie(random);
        assertTrue(validator.validateCookie(random, cookie));
        Dtls13CookieValidator wrongAddress = new Dtls13CookieValidator(COOKIE_SECRET, WRONG_ADDR);
        assertFalse(wrongAddress.validateCookie(random, cookie));
    }

    @Test
    public void udpTransportFactoryBuildsDtls13Configs() throws Exception {
        UDPTransportFactory factory = new UDPTransportFactory();
        factory.setDtlsVersion(DtlsVersion.DTLS_1_3);
        factory.setServerCredentials(new ServerCredentials(chain, privateKey));
        factory.setTrustManager(CertificateVerifier.trustManagerFromCertificates(chain));
        factory.setSecure(true);
        factory.start();
        assertNotNull(factory.buildClientConfig13(SERVER_NAME));
        assertNotNull(factory.getSharedServerConfig13());
        assertEquals(HandshakeRole.CLIENT, factory.buildClientConfig13(SERVER_NAME).getRole());
        assertEquals(HandshakeRole.SERVER, factory.getSharedServerConfig13().getRole());
    }
}
