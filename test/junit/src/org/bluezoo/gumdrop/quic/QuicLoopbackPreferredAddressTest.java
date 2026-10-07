/*
 * QuicLoopbackPreferredAddressTest.java
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.junit.Test;

/**
 * RFC 9000 section 9.6: a server advertises a {@code preferred_address}
 * and, once the handshake is confirmed, the client migrates to it from a
 * new local path using the connection ID the parameter carried; the
 * server validates the client's new path and follows.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackPreferredAddressTest {

    private static final class Fixture {
        final QuicLoopback lb = new QuicLoopback();
        final ConnCapture server = new ConnCapture();
        final ConnCapture client = new ConnCapture();

        Fixture(boolean serverPreferred, boolean clientMigrates) throws Exception {
            if (serverPreferred) {
                lb.serverFactory.setPreferredAddress(QuicLoopback.SERVER_PREFERRED_ADDRESS, null);
            }
            lb.clientFactory.setMigrateToPreferredAddress(clientMigrates);
            lb.startFactories();
            lb.startServer(server);
            if (serverPreferred) {
                lb.enableServerPreferredAddress();
            }
            lb.startClient(null, client);
            lb.pump();
            assertTrue(server.conn.isEstablished());
            assertTrue(client.conn.isEstablished());
        }

        String exchange(String request, String reply) throws Exception {
            Rec c = new Rec();
            Endpoint e = client.conn.openStream(c);
            int before = server.bidi.recs.size();
            e.send(ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII)));
            e.close();
            lb.pump();
            assertEquals(before + 1, server.bidi.recs.size());
            Rec s = server.bidi.recs.get(before);
            assertEquals(request, s.data.toString());
            s.endpoint.send(ByteBuffer.wrap(reply.getBytes(StandardCharsets.US_ASCII)));
            s.endpoint.close();
            lb.pump();
            return c.data.toString();
        }
    }

    @Test
    public void clientMigratesToThePreferredAddressAndTheServerFollows() throws Exception {
        Fixture f = new Fixture(true, true);
        assertTrue(f.client.conn.getPeerTransportParameters().hasPreferredAddress());
        byte[] preferredCid = f.client.conn.getPeerTransportParameters().getPreferredAddressConnectionId();

        assertEquals("pong", f.exchange("ping", "pong"));
        assertEquals("the client now sends to the preferred address",
                QuicLoopback.SERVER_PREFERRED_ADDRESS, f.client.conn.getRemoteAddress());
        assertEquals("from a new local path", QuicLoopback.CLIENT_ADDRESS_2, f.client.conn.getLocalAddress());
        assertArrayEquals("with the connection ID the parameter carried",
                preferredCid, f.client.conn.getPeerConnectionId());
        assertEquals("the server validated the client's new path and moved to it",
                QuicLoopback.CLIENT_ADDRESS_2, f.server.conn.getRemoteAddress());
        assertEquals("replying from its preferred address",
                QuicLoopback.SERVER_PREFERRED_ADDRESS, f.server.conn.getLocalAddress());
        assertEquals("pong2", f.exchange("ping2", "pong2"));
        assertFalse(f.client.conn.isClosed());
        assertFalse(f.server.conn.isClosed());
    }

    @Test
    public void clientConfiguredNotToMigrateStaysPut() throws Exception {
        Fixture f = new Fixture(true, false);
        assertTrue(f.client.conn.getPeerTransportParameters().hasPreferredAddress());
        assertEquals("pong", f.exchange("ping", "pong"));
        assertEquals(QuicLoopback.SERVER_ADDRESS, f.client.conn.getRemoteAddress());
        assertEquals(QuicLoopback.CLIENT_ADDRESS, f.server.conn.getRemoteAddress());
    }

    @Test
    public void serverWithoutPreferredAddressAdvertisesNone() throws Exception {
        Fixture f = new Fixture(false, true);
        assertFalse(f.client.conn.getPeerTransportParameters().hasPreferredAddress());
        assertEquals("pong", f.exchange("ping", "pong"));
        assertEquals(QuicLoopback.SERVER_ADDRESS, f.client.conn.getRemoteAddress());
    }

}
