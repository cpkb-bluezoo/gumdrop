/*
 * TokensTest.java
 * Copyright (C) 2025 Chris Burdess
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

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Tokens}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TokensTest {

    @Test
    public void testSimple() {
        assertArrayEquals(new String[] { "a", "b", "c" }, Tokens.split("a b c"));
    }

    @Test
    public void testSingleToken() {
        assertArrayEquals(new String[] { "abc" }, Tokens.split("abc"));
    }

    @Test
    public void testNullAndBlankYieldNoTokens() {
        assertEquals(0, Tokens.split(null).length);
        assertEquals(0, Tokens.split("").length);
        assertEquals(0, Tokens.split(" \t\r\n\f\u000B ").length);
    }

    @Test
    public void testLeadingTrailingAndRepeatedWhitespace() {
        assertArrayEquals(new String[] { "a", "b", "c" },
            Tokens.split("  a \t\t b\r\n c \n"));
    }

    @Test
    public void testAllWhitespaceKindsSeparate() {
        assertArrayEquals(new String[] { "a", "b", "c", "d", "e", "f" },
            Tokens.split("a b\tc\nd\re\u000Bf"));
        assertArrayEquals(new String[] { "a", "b" }, Tokens.split("a\fb"));
    }

    @Test
    public void testControlCharactersAreNotSeparators() {
        assertArrayEquals(new String[] { "a\u0001b", "\u0000" }, Tokens.split("a\u0001b \u0000"));
        assertArrayEquals(new String[] { "a\u000Eb" }, Tokens.split("a\u000Eb"));
    }

    @Test
    public void testNonAsciiIsNotSeparator() {
        assertArrayEquals(new String[] { "a b" }, Tokens.split("a b"));
    }

    @Test
    public void testLimitKeepsRemainderIntact() {
        assertArrayEquals(new String[] { "a", "b", "c  d e" },
            Tokens.split("a b c  d e", 3));
    }

    @Test
    public void testLimitTrimsTrailingWhitespaceOfRemainder() {
        assertArrayEquals(new String[] { "a", "b c" }, Tokens.split("  a   b c  \r\n", 2));
    }

    @Test
    public void testLimitOne() {
        assertArrayEquals(new String[] { "a b" }, Tokens.split(" a b ", 1));
    }

    @Test
    public void testLimitLargerThanTokenCount() {
        assertArrayEquals(new String[] { "a", "b" }, Tokens.split("a b", 9));
    }

    @Test
    public void testLimitEqualToTokenCount() {
        assertArrayEquals(new String[] { "a", "b" }, Tokens.split("a b", 2));
    }

    @Test
    public void testLimitOnBlank() {
        assertEquals(0, Tokens.split("   ", 3).length);
    }

    @Test
    public void testZeroAndNegativeLimitMeanUnlimited() {
        assertArrayEquals(new String[] { "a", "b", "c" }, Tokens.split("a b c", 0));
        assertArrayEquals(new String[] { "a", "b", "c" }, Tokens.split("a b c", -1));
    }

    @Test
    public void testListingLine() {
        String line = "-rw-r--r--   1 user group  1234 Nov 21 09:55 my file name.txt";
        String[] parts = Tokens.split(line, 9);
        assertEquals(9, parts.length);
        assertEquals("1234", parts[4]);
        assertEquals("my file name.txt", parts[8]);
    }

    @Test
    public void testInstantiable() {
        assertNotNull(new Tokens());
    }
}
