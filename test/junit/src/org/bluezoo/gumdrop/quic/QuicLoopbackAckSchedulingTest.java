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

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.junit.Test;

/**
 * ACK scheduling in the application data space (RFC 9000 section 13.2.1):
 * an ACK follows every second ack-eliciting packet, or the expiry of
 * max_ack_delay, and is immediate for out-of-order arrivals.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackAckSchedulingTest {

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
        Rec request = new Rec();
        Endpoint stream = client.conn.openStream(request);
        stream.send(ByteBuffer.wrap(new byte[] {1}));
        lb.pump();
        serverStream = server.bidi.recs.get(0).endpoint;
        // let any acknowledgement still owed from the handshake go out
        client.conn.clockOffsetMillis += 1000;
        QuicForger.invoke(client.conn, "onAckTimeout");
        server.conn.clockOffsetMillis += 1000;
        QuicForger.invoke(server.conn, "onAckTimeout");
        lb.pump();
    }

    private void serverSends() {
        serverStream.send(ByteBuffer.wrap(new byte[] {2, 3, 4}));
    }

    @Test
    public void singlePacketIsNotAcknowledgedUntilMaxAckDelay() throws Exception {
        connect();
        int before = lb.toServerLog.size();
        serverSends();
        lb.pump();
        assertEquals("one ack-eliciting packet is not acknowledged at once", before, lb.toServerLog.size());
        client.conn.clockOffsetMillis += 30;
        QuicForger.invoke(client.conn, "onAckTimeout");
        lb.pump();
        assertEquals("acknowledged when max_ack_delay expires", before + 1, lb.toServerLog.size());
    }

    @Test
    public void secondAckElicitingPacketIsAcknowledgedAtOnce() throws Exception {
        connect();
        int before = lb.toServerLog.size();
        serverSends();
        lb.pump();
        assertEquals(before, lb.toServerLog.size());
        serverSends();
        lb.pump();
        assertEquals(before + 1, lb.toServerLog.size());
    }

    @Test
    public void gapIsAcknowledgedAtOnce() throws Exception {
        connect();
        final int dropIndex = lb.sentToClient;
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return toServer || index != dropIndex;
            }
        };
        serverSends();
        lb.pump();
        int before = lb.toServerLog.size();
        serverSends();
        lb.pump();
        assertEquals("out-of-order arrival is acknowledged immediately", before + 1, lb.toServerLog.size());
    }

    @Test
    public void ackDelayReportsTimeSinceLargestPacket() throws Exception {
        connect();
        serverSends();
        lb.pump();
        client.conn.clockOffsetMillis += 10;
        long delay = ((Long) QuicForger.invoke(client.conn, "computeAckDelay",
                EncryptionLevel.ONE_RTT)).longValue();
        // 10 ms in units of 2^3 microseconds, plus the real time elapsed
        assertTrue("ack delay " + delay, delay >= 1250 && delay < 1250 + 1250);
    }

    @Test
    public void ackDelayHasSubMillisecondResolution() throws Exception {
        connect();
        serverSends();
        lb.pump();
        // a millisecond clock could only ever report whole multiples of
        // 1000 microseconds, which is 125 units of 2^3 microseconds
        boolean fractional = false;
        for (int i = 0; i < 100000 && !fractional; i++) {
            long delay = ((Long) QuicForger.invoke(client.conn, "computeAckDelay",
                    EncryptionLevel.ONE_RTT)).longValue();
            fractional = delay % 125 != 0;
        }
        assertTrue(fractional);
    }
}
