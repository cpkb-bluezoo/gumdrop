/*
 * ServletWebConnection.java
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

import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.WebConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of javax.servlet.http.WebConnection for WebSocket upgraded connections.
 * This class provides the input and output streams that upgraded servlet handlers
 * use to communicate over the WebSocket connection.
 *
 * <p>The WebConnection interface is part of the Servlet 4.0 specification and provides
 * a low-level interface for protocols that have been upgraded from HTTP.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ServletWebConnection implements WebConnection {

    private static final Logger LOGGER = Logger.getLogger(ServletWebConnection.class.getName());

    private final WebSocketServletTransport transport;
    private final WebSocketServletInputStream inputStream;
    private final WebSocketServletOutputStream outputStream;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a new servlet web connection.
     *
     * @param transport the WebSocket transport
     */
    public ServletWebConnection(WebSocketServletTransport transport) {
        this.transport = transport;
        this.inputStream = new WebSocketServletInputStream();
        this.outputStream = new WebSocketServletOutputStream(transport);
    }

    /**
     * Returns the input stream for reading data from the WebSocket connection.
     *
     * @return the input stream
     * @throws IOException if an I/O error occurs
     */
    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (closed.get()) {
            throw new IOException("WebConnection is closed");
        }
        return inputStream;
    }

    /**
     * Returns the output stream for writing data to the WebSocket connection.
     *
     * @return the output stream
     * @throws IOException if an I/O error occurs
     */
    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (closed.get()) {
            throw new IOException("WebConnection is closed");
        }
        return outputStream;
    }

    /**
     * Closes the WebSocket connection.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            try {
                inputStream.close();
                outputStream.close();
                transport.close();
            } catch (Exception e) {
                throw new IOException("Error closing WebConnection", e);
            }
        }
    }

    /**
     * Delivers a text message to the input stream.
     * This is called by the WebSocket connection when text messages are received.
     *
     * @param message the text message
     */
    void deliverTextMessage(String message) {
        if (!closed.get()) {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            inputStream.deliverData(data);
        }
    }

    /**
     * Delivers a binary message to the input stream.
     * This is called by the WebSocket connection when binary messages are received.
     *
     * @param data the binary message data
     */
    void deliverBinaryMessage(byte[] data) {
        if (!closed.get()) {
            inputStream.deliverData(data);
        }
    }

    /**
     * Notifies the connection of closure.
     *
     * @param code the close code
     * @param reason the close reason
     */
    void notifyClose(int code, String reason) {
        inputStream.notifyClose();
        outputStream.notifyClose();
    }

    /**
     * Notifies the connection of an error.
     *
     * @param error the error
     */
    void notifyError(Throwable error) {
        inputStream.notifyError(error);
        outputStream.notifyError(error);
    }

    /**
     * ServletInputStream implementation for WebSocket data.
     */
    private static class WebSocketServletInputStream extends ServletInputStream {

        private final BlockingQueue<byte[]> messageQueue = new LinkedBlockingQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        
        private byte[] currentMessage;
        private int currentPosition;
        private Throwable error;

        @Override
        public boolean isFinished() {
            return finished.get();
        }

        @Override
        public boolean isReady() {
            return !closed.get() && (currentMessage != null || !messageQueue.isEmpty());
        }

        @Override
        public void setReadListener(javax.servlet.ReadListener readListener) {
            // For upgraded connections, we don't use async read listeners
            // Data is delivered synchronously through deliverData()
            throw new UnsupportedOperationException("ReadListener not supported on upgraded connections");
        }

        @Override
        public int read() throws IOException {
            if (closed.get()) {
                return -1;
            }

            if (error != null) {
                throw new IOException("WebSocket error", error);
            }

            // Get current message or wait for next one
            if (currentMessage == null || currentPosition >= currentMessage.length) {
                try {
                    currentMessage = messageQueue.take();
                    currentPosition = 0;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while reading", e);
                }
            }

            if (currentMessage == null) {
                return -1; // End of stream
            }

            return currentMessage[currentPosition++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed.get()) {
                return -1;
            }

            if (error != null) {
                throw new IOException("WebSocket error", error);
            }

            if (b == null) {
                throw new NullPointerException();
            }
            if (off < 0 || len < 0 || len > b.length - off) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }

            // Get current message or wait for next one
            if (currentMessage == null || currentPosition >= currentMessage.length) {
                try {
                    currentMessage = messageQueue.take();
                    currentPosition = 0;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while reading", e);
                }
            }

            if (currentMessage == null) {
                return -1; // End of stream
            }

            // Copy data from current message
            int available = currentMessage.length - currentPosition;
            int bytesToRead = Math.min(len, available);
            System.arraycopy(currentMessage, currentPosition, b, off, bytesToRead);
            currentPosition += bytesToRead;

            return bytesToRead;
        }

        void deliverData(byte[] data) {
            if (!closed.get()) {
                messageQueue.offer(data);
            }
        }

        void notifyClose() {
            closed.set(true);
            finished.set(true);
            messageQueue.offer(null); // Unblock any waiting reads
        }

        void notifyError(Throwable error) {
            this.error = error;
            messageQueue.offer(null); // Unblock any waiting reads
        }

        @Override
        public void close() throws IOException {
            notifyClose();
        }
    }

    /**
     * ServletOutputStream implementation for WebSocket data.
     */
    private static class WebSocketServletOutputStream extends ServletOutputStream {

        private final WebSocketServletTransport transport;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private Throwable error;

        public WebSocketServletOutputStream(WebSocketServletTransport transport) {
            this.transport = transport;
        }

        @Override
        public boolean isReady() {
            return !closed.get() && error == null;
        }

        @Override
        public void setWriteListener(javax.servlet.WriteListener writeListener) {
            // For upgraded connections, we don't use async write listeners
            throw new UnsupportedOperationException("WriteListener not supported on upgraded connections");
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] { (byte) b });
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed.get()) {
                throw new IOException("OutputStream is closed");
            }

            if (error != null) {
                throw new IOException("WebSocket error", error);
            }

            if (b == null) {
                throw new NullPointerException();
            }
            if (off < 0 || len < 0 || len > b.length - off) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return;
            }

            // Create a copy of the data to send
            byte[] data = new byte[len];
            System.arraycopy(b, off, data, 0, len);

            // Send as binary WebSocket frame
            try {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
                transport.sendFrame(buffer);
            } catch (Exception e) {
                throw new IOException("Failed to write WebSocket data", e);
            }
        }

        void notifyClose() {
            closed.set(true);
        }

        void notifyError(Throwable error) {
            this.error = error;
        }

        @Override
        public void close() throws IOException {
            notifyClose();
        }
    }
}
