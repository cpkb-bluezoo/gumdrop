/*
 * Dtls12CookieExchangeTest.java
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

import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.X509TrustManager;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.IntegrationTestHosts;
import org.bluezoo.gumdrop.TestTlsFiles;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * RFC 6347 HelloVerifyRequest cookie exchange: HMAC binding, parsing,
 * client engine rebuild, and session-level cookie gating.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Dtls12CookieExchangeTest {

    private static final byte[] COOKIE_SECRET = "dtls12-cookie-test-secret".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    /** Must match a DNS SAN on {@code etc/tls/cert.pem} (see {@code integration.tls.names}). */
    private static final String SERVER_NAME = IntegrationTestHosts.TLS_SERVER_NAME;
    private static final InetSocketAddress CLIENT_ADDR = new InetSocketAddress("127.0.0.1", 4242);
    private static final InetSocketAddress SERVER_ADDR = new InetSocketAddress("127.0.0.1", 4343);
    private static final InetSocketAddress WRONG_ADDR = new InetSocketAddress("127.0.0.1", 9999);

    private static List<X509Certificate> ecChain;
    private static PrivateKey ecKey;

    private static X509TrustManager trustManager;

    @BeforeClass
    public static void loadCertificates() throws Exception {
        TestTlsFiles.assumeAvailable();
        ecChain = TestTlsFiles.certificateChain();
        ecKey = TestTlsFiles.privateKey();
        trustManager = TestTlsFiles.trustManager();
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
        clientCfg.setTrustManager(trustManager);
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
        clientTemplate.setTrustManager(trustManager);

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
