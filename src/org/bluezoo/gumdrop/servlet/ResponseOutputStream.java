/*
 * ResponseOutputStream.java
 * Copyright (C) 2005, 2013 Chris Burdess
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

/**
 * An output stream associated with an HTTP response.
 * Writes to this stream will fill a buffer, the size of which is determined
 * by the response. Buffer overflows cause the buffer to be flushed to the
 * underlying connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class ResponseOutputStream extends OutputStream {

    private final Response response;
    private final ByteBuffer buf;
    private boolean closed;

    ResponseOutputStream(Response response, int bufferSize) {
        this.response = response;
        buf = ByteBuffer.allocate(bufferSize);
    }

    @Override public synchronized void write(int b) throws IOException {
        if (closed) {
            String message = ServletService.L10N.getString("err.stream_closed");
            throw new IllegalStateException(message);
        }
        if (buf.remaining() < 1) {
            flush();
        }
        buf.put((byte) (b & 0xff));
    }

    @Override public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override public synchronized void write(byte[] b, int offset, int len) throws IOException {
        if (closed) {
            String message = ServletService.L10N.getString("err.stream_closed");
            throw new IllegalStateException(message);
        }
        int available = buf.remaining();
        while (len > available) {
            buf.put(b, offset, available);
            offset += available;
            len -= available;
            flush();
            available = buf.remaining();
        }
        buf.put(b, offset, len);
    }

    @Override public synchronized void flush() throws IOException {
        if (closed) {
            String message = ServletService.L10N.getString("err.stream_closed");
            throw new IllegalStateException(message);
        }
        if (buf.position() > 0) {
            buf.flip();
            response.writeBody(buf);
            buf.clear();
        }
    }

    @Override public void close() throws IOException {
        flush();
        closed = true;
    }
    
    /**
     * Returns true if the buffer has capacity for more data.
     * Used by {@link ServletOutputStreamWrapper} for non-blocking write support.
     * 
     * @return true if the buffer has remaining capacity
     */
    boolean hasCapacity() {
        return buf.hasRemaining();
    }
    
    /**
     * Returns the number of bytes of available capacity in the buffer.
     * 
     * @return the remaining capacity in bytes
     */
    int remainingCapacity() {
        return buf.remaining();
    }

}

