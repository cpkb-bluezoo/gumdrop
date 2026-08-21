/*
 * H3Writer.java
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

package org.bluezoo.gumdrop.http.h3;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.quic.packet.VarInt;

/**
 * Writes HTTP/3 frames (RFC 9114 section 7) into a {@link ByteBuffer}.
 *
 * <p>Unlike {@code org.bluezoo.gumdrop.http.h2.H2Writer}, which owns a
 * {@code WritableByteChannel} and its own send buffer, this follows
 * {@code org.bluezoo.gumdrop.quic.frame.QuicFrameWriter}'s shape
 * instead: stateless static methods writing into a caller-supplied
 * buffer, each paired with an {@code xxxLength} method so the exact
 * encoded size can be computed up front. That is the better fit here,
 * since HTTP/3 frame bytes are ultimately handed off to whatever buffers
 * pending data for a QUIC stream (via STREAM frames), not written
 * directly to a channel the way HTTP/2 writes to its TCP connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see H3Parser
 */
public final class H3Writer {

    private H3Writer() {
    }

    /**
     * Returns the encoded length of the unidirectional stream type
     * prefix (RFC 9114 section 6.2) for the given stream type, e.g.
     * {@code 0x00} for a control stream.
     *
     * @param streamType the stream type
     * @return the encoded length in bytes
     */
    public static int streamTypeLength(long streamType) {
        return VarInt.encodedLength(streamType);
    }

    /**
     * Writes the unidirectional stream type prefix (RFC 9114 section
     * 6.2) that must be the first bytes sent on any unidirectional
     * stream.
     *
     * @param out the destination buffer
     * @param streamType the stream type
     */
    public static void writeStreamType(ByteBuffer out, long streamType) {
        VarInt.encode(streamType, out);
    }

    /**
     * Returns the encoded length of a DATA frame carrying
     * {@code dataLength} bytes.
     *
     * @param dataLength the number of payload bytes
     * @return the encoded length in bytes
     */
    public static int dataLength(int dataLength) {
        return VarInt.encodedLength(H3FrameHandler.TYPE_DATA)
                + VarInt.encodedLength(dataLength)
                + dataLength;
    }

    /**
     * Writes a DATA frame (RFC 9114 section 7.2.1).
     *
     * @param out the destination buffer
     * @param data the payload bytes
     */
    public static void writeData(ByteBuffer out, byte[] data) {
        VarInt.encode(H3FrameHandler.TYPE_DATA, out);
        VarInt.encode(data.length, out);
        out.put(data);
    }

    /**
     * Returns the encoded length of a HEADERS frame carrying a
     * QPACK-encoded field section of {@code encodedFieldSectionLength} bytes.
     *
     * @param encodedFieldSectionLength the length of the encoded field section
     * @return the encoded length in bytes
     */
    public static int headersLength(int encodedFieldSectionLength) {
        return VarInt.encodedLength(H3FrameHandler.TYPE_HEADERS)
                + VarInt.encodedLength(encodedFieldSectionLength)
                + encodedFieldSectionLength;
    }

    /**
     * Writes a HEADERS frame (RFC 9114 section 7.2.2).
     *
     * @param out the destination buffer
     * @param encodedFieldSection the QPACK-encoded field section
     */
    public static void writeHeaders(ByteBuffer out, byte[] encodedFieldSection) {
        VarInt.encode(H3FrameHandler.TYPE_HEADERS, out);
        VarInt.encode(encodedFieldSection.length, out);
        out.put(encodedFieldSection);
    }

    /**
     * Returns the encoded length of a CANCEL_PUSH frame.
     *
     * @param pushId the push ID being cancelled
     * @return the encoded length in bytes
     */
    public static int cancelPushLength(long pushId) {
        int payloadLength = VarInt.encodedLength(pushId);
        return VarInt.encodedLength(H3FrameHandler.TYPE_CANCEL_PUSH)
                + VarInt.encodedLength(payloadLength)
                + payloadLength;
    }

