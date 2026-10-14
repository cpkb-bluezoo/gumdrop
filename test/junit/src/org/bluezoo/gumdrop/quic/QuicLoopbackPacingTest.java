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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.quic.recovery.LossDetector;
import org.bluezoo.gumdrop.quic.recovery.RttEstimator;
import org.junit.Test;

/**
 * Pacing of bulk stream data (RFC 9002 section 7.7): a large queued send
 * leaves in bursts bounded by the initial congestion window, with a timer
 * armed for the rest, instead of all at once.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackPacingTest {

    private static final int PAYLOAD = 100000;
    // The initial congestion window: ten datagrams.
    private static final int BURST_DATAGRAMS = 10;

    private QuicLoopback lb;
    private ConnCapture server;
    private ConnCapture client;
    private Endpoint serverStream;

    private void connect() throws Exception {
        lb = new QuicLoopback();
        lb.startFactories();
        server = new ConnCapture();
        lb.startServer(server);
        client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertTrue(client.conn.isEstablished());
        Endpoint stream = client.conn.openStream(new Rec());
        stream.send(ByteBuffer.wrap(new byte[] {1}));
        lb.pump();
        serverStream = server.bidi.recs.get(0).endpoint;
        QuicForger.invoke(client.conn, "onAckTimeout");
        QuicForger.invoke(server.conn, "onAckTimeout");
        lb.pump();
        // From here the only time that passes is what a test adds.
        long now = System.nanoTime();
        client.conn.frozenNanos = now;
        server.conn.frozenNanos = now;
        // Pin the server's round-trip estimate at 100 ms: the pacing rate
        // (and so the credit a millisecond tick may release) depends on
        // it, and the real handshake's round trip varies with the host.
        Object rtt = ((LossDetector) QuicForger.field(server.conn, "lossDetector")).getRttEstimator();
        Field smoothed = RttEstimator.class.getDeclaredField("smoothedRtt");
        smoothed.setAccessible(true);
        smoothed.setLong(rtt, 100_000L);
    }

    @Test
    public void bulkSendIsSpreadOverTimeInsteadOfBurstingTheWholeWindow() throws Exception {
        connect();
        int before = lb.sentToClient;
        serverStream.send(ByteBuffer.wrap(new byte[PAYLOAD]));
        lb.pump();
        int firstBurst = lb.sentToClient - before;
        assertTrue("first burst of " + firstBurst + " datagrams", firstBurst <= BURST_DATAGRAMS + 2);
        assertNotNull("a timer is armed for the rest", QuicForger.field(server.conn, "pacingTimerHandle"));

        // Time passes a millisecond at a time, the timer fires, and more
        // goes out: each tick may release one millisecond of credit at
        // the pacing rate, never less than the initial window, and no
        // more however the round-trip time came out.
        LossDetector detector = (LossDetector) QuicForger.field(server.conn, "lossDetector");
        int rounds = 0;
        int idleTicks = 0;
        int last = lb.sentToClient;
        while (rounds++ < 5000) {
            long bytesPerMilli = detector.getPacingRateBitsPerSecond() / 8000;
            long allowed = Math.max(BURST_DATAGRAMS, (bytesPerMilli + 1199) / 1200) + 2;
            server.conn.clockOffsetMillis += 1;
            client.conn.clockOffsetMillis += 1;
            QuicForger.invoke(server.conn, "onPacingTimeout");
            lb.pump();
            int released = lb.sentToClient - last;
            assertTrue("round " + rounds + " released " + released + ", allowed " + allowed, released <= allowed);
            // At a low rate a single tick may not accrue a datagram's
            // worth of credit; only a long run of empty ticks means done.
            idleTicks = released == 0 ? idleTicks + 1 : 0;
            if (idleTicks > 50) {
                break;
            }
            last = lb.sentToClient;
        }
        int total = lb.sentToClient - before;
        assertTrue("all " + PAYLOAD + " bytes left: " + total + " datagrams", total >= PAYLOAD / 1200);
        assertTrue("took more than the first flush: " + rounds, rounds >= 2);
        assertNull("no timer left once nothing is waiting", QuicForger.field(server.conn, "pacingTimerHandle"));
    }
}
