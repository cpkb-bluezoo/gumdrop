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

import javax.servlet.WriteListener;
import javax.servlet.ServletOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * ServletOutputStream that sends data as WebSocket messages.
 *
 * <p>Data is buffered until flush() is called, at which point the
 * buffered data is sent as a single WebSocket text message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class WebSocketServletOutputStream extends ServletOutputStream {

    private final ServletWebConnection webConnection;
    private final ByteArrayOutputStream buffer;
    private WriteListener writeListener;
    private volatile boolean closed = false;

    WebSocketServletOutputStream(ServletWebConnection webConnection) {
        this.webConnection = webConnection;
        this.buffer = new ByteArrayOutputStream();
    }

    @Override
    public void write(int b) throws IOException {
        checkClosed();
        buffer.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        checkClosed();
        buffer.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        checkClosed();
        if (buffer.size() > 0) {
            byte[] data = buffer.toByteArray();
            buffer.reset();
            webConnection.sendMessage(data);
        }
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
        return !closed && !webConnection.isClosed();
    }

    @Override
    public void setWriteListener(WriteListener listener) {
        this.writeListener = listener;
        // For full async support, we'd need to notify when ready to write.
        // For now, blocking write is the primary use case.
        if (listener != null && isReady()) {
            try {
                listener.onWritePossible();
            } catch (IOException e) {
                listener.onError(e);
            }
        }
    }

    private void checkClosed() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
        if (webConnection.isClosed()) {
            throw new IOException("WebSocket connection is closed");
        }
    }

}