    /**
     * Writes a CANCEL_PUSH frame (RFC 9114 section 7.2.3).
     *
     * @param out the destination buffer
     * @param pushId the push ID being cancelled
     */
    public static void writeCancelPush(ByteBuffer out, long pushId) {
        VarInt.encode(H3FrameHandler.TYPE_CANCEL_PUSH, out);
        VarInt.encode(VarInt.encodedLength(pushId), out);
        VarInt.encode(pushId, out);
    }

    /**
     * Returns the encoded length of a SETTINGS frame carrying the given
     * identifier/value pairs.
     *
     * @param settings the setting identifier/value pairs, alternating
     *                 (as passed to {@link #writeSettings})
     * @return the encoded length in bytes
     */
    public static int settingsLength(long[] settings) {
        int payloadLength = 0;
        for (long value : settings) {
            payloadLength += VarInt.encodedLength(value);
        }
        return VarInt.encodedLength(H3FrameHandler.TYPE_SETTINGS)
                + VarInt.encodedLength(payloadLength)
                + payloadLength;
    }

    /**
     * Writes a SETTINGS frame (RFC 9114 section 7.2.4).
     *
     * @param out the destination buffer
     * @param settings the setting identifier/value pairs, alternating:
     *                 {@code [id0, value0, id1, value1, ...]}
     */
    public static void writeSettings(ByteBuffer out, long[] settings) {
        int payloadLength = 0;
        for (long value : settings) {
            payloadLength += VarInt.encodedLength(value);
        }
        VarInt.encode(H3FrameHandler.TYPE_SETTINGS, out);
        VarInt.encode(payloadLength, out);
        for (long value : settings) {
            VarInt.encode(value, out);
        }
    }

    /**
     * Returns the encoded length of a PUSH_PROMISE frame.
     *
     * @param pushId the push ID being promised
     * @param encodedFieldSectionLength the length of the encoded promised request field section
     * @return the encoded length in bytes
     */
    public static int pushPromiseLength(long pushId, int encodedFieldSectionLength) {
        int payloadLength = VarInt.encodedLength(pushId) + encodedFieldSectionLength;
        return VarInt.encodedLength(H3FrameHandler.TYPE_PUSH_PROMISE)
                + VarInt.encodedLength(payloadLength)
                + payloadLength;
    }

    /**
     * Writes a PUSH_PROMISE frame (RFC 9114 section 7.2.5).
     *
     * @param out the destination buffer
     * @param pushId the push ID being promised
     * @param encodedFieldSection the QPACK-encoded promised request field section
     */
    public static void writePushPromise(ByteBuffer out, long pushId, byte[] encodedFieldSection) {
        int payloadLength = VarInt.encodedLength(pushId) + encodedFieldSection.length;
        VarInt.encode(H3FrameHandler.TYPE_PUSH_PROMISE, out);
        VarInt.encode(payloadLength, out);
        VarInt.encode(pushId, out);
        out.put(encodedFieldSection);
    }

    /**
     * Returns the encoded length of a GOAWAY frame.
     *
     * @param streamOrPushId the stream ID or push ID being sent
     * @return the encoded length in bytes
     */
    public static int goawayLength(long streamOrPushId) {
        int payloadLength = VarInt.encodedLength(streamOrPushId);
        return VarInt.encodedLength(H3FrameHandler.TYPE_GOAWAY)
                + VarInt.encodedLength(payloadLength)
                + payloadLength;
    }

    /**
     * Writes a GOAWAY frame (RFC 9114 section 7.2.6).
     *
     * @param out the destination buffer
     * @param streamOrPushId a client-initiated bidirectional stream ID
     *                       (server-to-client direction) or a push ID
     *                       (client-to-server direction)
     */
    public static void writeGoaway(ByteBuffer out, long streamOrPushId) {
        VarInt.encode(H3FrameHandler.TYPE_GOAWAY, out);
        VarInt.encode(VarInt.encodedLength(streamOrPushId), out);
        VarInt.encode(streamOrPushId, out);
    }

