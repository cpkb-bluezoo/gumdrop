/*
 * QuicFrameCodecTest.java
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

package org.bluezoo.gumdrop.quic.frame;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Round-trips every frame type {@link QuicFrameWriter} can write through
 * {@link QuicFrameParser}, and checks that consecutive frames in one
 * payload are all correctly boundaried.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicFrameCodecTest {

    private static class RecordingHandler implements QuicFrameHandler {

        final List<String> events = new ArrayList<String>();

        @Override
        public void paddingFrameReceived(int length) {
            events.add("padding:" + length);
        }

        @Override
        public void pingFrameReceived() {
            events.add("ping");
        }

        @Override
        public void ackFrameReceived(long largestAcknowledged, long ackDelay, long firstAckRange) {
            events.add("ack:" + largestAcknowledged + ":" + ackDelay + ":" + firstAckRange);
        }

        @Override
        public void cryptoFrameReceived(long offset, ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            events.add("crypto:" + offset + ":" + new String(copy, StandardCharsets.US_ASCII));
        }

        @Override
        public void connectionCloseFrameReceived(boolean applicationError, long errorCode,
                long frameType, String reason) {
            events.add("close:" + applicationError + ":" + errorCode + ":" + frameType + ":" + reason);
        }

        @Override
        public void handshakeDoneFrameReceived() {
            events.add("handshake_done");
        }

        @Override
        public void frameError(String message) {
            events.add("error:" + message);
        }
    }

    @Test
    public void testPaddingFrameCoalesced() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.paddingLength(5));
        QuicFrameWriter.writePadding(buf, 5);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("padding:5", handler.events.get(0));
    }

    @Test
    public void testPingFrame() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.pingLength());
        QuicFrameWriter.writePing(buf);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("ping", handler.events.get(0));
    }

    @Test
    public void testAckFrame() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.ackLength(42, 100, 5));
        QuicFrameWriter.writeAck(buf, 42, 100, 5);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("ack:42:100:5", handler.events.get(0));
    }

    @Test
    public void testCryptoFrame() {
        byte[] data = "client hello bytes".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.cryptoLength(7, data.length));
        QuicFrameWriter.writeCrypto(buf, 7, data);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("crypto:7:client hello bytes", handler.events.get(0));
    }

    @Test
    public void testConnectionCloseTransport() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.connectionCloseLength(false, "bad frame"));
        QuicFrameWriter.writeConnectionClose(buf, false, 7, 6, "bad frame");
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("close:false:7:6:bad frame", handler.events.get(0));
    }

    @Test
    public void testConnectionCloseApplication() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.connectionCloseLength(true, "goodbye"));
        QuicFrameWriter.writeConnectionClose(buf, true, 0, 0, "goodbye");
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("close:true:0:0:goodbye", handler.events.get(0));
    }

    @Test
    public void testHandshakeDoneFrame() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.handshakeDoneLength());
        QuicFrameWriter.writeHandshakeDone(buf);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("handshake_done", handler.events.get(0));
    }

    @Test
    public void testMultipleFramesInOnePayload() {
        byte[] data = "hi".getBytes(StandardCharsets.US_ASCII);
        int size = QuicFrameWriter.pingLength()
                + QuicFrameWriter.cryptoLength(0, data.length)
                + QuicFrameWriter.handshakeDoneLength()
                + QuicFrameWriter.paddingLength(3);
        ByteBuffer buf = ByteBuffer.allocate(size);
        QuicFrameWriter.writePing(buf);
        QuicFrameWriter.writeCrypto(buf, 0, data);
        QuicFrameWriter.writeHandshakeDone(buf);
        QuicFrameWriter.writePadding(buf, 3);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(4, handler.events.size());
        assertEquals("ping", handler.events.get(0));
        assertEquals("crypto:0:hi", handler.events.get(1));
        assertEquals("handshake_done", handler.events.get(2));
        assertEquals("padding:3", handler.events.get(3));
    }

    @Test
    public void testUnknownFrameTypeReportsError() {
        ByteBuffer buf = ByteBuffer.allocate(1);
        buf.put((byte) 0x1f); // STREAMS_BLOCKED (bidi) -- not implemented
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertTrue(handler.events.get(0).startsWith("error:"));
    }
}
