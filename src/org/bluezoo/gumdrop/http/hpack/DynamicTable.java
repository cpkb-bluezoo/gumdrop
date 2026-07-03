/*
 * DynamicTable.java
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

package org.bluezoo.gumdrop.http.hpack;

import org.bluezoo.gumdrop.http.Header;

/**
 * HPACK dynamic table (RFC 7541 section 2.3, section 4).
 *
 * <p>Entries are addressed such that logical index 0 is the most recently
 * inserted entry; insertion happens at the front and eviction from the back
 * (the oldest entry), as required by RFC 7541 sections 2.3.3 and 4.4.
 *
 * <p>The table is backed by a growable circular buffer so that all HPACK hot
 * paths are cheap:
 * <ul>
 * <li>front insertion and back eviction are amortised O(1) (no array shift);</li>
 * <li>indexed lookup ({@link #get}) is O(1);</li>
 * <li>the table's octet size (RFC 7541 section 4.1) is kept in a running
 *     counter rather than recomputed by summing every entry.</li>
 * </ul>
 *
 * <p>This replaces an {@link java.util.ArrayList}-based implementation whose
 * eviction loop re-summed the entire table on every iteration (O(n&sup2;)) and
 * whose {@code add(0, …)} shifted the whole backing array (O(n)). Header block
 * decoding runs on every HTTP/2 request and the table contents are influenced
 * by the peer, so the quadratic behaviour was attacker-reachable.
 *
 * <p>Not thread-safe: each connection direction owns its own decoder/encoder
 * and therefore its own table.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7541">RFC 7541</a>
 */
final class DynamicTable {

    private static final int MIN_CAPACITY = 8;

    /**
     * Circular buffer of entries. The entry at logical index {@code i} lives at
     * physical index {@code (head + i) % entries.length}.
     */
    private Header[] entries = new Header[MIN_CAPACITY];

    /** Physical index of the newest (logical index 0) entry. */
    private int head;

    /** Number of entries currently stored. */
    private int count;

    /** Running RFC 7541 section 4.1 size of all stored entries, in octets. */
    private int byteSize;

    /**
     * @return the number of entries currently in the table
     */
    int size() {
        return count;
    }

    /**
     * @return the current size of the table in octets (RFC 7541 section 4.1)
     */
    int byteSize() {
        return byteSize;
    }

    /**
     * Returns the entry at the given logical index, where index 0 is the most
     * recently inserted entry (RFC 7541 section 2.3.3).
     *
     * @param index the logical index, 0 (newest) to {@link #size()}-1 (oldest)
     * @exception IndexOutOfBoundsException if the index is out of range
     */
    Header get(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(
                    "dynamic table index " + index + " out of range [0," + count + ")");
        }
        return entries[(head + index) % entries.length];
    }

    /**
     * Inserts a header at the front of the table per RFC 7541 section 4.4:
     * entries are evicted from the back until the header fits within
     * {@code maxSize}, then it is prepended. An entry larger than {@code maxSize}
     * is not added and the eviction empties the table.
     *
     * @param header the entry to add
     * @param maxSize the maximum permitted table size in octets
     */
    void insert(Header header, int maxSize) {
        int entrySize = HPACKConstants.headerSize(header);
        while (count > 0 && byteSize + entrySize > maxSize) {
            evictOldest();
        }
        if (entrySize <= maxSize) {
            addFirst(header);
        }
    }

    /**
     * Evicts entries from the back until the table size is at most
     * {@code maxSize} octets (RFC 7541 section 4.3, dynamic table size update).
     *
     * @param maxSize the new maximum table size in octets
     */
    void evictToFit(int maxSize) {
        while (count > 0 && byteSize > maxSize) {
            evictOldest();
        }
    }

    /**
     * Returns the logical index of the first entry equal to {@code header}, or
     * -1 if none. Index 0 is the newest entry.
     */
    int indexOf(Header header) {
        for (int i = 0; i < count; i++) {
            if (header.equals(entries[(head + i) % entries.length])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the logical index of the first entry whose name equals
     * {@code name}, or -1 if none. Index 0 is the newest entry.
     */
    int indexOfName(String name) {
        for (int i = 0; i < count; i++) {
            Header header = entries[(head + i) % entries.length];
            if (header != null && name.equals(header.getName())) {
                return i;
            }
        }
        return -1;
    }

    private void addFirst(Header header) {
        if (count == entries.length) {
            grow();
        }
        head = (head - 1 + entries.length) % entries.length;
        entries[head] = header;
        count++;
        byteSize += HPACKConstants.headerSize(header);
    }

    private void evictOldest() {
        int tail = (head + count - 1) % entries.length;
        byteSize -= HPACKConstants.headerSize(entries[tail]);
        entries[tail] = null; // release reference
        count--;
    }

    private void grow() {
        Header[] copy = new Header[entries.length << 1];
        for (int i = 0; i < count; i++) {
            copy[i] = entries[(head + i) % entries.length];
        }
        entries = copy;
        head = 0;
    }

}
