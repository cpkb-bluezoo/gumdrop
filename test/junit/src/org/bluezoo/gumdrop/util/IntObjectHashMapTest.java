/*
 * IntObjectHashMapTest.java
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

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link IntObjectHashMap} (issues #299/#335), the
 * primitive-int-keyed replacement for the boxed-{@code Integer}-keyed maps
 * used for HTTP/2 per-stream bookkeeping.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IntObjectHashMapTest {

    @Test
    public void testPutGetRemoveBasics() {
        IntObjectHashMap<String> map = new IntObjectHashMap<String>();
        assertTrue(map.isEmpty());
        assertNull(map.get(1));
        assertFalse(map.containsKey(1));

        assertNull(map.put(1, "one"));
        assertEquals("one", map.get(1));
        assertTrue(map.containsKey(1));
        assertEquals(1, map.size());
        assertFalse(map.isEmpty());

        assertEquals("one", map.put(1, "ONE"));
        assertEquals("ONE", map.get(1));
        assertEquals(1, map.size());

        assertEquals("ONE", map.remove(1));
        assertNull(map.get(1));
        assertFalse(map.containsKey(1));
        assertTrue(map.isEmpty());
        assertNull(map.remove(1));
    }

    @Test
    public void testNullValueRejected() {
        IntObjectHashMap<String> map = new IntObjectHashMap<String>();
        try {
            map.put(1, null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
    }

    @Test
    public void testNegativeAndZeroKeys() {
        IntObjectHashMap<String> map = new IntObjectHashMap<String>();
        map.put(0, "zero");
        map.put(-1, "neg one");
        map.put(Integer.MIN_VALUE, "min");
        map.put(Integer.MAX_VALUE, "max");
        assertEquals("zero", map.get(0));
        assertEquals("neg one", map.get(-1));
        assertEquals("min", map.get(Integer.MIN_VALUE));
        assertEquals("max", map.get(Integer.MAX_VALUE));
        assertEquals(4, map.size());
    }

    @Test
    public void testClear() {
        IntObjectHashMap<String> map = new IntObjectHashMap<String>();
        for (int i = 0; i < 50; i++) {
            map.put(i, "v" + i);
        }
        assertEquals(50, map.size());
        map.clear();
        assertTrue(map.isEmpty());
        assertNull(map.get(5));
        map.put(5, "again");
        assertEquals("again", map.get(5));
    }

    @Test
    public void testGrowsPastDefaultCapacityAndStaysCorrect() {
        IntObjectHashMap<Integer> map = new IntObjectHashMap<Integer>();
        int n = 5000;
        for (int i = 0; i < n; i++) {
            assertNull(map.put(i, Integer.valueOf(i * 2)));
        }
        assertEquals(n, map.size());
        for (int i = 0; i < n; i++) {
            assertEquals("key " + i, Integer.valueOf(i * 2), map.get(i));
        }
        for (int i = 0; i < n; i += 2) {
            assertEquals(Integer.valueOf(i * 2), map.remove(i));
        }
        assertEquals(n / 2, map.size());
        for (int i = 0; i < n; i++) {
            if (i % 2 == 0) {
                assertNull("key " + i + " should have been removed", map.get(i));
            } else {
                assertEquals("key " + i, Integer.valueOf(i * 2), map.get(i));
            }
        }
    }

    @Test
    public void testTombstoneReclamationUnderChurn() {
        // Repeatedly insert and remove the same small set of keys many
        // times over -- if tombstones were never reclaimed, this would
        // eventually degrade every probe into a full linear scan of an
        // ever-growing table; correctness (not just speed) is what's
        // asserted here, but a hang/timeout would itself indicate the
        // degradation this guards against.
        IntObjectHashMap<String> map = new IntObjectHashMap<String>();
        for (int round = 0; round < 20000; round++) {
            int key = round % 8;
            map.put(key, "round" + round);
            assertEquals("round" + round, map.get(key));
            map.remove(key);
            assertNull(map.get(key));
        }
        assertTrue(map.isEmpty());
    }

    @Test
    public void testKeysSnapshot() {
        IntObjectHashMap<String> map = new IntObjectHashMap<String>();
        Set<Integer> expected = new HashSet<Integer>();
        for (int i = 0; i < 30; i++) {
            int key = i * 7 - 15; // mix of negative/positive
            map.put(key, "v" + key);
            expected.add(Integer.valueOf(key));
        }
        int[] keys = map.keys();
        assertEquals(expected.size(), keys.length);
        Set<Integer> actual = new HashSet<Integer>();
        for (int k : keys) {
            actual.add(Integer.valueOf(k));
        }
        assertEquals(expected, actual);
    }

    @Test
    public void testForEachVisitsEveryEntryWithoutMutating() {
        IntObjectHashMap<String> map = new IntObjectHashMap<String>();
        Map<Integer, String> expected = new HashMap<Integer, String>();
        for (int i = 0; i < 40; i++) {
            map.put(i, "v" + i);
            expected.put(Integer.valueOf(i), "v" + i);
        }
        final Map<Integer, String> visited = new HashMap<Integer, String>();
        map.forEach(new IntObjectHashMap.EntryConsumer<String>() {
            @Override
            public void accept(int key, String value) {
                visited.put(Integer.valueOf(key), value);
            }
        });
        assertEquals(expected, visited);
        assertEquals(40, map.size()); // forEach must not remove anything
    }

    @Test
    public void testDrainEachRemovesEveryEntryAndAllowsReentrantPut() {
        IntObjectHashMap<Runnable> map = new IntObjectHashMap<Runnable>();
        final java.util.List<Integer> ran = new java.util.ArrayList<Integer>();
        for (int i = 0; i < 5; i++) {
            final int id = i;
            map.put(id, new Runnable() {
                @Override
                public void run() {
                    ran.add(Integer.valueOf(id));
                }
            });
        }
        assertEquals(5, map.size());

        final IntObjectHashMap<Runnable> mapRef = map;
        map.drainEach(new IntObjectHashMap.EntryConsumer<Runnable>() {
            @Override
            public void accept(int key, Runnable value) {
                value.run();
                if (key == 0) {
                    // Re-registering during the drain (mirroring a
                    // callback that immediately re-arms itself) must land
                    // in the now-cleared map, not corrupt the in-progress
                    // drain.
                    mapRef.put(99, new Runnable() {
                        @Override
                        public void run() {
                        }
                    });
                }
            }
        });

        assertEquals(5, ran.size());
        assertTrue(ran.containsAll(java.util.Arrays.asList(0, 1, 2, 3, 4)));
        assertEquals("re-registration during drain must survive it",
                1, map.size());
        assertTrue(map.containsKey(99));
    }

    @Test
    public void testDrainEachOnEmptyMapDoesNothing() {
        IntObjectHashMap<String> map = new IntObjectHashMap<String>();
        map.drainEach(new IntObjectHashMap.EntryConsumer<String>() {
            @Override
            public void accept(int key, String value) {
                fail("should not be called on an empty map");
            }
        });
        assertTrue(map.isEmpty());
    }

    @Test
    public void testAgainstRealHashMapReferenceRandomOperations() {
        // Cross-check against java.util.HashMap<Integer, String> under a
        // long randomized sequence of put/remove/get/containsKey -- the
        // strongest available evidence this reimplementation has no
        // correctness gap the more targeted tests above happen to miss.
        Random random = new Random(20260828L);
        IntObjectHashMap<String> actual = new IntObjectHashMap<String>();
        Map<Integer, String> reference = new HashMap<Integer, String>();

        for (int op = 0; op < 50000; op++) {
            int key = random.nextInt(500) - 100; // includes negatives
            int action = random.nextInt(4);
            switch (action) {
                case 0: {
                    String value = "v" + op;
                    String expected = reference.put(Integer.valueOf(key), value);
                    String actualOld = actual.put(key, value);
                    assertEquals("put() return value at op " + op, expected, actualOld);
                    break;
                }
                case 1: {
                    String expected = reference.remove(Integer.valueOf(key));
                    String actualOld = actual.remove(key);
                    assertEquals("remove() return value at op " + op, expected, actualOld);
                    break;
                }
                case 2: {
                    assertEquals("get() at op " + op,
                            reference.get(Integer.valueOf(key)), actual.get(key));
                    break;
                }
                default: {
                    assertEquals("containsKey() at op " + op,
                            reference.containsKey(Integer.valueOf(key)), actual.containsKey(key));
                    break;
                }
            }
            assertEquals("size mismatch at op " + op, reference.size(), actual.size());
        }

        assertEquals(reference.keySet(), toSet(actual.keys()));
        for (Map.Entry<Integer, String> e : reference.entrySet()) {
            assertEquals(e.getValue(), actual.get(e.getKey().intValue()));
        }
    }

    private static Set<Integer> toSet(int[] keys) {
        Set<Integer> set = new HashSet<Integer>();
        for (int k : keys) {
            set.add(Integer.valueOf(k));
        }
        return set;
    }
}
