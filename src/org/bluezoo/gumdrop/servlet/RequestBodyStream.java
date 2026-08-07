/*
 * RequestBodyStream.java
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

package org.bluezoo.gumdrop.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;

/**
 * Bridges the async HTTP I/O layer (producer, called on the connection's
 * SelectorLoop thread) to the blocking {@code ServletInputStream} API
 * (consumer, on a worker thread) without ever blocking the producer.
 *
 * <p>This replaces a {@code PipedOutputStream}/{@code PipedInputStream}
 * pair that was previously used for the same purpose. {@code
 * PipedOutputStream.write()} blocks once its buffer is full until the
 * reader drains it — since the write happened directly inside {@code
 * ServletHandler.requestBodyContent()}, called on the SelectorLoop thread,
 * a servlet that was slow to read (or never read) its request body stalled
 * that entire thread, freezing every other connection multiplexed on the
 * same loop (issue #120).
 *
 * <p>{@link #offer(byte[])} never blocks: when the buffered byte count
 * exceeds a high-water mark it returns {@code true} so the caller can
 * apply backpressure via {@link
 * org.bluezoo.gumdrop.http.HTTPResponseState#pauseRequestBody()} instead.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class RequestBodyStream extends InputStream {

    /**
     * Buffered-byte threshold above which {@link #offer(byte[])} signals
     * the caller to pause. Chosen to comfortably hold a few HTTP/2 frames
     * or TCP reads while still bounding per-request memory to a small,
     * constant amount regardless of how far ahead of the servlet's reads
     * the network gets.
     */
    static final int HIGH_WATERMARK = 256 * 1024;

    /**
     * Buffered-byte threshold at or below which, once paused, {@link
     * #read(byte[], int, int)} signals the resume callback. Set below
     * {@link #HIGH_WATERMARK} to avoid rapidly toggling pause/resume.
     */
    static final int LOW_WATERMARK = 64 * 1024;

    private final Object lock = new Object();
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<byte[]>();
    private int chunkOffset;
    private int queuedBytes;
    private boolean closed;
    private boolean eof;
    private IOException error;
    private boolean paused;
    private Runnable resumeCallback;

    /**
     * Sets the callback invoked when buffered bytes drop back to {@link
     * #LOW_WATERMARK} after having been paused. May be invoked from
     * whichever thread calls {@link #read(byte[], int, int)} (a worker
     * thread) — the callback itself is responsible for marshalling back
     * onto the SelectorLoop thread before calling {@code
     * resumeRequestBody()}.
     */
    void setResumeCallback(Runnable callback) {
        this.resumeCallback = callback;
    }

    /**
     * Appends data to the buffer. Never blocks.
     *
     * @param data the data to append; must not be retained/mutated by the
     *      caller afterwards
     * @return true if the buffer is now at or above {@link
     *      #HIGH_WATERMARK} and the caller should pause request body
     *      delivery
     */
    boolean offer(byte[] data) {
        synchronized (lock) {
            if (closed || eof) {
                return false;
            }
            if (data.length > 0) {
                chunks.add(data);
                queuedBytes += data.length;
                lock.notifyAll();
            }
            if (queuedBytes >= HIGH_WATERMARK) {
                paused = true;
                return true;
            }
            return false;
        }
    }

    /** Signals that no more data will be offered (request body complete). */
    void finish() {
        synchronized (lock) {
            eof = true;
            lock.notifyAll();
        }
    }

    /** Signals a transport-level error, to be thrown from the next read. */
    void fail(IOException e) {
        synchronized (lock) {
            error = e;
            lock.notifyAll();
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : (one[0] & 0xFF);
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (buf == null) {
            throw new NullPointerException();
        }
        if (off < 0 || len < 0 || len > buf.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        Runnable toRun = null;
        int n;
        synchronized (lock) {
            while (chunks.isEmpty()) {
                if (error != null) {
                    IOException e = error;
                    error = null;
                    throw e;
                }
                if (eof || closed) {
                    return -1;
                }
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Interrupted while waiting for request body data", e);
                }
            }
            byte[] head = chunks.peekFirst();
            int available = head.length - chunkOffset;
            n = Math.min(available, len);
            System.arraycopy(head, chunkOffset, buf, off, n);
            chunkOffset += n;
            queuedBytes -= n;
            if (chunkOffset >= head.length) {
                chunks.removeFirst();
                chunkOffset = 0;
            }
            if (paused && queuedBytes <= LOW_WATERMARK) {
                paused = false;
                toRun = resumeCallback;
            }
        }
        // Run outside the lock: the callback marshals onto another thread
        // and must not do so while holding this stream's monitor.
        if (toRun != null) {
            toRun.run();
        }
        return n;
    }

    @Override
    public int available() {
        synchronized (lock) {
            return queuedBytes;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            chunks.clear();
            lock.notifyAll();
        }
    }
}
