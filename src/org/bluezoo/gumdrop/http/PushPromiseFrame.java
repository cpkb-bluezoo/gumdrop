/*
 * PushPromiseFrame.java
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
