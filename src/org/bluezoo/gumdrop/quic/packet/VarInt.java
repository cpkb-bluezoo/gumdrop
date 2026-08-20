/*
 * VarInt.java
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

package org.bluezoo.gumdrop.quic.packet;

import java.nio.ByteBuffer;

/**
 * QUIC's variable-length integer encoding (RFC 9000 section 16), used
 * throughout the transport for lengths, packet-number-adjacent fields,
 * frame type codes, and frame fields.
 *
 * <p>The two most significant bits of the first byte encode the length
 * of the encoding (1, 2, 4, or 8 bytes); the remaining bits of those
 * bytes, in network byte order, are the value. This allows values up to
 * {@code 2^62 - 1} to be encoded in as few as one byte.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-16">RFC 9000 section 16</a>
 */
public final class VarInt {

    /** The largest value representable in QUIC's variable-length integer encoding. */
    public static final long MAX_VALUE = (1L << 62) - 1;

    private VarInt() {
    }

    /**
     * Returns the number of bytes {@link #encode} would use for the
     * given value.
     *
     * @param value the value to be encoded, must be in {@code [0, MAX_VALUE]}
     * @return 1, 2, 4, or 8
     */
    public static int encodedLength(long value) {
        if (value < 0 || value > MAX_VALUE) {
            throw new IllegalArgumentException("Value out of range for QUIC varint: " + value);
        }
        if (value <= 0x3fL) {
            return 1;
        }
        if (value <= 0x3fffL) {
            return 2;
        }
        if (value <= 0x3fffffffL) {
            return 4;
        }
        return 8;
    }

    /**
     * Returns the total encoded length (including the byte at
     * {@code position} itself) of the varint that starts at
     * {@code position}, without requiring the rest of the encoding to
     * be present in {@code buf} yet.
     *
     * <p>The two most significant bits of a varint's first byte
     * determine its total length by themselves (RFC 9000 section 16),
     * so this only needs that one byte to be available -- useful for
     * incremental parsing where a varint's continuation bytes might not
     * have arrived yet (unlike QUIC frames, which are always fully
     * contained within one already-decrypted packet, HTTP/3 framing
     * rides on a QUIC stream's reassembled byte sequence and can be
     * split arbitrarily across reads).
     *
     * @param buf the buffer
     * @param position the absolute position of the varint's first byte;
     *                 must be a valid, already-available index
     * @return the total encoded length, 1, 2, 4, or 8
     */
    public static int peekEncodedLength(ByteBuffer buf, int position) {
        int firstByte = buf.get(position) & 0xff;
        int lengthBits = (firstByte & 0xc0) >>> 6;
        switch (lengthBits) {
            case 0:
                return 1;
            case 1:
                return 2;
            case 2:
                return 4;
            default:
                return 8;
        }
    }

    /**
     * Encodes a value using the shortest possible encoding, writing it
     * to {@code out} at its current position and advancing the position.
     *
     * @param value the value to encode, must be in {@code [0, MAX_VALUE]}
     * @param out the destination buffer
     */
    public static void encode(long value, ByteBuffer out) {
        int length = encodedLength(value);
        switch (length) {
            case 1:
                out.put((byte) value);
                break;
            case 2:
                out.putShort((short) (value | 0x4000L));
                break;
            case 4:
                out.putInt((int) (value | 0x80000000L));
                break;
            default:
                out.putLong(value | 0xc000000000000000L);
                break;
        }
    }

    /**
     * Decodes a variable-length integer from {@code in} at its current
     * position, advancing the position past the encoding.
     *
     * @param in the source buffer, positioned at the start of the encoding
     * @return the decoded value
     */
    public static long decode(ByteBuffer in) {
        int firstByte = in.get(in.position()) & 0xff;
        int lengthBits = (firstByte & 0xc0) >>> 6;
        switch (lengthBits) {
            case 0: {
                int b = in.get() & 0xff;
                return b & 0x3f;
            }
            case 1: {
                int value = in.getShort() & 0xffff;
                return value & 0x3fff;
            }
            case 2: {
                int value = in.getInt();
                return ((long) value) & 0x3fffffffL;
            }
            default: {
                long value = in.getLong();
                return value & 0x3fffffffffffffffL;
            }
        }
    }
}
