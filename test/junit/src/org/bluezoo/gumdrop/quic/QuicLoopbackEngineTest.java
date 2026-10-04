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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.bluezoo.gumdrop.quic.frame.QuicFrameWriter;
import org.junit.After;
import org.junit.Test;

/**
 * Engine-level behaviour over an in-memory pair: Retry, Version
 * Negotiation, stateless reset, migration, garbage input and 0-RTT.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackEngineTest {

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
    public void retryRoundTripCompletesHandshake() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setRequireRetry(true);
        Pair p = new Pair(lb);
        assertNotNull(p.server.conn);
        assertTrue(p.client.conn.isEstablished());
        assertTrue(p.server.conn.isEstablished());
        assertTrue(lb.toClientLog.size() > 1);
    }

    @Test
    public void retryWithForgedTokenIsAnsweredWithAnotherRetry() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setRequireRetry(true);
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        byte[] first = lb.toServerLog.get(0);
        lb.pump();
        // Replay the very first Initial from a different address: the
        // server has no state and the token (none) is absent, so it
        // answers with a Retry again instead of creating a connection.
        int before = lb.toClientLog.size();
        lb.clientSource = new InetSocketAddress("127.0.0.1", 50001);
        lb.injectToServer(first);
        assertEquals(before + 1, lb.toClientLog.size());
    }

    @Test
    public void versionNegotiationRestartsInCommonVersion() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setVersions("2");
        lb.clientFactory.setVersions("1,2");
        Pair p = new Pair(lb);
        assertNotNull(p.server.conn);
        assertEquals(org.bluezoo.gumdrop.quic.packet.QuicVersion.V2, p.server.conn.getVersion());
    }

    @Test
    public void versionNegotiationWithoutCommonVersionFails() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setVersions("2");
        lb.clientFactory.setVersions("1");
        lb.startFactories();
        lb.startServer(new ConnCapture());
        Rec waiting = new Rec();
        lb.startClient(waiting, null);
        lb.pump();
        assertTrue(waiting.events.toString(), waiting.events.contains("error"));
        assertNull(lb.serverEngine == null ? null : QuicForger.serverConnection(lb.serverEngine));
    }

    private static byte[] unknownVersionInitial(int length) {
        byte[] d = new byte[length];
        d[0] = (byte) 0xc0;
        d[1] = 0x0a;
        d[2] = 0x0a;
        d[3] = 0x0a;
        d[4] = 0x0a;
        d[5] = 8;
        for (int i = 0; i < 8; i++) {
            d[6 + i] = (byte) (i + 1);
        }
        d[14] = 8;
        for (int i = 0; i < 8; i++) {
            d[15 + i] = (byte) (i + 11);
        }
        return d;
    }

    @Test
    public void unsupportedVersionInitialGetsVersionNegotiation() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        Pair p = new Pair(lb);
        int before = lb.toClientLog.size();
        lb.injectToServer(unknownVersionInitial(1200));
        assertEquals(before + 1, lb.toClientLog.size());
        // too short to answer
        lb.injectToServer(unknownVersionInitial(100));
        assertEquals(before + 1, lb.toClientLog.size());
        assertNotNull(p.server.conn);
    }

    @Test
    public void garbageDatagramsAreIgnored() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        Pair p = new Pair(lb);
        lb.injectToServer(new byte[] {0x40});
        lb.injectToServer(new byte[] {(byte) 0xc0, 0, 0, 0, 1});
        lb.injectToServer(new byte[] {(byte) 0xc0, 0, 0, 0, 0, 5, 1});
        lb.injectToServer(new byte[] {(byte) 0xc0, 0, 0, 0, 1, 40, 1, 2, 3});
        byte[] shortUnknown = new byte[60];
        shortUnknown[0] = 0x40;
        for (int i = 1; i < 60; i++) {
            shortUnknown[i] = (byte) 0x77;
        }
        lb.injectToServer(shortUnknown);
        lb.injectToClient(new byte[] {(byte) 0xc0, 0, 0, 0, 0, 1, 2});
        lb.injectToClient(new byte[] {0x40, 1, 2});
        lb.injectToClient(new byte[] {(byte) 0xc0, 0x0a, 0x0a, 0x0a, 0x0a, 0, 0});
        assertFalse(p.server.conn.isClosed());
        assertFalse(p.client.conn.isClosed());
    }

    @Test
    public void emptyPathDatagramIsIgnored() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        Pair p = new Pair(lb);
        lb.injectToServer(new byte[0]);
        lb.injectToClient(new byte[0]);
        assertFalse(p.server.conn.isClosed());
        assertFalse(p.client.conn.isClosed());
    }

    @Test
    public void corruptedPacketFromPeerIsDropped() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        Pair p = new Pair(lb);
        ByteBuffer b = ByteBuffer.allocate(100);
        QuicFrameWriter.writePing(b);
        b.flip();
        byte[] d = QuicForger.forge(p.client.conn, p.server.conn, 2000, b);
        d[d.length - 1] ^= 0x55;
        lb.injectToServer(d);
        assertFalse(p.server.conn.isClosed());
    }

    @Test
    public void statelessResetClosesClient() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        Pair p = new Pair(lb);
        Rec c = new Rec();
        Endpoint e = p.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[200]));
        lb.pump();
        // The server forgets the connection and its goodbye is lost.
        lb.filter = new QuicLoopback.Filter() {
            @Override
            public boolean deliver(boolean toServer, int index, byte[] datagram) {
                return toServer;
            }
        };
        p.server.conn.close();
        lb.filter = null;
        assertFalse(p.client.conn.isClosed());
        e.send(ByteBuffer.wrap(new byte[200]));
        lb.pump();
        assertTrue(p.client.conn.isClosed());
        assertTrue(c.events.toString(), c.events.contains("error") || c.events.contains("disconnected"));
    }

    @Test
    public void engineClientApiOpensStreamsAndCloses() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        Pair p = new Pair(lb);
        Rec r = new Rec();
        Endpoint e = lb.clientEngine.openStream(r);
        assertNotNull(e);
        Endpoint u = lb.clientEngine.openUnidirectionalStream(new Rec());
        assertNotNull(u);
        lb.clientEngine.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        lb.pump();
        assertTrue(lb.clientEngine.isOpen());
        assertFalse(lb.clientEngine.isClosing());
        lb.clientEngine.close();
        lb.clientEngine.close();
        assertTrue(lb.clientEngine.isClosing());
        lb.pump();
        assertTrue(p.server.conn.isClosed());
        lb.serverEngine.close();
    }

    @Test
    public void serverMigrationValidatesNewPath() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        Pair p = new Pair(lb);
        Rec c = new Rec();
        Endpoint e = p.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[10]));
        lb.pump();
        lb.clientSource = new InetSocketAddress("127.0.0.1", 50001);
        e.send(ByteBuffer.wrap(new byte[10]));
        lb.pump();
        assertFalse(p.server.conn.isClosed());
        assertFalse(p.client.conn.isClosed());
        // Same source again does not restart validation.
        e.send(ByteBuffer.wrap(new byte[10]));
        lb.pump();
    }

    @Test
    public void earlyDataSessionResumption() throws Exception {
        QuicLoopback first = new QuicLoopback();
        first.serverFactory.setEarlyDataEnabled(true);
        first.clientFactory.setEarlyDataEnabled(true);
        Pair p = new Pair(first);
        assertTrue(p.client.conn.isEstablished());
        first.pump();
        Rec dummy = new Rec();
        p.client.conn.openStream(dummy);
        first.pump();

        QuicLoopback second = new QuicLoopback();
        second.serverFactory.setEarlyDataEnabled(true);
        second.clientFactory.setEarlyDataEnabled(true);
        second.startFactories();
        final ConnCapture server = new ConnCapture();
        second.startServer(server);
        final ConnCapture client = new ConnCapture();
        final List<QuicConnection> early = new ArrayList<QuicConnection>();
        second.startClientEarly(client, new QuicEngine.EarlyDataHandler() {
            @Override
            public void earlyDataReady(QuicConnection connection) {
                early.add(connection);
                Rec r = new Rec();
                Endpoint e = connection.openStream(r);
                if (e != null) {
                    e.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
                }
            }
        });
        second.pump();
        assertNotNull(client.conn);
        assertTrue(client.conn.isEstablished());
    }

    /** Whether two long-header packets carry the same Destination Connection ID. */
    private static boolean sameDestination(byte[] a, byte[] b) {
        int length = a[5] & 0xff;
        if ((b[5] & 0xff) != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (a[6 + i] != b[6 + i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * A late client Initial addressed to the server-chosen connection ID of
     * a connection the server has since forgotten must not create a new
     * connection that then claims that ID: the client's next 1-RTT packet
     * has to draw a stateless reset (RFC 9000 section 10.3), not be routed
     * to a phantom connection.
     */
    @Test
    public void lateInitialToForgottenConnectionDoesNotCreatePhantom() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        final List<QuicConnection> accepted = new ArrayList<QuicConnection>();
        lb.startServer(new QuicEngine.ConnectionAcceptedHandler() {
            @Override
            public void connectionAccepted(QuicConnection connection) {
                accepted.add(connection);
            }
        });
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertEquals(1, accepted.size());
        byte[] late = null;
        for (int i = 1; i < lb.toServerLog.size(); i++) {
            byte[] d = lb.toServerLog.get(i);
            boolean longHeader = (d[0] & 0x80) != 0;
            boolean initial = (d[0] & 0x30) == 0;
            // The ClientHello itself may span several Initials, all still
            // addressed to the client's own first choice of connection ID.
            if (longHeader && initial && d.length >= 1200 && !sameDestination(d, lb.toServerLog.get(0))) {
                late = d;
                break;
            }
        }
        assertNotNull("client sent a padded Initial to the server-chosen connection ID", late);
        QuicForger.invoke(accepted.get(0), "dropLocalState");
        lb.injectToServer(late);
        assertEquals("no phantom connection", 1, accepted.size());
        Endpoint stream = client.conn.openStream(new Rec());
        stream.send(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        lb.pump();
        assertTrue("client saw the stateless reset", client.conn.isClosed());
    }
}
