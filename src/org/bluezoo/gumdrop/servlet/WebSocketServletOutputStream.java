/*
 * WebSocketServletOutputStream.java
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

package org.bluezoo.gumdrop.servlet;

import jakarta.servlet.WriteListener;
import jakarta.servlet.ServletOutputStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ServletOutputStream that sends data as WebSocket messages.
 *
 * <p>Data is buffered until {@link #flush()} is called, at which point the
 * buffered {@link ByteBuffer} is transferred to {@link ServletWebConnection}
 * without an extra copy. {@link #isReady()} reflects transport backpressure
 * from {@link ServletWebConnection#isResponseWritable()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class WebSocketServletOutputStream extends ServletOutputStream {

    private static final int INITIAL_BUFFER_SIZE = 8192;
    private static final ResourceBundle L10N =
        ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");
    private static final Logger LOGGER =
        Logger.getLogger(WebSocketServletOutputStream.class.getName());

    private final ServletWebConnection webConnection;
    private ByteBuffer buf;
    private WriteListener writeListener;
    private volatile boolean listenerNotified = true;
    private volatile boolean closed = false;

    WebSocketServletOutputStream(ServletWebConnection webConnection) {
        this.webConnection = webConnection;
        this.buf = ByteBuffer.allocate(INITIAL_BUFFER_SIZE);
    }

    @Override
    public void write(int b) throws IOException {
        checkClosed();
        checkWriteReady();
        ensureRemaining(1);
        buf.put((byte) (b & 0xff));
        markNotReadyIfNeeded();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        checkClosed();
        checkWriteReady();
        ensureRemaining(len);
        buf.put(b, off, len);
        markNotReadyIfNeeded();
    }

    @Override
    public void write(ByteBuffer src) throws IOException {
        checkClosed();
        checkWriteReady();
        if (!src.hasRemaining()) {
            return;
        }
        ensureRemaining(src.remaining());
        buf.put(src);
        markNotReadyIfNeeded();
    }

    @Override
    public void flush() throws IOException {
        checkClosed();
        checkWriteReady();
        if (buf.position() > 0) {
            buf.flip();
            ByteBuffer chunk = buf;
            buf = ByteBuffer.allocate(Math.max(INITIAL_BUFFER_SIZE, chunk.capacity()));
            webConnection.sendMessage(chunk, true);
        }
        markNotReadyIfNeeded();
        notifyWritePossibleIfReady();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            flush();
            closed = true;
        }
    }

    @Override
    public boolean isReady() {
        if (closed || webConnection.isClosed()) {
            return false;
        }
        return webConnection.isResponseWritable();
    }

    @Override
    public void setWriteListener(WriteListener listener) {
        if (this.writeListener != null) {
            throw new IllegalStateException(L10N.getString("err.write_listener_already_set"));
        }
        if (listener == null) {
            throw new NullPointerException(L10N.getString("err.write_listener_null"));
        }

        this.writeListener = listener;
        this.listenerNotified = false;
        Runnable initial = new Runnable() {
            @Override
            public void run() {
                notifyWritePossibleIfReady();
            }
        };
        webConnection.dispatchContainerCallback(initial);
    }

    boolean hasWriteListener() {
        return writeListener != null;
    }

    void notifyWritePossible() {
        listenerNotified = false;
        notifyWritePossibleIfReady();
    }

    private void notifyWritePossibleIfReady() {
        if (writeListener != null && isReady() && !listenerNotified) {
            listenerNotified = true;
            try {
                writeListener.onWritePossible();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("async.write_listener_error"), e);
                try {
                    writeListener.onError(e);
                } catch (Exception e2) {
                    LOGGER.log(Level.SEVERE, L10N.getString("async.write_listener_on_error"), e2);
                }
            }
        }
    }

    private void markNotReadyIfNeeded() {
        if (writeListener != null && !isReady()) {
            listenerNotified = false;
        }
    }

    private void checkWriteReady() {
        if (writeListener != null && !isReady()) {
            throw new IllegalStateException(L10N.getString("err.write_not_ready"));
        }
    }

    private void ensureRemaining(int needed) {
        if (buf.remaining() >= needed) {
            return;
        }
        buf.flip();
        int required = buf.remaining() + needed;
        int capacity = Math.max(buf.capacity() * 2, required);
        ByteBuffer grown = ByteBuffer.allocate(capacity);
        grown.put(buf);
        buf = grown;
    }

    private void checkClosed() throws IOException {
        if (closed) {
            throw new IOException(L10N.getString("err.websocket_stream_closed"));
        }
        if (webConnection.isClosed()) {
            throw new IOException(L10N.getString("err.websocket_connection_closed"));
        }
    }
}
