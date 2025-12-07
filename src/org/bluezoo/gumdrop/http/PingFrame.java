/*
 * PingFrame.java
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
 * A HTTP/2 PING frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
public final class PingFrame extends Frame {

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

    public int getLength() {
        return 8;
    }

    public int getType() {
        return TYPE_PING;
    }

    public int getFlags() {
        return ack ? FLAG_ACK : 0;
    }

    public int getStream() {
        return 0;
    }

    protected void write(ByteBuffer buf) {
        super.write(buf);
        buf.put(new byte[8]);
    }

}
