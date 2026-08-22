/*
 * QuicFrameWriter.java
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

import org.bluezoo.gumdrop.quic.packet.VarInt;

/**
 * Writes QUIC frames (RFC 9000 section 19) into a {@link ByteBuffer}.
 *
 * <p>Each {@code writeXxx} method has a matching {@code xxxLength}
 * method, so a packet's exact payload size can be computed before
 * allocating the buffer it will be written into -- needed up front,
 * since {@link org.bluezoo.gumdrop.quic.packet.LongHeaderCodec#build}
 * and {@link org.bluezoo.gumdrop.quic.packet.PacketProtection#seal}
 * both need the payload length before any frame bytes exist.
 *
 * <p>ACK frames support arbitrarily many ranges (RFC 9000 section
 * 19.3.1), but never ECN counts (always type
 * {@link QuicFrameHandler#TYPE_ACK}, never {@code TYPE_ACK_ECN}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicFrameParser
 */
public final class QuicFrameWriter {

    private QuicFrameWriter() {
    }

    /**
     * Returns the encoded length of {@code count} consecutive PADDING
     * frames: {@code count}, since each PADDING frame is a single
     * zero byte.
     *
     * @param count the number of PADDING frames
     * @return the encoded length in bytes
     */
    public static int paddingLength(int count) {
        return count;
    }

    /**
     * Writes {@code count} consecutive PADDING frames (RFC 9000 section 19.1).
     *
     * @param out the destination buffer
     * @param count the number of PADDING frames to write
     */
    public static void writePadding(ByteBuffer out, int count) {
        for (int i = 0; i < count; i++) {
            out.put((byte) 0);
        }
    }

