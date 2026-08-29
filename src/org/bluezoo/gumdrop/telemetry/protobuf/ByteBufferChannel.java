/*
 * ByteBufferChannel.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.telemetry.protobuf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;

/**
 * A {@link WritableByteChannel} implementation that writes to an expandable
 * {@link ByteBuffer}.
 *
 * <p>This class is used with {@link ProtobufWriter} to serialize protobuf
 * messages to a buffer. The buffer automatically expands as needed to
 * accommodate all written data.
 *
 * <p>Example usage:
 * <pre>
 * ByteBufferChannel channel = new ByteBufferChannel(1024);
 * ProtobufWriter writer = new ProtobufWriter(channel);
 * writer.writeStringField(1, "hello");
 * ByteBuffer result = channel.toByteBuffer();
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ByteBufferChannel implements WritableByteChannel {

    private ByteBuffer buffer;
    private int leadingReserve;
    private boolean open;

    /**
     * Creates a new ByteBufferChannel with the specified initial capacity.
     *
     * @param initialCapacity the initial buffer capacity
     */
    public ByteBufferChannel(int initialCapacity) {
        this.buffer = ByteBuffer.allocate(initialCapacity);
        this.open = true;
    }

    /**
     * Creates a new ByteBufferChannel with a default initial capacity of 1KB.
     */
    public ByteBufferChannel() {
        this(1024);
    }

    /**
     * Creates a channel that leaves {@code leadingReserve} bytes at the start
     * of the buffer for a caller to fill in after serialization (for example
     * the gRPC 5-byte frame header).
     *
     * @param leadingReserve bytes to skip before the first payload write
     * @param initialPayloadCapacity initial capacity for the payload region
     */
    public static ByteBufferChannel withLeadingReserve(int leadingReserve,
            int initialPayloadCapacity) {
        if (leadingReserve < 0) {
            throw new IllegalArgumentException("leadingReserve must be >= 0");
        }
        if (initialPayloadCapacity < 0) {
            throw new IllegalArgumentException("initialPayloadCapacity must be >= 0");
        }
        ByteBufferChannel channel = new ByteBufferChannel(
                leadingReserve + initialPayloadCapacity);
        channel.leadingReserve = leadingReserve;
        channel.buffer.position(leadingReserve);
        return channel;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        if (!open) {
            throw new ClosedChannelException();
        }

        int length = src.remaining();
        if (length == 0) {
            return 0;
        }

        ensureCapacity(length);
        buffer.put(src);
        return length;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        open = false;
    }

    /**
     * Returns the data written so far as a ByteBuffer.
     *
     * <p>The returned buffer is flipped (ready for reading) and contains
     * all data written since the channel was created. The returned buffer
     * is a view of the internal buffer, so it should not be modified.
     *
     * @return the written data
     */
    public ByteBuffer toByteBuffer() {
        ByteBuffer result = buffer.duplicate();
        result.flip();
        return result;
    }

    /**
     * Returns the data written so far as a byte array.
     *
     * @return the written data
     */
    public byte[] toByteArray() {
        byte[] data = new byte[buffer.position()];
        buffer.flip();
        buffer.get(data);
        buffer.position(buffer.limit());
        buffer.limit(buffer.capacity());
        return data;
    }

    /**
     * Returns the number of bytes written so far.
     *
     * @return the byte count
     */
    public int size() {
        return buffer.position();
    }

    /**
     * Returns the number of payload bytes written so far, excluding any
     * leading reserve configured at creation.
     */
    public int payloadLength() {
        return buffer.position() - leadingReserve;
    }

    /**
     * Prepares the internal buffer for in-place header writing: limit is set
     * to the end of the written payload (including any leading reserve) and
     * position is reset to {@code 0}.
     *
     * @return the internal buffer, ready for a header at indices
     *         {@code 0 .. leadingReserve - 1}
     */
    public ByteBuffer finishWithLeadingReserve() {
        if (leadingReserve == 0) {
            throw new IllegalStateException("no leading reserve configured");
        }
        buffer.limit(buffer.position());
        buffer.position(0);
        return buffer;
    }

    /**
     * Resets the channel, clearing all written data.
     */
    public void reset() {
        buffer.clear();
    }

    /**
     * Ensures the buffer has at least the specified additional capacity.
     */
    private void ensureCapacity(int additionalBytes) {
        if (buffer.remaining() < additionalBytes) {
            int payloadEnd = buffer.position();
            int required = payloadEnd + additionalBytes;
            int newCapacity = buffer.capacity();

            while (newCapacity < required) {
                newCapacity = newCapacity * 2;
            }

            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
            if (leadingReserve > 0) {
                buffer.limit(payloadEnd);
                buffer.position(leadingReserve);
                newBuffer.position(leadingReserve);
                newBuffer.put(buffer);
                newBuffer.position(payloadEnd);
            } else {
                buffer.flip();
                newBuffer.put(buffer);
                newBuffer.position(payloadEnd);
            }
            buffer = newBuffer;
        }
    }
}

