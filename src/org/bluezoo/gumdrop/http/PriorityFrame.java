/*
 * PriorityFrame.java
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
 * A HTTP/2 PRIORITY frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
class PriorityFrame extends Frame {

    int stream;

    int streamDependency;
    boolean streamDependencyExclusive;
    int weight;

    /**
     * Constructor for a priority frame received from the client.
     */
    protected PriorityFrame(int stream, byte[] payload) {
        this.stream = stream;
        int offset = 0;
        int sd1 = (int) payload[offset++] & 0xff;
        streamDependencyExclusive = (sd1 & 0x80) != 0;
        streamDependency = (sd1 & 0x7f) << 24
            | ((int) payload[offset++] & 0xff) << 16
            | ((int) payload[offset++] & 0xff) << 8
            | ((int) payload[offset++] & 0xff);
        weight = ((int) payload[offset++] & 0xff);
    }

    /**
     * Constructor for a priority frame to send to the client.
     */
    protected PriorityFrame(int stream, int streamDependency, boolean streamDependencyExclusive, int weight) {
        this.stream = stream;
        this.streamDependency = streamDependency;
        this.streamDependencyExclusive = streamDependencyExclusive;
        this.weight = weight;
    }

    protected int getLength() {
        return 5; // always 5 bytes
    }

    protected int getType() {
        return TYPE_PRIORITY;
    }

    protected int getFlags() {
        return 0;
    }

    protected int getStream() {
        return stream;
    }

    protected void write(ByteBuffer buf) {
        super.write(buf);
        int sd1 = (streamDependency >> 24) & 0x7f;
        if (streamDependencyExclusive) {
            sd1 |= 0x80;
        }
        buf.put((byte) sd1);
        buf.put((byte) ((streamDependency >> 16) & 0xff));
        buf.put((byte) ((streamDependency >> 8) & 0xff));
        buf.put((byte) (streamDependency & 0xff));
        buf.put((byte) (weight & 0xff));
    }

}
