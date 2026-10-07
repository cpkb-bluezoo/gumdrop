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
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.quic.QuicLoopbackFramesTest.Fixture;
import org.bluezoo.gumdrop.quic.frame.QuicFrameWriter;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.junit.After;
import org.junit.Test;

/**
 * The receiver side of draft-ietf-quic-ack-frequency-14: the
 * min_ack_delay transport parameter, and the ACK_FREQUENCY and
 * IMMEDIATE_ACK frames that parameterise ACK scheduling.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackAckFrequencyTest {

    private static final long PROTOCOL_VIOLATION = 0xa;
    private static final long TRANSPORT_PARAMETER_ERROR = 0x8;

    @After
    public void clearTickets() {
        SessionTicketCache.clear();
    }

    /** An established pair with no ACK owed and the client's next packet number lined up. */
    private static Fixture connect() throws Exception {
        Fixture f = Fixture.create();
        f.client.conn.clockOffsetMillis += 1000;
        QuicForger.invoke(f.client.conn, "onAckTimeout");
        f.server.conn.clockOffsetMillis += 1000;
        QuicForger.invoke(f.server.conn, "onAckTimeout");
        f.lb.pump();
        long[] largest = (long[]) QuicForger.field(f.server.conn, "largestReceived");
        f.pn = largest[2] + 1;
        return f;
    }

    private static void ping(Fixture f) throws Exception {
        ByteBuffer b = f.buf();
        QuicFrameWriter.writePing(b);
        f.toServer(b);
    }

    private static void ackFrequency(Fixture f, long sequence, long threshold, long delayMicros,
            long reordering) throws Exception {
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeAckFrequency(b, sequence, threshold, delayMicros, reordering);
        f.toServer(b);
    }

    private static int acks(Fixture f) {
        return f.lb.toClientLog.size();
    }

    private static long closeCode(QuicConnection conn) throws Exception {
        Field field = QuicConnection.class.getDeclaredField("deferredCloseErrorCode");
        field.setAccessible(true);
        return field.getLong(conn);
    }

    @Test
    public void minAckDelayIsAdvertisedByBothEnds() throws Exception {
        Fixture f = Fixture.create();
        TransportParameters atServer = (TransportParameters) QuicForger.field(f.server.conn,
                "peerTransportParameters");
        TransportParameters atClient = (TransportParameters) QuicForger.field(f.client.conn,
                "peerTransportParameters");
        assertTrue(atServer.hasMinAckDelay());
        assertTrue(atClient.hasMinAckDelay());
        assertEquals(1000, atClient.getMinAckDelay());
    }

    @Test
    public void thresholdFromAckFrequencyDelaysTheAck() throws Exception {
        Fixture f = connect();
        int before = acks(f);
        ackFrequency(f, 1, 3, 25000, 1);
        ping(f);
        ping(f);
        assertEquals("three ack-eliciting packets do not exceed a threshold of 3", before, acks(f));
        ping(f);
        assertEquals("the fourth does", before + 1, acks(f));
    }

    @Test
    public void staleSequenceNumberIsIgnored() throws Exception {
        Fixture f = connect();
        int before = acks(f);
        ackFrequency(f, 2, 3, 25000, 1);
        ackFrequency(f, 1, 0, 25000, 1);
        ping(f);
        assertEquals(before, acks(f));
        ping(f);
        assertEquals(before + 1, acks(f));
    }

    @Test
    public void requestedMaxAckDelayBelowMinAckDelayIsAProtocolViolation() throws Exception {
        Fixture f = connect();
        ackFrequency(f, 1, 1, 999, 1);
        assertTrue(f.server.conn.isClosed());
        assertEquals(PROTOCOL_VIOLATION, closeCode(f.server.conn));
    }

    @Test
    public void requestedMaxAckDelayOfTwoToTheFourteenMillisecondsIsAProtocolViolation() throws Exception {
        Fixture f = connect();
        ackFrequency(f, 1, 1, 16384000, 1);
        assertTrue(f.server.conn.isClosed());
        assertEquals(PROTOCOL_VIOLATION, closeCode(f.server.conn));
    }

    @Test
    public void reorderingThresholdZeroSuppressesTheReorderingAck() throws Exception {
        Fixture f = connect();
        ackFrequency(f, 1, 100, 25000, 0);
        int before = acks(f);
        f.pn += 5;
        ping(f);
        assertEquals("a gap does not elicit an ACK", before, acks(f));
        f.pn -= 3;
        ping(f);
        assertEquals("nor does a packet below the largest", before, acks(f));
    }

    @Test
    public void reorderingThresholdThreeAcknowledgesOnlyAGapOfThree() throws Exception {
        Fixture f = connect();
        ackFrequency(f, 1, 100, 25000, 3);
        int before = acks(f);
        f.pn += 1;
        ping(f);
        f.pn += 1;
        ping(f);
        assertEquals("gaps of one are tolerated", before, acks(f));
        f.pn += 3;
        ping(f);
        assertEquals("a gap of three is not", before + 1, acks(f));
    }

    @Test
    public void defaultReorderingThresholdAcknowledgesAnyGap() throws Exception {
        Fixture f = connect();
        ackFrequency(f, 1, 100, 25000, 1);
        int before = acks(f);
        f.pn += 1;
        ping(f);
        assertEquals(before + 1, acks(f));
    }

    @Test
    public void immediateAckIsAcknowledgedOnTheNextFlush() throws Exception {
        Fixture f = connect();
        ackFrequency(f, 1, 100, 25000, 0);
        int before = acks(f);
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeImmediateAck(b);
        f.toServer(b);
        assertEquals(before + 1, acks(f));
    }

    @Test
    public void requestedMaxAckDelayBoundsTheDelay() throws Exception {
        Fixture f = connect();
        ackFrequency(f, 1, 100, 5000, 0);
        int before = acks(f);
        ping(f);
        assertEquals(before, acks(f));
        // the timer is armed for the requested 5 ms, not the default 25 ms
        f.server.conn.clockOffsetMillis += 6;
        QuicForger.invoke(f.server.conn, "onAckTimeout");
        f.lb.pump();
        assertEquals(before + 1, acks(f));
    }

    @Test
    public void peerMinAckDelayAboveItsMaxAckDelayIsATransportParameterError() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(true);
        TransportParameters p = new TransportParameters();
        p.setMaxAckDelay(10);
        p.setMinAckDelay(10001);
        conn.transportParametersReceived(p);
        assertTrue(conn.isClosed());
        assertEquals(TRANSPORT_PARAMETER_ERROR, closeCode(conn));
    }

    @Test
    public void peerMinAckDelayEqualToItsMaxAckDelayIsAccepted() throws Exception {
        QuicConnection conn = QuicConnectionTestFactory.create(true);
        TransportParameters p = new TransportParameters();
        p.setMaxAckDelay(10);
        p.setMinAckDelay(10000);
        conn.transportParametersReceived(p);
        assertFalse(conn.isClosed());
    }

    @Test
    public void peerMinAckDelayIsNotRememberedWithTheSessionTicket() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setEarlyDataEnabled(true);
        lb.clientFactory.setEarlyDataEnabled(true);
        lb.startFactories();
        lb.startServer(new QuicLoopbackScenariosTest.ConnCapture());
        QuicLoopbackScenariosTest.ConnCapture client = new QuicLoopbackScenariosTest.ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        lb.pump();
        SessionTicketCache.Entry entry = SessionTicketCache.get("localhost", 4433);
        assertNotNull(entry);
        TransportParameters live = (TransportParameters) QuicForger.field(client.conn,
                "peerTransportParameters");
        assertTrue("the live connection has the peer's value", live.hasMinAckDelay());
        assertFalse(entry.toTransportParameters().hasMinAckDelay());
    }
}
