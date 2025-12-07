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
 * Unit tests for {@link MIMEUtils}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MIMEUtilsTest {

    // Token tests
    
    @Test
    public void testIsTokenValid() {
        assertTrue(MIMEUtils.isToken("text"));
        assertTrue(MIMEUtils.isToken("plain"));
        assertTrue(MIMEUtils.isToken("utf-8"));
        assertTrue(MIMEUtils.isToken("7bit"));
        assertTrue(MIMEUtils.isToken("base64"));
        assertTrue(MIMEUtils.isToken("x-custom"));
    }
    
    @Test
    public void testIsTokenWithNumbers() {
        assertTrue(MIMEUtils.isToken("iso-8859-1"));
        assertTrue(MIMEUtils.isToken("UTF8"));
        assertTrue(MIMEUtils.isToken("8bit"));
    }
    
    @Test
    public void testIsTokenWithSpecialChars() {
        // Tokens can contain certain special characters
        assertTrue(MIMEUtils.isToken("vnd.ms-excel"));
        assertTrue(MIMEUtils.isToken("application+json"));
    }
    
    @Test
    public void testIsTokenEmpty() {
        assertFalse(MIMEUtils.isToken(""));
    }
    
    @Test
    public void testIsTokenNull() {
        assertFalse(MIMEUtils.isToken(null));
    }
    
    @Test
    public void testIsTokenWithSpace() {
        assertFalse(MIMEUtils.isToken("text plain"));
    }
    
    @Test
    public void testIsTokenWithSpecials() {
        // These characters are not allowed in tokens
        assertFalse(MIMEUtils.isToken("text/plain"));  // slash
        assertFalse(MIMEUtils.isToken("name=value"));  // equals
        assertFalse(MIMEUtils.isToken("name;value"));  // semicolon
        assertFalse(MIMEUtils.isToken("name\"value")); // quote
    }
    
    // TokenChar tests
    
    @Test
    public void testIsTokenCharAlpha() {
        assertTrue(MIMEUtils.isTokenChar('a'));
        assertTrue(MIMEUtils.isTokenChar('z'));
        assertTrue(MIMEUtils.isTokenChar('A'));
        assertTrue(MIMEUtils.isTokenChar('Z'));
    }
    
    @Test
    public void testIsTokenCharDigit() {
        assertTrue(MIMEUtils.isTokenChar('0'));
        assertTrue(MIMEUtils.isTokenChar('9'));
    }
    
    @Test
    public void testIsTokenCharSpecial() {
        // Special chars that ARE allowed in tokens
        assertTrue(MIMEUtils.isTokenChar('-'));
        assertTrue(MIMEUtils.isTokenChar('.'));
        assertTrue(MIMEUtils.isTokenChar('!'));
        assertTrue(MIMEUtils.isTokenChar('#'));
        assertTrue(MIMEUtils.isTokenChar('$'));
        assertTrue(MIMEUtils.isTokenChar('%'));
        assertTrue(MIMEUtils.isTokenChar('&'));
        assertTrue(MIMEUtils.isTokenChar('\''));
        assertTrue(MIMEUtils.isTokenChar('*'));
        assertTrue(MIMEUtils.isTokenChar('+'));
        assertTrue(MIMEUtils.isTokenChar('^'));
        assertTrue(MIMEUtils.isTokenChar('_'));
        assertTrue(MIMEUtils.isTokenChar('`'));
        assertTrue(MIMEUtils.isTokenChar('|'));
        assertTrue(MIMEUtils.isTokenChar('~'));
    }
    
    @Test
    public void testIsTokenCharNotAllowed() {
        // Characters NOT allowed in tokens
        assertFalse(MIMEUtils.isTokenChar(' '));
        assertFalse(MIMEUtils.isTokenChar('\t'));
        assertFalse(MIMEUtils.isTokenChar('('));
        assertFalse(MIMEUtils.isTokenChar(')'));
        assertFalse(MIMEUtils.isTokenChar('<'));
        assertFalse(MIMEUtils.isTokenChar('>'));
        assertFalse(MIMEUtils.isTokenChar('@'));
        assertFalse(MIMEUtils.isTokenChar(','));
        assertFalse(MIMEUtils.isTokenChar(';'));
        assertFalse(MIMEUtils.isTokenChar(':'));
        assertFalse(MIMEUtils.isTokenChar('\\'));
        assertFalse(MIMEUtils.isTokenChar('"'));
        assertFalse(MIMEUtils.isTokenChar('/'));
        assertFalse(MIMEUtils.isTokenChar('['));
        assertFalse(MIMEUtils.isTokenChar(']'));
        assertFalse(MIMEUtils.isTokenChar('?'));
        assertFalse(MIMEUtils.isTokenChar('='));
    }
    
    // Special char tests
    
    @Test
    public void testIsSpecial() {
        // RFC 2045 specials (tspecials)
        assertTrue(MIMEUtils.isSpecial('('));
        assertTrue(MIMEUtils.isSpecial(')'));
        assertTrue(MIMEUtils.isSpecial('<'));
        assertTrue(MIMEUtils.isSpecial('>'));
        assertTrue(MIMEUtils.isSpecial('@'));
        assertTrue(MIMEUtils.isSpecial(','));
        assertTrue(MIMEUtils.isSpecial(';'));
        assertTrue(MIMEUtils.isSpecial(':'));
        assertTrue(MIMEUtils.isSpecial('\\'));
        assertTrue(MIMEUtils.isSpecial('"'));
        assertTrue(MIMEUtils.isSpecial('/'));
        assertTrue(MIMEUtils.isSpecial('['));
        assertTrue(MIMEUtils.isSpecial(']'));
        assertTrue(MIMEUtils.isSpecial('?'));
        assertTrue(MIMEUtils.isSpecial('='));
    }
    
    @Test
    public void testIsNotSpecial() {
        assertFalse(MIMEUtils.isSpecial('a'));
        assertFalse(MIMEUtils.isSpecial('0'));
        assertFalse(MIMEUtils.isSpecial('-'));
        assertFalse(MIMEUtils.isSpecial('.'));
    }
    
    // Boundary tests
    
    @Test
    public void testIsValidBoundarySimple() {
        assertTrue(MIMEUtils.isValidBoundary("simpleboundary"));
        assertTrue(MIMEUtils.isValidBoundary("boundary123"));
    }
    
    @Test
    public void testIsValidBoundaryWithSpecialChars() {
        // RFC 2046 allows these characters in boundaries
        assertTrue(MIMEUtils.isValidBoundary("----=_Part_123"));
        assertTrue(MIMEUtils.isValidBoundary("----WebKitFormBoundary7MA4YWxkTrZu0gW"));
    }
    
    @Test
    public void testIsValidBoundaryMaxLength() {
        // Boundary must be <= 70 characters
        String boundary70 = "a".repeat(70);
        assertTrue(MIMEUtils.isValidBoundary(boundary70));
    }
    
    @Test
    public void testIsValidBoundaryTooLong() {
        // Boundary > 70 characters should be invalid
        String boundary71 = "a".repeat(71);
        assertFalse(MIMEUtils.isValidBoundary(boundary71));
    }
    
    @Test
    public void testIsValidBoundaryEmpty() {
        assertFalse(MIMEUtils.isValidBoundary(""));
    }
    
    @Test
    public void testIsValidBoundaryNull() {
        assertFalse(MIMEUtils.isValidBoundary(null));
    }
    
    @Test
    public void testIsValidBoundaryWithSpace() {
        // Space is NOT allowed in boundaries per our implementation
        assertFalse(MIMEUtils.isValidBoundary("boundary with space"));
    }
    
    @Test
    public void testIsValidBoundaryEndingWithSpace() {
        // Boundary must NOT end with space (RFC 2046)
        assertFalse(MIMEUtils.isValidBoundary("boundary "));
    }
    
    // BoundaryChar tests
    
    @Test
    public void testIsBoundaryCharAlphanumeric() {
        assertTrue(MIMEUtils.isBoundaryChar('a'));
        assertTrue(MIMEUtils.isBoundaryChar('Z'));
        assertTrue(MIMEUtils.isBoundaryChar('0'));
        assertTrue(MIMEUtils.isBoundaryChar('9'));
    }
    
    @Test
    public void testIsBoundaryCharSpecial() {
        // RFC 2046 bcharsnospace
        assertTrue(MIMEUtils.isBoundaryChar('\''));
        assertTrue(MIMEUtils.isBoundaryChar('('));
        assertTrue(MIMEUtils.isBoundaryChar(')'));
        assertTrue(MIMEUtils.isBoundaryChar('+'));
        assertTrue(MIMEUtils.isBoundaryChar('_'));
        assertTrue(MIMEUtils.isBoundaryChar(','));
        assertTrue(MIMEUtils.isBoundaryChar('-'));
        assertTrue(MIMEUtils.isBoundaryChar('.'));
        assertTrue(MIMEUtils.isBoundaryChar('/'));
        assertTrue(MIMEUtils.isBoundaryChar(':'));
        assertTrue(MIMEUtils.isBoundaryChar('='));
        assertTrue(MIMEUtils.isBoundaryChar('?'));
    }
    
    @Test
    public void testIsBoundaryCharSpace() {
        // Space is NOT a valid boundary character in our implementation
        assertFalse(MIMEUtils.isBoundaryChar(' '));
    }
}

