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

import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.junit.Test;

/**
 * RFC 9000 section 14.1: a server must expand every UDP datagram that
 * carries an ack-eliciting Initial packet to at least 1200 bytes. picoquic
 * enforces it ("Server initial too short") and silently drops the packet,
 * so a gumdrop server whose first flight came to less never completed a
 * handshake with it. quic-go and ngtcp2 do not check, which hid the fault.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackInitialPaddingTest {

    private static final int MIN_DATAGRAM_SIZE = 1200;

    @Test
    public void serversFirstFlightIsExpandedToTheMinimumDatagramSize() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        // classical groups only: no post-quantum key share, so the ServerHello
        // is small and, with the small test certificate, the whole first flight
        // is well under 1200 bytes unless the server pads it
        lb.serverFactory.setNamedGroups("x25519");
        lb.clientFactory.setNamedGroups("x25519");
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertTrue("the handshake must still complete", client.conn.isEstablished());
        assertTrue("the server sent its first flight", !lb.toClientLog.isEmpty());
        int first = lb.toClientLog.get(0).length;
        assertTrue("the server's first datagram carries an ack-eliciting Initial packet and is "
                + first + " bytes, under the 1200 byte minimum", first >= MIN_DATAGRAM_SIZE);
    }
}
