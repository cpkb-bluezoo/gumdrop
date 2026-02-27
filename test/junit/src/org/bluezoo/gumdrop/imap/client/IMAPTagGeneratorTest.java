/*
 * IMAPTagGeneratorTest.java
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

package org.bluezoo.gumdrop.imap.client;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link IMAPTagGenerator}.
 */
public class IMAPTagGeneratorTest {

    @Test
    public void testFirstTag() {
        IMAPTagGenerator gen = new IMAPTagGenerator();
        assertEquals("A000", gen.next());
    }

    @Test
    public void testSequentialTags() {
        IMAPTagGenerator gen = new IMAPTagGenerator();
        assertEquals("A000", gen.next());
        assertEquals("A001", gen.next());
        assertEquals("A002", gen.next());
    }

    @Test
    public void testTagFormat() {
        IMAPTagGenerator gen = new IMAPTagGenerator();
        String tag = gen.next();
        assertEquals(4, tag.length());
        assertTrue(Character.isLetter(tag.charAt(0)));
        assertTrue(Character.isDigit(tag.charAt(1)));
        assertTrue(Character.isDigit(tag.charAt(2)));
        assertTrue(Character.isDigit(tag.charAt(3)));
    }

    @Test
    public void testPrefixRollover() {
        IMAPTagGenerator gen = new IMAPTagGenerator();
        for (int i = 0; i < 999; i++) {
            gen.next();
        }
        assertEquals("A999", gen.next());
        assertEquals("B000", gen.next());
        assertEquals("B001", gen.next());
    }

    @Test
    public void testZWraparound() {
        IMAPTagGenerator gen = new IMAPTagGenerator();
        // Advance to Z prefix
        for (int i = 0; i < 25 * 1000; i++) {
            gen.next();
        }
        assertEquals("Z000", gen.next());
        for (int i = 0; i < 998; i++) {
            gen.next();
        }
        assertEquals("Z999", gen.next());
        // Wraps back to A
        assertEquals("A000", gen.next());
    }

    @Test
    public void testReset() {
        IMAPTagGenerator gen = new IMAPTagGenerator();
        gen.next();
        gen.next();
        gen.next();
        gen.reset();
        assertEquals("A000", gen.next());
    }

    @Test
    public void testUniqueTags() {
        IMAPTagGenerator gen = new IMAPTagGenerator();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < 2000; i++) {
            String tag = gen.next();
            assertTrue("Duplicate tag: " + tag, seen.add(tag));
        }
    }
}
