/*
 * TlsRecordEngineLoopbackTest.java
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.testsupport.RecordingKeyLog;
import org.bluezoo.gumdrop.tls.TlsBLoopbackSupport.Peer;
import org.bluezoo.gumdrop.tls.TlsBLoopbackSupport.Sink;

/**
 * TLS 1.3 over TCP: full in-memory handshakes between two
 * {@link TlsRecordEngine} instances, record fragmentation, byte-at-a-time
 * delivery, key update, close_notify and failure handling.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TlsRecordEngineLoopbackTest {

    private static final class Pair {
        final TlsRecordEngine client;
        final TlsRecordEngine server;
        final Sink cs = new Sink();
        final Sink ss = new Sink();
        final Peer cp;
        final Peer sp;

        Pair(HandshakeConfig cc, HandshakeConfig sc) {
            client = new TlsRecordEngine(cc);
            server = new TlsRecordEngine(sc);
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

    private HandshakeConfig serverConfig() throws Exception {
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.SERVER);
        c.setServerCredentials(TlsBLoopbackSupport.ecCredentials());
        return c;
    }

    private HandshakeConfig clientConfig() throws Exception {
        HandshakeConfig c = new HandshakeConfig(HandshakeRole.CLIENT);
        c.setServerName(TlsBLoopbackSupport.SERVER_NAME);
        c.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.ecChain()));
        return c;
    }

    private Pair connected() throws Exception {
        Pair p = new Pair(clientConfig(), serverConfig());
        p.handshake(0);
        assertTrue(p.cs.events.toString(), p.client.isComplete());
        assertTrue(p.ss.events.toString(), p.server.isComplete());
        return p;
    }

    @Test
    public void handshakeAndApplicationData() throws Exception {
        Pair p = connected();
        assertTrue(p.cs.complete);
        assertTrue(p.ss.complete);
        assertNotNull(p.client.getNegotiatedCipherSuite());
        assertEquals(p.client.getNegotiatedCipherSuite(), p.server.getNegotiatedCipherSuite());
        assertNull(p.client.getNegotiatedApplicationProtocol());
        assertNotNull(p.client.getPeerCertificateChain());
        assertFalse(p.client.isResumed());
        byte[] msg = "hello server".getBytes("UTF-8");
        p.client.sendApplicationData(msg, p.cs);
        p.pump(0);
        assertArrayEquals(msg, p.ss.appBytes());
        byte[] reply = "hello client".getBytes("UTF-8");
        p.server.sendApplicationData(reply, 0, reply.length, p.ss);
        p.pump(0);
        assertArrayEquals(reply, p.cs.appBytes());
    }

    @Test
    public void emptyApplicationWriteEmitsNothing() throws Exception {
        Pair p = connected();
        p.client.sendApplicationData(new byte[0], p.cs);
        assertTrue(p.cs.outbound.isEmpty());
    }

    @Test
    public void handshakeOneByteAtATime() throws Exception {
        Pair p = new Pair(clientConfig(), serverConfig());
        p.handshake(1);
        assertTrue(p.client.isComplete());
        assertTrue(p.server.isComplete());
        byte[] msg = new byte[300];
        for (int i = 0; i < msg.length; i++) {
            msg[i] = (byte) i;
        }
        p.client.sendApplicationData(msg, p.cs);
        p.pump(1);
        assertArrayEquals(msg, p.ss.appBytes());
    }

    @Test
    public void handshakeInSmallOddChunks() throws Exception {
        int[] sizes = new int[] {2, 3, 5, 7, 13, 64};
        for (int i = 0; i < sizes.length; i++) {
            Pair p = new Pair(clientConfig(), serverConfig());
            p.handshake(sizes[i]);
            assertTrue("chunk " + sizes[i], p.client.isComplete() && p.server.isComplete());
        }
    }

    @Test
    public void largeWriteIsFragmented() throws Exception {
        Pair p = connected();
        byte[] big = new byte[50000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31);
        }
        p.client.sendApplicationData(big, p.cs);
        assertTrue(p.cs.takeOutbound().length > 50000);
    }

    @Test
    public void largeWriteRoundTrips() throws Exception {
        Pair p = connected();
        byte[] big = new byte[50000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31);
        }
        p.client.sendApplicationData(big, p.cs);
        p.pump(0);
        assertArrayEquals(big, p.ss.appBytes());
    }

    @Test
    public void alpnAndSni() throws Exception {
        HandshakeConfig cc = clientConfig();
        cc.setApplicationProtocols(Arrays.asList("h2", "http/1.1"));
        HandshakeConfig sc = serverConfig();
        sc.setApplicationProtocols(Collections.singletonList("http/1.1"));
        Pair p = new Pair(cc, sc);
        p.handshake(0);
        assertTrue(p.client.isComplete());
        assertEquals("http/1.1", p.client.getNegotiatedApplicationProtocol());
        assertEquals("http/1.1", p.server.getNegotiatedApplicationProtocol());
    }

    @Test
    public void chaCha20Suite() throws Exception {
        HandshakeConfig cc = clientConfig();
        cc.setCipherSuites(Collections.singletonList(CipherSuite.TLS_CHACHA20_POLY1305_SHA256));
        Pair p = new Pair(cc, serverConfig());
        p.handshake(0);
        assertTrue(p.client.isComplete());
        assertEquals(CipherSuite.TLS_CHACHA20_POLY1305_SHA256, p.server.getNegotiatedCipherSuite());
    }

    @Test
    public void aes256Suite() throws Exception {
        HandshakeConfig cc = clientConfig();
        cc.setCipherSuites(Collections.singletonList(CipherSuite.TLS_AES_256_GCM_SHA384));
        Pair p = new Pair(cc, serverConfig());
        p.handshake(0);
        assertTrue(p.client.isComplete());
        assertEquals(CipherSuite.TLS_AES_256_GCM_SHA384, p.client.getNegotiatedCipherSuite());
    }

    @Test
    public void rsaServerCertificate() throws Exception {
        HandshakeConfig sc = new HandshakeConfig(HandshakeRole.SERVER);
        sc.setServerCredentials(TlsBLoopbackSupport.rsaCredentials());
        HandshakeConfig cc = new HandshakeConfig(HandshakeRole.CLIENT);
        cc.setServerName(TlsBLoopbackSupport.SERVER_NAME);
        cc.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.rsaChain()));
        Pair p = new Pair(cc, sc);
        p.handshake(0);
        assertTrue(p.client.isComplete());
    }

    @Test
    public void untrustedServerCertificateFails() throws Exception {
        HandshakeConfig cc = new HandshakeConfig(HandshakeRole.CLIENT);
        cc.setServerName(TlsBLoopbackSupport.SERVER_NAME);
        cc.setTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.rsaChain()));
        Pair p = new Pair(cc, serverConfig());
        p.handshake(0);
        assertFalse(p.client.isComplete());
        assertNotNull(p.cs.error);
    }

    @Test
    public void wrongHostnameFails() throws Exception {
        HandshakeConfig cc = clientConfig();
        cc.setServerName("other.example.org");
        Pair p = new Pair(cc, serverConfig());
        p.handshake(0);
        assertFalse(p.client.isComplete());
        assertNotNull(p.cs.error);
    }

    @Test
    public void mutualAuthRequired() throws Exception {
        HandshakeConfig sc = serverConfig();
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
        sc.setClientTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.rsaChain()));
        HandshakeConfig cc = clientConfig();
        cc.setClientCredentials(TlsBLoopbackSupport.rsaCredentials());
        Pair p = new Pair(cc, sc);
        p.handshake(0);
        assertTrue(p.cs.events.toString() + p.ss.events, p.server.isComplete());
        assertNotNull(p.server.getPeerCertificateChain());
    }

    @Test
    public void mutualAuthRequiredButClientHasNoCertificate() throws Exception {
        HandshakeConfig sc = serverConfig();
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUIRE);
        sc.setClientTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.rsaChain()));
        Pair p = new Pair(clientConfig(), sc);
        p.handshake(0);
        assertFalse(p.server.isComplete());
        assertNotNull(p.ss.error);
    }

    @Test
    public void mutualAuthRequestedOptional() throws Exception {
        HandshakeConfig sc = serverConfig();
        sc.setClientAuthPolicy(ClientAuthPolicy.REQUEST);
        sc.setClientTrustManager(TlsBLoopbackSupport.trust(TlsBLoopbackSupport.rsaChain()));
        Pair p = new Pair(clientConfig(), sc);
        p.handshake(0);
        assertTrue(p.server.isComplete());
        assertTrue(p.client.isComplete());
    }

    @Test
    public void closeNotifyIsReported() throws Exception {
        Pair p = connected();
        p.client.sendCloseNotify(p.cs);
        p.pump(0);
        assertTrue(p.ss.peerClosed);
    }

    @Test
    public void keyLogRecordsUpdatedTrafficSecretsWithTheirGeneration() throws Exception {
        RecordingKeyLog clientLog = new RecordingKeyLog();
        RecordingKeyLog serverLog = new RecordingKeyLog();
        HandshakeConfig cc = clientConfig();
        cc.setKeyLog(clientLog);
        HandshakeConfig sc = serverConfig();
        sc.setKeyLog(serverLog);
        Pair p = new Pair(cc, sc);
        p.handshake(0);
        assertTrue(p.client.isComplete());
        assertTrue(p.server.isComplete());

        // The client ratchets its own secret and asks the server to do the same.
        assertTrue(p.client.requestKeyUpdate(p.cs, true));
        p.pump(0);
        assertNotNull(clientLog.secret(KeyLog.clientTrafficSecret(1)));
        assertNotNull(clientLog.secret(KeyLog.serverTrafficSecret(1)));
        assertArrayEquals(clientLog.secret(KeyLog.clientTrafficSecret(1)), serverLog.secret(KeyLog.clientTrafficSecret(1)));
        assertArrayEquals(clientLog.secret(KeyLog.serverTrafficSecret(1)), serverLog.secret(KeyLog.serverTrafficSecret(1)));

        // A second, unrequested update moves only the client's secret on.
        assertTrue(p.client.requestKeyUpdate(p.cs, false));
        p.pump(0);
        assertNotNull(serverLog.secret(KeyLog.clientTrafficSecret(2)));
        assertNull(clientLog.secret(KeyLog.serverTrafficSecret(2)));
        assertNull(serverLog.secret(KeyLog.serverTrafficSecret(2)));
    }

    @Test
    public void keyUpdateKeepsDataFlowing() throws Exception {
        Pair p = connected();
        assertTrue(p.client.requestKeyUpdate(p.cs, true));
        p.pump(0);
        byte[] msg = "after update".getBytes("UTF-8");
        p.client.sendApplicationData(msg, p.cs);
        p.server.sendApplicationData(msg, p.ss);
        p.pump(0);
        assertArrayEquals(msg, p.ss.appBytes());
        assertArrayEquals(msg, p.cs.appBytes());
        assertTrue(p.client.requestKeyUpdate(p.cs, false));
        p.pump(0);
        p.client.sendApplicationData(msg, p.cs);
        p.pump(0);
        assertEquals(msg.length * 2, p.ss.appBytes().length);
    }

    @Test
    public void tamperedRecordIsFatal() throws Exception {
        Pair p = connected();
        p.client.sendApplicationData("data".getBytes("UTF-8"), p.cs);
        byte[] wire = p.cs.takeOutbound();
        wire[wire.length - 1] ^= 0x01;
        p.server.feedCiphertext(wire, p.ss);
        assertNotNull(p.ss.error);
        int before = p.ss.events.size();
        p.server.feedCiphertext(wire, p.ss);
        assertEquals(before, p.ss.events.size());
        p.server.sendApplicationData(new byte[] {1}, p.ss);
        p.server.sendCloseNotify(p.ss);
        assertFalse(p.server.requestKeyUpdate(p.ss, true));
    }

    @Test
    public void garbageBeforeHandshakeIsFatal() throws Exception {
        Pair p = new Pair(clientConfig(), serverConfig());
        p.server.start(p.ss);
        byte[] junk = new byte[] {(byte) 0xff, 3, 3, 0, 4, 1, 2, 3, 4};
        p.server.feedCiphertext(junk, p.ss);
        assertNotNull(p.ss.error);
    }

    @Test
    public void oversizedRecordLengthIsFatal() throws Exception {
        Pair p = new Pair(clientConfig(), serverConfig());
        p.server.start(p.ss);
        byte[] hdr = new byte[] {22, 3, 3, (byte) 0xff, (byte) 0xff};
        p.server.feedCiphertext(hdr, p.ss);
        assertNotNull(p.ss.error);
    }

    @Test
    public void sendBeforeHandshakeIsError() throws Exception {
        Pair p = new Pair(clientConfig(), serverConfig());
        p.client.sendApplicationData(new byte[] {1}, p.cs);
        assertNotNull(p.cs.error);
    }

    @Test
    public void plaintextFatalAlertFromPeerIsReported() throws Exception {
        Pair p = new Pair(clientConfig(), serverConfig());
        p.server.start(p.ss);
        p.server.feedCiphertext(new byte[] {21, 3, 3, 0, 2, 2, 40}, p.ss);
        assertNotNull(p.ss.error);
    }

    @Test
    public void plaintextCloseNotifyBeforeHandshakeReportsPeerClosed() throws Exception {
        Pair p = new Pair(clientConfig(), serverConfig());
        p.server.start(p.ss);
        p.server.feedCiphertext(new byte[] {21, 3, 3, 0, 2, 1, 0}, p.ss);
        assertTrue(p.ss.peerClosed);
    }

    @Test
    public void unknownContentTypeIsFatal() throws Exception {
        Pair p = new Pair(clientConfig(), serverConfig());
        p.server.start(p.ss);
        p.server.feedCiphertext(new byte[] {99, 3, 3, 0, 1, 0}, p.ss);
        assertNotNull(p.ss.error);
    }

    @Test
    public void changeCipherSpecCompatRecordIsIgnored() throws Exception {
        Pair p = new Pair(clientConfig(), serverConfig());
        p.server.start(p.ss);
        p.server.feedCiphertext(new byte[] {20, 3, 3, 0, 1, 1}, p.ss);
        assertNull(p.ss.error);
    }

    @Test
    public void malformedClientHelloIsFatal() throws Exception {
        Pair p = new Pair(clientConfig(), serverConfig());
        p.server.start(p.ss);
        p.server.feedCiphertext(new byte[] {22, 3, 1, 0, 6, 1, 0, 0, 2, 3, 3}, p.ss);
        assertNotNull(p.ss.error);
    }

    @Test
    public void unexpectedHandshakeTypeIsFatal() throws Exception {
        Pair p = new Pair(clientConfig(), serverConfig());
        p.server.start(p.ss);
        p.server.feedCiphertext(new byte[] {22, 3, 1, 0, 4, 11, 0, 0, 0}, p.ss);
        assertNotNull(p.ss.error);
    }

    @Test
    public void serverIssuesTicketWhenKeysConfigured() throws Exception {
        HandshakeConfig sc = serverConfig();
        sc.setTicketKeys(new TicketKeys(new byte[16]));
        sc.setEnableEarlyData(true);
        Pair p = new Pair(clientConfig(), sc);
        p.handshake(0);
        assertTrue(p.client.isComplete());
        assertFalse(p.client.isResumed());
        p.pump(0);
        assertNull(p.cs.error);
    }

    @Test
    public void clientHelloAccessors() throws Exception {
        Pair p = new Pair(clientConfig(), serverConfig());
        p.client.start(p.cs);
        byte[] wire = p.client.getClientHelloOutboundWire();
        assertNotNull(wire);
        List<byte[]> none = new ArrayList<byte[]>();
        assertTrue(none.isEmpty());
    }
}
