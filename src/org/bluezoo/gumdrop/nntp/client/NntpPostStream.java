/*
 * NntpPostStream.java
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

package org.bluezoo.gumdrop.nntp.client;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.StorageExecutor;

/**
 * Where the article of a streaming POST is written. What is written is the
 * article as it should be stored: the stream turns line endings into CRLF,
 * doubles a dot that begins a line (RFC 3977 section 3.1.1) and, on
 * {@link #finish}, ends the article with the line that holds a single dot.
 * It keeps nothing but the position in the current line, so an article of
 * any size can be written in constant memory.
 *
 * <p>The stream does not queue. Each {@link #write} hands the data to the
 * connection and calls back when the connection has taken it all; the next
 * chunk is written then, so memory use does not depend on how fast the
 * server reads. Only one write may be outstanding at a time.
 *
 * <p>The methods may be called from any thread; the work is done, and the
 * callbacks are made, on the connection's event loop.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see NntpSession#post(NntpPoster, NntpCallback)
 */
public final class NntpPostStream {

    /** The most input encoded into one write to the connection. */
    private static final int SLICE = 8192;

    private final NntpClientProtocolHandler connection;
    private final Endpoint endpoint;

    // loop thread only
    private boolean lineStart = true;
    private boolean pendingCR;
    private boolean writing;
    private boolean closed;

    NntpPostStream(NntpClientProtocolHandler connection, Endpoint endpoint) {
        this.connection = connection;
        this.endpoint = endpoint;
    }

    /**
     * Returns whether the article can still be written: false once it has
     * been finished, or the post has failed or the connection has gone.
     *
     * @return true if data written will be sent
     */
    public boolean isOpen() {
        return !closed;
    }

    /**
     * Writes the next part of the article. Line endings of any kind become
     * CRLF. The buffer is consumed, and may be reused when the call returns.
     *
     * @param data the bytes
     * @param written called when the connection has taken them and the next
     * write may be made; also called if the stream is closed, in which case
     * the data was dropped (see {@link #isOpen()}); may be null
     * @throws IllegalStateException if the previous write has not been
     * called back
     */
    public void write(final ByteBuffer data, final Runnable written) {
        endpoint.execute(new Runnable() {
            @Override
            public void run() {
                if (writing) {
                    throw new IllegalStateException("a write is outstanding");
                }
                if (closed) {
                    if (written != null) {
                        written.run();
                    }
                    return;
                }
                writing = true;
                while (data.hasRemaining()) {
                    encode(data);
                }
                endpoint.onWriteReady(new Runnable() {
                    @Override
                    public void run() {
                        writing = false;
                        if (written != null) {
                            written.run();
                        }
                    }
                });
            }
        });
    }

    /**
     * Ends the article, which the server then accepts or refuses; the
     * outcome goes to the callback of the post. Nothing may be written
     * afterwards.
     */
    public void finish() {
        endpoint.execute(new Runnable() {
            @Override
            public void run() {
                if (closed) {
                    return;
                }
                closed = true;
                byte[] end = new byte[5];
                int n = 0;
                if (pendingCR) {
                    end[n++] = '\r';
                    end[n++] = '\n';
                } else if (!lineStart) {
                    end[n++] = '\r';
                    end[n++] = '\n';
                }
                end[n++] = '.';
                end[n++] = '\r';
                end[n++] = '\n';
                endpoint.send(ByteBuffer.wrap(end, 0, n));
                connection.postFinished();
            }
        });
    }

    /**
     * Gives up on the article. NNTP has no way to cancel a POST once the
     * server has said to send it, so the connection is closed and the post
     * fails.
     *
     * @param reason why, for the error given to the post's callback
     */
    public void abort(final String reason) {
        endpoint.execute(new Runnable() {
            @Override
            public void run() {
                closed = true;
                connection.postAborted(reason);
            }
        });
    }

    /** The post is over (answered early, or failed): further data is dropped. */
    void close() {
        closed = true;
    }

    /** The storage executor of the connection's runtime, for reading files; null if none. */
    StorageExecutor storage() {
        org.bluezoo.gumdrop.SelectorLoop loop = endpoint.getSelectorLoop();
        org.bluezoo.gumdrop.Gumdrop gumdrop = loop != null ? loop.getGumdrop() : null;
        return gumdrop != null ? gumdrop.getStorageExecutor() : null;
    }

    /** Runs a task on the connection's event loop. */
    void execute(Runnable task) {
        endpoint.execute(task);
    }

    /** Encodes up to a slice of the input and sends it. */
    private void encode(ByteBuffer in) {
        int n = Math.min(in.remaining(), SLICE);
        // every input byte gives at most two output bytes, and a held CR two more
        byte[] out = new byte[2 * n + 2];
        int op = 0;
        for (int i = 0; i < n; i++) {
            byte b = in.get();
            if (pendingCR) {
                pendingCR = false;
                out[op++] = '\r';
                out[op++] = '\n';
                lineStart = true;
                if (b == '\n') {
                    continue; // the CR and this LF were one line ending
                }
            }
            if (b == '\r') {
                pendingCR = true;
            } else if (b == '\n') {
                out[op++] = '\r';
                out[op++] = '\n';
                lineStart = true;
            } else {
                if (lineStart && b == '.') {
                    out[op++] = '.';
                }
                out[op++] = b;
                lineStart = false;
            }
        }
        endpoint.send(ByteBuffer.wrap(out, 0, op));
    }

}
