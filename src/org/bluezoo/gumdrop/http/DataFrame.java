/*
 * DataFrame.java
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
 * A HTTP/2 DATA frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
public class DataFrame extends Frame {

    int stream;
    boolean padded;
    boolean endStream;

    int padLength;
    ByteBuffer data;

    /**
     * Constructor for a data frame received from the client.
     * The payload ByteBuffer should be positioned at the start of payload data
     * with limit set to the end of payload data.
     */
    protected DataFrame(int flags, int stream, ByteBuffer payload) {
        this.stream = stream;
        padded = (flags & FLAG_PADDED) != 0;
        endStream = (flags & FLAG_END_STREAM) != 0;
        if (padded) {
            padLength = payload.get() & 0xff;
            // Create a slice for the data portion (excluding padding)
            int dataLength = payload.remaining() - padLength;
            int savedLimit = payload.limit();
            payload.limit(payload.position() + dataLength);
            data = payload.slice();
            payload.limit(savedLimit);
            payload.position(savedLimit); // consume all including padding
        } else {
            data = payload.slice();
            payload.position(payload.limit()); // consume all
        }
    }

    /**
     * Constructor for a data frame to send to the client.
     */
    protected DataFrame(int stream, boolean padded, boolean endStream, int padLength, ByteBuffer data) {
        this.stream = stream;
        this.padded = padded;
        this.endStream = endStream;
        this.padLength = padLength;
        this.data = data;
    }

    public int getLength() {
        int length = data.remaining();
        if (padded) {
            length += (padLength + 1);
        }
        return length;
    }

    public int getType() {
        return TYPE_DATA;
    }

    public int getFlags() {
        return (padded ? FLAG_PADDED : 0)
            | (endStream ? FLAG_END_STREAM : 0);
    }

    public int getStream() {
        return stream;
    }

    protected void write(ByteBuffer buf) {
        super.write(buf);
        if (padded) {
            buf.put((byte) (padLength & 0xff));
        }
        // Save position to restore after put
        int savedPos = data.position();
        buf.put(data);
        data.position(savedPos); // restore position for potential reuse
        if (padded) {
            // padding bytes are always 0
            for (int i = 0; i < padLength; i++) {
                buf.put((byte) 0);
            }
        }
    }

}
