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
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

/**
 * Servlet output stream wrapper with async write support.
 * 
 * <p>This implementation wraps a {@link ResponseOutputStream} and provides
 * non-blocking write support through the {@link WriteListener} interface.
 * The listener is notified when the underlying buffer has space for more data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ServletOutputStreamWrapper extends ServletOutputStream {

    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.L10N");
    private static final Logger LOGGER = Logger.getLogger(ServletOutputStreamWrapper.class.getName());

    private final OutputStream out;
    private final ResponseOutputStream responseOut;
    private WriteListener writeListener;
    private volatile boolean listenerNotified = false;
    private volatile boolean closed = false;

    /**
     * Creates a new servlet output stream wrapper.
     * 
     * @param out the underlying output stream
     */
    ServletOutputStreamWrapper(OutputStream out) {
        this.out = out;
        // Store reference to ResponseOutputStream for capacity checking
        if (out instanceof ResponseOutputStream) {
            this.responseOut = (ResponseOutputStream) out;
        } else {
            this.responseOut = null;
        }
    }

    @Override
    public void write(int b) throws IOException {
        checkClosed();
        out.write(b);
        checkNotifyListener();
    }

    @Override
    public void write(byte[] b) throws IOException {
        checkClosed();
        out.write(b, 0, b.length);
        checkNotifyListener();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        checkClosed();
        out.write(b, off, len);
        checkNotifyListener();
    }

    @Override
    public void flush() throws IOException {
        checkClosed();
        out.flush();
        
        // After flush, buffer is empty so we're ready for more data
        if (writeListener != null && !listenerNotified) {
            notifyWriteReady();
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            out.close();
        }
    }

    /**
     * Sets the write listener for non-blocking I/O.
     * 
     * <p>Once a listener is set, the container will call 
     * {@link WriteListener#onWritePossible()} when the output buffer
     * has space available for writing.
     * 
     * @param listener the write listener
     * @throws IllegalStateException if a listener is already set
     */
    @Override
    public void setWriteListener(WriteListener listener) {
        if (this.writeListener != null) {
            throw new IllegalStateException("WriteListener already set");
        }
        if (listener == null) {
            throw new NullPointerException("WriteListener cannot be null");
        }
        
        this.writeListener = listener;
        this.listenerNotified = false;
        
        // Initial callback - buffer is ready for writing
        notifyWriteReady();
    }

    /**
     * Returns true if data can be written without blocking.
     * 
     * @return true if the output buffer has space
     */
    @Override
    public boolean isReady() {
        if (closed) {
            return false;
        }
        
        // Check if the underlying buffer has capacity
        if (responseOut != null) {
            boolean ready = responseOut.hasCapacity();
            
            // If not ready and we have a listener, flag for notification
            if (!ready && writeListener != null) {
                listenerNotified = false;
            }
            
            return ready;
        }
        
        // For other output streams, assume always ready
        return true;
    }
    
    /**
     * Notifies the write listener that writing is possible.
     */
    private void notifyWriteReady() {
        if (writeListener != null && !listenerNotified) {
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
    
    /**
     * Checks if listener should be notified after a write operation.
     */
    private void checkNotifyListener() {
        // After a successful write, if we still have capacity, notify listener
        if (writeListener != null && isReady() && !listenerNotified) {
            notifyWriteReady();
        }
    }
    
    /**
     * Checks if the stream is closed and throws an exception if so.
     */
    private void checkClosed() throws IOException {
        if (closed) {
            throw new IOException(L10N.getString("async.stream_closed"));
        }
    }
}
