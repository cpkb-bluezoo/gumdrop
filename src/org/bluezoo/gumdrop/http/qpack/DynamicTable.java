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

package org.bluezoo.gumdrop.http.qpack;

import org.bluezoo.gumdrop.http.Header;

/**
 * QPACK dynamic table (RFC 9204 section 3.2), absolute indexing.
 *
 * <p>Entries are addressed by an ever-increasing "absolute index"
 * assigned at insertion time (0, 1, 2, ...) -- unlike
 * {@link org.bluezoo.gumdrop.http.hpack.DynamicTable HPACK's} relative
 * indexing, QPACK indices never shift as new entries arrive; only the
 * <em>window</em> of which indices are still live shrinks as entries
 * are evicted from the oldest end. This means insertion happens at the
 * newest (back) end rather than HPACK's front, the mirror image of
 * that class's circular-buffer layout, but the same underlying growable
 * circular array for O(1) insertion, eviction, and indexed lookup.
 *
 * <p>The same class backs both roles: the encoder's {@link #insert}
 * refuses to evict a still-referenced, unacknowledged entry (RFC 9204
 * section 3.2.2) and reports failure so the caller falls back to a
 * literal; the decoder's {@link #insertMirrored} unconditionally
 * mirrors whatever the peer encoder already decided, since the decoder
 * does its own eviction-safety accounting by definition (it never
 * originates an insert).
 *
 * <p>Not thread-safe: each connection direction owns its own
 * encoder/decoder and therefore its own table.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-3.2">RFC 9204 section 3.2</a>
 */
final class DynamicTable {

    private static final int MIN_CAPACITY = 8;

    private static final class Entry {
        final String name;
        final String value;
        int refCount;

