/*
 * RequestInputStream.java
 * Copyright (C) 2005, 2025 Chris Burdess
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
import java.nio.ByteBuffer;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;

/**
 * ServletInputStream implementation for a request with async read support.
 *
 * <p>This implementation wraps a {@link RequestBodyStream} that receives
 * data from the HTTP connection layer. It supports the Servlet 3.1
 * non-blocking read API through {@link ReadListener}. Once a listener is
 * registered, {@link #read()} methods never block and throw {@link
 * IllegalStateException} when {@link #isReady()} would return {@code false}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class RequestInputStream extends ServletInputStream {

    private static final ResourceBundle L10N =
        ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");
    private static final Logger LOGGER = Logger.getLogger(RequestInputStream.class.getName());

    private final Request request;
    private final RequestBodyStream in;
    private volatile boolean closed;
    ReadListener readListener;
    private volatile boolean listenerRegistered;
    private volatile boolean allDataReadNotified;

    RequestInputStream(Request request, RequestBodyStream in) {
        this.request = request;
        this.in = in;
    }

    @Override 
    public int read() throws IOException {
        if (listenerRegistered) {
            byte[] one = new byte[1];
            int n = readNonBlocking(one, 0, 1);
            if (n < 0) {
                afterRead();
                return -1;
            }
            if (n == 0) {
                throw notReady();
            }
            afterRead();
            return one[0] & 0xFF;
        }
        int n = in.read();
        afterRead();
        return n;
    }

    @Override 
    public int read(byte[] buf) throws IOException {
        return read(buf, 0, buf.length);
    }

    @Override 
    public int read(byte[] buf, int off, int len) throws IOException {
        if (listenerRegistered) {
            int n = readNonBlocking(buf, off, len);
            if (n < 0) {
                afterRead();
                return -1;
            }
            if (n == 0 && len > 0) {
                throw notReady();
            }
            afterRead();
            return n;
        }
        int n = in.read(buf, off, len);
        afterRead();
        return n;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (!dst.hasRemaining()) {
            return 0;
        }
        if (dst.hasArray()) {
            int n = read(dst.array(), dst.arrayOffset() + dst.position(), dst.remaining());
            if (n > 0) {
                dst.position(dst.position() + n);
            }
            return n;
        }
        byte[] buf = new byte[Math.min(dst.remaining(), 8192)];
        int n = read(buf, 0, buf.length);
        if (n <= 0) {
            return n;
        }
        dst.put(buf, 0, n);
        return n;
    }

    @Override 
    public long skip(long n) throws IOException {
        if (listenerRegistered) {
            if (n <= 0) {
                return 0;
            }
            if (isFinished()) {
                return 0;
            }
            if (!isReady()) {
                throw notReady();
            }
            byte[] buf = new byte[(int) Math.min(n, 8192)];
            long skipped = 0;
            while (skipped < n) {
                int toRead = (int) Math.min(n - skipped, buf.length);
                int r = readNonBlocking(buf, 0, toRead);
                if (r < 0) {
                    break;
                }
                if (r == 0) {
                    throw notReady();
                }
                skipped += r;
            }
            afterRead();
            return skipped;
        }
        return in.skip(n);
    }

    @Override 
    public int available() throws IOException {
        return in.available();
    }

    @Override 
    public void close() throws IOException {
        in.close();
        closed = true;
    }

    @Override 
    public void mark(int readlimit) {
        in.mark(readlimit);
    }

    @Override 
    public void reset() throws IOException {
        in.reset();
    }

    @Override 
    public boolean markSupported() {
        return in.markSupported();
    }

    // -- Servlet 3.1 non-blocking read methods --

    @Override 
    public void setReadListener(ReadListener listener) {
        if (this.readListener != null) {
            throw new IllegalStateException(L10N.getString("err.read_listener_already_set"));
        }
        if (listener == null) {
            throw new NullPointerException(L10N.getString("err.read_listener_null"));
        }
        if (request != null && !request.isAsyncStarted()) {
            throw new IllegalStateException(L10N.getString("err.read_listener_async"));
        }

        this.readListener = listener;
        this.listenerRegistered = true;

        Runnable initial = new Runnable() {
            @Override
            public void run() {
                dispatchReadState();
            }
        };
        if (request != null) {
            request.handler.dispatchContainerCallback(initial);
        } else {
            initial.run();
        }
    }

    @Override
    public boolean isReady() {
        if (closed) {
            return false;
        }
        return in.available() > 0;
    }

    @Override 
    public boolean isFinished() {
        if (closed) {
            return true;
        }
        return in.isEof() && in.available() == 0;
    }

    /**
     * Invoked from {@link ServletHandler} when request body data arrives
     * on the connection's I/O thread.
     */
    void dispatchDataAvailable() {
        if (!listenerRegistered) {
            return;
        }
        dispatchReadState();
    }

    private void dispatchReadState() {
        if (!listenerRegistered) {
            return;
        }
        if (isReady()) {
            notifyDataAvailable();
        }
        if (isFinished()) {
            notifyAllDataRead();
        }
    }

    private int readNonBlocking(byte[] buf, int off, int len) throws IOException {
        return in.readNonBlocking(buf, off, len);
    }

    private IllegalStateException notReady() {
        return new IllegalStateException(L10N.getString("err.read_not_ready"));
    }

    private void afterRead() throws IOException {
        if (listenerRegistered && isFinished()) {
            if (request != null) {
                request.handler.dispatchContainerCallback(new Runnable() {
                    @Override
                    public void run() {
                        notifyAllDataRead();
                    }
                });
            } else {
                notifyAllDataRead();
            }
        }
    }

    void notifyDataAvailable() {
        if (readListener != null && listenerRegistered) {
            try {
                readListener.onDataAvailable();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("async.read_listener_error"), e);
                notifyError(e);
            }
        }
    }

    void notifyAllDataRead() {
        if (readListener != null && listenerRegistered && !allDataReadNotified) {
            allDataReadNotified = true;
            try {
                readListener.onAllDataRead();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("async.read_listener_all_data"), e);
                notifyError(e);
            }
        }
    }

    void notifyError(Throwable t) {
        if (readListener != null && listenerRegistered) {
            try {
                readListener.onError(t);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, L10N.getString("async.read_listener_on_error"), e);
            }
        }
    }

    boolean hasReadListener() {
        return listenerRegistered;
    }
}
