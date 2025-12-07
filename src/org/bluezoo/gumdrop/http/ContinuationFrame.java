/*
 * ContinuationFrame.java
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;

/**
 * A HTTP/2 CONTINUATION frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
public class ContinuationFrame extends Frame {

    int stream;
    boolean endHeaders;

    byte[] headerBlockFragment;

    /**
     * Constructor for a continuation frame received from the client.
     */
    protected ContinuationFrame(int flags, int stream, byte[] payload) {
        this.stream = stream;
        endHeaders = (flags & FLAG_END_HEADERS) != 0;
        headerBlockFragment = payload;
    }

    /**
     * Constructor for a continuation frame to send to the client.
     */
    protected ContinuationFrame(int stream, boolean endHeaders, byte[] headerBlockFragment) {
        this.stream = stream;
        this.endHeaders = endHeaders;
        this.headerBlockFragment = headerBlockFragment;
    }

    public int getLength() {
        return headerBlockFragment.length;
    }

    public int getType() {
        return TYPE_CONTINUATION;
    }

    public int getFlags() {
        return endHeaders ? FLAG_END_HEADERS : 0;
    }

    public int getStream() {
        return stream;
    }

    protected void write(ByteBuffer buf) {
        super.write(buf);
        buf.put(headerBlockFragment);
    }

}
