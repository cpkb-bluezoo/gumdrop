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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.junit.Before;
import org.junit.Test;

/**
 * Multi-stream, flow-control, datagram and close scenarios run over an
 * in-memory client/server QUIC pair.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackScenariosTest {

    /** Tickets cached by other tests would seed remembered transport parameters. */
    @Before
    public void clearTicketCache() {
        SessionTicketCache.clear();
    }

    /** Records everything a stream handler sees. */
    static final class Rec implements ProtocolHandler {
        final List<String> events = new ArrayList<String>();
        final StringBuilder data = new StringBuilder();
        final List<byte[]> datagrams = new ArrayList<byte[]>();
        Endpoint endpoint;
        int bytes;

        @Override
        public void receive(ByteBuffer d) {
            bytes += d.remaining();
            while (d.hasRemaining()) {
                data.append((char) (d.get() & 0xff));
            }
        }

        @Override
        public void connected(Endpoint e) {
            endpoint = e;
            events.add("connected");
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
            events.add("security");
        }

        @Override
        public void disconnected() {
            events.add("disconnected");
        }

        @Override
        public void error(Exception cause) {
            events.add("error");
        }

        @Override
        public void readFinished() {
            events.add("readFinished");
        }

        @Override
        public void datagramReceived(ByteBuffer d) {
            byte[] b = new byte[d.remaining()];
            d.get(b);
            datagrams.add(b);
        }
    }

    /** Collects accepted streams. */
    static final class Acceptor implements StreamAcceptHandler {
        final List<Rec> recs = new ArrayList<Rec>();

        @Override
        public ProtocolHandler acceptStream(Endpoint stream) {
            Rec r = new Rec();
            recs.add(r);
            return r;
        }
    }

    /** Captures connections. */
    static final class ConnCapture implements QuicEngine.ConnectionAcceptedHandler {
        QuicConnection conn;
        final Acceptor bidi = new Acceptor();
        final Acceptor uni = new Acceptor();

        @Override
        public void connectionAccepted(QuicConnection connection) {
            conn = connection;
            connection.setStreamAcceptHandler(bidi);
            connection.setUnidirectionalStreamAcceptHandler(uni);
        }
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ('a' + (i % 26));
        }
        return b;
    }

    @Test
    public void connectionLevelHandlersUniAndBidiStreams() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertNotNull(server.conn);
        assertNotNull(client.conn);
        assertTrue(client.conn.isEstablished());
        assertTrue(server.conn.isEstablished());
        assertFalse(client.conn.isClosed());
        assertNotNull(client.conn.getVersion());
        assertNotNull(client.conn.getRemoteAddress());
        assertNotNull(client.conn.getLocalAddress());
        SecurityInfo si = client.conn.getSecurityInfo();
        assertNotNull(si);

        Rec c1 = new Rec();
        Endpoint e1 = client.conn.openStream(c1);
        assertNotNull(e1);
        Rec c2 = new Rec();
        Endpoint e2 = client.conn.openUnidirectionalStream(c2);
        assertNotNull(e2);
        e1.send(ByteBuffer.wrap(bytes(100)));
        e2.send(ByteBuffer.wrap(bytes(50)));
        lb.pump();
        assertEquals(1, server.bidi.recs.size());
        assertEquals(1, server.uni.recs.size());
        assertEquals(100, server.bidi.recs.get(0).bytes);
        assertEquals(50, server.uni.recs.get(0).bytes);
        e1.close();
        e2.close();
        lb.pump();
        assertTrue(server.bidi.recs.get(0).events.contains("readFinished"));
        server.bidi.recs.get(0).endpoint.close();
        lb.pump();
        assertFalse(e1.isOpen());
    }

    @Test
    public void largeTransferExercisesFlowControl() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setMaxData(30000);
        lb.serverFactory.setMaxStreamDataBidiRemote(10000);
        lb.clientFactory.setMaxData(30000);
        lb.clientFactory.setMaxStreamDataBidiLocal(10000);
        lb.startFactories();
        final Acceptor acc = new Acceptor();
        lb.startServer(acc);
        Rec c = new Rec();
        lb.startClient(c, null);
        lb.pump();
        c.endpoint.send(ByteBuffer.wrap(bytes(200000)));
        for (int i = 0; i < 50; i++) {
            lb.pump();
        }
        assertEquals(200000, acc.recs.get(0).bytes);
        acc.recs.get(0).endpoint.send(ByteBuffer.wrap(bytes(120000)));
        for (int i = 0; i < 50; i++) {
            lb.pump();
        }
        assertEquals(120000, c.bytes);
    }

    @Test
    public void streamLimitQueuesOpensUntilCreditReleased() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setMaxStreamsBidi(2);
        lb.serverFactory.setMaxStreamsUni(1);
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        Rec[] recs = new Rec[4];
        Endpoint[] eps = new Endpoint[4];
        for (int i = 0; i < 4; i++) {
            recs[i] = new Rec();
            eps[i] = client.conn.openStream(recs[i]);
        }
        assertNotNull(eps[0]);
        assertNotNull(eps[1]);
        assertNull(eps[2]);
        Rec u1 = new Rec();
        Rec u2 = new Rec();
        assertNotNull(client.conn.openUnidirectionalStream(u1));
        assertNull(client.conn.openUnidirectionalStream(u2));
        lb.pump();
        server.conn.releaseStreamCredit(true);
        server.conn.releaseStreamCredit(true);
        server.conn.releaseStreamCredit(false);
        lb.pump();
        assertEquals("connected", recs[2].events.get(0));
        assertEquals("connected", recs[3].events.get(0));
        assertEquals("connected", u2.events.get(0));
    }

    @Test
    public void datagramsFlowBothWays() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setMaxDatagramFrameSize(1200);
        lb.clientFactory.setMaxDatagramFrameSize(1200);
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        Rec sd = new Rec();
        Rec cd = new Rec();
        server.conn.setDatagramHandler(sd);
        client.conn.setDatagramHandler(cd);
        assertEquals(1200, client.conn.getPeerMaxDatagramFrameSize());
        assertTrue(client.conn.sendDatagram(ByteBuffer.wrap(bytes(100))));
        assertTrue(server.conn.sendDatagram(ByteBuffer.wrap(bytes(10))));
        assertFalse(client.conn.sendDatagram(ByteBuffer.wrap(bytes(5000))));
        assertFalse(client.conn.sendDatagram(null));
        lb.pump();
        assertEquals(1, sd.datagrams.size());
        assertEquals(1, cd.datagrams.size());
        assertEquals(100, sd.datagrams.get(0).length);
    }

    @Test
    public void datagramsRefusedWhenNotNegotiated() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setMaxDatagramFrameSize(0);
        lb.clientFactory.setMaxDatagramFrameSize(0);
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        assertEquals(0, client.conn.getPeerMaxDatagramFrameSize());
        assertFalse(client.conn.sendDatagram(ByteBuffer.wrap(bytes(5))));
    }

    @Test
    public void applicationCloseNotifiesPeer() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        Rec c = new Rec();
        Endpoint e = client.conn.openStream(c);
        e.send(ByteBuffer.wrap(bytes(10)));
        lb.pump();
        Rec s = server.bidi.recs.get(0);
        client.conn.closeWithApplicationError(42, "bye");
        assertTrue(client.conn.isClosed());
        lb.pump();
        assertTrue(server.conn.isClosed());
        assertTrue(s.events.contains("error") || s.events.contains("disconnected"));
    }

    @Test
    public void cleanCloseNotifiesPeer() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        client.conn.close();
        lb.pump();
        assertTrue(server.conn.isClosed());
    }

    @Test
    public void resetStreamReachesPeer() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        Rec c = new Rec();
        QuicStreamEndpoint e = (QuicStreamEndpoint) client.conn.openStream(c);
        e.send(ByteBuffer.wrap(bytes(10)));
        lb.pump();
        e.resetStream(7);
        lb.pump();
        Rec s = server.bidi.recs.get(0);
        assertTrue(s.events.toString(), s.events.contains("error") || s.events.contains("disconnected"));
    }

    @Test
    public void pauseAndResumeRead() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.startFactories();
        ConnCapture server = new ConnCapture();
        lb.startServer(server);
        ConnCapture client = new ConnCapture();
        lb.startClient(null, client);
        lb.pump();
        Rec c = new Rec();
        Endpoint e = client.conn.openStream(c);
        e.send(ByteBuffer.wrap(bytes(10)));
        lb.pump();
        Rec s = server.bidi.recs.get(0);
        s.endpoint.pauseRead();
        e.send(ByteBuffer.wrap(bytes(20)));
        lb.pump();
        assertEquals(10, s.bytes);
        s.endpoint.resumeRead();
        lb.pump();
        assertEquals(30, s.bytes);
    }
}
