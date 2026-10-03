/*
 * H2WriterEdgeTest.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Edge cases of {@link H2Writer} and {@link H2FlowControl}: padded and
 * prioritised frames, buffer growth and threshold flushing through a mock
 * channel that accepts one byte per write, stream-ID validation and the
 * pause/resume bookkeeping of the flow controller.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H2WriterEdgeTest {

    /** Mock channel that accepts at most one byte per write call. */
    private static class TrickleChannel implements WritableByteChannel {

        final java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        int writes;

        public int write(ByteBuffer src) {
            writes++;
            if (!src.hasRemaining()) {
                return 0;
            }
            sink.write(src.get());
            return 1;
        }

        public boolean isOpen() {
            return true;
        }

        public void close() {
        }
    }

    private static ByteBuffer bytes(String s) {
        return ByteBuffer.wrap(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static H2ParserWriterTest.RecordingHandler parse(TrickleChannel ch) {
        H2ParserWriterTest.RecordingHandler handler = new H2ParserWriterTest.RecordingHandler();
        H2Parser parser = new H2Parser(handler);
        parser.receive(ByteBuffer.wrap(ch.sink.toByteArray()));
        return handler;
    }

    @Test
    public void testPaddedDataRoundTrip() throws IOException {
        TrickleChannel ch = new TrickleChannel();
        H2Writer w = new H2Writer(ch);
        w.writeData(1, bytes("hello"), true, 4);
        w.flush();
        H2ParserWriterTest.RecordingHandler h = parse(ch);
        assertEquals(0, h.errors.size());
        assertEquals(1, h.dataFrames.size());
        assertEquals(5, h.dataFrames.get(0).data.remaining());
        assertTrue(h.dataFrames.get(0).endStream);
    }

    @Test
    public void testPaddedPrioritisedHeadersRoundTrip() throws IOException {
        TrickleChannel ch = new TrickleChannel();
        H2Writer w = new H2Writer(ch);
        w.writeHeaders(3, bytes("block"), false, true, 3, 7, 32, true);
        w.writeHeaders(5, bytes("plain"), true, false);
        w.writeContinuation(5, bytes("more"), true);
        w.flush();
        H2ParserWriterTest.RecordingHandler h = parse(ch);
        assertEquals(0, h.errors.size());
        assertEquals(2, h.headersFrames.size());
        H2ParserWriterTest.RecordingHandler.HeadersFrame first = h.headersFrames.get(0);
        assertTrue(first.exclusive);
        assertEquals(7, first.streamDependency);
        assertEquals(32, first.weight);
        assertEquals(5, first.headerBlockFragment.remaining());
        assertEquals(1, h.continuations.size());
        assertTrue(h.continuations.get(0).endHeaders);
    }

    @Test
    public void testPriorityHeadersOverloadRoundTrip() throws IOException {
        TrickleChannel ch = new TrickleChannel();
        H2Writer w = new H2Writer(ch);
        w.writeHeaders(3, bytes("b"), true, true, 9, 16, false);
        w.flush();
        H2ParserWriterTest.RecordingHandler h = parse(ch);
        assertEquals(1, h.headersFrames.size());
        assertEquals(9, h.headersFrames.get(0).streamDependency);
        assertEquals(16, h.headersFrames.get(0).weight);
    }

    @Test
    public void testSmallBufferGrowsAndFlushesThroughTrickleChannel() throws IOException {
        TrickleChannel ch = new TrickleChannel();
        H2Writer w = new H2Writer(ch, 16);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            big.append('z');
        }
        w.writeData(1, bytes(big.toString()), false);
        w.writePing(1L, false);
        w.writeGoaway(3, 0, bytes("bye"));
        w.writeGoaway(3, 0);
        w.writePushPromise(1, 2, bytes("pp"), true);
        w.writeRstStream(1, 8);
        w.writePriority(1, 0, 16, false);
        w.close();
        assertTrue(ch.writes > 1);
        H2ParserWriterTest.RecordingHandler h = parse(ch);
        assertEquals(0, h.errors.size());
        assertEquals(100, h.dataFrames.get(0).data.remaining());
        assertEquals(2, h.goaways.size());
        assertEquals(1, h.pushPromises.size());
        assertEquals(1, h.rstStreams.size());
        assertEquals(1, h.priorities.size());
    }

    @Test
    public void testFlushWithNothingBufferedWritesNothing() throws IOException {
        TrickleChannel ch = new TrickleChannel();
        H2Writer w = new H2Writer(ch);
        w.flush();
        assertEquals(0, ch.writes);
    }

    @Test
    public void testStreamIdZeroRejectedForStreamFrames() throws IOException {
        H2Writer w = new H2Writer(new TrickleChannel());
        try {
            w.writePriority(0, 1, 16, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null);
        }
        try {
            w.writePushPromise(0, 2, bytes("x"), true);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null);
        }
        try {
            w.writeContinuation(0, bytes("x"), true);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    @Test
    public void testWindowUpdateIncrementTooLargeRejected() throws IOException {
        H2Writer w = new H2Writer(new TrickleChannel());
        try {
            w.writeWindowUpdate(1, -5);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("-5"));
        }
    }

    @Test
    public void testSendBlockedOnlyForExhaustedWindows() {
        H2FlowControl fc = new H2FlowControl();
        fc.openStream(1);
        assertTrue(fc.availableSendWindow(1) > 0);
        assertEquals(false, fc.isSendBlocked(1));
    }

    @Test
    public void testForEachUnblockedStreamSkipsBlockedAndConnectionBlocked() {
        H2FlowControl fc = new H2FlowControl();
        fc.openStream(1);
        fc.openStream(3);
        fc.consumeSendWindow(3, 65535);
        fc.onWindowUpdate(0, 65535);
        final List<Integer> seen = new ArrayList<Integer>();
        H2FlowControl.UnblockedStreamCallback cb = new H2FlowControl.UnblockedStreamCallback() {
            public void onUnblocked(int streamId) {
                seen.add(Integer.valueOf(streamId));
            }
        };
        fc.forEachUnblockedStream(cb);
        assertEquals(1, seen.size());
        assertEquals(Integer.valueOf(1), seen.get(0));
        seen.clear();
        fc.consumeSendWindow(1, 65535);
        fc.forEachUnblockedStream(cb);
        assertEquals(0, seen.size());
    }

    @Test
    public void testResumeFlushesUnconsumedBytesBelowThreshold() {
        H2FlowControl fc = new H2FlowControl();
        H2FlowControl.DataReceivedResult result = new H2FlowControl.DataReceivedResult();
        fc.openStream(1);
        fc.onDataReceived(1, 100, result);
        assertEquals(0, result.streamIncrement);
        fc.pauseStream(1);
        assertTrue(fc.isStreamPaused(1));
        assertEquals(100, fc.resumeStream(1));
        assertEquals(false, fc.isStreamPaused(1));
        assertEquals(0, fc.resumeStream(1));
    }

    @Test
    public void testResumeReturnsWithheldIncrementPlusConsumed() {
        H2FlowControl fc = new H2FlowControl();
        H2FlowControl.DataReceivedResult result = new H2FlowControl.DataReceivedResult();
        fc.openStream(1);
        fc.pauseStream(1);
        fc.onDataReceived(1, 40000, result);
        assertEquals(0, result.streamIncrement);
        assertEquals(40000, fc.resumeStream(1));
    }
}
