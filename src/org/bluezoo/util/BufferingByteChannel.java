/*
 * BufferingByteChannel.java
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

package org.bluezoo.util;

import java.io.Flushable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * A {@link WritableByteChannel} decorator that buffers writes into a
 * fixed-size {@link ByteBuffer} before flushing to the underlying channel.
 *
 * <p>This reduces the number of blocking I/O syscalls when writing to
 * file-backed channels by accumulating small writes and flushing them
 * as larger chunks. The buffer is flushed automatically when full, or
 * explicitly via {@link #flush()}.
 *
 * <p>This is a pure NIO implementation with no {@code InputStream} or
 * {@code OutputStream} involvement.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BufferingByteChannel implements WritableByteChannel, Flushable {

    private final WritableByteChannel delegate;
    private final ByteBuffer buffer;
    private boolean open;

    /**
     * Creates a buffering channel that wraps the given delegate.
     *
     * @param delegate the underlying channel to write to
     * @param bufferSize the buffer size in bytes
     */
    public BufferingByteChannel(WritableByteChannel delegate, int bufferSize) {
        this.delegate = delegate;
        this.buffer = ByteBuffer.allocate(bufferSize);
        this.open = true;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        int totalWritten = 0;
        while (src.hasRemaining()) {
            int toCopy = Math.min(src.remaining(), buffer.remaining());
            if (toCopy > 0) {
                int srcLimit = src.limit();
                src.limit(src.position() + toCopy);
                buffer.put(src);
                src.limit(srcLimit);
                totalWritten += toCopy;
            }
            if (!buffer.hasRemaining()) {
                flushBuffer();
            }
        }
        return totalWritten;
    }

    @Override
    public void flush() throws IOException {
        if (buffer.position() > 0) {
            flushBuffer();
        }
    }

    private void flushBuffer() throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            delegate.write(buffer);
        }
        buffer.clear();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        if (open) {
            open = false;
            flush();
            delegate.close();
        }
    }
}
