/*
 * PriorityFrame.java
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
 * A HTTP/2 PRIORITY frame.
 *
 * @author Chris Burdess
 * @see https://www.rfc-editor.org/rfc/rfc7540
 */
public class PriorityFrame extends Frame {

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

    public int getLength() {
        return 5; // always 5 bytes
    }

    public int getType() {
        return TYPE_PRIORITY;
    }

    public int getFlags() {
        return 0;
    }

    public int getStream() {
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
