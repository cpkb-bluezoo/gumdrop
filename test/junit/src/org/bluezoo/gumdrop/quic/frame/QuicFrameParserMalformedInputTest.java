/*
 * QuicFrameParserMalformedInputTest.java
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

/**
 * Regression tests for issue #262 -- JQF/Zest fuzzing (200,000 generated
 * inputs, all failing) found that a truncated QUIC frame of essentially
 * any type throws an unchecked {@link java.nio.BufferUnderflowException}
 * or {@link IndexOutOfBoundsException} out of {@link QuicFrameParser#receive},
 * instead of being reported via {@link QuicFrameHandler#frameError} as
 * the class's own javadoc promises. These three cases exercise
 * distinct call sites (the frame-type varint read in {@code receive}
 * itself, an ACK frame's fixed-field reads, and a STREAM frame's
 * stream-ID read) to demonstrate the fix is systemic, not a one-off
 * bounds check.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicFrameParserMalformedInputTest {

    private static class RecordingHandler implements QuicFrameHandler {

        final List<String> events = new ArrayList<String>();

        @Override public void paddingFrameReceived(int length) { }
        @Override public void pingFrameReceived() { }
        @Override public void ackFrameReceived(long largestAcknowledged, long ackDelay, long[][] ranges) { }
        @Override public void resetStreamFrameReceived(long streamId, long applicationErrorCode, long finalSize) { }
        @Override public void stopSendingFrameReceived(long streamId, long applicationErrorCode) { }
        @Override public void cryptoFrameReceived(long offset, ByteBuffer data) { }
        @Override public void newTokenFrameReceived(ByteBuffer token) { }
        @Override public void streamFrameReceived(long streamId, long offset, boolean fin, ByteBuffer data) { }
        @Override public void maxDataFrameReceived(long maximumData) { }
        @Override public void maxStreamDataFrameReceived(long streamId, long maximumStreamData) { }
        @Override public void maxStreamsFrameReceived(boolean bidirectional, long maximumStreams) { }
        @Override public void dataBlockedFrameReceived(long maximumData) { }
        @Override public void streamDataBlockedFrameReceived(long streamId, long maximumStreamData) { }
        @Override public void streamsBlockedFrameReceived(boolean bidirectional, long maximumStreams) { }
        @Override public void newConnectionIdFrameReceived(long sequenceNumber, long retirePriorTo,
                ByteBuffer connectionId, ByteBuffer statelessResetToken) { }
        @Override public void retireConnectionIdFrameReceived(long sequenceNumber) { }
        @Override public void pathChallengeFrameReceived(ByteBuffer data) { }
        @Override public void pathResponseFrameReceived(ByteBuffer data) { }
        @Override public void connectionCloseFrameReceived(boolean applicationError, long errorCode,
                long frameType, String reason) { }
        @Override public void handshakeDoneFrameReceived() { }
        @Override public void datagramFrameReceived(ByteBuffer data, int encodedLength) { }

        @Override
        public void frameError(String message) {
            events.add("error:" + message);
        }
    }

    @Test
    public void testTruncatedFrameTypeVarintReportsErrorInsteadOfThrowing() {
        // A single byte whose top 2 bits (11) claim an 8-byte varint,
        // with no further bytes -- VarInt.decode's own getLong() call
        // throws BufferUnderflowException directly out of receive().
        byte[] data = { (byte) 0xC0 };

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(ByteBuffer.wrap(data));

        assertEquals(1, handler.events.size());
        assertTrue(handler.events.get(0).startsWith("error:"));
    }

    @Test
    public void testTruncatedAckFrameReportsErrorInsteadOfThrowing() {
        // ACK frame type (0x02), then 4 bytes -- satisfying parseAckFrame's
        // own "remaining() < 4" guard -- but the first of those 4 bytes
        // (0xC0) claims an 8-byte varint for largestAcknowledged, so
        // VarInt.decode's getLong() still reads past the buffer's end.
        byte[] data = { 0x02, (byte) 0xC0, 0x00, 0x00, 0x00 };

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(ByteBuffer.wrap(data));

        assertEquals(1, handler.events.size());
        assertTrue(handler.events.get(0).startsWith("error:"));
    }

    @Test
    public void testTruncatedStreamFrameReportsErrorInsteadOfThrowing() {
        // STREAM frame type 0x08 (no OFF/LEN/FIN), then a single byte
        // (0xC0) claiming an 8-byte varint for the stream ID, with no
        // further bytes -- parseStreamFrame's own guard only checks
        // hasRemaining() (>= 1 byte), not the full width VarInt.decode
        // may need.
        byte[] data = { 0x08, (byte) 0xC0 };

        RecordingHandler handler = new RecordingHandler();
        new QuicFrameParser(handler).receive(ByteBuffer.wrap(data));

        assertEquals(1, handler.events.size());
        assertTrue(handler.events.get(0).startsWith("error:"));
    }
}
