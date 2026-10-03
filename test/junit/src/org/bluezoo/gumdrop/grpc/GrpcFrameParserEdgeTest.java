/*
 * GrpcFrameParserEdgeTest.java
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


package org.bluezoo.gumdrop.grpc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Edge branches of {@link GrpcFrameParser} and {@link GrpcFraming}: argument
 * validation, compressed and oversized frames, unlimited message size,
 * partial-frame reporting and header parsing limits.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcFrameParserEdgeTest {

    private static final class Events implements GrpcEventHandler {
        final List<String> errors = new ArrayList<String>();
        int started;
        int ended;
        int dataBytes;

        @Override
        public void startMessage(byte compressionFlag, int length) {
            started++;
        }

        @Override
        public void messageData(ByteBuffer data) {
            dataBytes += data.remaining();
        }

        @Override
        public void endMessage() {
            ended++;
        }

        @Override
        public void parseError(String message) {
            errors.add(message);
        }
    }

    private static ByteBuffer header(int flag, long length) {
        ByteBuffer b = ByteBuffer.allocate(5);
        b.put((byte) flag);
        b.put((byte) (length >> 24));
        b.put((byte) (length >> 16));
        b.put((byte) (length >> 8));
        b.put((byte) length);
        b.flip();
        return b;
    }

    @Test
    public void parserRejectsNullHandlerAndNegativeLimit() {
        try {
            new GrpcFrameParser(null);
            fail("null handler accepted");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        GrpcFrameParser parser = new GrpcFrameParser(new Events());
        try {
            parser.setMaxMessageSize(-1);
            fail("negative limit accepted");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        parser.setMaxMessageSize(1234);
        assertEquals(1234, parser.getMaxMessageSize());
    }

    @Test
    public void compressedFramesAreReportedAndTheParserRecovers() {
        Events events = new Events();
        GrpcFrameParser parser = new GrpcFrameParser(events);
        parser.receive(header(1, 3));
        assertEquals(1, events.errors.size());
        assertFalse(parser.hasPartialFrame());
        parser.receive(header(0, 0));
        assertEquals(1, events.started);
        assertEquals(1, events.ended);
        assertTrue(parser.isMessageCompleted());
    }

    @Test
    public void framesBeyondTheIntegerRangeAreReported() {
        Events events = new Events();
        GrpcFrameParser parser = new GrpcFrameParser(events);
        parser.receive(header(0, 0xFFFFFFFFL));
        assertEquals(1, events.errors.size());
        assertEquals(0, events.started);
        assertFalse(parser.hasPartialFrame());
    }

    @Test
    public void framesBeyondTheConfiguredLimitAreReportedButZeroMeansUnlimited() {
        Events events = new Events();
        GrpcFrameParser parser = new GrpcFrameParser(events);
        parser.setMaxMessageSize(10);
        parser.receive(header(0, 11));
        assertEquals(1, events.errors.size());
        parser.setMaxMessageSize(0);
        ByteBuffer big = ByteBuffer.allocate(5 + 1000);
        big.put(header(0, 2000));
        big.position(big.limit());
        big.flip();
        parser.receive(big);
        assertEquals(1, events.errors.size());
        assertEquals(1, events.started);
        assertTrue(parser.hasPartialFrame());
        assertEquals(0, events.ended);
        parser.receive(ByteBuffer.allocate(1000));
        assertEquals(2000, events.dataBytes);
        assertEquals(1, events.ended);
    }

    @Test
    public void zeroLengthMessageEndsWithoutFurtherInput() {
        Events events = new Events();
        GrpcFrameParser parser = new GrpcFrameParser(events);
        parser.receive(header(0, 0));
        assertEquals(1, events.started);
        assertEquals("an empty message is complete as soon as its header is", 1, events.ended);
        assertTrue(parser.isMessageCompleted());
        assertFalse(parser.hasPartialFrame());
        ByteBuffer two = ByteBuffer.allocate(10);
        two.put(header(0, 0));
        two.put(header(0, 0));
        two.flip();
        parser.receive(two);
        assertEquals(3, events.ended);
    }

    @Test
    public void headerSplitAcrossBuffersCountsAsPartial() {
        Events events = new Events();
        GrpcFrameParser parser = new GrpcFrameParser(events);
        ByteBuffer h = header(0, 2);
        ByteBuffer first = ByteBuffer.wrap(new byte[] {h.get(), h.get()});
        parser.receive(first);
        assertTrue(parser.hasPartialFrame());
        assertEquals(0, events.started);
        ByteBuffer rest = ByteBuffer.allocate(5);
        rest.put(h.get());
        rest.put(h.get());
        rest.put(h.get());
        rest.put((byte) 1);
        rest.put((byte) 2);
        rest.flip();
        parser.receive(rest);
        assertEquals(1, events.ended);
        assertFalse(parser.hasPartialFrame());
        assertEquals(2, events.dataBytes);
    }

    @Test
    public void framingHeaderReadingHonoursLimits() {
        assertEquals(-1, GrpcFraming.readHeader(ByteBuffer.allocate(3)));
        assertEquals(7, GrpcFraming.readHeader(header(0, 7)));
        try {
            GrpcFraming.readHeader(header(0, 0xFFFFFFFFL));
            fail("oversized length accepted");
        } catch (GrpcException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            GrpcFraming.readHeader(header(0, 100), 10);
            fail("limit ignored");
        } catch (GrpcException expected) {
            assertNotNull(expected.getMessage());
        }
        assertEquals(100, GrpcFraming.readHeader(header(0, 100), 0));
        ByteBuffer framed = ByteBuffer.allocate(8);
        framed.put(header(0, 3));
        framed.put(new byte[] {1, 2, 3});
        framed.flip();
        GrpcFraming.skipHeader(framed);
        assertEquals(3, framed.remaining());
    }

    @Test
    public void exceptionKeepsItsCause() {
        Exception cause = new IllegalStateException("root");
        GrpcException e = new GrpcException("wrapped", cause);
        assertEquals("wrapped", e.getMessage());
        assertEquals(cause, e.getCause());
    }
}
