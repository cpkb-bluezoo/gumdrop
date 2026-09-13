/*
 * ServletOutputStreamWrapper.java
 * Copyright (C) 2013, 2025 Chris Burdess
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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

/**
 * Servlet output stream wrapper with async write support.
 * 
 * <p>This implementation wraps a {@link ResponseOutputStream} and provides
 * non-blocking write support through the {@link WriteListener} interface.
 * {@link #isReady()} reflects both the local response buffer and transport
 * backpressure from {@link ServletHandler#isResponseWritable()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ServletOutputStreamWrapper extends ServletOutputStream {

    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");
    private static final Logger LOGGER = Logger.getLogger(ServletOutputStreamWrapper.class.getName());

    private final Response response;
    private final OutputStream out;
    private final ResponseOutputStream responseOut;
    private WriteListener writeListener;
    private volatile boolean listenerNotified = true;
    private volatile boolean closed = false;

    ServletOutputStreamWrapper(Response response, OutputStream out) {
        this.response = response;
        this.out = out;
        if (out instanceof ResponseOutputStream) {
            this.responseOut = (ResponseOutputStream) out;
        } else {
            this.responseOut = null;
        }
    }

    @Override
    public void write(int b) throws IOException {
        checkClosed();
        checkWriteReady();
        out.write(b);
        markNotReadyIfNeeded();
    }

    @Override
    public void write(byte[] b) throws IOException {
        checkClosed();
        checkWriteReady();
        out.write(b, 0, b.length);
        markNotReadyIfNeeded();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        checkClosed();
        checkWriteReady();
        out.write(b, off, len);
        markNotReadyIfNeeded();
    }

    @Override
    public void write(ByteBuffer src) throws IOException {
        checkClosed();
        checkWriteReady();
        if (!src.hasRemaining()) {
            return;
        }
        if (src.hasArray()) {
            write(src.array(), src.arrayOffset() + src.position(), src.remaining());
            src.position(src.limit());
        } else {
            byte[] buf = new byte[src.remaining()];
            src.get(buf);
            write(buf, 0, buf.length);
        }
        markNotReadyIfNeeded();
    }

    @Override
    public void flush() throws IOException {
        checkClosed();
        checkWriteReady();
        out.flush();
        markNotReadyIfNeeded();
        notifyWritePossibleIfReady();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            out.close();
        }
    }

    @Override
    public void setWriteListener(WriteListener listener) {
        if (this.writeListener != null) {
            throw new IllegalStateException(L10N.getString("err.write_listener_already_set"));
        }
        if (listener == null) {
            throw new NullPointerException(L10N.getString("err.write_listener_null"));
        }
        if (response != null && !response.request.isAsyncStarted()) {
            throw new IllegalStateException(L10N.getString("err.write_listener_async"));
        }

        this.writeListener = listener;
        this.listenerNotified = false;
        Runnable initial = new Runnable() {
            @Override
            public void run() {
                notifyWritePossibleIfReady();
            }
        };
        if (response != null) {
            response.handler.dispatchContainerCallback(initial);
        } else {
            initial.run();
        }
    }

    @Override
    public boolean isReady() {
        if (closed) {
            return false;
        }
        if (responseOut != null && !responseOut.hasCapacity()) {
            return false;
        }
        if (response == null) {
            return true;
        }
        return response.isResponseWritable();
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

    private void checkClosed() throws IOException {
        if (closed) {
            throw new IOException(L10N.getString("async.stream_closed"));
        }
    }
}
