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
 * <p>ACK frames are always written with zero additional ranges and no
 * ECN counts (type {@link QuicFrameHandler#TYPE_ACK}); see
 * {@link QuicFrameHandler#ackFrameReceived} for why that is sufficient
 * for a connection with no packet loss.
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
     * Returns the encoded length of a single-range ACK frame.
     *
     * @param largestAcknowledged the largest packet number being acknowledged
     * @param ackDelay the ACK Delay field
     * @param firstAckRange the number of contiguous packets below
     *                      {@code largestAcknowledged} also being acknowledged
     * @return the encoded length in bytes
     */
    public static int ackLength(long largestAcknowledged, long ackDelay, long firstAckRange) {
        return VarInt.encodedLength(QuicFrameHandler.TYPE_ACK)
                + VarInt.encodedLength(largestAcknowledged)
                + VarInt.encodedLength(ackDelay)
                + VarInt.encodedLength(0L)
                + VarInt.encodedLength(firstAckRange);
    }

    /**
     * Writes a single-range ACK frame (RFC 9000 section 19.3), with an
     * ACK Range Count of zero (no additional ranges) and no ECN counts.
     *
     * @param out the destination buffer
     * @param largestAcknowledged the largest packet number being acknowledged
     * @param ackDelay the ACK Delay field
     * @param firstAckRange the number of contiguous packets below
     *                      {@code largestAcknowledged} also being acknowledged
     */
    public static void writeAck(ByteBuffer out, long largestAcknowledged, long ackDelay,
            long firstAckRange) {
        VarInt.encode(QuicFrameHandler.TYPE_ACK, out);
        VarInt.encode(largestAcknowledged, out);
        VarInt.encode(ackDelay, out);
        VarInt.encode(0L, out);
        VarInt.encode(firstAckRange, out);
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
     * Returns the encoded length of a CONNECTION_CLOSE frame.
     *
     * @param applicationError true for an application-level close
     *                         ({@link QuicFrameHandler#TYPE_CONNECTION_CLOSE_APP}),
     *                         false for a transport-level close
     * @param reason the human-readable reason phrase
     * @return the encoded length in bytes
     */
    public static int connectionCloseLength(boolean applicationError, String reason) {
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        long type = applicationError
                ? QuicFrameHandler.TYPE_CONNECTION_CLOSE_APP
                : QuicFrameHandler.TYPE_CONNECTION_CLOSE;
        long length = VarInt.encodedLength(type)
                + VarInt.encodedLength(0L) // error code
                + VarInt.encodedLength(reasonBytes.length)
                + reasonBytes.length;
        if (!applicationError) {
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
}
