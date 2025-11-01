/*
 * ResponseOutputStream.java
 * Copyright (C) 2005, 2013 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
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
            String message = ServletConnector.L10N.getString("err.stream_closed");
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
            String message = ServletConnector.L10N.getString("err.stream_closed");
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
            String message = ServletConnector.L10N.getString("err.stream_closed");
            throw new IllegalStateException(message);
        }
        if (buf.position() > 0) {
            buf.flip();
            response.stream.writeBody(buf);
            buf.clear();
        }
    }

    @Override public void close() throws IOException {
        flush();
        closed = true;
    }

}
