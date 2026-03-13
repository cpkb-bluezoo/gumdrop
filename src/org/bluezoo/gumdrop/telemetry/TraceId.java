/*
 * TraceId.java
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

package org.bluezoo.gumdrop.telemetry;

import org.bluezoo.util.ByteArrays;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Immutable wrapper for 16-byte trace IDs.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class TraceId {

    private static final int LENGTH = 16;

    private final byte[] bytes;
    private volatile String hex;

    /**
     * Creates a trace ID from the given bytes.
     * The array is defensively copied.
     *
     * @param bytes the 16-byte trace ID
     * @throws IllegalArgumentException if bytes is null or not 16 bytes
     */
    public TraceId(byte[] bytes) {
        if (bytes == null || bytes.length != LENGTH) {
            throw new IllegalArgumentException("traceId must be 16 bytes");
        }
        this.bytes = Arrays.copyOf(bytes, LENGTH);
    }

    /**
     * Returns the trace ID as a lowercase hexadecimal string.
     * The result is lazily cached.
     */
    public String toHexString() {
        String h = hex;
        if (h == null) {
            synchronized (this) {
                h = hex;
                if (h == null) {
                    hex = h = ByteArrays.toHexString(bytes);
                }
            }
        }
        return h;
    }

    /**
     * Writes the trace ID bytes directly to the given buffer.
     *
     * @param buf the buffer to write to (must have at least 16 bytes remaining)
     */
    public void writeTo(ByteBuffer buf) {
        buf.put(bytes);
    }

    /**
     * Returns a copy of the trace ID bytes.
     */
    public byte[] getBytes() {
        return Arrays.copyOf(bytes, LENGTH);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        TraceId other = (TraceId) obj;
        return Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return toHexString();
    }

}
