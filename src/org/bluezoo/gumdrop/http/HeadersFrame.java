/*
 * HeadersFrame.java
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

import java.net.ProtocolException;
import java.nio.ByteBuffer;

/**
 * A HTTP/2 HEADERS frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
final class HeadersFrame extends Frame {

    int stream;
    boolean padded;
    boolean endStream;
    boolean endHeaders;
    boolean priority;

    int padLength;
    int streamDependency;
    boolean streamDependencyExclusive;
    int weight;
    byte[] headerBlockFragment;

    /**
     * Constructor for a headers frame received from the client.
     */
    protected HeadersFrame(int flags, int stream, byte[] payload) throws ProtocolException {
        this.stream = stream;
        padded = (flags & FLAG_PADDED) != 0;
        endStream = (flags & FLAG_END_STREAM) != 0;
        endHeaders = (flags & FLAG_END_HEADERS) != 0;
        priority = (flags & FLAG_PRIORITY) != 0;
        int offset = 0;
        if (padded) {
            padLength = ((int) payload[offset++] & 0xff);
        }
        if (priority) {
            int sd1 = (int) payload[offset++] & 0xff;
            streamDependencyExclusive = (sd1 & 0x80) != 0;
            streamDependency = (sd1 & 0x7f) << 24
                | ((int) payload[offset++] & 0xff) << 16
                | ((int) payload[offset++] & 0xff) << 8
                | ((int) payload[offset++] & 0xff);
            weight = ((int) payload[offset++] & 0xff);
        }
        // header block fragment
        if (padded || priority) {
            int headerBlockLength = payload.length - (padLength + offset);
            if (headerBlockLength < 0) {
                throw new ProtocolException();
            }
            headerBlockFragment = new byte[headerBlockLength];
            System.arraycopy(payload, offset, headerBlockFragment, 0, headerBlockLength);
        } else {
            headerBlockFragment = payload;
        }
    }

    /**
     * Construct a headers frame to send to the client.
     */
    protected HeadersFrame(int stream, boolean padded, boolean endStream, boolean endHeaders, boolean priority, int padLength, int streamDependency, boolean streamDependencyExclusive, int weight, byte[] headerBlockFragment) {
        this.stream = stream;
        this.padded = padded;
        this.endStream = endStream;
        this.endHeaders = endHeaders;
        this.priority = priority;
        this.padLength = padLength;
        this.streamDependency = streamDependency;
        this.streamDependencyExclusive = streamDependencyExclusive;
        this.weight = weight;
        this.headerBlockFragment = headerBlockFragment;
    }

    protected int getLength() {
        int length = headerBlockFragment.length;
        if (padded) {
            length += (1 + padLength);
        }
        if (priority) {
            length += 5;
        }
        return length;
    }

    protected int getType() {
        return TYPE_HEADERS;
    }

    protected int getFlags() {
        return (padded ? FLAG_PADDED : 0)
            | (endStream ? FLAG_END_STREAM : 0)
            | (endHeaders ? FLAG_END_HEADERS : 0)
            | (priority ? FLAG_PRIORITY : 0);
    }

    protected int getStream() {
        return stream;
    }

    protected void write(ByteBuffer buf) {
        super.write(buf);
        if (padded) {
            buf.put((byte) (padLength & 0xff));
        }
        if (priority) {
            int sd1 = (streamDependency >> 24) & 0xff;
            if (streamDependencyExclusive) {
                sd1 |= 0x80;
            }
            buf.put((byte) sd1);
            buf.put((byte) ((streamDependency >> 16) & 0xff));
            buf.put((byte) ((streamDependency >> 8) & 0xff));
            buf.put((byte) (streamDependency & 0xff));
            buf.put((byte) (weight & 0xff));
        }
        buf.put(headerBlockFragment);
        if (padded) {
            // padding bytes are always 0
            buf.put(new byte[padLength]);
        }
    }

}