    /**
     * Returns the encoded length of a PING frame.
     *
     * @return the encoded length in bytes
     */
    public static int pingLength() {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_PING);
    }

    /**
     * Writes a PING frame (RFC 9000 section 19.2).
     *
     * @param out the destination buffer
     */
    public static void writePing(ByteBuffer out) {
        VarInt.encode(QuicFrameHandler.TYPE_PING, out);
    }

    /**
     * Returns the encoded length of an ACK frame acknowledging {@code ranges}.
     *
     * @param ranges every acknowledged packet number range, as
     *               {@code {low, high}} pairs inclusive of both ends, in
     *               descending order -- {@code ranges[0]} is the highest
     *               range and its {@code high} is the Largest Acknowledged
     * @param ackDelay the ACK Delay field
     * @return the encoded length in bytes
     */
    public static int ackLength(long[][] ranges, long ackDelay) {
        long length = VarInt.encodedLength(QuicFrameHandler.TYPE_ACK)
                + VarInt.encodedLength(ranges[0][1]) // Largest Acknowledged
                + VarInt.encodedLength(ackDelay)
                + VarInt.encodedLength(ranges.length - 1) // ACK Range Count
                + VarInt.encodedLength(ranges[0][1] - ranges[0][0]); // First ACK Range
        long previousLow = ranges[0][0];
        for (int i = 1; i < ranges.length; i++) {
            long gap = previousLow - ranges[i][1] - 2;
            long rangeLength = ranges[i][1] - ranges[i][0];
            length += VarInt.encodedLength(gap) + VarInt.encodedLength(rangeLength);
            previousLow = ranges[i][0];
        }
        return (int) length;
    }

    /**
     * Writes an ACK frame (RFC 9000 section 19.3/19.3.1), with no ECN counts.
     *
     * @param out the destination buffer
     * @param ranges every acknowledged packet number range, as
     *               {@code {low, high}} pairs inclusive of both ends, in
     *               descending order -- {@code ranges[0]} is the highest
     *               range and its {@code high} is the Largest Acknowledged
     * @param ackDelay the ACK Delay field
     */
    public static void writeAck(ByteBuffer out, long[][] ranges, long ackDelay) {
        VarInt.encode(QuicFrameHandler.TYPE_ACK, out);
        VarInt.encode(ranges[0][1], out); // Largest Acknowledged
        VarInt.encode(ackDelay, out);
        VarInt.encode(ranges.length - 1, out); // ACK Range Count
        VarInt.encode(ranges[0][1] - ranges[0][0], out); // First ACK Range

        long previousLow = ranges[0][0];
        for (int i = 1; i < ranges.length; i++) {
            long gap = previousLow - ranges[i][1] - 2;
            long rangeLength = ranges[i][1] - ranges[i][0];
            VarInt.encode(gap, out);
            VarInt.encode(rangeLength, out);
            previousLow = ranges[i][0];
        }
    }

    /**
     * Returns the encoded length of a RESET_STREAM frame.
     *
     * @param streamId the affected stream
     * @param applicationErrorCode the reason for the reset
     * @param finalSize the total number of bytes sent on the stream
     * @return the encoded length in bytes
     */
    public static int resetStreamLength(long streamId, long applicationErrorCode, long finalSize) {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_RESET_STREAM)
                + VarInt.encodedLength(streamId)
                + VarInt.encodedLength(applicationErrorCode)
                + VarInt.encodedLength(finalSize);
    }

    /**
     * Writes a RESET_STREAM frame (RFC 9000 section 19.4), abruptly
     * terminating this side's sending part of a stream.
     *
     * @param out the destination buffer
     * @param streamId the affected stream
     * @param applicationErrorCode the reason for the reset
     * @param finalSize the total number of bytes sent on the stream
     */
    public static void writeResetStream(ByteBuffer out, long streamId, long applicationErrorCode, long finalSize) {
        VarInt.encode(QuicFrameHandler.TYPE_RESET_STREAM, out);
        VarInt.encode(streamId, out);
        VarInt.encode(applicationErrorCode, out);
        VarInt.encode(finalSize, out);
    }

    /**
     * Returns the encoded length of a STOP_SENDING frame.
     *
     * @param streamId the affected stream
     * @param applicationErrorCode the reason for the request
     * @return the encoded length in bytes
     */
    public static int stopSendingLength(long streamId, long applicationErrorCode) {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_STOP_SENDING)
                + VarInt.encodedLength(streamId)
                + VarInt.encodedLength(applicationErrorCode);
    }

    /**
     * Writes a STOP_SENDING frame (RFC 9000 section 19.5), requesting
     * that the peer abruptly terminate its sending part of a stream.
     *
     * @param out the destination buffer
     * @param streamId the affected stream
     * @param applicationErrorCode the reason for the request
     */
    public static void writeStopSending(ByteBuffer out, long streamId, long applicationErrorCode) {
        VarInt.encode(QuicFrameHandler.TYPE_STOP_SENDING, out);
        VarInt.encode(streamId, out);
        VarInt.encode(applicationErrorCode, out);
    }

    /**
     * Returns the encoded length of a CRYPTO frame carrying
     * {@code dataLength} bytes of handshake data.
     *
     * @param offset the byte offset of this data within the CRYPTO stream
     * @param dataLength the number of handshake data bytes
     * @return the encoded length in bytes
     */
    public static int cryptoLength(long offset, int dataLength) {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_CRYPTO)
                + VarInt.encodedLength(offset)
                + VarInt.encodedLength(dataLength)
                + dataLength;
    }

    /**
     * Writes a CRYPTO frame (RFC 9000 section 19.6).
     *
     * @param out the destination buffer
     * @param offset the byte offset of this data within the CRYPTO stream
     * @param data the handshake data
     */
    public static void writeCrypto(ByteBuffer out, long offset, byte[] data) {
        VarInt.encode(QuicFrameHandler.TYPE_CRYPTO, out);
        VarInt.encode(offset, out);
        VarInt.encode(data.length, out);
        out.put(data);
    }

    /**
     * Returns the encoded length of a NEW_TOKEN frame carrying a token
     * of {@code tokenLength} bytes.
     *
     * @param tokenLength the number of token bytes
     * @return the encoded length in bytes
     */
    public static int newTokenLength(int tokenLength) {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_NEW_TOKEN)
                + VarInt.encodedLength(tokenLength)
                + tokenLength;
    }

    /**
     * Writes a NEW_TOKEN frame (RFC 9000 section 19.7). Server-to-client
     * only.
     *
     * @param out the destination buffer
     * @param token the opaque token bytes, must not be empty
     */
    public static void writeNewToken(ByteBuffer out, byte[] token) {
        VarInt.encode(QuicFrameHandler.TYPE_NEW_TOKEN, out);
        VarInt.encode(token.length, out);
        out.put(token);
    }

    /**
     * Returns the encoded length of a STREAM frame carrying
     * {@code dataLength} bytes, always written with explicit Offset and
     * Length fields (RFC 9000 section 19.8) for simplicity, regardless
     * of whether {@code offset} is 0.
     *
     * @param streamId the stream identifier
     * @param offset the byte offset of this data within the stream
     * @param dataLength the number of stream data bytes
     * @return the encoded length in bytes
     */
    public static int streamLength(long streamId, long offset, int dataLength) {
        // Type is always TYPE_STREAM_MIN | 0x06 (OFF and LEN bits set) here,
        // same varint length as TYPE_STREAM_MIN itself (single byte, 0x08-0x0f).
        return VarInt.encodedLength(QuicFrameHandler.TYPE_STREAM_MIN)
                + VarInt.encodedLength(streamId)
                + VarInt.encodedLength(offset)
                + VarInt.encodedLength(dataLength)
                + dataLength;
    }

    /**
     * Writes a STREAM frame (RFC 9000 section 19.8), always with
     * explicit Offset and Length fields.
     *
     * @param out the destination buffer
     * @param streamId the stream identifier
     * @param offset the byte offset of this data within the stream
     * @param data the stream data
     * @param fin true if this frame marks the end of the stream
     */
    public static void writeStream(ByteBuffer out, long streamId, long offset, byte[] data, boolean fin) {
        long type = QuicFrameHandler.TYPE_STREAM_MIN | 0x04 | 0x02 | (fin ? 0x01 : 0x00);
        VarInt.encode(type, out);
        VarInt.encode(streamId, out);
        VarInt.encode(offset, out);
        VarInt.encode(data.length, out);
        out.put(data);
    }

    /**
     * Returns the encoded length of a MAX_DATA frame.
     *
     * @return the encoded length in bytes
     */
    public static int maxDataLength(long maximumData) {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_MAX_DATA) + VarInt.encodedLength(maximumData);
    }

    /**
     * Writes a MAX_DATA frame (RFC 9000 section 19.9).
     *
     * @param out the destination buffer
     * @param maximumData the new connection-level send limit
     */
    public static void writeMaxData(ByteBuffer out, long maximumData) {
        VarInt.encode(QuicFrameHandler.TYPE_MAX_DATA, out);
        VarInt.encode(maximumData, out);
    }

    /**
     * Returns the encoded length of a MAX_STREAM_DATA frame.
     *
     * @return the encoded length in bytes
     */
    public static int maxStreamDataLength(long streamId, long maximumStreamData) {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_MAX_STREAM_DATA)
                + VarInt.encodedLength(streamId)
                + VarInt.encodedLength(maximumStreamData);
    }

    /**
     * Writes a MAX_STREAM_DATA frame (RFC 9000 section 19.10).
     *
     * @param out the destination buffer
     * @param streamId the affected stream
     * @param maximumStreamData the new stream-level send limit
     */
    public static void writeMaxStreamData(ByteBuffer out, long streamId, long maximumStreamData) {
        VarInt.encode(QuicFrameHandler.TYPE_MAX_STREAM_DATA, out);
        VarInt.encode(streamId, out);
        VarInt.encode(maximumStreamData, out);
    }

    /**
     * Returns the encoded length of a MAX_STREAMS frame.
     *
     * @return the encoded length in bytes
     */
    public static int maxStreamsLength(boolean bidirectional, long maximumStreams) {
        long type = bidirectional ? QuicFrameHandler.TYPE_MAX_STREAMS_BIDI : QuicFrameHandler.TYPE_MAX_STREAMS_UNI;
        return VarInt.encodedLength(type) + VarInt.encodedLength(maximumStreams);
    }

    /**
     * Writes a MAX_STREAMS frame (RFC 9000 section 19.11).
     *
     * @param out the destination buffer
     * @param bidirectional true to write {@link QuicFrameHandler#TYPE_MAX_STREAMS_BIDI},
     *                      false for {@link QuicFrameHandler#TYPE_MAX_STREAMS_UNI}
     * @param maximumStreams the new cumulative stream limit
     */
    public static void writeMaxStreams(ByteBuffer out, boolean bidirectional, long maximumStreams) {
        long type = bidirectional ? QuicFrameHandler.TYPE_MAX_STREAMS_BIDI : QuicFrameHandler.TYPE_MAX_STREAMS_UNI;
        VarInt.encode(type, out);
        VarInt.encode(maximumStreams, out);
    }

    /**
     * Returns the encoded length of a DATA_BLOCKED frame.
     *
     * @return the encoded length in bytes
     */
    public static int dataBlockedLength(long maximumData) {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_DATA_BLOCKED) + VarInt.encodedLength(maximumData);
    }

    /**
     * Writes a DATA_BLOCKED frame (RFC 9000 section 19.12).
     *
     * @param out the destination buffer
     * @param maximumData the connection-level limit at which sending was blocked
     */
    public static void writeDataBlocked(ByteBuffer out, long maximumData) {
        VarInt.encode(QuicFrameHandler.TYPE_DATA_BLOCKED, out);
        VarInt.encode(maximumData, out);
    }

    /**
     * Returns the encoded length of a STREAM_DATA_BLOCKED frame.
     *
     * @return the encoded length in bytes
     */
    public static int streamDataBlockedLength(long streamId, long maximumStreamData) {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_STREAM_DATA_BLOCKED)
                + VarInt.encodedLength(streamId)
                + VarInt.encodedLength(maximumStreamData);
    }

    /**
     * Writes a STREAM_DATA_BLOCKED frame (RFC 9000 section 19.13).
     *
     * @param out the destination buffer
     * @param streamId the blocked stream
     * @param maximumStreamData the stream-level limit at which sending was blocked
     */
    public static void writeStreamDataBlocked(ByteBuffer out, long streamId, long maximumStreamData) {
        VarInt.encode(QuicFrameHandler.TYPE_STREAM_DATA_BLOCKED, out);
        VarInt.encode(streamId, out);
        VarInt.encode(maximumStreamData, out);
    }

    /**
     * Returns the encoded length of a STREAMS_BLOCKED frame.
     *
     * @return the encoded length in bytes
     */
    public static int streamsBlockedLength(boolean bidirectional, long maximumStreams) {
        long type = bidirectional
                ? QuicFrameHandler.TYPE_STREAMS_BLOCKED_BIDI : QuicFrameHandler.TYPE_STREAMS_BLOCKED_UNI;
        return VarInt.encodedLength(type) + VarInt.encodedLength(maximumStreams);
    }

    /**
     * Writes a STREAMS_BLOCKED frame (RFC 9000 section 19.14).
     *
     * @param out the destination buffer
     * @param bidirectional true to write {@link QuicFrameHandler#TYPE_STREAMS_BLOCKED_BIDI},
     *                      false for {@link QuicFrameHandler#TYPE_STREAMS_BLOCKED_UNI}
     * @param maximumStreams the stream limit at which opening was blocked
     */
    public static void writeStreamsBlocked(ByteBuffer out, boolean bidirectional, long maximumStreams) {
        long type = bidirectional
                ? QuicFrameHandler.TYPE_STREAMS_BLOCKED_BIDI : QuicFrameHandler.TYPE_STREAMS_BLOCKED_UNI;
        VarInt.encode(type, out);
        VarInt.encode(maximumStreams, out);
    }

    /**
     * Returns the encoded length of a NEW_CONNECTION_ID frame.
     *
     * @param sequenceNumber the new connection ID's sequence number
     * @param retirePriorTo connection IDs below this sequence number must be retired
     * @param connectionId the new connection ID bytes, 1-20 bytes long
     * @param statelessResetToken the associated stateless reset token,
     *                            must be {@link QuicFrameHandler#STATELESS_RESET_TOKEN_LENGTH} bytes
     * @return the encoded length in bytes
     */
    public static int newConnectionIdLength(long sequenceNumber, long retirePriorTo,
            byte[] connectionId, byte[] statelessResetToken) {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_NEW_CONNECTION_ID)
                + VarInt.encodedLength(sequenceNumber)
                + VarInt.encodedLength(retirePriorTo)
                + 1 // Length
                + connectionId.length
                + statelessResetToken.length;
    }

    /**
     * Writes a NEW_CONNECTION_ID frame (RFC 9000 section 19.15).
     *
     * @param out the destination buffer
     * @param sequenceNumber the new connection ID's sequence number
     * @param retirePriorTo connection IDs below this sequence number must be retired
     * @param connectionId the new connection ID bytes, 1-20 bytes long
     * @param statelessResetToken the associated stateless reset token,
     *                            must be {@link QuicFrameHandler#STATELESS_RESET_TOKEN_LENGTH} bytes
     */
    public static void writeNewConnectionId(ByteBuffer out, long sequenceNumber, long retirePriorTo,
            byte[] connectionId, byte[] statelessResetToken) {
        VarInt.encode(QuicFrameHandler.TYPE_NEW_CONNECTION_ID, out);
        VarInt.encode(sequenceNumber, out);
        VarInt.encode(retirePriorTo, out);
        out.put((byte) connectionId.length);
        out.put(connectionId);
        out.put(statelessResetToken);
    }

    /**
     * Returns the encoded length of a RETIRE_CONNECTION_ID frame.
     *
     * @param sequenceNumber the sequence number being retired
     * @return the encoded length in bytes
     */
    public static int retireConnectionIdLength(long sequenceNumber) {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_RETIRE_CONNECTION_ID) + VarInt.encodedLength(sequenceNumber);
    }

    /**
     * Writes a RETIRE_CONNECTION_ID frame (RFC 9000 section 19.16).
     *
     * @param out the destination buffer
     * @param sequenceNumber the sequence number being retired
     */
    public static void writeRetireConnectionId(ByteBuffer out, long sequenceNumber) {
        VarInt.encode(QuicFrameHandler.TYPE_RETIRE_CONNECTION_ID, out);
        VarInt.encode(sequenceNumber, out);
    }

    /**
     * Returns the encoded length of a PATH_CHALLENGE frame.
     *
     * @return the encoded length in bytes
     */
    public static int pathChallengeLength() {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_PATH_CHALLENGE) + QuicFrameHandler.PATH_DATA_LENGTH;
    }

    /**
     * Writes a PATH_CHALLENGE frame (RFC 9000 section 19.17).
     *
     * @param out the destination buffer
     * @param data the challenge data, must be {@link QuicFrameHandler#PATH_DATA_LENGTH} bytes
     */
    public static void writePathChallenge(ByteBuffer out, byte[] data) {
        VarInt.encode(QuicFrameHandler.TYPE_PATH_CHALLENGE, out);
        out.put(data);
    }

    /**
     * Returns the encoded length of a PATH_RESPONSE frame.
     *
     * @return the encoded length in bytes
     */
    public static int pathResponseLength() {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_PATH_RESPONSE) + QuicFrameHandler.PATH_DATA_LENGTH;
    }

    /**
     * Writes a PATH_RESPONSE frame (RFC 9000 section 19.18).
     *
     * @param out the destination buffer
     * @param data the response data, must be {@link QuicFrameHandler#PATH_DATA_LENGTH} bytes,
     *             echoing a previously received PATH_CHALLENGE
     */
    public static void writePathResponse(ByteBuffer out, byte[] data) {
        VarInt.encode(QuicFrameHandler.TYPE_PATH_RESPONSE, out);
        out.put(data);
    }

    /**
     * Returns the encoded length of a CONNECTION_CLOSE frame.
     *
     * @param applicationError true for an application-level close
     *                         ({@link QuicFrameHandler#TYPE_CONNECTION_CLOSE_APP}),
     *                         false for a transport-level close
     * @param errorCode the error code that will be written (its encoded
     *                  length varies with its value, so this must match
     *                  what is later passed to {@link #writeConnectionClose})
     * @param reason the human-readable reason phrase
     * @return the encoded length in bytes
     */
    public static int connectionCloseLength(boolean applicationError, long errorCode, String reason) {
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        long type = applicationError
                ? QuicFrameHandler.TYPE_CONNECTION_CLOSE_APP
                : QuicFrameHandler.TYPE_CONNECTION_CLOSE;
        long length = VarInt.encodedLength(type)
                + VarInt.encodedLength(errorCode)
                + VarInt.encodedLength(reasonBytes.length)
                + reasonBytes.length;
        if (!applicationError) {
            // The frame type that triggered the error is always reported
            // as 0 by every caller in this codebase (frame-type-specific
            // errors aren't distinguished), so this doesn't need its own
            // parameter -- but it's coupled to the same VarInt.encodedLength(0L)
            // assumption as errorCode used to be.
            length += VarInt.encodedLength(0L); // frame type
        }
        return (int) length;
    }

    /**
     * Writes a CONNECTION_CLOSE frame (RFC 9000 section 19.19).
     *
     * @param out the destination buffer
     * @param applicationError true for an application-level close
     *                         ({@link QuicFrameHandler#TYPE_CONNECTION_CLOSE_APP}),
     *                         false for a transport-level close
     * @param errorCode the error code
     * @param frameType for a transport-level close, the frame type that
     *                  triggered the error (ignored for an application-level close)
     * @param reason the human-readable reason phrase
     */
    public static void writeConnectionClose(ByteBuffer out, boolean applicationError,
            long errorCode, long frameType, String reason) {
        long type = applicationError
                ? QuicFrameHandler.TYPE_CONNECTION_CLOSE_APP
                : QuicFrameHandler.TYPE_CONNECTION_CLOSE;
        VarInt.encode(type, out);
        VarInt.encode(errorCode, out);
        if (!applicationError) {
            VarInt.encode(frameType, out);
        }
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        VarInt.encode(reasonBytes.length, out);
        out.put(reasonBytes);
    }

    /**
     * Returns the encoded length of a HANDSHAKE_DONE frame.
     *
     * @return the encoded length in bytes
     */
    public static int handshakeDoneLength() {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_HANDSHAKE_DONE);
    }

    /**
     * Writes a HANDSHAKE_DONE frame (RFC 9000 section 19.20).
     *
     * @param out the destination buffer
     */
    public static void writeHandshakeDone(ByteBuffer out) {
        VarInt.encode(QuicFrameHandler.TYPE_HANDSHAKE_DONE, out);
    }

    /**
     * Returns the encoded length of a DATAGRAM frame with a Length
     * field (RFC 9221 type {@link QuicFrameHandler#TYPE_DATAGRAM_LEN}).
     * Production sends always use this form so a DATAGRAM can share a
     * packet with other frames.
     *
     * @param payloadLength the payload size in bytes
     * @return the encoded length in bytes, including type and Length
     */
    public static int datagramLength(int payloadLength) {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_DATAGRAM_LEN)
                + VarInt.encodedLength(payloadLength)
                + payloadLength;
    }

    /**
     * Writes a DATAGRAM frame with a Length field (RFC 9221 type
     * {@link QuicFrameHandler#TYPE_DATAGRAM_LEN}).
     *
     * @param out the destination buffer
     * @param payload the datagram payload
     */
    public static void writeDatagram(ByteBuffer out, byte[] payload) {
        VarInt.encode(QuicFrameHandler.TYPE_DATAGRAM_LEN, out);
        VarInt.encode(payload.length, out);
        out.put(payload);
    }

    /**
     * Returns the encoded length of a DATAGRAM frame without a Length
     * field (RFC 9221 type {@link QuicFrameHandler#TYPE_DATAGRAM}): the
     * payload occupies the remainder of the packet.
     *
     * @param payloadLength the payload size in bytes
     * @return the encoded length in bytes, including type
     */
    public static int datagramWithoutLengthLength(int payloadLength) {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_DATAGRAM) + payloadLength;
    }

    /**
     * Writes a DATAGRAM frame without a Length field (RFC 9221 type
     * {@link QuicFrameHandler#TYPE_DATAGRAM}). The payload is the
     * remainder of the packet, so this must be the last frame written.
     *
     * @param out the destination buffer
     * @param payload the datagram payload
     */
    public static void writeDatagramWithoutLength(ByteBuffer out, byte[] payload) {
        VarInt.encode(QuicFrameHandler.TYPE_DATAGRAM, out);
        out.put(payload);
    }
}
