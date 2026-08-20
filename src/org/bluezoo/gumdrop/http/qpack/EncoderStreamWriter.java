/*
 * EncoderStreamWriter.java
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

import java.nio.ByteBuffer;

/**
 * Writes QPACK encoder-stream instructions (RFC 9204 section 4.3) into
 * a {@link ByteBuffer}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see EncoderStreamParser
 */
final class EncoderStreamWriter {

    private EncoderStreamWriter() {
    }

    /**
     * Writes a Set Dynamic Table Capacity instruction (RFC 9204 section 4.3.1).
     *
     * @param out the destination buffer
     * @param capacity the new capacity in octets
     */
    static void writeSetDynamicTableCapacity(ByteBuffer out, long capacity) {
        PrefixedInteger.encode(out, 0x20, capacity, 5);
    }

    /**
     * Writes an Insert With Name Reference instruction (RFC 9204 section 4.3.2).
     *
     * @param out the destination buffer
     * @param isStaticTable true if {@code nameIndex} refers to the static table
     * @param nameIndex the name's index (static-table-absolute, or dynamic-table-relative)
     * @param value the literal value
     */
    static void writeInsertWithNameReference(ByteBuffer out, boolean isStaticTable, long nameIndex, byte[] value) {
        int tBit = isStaticTable ? 0x40 : 0x00;
        PrefixedInteger.encode(out, 0x80 | tBit, nameIndex, 6);
        QPACKStrings.write(out, value, 7, 0x00);
    }

    /**
     * Writes an Insert With Literal Name instruction (RFC 9204 section 4.3.3).
     *
     * @param out the destination buffer
     * @param name the literal name
     * @param value the literal value
     */
    static void writeInsertWithLiteralName(ByteBuffer out, byte[] name, byte[] value) {
        QPACKStrings.write(out, name, 5, 0x40);
        QPACKStrings.write(out, value, 7, 0x00);
    }

    /**
     * Writes a Duplicate instruction (RFC 9204 section 4.3.4).
     *
     * @param out the destination buffer
     * @param relativeIndex the relative index of the entry to duplicate
     */
    static void writeDuplicate(ByteBuffer out, long relativeIndex) {
        PrefixedInteger.encode(out, 0x00, relativeIndex, 5);
    }
}
