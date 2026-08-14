/*
 * PrefixedInteger.java
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

package org.bluezoo.gumdrop.http.qpack;

import java.net.ProtocolException;
import java.nio.ByteBuffer;

/**
 * The N-bit prefix integer encoding used throughout QPACK (RFC 9204
 * section 4.1.1), reusing RFC 7541 section 5.1's format unmodified --
 * QPACK just uses more prefix widths than HPACK does. Unlike HPACK's
 * copy of the same algorithm (an implementation detail private to that
 * package's {@code Encoder}/{@code Decoder}), this is a shared utility
 * since every QPACK field line representation and instruction uses it
 * with a different prefix width.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7541#section-5.1">RFC 7541 section 5.1</a>
 */
final class PrefixedInteger {

    private PrefixedInteger() {
    }

    /**
     * Encodes a value as an N-bit prefix integer, combined with high
     * bits already assigned to flags earlier in the same byte.
     *
     * @param buf the destination buffer
     * @param highBits the flag bits occupying the top {@code 8 - prefixBits}
     *                 bits of the first byte, already shifted into position
     * @param value the value to encode
     * @param prefixBits the number of low bits of the first byte
     *                   available for the value, 1-8
     */
    static void encode(ByteBuffer buf, int highBits, int value, int prefixBits) {
        int prefixMax = (1 << prefixBits) - 1;
        if (value < prefixMax) {
            buf.put((byte) (highBits | value));
            return;
        }
        buf.put((byte) (highBits | prefixMax));
        int remaining = value - prefixMax;
        while (remaining >= 128) {
            buf.put((byte) ((remaining % 128) | 0x80));
            remaining /= 128;
        }
        buf.put((byte) remaining);
    }

    /**
     * Decodes an N-bit prefix integer.
     *
     * @param buf the buffer to read continuation bytes from, positioned
     *            just after the first byte
     * @param firstByte the first byte, including both the flag bits and
     *                  the prefix value bits
     * @param prefixBits the number of low bits of {@code firstByte}
     *                   that hold the value, 1-8
     * @return the decoded value
     * @throws ProtocolException if the encoding overflows or the buffer
     *                           underflows
     */
    static long decode(ByteBuffer buf, int firstByte, int prefixBits) throws ProtocolException {
        int prefixMax = (1 << prefixBits) - 1;
        long value = firstByte & prefixMax;
        if (value < prefixMax) {
            return value;
        }
        int shift = 0;
        byte b;
        do {
            if (shift > 63) {
                throw new ProtocolException("QPACK integer overflow");
            }
            if (!buf.hasRemaining()) {
                throw new ProtocolException("QPACK integer underflow");
            }
            b = buf.get();
            value += ((long) (b & 0x7f)) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }
}
