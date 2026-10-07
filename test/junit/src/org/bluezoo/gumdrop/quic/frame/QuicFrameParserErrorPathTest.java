/*
 * QuicFrameParserErrorPathTest.java
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
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Drives every per-frame-type error arm of {@link QuicFrameParser} (frames
 * cut short after each field, out-of-range lengths, invalid connection ID
 * lengths, negative ACK ranges) plus the successful decoding of the
 * multi-range, ECN and datagram shapes, using a hand-written mock handler
 * that records each callback as a string.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicFrameParserErrorPathTest {

    /** Mock handler recording every callback as a short string. */
    private static class MockHandler implements QuicFrameHandler {

        final List<String> events = new ArrayList<String>();

        public void paddingFrameReceived(int length) {
            events.add("padding:" + length);
        }

        public void pingFrameReceived() {
            events.add("ping");
        }

        public void ackFrameReceived(long largestAcknowledged, long ackDelay, long[][] ranges) {
            StringBuilder sb = new StringBuilder("ack:");
            sb.append(largestAcknowledged).append(':').append(ackDelay);
            for (int i = 0; i < ranges.length; i++) {
                sb.append(':').append(ranges[i][0]).append('-').append(ranges[i][1]);
            }
            events.add(sb.toString());
        }

        public void resetStreamFrameReceived(long streamId, long applicationErrorCode, long finalSize) {
            events.add("reset:" + streamId + ":" + applicationErrorCode + ":" + finalSize);
        }

        public void stopSendingFrameReceived(long streamId, long applicationErrorCode) {
            events.add("stop:" + streamId + ":" + applicationErrorCode);
        }

        public void cryptoFrameReceived(long offset, ByteBuffer data) {
            events.add("crypto:" + offset + ":" + data.remaining());
        }

        public void newTokenFrameReceived(ByteBuffer token) {
            events.add("token:" + token.remaining());
        }

        public void streamFrameReceived(long streamId, long offset, boolean fin, ByteBuffer data) {
            events.add("stream:" + streamId + ":" + offset + ":" + fin + ":" + data.remaining());
        }

        public void maxDataFrameReceived(long maximumData) {
            events.add("maxdata:" + maximumData);
        }

        public void maxStreamDataFrameReceived(long streamId, long maximumStreamData) {
            events.add("maxstreamdata:" + streamId + ":" + maximumStreamData);
        }

        public void maxStreamsFrameReceived(boolean bidirectional, long maximumStreams) {
            events.add("maxstreams:" + bidirectional + ":" + maximumStreams);
        }

        public void dataBlockedFrameReceived(long maximumData) {
            events.add("datablocked:" + maximumData);
        }

        public void streamDataBlockedFrameReceived(long streamId, long maximumStreamData) {
            events.add("streamdatablocked:" + streamId + ":" + maximumStreamData);
        }

        public void streamsBlockedFrameReceived(boolean bidirectional, long maximumStreams) {
            events.add("streamsblocked:" + bidirectional + ":" + maximumStreams);
        }

        public void newConnectionIdFrameReceived(long sequenceNumber, long retirePriorTo,
                ByteBuffer connectionId, ByteBuffer statelessResetToken) {
            events.add("newcid:" + sequenceNumber + ":" + retirePriorTo + ":"
                    + connectionId.remaining() + ":" + statelessResetToken.remaining());
        }

        public void retireConnectionIdFrameReceived(long sequenceNumber) {
            events.add("retire:" + sequenceNumber);
        }

        public void pathChallengeFrameReceived(ByteBuffer data) {
            events.add("pathchallenge:" + data.remaining());
        }

        public void pathResponseFrameReceived(ByteBuffer data) {
            events.add("pathresponse:" + data.remaining());
        }

        public void connectionCloseFrameReceived(boolean applicationError, long errorCode,
                long frameType, String reason) {
            events.add("close:" + applicationError + ":" + errorCode + ":" + frameType + ":" + reason);
        }

        public void handshakeDoneFrameReceived() {
            events.add("handshakedone");
        }

        public void datagramFrameReceived(ByteBuffer data, int encodedLength) {
            events.add("datagram:" + data.remaining() + ":" + encodedLength);
        }

        @Override public void ackFrequencyFrameReceived(long sequenceNumber, long ackElicitingThreshold, long requestedMaxAckDelay, long reorderingThreshold) { }
        @Override public void immediateAckFrameReceived() { }
        public void frameError(String message) {
            events.add("error:" + message);
        }
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    private static List<String> parse(int... values) {
        MockHandler handler = new MockHandler();
        QuicFrameParser parser = new QuicFrameParser(handler);
        parser.receive(ByteBuffer.wrap(bytes(values)));
        return handler.events;
    }

    private static void assertSingleError(String expectedPrefix, int... values) {
        List<String> events = parse(values);
        assertEquals(events.toString(), 1, events.size());
        String event = events.get(0);
        assertTrue(event, event.startsWith("error:" + expectedPrefix));
    }

    @Test
    public void testNullHandlerRejected() {
        try {
            new QuicFrameParser(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("handler"));
        }
    }

    @Test
    public void testPaddingRunOnHeapBuffer() {
        List<String> events = parse(0, 0, 0, 1);
        assertEquals("[padding:3, ping]", events.toString());
    }

    @Test
    public void testPaddingRunOnReadOnlyBufferWithoutArray() {
        MockHandler handler = new MockHandler();
        QuicFrameParser parser = new QuicFrameParser(handler);
        ByteBuffer buf = ByteBuffer.wrap(bytes(0, 0, 0, 0, 1)).asReadOnlyBuffer();
        parser.receive(buf);
        assertEquals("[padding:4, ping]", handler.events.toString());
    }

    @Test
    public void testPaddingRunOnDirectBufferToEnd() {
        MockHandler handler = new MockHandler();
        QuicFrameParser parser = new QuicFrameParser(handler);
        ByteBuffer buf = ByteBuffer.allocateDirect(3);
        buf.put(bytes(0, 0, 0));
        buf.flip();
        parser.receive(buf);
        assertEquals("[padding:3]", handler.events.toString());
    }

    @Test
    public void testUnknownFrameTypeReportsErrorAndRewinds() {
        MockHandler handler = new MockHandler();
        QuicFrameParser parser = new QuicFrameParser(handler);
        ByteBuffer buf = ByteBuffer.wrap(bytes(0x21, 0x01));
        parser.receive(buf);
        assertEquals(1, handler.events.size());
        assertTrue(handler.events.get(0).startsWith("error:Unsupported or unknown frame type: 33"));
        assertEquals(0, buf.position());
    }

    @Test
    public void testHandshakeDoneAndPingFrames() {
        assertEquals("[handshakedone, ping]", parse(0x1e, 0x01).toString());
    }

    @Test
    public void testAckWithMultipleRanges() {
        List<String> events = parse(0x02, 0x0a, 0x00, 0x01, 0x00, 0x00, 0x00);
        assertEquals("[ack:10:0:10-10:8-8]", events.toString());
    }

    @Test
    public void testAckEcnWithCounts() {
        List<String> events = parse(0x03, 0x05, 0x01, 0x00, 0x02, 0x01, 0x02, 0x03);
        assertEquals("[ack:5:1:3-5]", events.toString());
    }

    @Test
    public void testAckEcnMissingCounts() {
        assertSingleError("ACK frame underflow in ECN counts", 0x03, 0x05, 0x00, 0x00, 0x00);
    }

    @Test
    public void testAckUnderflowAfterType() {
        assertSingleError("ACK frame underflow", 0x02);
        assertSingleError("ACK frame underflow", 0x02, 0x05, 0x00);
    }

    @Test
    public void testAckFirstRangeNegative() {
        assertSingleError("ACK frame's first range computes a negative", 0x02, 0x00, 0x00, 0x00, 0x05);
    }

    @Test
    public void testAckRangeCountWithNoRangeBytes() {
        assertSingleError("ACK frame underflow in range 0", 0x02, 0x05, 0x00, 0x01, 0x00);
    }

    @Test
    public void testAckRangeMissingLength() {
        assertSingleError("ACK frame underflow reading range 0's length", 0x02, 0x05, 0x00, 0x01, 0x00, 0x01);
    }

    @Test
    public void testAckRangeNegative() {
        assertSingleError("ACK frame range 0 computes a negative", 0x02, 0x05, 0x00, 0x01, 0x00, 0x05, 0x00);
    }

    @Test
    public void testResetStreamValidAndTruncated() {
        assertEquals("[reset:1:2:3]", parse(0x04, 0x01, 0x02, 0x03).toString());
        assertSingleError("RESET_STREAM frame underflow", 0x04);
        assertSingleError("RESET_STREAM frame underflow reading error code", 0x04, 0x01);
        assertSingleError("RESET_STREAM frame underflow reading final size", 0x04, 0x01, 0x02);
    }

    @Test
    public void testStopSendingValidAndTruncated() {
        assertEquals("[stop:1:2]", parse(0x05, 0x01, 0x02).toString());
        assertSingleError("STOP_SENDING frame underflow", 0x05);
        assertSingleError("STOP_SENDING frame underflow reading value", 0x05, 0x01);
    }

    @Test
    public void testCryptoValidAndErrors() {
        assertEquals("[crypto:4:2]", parse(0x06, 0x04, 0x02, 0xaa, 0xbb).toString());
        assertSingleError("CRYPTO frame underflow", 0x06);
        assertSingleError("CRYPTO frame length exceeds payload", 0x06, 0x00, 0x05, 0xaa);
        assertSingleError("Truncated frame", 0x06, 0x00);
    }

    @Test
    public void testNewTokenValidAndErrors() {
        assertEquals("[token:2]", parse(0x07, 0x02, 0xaa, 0xbb).toString());
        assertSingleError("NEW_TOKEN frame underflow", 0x07);
        assertSingleError("NEW_TOKEN frame length exceeds payload", 0x07, 0x00);
        assertSingleError("NEW_TOKEN frame length exceeds payload", 0x07, 0x05, 0xaa);
    }

    @Test
    public void testStreamFrameFlagCombinations() {
        assertEquals("[stream:1:0:false:2]", parse(0x08, 0x01, 0xaa, 0xbb).toString());
        assertEquals("[stream:1:7:true:2]", parse(0x0d, 0x01, 0x07, 0xaa, 0xbb).toString());
        assertEquals("[stream:1:0:false:1, ping]", parse(0x0a, 0x01, 0x01, 0xaa, 0x01).toString());
    }

    @Test
    public void testStreamFrameErrors() {
        assertSingleError("STREAM frame underflow", 0x08);
        assertSingleError("STREAM frame underflow reading offset", 0x0c, 0x01);
        assertSingleError("STREAM frame underflow reading length", 0x0a, 0x01);
        assertSingleError("STREAM frame length exceeds payload", 0x0a, 0x01, 0x05, 0xaa);
    }

    @Test
    public void testFlowControlFramesValidAndTruncated() {
        assertEquals("[maxdata:9]", parse(0x10, 0x09).toString());
        assertEquals("[maxstreamdata:1:9]", parse(0x11, 0x01, 0x09).toString());
        assertEquals("[maxstreams:true:4]", parse(0x12, 0x04).toString());
        assertEquals("[maxstreams:false:4]", parse(0x13, 0x04).toString());
        assertEquals("[datablocked:5]", parse(0x14, 0x05).toString());
        assertEquals("[streamdatablocked:2:5]", parse(0x15, 0x02, 0x05).toString());
        assertEquals("[streamsblocked:true:6]", parse(0x16, 0x06).toString());
        assertEquals("[streamsblocked:false:6]", parse(0x17, 0x06).toString());
        assertEquals("[retire:3]", parse(0x19, 0x03).toString());
        assertSingleError("MAX_DATA frame underflow", 0x10);
        assertSingleError("MAX_STREAM_DATA frame underflow", 0x11);
        assertSingleError("MAX_STREAM_DATA frame underflow reading value", 0x11, 0x01);
        assertSingleError("MAX_STREAMS frame underflow", 0x12);
        assertSingleError("MAX_STREAMS frame underflow", 0x13);
        assertSingleError("DATA_BLOCKED frame underflow", 0x14);
        assertSingleError("STREAM_DATA_BLOCKED frame underflow", 0x15);
        assertSingleError("STREAM_DATA_BLOCKED frame underflow reading value", 0x15, 0x01);
        assertSingleError("STREAMS_BLOCKED frame underflow", 0x16);
        assertSingleError("STREAMS_BLOCKED frame underflow", 0x17);
        assertSingleError("RETIRE_CONNECTION_ID frame underflow", 0x19);
    }

    @Test
    public void testNewConnectionIdValid() {
        int[] frame = new int[1 + 1 + 1 + 1 + 4 + 16];
        frame[0] = 0x18;
        frame[1] = 0x02;
        frame[2] = 0x01;
        frame[3] = 0x04;
        assertEquals("[newcid:2:1:4:16]", parse(frame).toString());
    }

    @Test
    public void testNewConnectionIdErrors() {
        assertSingleError("NEW_CONNECTION_ID frame underflow", 0x18);
        assertSingleError("NEW_CONNECTION_ID frame underflow reading Retire Prior To", 0x18, 0x01);
        assertSingleError("NEW_CONNECTION_ID frame underflow reading Length", 0x18, 0x01, 0x00);
        assertSingleError("NEW_CONNECTION_ID frame has an invalid connection ID length: 0", 0x18, 0x01, 0x00, 0x00);
        assertSingleError("NEW_CONNECTION_ID frame has an invalid connection ID length: 21", 0x18, 0x01, 0x00, 0x15);
        assertSingleError("NEW_CONNECTION_ID frame underflow reading connection ID", 0x18, 0x01, 0x00, 0x04, 0x01, 0x02);
    }

    @Test
    public void testPathFrames() {
        assertEquals("[pathchallenge:8]", parse(0x1a, 1, 2, 3, 4, 5, 6, 7, 8).toString());
        assertEquals("[pathresponse:8]", parse(0x1b, 1, 2, 3, 4, 5, 6, 7, 8).toString());
        assertSingleError("PATH_CHALLENGE frame underflow", 0x1a, 1, 2, 3);
        assertSingleError("PATH_RESPONSE frame underflow", 0x1b, 1, 2, 3);
    }

    @Test
    public void testConnectionCloseFrames() {
        assertEquals("[close:false:7:2:no]", parse(0x1c, 0x07, 0x02, 0x02, 'n', 'o').toString());
        assertEquals("[close:true:7:0:no]", parse(0x1d, 0x07, 0x02, 'n', 'o').toString());
        assertSingleError("CONNECTION_CLOSE frame underflow", 0x1c);
        assertSingleError("CONNECTION_CLOSE frame underflow", 0x1d);
        assertSingleError("CONNECTION_CLOSE reason phrase length exceeds payload", 0x1c, 0x07, 0x00, 0x05, 'n');
        assertSingleError("CONNECTION_CLOSE reason phrase length exceeds payload", 0x1d, 0x07, 0x05, 'n');
        assertSingleError("Truncated frame", 0x1c, 0x07);
    }

    @Test
    public void testDatagramFrames() {
        assertEquals("[datagram:2:3]", parse(0x30, 0xaa, 0xbb).toString());
        assertEquals("[datagram:2:4, ping]", parse(0x31, 0x02, 0xaa, 0xbb, 0x01).toString());
        assertSingleError("DATAGRAM frame underflow", 0x31);
        assertSingleError("DATAGRAM frame underflow", 0x31, 0x05, 0xaa);
    }
}
