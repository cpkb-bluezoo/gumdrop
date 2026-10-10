/*
 * QuicLoopbackRetransmitFlowControlTest.java
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

import java.nio.ByteBuffer;
import java.util.Map;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.junit.Test;

/**
 * A retransmission does not use up flow-control credit (RFC 9000 section
 * 4.1: the limits apply to the largest offset sent on a stream, not to the
 * number of bytes put on the wire). A sender that charged retransmitted
 * bytes again would run out of credit after enough loss and never finish
 * the stream, however much the peer had actually received.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackRetransmitFlowControlTest {

    /** Simulated time advanced per tick: well past any loss or probe timer. */
    private static final long TICK_MILLIS = 400L;

    private static final int STREAM_LIMIT = 40000;

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    @Test
    public void retransmittedBytesDoNotConsumeFlowControlCredit() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setMaxStreamDataBidiRemote(STREAM_LIMIT);
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        ConnCapture client = new ConnCapture();
        lb.startServer(server);
        lb.startClient(null, client);
        lb.pump();
        Rec c = new Rec();
        Endpoint e = client.conn.openStream(c);
        e.send(ByteBuffer.wrap(bytes(10)));
        lb.pump();
        final int start = lb.sentToServer;
        // The first ten datagrams of the large send are lost: about
        // 12 KB that has to be sent a second time.
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return !toServer || index < start || index >= start + 10;
            }
        };
        // Everything the stream limit allows, with no room to spare for
        // the bytes that were lost.
        e.send(ByteBuffer.wrap(bytes(STREAM_LIMIT - 10)));
        lb.pump();
        lb.filter = null;
        for (int i = 0; i < 20; i++) {
            client.conn.clockOffsetMillis += TICK_MILLIS;
            QuicForger.invoke(client.conn, "onLossDetectionTimeout");
            server.conn.clockOffsetMillis += TICK_MILLIS;
            QuicForger.invoke(server.conn, "onLossDetectionTimeout");
            lb.pump();
        }
        assertEquals(STREAM_LIMIT, server.bidi.recs.get(0).bytes);
        // The credit used is the 40000 distinct bytes, however many times
        // some of them went on the wire.
        Map<?, ?> perStream = (Map<?, ?>) QuicForger.field(client.conn, "streamBytesSent");
        assertEquals(Long.valueOf(STREAM_LIMIT), perStream.get(Long.valueOf(0L)));
        assertEquals(Long.valueOf(STREAM_LIMIT), Long.valueOf(((Long) QuicForger.field(client.conn, "connectionBytesSent")).longValue()));
    }
}
