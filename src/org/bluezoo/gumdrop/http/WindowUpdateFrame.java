/*
 * WindowUpdateFrame.java
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
 * A HTTP/2 WINDOW_UPDATE frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
public class WindowUpdateFrame extends Frame {

    int stream;

    int windowSizeIncrement;

    /**
     * Constructor for a window update frame received from the client.
     */
    protected WindowUpdateFrame(int stream, byte[] payload) throws ProtocolException {
        this.stream = stream;
        int offset = 0;
        windowSizeIncrement = ((int) payload[offset++] & 0x7f) << 24
                | ((int) payload[offset++] & 0xff) << 16
                | ((int) payload[offset++] & 0xff) << 8
                | ((int) payload[offset++] & 0xff);
        if (windowSizeIncrement == 0) {
            throw new ProtocolException();
        }
    }

    /**
     * Constructor for a window update frame to be sent to the client.
     */
    protected WindowUpdateFrame(int stream, int windowSizeIncrement) {
        this.stream = stream;
        this.windowSizeIncrement = windowSizeIncrement;
    }

    public int getLength() {
        return 4;
    }

    public int getType() {
        return TYPE_WINDOW_UPDATE;
    }

    public int getFlags() {
        return 0;
    }

    public int getStream() {
        return stream;
    }

    protected void write(ByteBuffer buf) {
        super.write(buf);
        buf.put((byte) ((windowSizeIncrement >> 24) & 0x7f)); // NB reserved bit
        buf.put((byte) ((windowSizeIncrement >> 16) & 0xff));
        buf.put((byte) ((windowSizeIncrement >> 8) & 0xff));
        buf.put((byte) (windowSizeIncrement & 0xff));
    }

    protected void appendFields(StringBuilder buf) {
        super.appendFields(buf);
        buf.append(";windowSizeIncrement=").append(windowSizeIncrement);
    }

}
