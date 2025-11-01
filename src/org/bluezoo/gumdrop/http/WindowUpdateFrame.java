/*
 * WindowUpdateFrame.java
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
 * A HTTP/2 WINDOW_UPDATE frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
class WindowUpdateFrame extends Frame {

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

    protected int getLength() {
        return 4;
    }

    protected int getType() {
        return TYPE_WINDOW_UPDATE;
    }

    protected int getFlags() {
        return 0;
    }

    protected int getStream() {
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
