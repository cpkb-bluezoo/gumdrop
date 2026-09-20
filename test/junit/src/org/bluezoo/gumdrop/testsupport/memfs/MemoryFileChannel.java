/*
 * MemoryFileChannel.java
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

package org.bluezoo.gumdrop.testsupport.memfs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * {@link FileChannel} over a {@link MemoryNode}. Memory mapping is not
 * supported; locks are granted unconditionally.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MemoryFileChannel extends FileChannel {

    private final MemoryFileSystem fs;
    private final MemoryNode node;
    private final boolean readable;
    private final boolean writable;
    private final boolean append;
    private long position;

    MemoryFileChannel(MemoryFileSystem fs, MemoryNode node, boolean readable,
            boolean writable, boolean append) {
        this.fs = fs;
        this.node = node;
        this.readable = readable;
        this.writable = writable;
        this.append = append;
    }

    private void checkRead() throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        if (!readable) {
            throw new NonReadableChannelException();
        }
    }

    private void checkWrite() throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        if (!writable) {
            throw new NonWritableChannelException();
        }
    }

    private int readAt(ByteBuffer dst, long pos) {
        if (pos >= node.size) {
            return -1;
        }
        int n = (int) Math.min(dst.remaining(), node.size - pos);
        dst.put(node.data, (int) pos, n);
        node.accessed = fs.tick();
        return n;
    }

    private int writeAt(ByteBuffer src, long pos) {
        int n = src.remaining();
        int end = (int) (pos + n);
        node.ensureCapacity(end);
        if (pos > node.size) {
            java.util.Arrays.fill(node.data, node.size, (int) pos, (byte) 0);
        }
        src.get(node.data, (int) pos, n);
        if (end > node.size) {
            node.size = end;
        }
        node.modified = fs.tick();
        return n;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        synchronized (fs.lock) {
            checkRead();
            if (!dst.hasRemaining()) {
                return 0;
            }
            int n = readAt(dst, position);
            if (n > 0) {
                position += n;
            }
            return n;
        }
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        long total = 0;
        for (int i = offset; i < offset + length; i++) {
            if (!dsts[i].hasRemaining()) {
                continue;
            }
            int n = read(dsts[i]);
            if (n < 0) {
                return total == 0 ? -1 : total;
            }
            total += n;
            if (dsts[i].hasRemaining()) {
                break;
            }
        }
        return total;
    }

    @Override
    public int read(ByteBuffer dst, long pos) throws IOException {
        if (pos < 0) {
            throw new IllegalArgumentException("Negative position");
        }
        synchronized (fs.lock) {
            checkRead();
            return dst.hasRemaining() ? readAt(dst, pos) : 0;
        }
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        synchronized (fs.lock) {
            checkWrite();
            if (append) {
                position = node.size;
            }
            int n = writeAt(src, position);
            position += n;
            return n;
        }
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        long total = 0;
        for (int i = offset; i < offset + length; i++) {
            total += write(srcs[i]);
        }
        return total;
    }

    @Override
    public int write(ByteBuffer src, long pos) throws IOException {
        if (pos < 0) {
            throw new IllegalArgumentException("Negative position");
        }
        synchronized (fs.lock) {
            checkWrite();
            return writeAt(src, pos);
        }
    }

    @Override
    public long position() throws IOException {
        synchronized (fs.lock) {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
            return position;
        }
    }

    @Override
    public FileChannel position(long newPosition) throws IOException {
        if (newPosition < 0) {
            throw new IllegalArgumentException("Negative position");
        }
        synchronized (fs.lock) {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
            position = newPosition;
            return this;
        }
    }

    @Override
    public long size() throws IOException {
        synchronized (fs.lock) {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
            return node.size;
        }
    }

    @Override
    public FileChannel truncate(long size) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException("Negative size");
        }
        synchronized (fs.lock) {
            checkWrite();
            if (size < node.size) {
                node.truncate((int) size);
                node.modified = fs.tick();
            }
            if (position > size) {
                position = size;
            }
            return this;
        }
    }

    @Override
    public void force(boolean metaData) throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    @Override
    public long transferTo(long pos, long count, WritableByteChannel target)
            throws IOException {
        byte[] chunk;
        synchronized (fs.lock) {
            checkRead();
            if (pos >= node.size) {
                return 0;
            }
            int n = (int) Math.min(count, node.size - pos);
            chunk = new byte[n];
            System.arraycopy(node.data, (int) pos, chunk, 0, n);
        }
        ByteBuffer buf = ByteBuffer.wrap(chunk);
        long written = 0;
        while (buf.hasRemaining()) {
            int n = target.write(buf);
            if (n <= 0) {
                break;
            }
            written += n;
        }
        return written;
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long pos, long count)
            throws IOException {
        checkWrite();
        ByteBuffer buf = ByteBuffer.allocate((int) Math.min(count, 8192));
        long total = 0;
        while (total < count) {
            buf.clear();
            buf.limit((int) Math.min(buf.capacity(), count - total));
            int n = src.read(buf);
            if (n <= 0) {
                break;
            }
            buf.flip();
            write(buf, pos + total);
            total += n;
        }
        return total;
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long pos, long size) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileLock lock(long pos, long size, boolean shared) throws IOException {
        return tryLock(pos, size, shared);
    }

    @Override
    public FileLock tryLock(long pos, long size, boolean shared) throws IOException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
        return new MemoryFileLock(this, pos, size, shared);
    }

    @Override
    protected void implCloseChannel() throws IOException {
    }

    /** Lock that is valid until released or its channel is closed. */
    private static final class MemoryFileLock extends FileLock {

        private boolean released;

        MemoryFileLock(FileChannel channel, long position, long size, boolean shared) {
            super(channel, position, size, shared);
        }

        @Override
        public boolean isValid() {
            return !released && channel().isOpen();
        }

        @Override
        public void release() throws IOException {
            released = true;
        }
    }
}
