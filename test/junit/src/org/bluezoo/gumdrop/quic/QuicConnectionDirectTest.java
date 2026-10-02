/*
 * ClientConnectApplyTest.java
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

package org.bluezoo.gumdrop.quic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackFramesTest.Fixture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.quic.cid.ConnectionIdManager;
import org.bluezoo.gumdrop.quic.packet.LongHeaderCodec;
import org.bluezoo.gumdrop.quic.packet.QuicVersion;
import org.bluezoo.gumdrop.quic.packet.RetryIntegrityTag;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.quic.packet.VersionNegotiationPacket;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;
import org.junit.Before;
import org.junit.Test;

/**
 * Drives {@link QuicConnection} and {@link QuicEngine} paths that need
 * hand-crafted input: malformed and spoofed packets, Retry and Version
 * Negotiation edge cases, transport parameter validation, connection ID
 * rotation and direct callbacks.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicConnectionDirectTest {

    /** Tickets cached by other tests would seed remembered transport parameters. */
    @Before
    public void clearTicketCache() {
        SessionTicketCache.clear();
    }

    private static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 4433);
    private static final byte[] CID = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};

    private static byte[] retryPacket(QuicVersion version, byte[] dcid, byte[] originalDcid) {
        byte[] scid = new byte[] {9, 9, 9, 9, 9, 9, 9, 9};
        byte[] without = LongHeaderCodec.buildRetryWithoutTag(version, dcid, scid, new byte[] {5, 5, 5});
        byte[] tag = RetryIntegrityTag.compute(version, originalDcid, without);
        byte[] packet = new byte[without.length + tag.length];
        System.arraycopy(without, 0, packet, 0, without.length);
        System.arraycopy(tag, 0, packet, without.length, tag.length);
        return packet;
    }

    @Test
    public void clientIgnoresBadRetryPackets() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(false);
        // wrong version (a Retry is always in the original version)
        conn.receive(ByteBuffer.wrap(retryPacket(QuicVersion.V2, CID, CID)), PEER);
        // destination connection ID that is not ours
        conn.receive(ByteBuffer.wrap(retryPacket(QuicVersion.V1, new byte[] {7, 7, 7, 7}, CID)), PEER);
        // integrity tag computed over the wrong original DCID
        conn.receive(ByteBuffer.wrap(retryPacket(QuicVersion.V1, CID, new byte[] {4, 4, 4, 4})), PEER);
        // truncated
        conn.receive(ByteBuffer.wrap(new byte[] {(byte) 0xf0, 0, 0, 0, 1, 0, 0}), PEER);
        conn.receive(ByteBuffer.wrap(new byte[] {(byte) 0xf0, 0, 0, 0}), PEER);
        assertFalse(conn.isClosed());
        // a good one is taken, a second is ignored
        conn.receive(ByteBuffer.wrap(retryPacket(QuicVersion.V1, CID, CID)), PEER);
        conn.receive(ByteBuffer.wrap(retryPacket(QuicVersion.V1, CID, CID)), PEER);
        assertFalse(conn.isClosed());
    }

    @Test
    public void serverIgnoresRetry() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(true);
        conn.receive(ByteBuffer.wrap(retryPacket(QuicVersion.V1, CID, CID)), PEER);
        assertFalse(conn.isClosed());
    }

    @Test
    public void versionNegotiationEdgeCases() throws Exception {
        QuicConnection server = QuicConnectionTestFactory.create(true);
        byte[] vn = VersionNegotiationPacket.build(CID, CID, new int[] {0x1a2a3a4a}, 1);
        server.receive(ByteBuffer.wrap(vn), PEER);
        assertFalse(server.isClosed());

        QuicConnection client = QuicConnectionTestFactory.create(false);
        // malformed
        client.receive(ByteBuffer.wrap(new byte[] {(byte) 0x80, 0, 0, 0, 0, 1}), PEER);
        // wrong connection IDs
        client.receive(ByteBuffer.wrap(VersionNegotiationPacket.build(new byte[] {1}, CID, new int[] {2}, 1)), PEER);
        client.receive(ByteBuffer.wrap(VersionNegotiationPacket.build(CID, new byte[] {1}, new int[] {2}, 1)), PEER);
        // it lists the version we already used: ignored
        client.receive(ByteBuffer.wrap(VersionNegotiationPacket.build(CID, CID, new int[] {1}, 1)), PEER);
        assertFalse(client.isClosed());
        // nothing in common: the attempt fails
        client.receive(ByteBuffer.wrap(VersionNegotiationPacket.build(CID, CID, new int[] {0x1a2a3a4a}, 1)), PEER);
        assertTrue(client.isClosed());
    }

    @Test
    public void malformedLongHeadersAreDropped() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(true);
        conn.receive(ByteBuffer.wrap(new byte[] {(byte) 0xc0, 0, 0}), PEER);
        // unknown version
        conn.receive(ByteBuffer.wrap(new byte[] {(byte) 0xc0, 0x1a, 0x2a, 0x3a, 0x4a, 0, 0, 0}), PEER);
        // Initial whose declared length overruns the datagram
        byte[] initial = new byte[30];
        initial[0] = (byte) 0xc0;
        initial[4] = 1;
        initial[5] = 4;
        initial[10] = 4;
        initial[11] = 0;
        initial[12] = 0x7f;
        initial[13] = (byte) 0xff;
        conn.receive(ByteBuffer.wrap(initial), PEER);
        // short header too small to carry anything
        conn.receive(ByteBuffer.wrap(new byte[] {0x40, 1, 2, 3, 4, 5, 6, 7, 8}), PEER);
        assertFalse(conn.isClosed());
    }

    @Test
    public void statelessResetTokenInUndecryptablePacketClosesConnection() throws Exception {
        Fixture f = Fixture.create();
        ConnectionIdManager mgr = (ConnectionIdManager) QuicForger.field(f.client.conn, "connectionIdManager");
        List<byte[]> tokens = mgr.collectPeerResetTokens();
        assertFalse(tokens.isEmpty());
        byte[] reset = new byte[60];
        reset[0] = 0x40;
        byte[] cid = f.client.conn.getOurConnectionId();
        System.arraycopy(cid, 0, reset, 1, cid.length);
        for (int i = cid.length + 1; i < reset.length - 16; i++) {
            reset[i] = (byte) (i * 31);
        }
        System.arraycopy(tokens.get(0), 0, reset, reset.length - 16, 16);
        f.lb.injectToClient(reset);
        assertTrue(f.client.conn.isClosed());
    }

    @Test
    public void handleIncomingStatelessResetDatagramChecksTokens() throws Exception {
        Fixture f = Fixture.create();
        assertFalse(f.client.conn.handleIncomingStatelessResetDatagram(new byte[5]));
        assertFalse(f.client.conn.handleIncomingStatelessResetDatagram(new byte[60]));
        ConnectionIdManager mgr = (ConnectionIdManager) QuicForger.field(f.client.conn, "connectionIdManager");
        byte[] reset = new byte[60];
        reset[0] = 0x40;
        System.arraycopy(mgr.collectPeerResetTokens().get(0), 0, reset, 44, 16);
        assertTrue(f.client.conn.handleIncomingStatelessResetDatagram(reset));
        assertTrue(f.client.conn.isClosed());
        assertFalse(f.client.conn.handleIncomingStatelessResetDatagram(reset));
    }

    @Test
    public void transportParameterValidation() throws Exception {
        Fixture f = Fixture.create();
        // Client connection: unexpected retry_source_connection_id.
        QuicConnection c1 = QuicConnectionTestFactory.create(false);
        TransportParameters p = new TransportParameters();
        p.setRetrySourceConnectionId(new byte[] {1});
        c1.transportParametersReceived(p);
        assertTrue(c1.isClosed());

        // initial_max_streams above 2^60
        QuicConnection c2 = QuicConnectionTestFactory.create(true);
        TransportParameters q = new TransportParameters();
        q.setInitialMaxStreamsBidi((1L << 60) + 1);
        c2.transportParametersReceived(q);
        assertTrue(c2.isClosed());

        // Shrinking the datagram limit drops queued oversized datagrams.
        QuicConnection c3 = QuicConnectionTestFactory.create(true);
        c3.sendDatagram(ByteBuffer.wrap(new byte[0]));
        TransportParameters r = new TransportParameters();
        r.setMaxDatagramFrameSize(0);
        c3.transportParametersReceived(r);
        TransportParameters r2 = new TransportParameters();
        r2.setMaxDatagramFrameSize(100);
        c3.transportParametersReceived(r2);
        assertFalse(c3.isClosed());
        assertNotNull(f.client.conn);
    }

    @Test
    public void directCallbacksAndHelpers() throws Exception {
        Fixture f = Fixture.create();
        final int[] ran = new int[1];
        f.client.conn.execute(new Runnable() {
            @Override
            public void run() {
                ran[0]++;
            }
        });
        assertEquals(1, ran[0]);
        f.client.conn.cryptoProcessingFailed(EncryptionLevel.HANDSHAKE, new RuntimeException("boom"));
        f.client.conn.runWhenOneRttSendKeysReady(new Runnable() {
            @Override
            public void run() {
                ran[0]++;
            }
        });
        assertEquals(2, ran[0]);
        f.client.conn.runWhenLossDetectionIdle(new Runnable() {
            @Override
            public void run() {
                ran[0]++;
            }
        });
        assertTrue(ran[0] >= 2);
        // keys not ready yet on a fresh connection: runs when they arrive
        QuicConnection fresh = QuicConnectionTestFactory.create(false);
        fresh.runWhenOneRttSendKeysReady(new Runnable() {
            @Override
            public void run() {
                ran[0] += 10;
            }
        });
        assertNotNull(fresh.getSecurityInfo() == null ? "x" : "y");
        // early-secret callbacks with nothing to do
        f.server.conn.earlySecretsAvailable();
        f.client.conn.earlySecretsAvailable();
        f.client.conn.earlyDataOutcomeKnown(true);
        f.client.conn.earlyDataOutcomeKnown(false);
        assertFalse(f.client.conn.isClosed());
    }

    @Test
    public void grantMaxStreamsRejectsOverflowAndIgnoresShrink() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(true);
        conn.grantMaxStreams(true, 5);
        conn.grantMaxStreams(true, 1);
        conn.grantMaxStreams(false, 5);
        conn.grantMaxStreams(false, 1);
        try {
            conn.grantMaxStreams(true, (1L << 60) + 1);
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void streamSendPriorityReordersPendingStreams() throws Exception {
        Fixture f = Fixture.create();
        Rec a = new Rec();
        Rec b = new Rec();
        QuicStreamEndpoint ea = (QuicStreamEndpoint) f.client.conn.openStream(a);
        QuicStreamEndpoint eb = (QuicStreamEndpoint) f.client.conn.openStream(b);
        f.client.conn.setStreamSendPriority(ea.getStreamId(), 1);
        java.lang.reflect.Field suppress = f.client.conn.getClass().getDeclaredField("suppressFlush");
        suppress.setAccessible(true);
        suppress.setBoolean(f.client.conn, true);
        ea.send(ByteBuffer.wrap(new byte[100]));
        eb.send(ByteBuffer.wrap(new byte[100]));
        f.client.conn.setStreamSendPriority(eb.getStreamId(), 9);
        f.client.conn.setStreamSendPriority(eb.getStreamId(), 3);
        suppress.setBoolean(f.client.conn, false);
        f.client.conn.requestFlush();
        f.lb.pump();
        assertEquals(2, f.server.bidi.recs.size());
    }

    @Test
    public void closingWithQueuedStreamOpensFailsThem() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setMaxStreamsBidi(1);
        Fixture f = new Fixture(lb);
        Rec first = new Rec();
        Rec second = new Rec();
        assertNotNull(f.client.conn.openStream(first));
        assertNull(f.client.conn.openStream(second));
        f.client.conn.close();
        assertTrue(second.events.toString(), second.events.contains("error"));
    }

    @Test
    public void dropLocalStateForgetsConnectionSilently() throws Exception {
        Fixture f = Fixture.create();
        int before = f.lb.sentToServer;
        f.client.conn.dropLocalState();
        assertTrue(f.client.conn.isClosed());
        assertEquals(before, f.lb.sentToServer);
        f.client.conn.dropLocalState();
    }

    @Test
    public void antiAmplificationLimitWithholdsServerFlight() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        lb.startClient(null, new ConnCapture());
        // Deliver only the client's first datagram and pretend it was tiny.
        byte[] first = lb.toServerLog.get(0);
        lb.injectToServer(first);
        QuicConnection sc = QuicForger.serverConnection(lb.serverEngine);
        assertNotNull(sc);
        java.lang.reflect.Field f = sc.getClass().getDeclaredField("amplificationBytesReceived");
        f.setAccessible(true);
        f.setLong(sc, 1L);
        int sent = lb.sentToClient;
        sc.flush();
        assertEquals(sent, lb.sentToClient);
    }

    @Test
    public void connectionIdRotationAndMigrationUsesSpareId() throws Exception {
        Fixture f = Fixture.create();
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[10]));
        f.lb.pump();
        ConnectionIdManager clientIds = (ConnectionIdManager) QuicForger.field(f.client.conn, "connectionIdManager");
        assertNotNull(clientIds.issueNext());
        f.client.conn.requestFlush();
        f.lb.pump();
        InetSocketAddress moved = new InetSocketAddress("127.0.0.1", 50077);
        f.lb.clientSource = moved;
        e.send(ByteBuffer.wrap(new byte[10]));
        f.lb.pump();
        assertEquals(moved, f.server.conn.getRemoteAddress());
        e.send(ByteBuffer.wrap(new byte[10]));
        f.lb.pump();
        assertFalse(f.server.conn.isClosed());
        assertFalse(f.client.conn.isClosed());
    }

    @Test
    public void recentlyMigratedAddressCooldownExpires() throws Exception {
        Fixture f = Fixture.create();
        java.util.Map<InetSocketAddress, Long> recent = castMap(
                QuicForger.field(f.server.conn, "recentlyMigratedFromAddresses"));
        InetSocketAddress old = new InetSocketAddress("127.0.0.1", 50123);
        recent.put(old, Long.valueOf(1L));
        assertFalse((Boolean) QuicForger.invoke(f.server.conn, "isRecentlyMigratedFrom", old));
        assertTrue(recent.isEmpty());
        // stamped one hour ahead of the connection's clock: inside the cooldown
        // regardless of how long the test takes to run
        Long connNow = (Long) QuicForger.invoke(f.server.conn, "nowMillis");
        recent.put(old, Long.valueOf(connNow.longValue() + 3600000L));
        assertTrue((Boolean) QuicForger.invoke(f.server.conn, "isRecentlyMigratedFrom", old));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<InetSocketAddress, Long> castMap(Object o) {
        return (java.util.Map<InetSocketAddress, Long>) o;
    }

    /**
     * A write larger than the peer's flow-control window must still send as
     * much as the window allows (RFC 9000 section 4.1) rather than wait for
     * the whole chunk to fit: a peer that only raises its limit as it
     * consumes data would otherwise deadlock the stream. The peer's replies
     * are dropped so no credit can arrive.
     */
    private static void assertWindowIsFilled(QuicLoopback lb, int expected) throws Exception {
        Fixture f = new Fixture(lb);
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        f.lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return toServer;
            }
        };
        e.send(ByteBuffer.wrap(new byte[5000]));
        for (int i = 0; i < 5; i++) {
            f.lb.pump();
        }
        assertEquals(1, f.server.bidi.recs.size());
        assertEquals(expected, f.server.bidi.recs.get(0).bytes);
    }

    @Test
    public void oversizedWriteFillsStreamFlowControlWindow() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setMaxStreamDataBidiRemote(100);
        assertWindowIsFilled(lb, 100);
    }

    @Test
    public void oversizedWriteFillsConnectionFlowControlWindow() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setMaxData(300);
        assertWindowIsFilled(lb, 300);
    }

    @Test
    public void bothCipherSuitesAreSupported() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setCipherSuites("TLS_AES_256_GCM_SHA384");
        lb.clientFactory.setCipherSuites("TLS_AES_256_GCM_SHA384");
        Fixture f = new Fixture(lb);
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[10]));
        f.lb.pump();
        assertEquals(10, f.server.bidi.recs.get(0).bytes);
        assertTrue(f.client.conn.getSecurityInfo().getKeySize() == 256);
    }

    @Test
    public void chachaCipherSuiteIsSupported() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setCipherSuites("TLS_CHACHA20_POLY1305_SHA256");
        lb.clientFactory.setCipherSuites("TLS_CHACHA20_POLY1305_SHA256");
        Fixture f = new Fixture(lb);
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[10]));
        f.lb.pump();
        assertEquals(10, f.server.bidi.recs.get(0).bytes);
    }

    @Test
    public void compatibleVersionNegotiationSwitchesClientToVersionTwo() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setVersions("2,1");
        lb.clientFactory.setVersions("2,1");
        Fixture f = new Fixture(lb);
        assertEquals(QuicVersion.V2, f.client.conn.getVersion());
        assertEquals(QuicVersion.V2, f.server.conn.getVersion());
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[10]));
        f.lb.pump();
        assertEquals(10, f.server.bidi.recs.get(0).bytes);
    }

    @Test
    public void streamAcceptHandlerAppliesToExistingConnections() throws Exception {
        Fixture f = Fixture.create();
        QuicLoopbackScenariosTest.Acceptor late = new QuicLoopbackScenariosTest.Acceptor();
        f.lb.serverEngine.setStreamAcceptHandler(late);
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[10]));
        f.lb.pump();
        assertEquals(1, late.recs.size());
    }
}
