/*
 * ByteArraysTest.java
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

package org.bluezoo.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Unit tests for {@link ByteArrays}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ByteArraysTest {

    @Test
    public void hexRoundTrip() {
        byte[] data = new byte[] {0, 1, (byte) 0xab, (byte) 0xff, 0x7f};
        String hex = ByteArrays.toHexString(data);
        assertEquals("0001abff7f", hex);
        assertArrayEquals(data, ByteArrays.toByteArray(hex));
        assertArrayEquals(data, ByteArrays.toByteArray("0001ABFF7F"));
    }

    @Test
    public void emptyArray() {
        assertEquals("", ByteArrays.toHexString(new byte[0]));
        assertEquals(0, ByteArrays.toByteArray("").length);
    }

    @Test
    public void toByteArrayRejectsBadInput() {
        String[] bad = new String[] {null, "abc", "zz", "0g"};
        for (int i = 0; i < bad.length; i++) {
            try {
                ByteArrays.toByteArray(bad[i]);
                fail("expected failure for " + bad[i]);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void toHexStringRejectsNull() {
        ByteArrays.toHexString(null);
    }

    @Test
    public void equalsVariants() {
        byte[] a = new byte[] {1, 2, 3};
        assertTrue(ByteArrays.equals(a, new byte[] {1, 2, 3}));
        assertFalse(ByteArrays.equals(a, new byte[] {1, 2, 4}));
        assertFalse(ByteArrays.equals(a, new byte[] {1, 2}));
        assertFalse(ByteArrays.equals(null, a));
        assertFalse(ByteArrays.equals(a, null));
    }

    @Test
    public void equalsConstantTimeVariants() {
        byte[] a = new byte[] {1, 2, 3};
        assertTrue(ByteArrays.equalsConstantTime(a, new byte[] {1, 2, 3}));
        assertFalse(ByteArrays.equalsConstantTime(a, new byte[] {1, 2, 4}));
        assertFalse(ByteArrays.equalsConstantTime(a, new byte[] {1, 2}));
        assertFalse(ByteArrays.equalsConstantTime(null, a));
        assertFalse(ByteArrays.equalsConstantTime(a, null));
    }
}
