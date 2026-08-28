/*
 * IntObjectHashMap.java
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

package org.bluezoo.gumdrop.util;

/**
 * A hash map keyed by primitive {@code int}, avoiding the boxed
 * {@code Integer} allocation a {@code Map<Integer, V>} pays on every
 * put/get/remove/containsKey call (issues #299/#335: HTTP/2 per-stream
 * bookkeeping keyed by stream ID does exactly this, once per stream
 * lifecycle event and once per {@code DATA} frame in each direction --
 * hot enough, on a connection that otherwise has no equivalent HTTP/1.1
 * cost, to be worth a dedicated primitive-keyed map rather than accepting
 * the boxing).
 *
 * <p>Open addressing with linear probing and tombstone-marked deletions.
 * Tombstones are reclaimed on the next resize, which is triggered by
 * {@code live + tombstoned} slot count rather than live count alone, so a
 * put/remove-heavy workload cannot accumulate tombstones indefinitely
 * without eventually triggering a compacting resize.
 *
 * <p>Values may not be {@code null} -- {@link #remove} is how an entry is
 * cleared. Not thread-safe; callers needing concurrent access must
 * synchronize externally, as with a plain {@link java.util.HashMap}.
 *
 * @param <V> the value type
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class IntObjectHashMap<V> {

    private static final byte EMPTY = 0;
    private static final byte OCCUPIED = 1;
    private static final byte DELETED = 2;

    private static final int DEFAULT_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.6f;

    private int[] keys;
    private Object[] values;
    private byte[] states;
    private int size; // live entries
    private int used; // live + tombstoned slots

    public IntObjectHashMap() {
        this(DEFAULT_CAPACITY);
    }

    public IntObjectHashMap(int initialCapacity) {
        int capacity = DEFAULT_CAPACITY;
        while (capacity < initialCapacity) {
            capacity <<= 1;
        }
        allocate(capacity);
    }

    private void allocate(int capacity) {
        keys = new int[capacity];
        values = new Object[capacity];
        states = new byte[capacity];
        size = 0;
        used = 0;
    }

    // Fibonacci/multiplicative hashing to spread the poorly-distributed
    // keys this map is actually used for -- small, sequential, always
    // odd (client-initiated) or always even (server-initiated) HTTP/2
    // stream IDs -- across the table, matching what a well-distributed
    // Integer.hashCode() would otherwise have given HashMap for free.
    private static int spread(int key) {
        int h = key * 0x9E3779B1;
        return h ^ (h >>> 16);
    }

    @SuppressWarnings("unchecked")
    public V get(int key) {
        int capacity = keys.length;
        int idx = spread(key) & (capacity - 1);
        for (int probes = 0; probes < capacity; probes++) {
            byte state = states[idx];
            if (state == EMPTY) {
                return null;
            }
            if (state == OCCUPIED && keys[idx] == key) {
                return (V) values[idx];
            }
            idx = (idx + 1) & (capacity - 1);
        }
        return null;
    }

    public boolean containsKey(int key) {
        return findOccupied(key) >= 0;
    }

    private int findOccupied(int key) {
        int capacity = keys.length;
        int idx = spread(key) & (capacity - 1);
        for (int probes = 0; probes < capacity; probes++) {
            byte state = states[idx];
            if (state == EMPTY) {
                return -1;
            }
            if (state == OCCUPIED && keys[idx] == key) {
                return idx;
            }
            idx = (idx + 1) & (capacity - 1);
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    public V put(int key, V value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if ((used + 1) > keys.length * LOAD_FACTOR) {
            resize(keys.length * 2);
        }
        int capacity = keys.length;
        int idx = spread(key) & (capacity - 1);
        int firstTombstone = -1;
        for (int probes = 0; probes < capacity; probes++) {
            byte state = states[idx];
            if (state == EMPTY) {
                int insertAt = firstTombstone >= 0 ? firstTombstone : idx;
                if (firstTombstone < 0) {
                    used++;
                }
                keys[insertAt] = key;
                values[insertAt] = value;
                states[insertAt] = OCCUPIED;
                size++;
                return null;
            }
            if (state == OCCUPIED && keys[idx] == key) {
                V old = (V) values[idx];
                values[idx] = value;
                return old;
            }
            if (state == DELETED && firstTombstone < 0) {
                firstTombstone = idx;
            }
            idx = (idx + 1) & (capacity - 1);
        }
        // Should not happen -- the load-factor check above always leaves
        // at least one empty slot -- but resize and retry defensively
        // rather than looping forever if it ever does.
        resize(capacity * 2);
        return put(key, value);
    }

    @SuppressWarnings("unchecked")
    public V remove(int key) {
        int idx = findOccupied(key);
        if (idx < 0) {
            return null;
        }
        V old = (V) values[idx];
        values[idx] = null;
        states[idx] = DELETED;
        size--;
        return old;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    public void clear() {
        allocate(DEFAULT_CAPACITY);
    }

    private void resize(int newCapacity) {
        int[] oldKeys = keys;
        Object[] oldValues = values;
        byte[] oldStates = states;
        allocate(newCapacity);
        for (int i = 0; i < oldStates.length; i++) {
            if (oldStates[i] == OCCUPIED) {
                putFresh(oldKeys[i], oldValues[i]);
            }
        }
    }

    // Used only during resize, where every key is known to be absent
    // from the freshly-allocated table -- skips the equality/tombstone
    // checks put() needs for the general case.
    private void putFresh(int key, Object value) {
        int capacity = keys.length;
        int idx = spread(key) & (capacity - 1);
        while (states[idx] != EMPTY) {
            idx = (idx + 1) & (capacity - 1);
        }
        keys[idx] = key;
        values[idx] = value;
        states[idx] = OCCUPIED;
        size++;
        used++;
    }

    /**
     * Returns a snapshot of the keys currently in the map, in unspecified
     * order.
     *
     * @return a new array of the current keys
     */
    public int[] keys() {
        int[] result = new int[size];
        int pos = 0;
        for (int i = 0; i < states.length; i++) {
            if (states[i] == OCCUPIED) {
                result[pos++] = keys[i];
            }
        }
        return result;
    }

    /**
     * Callback for {@link #forEach}/{@link #drainEach}.
     *
     * @param <V> the value type
     */
    public interface EntryConsumer<V> {
        void accept(int key, V value);
    }

    /**
     * Invokes {@code action} for every entry currently in the map, in
     * unspecified order. {@code action} must not mutate this map.
     *
     * @param action called once per entry
     */
    @SuppressWarnings("unchecked")
    public void forEach(EntryConsumer<? super V> action) {
        for (int i = 0; i < states.length; i++) {
            if (states[i] == OCCUPIED) {
                action.accept(keys[i], (V) values[i]);
            }
        }
    }

    /**
     * Removes and invokes {@code action} for every entry currently in the
     * map, in unspecified order -- equivalent to iterating with an
     * iterator that unconditionally removes each entry it visits, but
     * without needing a general mutable iterator. {@code action} may
     * safely add new entries to this map (e.g. re-registering a callback
     * from within its own invocation); entries added during the drain are
     * not themselves visited by this same call.
     *
     * @param action called once per entry that was present at the start
     *        of this call
     */
    @SuppressWarnings("unchecked")
    public void drainEach(EntryConsumer<? super V> action) {
        int[] oldKeys = keys;
        Object[] oldValues = values;
        byte[] oldStates = states;
        allocate(DEFAULT_CAPACITY);
        for (int i = 0; i < oldStates.length; i++) {
            if (oldStates[i] == OCCUPIED) {
                action.accept(oldKeys[i], (V) oldValues[i]);
            }
        }
    }
}
