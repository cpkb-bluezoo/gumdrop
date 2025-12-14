/*
 * PushPromiseFrame.java
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
 * A HTTP/2 PUSH_PROMISE frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
public class PushPromiseFrame extends Frame {

    private static final int PROMISED_STREAM_LENGTH = 4;

    int stream;
    boolean padded;
    boolean endHeaders;

    int padLength;
    int promisedStream;
    ByteBuffer headerBlockFragment;

    /**
     * Constructor for a PUSH_PROMISE frame received from the client.
     * The payload ByteBuffer should be positioned at the start of payload data
     * with limit set to the end of payload data.
     */
    protected PushPromiseFrame(int flags, int stream, ByteBuffer payload) {
        this.stream = stream;
        padded = (flags & FLAG_PADDED) != 0;
        endHeaders = (flags & FLAG_END_HEADERS) != 0;
        
        int endPos = payload.limit();
        
        if (padded) {
            padLength = payload.get() & 0xff;
            endPos -= padLength; // Exclude padding from header block
        }
        promisedStream = (payload.get() & 0x7f) << 24
                | (payload.get() & 0xff) << 16
                | (payload.get() & 0xff) << 8
                | (payload.get() & 0xff);
        
        // header block fragment is remaining data minus padding
        int headerBlockLength = endPos - payload.position();
        int savedLimit = payload.limit();
        payload.limit(payload.position() + headerBlockLength);
        headerBlockFragment = payload.slice();
        payload.limit(savedLimit);
        payload.position(savedLimit); // consume all including padding
    }

    /**
     * Construct a push promise frame to send to the client.
     */
    protected PushPromiseFrame(int stream, boolean padded, boolean endHeaders, int padLength, 
                               int promisedStream, ByteBuffer headerBlockFragment) {
        this.stream = stream;
        this.padded = padded;
        this.endHeaders = endHeaders;
        this.padLength = padLength;
        this.promisedStream = promisedStream;
        this.headerBlockFragment = headerBlockFragment;
    }

    /**
     * Construct a push promise frame to send to the client (convenience for byte[]).
     */
    protected PushPromiseFrame(int stream, boolean padded, boolean endHeaders, int padLength, 
                               int promisedStream, byte[] headerBlockFragment) {
        this(stream, padded, endHeaders, padLength, promisedStream, ByteBuffer.wrap(headerBlockFragment));
    }

    public int getLength() {
        int length = headerBlockFragment.remaining() + PROMISED_STREAM_LENGTH;
        if (padded) {
            length += (1 + padLength);
        }
        return length;
    }

    public int getType() {
        return TYPE_PUSH_PROMISE;
    }

    public int getFlags() {
        return (padded ? FLAG_PADDED : 0)
            | (endHeaders ? FLAG_END_HEADERS : 0);
    }

    public int getStream() {
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

}
