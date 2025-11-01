/*
 * DataFrame.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;

/**
 * A HTTP/2 DATA frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
class DataFrame extends Frame {

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

    protected int getLength() {
        int length = data.length;
        if (padded) {
            length += (padLength + 1);
        }
        return length;
    }

    protected int getType() {
        return TYPE_DATA;
    }

    protected int getFlags() {
        return (padded ? FLAG_PADDED : 0)
            | (endStream ? FLAG_END_STREAM : 0);
    }

    protected int getStream() {
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
