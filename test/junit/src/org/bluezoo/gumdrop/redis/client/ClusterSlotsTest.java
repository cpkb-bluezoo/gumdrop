/*
 * ClusterSlotsTest.java
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

package org.bluezoo.gumdrop.redis.client;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link ClusterSlots}: the CRC16 check value and slot numbers
 * published for Redis Cluster, and hash-tag handling.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ClusterSlotsTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static int slot(String key) {
        return ClusterSlots.slot(b(key));
    }

    @Test
    public void crc16MatchesTheXmodemCheckValue() {
        byte[] data = b("123456789");
        assertEquals(0x31C3, ClusterSlots.crc16(data, 0, data.length));
    }

    @Test
    public void crc16OfAnEmptyRangeIsZero() {
        assertEquals(0, ClusterSlots.crc16(new byte[0], 0, 0));
    }

    @Test
    public void knownKeysMapToTheirPublishedSlots() {
        assertEquals(12182, slot("foo"));
        assertEquals(5061, slot("bar"));
        assertEquals(866, slot("hello"));
        assertEquals(11058, slot("somekey"));
        assertEquals(15495, slot("a"));
        assertEquals(3300, slot("b"));
        assertEquals(7365, slot("c"));
    }

    @Test
    public void emptyKeyIsSlotZero() {
        assertEquals(0, slot(""));
    }

    @Test
    public void everySlotIsInRange() {
        for (int i = 0; i < 2000; i++) {
            int s = slot("key:" + i);
            assertTrue(s >= 0 && s < ClusterSlots.SLOT_COUNT);
        }
    }

    @Test
    public void hashTagSelectsTheSlot() {
        assertEquals(slot("user1000"), slot("{user1000}.following"));
        assertEquals(slot("{user1000}.following"), slot("{user1000}.followers"));
        assertEquals(slot("bar"), slot("foo{bar}"));
        assertEquals(slot("bar"), slot("foo{bar}zap"));
    }

    @Test
    public void onlyTheFirstHashTagCounts() {
        assertEquals(slot("bar"), slot("foo{bar}{zap}"));
    }

    @Test
    public void emptyHashTagHashesTheWholeKey() {
        // "{}" is empty, so the whole key is hashed (not the later {bar})
        int whole = ClusterSlots.crc16(b("foo{}{bar}"), 0, 10) % ClusterSlots.SLOT_COUNT;
        assertEquals(whole, slot("foo{}{bar}"));
        assertNotEquals(slot("bar"), slot("foo{}{bar}"));
    }

    @Test
    public void nestedBracesTagStartsAtTheFirstOpenBrace() {
        // tag is "{bar", between the first '{' and the first '}'
        assertEquals(4015, slot("foo{{bar}}zap"));
    }

    @Test
    public void unterminatedBraceHashesTheWholeKey() {
        int whole = ClusterSlots.crc16(b("foo{bar"), 0, 7) % ClusterSlots.SLOT_COUNT;
        assertEquals(whole, slot("foo{bar"));
        int other = ClusterSlots.crc16(b("{foo"), 0, 4) % ClusterSlots.SLOT_COUNT;
        assertEquals(other, slot("{foo"));
    }

    @Test
    public void closingBraceBeforeOpeningOneIsNotATag() {
        int whole = ClusterSlots.crc16(b("a}b{c"), 0, 5) % ClusterSlots.SLOT_COUNT;
        assertEquals(whole, slot("a}b{c"));
    }

    @Test
    public void binaryKeysAreHashedByTheirBytes() {
        byte[] key = { (byte) 0xFF, 0x00, (byte) 0x80, 0x7F };
        int expected = ClusterSlots.crc16(key, 0, key.length) % ClusterSlots.SLOT_COUNT;
        assertEquals(expected, ClusterSlots.slot(key));
    }
}
