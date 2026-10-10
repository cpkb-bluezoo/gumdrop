/*
 * QuicLoopbackWindowUpdateTest.java
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

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.junit.Test;

/**
 * A receiver raises its advertised flow-control limits once per half
 * window of data consumed, not once per packet. Raising a limit only half
 * a window ahead of the data received left the very next packet under the
 * same half-window threshold, so every received packet cost a MAX_DATA and
 * a MAX_STREAM_DATA frame, which made nearly every acknowledgement
 * ack-eliciting and defeated the point of acknowledging less often.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackWindowUpdateTest {

    private static final int WINDOW = 40000;
    private static final int CHUNK = 1000;
    private static final int CHUNKS = 120;

    @Test
    public void limitsAreRaisedOncePerHalfWindowNotOncePerPacket() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setMaxData(WINDOW);
        lb.serverFactory.setMaxStreamDataBidiRemote(WINDOW);
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        ConnCapture client = new ConnCapture();
        lb.startServer(server);
        lb.startClient(null, client);
        lb.pump();
        Rec c = new Rec();
        Endpoint e = client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[1]));
        lb.pump();
        Set<Long> connectionLimits = new HashSet<Long>();
        Set<Long> streamLimits = new HashSet<Long>();
        for (int i = 0; i < CHUNKS; i++) {
            e.send(ByteBuffer.wrap(new byte[CHUNK]));
            lb.pump();
            connectionLimits.add((Long) QuicForger.field(server.conn, "localMaxData"));
            java.util.Map<?, ?> perStream = (java.util.Map<?, ?>) QuicForger.field(server.conn, "localMaxStreamData");
            Object limit = perStream.get(Long.valueOf(0L));
            if (limit != null) {
                streamLimits.add((Long) limit);
            }
        }
        assertEquals(1 + CHUNKS * CHUNK, server.bidi.recs.get(0).bytes);
        // 120 KB through a 40 KB window is a handful of raises (each one a
        // half window ahead of the data received), nowhere near 120.
        assertTrue("connection limit raised " + connectionLimits.size() + " times",
                connectionLimits.size() <= 12);
        assertTrue("stream limit raised " + streamLimits.size() + " times",
                streamLimits.size() <= 12);
    }
}
