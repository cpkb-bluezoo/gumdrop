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
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.quic.cid.ConnectionIdManager;
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
        // the server saw the connection ID the parameter carried (sequence
        // number 1) addressed on the new path, and so retires the first and
        // gives the client another in its place (it then goes on with that)
        ConnectionIdManager ids = (ConnectionIdManager) QuicForger.field(f.server.conn, "connectionIdManager");
        assertTrue("the first connection ID is retired", ids.getRetirePriorTo() >= 1);
        assertEquals("the server validated the client's new path and moved to it",
                QuicLoopback.CLIENT_ADDRESS_2, f.server.conn.getRemoteAddress());
        assertEquals("replying from its preferred address",
                QuicLoopback.SERVER_PREFERRED_ADDRESS, f.server.conn.getLocalAddress());
        assertEquals("pong2", f.exchange("ping2", "pong2"));
        assertFalse(f.client.conn.isClosed());
        assertFalse(f.server.conn.isClosed());
    }

    // A client moving to the preferred address sends a packet of a PING
    // alone ahead of its PATH_CHALLENGE, so that a server which starts
    // validating on a non-probing packet (ngtcp2) sends its own challenge
    // first. The packet builder's qlog code took every packet that was not a
    // challenge for a PATH_RESPONSE and failed on the PING, which stopped the
    // migration, but only with qlog on.
    @Test
    public void clientPingsBeforeChallengingThePreferredAddressWithQlogOn() throws Exception {
        TelemetryConfig config = new TelemetryConfig();
        QuicLoopbackQlogTest.Capture capture = new QuicLoopbackQlogTest.Capture();
        config.exporter(capture);
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setPreferredAddress(QuicLoopback.SERVER_PREFERRED_ADDRESS, null);
        lb.clientFactory.setMigrateToPreferredAddress(true);
        lb.clientFactory.setTelemetryConfig(config);
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        ConnCapture client = new ConnCapture();
        lb.startServer(server);
        lb.enableServerPreferredAddress();
        lb.startClient(null, client);
        lb.pump();
        assertTrue(client.conn.isEstablished());
        QuicForger.invoke(client.conn, "onAckTimeout");
        QuicForger.invoke(server.conn, "onAckTimeout");
        lb.pump();

        java.util.List<java.util.List<String>> sent = new java.util.ArrayList<java.util.List<String>>();
        for (org.bluezoo.gumdrop.telemetry.LogRecord r : capture.named("client", "quic:packet_sent")) {
            java.util.Map<String, String> p = org.bluezoo.gumdrop.testsupport.FlatJson.flatten(r.getBody());
            java.util.List<String> types = new java.util.ArrayList<String>();
            for (int i = 0; p.containsKey("frames." + i + ".frame_type"); i++) {
                types.add(p.get("frames." + i + ".frame_type"));
            }
            sent.add(types);
        }
        int challenge = -1;
        int ping = -1;
        for (int i = 0; i < sent.size(); i++) {
            if (challenge < 0 && sent.get(i).contains("path_challenge")) {
                challenge = i;
            }
            if (ping < 0 && sent.get(i).contains("ping") && !sent.get(i).contains("path_challenge")) {
                ping = i;
            }
        }
        assertTrue("the client challenged the preferred address: " + sent, challenge >= 0);
        assertTrue("a packet of a PING came before the challenge: " + sent, ping >= 0 && ping < challenge);
        assertEquals(QuicLoopback.SERVER_PREFERRED_ADDRESS, client.conn.getRemoteAddress());
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
