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
import java.util.Map;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;
import org.junit.Test;

/**
 * Issue 573: the record of what a sent packet carried, kept so that a
 * lost packet's frames can be queued again, is released once the packet
 * is acknowledged. It used to be released only on loss, so a connection
 * kept every byte it had ever sent.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackSentRecordsTest {

    private static final int PAYLOAD = 300000;

    @Test
    public void acknowledgedPacketsReleaseTheirSentRecords() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertTrue(client.conn.isEstablished());

        Rec reply = new Rec();
        Endpoint request = client.conn.openStream(reply);
        request.send(ByteBuffer.wrap(new byte[] {1}));
        request.close();
        lb.pump();
        Endpoint response = server.bidi.recs.get(0).endpoint;
        response.send(ByteBuffer.wrap(new byte[PAYLOAD]));
        response.close();
        // The pacer may hold data back between ticks; drive it until the
        // whole response has arrived, then let any acknowledgement still
        // owed at either end go out.
        for (int tick = 0; tick < 5000 && reply.bytes < PAYLOAD; tick++) {
            server.conn.clockOffsetMillis += 1;
            client.conn.clockOffsetMillis += 1;
            QuicForger.invoke(server.conn, "onPacingTimeout");
            lb.pump();
        }
        assertEquals(PAYLOAD, reply.bytes);
        QuicForger.invoke(client.conn, "onAckTimeout");
        QuicForger.invoke(server.conn, "onAckTimeout");
        lb.pump();

        Map<?, ?> sentStream = (Map<?, ?>) QuicForger.field(server.conn, "sentStream");
        assertEquals("sent STREAM records still held after acknowledgement", 0, sentStream.size());
        Map<?, ?> sentCrypto = (Map<?, ?>) QuicForger.field(server.conn, "sentCrypto");
        Map<?, ?> oneRttCrypto = (Map<?, ?>) sentCrypto.get(EncryptionLevel.ONE_RTT);
        assertEquals("sent 1-RTT CRYPTO records still held after acknowledgement", 0, oneRttCrypto.size());
        Map<?, ?> clientSentStream = (Map<?, ?>) QuicForger.field(client.conn, "sentStream");
        assertEquals(0, clientSentStream.size());
    }
}
