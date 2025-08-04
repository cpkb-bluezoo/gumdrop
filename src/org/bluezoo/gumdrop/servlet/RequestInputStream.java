/*
 * RequestInputStream.java
 * Copyright (C) 2005, 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletInputStream;
import javax.servlet.ReadListener;

/**
 * ServletInputStream implementation for a request.
 *
 * @author <a href='amilto:dog@gnu.org'>Chris Burdess</a>
 */
class RequestInputStream extends ServletInputStream {

    private final PipedInputStream in;
    final AtomicBoolean finished = new AtomicBoolean(false);
    ReadListener readListener;

    RequestInputStream(PipedInputStream in) {
        this.in = in;
    }

    @Override public int read() throws IOException {
        return in.read();
    }

    @Override public int read(byte[] buf) throws IOException {
        return in.read(buf);
    }

    @Override public int read(byte[] buf, int off, int len) throws IOException {
        return in.read(buf, off, len);
    }

    @Override public long skip(long n) throws IOException {
        return in.skip(n);
    }

    @Override public int available() throws IOException {
        return in.available();
    }

    @Override public void close() throws IOException {
        in.close();
        finished.set(true);
    }

    @Override public void mark(int readlimit) {
        in.mark(readlimit);
    }

    @Override public void reset() throws IOException {
        in.reset();
    }

    @Override public boolean markSupported() {
        return in.markSupported();
    }

    // -- Servlet 3.1 methods --

    @Override public void setReadListener(ReadListener readListener) {
        this.readListener = readListener;
    }

    @Override public boolean isReady() {
        try {
            if (finished.get()) {
                return false; // pipe is closed
            }
            return in.available() > 0; // available() will not block
        } catch (IOException e) {
            return false;
        }
    }

    @Override public boolean isFinished() {
        return finished.get();
    }

}
