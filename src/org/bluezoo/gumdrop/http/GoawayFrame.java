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

    int lastStream;
    int errorCode;
    byte[] debug;

    protected GoawayFrame(byte[] payload) {
        int offset = 0;
        lastStream = ((int) payload[offset++] & 0x7f) << 24
                | ((int) payload[offset++] & 0xff) << 16
                | ((int) payload[offset++] & 0xff) << 8
                | ((int) payload[offset++] & 0xff);
        errorCode = ((int) payload[offset++] & 0xff) << 24
                | ((int) payload[offset++] & 0xff) << 16
                | ((int) payload[offset++] & 0xff) << 8
                | ((int) payload[offset++] & 0xff);
        debug = new byte[payload.length - 8];
        System.arraycopy(payload, 8, debug, 0, debug.length);
    }

    /**
     * Construct a GOAWAY frame.
     */
    protected GoawayFrame(int lastStream, int errorCode, byte[] debug) {
        this.lastStream = lastStream;
        this.errorCode = errorCode;
        this.debug = debug;
    }

    public int getLength() {
        return debug.length + 8;
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
        buf.put(debug);
    }

}
