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

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackFramesTest.Fixture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.quic.frame.QuicFrameWriter;
import org.junit.Test;

/**
 * Issue 574: a stream's per-stream records go when the stream does. The
 * receive-side ones (limits advertised, bytes received, updates owed) go
 * on retirement, since a late frame for a closed stream is discarded
 * before flow-control accounting; the send-side ones (offset, the peer's
 * limit, bytes sent) stay until nothing of the stream can be sent again,
 * because a retransmission is charged against the highest offset sent.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackStreamRecordsTest {

    private static final String[] PER_STREAM = {
        "streamSendOffset", "peerMaxStreamData", "streamBytesSent", "streamDataBlockedOwed",
        "streamDataBlockedSignalled", "localMaxStreamData", "streamBytesReceived", "maxStreamDataOwed",
        "streamSendPriority", "streamReceiveWindow", "streamWindowRaisedMicros", "pendingFinOffset",
        "streamReassemblers", "streams",
    };

    private static int size(QuicConnection conn, String name) throws Exception {
        Object o = QuicForger.field(conn, name);
        return ((Integer) o.getClass().getMethod("size").invoke(o)).intValue();
    }

    private static void assertNoStreamRecords(String who, QuicConnection conn) throws Exception {
        for (String name : PER_STREAM) {
            assertEquals(who + " " + name, 0, size(conn, name));
        }
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
        assertEquals(3, c.bytes);
    }

    private static void settle(Fixture f) throws Exception {
        QuicForger.invoke(f.client.conn, "onAckTimeout");
        QuicForger.invoke(f.server.conn, "onAckTimeout");
        f.lb.pump();
    }

    @Test
    public void closedStreamsLeaveNoRecordsBehind() throws Exception {
        Fixture f = Fixture.create();
        for (int i = 0; i < 60; i++) {
            exchange(f, i);
        }
        settle(f);
        assertNoStreamRecords("client", f.client.conn);
        assertNoStreamRecords("server", f.server.conn);
    }

    @Test
    public void aLateFrameForARetiredStreamIsIgnored() throws Exception {
        Fixture f = Fixture.create();
        exchange(f, 0);
        settle(f);
        long received = ((Long) QuicForger.field(f.server.conn, "connectionBytesReceived")).longValue();
        // A retransmission of the request's first byte, arriving after both
        // ends have forgotten stream 0.
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeStream(b, 0, 0, new byte[] {1}, false);
        f.pn = ((long[]) QuicForger.field(f.server.conn, "largestReceived"))[2] + 1;
        f.toServer(b);
        f.lb.pump();
        assertFalse("connection still open", f.server.conn.isClosed());
        assertEquals("nothing counted twice", received,
                ((Long) QuicForger.field(f.server.conn, "connectionBytesReceived")).longValue());
        assertNoStreamRecords("server", f.server.conn);
    }

    @Test
    public void aRetiredStreamsLostDataIsStillRetransmittedBeyondTheInitialLimit() throws Exception {
        final QuicLoopback lb = new QuicLoopback();
        // The client's own streams start with a 1500-byte window, so a 20 KB
        // response depends on the MAX_STREAM_DATA updates the client sends.
        lb.clientFactory.setMaxStreamDataBidiLocal(1500);
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        long now = System.nanoTime();
        client.conn.frozenNanos = now;
        server.conn.frozenNanos = now;

        Rec reply = new Rec();
        Endpoint request = client.conn.openStream(reply);
        request.send(ByteBuffer.wrap(new byte[] {1}));
        request.close();
        lb.pump();
        Endpoint response = server.bidi.recs.get(0).endpoint;
        final int payload = 20000;
        // Every fourth server datagram from here on is lost, so data of
        // the response, which closes (and so retires) its stream as soon
        // as it is queued, must be sent again after the stream is gone.
        final int start = lb.sentToClient;
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return toServer || index < start || (index - start) % 4 != 1;
            }
        };
        response.send(ByteBuffer.wrap(new byte[payload]));
        response.close();
        lb.pump();
        assertFalse("stream 0 retired on the server", ((java.util.Map<?, ?>) QuicForger.field(server.conn, "streams")).containsKey(Long.valueOf(0L)));
        for (int round = 0; round < 400 && reply.bytes < payload; round++) {
            server.conn.clockOffsetMillis += 300;
            client.conn.clockOffsetMillis += 300;
            QuicForger.invoke(server.conn, "onPacingTimeout");
            QuicForger.invoke(server.conn, "onLossDetectionTimeout");
            QuicForger.invoke(client.conn, "onAckTimeout");
            lb.pump();
        }
        assertEquals("the whole response arrived", payload, reply.bytes);
        lb.filter = null;
        settle_(lb, client, server);
        assertNoStreamRecords("server", server.conn);
    }

    private static void settle_(QuicLoopback lb, ConnCapture client, ConnCapture server) throws Exception {
        QuicForger.invoke(client.conn, "onAckTimeout");
        QuicForger.invoke(server.conn, "onAckTimeout");
        lb.pump();
    }
}
