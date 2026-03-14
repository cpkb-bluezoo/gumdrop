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
     * Parses the gRPC frame header from the buffer.
     *
     * @param buffer the buffer (position at start of frame)
     * @return the message length, or -1 if header is incomplete
     */
    public static int readHeader(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            return -1;
        }
        buffer.get(); // compressed flag
        return ((buffer.get() & 0xFF) << 24)
                | ((buffer.get() & 0xFF) << 16)
                | ((buffer.get() & 0xFF) << 8)
                | (buffer.get() & 0xFF);
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