        Entry(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    /**
     * Circular buffer of entries, oldest first. Entry at logical index
     * {@code i} (0 = oldest) lives at physical index
     * {@code (head + i) % entries.length}.
     */
    private Entry[] entries = new Entry[MIN_CAPACITY];

    /** Physical index of the oldest (lowest absolute index) live entry. */
    private int head;

    /** Number of entries currently stored. */
    private int count;

    /** Absolute index of the oldest live entry (entries below this have been evicted). */
    private long baseIndex;

    /** Running RFC 9204 section 3.2.1 size of all stored entries, in octets. */
    private int byteSize;

    /** The configured capacity in octets (RFC 9204 section 3.2.1, section 4.3.1). */
    private int capacity;

    DynamicTable(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Returns the working capacity in octets.
     *
     * @return the capacity
     */
    int getCapacity() {
        return capacity;
    }

    /**
     * Returns the total number of entries ever inserted -- the table's
     * "Insert Count" (RFC 9204 section 2.1.1).
     *
     * @return the insert count
     */
    long getInsertCount() {
        return baseIndex + count;
    }

    /**
     * Returns the absolute index of the oldest still-live entry.
     *
     * @return the base index
     */
    long getBaseIndex() {
        return baseIndex;
    }

    /**
     * RFC 9204 section 3.2.1: an entry's accounted size is its name and
     * value lengths (in octets) plus 32, the same formula HPACK uses
     * (RFC 7541 section 4.1).
     *
     * @param name the field name
     * @param value the field value
     * @return the accounted size in octets
     */
    static int entrySize(String name, String value) {
        return name.length() + value.length() + 32;
    }

    /**
     * Looks up a live entry by absolute index.
     *
     * @param absoluteIndex the absolute index
     * @return the entry, or null if not live (evicted, or never inserted)
     */
    Header get(long absoluteIndex) {
        if (absoluteIndex < baseIndex) {
            return null;
        }
        long pos = absoluteIndex - baseIndex;
        if (pos >= count) {
            return null;
        }
        Entry entry = entries[(head + (int) pos) % entries.length];
        return new Header(entry.name, entry.value);
    }

    /**
     * Encoder-side insert: evicts unreferenced entries from the oldest
     * end as needed, but refuses (returns -1) rather than evict a
     * still-referenced entry or exceed capacity with a single oversized
     * entry.
     *
     * @param name the field name
     * @param value the field value
     * @return the new entry's absolute index, or -1 if it could not be inserted
     */
    long insert(String name, String value) {
        int size = entrySize(name, value);
        if (size > capacity) {
            return -1;
        }
        while (byteSize + size > capacity) {
            if (count == 0) {
                return -1;
            }
            Entry oldest = entries[head];
            if (oldest.refCount != 0) {
                return -1;
            }
            evictOldest();
        }
        long index = getInsertCount();
        addNewest(new Entry(name, value));
        return index;
    }

    /**
     * Decoder-side insert: unconditionally mirrors an insert
     * instruction already accepted by the peer's encoder, evicting
     * oldest entries as needed regardless of any local reference count
     * (the decoder never originates evictions).
     *
     * @param name the field name
     * @param value the field value
     */
    void insertMirrored(String name, String value) {
        int size = entrySize(name, value);
        if (size > capacity) {
            return; // peer's own accounting should prevent this; defensively ignore
        }
        while (byteSize + size > capacity && count > 0) {
            evictOldest();
        }
        addNewest(new Entry(name, value));
    }

    /**
     * Updates the working capacity (Set Dynamic Table Capacity, RFC
     * 9204 section 4.3.1), evicting oldest entries if the table must
     * shrink.
     *
     * @param newCapacity the new capacity in octets
     */
    void setCapacity(int newCapacity) {
        this.capacity = newCapacity;
        while (byteSize > capacity && count > 0) {
            evictOldest();
        }
    }

    /**
     * The result of {@link DynamicTable#find}.
     */
    static final class FindResult {
        final long absoluteIndex;
        final boolean fullMatch;

        FindResult(long absoluteIndex, boolean fullMatch) {
            this.absoluteIndex = absoluteIndex;
            this.fullMatch = fullMatch;
        }
    }

    /**
     * Finds a match visible to a reference with absolute index strictly
     * less than {@code visibleBefore} (e.g. the encoder's Known
     * Received Count, for a non-blocking encoding policy). Prefers a
     * full name+value match over a name-only one.
     *
     * @param name the field name to match
     * @param value the field value to match
     * @param visibleBefore only entries with an absolute index strictly
     *                      less than this are considered
     * @return the match, or null if none
     */
    FindResult find(String name, String value, long visibleBefore) {
        long nameOnlyIndex = -1;
        for (int i = 0; i < count; i++) {
            long abs = baseIndex + i;
            if (abs >= visibleBefore) {
                break; // entries are insertion-ordered; later ones are even less visible
            }
            Entry entry = entries[(head + i) % entries.length];
            if (entry.name.equals(name)) {
                if (entry.value.equals(value)) {
                    return new FindResult(abs, true);
                }
                if (nameOnlyIndex == -1) {
                    nameOnlyIndex = abs;
                }
            }
        }
        return nameOnlyIndex == -1 ? null : new FindResult(nameOnlyIndex, false);
    }

    /**
     * Marks {@code absoluteIndex} as referenced by an outstanding field
     * section (encoder side -- protects it from eviction until
     * released).
     *
     * @param absoluteIndex the entry's absolute index
     */
    void addRef(long absoluteIndex) {
        int pos = positionOf(absoluteIndex);
        if (pos >= 0) {
            entries[pos].refCount++;
        }
    }

    /**
     * Releases a reference previously taken via {@link #addRef} (the
     * field section that held it has been acknowledged or cancelled).
     *
     * @param absoluteIndex the entry's absolute index
     */
    void releaseRef(long absoluteIndex) {
        int pos = positionOf(absoluteIndex);
        if (pos >= 0 && entries[pos].refCount > 0) {
            entries[pos].refCount--;
        }
    }

    private int positionOf(long absoluteIndex) {
        if (absoluteIndex < baseIndex) {
            return -1;
        }
        long pos = absoluteIndex - baseIndex;
        if (pos >= count) {
            return -1;
        }
        return (head + (int) pos) % entries.length;
    }

    private void addNewest(Entry entry) {
        if (count == entries.length) {
            grow();
        }
        int pos = (head + count) % entries.length;
        entries[pos] = entry;
        count++;
        byteSize += entrySize(entry.name, entry.value);
    }

    private void evictOldest() {
        Entry evicted = entries[head];
        byteSize -= entrySize(evicted.name, evicted.value);
        entries[head] = null; // release reference
        head = (head + 1) % entries.length;
        count--;
        baseIndex++;
    }

    private void grow() {
        Entry[] copy = new Entry[entries.length << 1];
        for (int i = 0; i < count; i++) {
            copy[i] = entries[(head + i) % entries.length];
        }
        entries = copy;
        head = 0;
    }
}
