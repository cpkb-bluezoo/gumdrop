/*
 * RstStreamFrame.java
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A HTTP/2 RST_STREAM frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
public class RstStreamFrame extends Frame {

    int stream;

    int errorCode;

    protected RstStreamFrame(int stream, byte[] payload) throws ProtocolException {
        this.stream = stream;
        errorCode = ((int) payload[0] & 0xff) << 24
            | ((int) payload[1] & 0xff) << 16
            | ((int) payload[2] & 0xff) << 8
            | ((int) payload[3] & 0xff);
    }

    /**
     * Construct an error frame to send to the client.
     */
    protected RstStreamFrame(int stream, int errorCode) {
        this.stream = stream;
        this.errorCode = errorCode;
    }

    public int getLength() {
        return 4; // always 4 bytes
    }

    public int getType() {
        return TYPE_RST_STREAM;
    }

    public int getFlags() {
        return 0;
    }

    public int getStream() {
        return stream;
    }

    protected void write(ByteBuffer buf) {
        super.write(buf);
        buf.put((byte) ((errorCode >> 24) & 0xff));
        buf.put((byte) ((errorCode >> 16) & 0xff));
        buf.put((byte) ((errorCode >> 8) & 0xff));
        buf.put((byte) (errorCode & 0xff));
    }

}
