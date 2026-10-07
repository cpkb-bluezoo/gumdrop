/*
 * QuicLoopbackKeyUpdateTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.junit.Test;

/**
 * RFC 9001 section 6 key updates between two in-memory connections:
 * either side may initiate, the peer follows, data keeps flowing under
 * the new keys, a second update waits for the first to be acknowledged,
 * and a packet from the previous key phase that arrives late is still
 * read.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackKeyUpdateTest {

    private static final class Fixture {
        final QuicLoopback lb;
        final ConnCapture server = new ConnCapture();
        final ConnCapture client = new ConnCapture();

        Fixture() throws Exception {
            lb = new QuicLoopback();
            lb.startFactories();
            lb.startServer(server);
            lb.startClient(null, client);
            lb.pump();
            assertTrue(server.conn.isEstablished());
            assertTrue(client.conn.isEstablished());
        }

        /** A request/response pair on a fresh client stream; returns the server's reply as seen by the client. */
        String exchange(String request, String reply) throws Exception {
            Rec c = new Rec();
            Endpoint e = client.conn.openStream(c);
            int before = server.bidi.recs.size();
            e.send(ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII)));
            e.close();
            lb.pump();
            assertEquals("server accepted the stream", before + 1, server.bidi.recs.size());
            Rec s = server.bidi.recs.get(before);
            assertEquals(request, s.data.toString());
            s.endpoint.send(ByteBuffer.wrap(reply.getBytes(StandardCharsets.US_ASCII)));
            s.endpoint.close();
            lb.pump();
            return c.data.toString();
        }
    }

    @Test
    public void clientInitiatedUpdateIsFollowedByTheServer() throws Exception {
        Fixture f = new Fixture();
        assertEquals("pong", f.exchange("ping", "pong"));
        assertFalse(f.client.conn.getKeyPhase());
        assertFalse(f.server.conn.getKeyPhase());

        assertTrue("confirmed handshake permits an update", f.client.conn.requestKeyUpdate());
        assertTrue("initiator sends in the new phase at once", f.client.conn.getKeyPhase());
        assertEquals("pong2", f.exchange("ping2", "pong2"));
        assertTrue("the server moved to the new phase on the first packet in it", f.server.conn.getKeyPhase());
        assertEquals(1, f.client.conn.getKeyUpdateCount());
        assertEquals(1, f.server.conn.getKeyUpdateCount());
        assertEquals("pong3", f.exchange("ping3", "pong3"));
    }

    @Test
    public void serverInitiatedUpdateIsFollowedByTheClient() throws Exception {
        Fixture f = new Fixture();
        assertEquals("pong", f.exchange("ping", "pong"));
        assertTrue(f.server.conn.requestKeyUpdate());
        assertTrue(f.server.conn.getKeyPhase());
        // Nothing crosses until the server sends; a client stream makes it reply.
        assertEquals("pong2", f.exchange("ping2", "pong2"));
        assertTrue(f.client.conn.getKeyPhase());
        assertEquals(1, f.client.conn.getKeyUpdateCount());
        assertEquals("pong3", f.exchange("ping3", "pong3"));
    }

    @Test
    public void secondUpdateWaitsForAcknowledgementOfTheFirst() throws Exception {
        Fixture f = new Fixture();
        assertEquals("pong", f.exchange("ping", "pong"));
        assertTrue(f.client.conn.requestKeyUpdate());
        assertFalse("no packet of the new phase has been acknowledged yet",
                f.client.conn.requestKeyUpdate());
        assertEquals("pong2", f.exchange("ping2", "pong2"));
        assertTrue("the exchange acknowledged new-phase packets", f.client.conn.requestKeyUpdate());
        assertFalse("phase bit alternates", f.client.conn.getKeyPhase());
        assertEquals("pong3", f.exchange("ping3", "pong3"));
        assertFalse(f.server.conn.getKeyPhase());
        assertEquals(2, f.server.conn.getKeyUpdateCount());
    }

    @Test
    public void latePacketFromThePreviousPhaseIsStillRead() throws Exception {
        final Fixture f = new Fixture();
        assertEquals("pong", f.exchange("ping", "pong"));

        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap("late".getBytes(StandardCharsets.US_ASCII)));
        e.close();
        f.lb.pump();
        Rec s = f.server.bidi.recs.get(f.server.bidi.recs.size() - 1);

        // Hold back the datagram carrying the server's reply, protected
        // with the current (soon to be previous) keys.
        final byte[][] held = new byte[1][];
        f.lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                if (!toServer && held[0] == null) {
                    held[0] = datagram;
                    return false;
                }
                return true;
            }
        };
        s.endpoint.send(ByteBuffer.wrap("old-phase".getBytes(StandardCharsets.US_ASCII)));
        s.endpoint.close();
        f.lb.pump();
        assertNotNull("the reply was captured", held[0]);
        f.lb.filter = null;

        // Both sides move to the next phase.
        assertTrue(f.client.conn.requestKeyUpdate());
        assertEquals("pong2", f.exchange("ping2", "pong2"));
        assertTrue(f.server.conn.getKeyPhase());
        assertEquals(0, f.client.conn.getPreviousPhasePacketsRead());

        // The old-phase reply arrives after the update and is read with
        // the keys kept for exactly this case.
        f.lb.injectToClient(held[0]);
        f.lb.pump();
        assertEquals(1, f.client.conn.getPreviousPhasePacketsRead());
        assertEquals("old-phase", c.data.toString());
        assertFalse(f.client.conn.isClosed());
    }

}
