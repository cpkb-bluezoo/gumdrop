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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Acceptor;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.junit.After;
import org.junit.Test;

/**
 * Session tickets and 0-RTT early data between an in-memory client and
 * server (RFC 9001 section 4.6). The server factory supplies its own ticket
 * keys, so no test-side key installation is needed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackEarlyDataTest {

    @After
    public void clearTickets() {
        SessionTicketCache.clear();
    }

    /** Server side: records the accepted connection and its stream acceptor. */
    private static final class TicketingServer implements QuicEngine.ConnectionAcceptedHandler {
        final Acceptor bidi = new Acceptor();
        QuicConnection conn;

        @Override
        public void connectionAccepted(QuicConnection connection) {
            conn = connection;
            connection.setStreamAcceptHandler(bidi);
        }
    }

    private static void firstConnection(QuicLoopback lb) throws Exception {
        lb.serverFactory.setEarlyDataEnabled(true);
        lb.clientFactory.setEarlyDataEnabled(true);
        lb.startFactories();
        lb.startServer(new TicketingServer());
        lb.startClient(null, new QuicLoopbackScenariosTest.ConnCapture());
        lb.pump();
        lb.pump();
    }

    private static final class Early implements QuicEngine.EarlyDataHandler {
        final List<QuicConnection> ready = new ArrayList<QuicConnection>();
        final Rec stream = new Rec();

        @Override
        public void earlyDataReady(QuicConnection connection) {
            ready.add(connection);
            Endpoint e = connection.openStream(stream);
            if (e != null) {
                e.send(ByteBuffer.wrap(new byte[] {'h', 'i'}));
            }
        }
    }

    @Test
    public void ticketIsCachedAndZeroRttAccepted() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        firstConnection(lb);
        assertNotNull(SessionTicketCache.get("localhost", 4433));

        // Second connection to a new engine of the same factory, so the
        // factory's ticket keys are shared with the first.
        TicketingServer server = new TicketingServer();
        lb.startServer(server);
        Early early = new Early();
        QuicLoopbackScenariosTest.ConnCapture client = new QuicLoopbackScenariosTest.ConnCapture();
        lb.startClientEarly(client, early);
        lb.pump();
        assertEquals(1, early.ready.size());
        assertTrue(client.conn.isEstablished());
        assertEquals(1, server.bidi.recs.size());
        assertEquals("hi", server.bidi.recs.get(0).data.toString());
        assertTrue(client.conn.getSecurityInfo().isEarlyDataAccepted());
    }

    @Test
    public void rejectedZeroRttIsResentAtOneRtt() throws Exception {
        firstConnection(new QuicLoopback());
        assertNotNull(SessionTicketCache.get("localhost", 4433));

        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setEarlyDataEnabled(true);
        lb.clientFactory.setEarlyDataEnabled(true);
        lb.startFactories();
        // A server factory with different ticket keys cannot open the ticket.
        TicketingServer server = new TicketingServer();
        lb.startServer(server);
        Early early = new Early();
        QuicLoopbackScenariosTest.ConnCapture client = new QuicLoopbackScenariosTest.ConnCapture();
        lb.startClientEarly(client, early);
        lb.pump();
        lb.pump();
        assertEquals(1, early.ready.size());
        assertTrue(client.conn.isEstablished());
        assertFalse(client.conn.getSecurityInfo().isEarlyDataAccepted());
        assertEquals(1, server.bidi.recs.size());
        assertEquals("hi", server.bidi.recs.get(0).data.toString());
    }

    @Test
    public void ticketResumesWithoutEarlyData() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        lb.startServer(new TicketingServer());
        QuicLoopbackScenariosTest.ConnCapture first = new QuicLoopbackScenariosTest.ConnCapture();
        lb.startClient(null, first);
        lb.pump();
        lb.pump();
        assertFalse(first.conn.getSecurityInfo().isSessionResumed());
        assertNotNull(SessionTicketCache.get("localhost", 4433));

        TicketingServer server = new TicketingServer();
        lb.startServer(server);
        QuicLoopbackScenariosTest.ConnCapture second = new QuicLoopbackScenariosTest.ConnCapture();
        lb.startClient(null, second);
        lb.pump();
        lb.pump();
        assertTrue(second.conn.isEstablished());
        assertTrue(second.conn.getSecurityInfo().isSessionResumed());
        assertTrue(server.conn.getSecurityInfo().isSessionResumed());
        assertFalse(second.conn.getSecurityInfo().isEarlyDataAccepted());
    }
}
