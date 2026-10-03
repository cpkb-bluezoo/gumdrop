/*
 * H2ParserErrorPathTest.java
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

package org.bluezoo.gumdrop.http.h2;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Drives every validation arm of {@link H2Parser}: stream-ID rules per frame
 * type, fixed-size checks, padding overflow, SETTINGS value validation,
 * CONTINUATION sequencing and PRIORITY_UPDATE decoding, using hand-built
 * frames and the recording handler from {@link H2ParserWriterTest}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H2ParserErrorPathTest {

    private static final int PROTOCOL = H2FrameHandler.ERROR_PROTOCOL_ERROR;
    private static final int FRAME_SIZE = H2FrameHandler.ERROR_FRAME_SIZE_ERROR;

    private H2ParserWriterTest.RecordingHandler handler;
    private H2Parser parser;

    @Before
    public void setUp() {
        handler = new H2ParserWriterTest.RecordingHandler();
        parser = new H2Parser(handler);
    }

    private static byte[] frame(int type, int flags, int streamId, int... payload) {
        byte[] out = new byte[9 + payload.length];
        out[0] = (byte) (payload.length >> 16);
        out[1] = (byte) (payload.length >> 8);
        out[2] = (byte) payload.length;
        out[3] = (byte) type;
        out[4] = (byte) flags;
        out[5] = (byte) (streamId >> 24);
        out[6] = (byte) (streamId >> 16);
        out[7] = (byte) (streamId >> 8);
        out[8] = (byte) streamId;
        for (int i = 0; i < payload.length; i++) {
            out[9 + i] = (byte) payload[i];
        }
        return out;
    }

    private void feed(byte[]... frames) {
        int total = 0;
        for (int i = 0; i < frames.length; i++) {
            total += frames[i].length;
        }
        ByteBuffer buf = ByteBuffer.allocate(total);
        for (int i = 0; i < frames.length; i++) {
            buf.put(frames[i]);
        }
        buf.flip();
        parser.receive(buf);
    }

    private void assertError(int code, int streamId, String messagePart) {
        assertEquals(1, handler.errors.size());
        H2ParserWriterTest.RecordingHandler.ErrorRecord e = handler.errors.get(0);
        assertEquals(code, e.errorCode);
        assertEquals(streamId, e.streamId);
        assertTrue(e.message, e.message.contains(messagePart));
        assertEquals(0, handler.totalFrames());
    }

    @Test
    public void testOversizedFrameIsFrameSizeError() {
        parser.setMaxFrameSize(16384);
        byte[] header = new byte[9];
        header[0] = 0x00;
        header[1] = 0x40;
        header[2] = 0x01;
        header[3] = H2FrameHandler.TYPE_DATA;
        header[8] = 1;
        parser.receive(ByteBuffer.wrap(header));
        assertError(FRAME_SIZE, 1, "exceeds maximum");
    }

    @Test
    public void testUnknownFrameTypeIsIgnored() {
        feed(frame(0xee, 0, 1, 1, 2, 3), frame(H2FrameHandler.TYPE_PING, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7));
        assertEquals(0, handler.errors.size());
        assertEquals(1, handler.pings.size());
        assertEquals(7L, handler.pings.get(0).opaqueData);
    }

    @Test
    public void testDataFrameRules() {
        feed(frame(H2FrameHandler.TYPE_DATA, 0, 0, 1));
        assertError(PROTOCOL, 0, "DATA frame with stream ID 0");
    }

    @Test
    public void testPaddedDataPaddingExceedsPayload() {
        feed(frame(H2FrameHandler.TYPE_DATA, H2FrameHandler.FLAG_PADDED, 1, 9, 1, 2));
        assertError(PROTOCOL, 1, "padding exceeds");
    }

    @Test
    public void testPaddedDataStripsPadding() {
        feed(frame(H2FrameHandler.TYPE_DATA, H2FrameHandler.FLAG_PADDED | H2FrameHandler.FLAG_END_STREAM,
                1, 2, 'a', 'b', 0, 0));
        assertEquals(1, handler.dataFrames.size());
        assertEquals(2, handler.dataFrames.get(0).data.remaining());
        assertTrue(handler.dataFrames.get(0).endStream);
    }

    @Test
    public void testHeadersFrameRules() {
        feed(frame(H2FrameHandler.TYPE_HEADERS, H2FrameHandler.FLAG_END_HEADERS, 0, 1));
        assertError(PROTOCOL, 0, "HEADERS frame with stream ID 0");
    }

    @Test
    public void testPaddedHeadersPaddingExceedsPayload() {
        feed(frame(H2FrameHandler.TYPE_HEADERS, H2FrameHandler.FLAG_PADDED | H2FrameHandler.FLAG_END_HEADERS,
                1, 9, 1));
        assertError(PROTOCOL, 1, "padding exceeds");
    }

    @Test
    public void testHeadersWithPriorityAndPadding() {
        int flags = H2FrameHandler.FLAG_PADDED | H2FrameHandler.FLAG_PRIORITY | H2FrameHandler.FLAG_END_HEADERS;
        feed(frame(H2FrameHandler.TYPE_HEADERS, flags, 3,
                1, 0x80, 0, 0, 5, 9, 'x', 'y', 0));
        assertEquals(1, handler.headersFrames.size());
        H2ParserWriterTest.RecordingHandler.HeadersFrame h = handler.headersFrames.get(0);
        assertTrue(h.exclusive);
        assertEquals(5, h.streamDependency);
        assertEquals(10, h.weight);
        assertEquals(2, h.headerBlockFragment.remaining());
    }

    @Test
    public void testPriorityFrameRules() {
        feed(frame(H2FrameHandler.TYPE_PRIORITY, 0, 0, 0, 0, 0, 1, 1));
        assertError(PROTOCOL, 0, "PRIORITY frame with stream ID 0");
    }

    @Test
    public void testPriorityFrameWrongLength() {
        feed(frame(H2FrameHandler.TYPE_PRIORITY, 0, 1, 0, 0, 0, 1));
        assertError(FRAME_SIZE, 1, "5 bytes");
    }

    @Test
    public void testRstStreamRules() {
        feed(frame(H2FrameHandler.TYPE_RST_STREAM, 0, 0, 0, 0, 0, 8));
        assertError(PROTOCOL, 0, "RST_STREAM frame with stream ID 0");
    }

    @Test
    public void testRstStreamWrongLength() {
        feed(frame(H2FrameHandler.TYPE_RST_STREAM, 0, 1, 0, 0, 8));
        assertError(FRAME_SIZE, 1, "4 bytes");
    }

    @Test
    public void testSettingsOnStream() {
        feed(frame(H2FrameHandler.TYPE_SETTINGS, 0, 1));
        assertError(PROTOCOL, 1, "non-zero stream ID");
    }

    @Test
    public void testSettingsAckWithPayload() {
        feed(frame(H2FrameHandler.TYPE_SETTINGS, H2FrameHandler.FLAG_ACK, 0, 0, 3, 0, 0, 0, 1));
        assertError(FRAME_SIZE, 0, "ACK frame must be empty");
    }

    @Test
    public void testSettingsLengthNotMultipleOfSix() {
        feed(frame(H2FrameHandler.TYPE_SETTINGS, 0, 0, 0, 3, 0, 0));
        assertError(FRAME_SIZE, 0, "multiple of 6");
    }

    @Test
    public void testSettingsEnablePushInvalid() {
        feed(frame(H2FrameHandler.TYPE_SETTINGS, 0, 0, 0, 2, 0, 0, 0, 2));
        assertError(PROTOCOL, 0, "ENABLE_PUSH");
    }

    @Test
    public void testSettingsEnablePushValidZeroAndOne() {
        feed(frame(H2FrameHandler.TYPE_SETTINGS, 0, 0, 0, 2, 0, 0, 0, 0, 0, 2, 0, 0, 0, 1));
        assertEquals(1, handler.settings.size());
        assertEquals(Integer.valueOf(1), handler.settings.get(0).settings.get(2));
    }

    @Test
    public void testSettingsMaxFrameSizeTooSmallAndTooLarge() {
        feed(frame(H2FrameHandler.TYPE_SETTINGS, 0, 0, 0, 5, 0, 0, 0, 1));
        assertError(PROTOCOL, 0, "MAX_FRAME_SIZE");
        handler.errors.clear();
        feed(frame(H2FrameHandler.TYPE_SETTINGS, 0, 0, 0, 5, 0x01, 0, 0, 0));
        assertError(PROTOCOL, 0, "MAX_FRAME_SIZE");
    }

    @Test
    public void testSettingsMaxFrameSizeValid() {
        feed(frame(H2FrameHandler.TYPE_SETTINGS, 0, 0, 0, 5, 0, 0, 0x40, 0));
        assertEquals(Integer.valueOf(16384), handler.settings.get(0).settings.get(5));
    }

    @Test
    public void testSettingsInitialWindowSizeTooLarge() {
        feed(frame(H2FrameHandler.TYPE_SETTINGS, 0, 0, 0, 4, 0x80, 0, 0, 0));
        assertError(H2FrameHandler.ERROR_FLOW_CONTROL_ERROR, 0, "INITIAL_WINDOW_SIZE");
    }

    @Test
    public void testSettingsInitialWindowSizeValidAndUnknownId() {
        feed(frame(H2FrameHandler.TYPE_SETTINGS, 0, 0, 0, 4, 0x7f, 0xff, 0xff, 0xff, 0x7f, 0x01, 0, 0, 0, 9));
        assertEquals(Integer.valueOf(0x7fffffff), handler.settings.get(0).settings.get(4));
        assertEquals(Integer.valueOf(9), handler.settings.get(0).settings.get(0x7f01));
    }

    @Test
    public void testPushPromiseRules() {
        feed(frame(H2FrameHandler.TYPE_PUSH_PROMISE, H2FrameHandler.FLAG_END_HEADERS, 0, 0, 0, 0, 2));
        assertError(PROTOCOL, 0, "PUSH_PROMISE frame with stream ID 0");
    }

    @Test
    public void testPushPromisePaddingExceedsPayload() {
        feed(frame(H2FrameHandler.TYPE_PUSH_PROMISE, H2FrameHandler.FLAG_PADDED | H2FrameHandler.FLAG_END_HEADERS,
                1, 9, 0, 0, 0, 2));
        assertError(PROTOCOL, 1, "padding exceeds");
    }

    @Test
    public void testPaddedPushPromiseDecodes() {
        feed(frame(H2FrameHandler.TYPE_PUSH_PROMISE, H2FrameHandler.FLAG_PADDED | H2FrameHandler.FLAG_END_HEADERS,
                1, 1, 0, 0, 0, 2, 'h', 0));
        assertEquals(1, handler.pushPromises.size());
        assertEquals(2, handler.pushPromises.get(0).promisedStreamId);
        assertEquals(1, handler.pushPromises.get(0).headerBlockFragment.remaining());
    }

    @Test
    public void testPingRules() {
        feed(frame(H2FrameHandler.TYPE_PING, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0));
        assertError(PROTOCOL, 1, "non-zero stream ID");
        handler.errors.clear();
        feed(frame(H2FrameHandler.TYPE_PING, 0, 0, 0, 0, 0, 0));
        assertError(FRAME_SIZE, 0, "8 bytes");
    }

    @Test
    public void testGoawayRules() {
        feed(frame(H2FrameHandler.TYPE_GOAWAY, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0));
        assertError(PROTOCOL, 1, "non-zero stream ID");
        handler.errors.clear();
        feed(frame(H2FrameHandler.TYPE_GOAWAY, 0, 0, 0, 0, 0, 0));
        assertError(FRAME_SIZE, 0, "at least 8 bytes");
    }

    @Test
    public void testWindowUpdateRules() {
        feed(frame(H2FrameHandler.TYPE_WINDOW_UPDATE, 0, 1, 0, 0, 1));
        assertError(FRAME_SIZE, 1, "4 bytes");
        handler.errors.clear();
        feed(frame(H2FrameHandler.TYPE_WINDOW_UPDATE, 0, 1, 0, 0, 0, 0));
        assertError(PROTOCOL, 1, "non-zero");
    }

    @Test
    public void testContinuationChain() {
        feed(frame(H2FrameHandler.TYPE_HEADERS, 0, 1, 'a'),
                frame(H2FrameHandler.TYPE_CONTINUATION, 0, 1, 'b'),
                frame(H2FrameHandler.TYPE_CONTINUATION, H2FrameHandler.FLAG_END_HEADERS, 1, 'c'),
                frame(H2FrameHandler.TYPE_PING, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1));
        assertEquals(0, handler.errors.size());
        assertEquals(1, handler.headersFrames.size());
        assertEquals(2, handler.continuations.size());
        assertEquals(1, handler.pings.size());
    }

    @Test
    public void testPushPromiseWithoutEndHeadersLocksToContinuation() {
        feed(frame(H2FrameHandler.TYPE_PUSH_PROMISE, 0, 1, 0, 0, 0, 2, 'a'),
                frame(H2FrameHandler.TYPE_DATA, 0, 1, 'x'));
        assertEquals(1, handler.pushPromises.size());
        assertEquals(1, handler.errors.size());
        assertEquals(PROTOCOL, handler.errors.get(0).errorCode);
        assertTrue(handler.errors.get(0).message.contains("CONTINUATION"));
    }

    @Test
    public void testNonContinuationFrameDuringHeaderBlock() {
        feed(frame(H2FrameHandler.TYPE_HEADERS, 0, 1, 'a'),
                frame(H2FrameHandler.TYPE_PING, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1));
        assertEquals(1, handler.errors.size());
        assertTrue(handler.errors.get(0).message.contains("Expected CONTINUATION for stream 1"));
        assertTrue(handler.errors.get(0).message.contains("PING"));
    }

    @Test
    public void testContinuationOnWrongStreamDuringHeaderBlock() {
        feed(frame(H2FrameHandler.TYPE_HEADERS, 0, 1, 'a'),
                frame(H2FrameHandler.TYPE_CONTINUATION, H2FrameHandler.FLAG_END_HEADERS, 3, 'b'));
        assertEquals(1, handler.errors.size());
        assertTrue(handler.errors.get(0).message.contains("on stream 3"));
    }

    @Test
    public void testContinuationOnStreamZero() {
        feed(frame(H2FrameHandler.TYPE_CONTINUATION, H2FrameHandler.FLAG_END_HEADERS, 0, 'a'));
        assertError(PROTOCOL, 0, "CONTINUATION frame with stream ID 0");
    }

    @Test
    public void testUnexpectedContinuation() {
        feed(frame(H2FrameHandler.TYPE_CONTINUATION, H2FrameHandler.FLAG_END_HEADERS, 1, 'a'));
        assertError(PROTOCOL, 1, "Unexpected CONTINUATION");
    }

    @Test
    public void testPriorityUpdateRules() {
        feed(frame(H2FrameHandler.TYPE_PRIORITY_UPDATE, 0, 1, 0, 0, 0, 1, 'u'));
        assertError(PROTOCOL, 1, "stream 0");
        handler.errors.clear();
        feed(frame(H2FrameHandler.TYPE_PRIORITY_UPDATE, 0, 0, 0, 0, 1));
        assertError(FRAME_SIZE, 0, "at least 4 bytes");
    }

    @Test
    public void testPriorityUpdateInvalidUtf8() {
        feed(frame(H2FrameHandler.TYPE_PRIORITY_UPDATE, 0, 0, 0, 0, 0, 1, 0xff, 0xfe));
        assertError(PROTOCOL, 0, "UTF-8");
    }

    @Test
    public void testPriorityUpdateDecodes() {
        byte[] value = "u=1".getBytes(StandardCharsets.UTF_8);
        int[] payload = new int[4 + value.length];
        payload[3] = 5;
        for (int i = 0; i < value.length; i++) {
            payload[4 + i] = value[i];
        }
        feed(frame(H2FrameHandler.TYPE_PRIORITY_UPDATE, 0, 0, payload));
        assertEquals(1, handler.priorityUpdates.size());
        assertEquals(5, handler.priorityUpdates.get(0).prioritizedStreamId);
        assertEquals("u=1", handler.priorityUpdates.get(0).fieldValue);
    }

    @Test
    public void testTypeNames() {
        assertEquals("DATA", H2FrameHandler.typeToString(H2FrameHandler.TYPE_DATA));
        assertEquals("HEADERS", H2FrameHandler.typeToString(H2FrameHandler.TYPE_HEADERS));
        assertEquals("PRIORITY", H2FrameHandler.typeToString(H2FrameHandler.TYPE_PRIORITY));
        assertEquals("RST_STREAM", H2FrameHandler.typeToString(H2FrameHandler.TYPE_RST_STREAM));
        assertEquals("SETTINGS", H2FrameHandler.typeToString(H2FrameHandler.TYPE_SETTINGS));
        assertEquals("PUSH_PROMISE", H2FrameHandler.typeToString(H2FrameHandler.TYPE_PUSH_PROMISE));
        assertEquals("PING", H2FrameHandler.typeToString(H2FrameHandler.TYPE_PING));
        assertEquals("GOAWAY", H2FrameHandler.typeToString(H2FrameHandler.TYPE_GOAWAY));
        assertEquals("WINDOW_UPDATE", H2FrameHandler.typeToString(H2FrameHandler.TYPE_WINDOW_UPDATE));
        assertEquals("CONTINUATION", H2FrameHandler.typeToString(H2FrameHandler.TYPE_CONTINUATION));
        assertEquals("PRIORITY_UPDATE", H2FrameHandler.typeToString(H2FrameHandler.TYPE_PRIORITY_UPDATE));
        assertEquals("UNKNOWN(99)", H2FrameHandler.typeToString(99));
    }
}
