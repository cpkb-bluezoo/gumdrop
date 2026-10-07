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
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Map;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackFramesTest.Fixture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.quic.frame.QuicFrameWriter;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.quic.recovery.LossDetector;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;
import org.junit.Test;

/**
 * The sender side of draft-ietf-quic-ack-frequency-14: when this endpoint
 * asks its peer to acknowledge less often, and what that does to its own
 * loss recovery.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackAckFrequencySenderTest {

    private static long field(Object target, String name) throws Exception {
        Field f = QuicConnection.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(target);
    }

    private static void setField(Object target, String name, long value) throws Exception {
        Field f = QuicConnection.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setLong(target, value);
    }

    /** Lets any ACK still held back by max_ack_delay go out. */
    private static void settle(Fixture f) throws Exception {
        QuicForger.invoke(f.client.conn, "onAckTimeout");
        QuicForger.invoke(f.server.conn, "onAckTimeout");
        f.lb.pump();
    }

    private static void exchange(Fixture f, int index) throws Exception {
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        e.close();
        f.lb.pump();
        Rec s = f.server.bidi.recs.get(index);
        s.endpoint.send(ByteBuffer.wrap(new byte[] {4, 5, 6}));
        s.endpoint.close();
        f.lb.pump();
    }

    @Test
    public void bothEndsAskTheirPeerToAcknowledgeLessOften() throws Exception {
        Fixture f = Fixture.create();
        exchange(f, 0);
        settle(f);
        QuicConnection[] receivers = { f.client.conn, f.server.conn };
        for (int i = 0; i < receivers.length; i++) {
            QuicConnection receiver = receivers[i];
            assertTrue("an ACK_FREQUENCY frame arrived", field(receiver, "lastAckFrequencySequence") >= 0);
            long delay = field(receiver, "requestedMaxAckDelayMicros");
            assertTrue("no less than the peer's min_ack_delay: " + delay, delay >= 1000);
            assertTrue("modest: " + delay, delay <= 25000);
            assertEquals(1, field(receiver, "reorderingThreshold"));
            assertTrue(field(receiver, "ackElicitingThreshold") >= 1);
        }
    }

    @Test
    public void senderCanBeSwitchedOffOnTheFactoryWithoutLosingTheReceiver() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.clientFactory.setAckFrequencyEnabled(false);
        Fixture f = new Fixture(lb);
        exchange(f, 0);
        settle(f);
        assertEquals("the client asked for nothing", -1, field(f.server.conn, "lastAckFrequencySequence"));
        assertTrue("the server's request was honoured", field(f.client.conn, "lastAckFrequencySequence") >= 0);
    }

    @Test
    public void nothingIsSentToAPeerThatDidNotAdvertiseMinAckDelay() throws Exception {
        Fixture f = Fixture.create();
        exchange(f, 0);
        settle(f);
        long before = field(f.client.conn, "lastAckFrequencySequence");
        setField(f.server.conn, "peerMinAckDelayMicros", -1);
        QuicForger.invoke(f.server.conn, "queueAckFrequency");
        exchange(f, 1);
        settle(f);
        assertEquals(before, field(f.client.conn, "lastAckFrequencySequence"));

        setField(f.server.conn, "peerMinAckDelayMicros", 1000);
        QuicForger.invoke(f.server.conn, "queueAckFrequency");
        exchange(f, 2);
        settle(f);
        assertEquals("a new sequence number", before + 1, field(f.client.conn, "lastAckFrequencySequence"));
    }

    @Test
    public void lostAckFrequencyIsReissuedWithANewSequenceNumber() throws Exception {
        Fixture f = Fixture.create();
        exchange(f, 0);
        settle(f);
        long before = field(f.client.conn, "lastAckFrequencySequence");
        Map<?, ?> sent = (Map<?, ?>) QuicForger.field(f.server.conn, "sentAckFrequency");
        assertFalse("the sent frame is tracked until acknowledged or lost", sent == null);
        // a packet carrying the latest sequence number is declared lost
        @SuppressWarnings("unchecked")
        Map<Long, long[]> tracked = (Map<Long, long[]>) sent;
        tracked.put(Long.valueOf(999999), new long[] { before, 1000, 1 });
        QuicForger.invoke(f.server.conn, "requeueLostPacket", EncryptionLevel.ONE_RTT, Long.valueOf(999999));
        f.server.conn.flush();
        f.lb.pump();
        settle(f);
        assertEquals(before + 1, field(f.client.conn, "lastAckFrequencySequence"));
    }

    @Test
    public void probeTimeoutPacketAsksForAnImmediateAck() throws Exception {
        Fixture f = Fixture.create();
        exchange(f, 0);
        settle(f);
        // the server would otherwise hold its ACKs back
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeAckFrequency(b, 1000, 100, 25000, 0);
        f.pn = ((long[]) QuicForger.field(f.server.conn, "largestReceived"))[2] + 1;
        f.toServer(b);
        settle(f);

        final int dropAt = f.lb.sentToServer;
        f.lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return !(toServer && index == dropAt);
            }
        };
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[] {1}));
        f.lb.pump();
        f.lb.filter = null;
        int before = f.lb.toClientLog.size();
        f.client.conn.clockOffsetMillis += 5000;
        QuicForger.invoke(f.client.conn, "onLossDetectionTimeout");
        f.lb.pump();
        assertTrue("the probe was acknowledged at once", f.lb.toClientLog.size() > before);
    }

    @Test
    public void probeTimeoutPacketDoesNotAskAPeerThatDidNotAdvertiseMinAckDelay() throws Exception {
        Fixture f = Fixture.create();
        exchange(f, 0);
        settle(f);
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeAckFrequency(b, 1000, 100, 25000, 0);
        f.pn = ((long[]) QuicForger.field(f.server.conn, "largestReceived"))[2] + 1;
        f.toServer(b);
        settle(f);
        setField(f.client.conn, "peerMinAckDelayMicros", -1);

        final int dropAt = f.lb.sentToServer;
        f.lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return !(toServer && index == dropAt);
            }
        };
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[] {1}));
        f.lb.pump();
        f.lb.filter = null;
        int before = f.lb.toClientLog.size();
        f.client.conn.clockOffsetMillis += 5000;
        QuicForger.invoke(f.client.conn, "onLossDetectionTimeout");
        f.lb.pump();
        assertEquals("a plain PING is held back like any other packet", before, f.lb.toClientLog.size());
    }

    @Test
    public void ackSilenceOverOneRttAsksForAnImmediateAck() throws Exception {
        Fixture f = Fixture.create();
        exchange(f, 0);
        settle(f);
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeAckFrequency(b, 1000, 100, 25000, 0);
        f.pn = ((long[]) QuicForger.field(f.server.conn, "largestReceived"))[2] + 1;
        f.toServer(b);
        settle(f);

        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        int before = f.lb.toClientLog.size();
        e.send(ByteBuffer.wrap(new byte[] {1}));
        f.lb.pump();
        assertEquals("the first packet is held back", before, f.lb.toClientLog.size());
        // more than a smoothed round trip of silence
        f.client.conn.clockOffsetMillis += 10 + ((LossDetector) QuicForger.field(f.client.conn, "lossDetector"))
                .getRttEstimator().getSmoothedRtt() / 1000;
        e.send(ByteBuffer.wrap(new byte[] {2}));
        f.lb.pump();
        assertEquals("the second came after more than a round trip of silence", before + 1,
                f.lb.toClientLog.size());
    }

    @Test
    public void acknowledgedRequestReplacesThePeersMaxAckDelayForRecovery() throws Exception {
        Fixture f = Fixture.create();
        exchange(f, 0);
        settle(f);
        long requested = field(f.server.conn, "requestedMaxAckDelayMicros");
        assertTrue(requested < 25000);
        assertEquals(requested, ((Long) QuicForger.invoke(f.client.conn, "peerMaxAckDelayMicros")).longValue());
    }

    @Test
    public void unacknowledgedRequestWidensThePto() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(true);
        TransportParameters p = new TransportParameters();
        p.setMaxAckDelay(25);
        conn.transportParametersReceived(p);
        assertEquals(25000, ((Long) QuicForger.invoke(conn, "peerMaxAckDelayMicros")).longValue());

        setField(conn, "ackFrequencyPendingDelayMicros", 60000);
        assertEquals("the larger of the current and the in-flight value", 60000,
                ((Long) QuicForger.invoke(conn, "peerMaxAckDelayMicros")).longValue());

        setField(conn, "ackFrequencyPendingDelayMicros", -1);
        setField(conn, "ackFrequencyAckedDelayMicros", 10000);
        assertEquals("the acknowledged value replaces the peer's", 10000,
                ((Long) QuicForger.invoke(conn, "peerMaxAckDelayMicros")).longValue());

        setField(conn, "ackFrequencyPendingDelayMicros", 5000);
        assertEquals(10000, ((Long) QuicForger.invoke(conn, "peerMaxAckDelayMicros")).longValue());
    }

    /** The congestion controller is reset when the peer moves, so what was asked of it is asked again. */
    @Test
    public void requestIsRepeatedAfterThePeerMigrates() throws Exception {
        Fixture f = Fixture.create();
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[10]));
        f.lb.pump();
        settle(f);
        long before = field(f.client.conn, "lastAckFrequencySequence");
        assertTrue(before >= 0);
        f.lb.clientSource = new java.net.InetSocketAddress("127.0.0.1", 50077);
        e.send(ByteBuffer.wrap(new byte[10]));
        f.lb.pump();
        assertEquals(f.lb.clientSource, f.server.conn.getRemoteAddress());
        settle(f);
        assertTrue("a fresh request followed the migration: " + before + " -> "
                + field(f.client.conn, "lastAckFrequencySequence"),
                field(f.client.conn, "lastAckFrequencySequence") > before);
    }
}
