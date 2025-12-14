/*
 * HeadersFrame.java
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

import java.net.ProtocolException;
import java.nio.ByteBuffer;

/**
 * A HTTP/2 HEADERS frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
public final class HeadersFrame extends Frame {

    int stream;
    boolean padded;
    boolean endStream;
    boolean endHeaders;
    boolean priority;

    int padLength;
    int streamDependency;
    boolean streamDependencyExclusive;
    int weight;
    ByteBuffer headerBlockFragment;

    /**
     * Constructor for a headers frame received from the client.
     * The payload ByteBuffer should be positioned at the start of payload data
     * with limit set to the end of payload data.
     */
    protected HeadersFrame(int flags, int stream, ByteBuffer payload) throws ProtocolException {
        this.stream = stream;
        padded = (flags & FLAG_PADDED) != 0;
        endStream = (flags & FLAG_END_STREAM) != 0;
        endHeaders = (flags & FLAG_END_HEADERS) != 0;
        priority = (flags & FLAG_PRIORITY) != 0;
        
        int startPos = payload.position();
        int endPos = payload.limit();
        
        if (padded) {
            padLength = payload.get() & 0xff;
            endPos -= padLength; // Exclude padding from header block
        }
        if (priority) {
            int sd1 = payload.get() & 0xff;
            streamDependencyExclusive = (sd1 & 0x80) != 0;
            streamDependency = (sd1 & 0x7f) << 24
                | (payload.get() & 0xff) << 16
                | (payload.get() & 0xff) << 8
                | (payload.get() & 0xff);
            weight = payload.get() & 0xff;
        }
        
        // header block fragment is remaining data minus padding
        int headerBlockLength = endPos - payload.position();
        if (headerBlockLength < 0) {
            throw new ProtocolException();
        }
        
        int savedLimit = payload.limit();
        payload.limit(payload.position() + headerBlockLength);
        headerBlockFragment = payload.slice();
        payload.limit(savedLimit);
        payload.position(savedLimit); // consume all including padding
    }

    /**
     * Construct a headers frame to send to the client.
     */
    protected HeadersFrame(int stream, boolean padded, boolean endStream, boolean endHeaders, 
                          boolean priority, int padLength, int streamDependency, 
                          boolean streamDependencyExclusive, int weight, ByteBuffer headerBlockFragment) {
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

    /**
     * Construct a headers frame to send to the client (convenience for byte[]).
     */
    protected HeadersFrame(int stream, boolean padded, boolean endStream, boolean endHeaders, 
                          boolean priority, int padLength, int streamDependency, 
                          boolean streamDependencyExclusive, int weight, byte[] headerBlockFragment) {
        this(stream, padded, endStream, endHeaders, priority, padLength, streamDependency,
             streamDependencyExclusive, weight, ByteBuffer.wrap(headerBlockFragment));
    }

    public int getLength() {
        int length = headerBlockFragment.remaining();
        if (padded) {
            length += (1 + padLength);
        }
        if (priority) {
            length += 5;
        }
        return length;
    }

    public int getType() {
        return TYPE_HEADERS;
    }

    public int getFlags() {
        return (padded ? FLAG_PADDED : 0)
            | (endStream ? FLAG_END_STREAM : 0)
            | (endHeaders ? FLAG_END_HEADERS : 0)
            | (priority ? FLAG_PRIORITY : 0);
    }

    public int getStream() {
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
        // Save position to restore after put
        int savedPos = headerBlockFragment.position();
        buf.put(headerBlockFragment);
        headerBlockFragment.position(savedPos); // restore position for potential reuse
        if (padded) {
            // padding bytes are always 0
            for (int i = 0; i < padLength; i++) {
                buf.put((byte) 0);
            }
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
        buf.append(";headerBlockFragment.remaining=").append(headerBlockFragment.remaining());
    }

}
