/*
 * HeadersFrame.java
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

    protected void appendFields(StringBuilder buf) {
        super.appendFields(buf);
        if (padded) {
            buf.append(";padded");
        }
        if (endStream) {
            buf.append(";endStream");
        }
        if (endHeaders) {
            buf.append(";endHeaders");
        }
        buf.append(";priority=").append(priority);
        buf.append(";padLength=").append(padLength);
        buf.append(";streamDependency=").append(streamDependency);
        buf.append(";streamDependencyExclusive=").append(streamDependencyExclusive);
        buf.append(";weight=").append(weight);
        buf.append(";headerBlockFragment=").append(HTTPConnection.toHexString(headerBlockFragment));
    }

}
