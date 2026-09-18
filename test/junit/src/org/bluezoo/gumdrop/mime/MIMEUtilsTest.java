/*
 * MIMEUtilsTest.java
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

package org.bluezoo.gumdrop.mime;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MimeUtils}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MIMEUtilsTest {

    // Token tests
    
    @Test
    public void testIsTokenValid() {
        assertTrue(MimeUtils.isToken("text"));
        assertTrue(MimeUtils.isToken("plain"));
        assertTrue(MimeUtils.isToken("utf-8"));
        assertTrue(MimeUtils.isToken("7bit"));
        assertTrue(MimeUtils.isToken("base64"));
        assertTrue(MimeUtils.isToken("x-custom"));
    }
    
    @Test
    public void testIsTokenWithNumbers() {
        assertTrue(MimeUtils.isToken("iso-8859-1"));
        assertTrue(MimeUtils.isToken("UTF8"));
        assertTrue(MimeUtils.isToken("8bit"));
    }
    
    @Test
    public void testIsTokenWithSpecialChars() {
        // Tokens can contain certain special characters
        assertTrue(MimeUtils.isToken("vnd.ms-excel"));
        assertTrue(MimeUtils.isToken("application+json"));
    }
    
    @Test
    public void testIsTokenEmpty() {
        assertFalse(MimeUtils.isToken(""));
    }
    
    @Test
    public void testIsTokenNull() {
        assertFalse(MimeUtils.isToken(null));
    }
    
    @Test
    public void testIsTokenWithSpace() {
        assertFalse(MimeUtils.isToken("text plain"));
    }
    
    @Test
    public void testIsTokenWithSpecials() {
        // These characters are not allowed in tokens
        assertFalse(MimeUtils.isToken("text/plain"));  // slash
        assertFalse(MimeUtils.isToken("name=value"));  // equals
        assertFalse(MimeUtils.isToken("name;value"));  // semicolon
        assertFalse(MimeUtils.isToken("name\"value")); // quote
    }
    
    // TokenChar tests
    
    @Test
    public void testIsTokenCharAlpha() {
        assertTrue(MimeUtils.isTokenChar('a'));
        assertTrue(MimeUtils.isTokenChar('z'));
        assertTrue(MimeUtils.isTokenChar('A'));
        assertTrue(MimeUtils.isTokenChar('Z'));
    }
    
    @Test
    public void testIsTokenCharDigit() {
        assertTrue(MimeUtils.isTokenChar('0'));
        assertTrue(MimeUtils.isTokenChar('9'));
    }
    
    @Test
    public void testIsTokenCharSpecial() {
        // Special chars that ARE allowed in tokens
        assertTrue(MimeUtils.isTokenChar('-'));
        assertTrue(MimeUtils.isTokenChar('.'));
        assertTrue(MimeUtils.isTokenChar('!'));
        assertTrue(MimeUtils.isTokenChar('#'));
        assertTrue(MimeUtils.isTokenChar('$'));
        assertTrue(MimeUtils.isTokenChar('%'));
        assertTrue(MimeUtils.isTokenChar('&'));
        assertTrue(MimeUtils.isTokenChar('\''));
        assertTrue(MimeUtils.isTokenChar('*'));
        assertTrue(MimeUtils.isTokenChar('+'));
        assertTrue(MimeUtils.isTokenChar('^'));
        assertTrue(MimeUtils.isTokenChar('_'));
        assertTrue(MimeUtils.isTokenChar('`'));
        assertTrue(MimeUtils.isTokenChar('|'));
        assertTrue(MimeUtils.isTokenChar('~'));
    }
    
    @Test
    public void testIsTokenCharNotAllowed() {
        // Characters NOT allowed in tokens
        assertFalse(MimeUtils.isTokenChar(' '));
        assertFalse(MimeUtils.isTokenChar('\t'));
        assertFalse(MimeUtils.isTokenChar('('));
        assertFalse(MimeUtils.isTokenChar(')'));
        assertFalse(MimeUtils.isTokenChar('<'));
        assertFalse(MimeUtils.isTokenChar('>'));
        assertFalse(MimeUtils.isTokenChar('@'));
        assertFalse(MimeUtils.isTokenChar(','));
        assertFalse(MimeUtils.isTokenChar(';'));
        assertFalse(MimeUtils.isTokenChar(':'));
        assertFalse(MimeUtils.isTokenChar('\\'));
        assertFalse(MimeUtils.isTokenChar('"'));
        assertFalse(MimeUtils.isTokenChar('/'));
        assertFalse(MimeUtils.isTokenChar('['));
        assertFalse(MimeUtils.isTokenChar(']'));
        assertFalse(MimeUtils.isTokenChar('?'));
        assertFalse(MimeUtils.isTokenChar('='));
    }
    
    // Special char tests
    
    @Test
    public void testIsSpecial() {
        // RFC 2045 specials (tspecials)
        assertTrue(MimeUtils.isSpecial('('));
        assertTrue(MimeUtils.isSpecial(')'));
        assertTrue(MimeUtils.isSpecial('<'));
        assertTrue(MimeUtils.isSpecial('>'));
        assertTrue(MimeUtils.isSpecial('@'));
        assertTrue(MimeUtils.isSpecial(','));
        assertTrue(MimeUtils.isSpecial(';'));
        assertTrue(MimeUtils.isSpecial(':'));
        assertTrue(MimeUtils.isSpecial('\\'));
        assertTrue(MimeUtils.isSpecial('"'));
        assertTrue(MimeUtils.isSpecial('/'));
        assertTrue(MimeUtils.isSpecial('['));
        assertTrue(MimeUtils.isSpecial(']'));
        assertTrue(MimeUtils.isSpecial('?'));
        assertTrue(MimeUtils.isSpecial('='));
    }
    
    @Test
    public void testIsNotSpecial() {
        assertFalse(MimeUtils.isSpecial('a'));
        assertFalse(MimeUtils.isSpecial('0'));
        assertFalse(MimeUtils.isSpecial('-'));
        assertFalse(MimeUtils.isSpecial('.'));
    }
    
    // Boundary tests
    
    @Test
    public void testIsValidBoundarySimple() {
        assertTrue(MimeUtils.isValidBoundary("simpleboundary"));
        assertTrue(MimeUtils.isValidBoundary("boundary123"));
    }
    
    @Test
    public void testIsValidBoundaryWithSpecialChars() {
        // RFC 2046 allows these characters in boundaries
        assertTrue(MimeUtils.isValidBoundary("----=_Part_123"));
        assertTrue(MimeUtils.isValidBoundary("----WebKitFormBoundary7MA4YWxkTrZu0gW"));
    }
    
    @Test
    public void testIsValidBoundaryMaxLength() {
        // Boundary must be <= 70 characters
        String boundary70 = "a".repeat(70);
        assertTrue(MimeUtils.isValidBoundary(boundary70));
    }
    
    @Test
    public void testIsValidBoundaryTooLong() {
        // Boundary > 70 characters should be invalid
        String boundary71 = "a".repeat(71);
        assertFalse(MimeUtils.isValidBoundary(boundary71));
    }
    
    @Test
    public void testIsValidBoundaryEmpty() {
        assertFalse(MimeUtils.isValidBoundary(""));
    }
    
    @Test
    public void testIsValidBoundaryNull() {
        assertFalse(MimeUtils.isValidBoundary(null));
    }
    
    @Test
    public void testIsValidBoundaryWithSpace() {
        // Space is NOT allowed in boundaries per our implementation
        assertFalse(MimeUtils.isValidBoundary("boundary with space"));
    }
    
    @Test
    public void testIsValidBoundaryEndingWithSpace() {
        // Boundary must NOT end with space (RFC 2046)
        assertFalse(MimeUtils.isValidBoundary("boundary "));
    }
    
    // BoundaryChar tests
    
    @Test
    public void testIsBoundaryCharAlphanumeric() {
        assertTrue(MimeUtils.isBoundaryChar('a'));
        assertTrue(MimeUtils.isBoundaryChar('Z'));
        assertTrue(MimeUtils.isBoundaryChar('0'));
        assertTrue(MimeUtils.isBoundaryChar('9'));
    }
    
    @Test
    public void testIsBoundaryCharSpecial() {
        // RFC 2046 bcharsnospace
        assertTrue(MimeUtils.isBoundaryChar('\''));
        assertTrue(MimeUtils.isBoundaryChar('('));
        assertTrue(MimeUtils.isBoundaryChar(')'));
        assertTrue(MimeUtils.isBoundaryChar('+'));
        assertTrue(MimeUtils.isBoundaryChar('_'));
        assertTrue(MimeUtils.isBoundaryChar(','));
        assertTrue(MimeUtils.isBoundaryChar('-'));
        assertTrue(MimeUtils.isBoundaryChar('.'));
        assertTrue(MimeUtils.isBoundaryChar('/'));
        assertTrue(MimeUtils.isBoundaryChar(':'));
        assertTrue(MimeUtils.isBoundaryChar('='));
        assertTrue(MimeUtils.isBoundaryChar('?'));
    }
    
    @Test
    public void testIsBoundaryCharSpace() {
        // Space is NOT a valid boundary character in our implementation
        assertFalse(MimeUtils.isBoundaryChar(' '));
    }
}

