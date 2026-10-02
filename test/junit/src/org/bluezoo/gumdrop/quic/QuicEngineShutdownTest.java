/*
 * QuicEngineShutdownTest.java
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

import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.junit.After;
import org.junit.Test;

/**
 * Closing a {@link QuicEngine} for shutdown: an orderly close sends the
 * peer a CONNECTION_CLOSE before the socket goes, an abort tears down local
 * state and sends nothing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicEngineShutdownTest {

    @After
    public void clearTickets() {
        SessionTicketCache.clear();
    }

    private static final class Pair {
        final QuicLoopback lb;
        final ConnCapture server = new ConnCapture();
        final ConnCapture client = new ConnCapture();

        Pair(QuicLoopback lb) {
            this.lb = lb;
            lb.startFactories();
            lb.startServer(server);
            lb.startClient(null, client);
            lb.pump();
        }
    }

    @Test
    public void orderlyCloseTellsThePeer() throws Exception {
        Pair p = new Pair(new QuicLoopback());
        assertTrue(p.client.conn.isEstablished());
        int before = p.lb.toClientLog.size();

        p.lb.serverEngine.closeForShutdown(true);

        assertTrue("CONNECTION_CLOSE goes to the peer", p.lb.toClientLog.size() > before);
        assertTrue(p.server.conn.isClosed());
        assertFalse(p.lb.serverEngine.isOpen());
        p.lb.pump();
        assertTrue("the peer learns of the close", p.client.conn.isClosed());
    }

    @Test
    public void abortSendsNothingButClosesLocalState() throws Exception {
        Pair p = new Pair(new QuicLoopback());
        int before = p.lb.toClientLog.size();

        p.lb.serverEngine.closeForShutdown(false);

        assertEquals("no goodbye on abort", before, p.lb.toClientLog.size());
        assertTrue(p.server.conn.isClosed());
        assertFalse(p.lb.serverEngine.isOpen());
        p.lb.pump();
        assertFalse("the peer has been told nothing", p.client.conn.isClosed());
    }

    @Test
    public void closingTwiceIsHarmless() throws Exception {
        Pair p = new Pair(new QuicLoopback());
        p.lb.serverEngine.closeForShutdown(true);
        int after = p.lb.toClientLog.size();
        p.lb.serverEngine.closeForShutdown(false);
        p.lb.serverEngine.closeForShutdown(true);
        assertEquals(after, p.lb.toClientLog.size());
    }
}
