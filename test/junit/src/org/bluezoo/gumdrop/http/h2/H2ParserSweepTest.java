/*
 * H2ParserSweepTest.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.testsupport.TruncationSweep;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Truncation and corruption sweeps over {@link H2Parser}: every frame type
 * with its optional fields (padding, priority), fed whole, truncated and
 * with each byte corrupted. The parser reports problems through
 * {@code frameError} and must never throw; a buffer ending inside a frame
 * must be left unconsumed from the start of that frame.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H2ParserSweepTest {

    /** Ignores every event. */
    private static final class NullHandler implements H2FrameHandler {
        public void dataFrameReceived(int streamId, boolean endStream, ByteBuffer data) { }
        public void headersFrameReceived(int streamId, boolean endStream, boolean endHeaders,
                int streamDependency, boolean exclusive, int weight, ByteBuffer fragment) { }
        public void priorityFrameReceived(int streamId, int streamDependency,
                boolean exclusive, int weight) { }
        public void priorityUpdateFrameReceived(int prioritizedStreamId, String fieldValue) { }
        public void rstStreamFrameReceived(int streamId, int errorCode) { }
        public void settingsFrameReceived(boolean ack, Map<Integer, Integer> settings) { }
        public void pushPromiseFrameReceived(int streamId, int promisedStreamId,
                boolean endHeaders, ByteBuffer fragment) { }
        public void pingFrameReceived(boolean ack, long opaqueData) { }
        public void goawayFrameReceived(int lastStreamId, int errorCode, ByteBuffer debugData) { }
        public void windowUpdateFrameReceived(int streamId, int windowSizeIncrement) { }
        public void continuationFrameReceived(int streamId, boolean endHeaders,
                ByteBuffer fragment) { }
        public void frameError(int errorCode, int streamId, String message) { }
    }

    private static void frame(ByteArrayOutputStream out, int type, int flags, int stream,
            int... payload) {
        out.write(payload.length >> 16);
        out.write(payload.length >> 8);
        out.write(payload.length);
        out.write(type);
        out.write(flags);
        out.write(stream >> 24);
        out.write(stream >> 16);
        out.write(stream >> 8);
        out.write(stream);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i]);
        }
    }

    private static byte[] frames() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        frame(out, 0x0, 0x08, 1, 2, 'a', 'b', 0, 0);
        frame(out, 0x1, 0x2c, 1, 1, 0, 0, 0, 3, 15, 0x82, 0x84, 0);
        frame(out, 0x2, 0, 3, 0, 0, 0, 1, 10);
        frame(out, 0x3, 0, 3, 0, 0, 0, 8);
        frame(out, 0x4, 0, 0, 0, 3, 0, 0, 0, 100, 0, 5, 0, 0, 0x40, 0);
        frame(out, 0x5, 0x04 | 0x08, 1, 1, 0, 0, 0, 2, 0x82, 0);
        frame(out, 0x6, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8);
        frame(out, 0x7, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 'x');
        frame(out, 0x8, 0, 1, 0, 0, 1, 0);
        frame(out, 0x9, 0x4, 1, 0x82);
        frame(out, 0x10, 0, 0, 0, 0, 0, 3, 'u', '=', '1');
        return out.toByteArray();
    }

    private static TruncationSweep.Target target() {
        return new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) {
                new H2Parser(new NullHandler()).receive(ByteBuffer.wrap(input));
            }
        };
    }

    @Test
    public void parserNeverThrows() {
        List<String> failures = TruncationSweep.allFailures(frames(), target());
        assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test
    public void everyStrictPrefixOfAFrameStaysUnconsumed() {
        ByteArrayOutputStream one = new ByteArrayOutputStream();
        frame(one, 0x6, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8);
        byte[] data = one.toByteArray();
        for (int n = 0; n < data.length; n++) {
            ByteBuffer buf = ByteBuffer.wrap(data, 0, n);
            new H2Parser(new NullHandler()).receive(buf);
            assertEquals("prefix " + n, 0, buf.position());
        }
    }
}
