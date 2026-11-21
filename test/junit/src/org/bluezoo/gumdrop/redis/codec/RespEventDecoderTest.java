/*
 * RespEventDecoderTest.java
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

package org.bluezoo.gumdrop.redis.codec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests of {@link RespDecoder} as an event source: that every RESP2 and RESP3
 * type produces the same events at every split of the input, compared with
 * the events a generator says the wire it wrote holds, and that flow control
 * steers the decoder.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RespEventDecoderTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Wire and expected events written together
    // ─────────────────────────────────────────────────────────────────────────

    /** Writes RESP and, beside it, the events it must produce. */
    private static final class Writer {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        final List<String> events = new ArrayList<String>();
        final Random random;

        Writer(long seed) {
            random = new Random(seed);
        }

        void raw(String s) {
            byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
            wire.write(b, 0, b.length);
        }

        void rawBytes(byte[] b) {
            wire.write(b, 0, b.length);
        }

        byte[] payload(int max) {
            byte[] b = new byte[random.nextInt(max + 1)];
            random.nextBytes(b);
            if (b.length > 3 && random.nextBoolean()) {
                // payloads may hold CRLF and type bytes
                b[1] = '\r';
                b[2] = '\n';
            }
            return b;
        }

        void string(String prefix, String kind, byte[] data, String format) {
            raw(prefix + (data.length + (format == null ? 0 : 4)) + "\r\n");
            if (format != null) {
                raw(format + ":");
            }
            rawBytes(data);
            raw("\r\n");
            events.add("str:" + kind + ":" + data.length + (format == null ? "" : ":" + format));
            if (data.length > 0) {
                events.add("chunk:" + RecordingEventHandler.hex(data));
            }
            events.add("endstr");
        }

        // a value, at depth, possibly inside an aggregate
        void value(int depth) {
            int choice = random.nextInt(depth > 3 ? 12 : 19);
            switch (choice) {
                case 0:
                    raw("+OK\r\n");
                    events.add("simple:OK");
                    break;
                case 1:
                    raw("-ERR bad thing\r\n");
                    events.add("error:ERR bad thing");
                    break;
                case 2: {
                    long n = random.nextLong();
                    raw(":" + n + "\r\n");
                    events.add("int:" + n);
                    break;
                }
                case 3:
                case 4:
                    string("$", "BULK_STRING", payload(300), null);
                    break;
                case 5:
                    raw("$-1\r\n");
                    events.add("null");
                    break;
                case 6:
                    raw("*-1\r\n");
                    events.add("null");
                    break;
                case 7:
                    raw("_\r\n");
                    events.add("null");
                    break;
                case 8:
                    raw(",1.5\r\n");
                    events.add("double:1.5");
                    break;
                case 9:
                    raw("#t\r\n");
                    events.add("bool:true");
                    break;
                case 10:
                    raw("(-123456789012345678901234567890\r\n");
                    events.add("big:-123456789012345678901234567890");
                    break;
                case 11:
                    string("=", "VERBATIM_STRING", payload(50), "txt");
                    break;
                case 12:
                    string("!", "BLOB_ERROR", payload(50), null);
                    break;
                case 13:
                case 14: {
                    int n = random.nextInt(5);
                    raw("*" + n + "\r\n");
                    events.add("agg:ARRAY:" + n);
                    for (int i = 0; i < n; i++) {
                        value(depth + 1);
                    }
                    events.add("endagg");
                    break;
                }
                case 15: {
                    int n = random.nextInt(4);
                    raw("%" + n + "\r\n");
                    events.add("agg:MAP:" + n);
                    for (int i = 0; i < 2 * n; i++) {
                        value(depth + 1);
                    }
                    events.add("endagg");
                    break;
                }
                case 16: {
                    int n = random.nextInt(4);
                    raw("~" + n + "\r\n");
                    events.add("agg:SET:" + n);
                    for (int i = 0; i < n; i++) {
                        value(depth + 1);
                    }
                    events.add("endagg");
                    break;
                }
                case 17: {
                    // a streamed string
                    raw("$?\r\n");
                    events.add("str:BULK_STRING:-1");
                    StringBuilder all = new StringBuilder();
                    int parts = random.nextInt(4);
                    for (int i = 0; i < parts; i++) {
                        byte[] part = payload(40);
                        if (part.length == 0) {
                            continue;
                        }
                        raw(";" + part.length + "\r\n");
                        rawBytes(part);
                        raw("\r\n");
                        all.append(RecordingEventHandler.hex(part));
                    }
                    raw(";0\r\n");
                    if (all.length() > 0) {
                        events.add("chunk:" + all);
                    }
                    events.add("endstr");
                    break;
                }
                default: {
                    // a streamed aggregate
                    boolean map = random.nextBoolean();
                    raw(map ? "%?\r\n" : "*?\r\n");
                    events.add(map ? "agg:MAP:-1" : "agg:ARRAY:-1");
                    int n = random.nextInt(4);
                    for (int i = 0; i < (map ? 2 * n : n); i++) {
                        value(depth + 1);
                    }
                    raw(".\r\n");
                    events.add("endagg");
                    break;
                }
            }
        }

        // an attribute, which the decoder drops
        void attribute(int depth) {
            raw("|1\r\n+key-popularity\r\n");
            Writer inner = new Writer(random.nextLong());
            inner.value(depth + 1);
            rawBytes(inner.wire.toByteArray());
        }

        // a whole reply or push, with its begin and end
        void reply() {
            int kind = random.nextInt(10);
            if (kind == 0) {
                events.add("begin:push");
                int n = 1 + random.nextInt(3);
                raw(">" + n + "\r\n");
                events.add("agg:PUSH:" + n);
                raw("+invalidate\r\n");
                events.add("simple:invalidate");
                for (int i = 1; i < n; i++) {
                    value(2);
                }
                events.add("endagg");
                events.add("end");
                return;
            }
            if (kind == 1) {
                attribute(0);
            }
            events.add("begin");
            value(0);
            events.add("end");
        }
    }

    private static List<String> decode(byte[] wire, int split) throws RespException {
        RespDecoder decoder = new RespDecoder();
        RecordingEventHandler handler = new RecordingEventHandler();
        int off = 0;
        while (off < wire.length) {
            int n = Math.min(split, wire.length - off);
            ByteBuffer chunk = ByteBuffer.wrap(wire, off, n);
            assertFalse(decoder.receive(chunk, handler));
            assertEquals("all of the input is consumed", 0, chunk.remaining());
            off += n;
        }
        assertTrue(decoder.isIdle());
        return handler.events();
    }

    @Test
    public void everyTypeProducesTheSameEventsAtEverySplit() throws RespException {
        int[] splits = {1, 2, 3, 5, 7, 16, 64, 1 << 20};
        for (long seed = 0; seed < 300; seed++) {
            Writer w = new Writer(seed);
            int replies = 1 + w.random.nextInt(4);
            for (int i = 0; i < replies; i++) {
                w.reply();
            }
            byte[] wire = w.wire.toByteArray();
            for (int i = 0; i < splits.length; i++) {
                assertEquals("seed " + seed + " split " + splits[i], w.events,
                        decode(wire, splits[i]));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fixed cases
    // ─────────────────────────────────────────────────────────────────────────

    private static List<String> events(String wire) throws RespException {
        return decode(wire.getBytes(StandardCharsets.ISO_8859_1), 1);
    }

    @Test
    public void aSimpleString() throws RespException {
        assertEquals(Arrays.asList("begin", "simple:OK", "end"), events("+OK\r\n"));
    }

    @Test
    public void pipelinedRepliesAreSeparate() throws RespException {
        assertEquals(Arrays.asList("begin", "int:1", "end", "begin", "int:2", "end"),
                events(":1\r\n:2\r\n"));
    }

    @Test
    public void anEmptyAggregateIsOpenedAndClosed() throws RespException {
        assertEquals(Arrays.asList("begin", "agg:ARRAY:0", "endagg", "end"), events("*0\r\n"));
    }

    @Test
    public void anEmptyBulkStringHasNoChunk() throws RespException {
        assertEquals(Arrays.asList("begin", "str:BULK_STRING:0", "endstr", "end"),
                events("$0\r\n\r\n"));
    }

    @Test
    public void aNestedAggregateHoldingABulkString() throws RespException {
        assertEquals(Arrays.asList("begin", "agg:ARRAY:2", "agg:ARRAY:1", "str:BULK_STRING:3",
                "chunk:616263", "endstr", "endagg", "int:7", "endagg", "end"),
                events("*2\r\n*1\r\n$3\r\nabc\r\n:7\r\n"));
    }

    @Test
    public void attributesAreReadAndDropped() throws RespException {
        assertEquals(Arrays.asList("begin", "int:5", "end"),
                events("|1\r\n+ttl\r\n*2\r\n:1\r\n:2\r\n:5\r\n"));
    }

    @Test
    public void anAttributeInsideAnAggregateIsDropped() throws RespException {
        assertEquals(Arrays.asList("begin", "agg:ARRAY:2", "int:1", "int:2", "endagg", "end"),
                events("*2\r\n:1\r\n|1\r\n+a\r\n+b\r\n:2\r\n"));
    }

    @Test
    public void aPushBetweenRepliesIsMarkedAsOne() throws RespException {
        assertEquals(Arrays.asList("begin", "int:1", "end", "begin:push", "agg:PUSH:2",
                "simple:message", "simple:x", "endagg", "end", "begin", "int:2", "end"),
                events(":1\r\n>2\r\n+message\r\n+x\r\n:2\r\n"));
    }

    @Test
    public void aVerbatimStringReportsItsFormatAndTextLength() throws RespException {
        assertEquals(Arrays.asList("begin", "str:VERBATIM_STRING:3:txt", "chunk:616263",
                "endstr", "end"), events("=7\r\ntxt:abc\r\n"));
    }

    @Test
    public void aStreamedStringHasNoAnnouncedLength() throws RespException {
        assertEquals(Arrays.asList("begin", "str:BULK_STRING:-1", "chunk:6162", "endstr", "end"),
                events("$?\r\n;1\r\na\r\n;1\r\nb\r\n;0\r\n"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Malformed input
    // ─────────────────────────────────────────────────────────────────────────

    private static void assertMalformed(String wire) {
        RespDecoder decoder = new RespDecoder();
        try {
            decoder.receive(ByteBuffer.wrap(wire.getBytes(StandardCharsets.ISO_8859_1)),
                    new RecordingEventHandler());
            fail("expected RespException for " + wire);
        } catch (RespException expected) {
            // and it stays failed
            try {
                decoder.receive(ByteBuffer.wrap(new byte[] {'+'}), new RecordingEventHandler());
                fail("expected the failure to stay");
            } catch (RespException again) {
                assertEquals(expected.getMessage(), again.getMessage());
            }
        }
    }

    @Test
    public void malformedInputIsRejected() {
        assertMalformed("?\r\n");
        assertMalformed(":12x\r\n");
        assertMalformed(":\r\n");
        assertMalformed(":99999999999999999999\r\n");
        assertMalformed("$-2\r\n");
        assertMalformed("$3\r\nabcde\r\n");
        assertMalformed("$3\r\nabc\rX");
        assertMalformed("*x\r\n");
        assertMalformed("#x\r\n");
        assertMalformed("_x\r\n");
        assertMalformed(",abc\r\n");
        assertMalformed("(12a\r\n");
        assertMalformed("=3\r\nabc\r\n");
        assertMalformed("=4\r\ntxt;\r\n");
        assertMalformed("!?\r\n");
        assertMalformed(".\r\n");
        assertMalformed("$?\r\n+x\r\n");
        assertMalformed("+a\nb\r\n");
    }

    @Test
    public void aLineOverTheLimitIsRejected() {
        RespDecoder decoder = new RespDecoder();
        decoder.setMaxLine(16);
        byte[] big = new byte[40];
        Arrays.fill(big, (byte) 'a');
        ByteBuffer buf = ByteBuffer.allocate(big.length + 1);
        buf.put((byte) '+');
        buf.put(big);
        buf.flip();
        try {
            decoder.receive(buf, new RecordingEventHandler());
            fail("expected RespException");
        } catch (RespException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    @Test
    public void aLineAtTheLimitIsAccepted() throws RespException {
        RespDecoder decoder = new RespDecoder();
        decoder.setMaxLine(16);
        RecordingEventHandler h = new RecordingEventHandler();
        decoder.receive(ByteBuffer.wrap("+0123456789abcdef\r\n".getBytes(StandardCharsets.US_ASCII)), h);
        assertEquals(Arrays.asList("begin", "simple:0123456789abcdef", "end"), h.events());
    }

    @Test
    public void nestingPastTheDepthLimitIsRejected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= RespDecoder.MAX_NESTING_DEPTH; i++) {
            sb.append("*1\r\n");
        }
        assertMalformed(sb.toString());
    }

    @Test
    public void nestingAtTheDepthLimitIsAccepted() throws RespException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < RespDecoder.MAX_NESTING_DEPTH; i++) {
            sb.append("*1\r\n");
        }
        sb.append(":1\r\n");
        List<String> events = events(sb.toString());
        assertEquals("end", events.get(events.size() - 1));
    }

    @Test
    public void anAnnouncedLengthReservesNothing() throws RespException {
        // a hostile length is just a number: nothing is allocated for it
        RespDecoder decoder = new RespDecoder();
        RecordingEventHandler h = new RecordingEventHandler();
        decoder.receive(ByteBuffer.wrap("$9223372036854775807\r\nab".getBytes(StandardCharsets.US_ASCII)), h);
        assertEquals(Arrays.asList("begin", "str:BULK_STRING:9223372036854775807", "chunk:6162"),
                h.events());
        RespDecoder other = new RespDecoder();
        RecordingEventHandler h2 = new RecordingEventHandler();
        other.receive(ByteBuffer.wrap("*9223372036854775807\r\n".getBytes(StandardCharsets.US_ASCII)), h2);
        assertEquals(Arrays.asList("begin", "agg:ARRAY:9223372036854775807"), h2.events());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flow
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    public void pauseStopsAfterTheEventAndLeavesTheRestInTheBuffer() throws RespException {
        RespDecoder decoder = new RespDecoder();
        RecordingEventHandler h = new RecordingEventHandler() {
            private boolean paused;

            @Override
            protected ReplyFlow flowAfter(String event) {
                if (event.equals("int:1") && !paused) {
                    paused = true;
                    return ReplyFlow.PAUSE;
                }
                return ReplyFlow.CONTINUE;
            }
        };
        ByteBuffer buf = ByteBuffer.wrap(":1\r\n:2\r\n".getBytes(StandardCharsets.US_ASCII));
        assertTrue(decoder.receive(buf, h));
        // a reply that ends with the event is ended before the decoder stops
        assertEquals(Arrays.asList("begin", "int:1", "end"), h.events());
        assertEquals(4, buf.remaining());
        assertFalse(decoder.receive(buf, h));
        assertEquals(Arrays.asList("begin", "int:1", "end", "begin", "int:2", "end"), h.events());
    }

    @Test
    public void pauseMidPayloadContinuesTheSameReply() throws RespException {
        byte[] wire = "$10\r\n0123456789\r\n+next\r\n".getBytes(StandardCharsets.US_ASCII);
        RespDecoder decoder = new RespDecoder();
        RecordingEventHandler h = new RecordingEventHandler() {
            @Override
            protected ReplyFlow flowAfter(String event) {
                return event.startsWith("chunk:") ? ReplyFlow.PAUSE : ReplyFlow.CONTINUE;
            }
        };
        // 5 bytes of header, 3 of payload, then the rest
        ByteBuffer first = ByteBuffer.wrap(wire, 0, 5 + 3);
        assertTrue(decoder.receive(first, h));
        assertEquals(0, first.remaining());
        ByteBuffer rest = ByteBuffer.wrap(wire, 5 + 3, wire.length - 8);
        while (rest.hasRemaining()) {
            decoder.receive(rest, h);
        }
        assertEquals(Arrays.asList("begin", "str:BULK_STRING:10", "chunk:303132333435363738" + "39",
                "endstr", "end", "begin", "simple:next", "end"), h.events());
    }

    @Test
    public void skipDiscardsTheRestOfThatReplyOnly() throws RespException {
        RespDecoder decoder = new RespDecoder();
        RecordingEventHandler h = new RecordingEventHandler() {
            @Override
            protected ReplyFlow flowAfter(String event) {
                return event.equals("int:2") ? ReplyFlow.SKIP : ReplyFlow.CONTINUE;
            }
        };
        byte[] wire = "+before\r\n*4\r\n:1\r\n:2\r\n$3\r\nabc\r\n*1\r\n:9\r\n+after\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < wire.length; i++) {
            decoder.receive(ByteBuffer.wrap(wire, i, 1), h);
        }
        assertEquals(Arrays.asList("begin", "simple:before", "end",
                "begin", "agg:ARRAY:4", "int:1", "int:2", "end:skipped",
                "begin", "simple:after", "end"), h.events());
        assertTrue(decoder.isIdle());
    }

    @Test
    public void skipAtTheFirstEventOfAStringDiscardsItsPayload() throws RespException {
        RespDecoder decoder = new RespDecoder();
        RecordingEventHandler h = new RecordingEventHandler() {
            @Override
            protected ReplyFlow flowAfter(String event) {
                return event.startsWith("str:") ? ReplyFlow.SKIP : ReplyFlow.CONTINUE;
            }
        };
        decoder.receive(ByteBuffer.wrap("$5\r\nhello\r\n:1\r\n".getBytes(StandardCharsets.US_ASCII)), h);
        assertEquals(Arrays.asList("begin", "str:BULK_STRING:5", "end:skipped", "begin", "int:1", "end"),
                h.events());
    }

    @Test
    public void skippingPastTheLimitFailsTheDecoder() throws RespException {
        RespDecoder decoder = new RespDecoder();
        decoder.setMaxSkip(100);
        RecordingEventHandler h = new RecordingEventHandler() {
            @Override
            protected ReplyFlow flowAfter(String event) {
                return event.startsWith("str:") ? ReplyFlow.SKIP : ReplyFlow.CONTINUE;
            }
        };
        decoder.receive(ByteBuffer.wrap("$1000\r\n".getBytes(StandardCharsets.US_ASCII)), h);
        ByteBuffer body = ByteBuffer.allocate(99);
        decoder.receive(body, h);
        try {
            decoder.receive(ByteBuffer.allocate(2), h);
            fail("expected RespException");
        } catch (RespException expected) {
            assertTrue(expected.getMessage().contains("100"));
        }
    }

    @Test
    public void resetFromAHandlerDropsTheRestOfTheInput() throws RespException {
        final RespDecoder decoder = new RespDecoder();
        RecordingEventHandler h = new RecordingEventHandler() {
            @Override
            protected ReplyFlow flowAfter(String event) {
                if (event.equals("int:1")) {
                    decoder.reset();
                }
                return ReplyFlow.CONTINUE;
            }
        };
        ByteBuffer buf = ByteBuffer.wrap(":1\r\n:2\r\n".getBytes(StandardCharsets.US_ASCII));
        assertFalse(decoder.receive(buf, h));
        assertEquals(0, buf.remaining());
        assertEquals(Arrays.asList("begin", "int:1"), h.events());
        assertTrue(decoder.isIdle());
    }

}
