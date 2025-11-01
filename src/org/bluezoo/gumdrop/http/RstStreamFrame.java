/*
 * RstStreamFrame.java
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A HTTP/2 RST_STREAM frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
class RstStreamFrame extends Frame {

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

    protected int getLength() {
        return 4; // always 4 bytes
    }

    protected int getType() {
        return TYPE_RST_STREAM;
    }

    protected int getFlags() {
        return 0;
    }

    protected int getStream() {
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
