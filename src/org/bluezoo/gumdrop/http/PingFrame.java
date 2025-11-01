/*
 * PingFrame.java
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
 * A HTTP/2 PING frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
final class PingFrame extends Frame {

    boolean ack;

    /**
     * Constructor for a PING frame read from the client.
     */
    protected PingFrame(int flags) {
        ack = (flags & FLAG_ACK) != 0;
    }

    /**
     * Constructor for a PING frame to be sent to the client.
     */
    protected PingFrame(boolean ack) {
        this.ack = ack;
    }

    protected int getLength() {
        return 8;
    }

    protected int getType() {
        return TYPE_PING;
    }

    protected int getFlags() {
        return ack ? FLAG_ACK : 0;
    }

    protected int getStream() {
        return 0;
    }

    protected void write(ByteBuffer buf) {
        super.write(buf);
        buf.put(new byte[8]);
    }

}
