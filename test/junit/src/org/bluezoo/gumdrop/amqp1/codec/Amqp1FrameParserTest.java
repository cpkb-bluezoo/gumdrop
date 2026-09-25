/*
 * Amqp1FrameParserTest.java
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

package org.bluezoo.gumdrop.amqp1.codec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link Amqp1FrameParser} and {@link Amqp1Frame}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Amqp1FrameParserTest {

    private Recorder handler;
    private Amqp1FrameParser parser;

    @Before
    public void setUp() {
        handler = new Recorder();
        parser = new Amqp1FrameParser(handler);
    }

    private static byte[] bytes(ByteBuffer buf) {
        byte[] b = new byte[buf.remaining()];
        buf.duplicate().get(b);
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (int i = 0; i < parts.length; i++) {
            n += parts[i].length;
        }
        byte[] out = new byte[n];
        int off = 0;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, out, off, parts[i].length);
            off += parts[i].length;
        }
        return out;
    }

    private static byte[] frame(int type, int channel, byte[] body) {
        return bytes(Amqp1Frame.encode(type, channel, ByteBuffer.wrap(body)));
    }

    private void feed(byte[] data) {
        parser.receive(ByteBuffer.wrap(data));
    }

    @Test
    public void testProtocolHeaderEncoding() {
        assertEquals("414d515000010000",
                Amqp1TypesTest.toHex(bytes(Amqp1Frame.protocolHeader(Amqp1Frame.PROTOCOL_ID_AMQP))));
        assertEquals("414d515003010000",
                Amqp1TypesTest.toHex(bytes(Amqp1Frame.protocolHeader(Amqp1Frame.PROTOCOL_ID_SASL))));
    }

    @Test
    public void testFrameEncoding() {
        byte[] f = frame(Amqp1Frame.TYPE_AMQP, 3, new byte[] {9, 8, 7});
        assertEquals("0000000b02000003" + "090807", Amqp1TypesTest.toHex(f));
        assertEquals("0000000802000000",
                Amqp1TypesTest.toHex(bytes(Amqp1Frame.encodeHeartbeat(0))));
    }

    @Test
    public void testHeaderThenFrame() {
        byte[] data = concat(bytes(Amqp1Frame.protocolHeader(0)),
                frame(Amqp1Frame.TYPE_AMQP, 5, new byte[] {1, 2, 3}));
        feed(data);
        assertEquals(2, handler.events.size());
        assertEquals("header 0 1.0.0", handler.events.get(0));
        assertEquals("frame 0 5 010203", handler.events.get(1));
    }

    @Test
    public void testSaslHeaderReportsProtocolId() {
        feed(bytes(Amqp1Frame.protocolHeader(Amqp1Frame.PROTOCOL_ID_SASL)));
        assertEquals("header 3 1.0.0", handler.events.get(0));
    }

    @Test
    public void testPeerOfferingOtherVersionIsReportedNotRejected() {
        feed(new byte[] {'A', 'M', 'Q', 'P', 0, 0, 9, 1});
        assertEquals("header 0 0.9.1", handler.events.get(0));
        assertTrue(handler.errors.isEmpty());
    }

    @Test
    public void testBadProtocolHeaderIsAnError() {
        feed(new byte[] {'H', 'T', 'T', 'P', '/', '1', '.', '1'});
        assertEquals(1, handler.errors.size());
        assertTrue(handler.events.isEmpty());
    }

    @Test
    public void testHeartbeat() {
        feed(concat(bytes(Amqp1Frame.protocolHeader(0)),
                bytes(Amqp1Frame.encodeHeartbeat(0)),
                bytes(Amqp1Frame.encodeHeartbeat(7))));
        assertEquals("heartbeat 0", handler.events.get(1));
        assertEquals("heartbeat 7", handler.events.get(2));
    }

    @Test
    public void testSaslFrame() {
        feed(concat(bytes(Amqp1Frame.protocolHeader(3)),
                frame(Amqp1Frame.TYPE_SASL, 0, new byte[] {0x42})));
        assertEquals("frame 1 0 42", handler.events.get(1));
    }

    @Test
    public void testEmptySaslFrameIsAnError() {
        feed(concat(bytes(Amqp1Frame.protocolHeader(3)),
                frame(Amqp1Frame.TYPE_SASL, 0, new byte[0])));
        assertEquals(1, handler.errors.size());
    }

    @Test
    public void testExtendedHeaderIsSkipped() {
        // doff = 3: four extra octets between the header and the body
        byte[] f = new byte[] {0, 0, 0, 13, 3, 0, 0, 1, (byte) 0xAA, (byte) 0xBB,
                (byte) 0xCC, (byte) 0xDD, 0x77};
        feed(concat(bytes(Amqp1Frame.protocolHeader(0)), f));
        assertEquals("frame 0 1 77", handler.events.get(1));
    }

    @Test
    public void testFrameSplitAtEveryByteBoundary() {
        byte[] data = concat(bytes(Amqp1Frame.protocolHeader(0)),
                frame(Amqp1Frame.TYPE_AMQP, 2, new byte[] {1, 2, 3, 4, 5}),
                bytes(Amqp1Frame.encodeHeartbeat(0)),
                frame(Amqp1Frame.TYPE_AMQP, 9, new byte[] {6}));
        for (int split = 1; split < data.length; split++) {
            setUp();
            ByteBuffer buf = ByteBuffer.allocate(data.length);
            buf.put(data, 0, split);
            buf.flip();
            parser.receive(buf);
            buf.compact();
            buf.put(data, split, data.length - split);
            buf.flip();
            parser.receive(buf);
            assertEquals("split at " + split, 4, handler.events.size());
            assertEquals("frame 0 2 0102030405", handler.events.get(1));
            assertEquals("heartbeat 0", handler.events.get(2));
            assertEquals("frame 0 9 06", handler.events.get(3));
            assertFalse(buf.hasRemaining());
        }
    }

    @Test
    public void testByteByByteDelivery() {
        byte[] data = concat(bytes(Amqp1Frame.protocolHeader(0)),
                frame(Amqp1Frame.TYPE_AMQP, 2, new byte[] {1, 2, 3, 4, 5}));
        ByteBuffer buf = ByteBuffer.allocate(64);
        for (int i = 0; i < data.length; i++) {
            buf.put(data[i]);
            buf.flip();
            parser.receive(buf);
            buf.compact();
        }
        assertEquals(2, handler.events.size());
    }

    @Test
    public void testPartialItemLeavesPositionUntouched() {
        byte[] f = frame(Amqp1Frame.TYPE_AMQP, 0, new byte[] {1, 2, 3});
        ByteBuffer buf = ByteBuffer.wrap(concat(bytes(Amqp1Frame.protocolHeader(0)),
                f, new byte[] {0, 0, 0, 20, 2}));
        parser.receive(buf);
        assertEquals(2, handler.events.size());
        assertEquals("a partial frame header stays in the buffer", 5, buf.remaining());
    }

    @Test
    public void testBodySliceIsIndependentOfInputPosition() {
        byte[] data = concat(bytes(Amqp1Frame.protocolHeader(0)),
                frame(Amqp1Frame.TYPE_AMQP, 0, new byte[] {1, 2}),
                frame(Amqp1Frame.TYPE_AMQP, 0, new byte[] {3}));
        feed(data);
        assertEquals("frame 0 0 0102", handler.events.get(1));
        assertEquals("frame 0 0 03", handler.events.get(2));
    }

    @Test
    public void testExpectProtocolHeaderRearmsAfterSasl() {
        Amqp1FrameHandler rearming = new Recorder() {
            @Override
            void frameComplete(int type, int channel, byte[] bodyBytes) {
                super.frameComplete(type, channel, bodyBytes);
                if (type == Amqp1Frame.TYPE_SASL) {
                    parser.expectProtocolHeader();
                }
            }
        };
        parser = new Amqp1FrameParser(rearming);
        // SASL header, SASL frame, then (in the same read) the AMQP header
        // and an AMQP frame: the re-arm must apply mid-buffer
        feed(concat(bytes(Amqp1Frame.protocolHeader(3)),
                frame(Amqp1Frame.TYPE_SASL, 0, new byte[] {0x44}),
                bytes(Amqp1Frame.protocolHeader(0)),
                frame(Amqp1Frame.TYPE_AMQP, 0, new byte[] {0x10})));
        List<String> events = ((Recorder) rearming).events;
        assertEquals(4, events.size());
        assertEquals("header 3 1.0.0", events.get(0));
        assertEquals("frame 1 0 44", events.get(1));
        assertEquals("header 0 1.0.0", events.get(2));
        assertEquals("frame 0 0 10", events.get(3));
    }

    @Test
    public void testFrameSizeBelowHeaderIsAnError() {
        feed(concat(bytes(Amqp1Frame.protocolHeader(0)),
                new byte[] {0, 0, 0, 7, 2, 0, 0, 0}));
        assertEquals(1, handler.errors.size());
    }

    @Test
    public void testOversizeFrameIsAnErrorBeforeItsBodyArrives() {
        // Only the header of a 1 MB frame is present; the parser must
        // reject it now rather than wait to buffer it
        feed(concat(bytes(Amqp1Frame.protocolHeader(0)),
                new byte[] {0, 0x10, 0, 0, 2, 0, 0, 0}));
        assertEquals(1, handler.errors.size());
    }

    @Test
    public void testMaxFrameSizeCanBeRaised() {
        parser.setMaxFrameSize(4096);
        byte[] body = new byte[1000];
        feed(concat(bytes(Amqp1Frame.protocolHeader(0)),
                frame(Amqp1Frame.TYPE_AMQP, 0, body)));
        assertTrue(handler.errors.isEmpty());
        assertEquals(2, handler.events.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxFrameSizeBelowMinimumRejected() {
        parser.setMaxFrameSize(511);
    }

    @Test
    public void testDefaultMaxFrameSizeIsSpecMinimum() {
        assertEquals(512, parser.getMaxFrameSize());
        feed(concat(bytes(Amqp1Frame.protocolHeader(0)),
                frame(Amqp1Frame.TYPE_AMQP, 0, new byte[505])));
        assertEquals(1, handler.errors.size());
    }

    @Test
    public void testBadDataOffsetIsAnError() {
        feed(concat(bytes(Amqp1Frame.protocolHeader(0)),
                new byte[] {0, 0, 0, 8, 1, 0, 0, 0}));
        assertEquals(1, handler.errors.size());
        setUp();
        // doff points beyond the end of the frame
        feed(concat(bytes(Amqp1Frame.protocolHeader(0)),
                new byte[] {0, 0, 0, 9, 3, 0, 0, 0, 1}));
        assertEquals(1, handler.errors.size());
    }

    @Test
    public void testUnknownFrameTypeIsAnError() {
        feed(concat(bytes(Amqp1Frame.protocolHeader(0)),
                new byte[] {0, 0, 0, 9, 2, 5, 0, 0, 1}));
        assertEquals(1, handler.errors.size());
    }

    @Test
    public void testNothingDeliveredAfterAnError() {
        feed(concat(bytes(Amqp1Frame.protocolHeader(0)),
                new byte[] {0, 0, 0, 9, 2, 5, 0, 0, 1},
                frame(Amqp1Frame.TYPE_AMQP, 0, new byte[] {1})));
        assertEquals(1, handler.errors.size());
        assertEquals(1, handler.events.size()); // just the header
        feed(frame(Amqp1Frame.TYPE_AMQP, 0, new byte[] {1}));
        assertEquals(1, handler.events.size());
        assertEquals(1, handler.errors.size());
    }

    @Test
    public void testErrorLeavesOffendingBytesUnconsumed() {
        ByteBuffer buf = ByteBuffer.wrap(concat(bytes(Amqp1Frame.protocolHeader(0)),
                new byte[] {0, 0, 0, 9, 2, 5, 0, 0, 1}));
        parser.receive(buf);
        assertEquals(9, buf.remaining());
    }

    @Test
    public void testBodyBytesAreExact() {
        byte[] body = new byte[100];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        feed(concat(bytes(Amqp1Frame.protocolHeader(0)),
                frame(Amqp1Frame.TYPE_AMQP, 0, body)));
        assertEquals("frame 0 0 " + Amqp1TypesTest.toHex(body), handler.events.get(1));
    }

    @Test
    public void testBodyStreamsWithoutWaitingForWholeFrame() {
        parser.setMaxFrameSize(4096);
        byte[] body = new byte[400];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        byte[] wire = concat(bytes(Amqp1Frame.protocolHeader(0)),
                frame(Amqp1Frame.TYPE_AMQP, 4, body));
        // header + frame header + 10 body octets only
        ByteBuffer buf = ByteBuffer.wrap(wire, 0, 8 + 8 + 10);
        parser.receive(buf);
        assertEquals("the frame has started and its first chunk been delivered",
                1, handler.chunks);
        assertEquals(400, handler.declaredLength);
        assertEquals("not complete yet: only the header event", 1, handler.events.size());
        // the rest, in 50-octet pieces
        int off = 8 + 8 + 10;
        while (off < wire.length) {
            int n = Math.min(50, wire.length - off);
            parser.receive(ByteBuffer.wrap(wire, off, n));
            off += n;
        }
        assertEquals(2, handler.events.size());
        assertEquals("frame 0 4 " + Amqp1TypesTest.toHex(body), handler.events.get(1));
        assertTrue(handler.chunks > 5);
    }

    @Test
    public void testConsecutiveFramesInOneBuffer() {
        feed(concat(bytes(Amqp1Frame.protocolHeader(0)),
                frame(Amqp1Frame.TYPE_AMQP, 1, new byte[] {1}),
                frame(Amqp1Frame.TYPE_AMQP, 2, new byte[] {2, 2}),
                frame(Amqp1Frame.TYPE_AMQP, 3, new byte[] {3, 3, 3})));
        assertEquals(4, handler.events.size());
        assertEquals("frame 0 3 030303", handler.events.get(3));
    }

    /**
     * Records handler events as strings, reassembling each frame's body
     * from its streamed chunks.
     */
    private static class Recorder implements Amqp1FrameHandler {
        final List<String> events = new ArrayList<String>();
        final List<String> errors = new ArrayList<String>();
        int chunks;
        int declaredLength;
        private int type;
        private int channel;
        private final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();

        @Override
        public void protocolHeader(int protocolId, int major, int minor, int revision) {
            events.add("header " + protocolId + " " + major + "." + minor + "." + revision);
        }

        @Override
        public void startFrame(int type, int channel, int bodyLength) {
            this.type = type;
            this.channel = channel;
            this.declaredLength = bodyLength;
            body.reset();
        }

        @Override
        public void frameBody(ByteBuffer chunk) {
            chunks++;
            byte[] b = bytes(chunk);
            body.write(b, 0, b.length);
        }

        @Override
        public void endFrame() {
            assertEquals(declaredLength, body.size());
            frameComplete(type, channel, body.toByteArray());
        }

        void frameComplete(int type, int channel, byte[] bodyBytes) {
            events.add("frame " + type + " " + channel + " "
                    + Amqp1TypesTest.toHex(bodyBytes));
        }

        @Override
        public void heartbeat(int channel) {
            events.add("heartbeat " + channel);
        }

        @Override
        public void frameError(String message) {
            errors.add(message);
        }
    }
}
