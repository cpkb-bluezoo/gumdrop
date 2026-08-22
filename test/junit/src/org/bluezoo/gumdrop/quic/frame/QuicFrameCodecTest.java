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

import org.bluezoo.util.ByteArrays;

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
        public void ackFrameReceived(long largestAcknowledged, long ackDelay, long[][] ranges) {
            StringBuilder rangesText = new StringBuilder();
            for (int i = 0; i < ranges.length; i++) {
                if (i > 0) {
                    rangesText.append(',');
                }
                rangesText.append(ranges[i][0]).append('-').append(ranges[i][1]);
            }
            events.add("ack:" + largestAcknowledged + ":" + ackDelay + ":" + rangesText);
        }

        @Override
        public void resetStreamFrameReceived(long streamId, long applicationErrorCode, long finalSize) {
            events.add("reset_stream:" + streamId + ":" + applicationErrorCode + ":" + finalSize);
        }

        @Override
        public void stopSendingFrameReceived(long streamId, long applicationErrorCode) {
            events.add("stop_sending:" + streamId + ":" + applicationErrorCode);
        }

        @Override
        public void cryptoFrameReceived(long offset, ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            events.add("crypto:" + offset + ":" + new String(copy, StandardCharsets.US_ASCII));
        }

        @Override
        public void newTokenFrameReceived(ByteBuffer token) {
            byte[] copy = new byte[token.remaining()];
            token.get(copy);
            events.add("new_token:" + new String(copy, StandardCharsets.US_ASCII));
        }

        @Override
        public void streamFrameReceived(long streamId, long offset, boolean fin, ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            events.add("stream:" + streamId + ":" + offset + ":" + fin + ":"
                    + new String(copy, StandardCharsets.US_ASCII));
        }

        @Override
        public void maxDataFrameReceived(long maximumData) {
            events.add("max_data:" + maximumData);
        }

        @Override
        public void maxStreamDataFrameReceived(long streamId, long maximumStreamData) {
            events.add("max_stream_data:" + streamId + ":" + maximumStreamData);
        }

        @Override
        public void maxStreamsFrameReceived(boolean bidirectional, long maximumStreams) {
            events.add("max_streams:" + bidirectional + ":" + maximumStreams);
        }

        @Override
        public void dataBlockedFrameReceived(long maximumData) {
            events.add("data_blocked:" + maximumData);
        }

        @Override
        public void streamDataBlockedFrameReceived(long streamId, long maximumStreamData) {
            events.add("stream_data_blocked:" + streamId + ":" + maximumStreamData);
        }

        @Override
        public void streamsBlockedFrameReceived(boolean bidirectional, long maximumStreams) {
            events.add("streams_blocked:" + bidirectional + ":" + maximumStreams);
        }

        @Override
        public void newConnectionIdFrameReceived(long sequenceNumber, long retirePriorTo,
                ByteBuffer connectionId, ByteBuffer statelessResetToken) {
            byte[] cid = new byte[connectionId.remaining()];
            connectionId.get(cid);
            byte[] token = new byte[statelessResetToken.remaining()];
            statelessResetToken.get(token);
            events.add("new_connection_id:" + sequenceNumber + ":" + retirePriorTo + ":"
                    + ByteArrays.toHexString(cid) + ":" + ByteArrays.toHexString(token));
        }

        @Override
        public void retireConnectionIdFrameReceived(long sequenceNumber) {
            events.add("retire_connection_id:" + sequenceNumber);
        }

        @Override
        public void pathChallengeFrameReceived(ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            events.add("path_challenge:" + ByteArrays.toHexString(copy));
        }

        @Override
        public void pathResponseFrameReceived(ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            events.add("path_response:" + ByteArrays.toHexString(copy));
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
        public void datagramFrameReceived(ByteBuffer data, int encodedLength) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            events.add("datagram:" + encodedLength + ":" + ByteArrays.toHexString(bytes));
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
        long[][] ranges = { { 37, 42 } };
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.ackLength(ranges, 100));
        QuicFrameWriter.writeAck(buf, ranges, 100);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("ack:42:100:37-42", handler.events.get(0));
    }

    @Test
    public void testAckFrameWithGapBetweenRanges() {
        // Largest=100, first range covers 95-100; a gap (packets 92-94
        // unacknowledged), then a second range covering 88-91.
        long[][] ranges = { { 95, 100 }, { 88, 91 } };
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.ackLength(ranges, 0));
        QuicFrameWriter.writeAck(buf, ranges, 0);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("ack:100:0:95-100,88-91", handler.events.get(0));
    }

    @Test
    public void testAckFrameWithMinimalOnePacketGap() {
        // Largest=50, first range covers 48-50; a one-packet gap (47),
        // then a second range covering just 45-46.
        long[][] ranges = { { 48, 50 }, { 45, 46 } };
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.ackLength(ranges, 0));
        QuicFrameWriter.writeAck(buf, ranges, 0);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("ack:50:0:48-50,45-46", handler.events.get(0));
    }

    @Test
    public void testAckFrameNegativePacketNumberReportsError() {
        // Largest Acknowledged=2, First ACK Range=10: 2-10 is negative.
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put((byte) 0x02); // TYPE_ACK
        buf.put((byte) 2);    // Largest Acknowledged
        buf.put((byte) 0);    // ACK Delay
        buf.put((byte) 0);    // ACK Range Count
        buf.put((byte) 10);   // First ACK Range
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertTrue(handler.events.get(0).startsWith("error:"));
    }

    @Test
    public void testResetStreamFrame() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.resetStreamLength(4, 12, 1024));
        QuicFrameWriter.writeResetStream(buf, 4, 12, 1024);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("reset_stream:4:12:1024", handler.events.get(0));
    }

    @Test
    public void testResetStreamFrameUnderflow() {
        ByteBuffer full = ByteBuffer.allocate(QuicFrameWriter.resetStreamLength(4, 12, 1024));
        QuicFrameWriter.writeResetStream(full, 4, 12, 1024);
        full.flip();
        // Truncate after the stream ID, before the error code.
        ByteBuffer truncated = ByteBuffer.allocate(2);
        truncated.put(full.get());
        truncated.put(full.get());
        truncated.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(truncated);

        assertEquals(1, handler.events.size());
        assertTrue(handler.events.get(0).startsWith("error:"));
    }

    @Test
    public void testStopSendingFrame() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.stopSendingLength(4, 12));
        QuicFrameWriter.writeStopSending(buf, 4, 12);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("stop_sending:4:12", handler.events.get(0));
    }

    @Test
    public void testNewTokenFrame() {
        byte[] token = "opaque-token-bytes".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.newTokenLength(token.length));
        QuicFrameWriter.writeNewToken(buf, token);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("new_token:opaque-token-bytes", handler.events.get(0));
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
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.connectionCloseLength(false, 7, "bad frame"));
        QuicFrameWriter.writeConnectionClose(buf, false, 7, 6, "bad frame");
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("close:false:7:6:bad frame", handler.events.get(0));
    }

    @Test
    public void testConnectionCloseApplication() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.connectionCloseLength(true, 0, "goodbye"));
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
        buf.put((byte) 0x1f); // one past HANDSHAKE_DONE (0x1e) -- not a defined frame type
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertTrue(handler.events.get(0).startsWith("error:"));
    }

    @Test
    public void testStreamFrame() {
        byte[] data = "query bytes".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.streamLength(4, 10, data.length));
        QuicFrameWriter.writeStream(buf, 4, 10, data, true);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("stream:4:10:true:query bytes", handler.events.get(0));
    }

    @Test
    public void testMaxDataFrame() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.maxDataLength(1_000_000));
        QuicFrameWriter.writeMaxData(buf, 1_000_000);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("max_data:1000000", handler.events.get(0));
    }

    @Test
    public void testMaxStreamDataFrame() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.maxStreamDataLength(4, 500_000));
        QuicFrameWriter.writeMaxStreamData(buf, 4, 500_000);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("max_stream_data:4:500000", handler.events.get(0));
    }

    @Test
    public void testMaxStreamsFrame() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.maxStreamsLength(true, 100));
        QuicFrameWriter.writeMaxStreams(buf, true, 100);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("max_streams:true:100", handler.events.get(0));
    }

    @Test
    public void testDataBlockedFrame() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.dataBlockedLength(1_000_000));
        QuicFrameWriter.writeDataBlocked(buf, 1_000_000);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("data_blocked:1000000", handler.events.get(0));
    }

    @Test
    public void testStreamDataBlockedFrame() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.streamDataBlockedLength(4, 500_000));
        QuicFrameWriter.writeStreamDataBlocked(buf, 4, 500_000);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("stream_data_blocked:4:500000", handler.events.get(0));
    }

    @Test
    public void testStreamsBlockedFrame() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.streamsBlockedLength(false, 50));
        QuicFrameWriter.writeStreamsBlocked(buf, false, 50);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("streams_blocked:false:50", handler.events.get(0));
    }

    @Test
    public void testNewConnectionIdFrame() {
        byte[] cid = ByteArrays.toByteArray("a1b2c3d4e5f60708");
        byte[] token = ByteArrays.toByteArray("000102030405060708090a0b0c0d0e0f");
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.newConnectionIdLength(3, 1, cid, token));
        QuicFrameWriter.writeNewConnectionId(buf, 3, 1, cid, token);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("new_connection_id:3:1:" + ByteArrays.toHexString(cid) + ":" + ByteArrays.toHexString(token),
                handler.events.get(0));
    }

    @Test
    public void testNewConnectionIdFrameInvalidLengthReportsError() {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.put((byte) QuicFrameHandler.TYPE_NEW_CONNECTION_ID);
        buf.put((byte) 3); // Sequence Number
        buf.put((byte) 1); // Retire Prior To
        buf.put((byte) 0); // Length = 0, invalid (connection IDs are 1-20 bytes)
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertTrue(handler.events.get(0).startsWith("error:"));
    }

    @Test
    public void testRetireConnectionIdFrame() {
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.retireConnectionIdLength(2));
        QuicFrameWriter.writeRetireConnectionId(buf, 2);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("retire_connection_id:2", handler.events.get(0));
    }

    @Test
    public void testPathChallengeFrame() {
        byte[] data = ByteArrays.toByteArray("0011223344556677");
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.pathChallengeLength());
        QuicFrameWriter.writePathChallenge(buf, data);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("path_challenge:" + ByteArrays.toHexString(data), handler.events.get(0));
    }

    @Test
    public void testPathResponseFrame() {
        byte[] data = ByteArrays.toByteArray("7766554433221100");
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.pathResponseLength());
        QuicFrameWriter.writePathResponse(buf, data);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertEquals("path_response:" + ByteArrays.toHexString(data), handler.events.get(0));
    }

    @Test
    public void testDatagramFrameWithLength() {
        byte[] payload = ByteArrays.toByteArray("cafef00d");
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.datagramLength(payload.length));
        QuicFrameWriter.writeDatagram(buf, payload);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        int encoded = QuicFrameWriter.datagramLength(payload.length);
        assertEquals("datagram:" + encoded + ":" + ByteArrays.toHexString(payload), handler.events.get(0));
    }

    @Test
    public void testDatagramFrameWithoutLengthConsumesRemainder() {
        byte[] payload = ByteArrays.toByteArray("deadbeef");
        ByteBuffer buf = ByteBuffer.allocate(QuicFrameWriter.datagramWithoutLengthLength(payload.length));
        QuicFrameWriter.writeDatagramWithoutLength(buf, payload);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        int encoded = QuicFrameWriter.datagramWithoutLengthLength(payload.length);
        assertEquals("datagram:" + encoded + ":" + ByteArrays.toHexString(payload), handler.events.get(0));
    }

    @Test
    public void testDatagramFrameWithLengthThenPing() {
        byte[] payload = ByteArrays.toByteArray("01");
        int size = QuicFrameWriter.datagramLength(payload.length) + QuicFrameWriter.pingLength();
        ByteBuffer buf = ByteBuffer.allocate(size);
        QuicFrameWriter.writeDatagram(buf, payload);
        QuicFrameWriter.writePing(buf);
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(2, handler.events.size());
        assertTrue(handler.events.get(0).startsWith("datagram:"));
        assertEquals("ping", handler.events.get(1));
    }

    @Test
    public void testDatagramFrameLengthUnderflowReportsError() {
        ByteBuffer buf = ByteBuffer.allocate(3);
        buf.put((byte) QuicFrameHandler.TYPE_DATAGRAM_LEN);
        buf.put((byte) 10); // Length claims 10 bytes that are not present
        buf.flip();

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(buf);

        assertEquals(1, handler.events.size());
        assertTrue(handler.events.get(0).startsWith("error:"));
    }
}
