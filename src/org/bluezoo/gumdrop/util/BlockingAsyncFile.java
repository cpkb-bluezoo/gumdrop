/*
 * BlockingAsyncFile.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

import org.bluezoo.gumdrop.StorageExecutor;

/**
 * {@link AsyncFile} for file systems without asynchronous channels: each
 * operation is a blocking positional call on a {@link FileChannel}, run on a
 * {@link StorageExecutor} worker so the caller never blocks.
 *
 * <p>With no executor the operation runs on the calling thread. Handlers
 * commonly start the next operation from inside their own completion, so
 * inline operations are trampolined: an operation started from within a
 * handler is queued and run by the outermost call rather than nested inside
 * it, which keeps a long sequence of reads from overflowing the stack.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class BlockingAsyncFile implements AsyncFile {

    /** Runs a command on whichever thread completes the storage work. */
    private static final Executor SAME_THREAD = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private final FileChannel channel;
    private final StorageExecutor storage;
    private final ArrayDeque<Runnable> inlineQueue = new ArrayDeque<Runnable>();
    private boolean draining;

    private BlockingAsyncFile(FileChannel channel, StorageExecutor storage) {
        this.channel = channel;
        this.storage = storage;
    }

    static BlockingAsyncFile open(StorageExecutor storage, Path path,
            OpenOption... options) throws IOException {
        return new BlockingAsyncFile(FileChannel.open(path, options), storage);
    }

    @Override
    public <A> void read(final ByteBuffer dst, final long position,
            A attachment, CompletionHandler<Integer, ? super A> handler) {
        run(new Callable<Integer>() {
            @Override
            public Integer call() throws IOException {
                return Integer.valueOf(channel.read(dst, position));
            }
        }, attachment, handler);
    }

    @Override
    public <A> void write(final ByteBuffer src, final long position,
            A attachment, CompletionHandler<Integer, ? super A> handler) {
        run(new Callable<Integer>() {
            @Override
            public Integer call() throws IOException {
                return Integer.valueOf(channel.write(src, position));
            }
        }, attachment, handler);
    }

    private <A> void run(final Callable<Integer> operation, final A attachment,
            final CompletionHandler<Integer, ? super A> handler) {
        if (storage != null) {
            storage.submit(SAME_THREAD, operation,
                    new StorageExecutor.Callback<Integer>() {
                @Override
                public void completed(Integer result) {
                    handler.completed(result, attachment);
                }

                @Override
                public void failed(Throwable error) {
                    handler.failed(error, attachment);
                }
            });
            return;
        }
        runInline(new Runnable() {
            @Override
            public void run() {
                Integer result;
                try {
                    result = operation.call();
                } catch (Throwable error) {
                    handler.failed(error, attachment);
                    return;
                }
                handler.completed(result, attachment);
            }
        });
    }

    /**
     * Runs {@code task} now, unless another inline task is already running
     * (possibly on another thread), in which case it is queued and that task's
     * caller runs it in turn.
     */
    private void runInline(Runnable task) {
        synchronized (inlineQueue) {
            inlineQueue.add(task);
            if (draining) {
                return;
            }
            draining = true;
        }
        try {
            while (true) {
                Runnable next;
                synchronized (inlineQueue) {
                    next = inlineQueue.poll();
                    if (next == null) {
                        draining = false;
                        return;
                    }
                }
                next.run();
            }
        } finally {
            // Reached with work still queued only when a handler threw; the
            // exception propagates to the caller and the next operation
            // resumes draining instead of finding the queue blocked.
            synchronized (inlineQueue) {
                draining = false;
            }
        }
    }

    @Override
    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
