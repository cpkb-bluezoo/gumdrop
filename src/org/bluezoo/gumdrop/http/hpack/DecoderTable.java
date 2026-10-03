/*
 * DecoderTable.java
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

/**
 * The HPACK decoder's dynamic table (RFC 7541 section 2.3.2): a bounded list
 * of entries, newest first, evicted from the oldest end.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class DecoderTable {

    private static final int MIN_CAPACITY = 8;

    private TableEntry[] entries = new TableEntry[MIN_CAPACITY];
    private int head;
    private int count;
    private int byteSize;

    /** Number of entries. */
    int size() {
        return count;
    }

    /** RFC 7541 section 4.1: sum of the sizes of the entries. */
    int byteSize() {
        return byteSize;
    }

    /** Entry at {@code index}, 0 being the newest. */
    TableEntry get(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(
                    "dynamic table index " + index + " out of range [0," + count + ")");
        }
        return entries[(head + index) % entries.length];
    }

    /**
     * Adds an entry as the newest, evicting from the oldest end until it
     * fits within {@code maxSize}. RFC 7541 section 4.4: an entry larger
     * than the maximum empties the table and is not added.
     */
    void insert(TableEntry entry, int maxSize) {
        int entrySize = entry.size();
        while (count > 0 && byteSize + entrySize > maxSize) {
            evictOldest();
        }
        if (entrySize <= maxSize) {
            addFirst(entry);
        }
    }

    /** Evicts oldest entries until the table is no larger than {@code maxSize}. */
    void evictToFit(int maxSize) {
        while (count > 0 && byteSize > maxSize) {
            evictOldest();
        }
    }

    private void addFirst(TableEntry entry) {
        if (count == entries.length) {
            grow();
        }
        head = (head - 1 + entries.length) % entries.length;
        entries[head] = entry;
        count++;
        byteSize += entry.size();
    }

    private void evictOldest() {
        int tail = (head + count - 1) % entries.length;
        byteSize -= entries[tail].size();
        entries[tail] = null;
        count--;
    }

    private void grow() {
        TableEntry[] copy = new TableEntry[entries.length << 1];
        for (int i = 0; i < count; i++) {
            copy[i] = entries[(head + i) % entries.length];
        }
        entries = copy;
        head = 0;
    }

}
