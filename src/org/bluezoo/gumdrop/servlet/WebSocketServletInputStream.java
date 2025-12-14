/*
 * WebSocketServletInputStream.java
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

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

import java.io.IOException;
import java.io.PipedInputStream;

/**
 * ServletInputStream wrapper for WebSocket message delivery.
 *
 * <p>This reads decoded WebSocket messages from a pipe. Each message
 * is delivered as a contiguous sequence of bytes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class WebSocketServletInputStream extends ServletInputStream {

    private final PipedInputStream pipeIn;
    private volatile boolean finished = false;
    private ReadListener readListener;

    WebSocketServletInputStream(PipedInputStream pipeIn) {
        this.pipeIn = pipeIn;
    }

    @Override
    public int read() throws IOException {
        int b = pipeIn.read();
        if (b == -1) {
            finished = true;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = pipeIn.read(b, off, len);
        if (n == -1) {
            finished = true;
        }
        return n;
    }

    @Override
    public int available() throws IOException {
        return pipeIn.available();
    }

    @Override
    public void close() throws IOException {
        pipeIn.close();
        finished = true;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public boolean isReady() {
        try {
            return pipeIn.available() > 0;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void setReadListener(ReadListener listener) {
        this.readListener = listener;
        // Note: For full async support, we'd need to integrate with
        // the event handler to call onDataAvailable() when messages arrive.
        // For now, blocking read is the primary use case.
    }

}

