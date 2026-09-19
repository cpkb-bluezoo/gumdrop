/*
 * MboxMessageDescriptorTest.java
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

package org.bluezoo.gumdrop.mailbox.mbox;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MboxMessageDescriptor}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MboxMessageDescriptorTest {

    @Test
    public void testConstructorAndGetters() {
        MboxMessageDescriptor d = new MboxMessageDescriptor(1, 100, 500, "abc123");
        assertEquals(1, d.getMessageNumber());
        assertEquals(100, d.getStartOffset());
        assertEquals(500, d.getEndOffset());
        assertEquals("abc123", d.getUniqueId());
    }

    @Test
    public void testGetSize() {
        MboxMessageDescriptor d = new MboxMessageDescriptor(1, 100, 500, "id1");
        assertEquals(400, d.getSize());
    }

    @Test
    public void testGetSizeZero() {
        MboxMessageDescriptor d = new MboxMessageDescriptor(1, 200, 200, "id2");
        assertEquals(0, d.getSize());
    }

    @Test
    public void testGetSizeLargeOffsets() {
        long start = 1_000_000_000L;
        long end = 2_000_000_000L;
        MboxMessageDescriptor d = new MboxMessageDescriptor(1, start, end, "id3");
        assertEquals(1_000_000_000L, d.getSize());
    }

    @Test
    public void testToString() {
        MboxMessageDescriptor d = new MboxMessageDescriptor(3, 1000, 2000, "uid42");
        String s = d.toString();
        assertTrue(s.contains("3"));
        assertTrue(s.contains("1000"));
        assertTrue(s.contains("2000"));
        assertTrue(s.contains("uid42"));
    }

    @Test
    public void testMultipleDescriptors() {
        MboxMessageDescriptor d1 = new MboxMessageDescriptor(1, 0, 100, "a");
        MboxMessageDescriptor d2 = new MboxMessageDescriptor(2, 100, 300, "b");
        MboxMessageDescriptor d3 = new MboxMessageDescriptor(3, 300, 600, "c");

        assertEquals(100, d1.getSize());
        assertEquals(200, d2.getSize());
        assertEquals(300, d3.getSize());
    }
}
