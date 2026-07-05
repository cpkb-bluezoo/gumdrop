/*
 * GrpcFraming.java
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

/**
 * gRPC message framing (5-byte prefix).
 *
 * <p>Each gRPC message is prefixed with:
 * <ul>
 *   <li>1 byte: compressed flag (0 = uncompressed, 1 = compressed)</li>
 *   <li>4 bytes: message length (big-endian)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md">gRPC over HTTP/2</a>
 */
public final class GrpcFraming {

    private static final int HEADER_SIZE = 5;
    private static final byte UNCOMPRESSED = 0;

    /**
     * Default maximum gRPC message payload size: 4 MB (common gRPC default).
     * Deployments can raise this or set {@code 0} (unlimited) via
     * {@link org.bluezoo.gumdrop.grpc.server.GrpcHandlerFactory#setMaxMessageSize(long)}.
     */
    public static final long DEFAULT_MAX_MESSAGE_SIZE = 4L * 1024 * 1024;

    private GrpcFraming() {
    }

    /**
     * Wraps a message with the gRPC 5-byte prefix.
     *
     * @param message the message bytes
     * @return a new buffer containing the framed message (flipped for reading)
     */
    public static ByteBuffer frame(ByteBuffer message) {
        int length = message.remaining();
        ByteBuffer framed = ByteBuffer.allocate(HEADER_SIZE + length);
        framed.put(UNCOMPRESSED);
        framed.put((byte) (length >>> 24));
        framed.put((byte) (length >>> 16));
        framed.put((byte) (length >>> 8));
        framed.put((byte) length);
        framed.put(message);
        framed.flip();
        return framed;
    }

    /**
     * Wraps a message with the gRPC 5-byte prefix.
     *
     * @param message the message bytes
     * @return a new buffer containing the framed message (flipped for reading)
     */
    public static ByteBuffer frame(byte[] message) {
        return frame(ByteBuffer.wrap(message));
    }

    /**
     * Returns the total size of a framed message (header + payload).
     *
     * @param payloadLength the payload length
     * @return HEADER_SIZE + payloadLength
     */
    public static int framedSize(int payloadLength) {
        return HEADER_SIZE + payloadLength;
    }

    /**
     * Parses the gRPC frame header from the buffer with no payload-size limit
     * (except {@link Integer#MAX_VALUE}).
     *
     * @param buffer the buffer (position at start of frame)
     * @return the message length, or -1 if header is incomplete
     * @throws GrpcException if the declared length exceeds {@code maxMessageLength}
     */
    public static int readHeader(ByteBuffer buffer) {
        return readHeader(buffer, Long.MAX_VALUE);
    }

    /**
     * Parses the gRPC frame header from the buffer.
     *
     * @param buffer the buffer (position at start of frame)
     * @param maxMessageLength maximum permitted message payload bytes
     *        ({@link Long#MAX_VALUE} = no limit except {@link Integer#MAX_VALUE})
     * @return the message length, or -1 if header is incomplete
     * @throws GrpcException if the declared length exceeds {@code maxMessageLength}
     */
    public static int readHeader(ByteBuffer buffer, long maxMessageLength) {
        if (buffer.remaining() < HEADER_SIZE) {
            return -1;
        }
        buffer.get(); // compressed flag
        long length = ((long) (buffer.get() & 0xFF) << 24)
                | ((long) (buffer.get() & 0xFF) << 16)
                | ((long) (buffer.get() & 0xFF) << 8)
                | (buffer.get() & 0xFF);
        if (length > Integer.MAX_VALUE) {
            throw new GrpcException("gRPC message length exceeds maximum "
                    + Integer.MAX_VALUE);
        }
        int messageLength = (int) length;
        if (maxMessageLength > 0 && messageLength > maxMessageLength) {
            throw new GrpcException("gRPC message length " + messageLength
                    + " exceeds maximum " + maxMessageLength);
        }
        return messageLength;
    }

    /**
     * Skips the frame header (call when buffer position is at frame start).
     *
     * @param buffer the buffer
     */
    public static void skipHeader(ByteBuffer buffer) {
        buffer.position(buffer.position() + HEADER_SIZE);
    }
}
