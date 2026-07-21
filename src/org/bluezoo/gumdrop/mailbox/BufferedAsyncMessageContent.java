/*
 * BufferedAsyncMessageContent.java
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

package org.bluezoo.gumdrop.mailbox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;

/**
 * In-memory {@link AsyncMessageContent} backed by a byte array.
 *
 * <p>Used when a mailbox cannot open an {@link java.nio.channels.AsynchronousFileChannel}
 * (e.g. mbox): the full message is loaded on a {@code StorageExecutor} worker,
 * then streamed to the client from this buffer on the SelectorLoop without
 * further blocking disk I/O.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class BufferedAsyncMessageContent implements AsyncMessageContent {

    private static final byte CR = 0x0d;
    private static final byte LF = 0x0a;

    private final byte[] data;
    private final long bodyOffset;

    /**
     * Creates a buffered content reader over {@code data}.
     *
     * @param data the complete message bytes (headers + body); not copied
     */
    public BufferedAsyncMessageContent(byte[] data) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        this.data = data;
        this.bodyOffset = detectBodyOffset(data);
    }

    /**
     * Scans for the blank-line header/body boundary (CRLFCRLF or LFLF).
     *
     * @param data message bytes
     * @return body start offset, or {@code -1} if not found
     */
    public static long detectBodyOffset(byte[] data) {
        boolean lastWasLF = false;
        for (int i = 0; i < data.length; i++) {
            byte b = data[i];
            if (b == LF) {
                if (lastWasLF) {
                    return (long) i + 1;
                }
                lastWasLF = true;
            } else if (b != CR) {
                lastWasLF = false;
            }
        }
        return -1;
    }

    @Override
    public long size() {
        return data.length;
    }

    @Override
    public long bodyOffset() {
        return bodyOffset;
    }

    /**
     * Returns the underlying byte array (not a copy).
     *
     * @return message bytes
     */
    public byte[] getBytes() {
        return data;
    }

    @Override
    public void read(ByteBuffer dst, long position,
            CompletionHandler<Integer, ByteBuffer> handler) {
        if (position < 0) {
            handler.failed(new IllegalArgumentException(
                    "position < 0"), dst);
            return;
        }
        if (position >= data.length) {
            handler.completed(Integer.valueOf(-1), dst);
            return;
        }
        int toCopy = (int) Math.min(dst.remaining(),
                data.length - position);
        if (toCopy <= 0) {
            handler.completed(Integer.valueOf(0), dst);
            return;
        }
        dst.put(data, (int) position, toCopy);
        handler.completed(Integer.valueOf(toCopy), dst);
    }

    @Override
    public void close() throws IOException {
        // Nothing to release
    }
}
