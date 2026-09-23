/*
 * Dtls13SessionTest.java
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

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.crypto.CertificateVerifier;
import org.bluezoo.gumdrop.tls.Dtls13HandshakeConfig;
import org.bluezoo.gumdrop.tls.EchConfig;
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

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
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

    private static final class RecordingEndpoint extends UdpEndpoint {
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
            UdpTransportFactory factory = new UdpTransportFactory() {
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
        UdpTransportFactory factory = new UdpTransportFactory();
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

    private static final String ECH_PK = "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d";
    private static final String ECH_SK = "4612c550263fc8ad58375df3f557aac531d26850903e55a9f23f21d8534e8ac8";

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static UdpTransportFactory echFactory(EchConfig ech, boolean echRequired) throws Exception {
        Path list = Files.createTempFile("dtls13-ech-list", ".bin");
        Files.write(list, EchConfig.encodeList(new EchConfig[] { ech }));
        Path key = Files.createTempFile("dtls13-ech-key", ".hex");
        Files.write(key, (ECH_SK + "\n").getBytes(StandardCharsets.US_ASCII));
        list.toFile().deleteOnExit();
        key.toFile().deleteOnExit();
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.setDtlsVersion(DtlsVersion.DTLS_1_3);
        factory.setServerCredentials(new ServerCredentials(chain, privateKey));
        factory.setTrustManager(CertificateVerifier.trustManagerFromCertificates(chain));
        factory.setSecure(true);
        factory.setEchConfigListFile(list);
        factory.setEchPrivateKeyFile(key);
        factory.setEchServerRequired(echRequired);
        factory.start();
        return factory;
    }

    private static Dtls13HandshakeConfig echClientConfig(EchConfig ech, boolean offerEch) throws Exception {
        Dtls13HandshakeConfig config = clientConfig();
        if (offerEch) {
            config.getBase().setEchEnabled(true);
            config.getBase().setEchConfig(ech);
            config.getBase().setEchRequired(true);
        }
        return config;
    }

    private static boolean[] runEchHandshake(Dtls13HandshakeConfig serverCfg, Dtls13HandshakeConfig clientCfg)
            throws Exception {
        RecordingEndpoint clientEp = new RecordingEndpoint(true);
        clientEp.initDtls13();
        RecordingEndpoint serverEp = new RecordingEndpoint(true);
        serverEp.initDtls13();
        Dtls13Session client = new Dtls13Session(clientCfg, clientEp, SERVER_ADDR);
        Dtls13Session server = new Dtls13Session(serverCfg, serverEp, CLIENT_ADDR);
        client.beginHandshake();
        pump(clientEp, client, serverEp, server);
        return new boolean[] { client.isHandshakeComplete(), server.isHandshakeComplete() };
    }

    @Test
    public void udpTransportFactoryLoadsEchServerKeysIntoDtls13Config() throws Exception {
        EchConfig ech = EchConfig.createV13(7, hex(ECH_PK), "public.example", 64);
        UdpTransportFactory factory = echFactory(ech, true);
        HandshakeConfig base = factory.getSharedServerConfig13().getBase();
        assertEquals(1, base.getEchServerKeys().size());
        assertEquals(7, base.getEchServerKeys().get(0).getConfig().getConfigId());
        assertTrue(base.isEchServerRequired());
        assertNotNull(base.getEchRetryConfigList());
        HandshakeConfig engineConfig = factory.getSharedServerConfig13().copyBaseForEngine();
        assertEquals("per-connection copy keeps the keys", 1, engineConfig.getEchServerKeys().size());
        assertTrue(engineConfig.isEchServerRequired());
    }

    @Test
    public void udpListenerWithoutEchFilesIsUnchanged() throws Exception {
        UdpTransportFactory factory = new UdpTransportFactory();
        factory.setDtlsVersion(DtlsVersion.DTLS_1_3);
        factory.setServerCredentials(new ServerCredentials(chain, privateKey));
        factory.setTrustManager(CertificateVerifier.trustManagerFromCertificates(chain));
        factory.setSecure(true);
        factory.start();
        HandshakeConfig base = factory.getSharedServerConfig13().getBase();
        assertTrue(base.getEchServerKeys().isEmpty());
        assertFalse(base.isEchServerRequired());
        assertEquals(null, base.getEchRetryConfigList());
    }

    @Test
    public void echHandshakeCompletesOverDtls13WithFactoryConfiguredKeys() throws Exception {
        EchConfig ech = EchConfig.createV13(7, hex(ECH_PK), "public.example", 64);
        UdpTransportFactory factory = echFactory(ech, false);
        // The client insists on ECH (ech_required), so completing proves the
        // server decrypted the inner ClientHello with its configured key.
        boolean[] done = runEchHandshake(factory.getSharedServerConfig13(), echClientConfig(ech, true));
        assertTrue("client complete", done[0]);
        assertTrue("server complete", done[1]);
    }

    @Test
    public void echRequiredListenerRejectsClientHelloWithoutEch() throws Exception {
        EchConfig ech = EchConfig.createV13(7, hex(ECH_PK), "public.example", 64);
        UdpTransportFactory factory = echFactory(ech, true);
        boolean[] done = runEchHandshake(factory.getSharedServerConfig13(), echClientConfig(ech, false));
        assertFalse("client must not complete", done[0]);
        assertFalse("server must not complete", done[1]);
    }

    @Test
    public void listenerWithEchFilesButNotRequiredStillServesClientsWithoutEch() throws Exception {
        EchConfig ech = EchConfig.createV13(7, hex(ECH_PK), "public.example", 64);
        UdpTransportFactory factory = echFactory(ech, false);
        boolean[] done = runEchHandshake(factory.getSharedServerConfig13(), echClientConfig(ech, false));
        assertTrue(done[0]);
        assertTrue(done[1]);
    }

}
