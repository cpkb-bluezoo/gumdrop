/*
 * AMQPFrameParserTest.java
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

package org.bluezoo.gumdrop.amqp.client;

import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class AMQPFrameParserTest {

    private RecordingHandler handler;
    private AMQPFrameParser parser;

    @Before
    public void setUp() {
        handler = new RecordingHandler();
        parser = new AMQPFrameParser(handler);
    }

    private static byte[] bytes(ByteBuffer buf) {
        byte[] b = new byte[buf.remaining()];
        buf.get(b);
        return b;
    }

    @Test
    public void testSingleFrameDeliveredWhole() {
        ByteBuffer encoded = AMQPFrame.encode(AMQPFrame.TYPE_METHOD, 1,
                ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII)));

        parser.receive(encoded);

        assertEquals(1, handler.methodFrames.size());
        Recorded f = handler.methodFrames.get(0);
        assertEquals(1, f.channel);
        assertEquals("hello", new String(f.payload, StandardCharsets.US_ASCII));
        assertFalse(encoded.hasRemaining());
    }

    @Test
    public void testHeartbeatFrame() {
        parser.receive(AMQPFrame.encodeHeartbeat());
        assertEquals(1, handler.heartbeats);
    }

    @Test
    public void testHeaderAndBodyFrames() {
        ByteBuffer header = AMQPFrame.encode(AMQPFrame.TYPE_HEADER, 2,
                ByteBuffer.wrap(new byte[] { 1, 2, 3 }));
        ByteBuffer body = AMQPFrame.encode(AMQPFrame.TYPE_BODY, 2,
                ByteBuffer.wrap("payload data".getBytes(StandardCharsets.US_ASCII)));

        parser.receive(header);
        parser.receive(body);

        assertEquals(1, handler.headerFrames.size());
        assertArrayEquals(new byte[] { 1, 2, 3 }, handler.headerFrames.get(0).payload);
        assertEquals(1, handler.bodyFrames.size());
        assertEquals("payload data",
                new String(handler.bodyFrames.get(0).payload, StandardCharsets.US_ASCII));
    }

    // The core requirement: bytes arrive incrementally and no call to
    // receive() may assume it was handed a complete frame. Feed
    // progressively longer prefixes of the same encoded frame (as a
    // caller's accumulation buffer would grow across reads) — nothing
    // must fire until the very last, complete prefix, since receive()
    // leaves an incomplete frame's bytes untouched each time.
    @Test
    public void testFrameSplitAcrossManyReceiveCalls() {
        byte[] all = bytes(AMQPFrame.encode(AMQPFrame.TYPE_METHOD, 5,
                ByteBuffer.wrap("split across reads".getBytes(StandardCharsets.US_ASCII))));

        for (int len = 1; len < all.length; len++) {
            parser.receive(ByteBuffer.wrap(all, 0, len));
            assertTrue("frame must not fire before all " + all.length + " bytes have arrived (at "
                    + len + ")", handler.methodFrames.isEmpty());
        }

        parser.receive(ByteBuffer.wrap(all, 0, all.length));

        assertEquals(1, handler.methodFrames.size());
        assertEquals("split across reads",
                new String(handler.methodFrames.get(0).payload, StandardCharsets.US_ASCII));
    }

    @Test
    public void testIncompleteHeaderLeavesPositionUnchanged() {
        ByteBuffer buf = ByteBuffer.wrap(new byte[] { 1, 0 }); // only 2 of 7 header bytes
        int posBefore = buf.position();

        parser.receive(buf);

        assertTrue(handler.methodFrames.isEmpty());
        assertTrue(handler.errors.isEmpty());
        assertEquals("must not consume an incomplete header", posBefore, buf.position());
    }

    @Test
    public void testIncompletePayloadLeavesPositionUnchanged() {
        ByteBuffer full = AMQPFrame.encode(AMQPFrame.TYPE_METHOD, 0,
                ByteBuffer.wrap("abcdefgh".getBytes(StandardCharsets.US_ASCII)));
        ByteBuffer partial = full.duplicate();
        partial.limit(AMQPFrame.HEADER_SIZE + 3); // header complete, payload is not

        int posBefore = partial.position();
        parser.receive(partial);

        assertTrue(handler.methodFrames.isEmpty());
        assertEquals(posBefore, partial.position());
    }

    @Test
    public void testMultipleFramesInOneBufferAllDelivered() {
        ByteBuffer f1 = AMQPFrame.encode(AMQPFrame.TYPE_METHOD, 1,
                ByteBuffer.wrap("first".getBytes(StandardCharsets.US_ASCII)));
        ByteBuffer f2 = AMQPFrame.encode(AMQPFrame.TYPE_BODY, 1,
                ByteBuffer.wrap("second".getBytes(StandardCharsets.US_ASCII)));

        ByteBuffer combined = ByteBuffer.allocate(f1.remaining() + f2.remaining());
        combined.put(f1);
        combined.put(f2);
        combined.flip();

        parser.receive(combined);

        assertEquals(1, handler.methodFrames.size());
        assertEquals(1, handler.bodyFrames.size());
        assertFalse(combined.hasRemaining());
    }

    @Test
    public void testTrailingPartialFrameNotConsumedAfterCompleteOnesAreDelivered() {
        ByteBuffer complete = AMQPFrame.encode(AMQPFrame.TYPE_METHOD, 1,
                ByteBuffer.wrap("complete".getBytes(StandardCharsets.US_ASCII)));
        byte[] partialTail = { 1, 0, 0 }; // start of another frame's header, incomplete

        ByteBuffer combined = ByteBuffer.allocate(complete.remaining() + partialTail.length);
        combined.put(complete);
        combined.put(partialTail);
        combined.flip();

        int expectedRemainingAfter = partialTail.length;
        parser.receive(combined);

        assertEquals(1, handler.methodFrames.size());
        assertEquals("partial trailing frame must be left for the next receive()",
                expectedRemainingAfter, combined.remaining());
    }

    @Test
    public void testBadFrameEndReportsError() {
        ByteBuffer buf = ByteBuffer.allocate(AMQPFrame.OVERHEAD);
        buf.put((byte) AMQPFrame.TYPE_METHOD);
        buf.putShort((short) 0);
        buf.putInt(0);
        buf.put((byte) 0x00); // wrong frame-end, should be 0xCE
        buf.flip();

        parser.receive(buf);

        assertEquals(1, handler.errors.size());
        assertTrue(handler.methodFrames.isEmpty());
    }

    @Test
    public void testOversizedFrameReportsError() {
        parser.setMaxFrameSize(100);
        ByteBuffer buf = ByteBuffer.allocate(AMQPFrame.HEADER_SIZE);
        buf.put((byte) AMQPFrame.TYPE_METHOD);
        buf.putShort((short) 0);
        buf.putInt(1000); // declared size exceeds maxFrameSize
        buf.flip();

        parser.receive(buf);

        assertEquals(1, handler.errors.size());
    }

    @Test
    public void testUnknownFrameTypeReportsError() {
        ByteBuffer buf = AMQPFrame.encode(99, 0, ByteBuffer.wrap(new byte[0]));
        parser.receive(buf);
        assertEquals(1, handler.errors.size());
    }

    private static final class Recorded {
        final int channel;
        final byte[] payload;

        Recorded(int channel, byte[] payload) {
            this.channel = channel;
            this.payload = payload;
        }
    }

    private static final class RecordingHandler implements AMQPFrameHandler {
        final List<Recorded> methodFrames = new ArrayList<Recorded>();
        final List<Recorded> headerFrames = new ArrayList<Recorded>();
        final List<Recorded> bodyFrames = new ArrayList<Recorded>();
        final List<String> errors = new ArrayList<String>();
        int heartbeats;

        @Override
        public void methodFrame(int channel, ByteBuffer payload) {
            methodFrames.add(new Recorded(channel, bytes(payload)));
        }

        @Override
        public void headerFrame(int channel, ByteBuffer payload) {
            headerFrames.add(new Recorded(channel, bytes(payload)));
        }

        @Override
        public void bodyFrame(int channel, ByteBuffer payload) {
            bodyFrames.add(new Recorded(channel, bytes(payload)));
        }

        @Override
        public void heartbeatFrame() {
            heartbeats++;
        }

        @Override
        public void frameError(String message) {
            errors.add(message);
        }
    }
}
