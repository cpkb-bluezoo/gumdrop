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
 * Issue 566: a prioritised stream that is closed in both directions while
 * its data is still queued keeps its place in the send order, and its data
 * is still sent. Retiring the stream used to drop its priority while it was
 * a member of the priority-ordered set, which corrupted the set's ordering,
 * so the stream could not be removed once drained and the next flush
 * dereferenced a stream that no longer had a queue.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackRetiredStreamTest {

    private static final int RESPONSE = 20000;

    @Test
    public void retiringAPrioritisedStreamWhileItsDataIsQueuedStillSendsTheData() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertTrue(client.conn.isEstablished());
        long now = System.nanoTime();
        client.conn.frozenNanos = now;
        server.conn.frozenNanos = now;
        // A long round trip keeps the server's pacer to its initial burst
        // per millisecond tick, so a 20 KB response cannot leave in one go
        // and the retirement below happens with data still queued.
        Object rtt = ((LossDetector) QuicForger.field(server.conn, "lossDetector")).getRttEstimator();
        Field smoothed = RttEstimator.class.getDeclaredField("smoothedRtt");
        smoothed.setAccessible(true);
        smoothed.setLong(rtt, 100_000L);

        // Stream 0: request with FIN, a low-priority response left queued.
        Rec first = new Rec();
        Endpoint request0 = client.conn.openStream(first);
        request0.send(ByteBuffer.wrap(new byte[] {1}));
        request0.close();
        lb.pump();
        Endpoint response0 = server.bidi.recs.get(0).endpoint;
        server.conn.setStreamSendPriority(0, 0);
        response0.send(ByteBuffer.wrap(new byte[RESPONSE]));
        lb.pump();

        // Stream 4: higher priority, so it sits before stream 0 in the
        // order; its response is queued and the stream closed in both
        // directions while most of that response is still waiting.
        Rec second = new Rec();
        Endpoint request4 = client.conn.openStream(second);
        request4.send(ByteBuffer.wrap(new byte[] {2}));
        request4.close();
        lb.pump();
        Endpoint response4 = server.bidi.recs.get(1).endpoint;
        server.conn.setStreamSendPriority(4, 10);
        response4.send(ByteBuffer.wrap(new byte[RESPONSE]));
        response4.close();
        lb.pump();

        // Let the pacer release the rest, a tick at a time.
        for (int tick = 0; tick < 500 && (first.bytes < RESPONSE || second.bytes < RESPONSE); tick++) {
            server.conn.clockOffsetMillis += 1;
            client.conn.clockOffsetMillis += 1;
            QuicForger.invoke(server.conn, "onPacingTimeout");
            lb.pump();
        }
        assertEquals("stream 4 response", RESPONSE, second.bytes);
        assertEquals("stream 0 response", RESPONSE, first.bytes);
    }
}