    /**
     * Returns the encoded length of a MAX_PUSH_ID frame.
     *
     * @param maxPushId the new maximum push ID
     * @return the encoded length in bytes
     */
    public static int maxPushIdLength(long maxPushId) {
        int payloadLength = VarInt.encodedLength(maxPushId);
        return VarInt.encodedLength(H3FrameHandler.TYPE_MAX_PUSH_ID)
                + VarInt.encodedLength(payloadLength)
                + payloadLength;
    }

    /**
     * Writes a MAX_PUSH_ID frame (RFC 9114 section 7.2.7).
     *
     * @param out the destination buffer
     * @param maxPushId the new maximum push ID the server may use
     */
    public static void writeMaxPushId(ByteBuffer out, long maxPushId) {
        VarInt.encode(H3FrameHandler.TYPE_MAX_PUSH_ID, out);
        VarInt.encode(VarInt.encodedLength(maxPushId), out);
        VarInt.encode(maxPushId, out);
    }

    /**
     * Returns the encoded length of a PRIORITY_UPDATE frame for a request
     * stream (RFC 9218 section 7.2).
     *
     * @param streamId the client-initiated bidirectional stream ID
     * @param fieldValueLength the length of the Priority Field Value
     * @return the encoded length in bytes
     */
    public static int priorityUpdateRequestLength(long streamId, int fieldValueLength) {
        int payloadLength = VarInt.encodedLength(streamId) + fieldValueLength;
        return VarInt.encodedLength(H3FrameHandler.TYPE_PRIORITY_UPDATE_REQUEST)
                + VarInt.encodedLength(payloadLength)
                + payloadLength;
    }

    /**
     * Writes a PRIORITY_UPDATE frame for a request stream (RFC 9218
     * section 7.2).
     *
     * @param out the destination buffer
     * @param streamId the client-initiated bidirectional stream ID
     * @param fieldValue the Priority Field Value bytes
     */
    public static void writePriorityUpdateRequest(ByteBuffer out, long streamId, byte[] fieldValue) {
        int payloadLength = VarInt.encodedLength(streamId) + fieldValue.length;
        VarInt.encode(H3FrameHandler.TYPE_PRIORITY_UPDATE_REQUEST, out);
        VarInt.encode(payloadLength, out);
        VarInt.encode(streamId, out);
        out.put(fieldValue);
    }

    /**
     * Returns the encoded length of a PRIORITY_UPDATE frame for a push
     * stream (RFC 9218 section 7.2).
     *
     * @param pushId the push ID
     * @param fieldValueLength the length of the Priority Field Value
     * @return the encoded length in bytes
     */
    public static int priorityUpdatePushLength(long pushId, int fieldValueLength) {
        int payloadLength = VarInt.encodedLength(pushId) + fieldValueLength;
        return VarInt.encodedLength(H3FrameHandler.TYPE_PRIORITY_UPDATE_PUSH)
                + VarInt.encodedLength(payloadLength)
                + payloadLength;
    }

    /**
     * Writes a PRIORITY_UPDATE frame for a push stream (RFC 9218 section 7.2).
     *
     * @param out the destination buffer
     * @param pushId the push ID
     * @param fieldValue the Priority Field Value bytes
     */
    public static void writePriorityUpdatePush(ByteBuffer out, long pushId, byte[] fieldValue) {
        int payloadLength = VarInt.encodedLength(pushId) + fieldValue.length;
        VarInt.encode(H3FrameHandler.TYPE_PRIORITY_UPDATE_PUSH, out);
        VarInt.encode(payloadLength, out);
        VarInt.encode(pushId, out);
        out.put(fieldValue);
    }
}
