/*
 * VariableLengthEncoding.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.mqtt.codec;

import java.nio.ByteBuffer;

/**
 * MQTT variable-length integer encoding (MQTT 3.1.1 section 2.2.3,
 * MQTT 5.0 section 1.5.5).
 *
 * <p>Uses a variable-byte encoding scheme where each byte encodes 7 bits
 * of the value plus a continuation bit. Values 0 through 268,435,455
 * (0x0FFFFFFF) can be represented.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class VariableLengthEncoding {

    /** Maximum value that can be encoded: 268,435,455 */
    public static final int MAX_VALUE = 268_435_455;

    /** Maximum number of bytes in the encoded form. */
    public static final int MAX_BYTES = 4;

    /**
     * Sentinel returned by {@link #decode(ByteBuffer)} when the buffer
     * does not contain enough bytes to complete decoding.
     */
    public static final int NEEDS_MORE_DATA = -1;

    /**
     * Sentinel returned by {@link #decode(ByteBuffer)} when the encoded
     * value is malformed (e.g., more than 4 continuation bytes).
     */
    public static final int MALFORMED = -2;

    private VariableLengthEncoding() {
    }

    /**
     * Decodes a variable-length integer from the buffer.
     *
     * <p>On success, the buffer position is advanced past the encoded bytes.
     * On underflow ({@link #NEEDS_MORE_DATA}), the buffer position is
     * restored to its original value. On error ({@link #MALFORMED}), the
     * buffer position is undefined.
     *
     * @param buf the buffer to read from (in read mode)
     * @return the decoded value, {@link #NEEDS_MORE_DATA}, or {@link #MALFORMED}
     */
    public static int decode(ByteBuffer buf) {
        int startPos = buf.position();
        int value = 0;
        int multiplier = 1;

        for (int i = 0; i < MAX_BYTES; i++) {
            if (!buf.hasRemaining()) {
                buf.position(startPos);
                return NEEDS_MORE_DATA;
            }
            int encodedByte = buf.get() & 0xFF;
            value += (encodedByte & 0x7F) * multiplier;
            if ((encodedByte & 0x80) == 0) {
                return value;
            }
            multiplier *= 128;
        }
        return MALFORMED;
    }

    /**
     * Encodes a value using variable-length encoding and writes it to the buffer.
     *
     * @param buf the buffer to write to
     * @param value the value to encode (0 to {@link #MAX_VALUE})
     * @throws IllegalArgumentException if value is negative or exceeds the maximum
     */
    public static void encode(ByteBuffer buf, int value) {
        if (value < 0 || value > MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Value out of range: " + value);
        }
        do {
            int encodedByte = value % 128;
            value /= 128;
            if (value > 0) {
                encodedByte |= 0x80;
            }
            buf.put((byte) encodedByte);
        } while (value > 0);
    }

    /**
     * Returns the number of bytes needed to encode the given value.
     *
     * @param value the value
     * @return 1, 2, 3, or 4
     */
    public static int encodedLength(int value) {
        if (value <= 127) return 1;
        if (value <= 16383) return 2;
        if (value <= 2097151) return 3;
        return 4;
    }
}
