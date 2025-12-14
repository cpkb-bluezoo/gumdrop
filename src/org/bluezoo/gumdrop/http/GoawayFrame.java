/*
 * GoawayFrame.java
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
 * A HTTP/2 GOAWAY frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
public class GoawayFrame extends Frame {

    private static final int FIXED_FIELDS_LENGTH = 8; // lastStream (4) + errorCode (4)

    int lastStream;
    int errorCode;
    ByteBuffer debug;

    /**
     * Constructor for a GOAWAY frame received from the client.
     * The payload ByteBuffer should be positioned at the start of payload data
     * with limit set to the end of payload data.
     */
    protected GoawayFrame(ByteBuffer payload) {
        lastStream = (payload.get() & 0x7f) << 24
                | (payload.get() & 0xff) << 16
                | (payload.get() & 0xff) << 8
                | (payload.get() & 0xff);
        errorCode = (payload.get() & 0xff) << 24
                | (payload.get() & 0xff) << 16
                | (payload.get() & 0xff) << 8
                | (payload.get() & 0xff);
        debug = payload.slice();
        payload.position(payload.limit()); // consume all
    }

    /**
     * Construct a GOAWAY frame to send to the client.
     */
    protected GoawayFrame(int lastStream, int errorCode, ByteBuffer debug) {
        this.lastStream = lastStream;
        this.errorCode = errorCode;
        this.debug = debug;
    }

    /**
     * Construct a GOAWAY frame to send to the client (convenience for byte[]).
     */
    protected GoawayFrame(int lastStream, int errorCode, byte[] debug) {
        this(lastStream, errorCode, ByteBuffer.wrap(debug));
    }

    public int getLength() {
        return debug.remaining() + FIXED_FIELDS_LENGTH;
    }

    public int getType() {
        return TYPE_GOAWAY;
    }

    public int getFlags() {
        return 0;
    }

    public int getStream() {
        return 0;
    }

    protected void write(ByteBuffer buf) {
        super.write(buf);
        buf.put((byte) ((lastStream >> 24) & 0x7f)); // NB reserved bit
        buf.put((byte) ((lastStream >> 16) & 0xff));
        buf.put((byte) ((lastStream >> 8) & 0xff));
        buf.put((byte) (lastStream & 0xff));
        buf.put((byte) ((errorCode >> 24) & 0xff));
        buf.put((byte) ((errorCode >> 16) & 0xff));
        buf.put((byte) ((errorCode >> 8) & 0xff));
        buf.put((byte) (errorCode & 0xff));
        // Save position to restore after put
        int savedPos = debug.position();
        buf.put(debug);
        debug.position(savedPos); // restore position for potential reuse
    }

}
