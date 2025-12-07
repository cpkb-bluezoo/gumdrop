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
    byte[] data;

    /**
     * Constructor for a data frame received from the client.
     */
    protected DataFrame(int flags, int stream, byte[] payload) {
        this.stream = stream;
        padded = (flags & FLAG_PADDED) != 0;
        endStream = (flags & FLAG_END_STREAM) != 0;
        int offset = 0;
        int length = payload.length;
        if (padded) {
            padLength = ((int) payload[offset++] & 0xff);
            data = new byte[length - (padLength + 1)];
            System.arraycopy(payload, offset, data, 0, data.length);
        } else {
            data = payload;
        }
    }

    /**
     * Constructor for a data frame to send to the client.
     */
    protected DataFrame(int stream, boolean padded, boolean endStream, int padLength, byte[] data) {
        this.stream = stream;
        this.padded = padded;
        this.endStream = endStream;
        this.padLength = padLength;
        this.data = data;
    }

    public int getLength() {
        int length = data.length;
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
            buf.put(data);
            // padding bytes are always 0
            buf.put(new byte[padLength]);
        } else {
            buf.put(data);
        }
    }

}
