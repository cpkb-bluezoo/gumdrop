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
import java.io.PipedInputStream;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletInputStream;
import javax.servlet.ReadListener;

/**
 * ServletInputStream implementation for a request with async read support.
 * 
 * <p>This implementation wraps a {@link PipedInputStream} that receives
 * data from the HTTP connection layer. It supports the Servlet 3.1
 * non-blocking read API through {@link ReadListener}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class RequestInputStream extends ServletInputStream {

    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");
    private static final Logger LOGGER = Logger.getLogger(RequestInputStream.class.getName());

    private final PipedInputStream in;
    final AtomicBoolean finished = new AtomicBoolean(false);
    ReadListener readListener;
    private volatile boolean listenerRegistered = false;
    private volatile boolean allDataReadNotified = false;

    RequestInputStream(PipedInputStream in) {
        this.in = in;
    }

    @Override 
    public int read() throws IOException {
        return in.read();
    }

    @Override 
    public int read(byte[] buf) throws IOException {
        return in.read(buf);
    }

    @Override 
    public int read(byte[] buf, int off, int len) throws IOException {
        return in.read(buf, off, len);
    }

    @Override 
    public long skip(long n) throws IOException {
        return in.skip(n);
    }

    @Override 
    public int available() throws IOException {
        return in.available();
    }

    @Override 
    public void close() throws IOException {
        in.close();
        finished.set(true);
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

    /**
     * Sets the read listener for non-blocking I/O.
     * 
     * <p>Once a listener is set, the container will call:
     * <ul>
     * <li>{@link ReadListener#onDataAvailable()} when data is available to read</li>
     * <li>{@link ReadListener#onAllDataRead()} when all request data has been read</li>
     * <li>{@link ReadListener#onError(Throwable)} when an error occurs</li>
     * </ul>
     * 
     * @param listener the read listener
     * @throws IllegalStateException if a listener is already set or async is not started
     */
    @Override 
    public void setReadListener(ReadListener listener) {
        if (this.readListener != null) {
            throw new IllegalStateException("ReadListener already set");
        }
        if (listener == null) {
            throw new NullPointerException("ReadListener cannot be null");
        }
        
        this.readListener = listener;
        this.listenerRegistered = true;
        
        // If data is already available, notify immediately
        if (isReady()) {
            notifyDataAvailable();
        } else if (isFinished()) {
            notifyAllDataRead();
        }
    }

    /**
     * Returns true if data can be read without blocking.
     * 
     * @return true if data is available
     */
    @Override 
    public boolean isReady() {
        try {
            if (finished.get()) {
                return false; // pipe is closed
            }
            return in.available() > 0; // available() will not block
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns true if all request data has been read.
     * 
     * @return true if all data has been read
     */
    @Override 
    public boolean isFinished() {
        return finished.get();
    }
    
    /**
     * Notifies the read listener that data is available.
     * Called by the container when data arrives on the connection.
     */
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
    
    /**
     * Notifies the read listener that all data has been read.
     * Called by the container when the request body is complete.
     */
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
    
    /**
     * Notifies the read listener of an error.
     * 
     * @param t the error that occurred
     */
    void notifyError(Throwable t) {
        if (readListener != null && listenerRegistered) {
            try {
                readListener.onError(t);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, L10N.getString("async.read_listener_on_error"), e);
            }
        }
    }
    
    /**
     * Returns true if a read listener has been registered.
     * 
     * @return true if a listener is registered
     */
    boolean hasReadListener() {
        return listenerRegistered;
    }
}
