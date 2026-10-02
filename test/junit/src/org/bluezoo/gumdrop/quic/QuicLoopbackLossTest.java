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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.junit.Test;

/**
 * Loss recovery (RFC 9002) and migration path validation (RFC 9000 section
 * 9) over an in-memory pair. The loss timer never fires on its own here, so
 * the tests invoke its callback directly and advance each connection's
 * package-private clock offset to stand in for the passage of time.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackLossTest {

    /** Simulated time advanced per tick: well past any loss or probe timer. */
    private static final long TICK_MILLIS = 400L;

    private static void tick(QuicLoopback lb, QuicConnection a, QuicConnection b, int rounds) throws Exception {
        for (int i = 0; i < rounds; i++) {
            if (a != null && !a.isClosed()) {
                a.clockOffsetMillis += TICK_MILLIS;
                QuicForger.invoke(a, "onLossDetectionTimeout");
            }
            if (b != null && !b.isClosed()) {
                b.clockOffsetMillis += TICK_MILLIS;
                QuicForger.invoke(b, "onLossDetectionTimeout");
            }
            lb.pump();
        }
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    @Test
    public void lostClientInitialIsRetransmittedOnProbeTimeout() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return !(toServer && index == 0);
            }
        };
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        QuicConnection cc = QuicForger.clientConnection(lb.clientEngine);
        tick(lb, cc, null, 1);
        QuicConnection sc = QuicForger.serverConnection(lb.serverEngine);
        assertNotNull(sc);
        tick(lb, cc, sc, 12);
        assertTrue(cc.isEstablished());
    }

    @Test
    public void lostServerFlightIsRecovered() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return toServer || index > 1;
            }
        };
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        QuicConnection cc = QuicForger.clientConnection(lb.clientEngine);
        tick(lb, cc, null, 1);
        QuicConnection sc = QuicForger.serverConnection(lb.serverEngine);
        assertNotNull(sc);
        tick(lb, cc, sc, 12);
        assertTrue(cc.isEstablished());
        assertTrue(sc.isEstablished());
    }

    @Test
    public void retransmittedInitialDoesNotCreateSecondConnection() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        final java.util.List<QuicConnection> accepted = new java.util.ArrayList<QuicConnection>();
        lb.startServer(new QuicEngine.ConnectionAcceptedHandler() {
            @Override
            public void connectionAccepted(QuicConnection connection) {
                accepted.add(connection);
            }
        });
        // The server's first flight is lost, so the client has not yet
        // learned the server's connection ID when it retransmits its
        // Initial, which still carries the client-chosen original DCID.
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return toServer || index > 1;
            }
        };
        lb.startClient(null, new ConnCapture());
        lb.pump();
        QuicConnection cc = QuicForger.clientConnection(lb.clientEngine);
        for (int i = 0; i < 12 && accepted.isEmpty(); i++) {
            tick(lb, cc, null, 1);
        }
        QuicConnection sc = accepted.get(0);
        tick(lb, cc, sc, 12);
        assertEquals("one connection for one client attempt", 1, accepted.size());
        assertTrue(cc.isEstablished());
        assertTrue(accepted.get(0).isEstablished());
    }

    @Test
    public void lostStreamDataIsRetransmitted() throws Exception {
        QuicLoopbackFramesTest.Fixture f = QuicLoopbackFramesTest.Fixture.create();
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(bytes(10)));
        f.lb.pump();
        final int start = f.lb.sentToServer;
        f.lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return !toServer || index < start || index > start + 2;
            }
        };
        e.send(ByteBuffer.wrap(bytes(5000)));
        f.lb.pump();
        f.lb.filter = null;
        tick(f.lb, f.client.conn, f.server.conn, 10);
        assertEquals(5010, f.server.bidi.recs.get(0).bytes);
    }

    @Test
    public void gapInAcknowledgementsTriggersFastRetransmit() throws Exception {
        QuicLoopbackFramesTest.Fixture f = QuicLoopbackFramesTest.Fixture.create();
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(bytes(10)));
        f.lb.pump();
        final int dropped = f.lb.sentToServer + 2;
        f.lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return !(toServer && index == dropped);
            }
        };
        e.send(ByteBuffer.wrap(bytes(60000)));
        for (int i = 0; i < 30; i++) {
            f.lb.pump();
        }
        f.lb.filter = null;
        tick(f.lb, f.client.conn, f.server.conn, 10);
        assertEquals(60010, f.server.bidi.recs.get(0).bytes);
    }

    @Test
    public void lostResponseDataFromServerIsRecovered() throws Exception {
        QuicLoopbackFramesTest.Fixture f = QuicLoopbackFramesTest.Fixture.create();
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(bytes(10)));
        f.lb.pump();
        f.lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return toServer;
            }
        };
        f.server.bidi.recs.get(0).endpoint.send(ByteBuffer.wrap(bytes(3000)));
        f.lb.pump();
        assertEquals(0, c.bytes);
        f.lb.filter = null;
        Endpoint se = f.server.bidi.recs.get(0).endpoint;
        for (int i = 0; i < 5; i++) {
            se.send(ByteBuffer.wrap(new byte[] {1}));
            f.lb.pump();
        }
        tick(f.lb, f.client.conn, f.server.conn, 10);
        assertEquals(3005, c.bytes);
    }

    @Test
    public void migrationCompletesAfterPathResponse() throws Exception {
        QuicLoopbackFramesTest.Fixture f = QuicLoopbackFramesTest.Fixture.create();
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(bytes(10)));
        f.lb.pump();
        InetSocketAddress moved = new InetSocketAddress("127.0.0.1", 50001);
        f.lb.clientSource = moved;
        e.send(ByteBuffer.wrap(bytes(10)));
        f.lb.pump();
        assertEquals(moved, f.server.conn.getRemoteAddress());
        // Traffic from the abandoned address is not another migration.
        f.lb.clientSource = QuicLoopback.CLIENT_ADDRESS;
        e.send(ByteBuffer.wrap(bytes(10)));
        f.lb.pump();
        assertFalse(f.server.conn.isClosed());
    }

    @Test
    public void migrationCandidatesAreCappedAndTimeOut() throws Exception {
        QuicLoopbackFramesTest.Fixture f = QuicLoopbackFramesTest.Fixture.create();
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(bytes(10)));
        f.lb.pump();
        // Drop server to client traffic so no PATH_RESPONSE comes back.
        f.lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return toServer;
            }
        };
        InetSocketAddress[] candidates = new InetSocketAddress[5];
        for (int i = 0; i < candidates.length; i++) {
            candidates[i] = new InetSocketAddress("127.0.0.1", 51000 + i);
            f.lb.clientSource = candidates[i];
            e.send(ByteBuffer.wrap(bytes(10)));
            f.lb.pump();
        }
        Map<?, ?> attempts = (Map<?, ?>) QuicForger.field(f.server.conn, "pathValidationAttempts");
        assertEquals(3, attempts.size());
        // The retry timer fires before the deadline: another challenge.
        QuicForger.invoke(f.server.conn, "onPathValidationTimeout", candidates[0]);
        assertEquals(3, attempts.size());
        // A timer for an unknown candidate is a no-op.
        QuicForger.invoke(f.server.conn, "onPathValidationTimeout", candidates[4]);
        // Abandoning removes the candidate.
        QuicForger.invoke(f.server.conn, "abandonMigrationValidation", candidates[1]);
        assertEquals(2, attempts.size());
        QuicForger.invoke(f.server.conn, "abandonMigrationValidation", candidates[1]);
        assertFalse(f.server.conn.isClosed());
    }

    @Test
    public void pathValidationTimeoutPastDeadlineAbandons() throws Exception {
        QuicLoopbackFramesTest.Fixture f = QuicLoopbackFramesTest.Fixture.create();
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(bytes(10)));
        f.lb.pump();
        f.lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return toServer;
            }
        };
        InetSocketAddress candidate = new InetSocketAddress("127.0.0.1", 52000);
        f.lb.clientSource = candidate;
        e.send(ByteBuffer.wrap(bytes(10)));
        f.lb.pump();
        Map<?, ?> attempts = (Map<?, ?>) QuicForger.field(f.server.conn, "pathValidationAttempts");
        assertEquals(1, attempts.size());
        // Move the connection's clock past the attempt's deadline: the
        // timeout then abandons the candidate instead of re-challenging it.
        f.server.conn.clockOffsetMillis += 3600000L;
        QuicForger.invoke(f.server.conn, "onPathValidationTimeout", candidate);
        assertEquals(0, attempts.size());
    }
}
