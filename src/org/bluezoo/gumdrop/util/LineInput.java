/*
 * LineInput.java
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

package org.bluezoo.gumdrop.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

/**
 * Interface to be implemented by objects that perform CRLF-delimited input
 * line decoding.
 * This enables them to be able to reuse the character buffer involved and
 * not reallocate it for every call, assuming it has sufficient capacity.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface LineInput {

    /**
     * Returns the input byte buffer from which the line will be read.
     */
    public ByteBuffer getLineInputBuffer();

    /**
     * Returns a character sink to be used to store the result of decoding
     * the input line.
     * The contents of this buffer will be overwritten.
     * @param capacity the returned buffer should have at least this
     * capacity
     */
    public CharBuffer getOrCreateLineInputCharacterSink(int capacity);

    /**
     * Read a line up to CRLF (HTTP/1).
     * Returns null if no CRLF was seen
     * @param decoder the decoder used to create strings
     * @throws IOException if the input is malformed
     */
    public default String readLine(CharsetDecoder decoder) throws IOException {
        try {
        ByteBuffer in = getLineInputBuffer();
//System.err.println("readLine in="+toString(in));
        // in is ready for get
        CharBuffer sink = getOrCreateLineInputCharacterSink(in.capacity());
        sink.clear();
        byte last = '\u0000';
        int limit = in.limit();
        while (in.hasRemaining()) {
            byte c = in.get();
            if (c == (byte) 0x0a && last == (byte) 0x0d) { // LF of CRLF
                int lineLength = in.position();
//System.err.println("CRLF at position="+lineLength);
                in.position(0);
                in.limit(lineLength - 2);
//System.err.println("before decode: in="+toString(in));
                CoderResult cr = decoder.decode(in, sink, false); // decode line up to CRLF
//System.err.println("after decode: in="+toString(in));
                // Compact and prepare for next readLine
                in.limit(limit);
                in.position(lineLength); // skip CRLF
//System.err.println("before compact: in="+toString(in));
                in.compact();
                in.position(0);
                in.limit(limit - lineLength);
//System.err.println("after compact: in="+toString(in));
                // in is now ready for get
                if (cr.isError()) {
                    cr.throwException();
                }
                sink.flip();
                return sink.toString();
            }
            last = c;
        }
        // We got to the end of the input buffer without seeing CRLF.
        // We need to wait for more data.
        // in should now be ready for put
//System.err.println("CRLF not seen");
        return null;
        } catch (RuntimeException e) {
            e.printStackTrace(System.err);
            throw e;
        }
    }

    static String toString(ByteBuffer buf) {
        StringBuilder builder = new StringBuilder();
        int pos = buf.position();
        while (buf.hasRemaining()) {
            byte b = buf.get();
            builder.append((char) b);
        }
        buf.position(pos);
        return builder.toString();
    }

}
