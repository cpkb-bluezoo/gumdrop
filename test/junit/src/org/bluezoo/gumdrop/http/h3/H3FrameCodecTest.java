/*
 * H3FrameCodecTest.java
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

package org.bluezoo.gumdrop.http.h3;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.quic.packet.VarInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Round-trips every frame type {@link H3Writer} can write through
 * {@link H3Parser}, and specifically exercises the incremental parsing
 * behaviour ({@link H3Parser}'s reason for existing over a simpler
 * whole-frame-at-a-time design): frames -- including a single varint --
 * split arbitrarily across {@link H3Parser#receive} calls, and DATA
 * payload delivered in chunks rather than buffered whole.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3FrameCodecTest {

    private static class RecordingHandler implements H3FrameHandler {

        final List<String> events = new ArrayList<String>();

        @Override
        public void dataFrameReceived(ByteBuffer data, boolean endOfFrame) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            events.add("data:" + new String(copy, StandardCharsets.US_ASCII) + ":" + endOfFrame);
        }

        @Override
        public void headersFrameReceived(ByteBuffer encodedFieldSection) {
            byte[] copy = new byte[encodedFieldSection.remaining()];
            encodedFieldSection.get(copy);
            events.add("headers:" + new String(copy, StandardCharsets.US_ASCII));
        }

        @Override
        public void cancelPushFrameReceived(long pushId) {
            events.add("cancel_push:" + pushId);
        }

        @Override
        public void settingsFrameReceived(long[] settings) {
            StringBuilder sb = new StringBuilder("settings:");
            for (int i = 0; i < settings.length; i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(settings[i]);
            }
            events.add(sb.toString());
        }

        @Override
        public void pushPromiseFrameReceived(long pushId, ByteBuffer encodedFieldSection) {
            byte[] copy = new byte[encodedFieldSection.remaining()];
            encodedFieldSection.get(copy);
            events.add("push_promise:" + pushId + ":" + new String(copy, StandardCharsets.US_ASCII));
        }

        @Override
        public void goawayFrameReceived(long streamOrPushId) {
            events.add("goaway:" + streamOrPushId);
        }

        @Override
        public void maxPushIdFrameReceived(long maxPushId) {
            events.add("max_push_id:" + maxPushId);
        }

        @Override
        public void priorityUpdateRequestFrameReceived(long streamId, String fieldValue) {
            events.add("priority_update_request:" + streamId + ":" + fieldValue);
        }

        @Override
        public void priorityUpdatePushFrameReceived(long pushId, String fieldValue) {
            events.add("priority_update_push:" + pushId + ":" + fieldValue);
        }

        @Override
        public void unknownFrameReceived(long frameType) {
            events.add("unknown:" + frameType);
        }

        @Override
        public void frameError(String message) {
            events.add("error:" + message);
        }
    }

    @Test
    public void testDataFrameWholeBuffer() {
        byte[] data = "hello world".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(H3Writer.dataLength(data.length));
        H3Writer.writeData(buf, data);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new H3Parser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("data:hello world:true", handler.events.get(0));
    }

    @Test
    public void testHeadersFrame() {
        byte[] encoded = "fake-qpack-bytes".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(H3Writer.headersLength(encoded.length));
        H3Writer.writeHeaders(buf, encoded);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new H3Parser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("headers:fake-qpack-bytes", handler.events.get(0));
    }

    @Test
    public void testCancelPushFrame() {
        ByteBuffer buf = ByteBuffer.allocate(H3Writer.cancelPushLength(42));
        H3Writer.writeCancelPush(buf, 42);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new H3Parser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("cancel_push:42", handler.events.get(0));
    }

    @Test
    public void testSettingsFrame() {
        long[] settings = { H3FrameHandler.SETTINGS_QPACK_MAX_TABLE_CAPACITY, 0,
                H3FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1 };
        ByteBuffer buf = ByteBuffer.allocate(H3Writer.settingsLength(settings));
        H3Writer.writeSettings(buf, settings);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new H3Parser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("settings:1,0,8,1", handler.events.get(0));
    }

    @Test
    public void testEmptySettingsFrame() {
        long[] settings = {};
        ByteBuffer buf = ByteBuffer.allocate(H3Writer.settingsLength(settings));
        H3Writer.writeSettings(buf, settings);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new H3Parser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("settings:", handler.events.get(0));
    }

    @Test
    public void testSettingsH3Datagram() {
        long[] settings = { H3FrameHandler.SETTINGS_H3_DATAGRAM, 1 };
        ByteBuffer buf = ByteBuffer.allocate(H3Writer.settingsLength(settings));
        H3Writer.writeSettings(buf, settings);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new H3Parser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("settings:51,1", handler.events.get(0));
    }

    @Test
    public void testFieldSectionSizeAccounting() {
        List<Header> fields = new ArrayList<Header>();
        fields.add(new Header(":method", "GET"));
        fields.add(new Header("x", "y"));
        // RFC 9114 section 4.2.2: name + value + 32 per field line.
        assertEquals((7 + 3 + 32) + (1 + 1 + 32), H3Writer.fieldSectionSize(fields));
    }

    @Test
    public void testPushPromiseFrame() {
        byte[] encoded = "fake-promised-request".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(H3Writer.pushPromiseLength(7, encoded.length));
        H3Writer.writePushPromise(buf, 7, encoded);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new H3Parser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("push_promise:7:fake-promised-request", handler.events.get(0));
    }

    @Test
    public void testGoawayFrame() {
        ByteBuffer buf = ByteBuffer.allocate(H3Writer.goawayLength(16));
        H3Writer.writeGoaway(buf, 16);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new H3Parser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("goaway:16", handler.events.get(0));
    }

    @Test
    public void testMaxPushIdFrame() {
        ByteBuffer buf = ByteBuffer.allocate(H3Writer.maxPushIdLength(100));
        H3Writer.writeMaxPushId(buf, 100);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new H3Parser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("max_push_id:100", handler.events.get(0));
    }

    @Test
    public void testPriorityUpdateRequestFrame() {
        byte[] value = "u=0, i".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(H3Writer.priorityUpdateRequestLength(0, value.length));
        H3Writer.writePriorityUpdateRequest(buf, 0, value);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new H3Parser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("priority_update_request:0:u=0, i", handler.events.get(0));
    }

    @Test
    public void testUnrecognisedFrameTypeIsSilentlyIgnored() {
        // RFC 9114 section 7.2.8: a generic reserved/unknown frame type
        // (0x21 = 0x1f*0 + 0x21) is delivered as unknown, not as an error.
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.put((byte) 0x21); // type
        buf.put((byte) 0x03); // length = 3
        buf.put((byte) 0xaa);
        buf.put((byte) 0xbb);
        buf.put((byte) 0xcc);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new H3Parser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("unknown:33", handler.events.get(0));
    }

    @Test
    public void testMultipleFramesInOneBuffer() {
        long[] settings = { H3FrameHandler.SETTINGS_QPACK_MAX_TABLE_CAPACITY, 0 };
        byte[] data = "body".getBytes(StandardCharsets.US_ASCII);
        int size = H3Writer.settingsLength(settings) + H3Writer.dataLength(data.length);
        ByteBuffer buf = ByteBuffer.allocate(size);
        H3Writer.writeSettings(buf, settings);
        H3Writer.writeData(buf, data);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new H3Parser(handler).receive(buf);

        assertEquals(2, handler.events.size());
        assertEquals("settings:1,0", handler.events.get(0));
        assertEquals("data:body:true", handler.events.get(1));
    }

    @Test
    public void testFrameSplitAcrossMultipleReceiveCallsByteAtATime() {
        // HEADERS (not DATA): DATA is delivered incrementally by design
        // (see testDataFrameDeliveredIncrementallyNotBuffered), so
        // feeding it one byte at a time would legitimately fire one
        // callback per byte. HEADERS is buffered until complete, so
        // this isolates the thing actually under test: that a varint
        // split across many single-byte receive() calls -- including
        // splitting the Type and Length varints themselves, which
        // QuicFrameParser never has to handle since QUIC frames are
        // always fully contained in one already-decrypted packet --
        // still assembles into exactly one correct dispatch.
        byte[] encoded = "fake-qpack-bytes".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer whole = ByteBuffer.allocate(H3Writer.headersLength(encoded.length));
        H3Writer.writeHeaders(whole, encoded);
        whole.flip();
        byte[] wholeBytes = new byte[whole.remaining()];
        whole.get(wholeBytes);

        RecordingHandler handler = new RecordingHandler();
        H3Parser parser = new H3Parser(handler);

        for (byte b : wholeBytes) {
            ByteBuffer single = ByteBuffer.wrap(new byte[] { b });
            parser.receive(single);
        }

        assertEquals(1, handler.events.size());
        assertEquals("headers:fake-qpack-bytes", handler.events.get(0));
    }

    @Test
    public void testDataFrameDeliveredIncrementallyNotBuffered() {
        byte[] part1 = "first-chunk-".getBytes(StandardCharsets.US_ASCII);
        byte[] part2 = "second-chunk".getBytes(StandardCharsets.US_ASCII);
        byte[] whole = new byte[part1.length + part2.length];
        System.arraycopy(part1, 0, whole, 0, part1.length);
        System.arraycopy(part2, 0, whole, part1.length, part2.length);

        // Write just the frame header (type + length), then deliver the
        // payload across two separate receive() calls to prove DATA
        // isn't buffered in full before the first callback.
        ByteBuffer header = ByteBuffer.allocate(16);
        VarInt.encode(H3FrameHandler.TYPE_DATA, header);
        VarInt.encode(whole.length, header);
        header.flip();

        RecordingHandler handler = new RecordingHandler();
        H3Parser parser = new H3Parser(handler);
        parser.receive(header);
        assertTrue("No callback yet: only the header has arrived", handler.events.isEmpty());

        parser.receive(ByteBuffer.wrap(part1));
        assertEquals(1, handler.events.size());
        assertEquals("data:first-chunk-:false", handler.events.get(0));

        parser.receive(ByteBuffer.wrap(part2));
        assertEquals(2, handler.events.size());
        assertEquals("data:second-chunk:true", handler.events.get(1));
    }
}
