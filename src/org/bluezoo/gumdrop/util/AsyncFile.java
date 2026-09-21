/*
 * AsyncFile.java
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

package org.bluezoo.gumdrop.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.OpenOption;
import java.nio.file.Path;

import org.bluezoo.gumdrop.StorageExecutor;

/**
 * A file read and written with positional, callback-completed operations, in
 * the manner of {@link AsynchronousFileChannel} but usable on any file
 * system.
 *
 * <p>{@link #open} returns a real {@code AsynchronousFileChannel} wrapper
 * whenever the file system provider supports one (the default file system
 * does). Providers that do not (a zip or in-memory file system, for
 * instance) make {@code AsynchronousFileChannel.open} throw
 * {@link UnsupportedOperationException}; for those, {@code open} returns an
 * equivalent that performs blocking positional reads and writes on the given
 * {@link StorageExecutor}, or on the calling thread when there is none.
 * Callers see the same contract either way.
 *
 * <p>As with {@code AsynchronousFileChannel}, completion handlers run on a
 * thread that belongs to neither the caller nor an endpoint's SelectorLoop,
 * so a handler that touches per-connection state must marshal back to its
 * loop (for example with {@link org.bluezoo.gumdrop.Endpoint#execute}).
 * Opening a file is itself a blocking operation and, like
 * {@code AsynchronousFileChannel.open}, must not be done on a SelectorLoop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface AsyncFile extends Closeable {

    /**
     * Opens a file for asynchronous access. Blocking: call only from a
     * {@link StorageExecutor} worker or another non-loop thread.
     *
     * @param storage the executor that runs blocking reads and writes when
     *        the file system has no asynchronous channels, or null to run
     *        them on the calling thread; unused for file systems that do
     * @param path the file to open
     * @param options how to open it, as for {@code Files.newByteChannel}
     * @return the open file
     * @throws IOException if the file cannot be opened
     */
    static AsyncFile open(StorageExecutor storage, Path path, OpenOption... options)
            throws IOException {
        AsynchronousFileChannel channel;
        try {
            channel = AsynchronousFileChannel.open(path, options);
        } catch (UnsupportedOperationException e) {
            return BlockingAsyncFile.open(storage, path, options);
        }
        return new NativeAsyncFile(channel);
    }

    /**
     * Reads a sequence of bytes starting at a file position.
     *
     * @param dst the buffer to read into
     * @param position the file position at which to start
     * @param attachment passed unchanged to the handler
     * @param handler told the number of bytes read, or -1 at end of file
     * @param <A> the attachment type
     */
    <A> void read(ByteBuffer dst, long position, A attachment,
            CompletionHandler<Integer, ? super A> handler);

    /**
     * Writes a sequence of bytes starting at a file position.
     *
     * @param src the buffer to write from
     * @param position the file position at which to start
     * @param attachment passed unchanged to the handler
     * @param handler told the number of bytes written
     * @param <A> the attachment type
     */
    <A> void write(ByteBuffer src, long position, A attachment,
            CompletionHandler<Integer, ? super A> handler);

    /**
     * Returns the current size of the file in bytes.
     *
     * @throws IOException if the size cannot be determined
     */
    long size() throws IOException;

    /**
     * Closes the file. Closing an already closed file has no effect.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    void close() throws IOException;
}
