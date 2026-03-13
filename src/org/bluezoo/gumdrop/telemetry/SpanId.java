/*
 * SpanId.java
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
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Immutable wrapper for 8-byte span IDs.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class SpanId {

    private static final int LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] bytes;
    private volatile String hex;

    /**
     * Generates a new random span ID.
     *
     * @return a new span ID
     */
    public static SpanId generate() {
        byte[] id = new byte[LENGTH];
        RANDOM.nextBytes(id);
        return new SpanId(id);
    }

    /**
     * Creates a span ID from the given bytes.
     * The array is defensively copied.
     *
     * @param bytes the 8-byte span ID
     * @throws IllegalArgumentException if bytes is null or not 8 bytes
     */
    public SpanId(byte[] bytes) {
        if (bytes == null || bytes.length != LENGTH) {
            throw new IllegalArgumentException("spanId must be 8 bytes");
        }
        this.bytes = Arrays.copyOf(bytes, LENGTH);
    }

    /**
     * Returns the span ID as a lowercase hexadecimal string.
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
     * Writes the span ID bytes directly to the given buffer.
     *
     * @param buf the buffer to write to (must have at least 8 bytes remaining)
     */
    public void writeTo(ByteBuffer buf) {
        buf.put(bytes);
    }

    /**
     * Returns a copy of the span ID bytes.
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
        SpanId other = (SpanId) obj;
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
