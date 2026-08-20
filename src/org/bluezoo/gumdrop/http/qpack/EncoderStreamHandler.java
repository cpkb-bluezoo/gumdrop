/*
 * EncoderStreamHandler.java
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

/**
 * Callback interface for receiving parsed QPACK encoder-stream
 * instructions (RFC 9204 section 4.3) from an {@link EncoderStreamParser}.
 * {@link Decoder} implements this directly: each callback method is
 * exactly what mirroring that instruction into the decoder's own
 * {@link DynamicTable} requires.
 *
 * <p>Index interpretation is left to the implementor: a name
 * reference's dynamic-table index ({@code isStaticTable} false) and a
 * {@link #duplicate}'s index are both <em>relative</em> indices (RFC
 * 9204 section 3.2.6 -- 0 is the most recently inserted entry at the
 * time the instruction was written), resolvable to an absolute index
 * only by whoever holds the live table state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see EncoderStreamParser
 * @see EncoderStreamWriter
 */
interface EncoderStreamHandler {

    /**
     * RFC 9204 section 4.3.1: set the dynamic table's capacity in octets.
     *
     * @param capacity the new capacity
     */
    void setDynamicTableCapacity(long capacity);

    /**
     * RFC 9204 section 4.3.2: insert an entry with a referenced name
     * (static if {@code isStaticTable}, else dynamic-relative) and a
     * literal value.
     *
     * @param isStaticTable true if {@code nameIndex} refers to the static table
     * @param nameIndex the name's index (static-table-absolute, or dynamic-table-relative)
     * @param value the literal value
     */
    void insertWithNameReference(boolean isStaticTable, long nameIndex, byte[] value);

    /**
     * RFC 9204 section 4.3.3: insert an entry with both name and value
     * given literally.
     *
     * @param name the literal name
     * @param value the literal value
     */
    void insertWithLiteralName(byte[] name, byte[] value);

    /**
     * RFC 9204 section 4.3.4: duplicate an existing entry, identified
     * by a dynamic-table-relative index.
     *
     * @param relativeIndex the relative index of the entry to duplicate
     */
    void duplicate(long relativeIndex);

    /**
     * Called when an instruction cannot be parsed (as opposed to
     * merely being incomplete so far -- see {@link EncoderStreamParser}).
     *
     * @param message a human-readable description of the error
     */
    void instructionError(String message);
}
