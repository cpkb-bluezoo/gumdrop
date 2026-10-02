/*
 * Tls12RecordEngineLoopbackTest.java
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

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.tls.TlsBLoopbackSupport.Peer;
import org.bluezoo.gumdrop.tls.TlsBLoopbackSupport.Sink;

/**
 * TLS 1.2 over TCP: in-memory handshakes between two
 * {@link Tls12RecordEngine} instances for every cipher suite, session
 * ticket resumption, client authentication, fragmentation, byte-at-a-time
 * delivery and failure handling.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Tls12RecordEngineLoopbackTest {

    private static final class Pair {
        final Tls12RecordEngine client;
        final Tls12RecordEngine server;
        final Sink cs = new Sink();
        final Sink ss = new Sink();
        final Peer cp;
        final Peer sp;

        Pair(Tls12HandshakeConfig cc, Tls12HandshakeConfig sc) {
            client = new Tls12RecordEngine(cc);
            server = new Tls12RecordEngine(sc);
            cp = TlsBLoopbackSupport.peer(client);
            sp = TlsBLoopbackSupport.peer(server);
        }

        void handshake(int chunk) {
            server.start(ss);
            client.start(cs);
            TlsBLoopbackSupport.pump(cs, cp, ss, sp, chunk);
        }

        void pump(int chunk) {
            TlsBLoopbackSupport.pump(cs, cp, ss, sp, chunk);
        }
    }

    private Tls12HandshakeConfig serverConfig(boolean rsa) throws Exception {
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.SERVER);
        if (rsa) {
            c.setServerCredentials(TlsBLoopbackSupport.rsaCredentials());
        } else {
            c.setServerCredentials(TlsBLoopbackSupport.ecCredentials());
        }
        return c;
    }

    private Tls12HandshakeConfig clientConfig(boolean rsa) throws Exception {
        Tls12HandshakeConfig c = new Tls12HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TlsBLoopbackSupport.SERVER_NAME);
        if (rsa) {
            c.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.rsaChain()));
        } else {
            c.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.ecChain()));
        }
        return c;
    }

    private Pair connected(boolean rsa) throws Exception {
        Pair p = new Pair(clientConfig(rsa), serverConfig(rsa));
        p.handshake(0);
        assertTrue(p.cs.events.toString(), p.client.isComplete());
        assertTrue(p.ss.events.toString(), p.server.isComplete());
        return p;
    }

    @Test
    public void handshakeAndData() throws Exception {
        Pair p = connected(false);
        assertTrue(p.cs.complete);
        assertTrue(p.ss.complete);
        assertNotNull(p.client.getNegotiatedCipherSuite());
        assertEquals(p.client.getNegotiatedCipherSuite(), p.server.getNegotiatedCipherSuite());
        assertNotNull(p.client.getPeerCertificateChain());
        assertNull(p.client.getNegotiatedApplicationProtocol());
        assertFalse(p.client.isResumed());
        byte[] msg = "tls12 hello".getBytes("UTF-8");
        p.client.sendApplicationData(msg, p.cs);
        p.server.sendApplicationData(msg, 0, msg.length, p.ss);
        p.pump(0);
        assertArrayEquals(msg, p.ss.appBytes());
        assertArrayEquals(msg, p.cs.appBytes());
        p.client.sendApplicationData(new byte[0], p.cs);
        assertTrue(p.cs.outbound.isEmpty());
    }

    @Test
    public void everySuiteNegotiates() throws Exception {
        Tls12CipherSuite[] all = Tls12CipherSuite.values();
        for (int i = 0; i < all.length; i++) {
            boolean rsa = all[i].name().indexOf("_RSA_") >= 0;
            Tls12HandshakeConfig cc = clientConfig(rsa);
            cc.setCipherSuites(Collections.singletonList(all[i]));
            Tls12HandshakeConfig sc = serverConfig(rsa);
            Pair p = new Pair(cc, sc);
            p.handshake(0);
            assertTrue(all[i] + " " + p.cs.events + p.ss.events, p.client.isComplete());
            assertEquals(all[i], p.server.getNegotiatedCipherSuite());
            byte[] msg = new byte[2000];
            Arrays.fill(msg, (byte) i);
            p.client.sendApplicationData(msg, p.cs);
            p.pump(0);
            assertArrayEquals(msg, p.ss.appBytes());
        }
    }

    @Test
    public void handshakeOneByteAtATime() throws Exception {
        Pair p = new Pair(clientConfig(false), serverConfig(false));
        p.handshake(1);
        assertTrue(p.client.isComplete());
        assertTrue(p.server.isComplete());
        byte[] msg = new byte[400];
        Arrays.fill(msg, (byte) 7);
        p.server.sendApplicationData(msg, p.ss);
        p.pump(1);
        assertArrayEquals(msg, p.cs.appBytes());
    }

    @Test
    public void largeWriteIsFragmentedAndReassembled() throws Exception {
        Pair p = connected(false);
        byte[] big = new byte[60000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 7);
        }
        p.client.sendApplicationData(big, p.cs);
        p.pump(0);
        assertArrayEquals(big, p.ss.appBytes());
    }

    @Test
    public void alpnNegotiation() throws Exception {
        Tls12HandshakeConfig cc = clientConfig(false);
        cc.setApplicationProtocols(Arrays.asList("h2", "http/1.1"));
        Tls12HandshakeConfig sc = serverConfig(false);
        sc.setApplicationProtocols(Collections.singletonList("h2"));
        Pair p = new Pair(cc, sc);
        p.handshake(0);
        assertEquals("h2", p.client.getNegotiatedApplicationProtocol());
        assertEquals("h2", p.server.getNegotiatedApplicationProtocol());
    }

    @Test
    public void sessionTicketResumption() throws Exception {
        TicketKeys keys = new TicketKeys(new byte[16]);
        Tls12ClientTicketStore store = new Tls12ClientTicketStore();
        Tls12HandshakeConfig cc = clientConfig(false);
        cc.setClientTicketStore(store);
        Tls12HandshakeConfig sc = serverConfig(false);
        sc.setTicketKeys(keys);
        Pair first = new Pair(cc, sc);
        first.handshake(0);
        assertTrue(first.client.isComplete());
        assertFalse(first.client.isResumed());

        Tls12HandshakeConfig cc2 = clientConfig(false);
        cc2.setClientTicketStore(store);
        Tls12HandshakeConfig sc2 = serverConfig(false);
        sc2.setTicketKeys(keys);
        Pair second = new Pair(cc2, sc2);
        second.handshake(0);
        assertTrue(second.cs.events.toString(), second.client.isComplete());
        assertTrue(second.client.isResumed());
        assertTrue(second.server.isResumed());
        byte[] msg = "resumed".getBytes("UTF-8");
        second.client.sendApplicationData(msg, second.cs);
        second.pump(0);
        assertArrayEquals(msg, second.ss.appBytes());
    }

    @Test
    public void resumptionWithUnknownTicketKeyFallsBackToFullHandshake() throws Exception {
        Tls12ClientTicketStore store = new Tls12ClientTicketStore();
        Tls12HandshakeConfig cc = clientConfig(false);
        cc.setClientTicketStore(store);
        Tls12HandshakeConfig sc = serverConfig(false);
        sc.setTicketKeys(new TicketKeys(new byte[16]));
        Pair first = new Pair(cc, sc);
        first.handshake(0);
        assertTrue(first.client.isComplete());

        byte[] other = new byte[16];
        Arrays.fill(other, (byte) 9);
        Tls12HandshakeConfig cc2 = clientConfig(false);
        cc2.setClientTicketStore(store);
        Tls12HandshakeConfig sc2 = serverConfig(false);
        sc2.setTicketKeys(new TicketKeys(other));
        Pair second = new Pair(cc2, sc2);
        second.handshake(0);
        assertTrue(second.client.isComplete());
        assertFalse(second.client.isResumed());
    }

    @Test
    public void clientAuthRequired() throws Exception {
        Tls12HandshakeConfig sc = serverConfig(false);
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
        sc.setClientTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.rsaChain()));
        Tls12HandshakeConfig cc = clientConfig(false);
        cc.setClientCredentials(TlsBLoopbackSupport.rsaCredentials());
        Pair p = new Pair(cc, sc);
        p.handshake(0);
        assertTrue(p.ss.events.toString() + p.cs.events, p.server.isComplete());
        assertNotNull(p.server.getPeerCertificateChain());
    }

    @Test
    public void clientAuthRequiredWithoutCertificateFails() throws Exception {
        Tls12HandshakeConfig sc = serverConfig(false);
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
        sc.setClientTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.rsaChain()));
        Pair p = new Pair(clientConfig(false), sc);
        p.handshake(0);
        assertFalse(p.server.isComplete());
        assertNotNull(p.ss.error);
    }

    @Test
    public void clientAuthRequestedWithoutCertificateSucceeds() throws Exception {
        Tls12HandshakeConfig sc = serverConfig(false);
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUEST);
        sc.setClientTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.rsaChain()));
        Pair p = new Pair(clientConfig(false), sc);
        p.handshake(0);
        assertTrue(p.server.isComplete());
    }

    @Test
    public void clientAuthUntrustedCertificateFails() throws Exception {
        Tls12HandshakeConfig sc = serverConfig(false);
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
        sc.setClientTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.ecChain()));
        Tls12HandshakeConfig cc = clientConfig(false);
        cc.setClientCredentials(TlsBLoopbackSupport.rsaCredentials());
        Pair p = new Pair(cc, sc);
        p.handshake(0);
        assertFalse(p.server.isComplete());
        assertNotNull(p.ss.error);
    }

    @Test
    public void untrustedServerFails() throws Exception {
        Pair p = new Pair(clientConfig(true), serverConfig(false));
        p.handshake(0);
        assertFalse(p.client.isComplete());
        assertNotNull(p.cs.error);
    }

    @Test
    public void wrongHostnameFails() throws Exception {
        Tls12HandshakeConfig cc = clientConfig(false);
        cc.setServerName("nope.example.org");
        Pair p = new Pair(cc, serverConfig(false));
        p.handshake(0);
        assertFalse(p.client.isComplete());
        assertNotNull(p.cs.error);
    }

    @Test
    public void noCommonSuiteFails() throws Exception {
        Tls12HandshakeConfig cc = clientConfig(false);
        cc.setCipherSuites(Collections.singletonList(Tls12CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256));
        Pair p = new Pair(cc, serverConfig(false));
        p.handshake(0);
        assertFalse(p.client.isComplete());
        assertNotNull(p.ss.error);
    }

    @Test
    public void closeNotify() throws Exception {
        Pair p = connected(false);
        p.client.sendCloseNotify(p.cs);
        p.pump(0);
        assertTrue(p.ss.peerClosed);
    }

    @Test
    public void tamperedRecordIsFatalAndLatches() throws Exception {
        Pair p = connected(false);
        p.client.sendApplicationData("x".getBytes("UTF-8"), p.cs);
        byte[] wire = p.cs.takeOutbound();
        wire[wire.length - 1] ^= 1;
        p.server.feedCiphertext(wire, p.ss);
        assertNotNull(p.ss.error);
        int n = p.ss.events.size();
        p.server.feedCiphertext(wire, p.ss);
        assertEquals(n, p.ss.events.size());
        p.server.sendApplicationData(new byte[] {1}, p.ss);
        p.server.sendCloseNotify(p.ss);
    }

    @Test
    public void garbageIsFatal() throws Exception {
        Pair p = new Pair(clientConfig(false), serverConfig(false));
        p.server.start(p.ss);
        p.server.feedCiphertext(new byte[] {(byte) 0xee, 3, 3, 0, 1, 0}, p.ss);
        assertNotNull(p.ss.error);
    }

    @Test
    public void oversizedRecordIsFatal() throws Exception {
        Pair p = new Pair(clientConfig(false), serverConfig(false));
        p.server.start(p.ss);
        p.server.feedCiphertext(new byte[] {22, 3, 3, (byte) 0xff, (byte) 0xff}, p.ss);
        assertNotNull(p.ss.error);
    }

    @Test
    public void plaintextAlertsFromPeer() throws Exception {
        Pair p = new Pair(clientConfig(false), serverConfig(false));
        p.server.start(p.ss);
        p.server.feedCiphertext(new byte[] {21, 3, 3, 0, 2, 2, 40}, p.ss);
        assertNotNull(p.ss.error);
        Pair q = new Pair(clientConfig(false), serverConfig(false));
        q.server.start(q.ss);
        q.server.feedCiphertext(new byte[] {21, 3, 3, 0, 2, 1, 0}, q.ss);
        assertTrue(q.ss.peerClosed);
    }

    @Test
    public void sendBeforeHandshakeIsError() throws Exception {
        Pair p = new Pair(clientConfig(false), serverConfig(false));
        p.client.sendApplicationData(new byte[] {1}, p.cs);
        assertNotNull(p.cs.error);
    }

    @Test
    public void malformedHandshakeMessagesAreFatal() throws Exception {
        Pair p = new Pair(clientConfig(false), serverConfig(false));
        p.server.start(p.ss);
        p.server.feedCiphertext(new byte[] {22, 3, 1, 0, 6, 1, 0, 0, 2, 3, 3}, p.ss);
        assertNotNull(p.ss.error);
        Pair q = new Pair(clientConfig(false), serverConfig(false));
        q.server.start(q.ss);
        q.server.feedCiphertext(new byte[] {22, 3, 1, 0, 4, 20, 0, 0, 0}, q.ss);
        assertNotNull(q.ss.error);
    }

    @Test
    public void recordSizeLimitDisabled() throws Exception {
        Tls12HandshakeConfig cc = clientConfig(false);
        cc.setRecordSizeLimitEnabled(false);
        Tls12HandshakeConfig sc = serverConfig(false);
        sc.setRecordSizeLimitEnabled(false);
        Pair p = new Pair(cc, sc);
        p.handshake(0);
        assertTrue(p.client.isComplete());
    }

    @Test
    public void smallRecordSizeLimitFragmentsOutput() throws Exception {
        Tls12HandshakeConfig cc = clientConfig(false);
        cc.setRecordSizeLimit(256);
        Pair p = new Pair(cc, serverConfig(false));
        p.handshake(0);
        assertTrue(p.client.isComplete());
        byte[] msg = new byte[3000];
        Arrays.fill(msg, (byte) 5);
        p.server.sendApplicationData(msg, p.ss);
        p.pump(0);
        assertArrayEquals(msg, p.cs.appBytes());
    }

    @Test
    public void clientHelloWireAccessor() throws Exception {
        Pair p = new Pair(clientConfig(false), serverConfig(false));
        p.client.start(p.cs);
        assertTrue(p.cs.outbound.size() > 0);
    }
}
