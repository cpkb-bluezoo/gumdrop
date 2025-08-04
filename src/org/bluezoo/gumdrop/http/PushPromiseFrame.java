/*
 * PushPromiseFrame.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;

/**
 * A HTTP/2 PUSH_PROMISE frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
class PushPromiseFrame extends Frame {

    int stream;
    boolean padded;
    boolean endHeaders;

    int padLength;
    int promisedStream;
    byte[] headerBlockFragment;

    protected PushPromiseFrame(int flags, int stream, byte[] payload) {
        this.stream = stream;
        padded = (flags & FLAG_PADDED) != 0;
        endHeaders = (flags & FLAG_END_HEADERS) != 0;
        int offset = 0;
        if (padded) {
            padLength = ((int) payload[offset++] & 0xff);
        }
        promisedStream = ((int) payload[offset++] & 0x7f) << 24
                | ((int) payload[offset++] & 0xff) << 16
                | ((int) payload[offset++] & 0xff) << 8
                | ((int) payload[offset++] & 0xff);
        // header block fragment
        int headerBlockLength = payload.length - (padLength + offset);
        headerBlockFragment = new byte[headerBlockLength];
        System.arraycopy(payload, offset, headerBlockFragment, 0, headerBlockLength);
    }

    /**
     * Construct a push promise frame to send to the client.
     */
    protected PushPromiseFrame(int stream, boolean padded, boolean endHeaders, int padLength, int promisedStream, byte[] headerBlockFragment) {
        this.stream = stream;
        this.padded = padded;
        this.endHeaders = endHeaders;
        this.padLength = padLength;
        this.promisedStream = promisedStream;
        this.headerBlockFragment = headerBlockFragment;
    }

    protected int getLength() {
        int length = headerBlockFragment.length + 4;
        if (padded) {
            length += (1 + padLength);
        }
        return length;
    }

    protected int getType() {
        return TYPE_PUSH_PROMISE;
    }

    protected int getFlags() {
        return (padded ? FLAG_PADDED : 0)
            | (endHeaders ? FLAG_END_HEADERS : 0);
    }

    protected int getStream() {
        return stream;
    }

    protected void write(ByteBuffer buf) {
        super.write(buf);
        if (padded) {
            buf.put((byte) (padLength & 0xff));
        }
        buf.put((byte) ((promisedStream >> 24) & 0x7f));
        buf.put((byte) ((promisedStream >> 16) & 0xff));
        buf.put((byte) ((promisedStream >> 8) & 0xff));
        buf.put((byte) (promisedStream & 0xff));
        buf.put(headerBlockFragment);
        if (padded) {
            // padding bytes are always 0
            buf.put(new byte[padLength]);
        }
    }

}
