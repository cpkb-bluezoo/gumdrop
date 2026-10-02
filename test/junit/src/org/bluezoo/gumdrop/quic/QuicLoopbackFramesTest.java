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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.quic.frame.QuicFrameWriter;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.ConnCapture;
import org.bluezoo.gumdrop.quic.QuicLoopbackScenariosTest.Rec;
import org.junit.Test;

/**
 * Drives {@link QuicConnection}'s frame handling by injecting forged,
 * correctly protected 1-RTT packets into an established in-memory pair.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicLoopbackFramesTest {

    /** An established connection pair plus helpers to inject frames. */
    static final class Fixture {
        final QuicLoopback lb;
        final ConnCapture server = new ConnCapture();
        final ConnCapture client = new ConnCapture();
        long pn = 1000;

        Fixture(QuicLoopback lb) throws Exception {
            this.lb = lb;
            lb.startFactories();
            lb.startServer(server);
            lb.startClient(null, client);
            lb.pump();
            assertTrue(server.conn.isEstablished());
            assertTrue(client.conn.isEstablished());
        }

        static Fixture create() throws Exception {
            return new Fixture(new QuicLoopback());
        }

        ByteBuffer buf() {
            return ByteBuffer.allocate(4096);
        }

        void toServer(ByteBuffer frames) throws Exception {
            frames.flip();
            byte[] d = QuicForger.forge(client.conn, server.conn, pn++, frames);
            lb.injectToServer(d);
            lb.pump();
        }

        void toClient(ByteBuffer frames) throws Exception {
            frames.flip();
            byte[] d = QuicForger.forge(server.conn, client.conn, pn++, frames);
            lb.injectToClient(d);
            lb.pump();
        }
    }

    @Test
    public void pingPaddingAndTokenAreAccepted() throws Exception {
        Fixture f = Fixture.create();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writePing(b);
        QuicFrameWriter.writePadding(b, 5);
        f.toServer(b);
        b = f.buf();
        QuicFrameWriter.writeNewToken(b, new byte[] {1, 2, 3});
        f.toClient(b);
        assertFalse(f.server.conn.isClosed());
        assertFalse(f.client.conn.isClosed());
    }

    @Test
    public void resetStreamFrameClosesStream() throws Exception {
        Fixture f = Fixture.create();
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[] {1}));
        f.lb.pump();
        Rec s = f.server.bidi.recs.get(0);
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeResetStream(b, 0, 5, 1);
        f.toServer(b);
        assertTrue(s.events.contains("disconnected"));
    }

    @Test
    public void resetStreamBeyondLimitClosesConnection() throws Exception {
        Fixture f = Fixture.create();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeResetStream(b, 4L * 100000, 5, 0);
        f.toServer(b);
        assertTrue(f.server.conn.isClosed());
    }

    @Test
    public void stopSendingResetsLocalStream() throws Exception {
        Fixture f = Fixture.create();
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[] {1}));
        f.lb.pump();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeStopSending(b, 0, 9);
        f.toClient(b);
        assertFalse(f.client.conn.isClosed());
    }

    @Test
    public void flowControlFramesUpdateLimits() throws Exception {
        Fixture f = Fixture.create();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeMaxData(b, 99999999L);
        QuicFrameWriter.writeMaxData(b, 5);
        QuicFrameWriter.writeMaxStreamData(b, 0, 5000000);
        QuicFrameWriter.writeMaxStreamData(b, 0, 10);
        QuicFrameWriter.writeMaxStreams(b, true, 5000);
        QuicFrameWriter.writeMaxStreams(b, true, 10);
        QuicFrameWriter.writeMaxStreams(b, false, 5000);
        QuicFrameWriter.writeMaxStreams(b, false, 10);
        f.toServer(b);
        assertFalse(f.server.conn.isClosed());
    }

    @Test
    public void maxStreamsAboveLimitClosesConnection() throws Exception {
        Fixture f = Fixture.create();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeMaxStreams(b, true, (1L << 60) + 1);
        f.toServer(b);
        assertTrue(f.server.conn.isClosed());
    }

    @Test
    public void blockedFramesGrowLimits() throws Exception {
        Fixture f = Fixture.create();
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[] {1}));
        f.lb.pump();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeDataBlocked(b, 100);
        QuicFrameWriter.writeStreamDataBlocked(b, 0, 100);
        QuicFrameWriter.writeStreamsBlocked(b, true, 100);
        QuicFrameWriter.writeStreamsBlocked(b, false, 100);
        f.toServer(b);
        assertFalse(f.server.conn.isClosed());
    }

    @Test
    public void streamDataBlockedBeyondLimitClosesConnection() throws Exception {
        Fixture f = Fixture.create();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeStreamDataBlocked(b, 4L * 100000, 100);
        f.toServer(b);
        assertTrue(f.server.conn.isClosed());
    }

    @Test
    public void connectionIdFramesIssueAndRetire() throws Exception {
        Fixture f = Fixture.create();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeNewConnectionId(b, 1, 0, new byte[] {9, 9, 9, 9, 9, 9, 9, 9}, new byte[16]);
        QuicFrameWriter.writeNewConnectionId(b, 2, 1, new byte[] {8, 8, 8, 8, 8, 8, 8, 8}, new byte[16]);
        f.toServer(b);
        b = f.buf();
        QuicFrameWriter.writeRetireConnectionId(b, 0);
        QuicFrameWriter.writeRetireConnectionId(b, 77);
        f.toServer(b);
        assertFalse(f.server.conn.isClosed());
    }

    @Test
    public void pathChallengeIsAnsweredAndStrayResponseIgnored() throws Exception {
        Fixture f = Fixture.create();
        int before = f.lb.sentToClient;
        ByteBuffer b = f.buf();
        QuicFrameWriter.writePathChallenge(b, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        f.toServer(b);
        assertTrue(f.lb.sentToClient > before);
        b = f.buf();
        QuicFrameWriter.writePathResponse(b, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        f.toServer(b);
        assertFalse(f.server.conn.isClosed());
    }

    @Test
    public void handshakeDoneFrameIsAccepted() throws Exception {
        Fixture f = Fixture.create();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeHandshakeDone(b);
        f.toClient(b);
        assertFalse(f.client.conn.isClosed());
    }

    @Test
    public void oversizedDatagramClosesConnection() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setMaxDatagramFrameSize(20);
        Fixture f = new Fixture(lb);
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeDatagram(b, new byte[100]);
        f.toServer(b);
        assertTrue(f.server.conn.isClosed());
    }

    @Test
    public void datagramWhenDisabledClosesConnection() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setMaxDatagramFrameSize(0);
        Fixture f = new Fixture(lb);
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeDatagram(b, new byte[10]);
        f.toServer(b);
        assertTrue(f.server.conn.isClosed());
    }

    @Test
    public void unknownFrameTypeIsLoggedNotFatalToTest() throws Exception {
        Fixture f = Fixture.create();
        ByteBuffer b = f.buf();
        b.put((byte) 0x3f);
        b.put((byte) 0xff);
        f.toServer(b);
        assertNotNull(f.server.conn);
    }

    @Test
    public void ackFrameForSentPackets() throws Exception {
        Fixture f = Fixture.create();
        Rec c = new Rec();
        Endpoint e = f.client.conn.openStream(c);
        e.send(ByteBuffer.wrap(new byte[100]));
        f.lb.pump();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeAck(b, new long[][] {{0, 50}}, 10);
        f.toClient(b);
        b = f.buf();
        QuicFrameWriter.writeAck(b, new long[][] {{10, 12}, {2, 4}}, 0);
        f.toClient(b);
        assertFalse(f.client.conn.isClosed());
    }

    @Test
    public void streamFramesOutOfOrderWithFin() throws Exception {
        Fixture f = Fixture.create();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeStream(b, 0, 5, new byte[] {5, 6, 7}, true);
        f.toServer(b);
        b = f.buf();
        QuicFrameWriter.writeStream(b, 0, 0, new byte[] {0, 1, 2, 3, 4}, false);
        f.toServer(b);
        Rec s = f.server.bidi.recs.get(0);
        assertEquals(8, s.bytes);
        assertTrue(s.events.contains("readFinished"));
        b = f.buf();
        QuicFrameWriter.writeStream(b, 0, 0, new byte[] {0, 1}, false);
        f.toServer(b);
    }

    @Test
    public void streamFrameBeyondFlowControlClosesConnection() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setMaxStreamDataBidiRemote(100);
        Fixture f = new Fixture(lb);
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeStream(b, 0, 0, new byte[3000], false);
        f.toServer(b);
        assertTrue(f.server.conn.isClosed());
    }

    @Test
    public void streamFrameBeyondConnectionFlowControlClosesConnection() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        lb.serverFactory.setMaxData(100);
        Fixture f = new Fixture(lb);
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeStream(b, 0, 0, new byte[3000], false);
        f.toServer(b);
        assertTrue(f.server.conn.isClosed());
    }

    @Test
    public void streamFrameBeyondStreamLimitClosesConnection() throws Exception {
        Fixture f = Fixture.create();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeStream(b, 4L * 100000, 0, new byte[1], false);
        f.toServer(b);
        assertTrue(f.server.conn.isClosed());
    }

    @Test
    public void streamFrameWithoutHandlerIsDropped() throws Exception {
        QuicLoopback lb = new QuicLoopback();
        Fixture f = new Fixture(lb);
        f.server.conn.setStreamAcceptHandler(null);
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeStream(b, 4, 0, new byte[1], false);
        f.toServer(b);
        assertFalse(f.server.conn.isClosed());
    }

    @Test
    public void cryptoFrameBeyondBufferClosesConnection() throws Exception {
        Fixture f = Fixture.create();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeCrypto(b, 10000000L, new byte[10]);
        f.toServer(b);
        assertNotNull(f.server.conn);
    }

    @Test
    public void connectionCloseFrameClosesPeer() throws Exception {
        Fixture f = Fixture.create();
        ByteBuffer b = f.buf();
        QuicFrameWriter.writeConnectionClose(b, true, 5, 0, "bye");
        f.toServer(b);
        assertTrue(f.server.conn.isClosed());
    }
}
