/*
 * DSNTest.java
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

package org.bluezoo.gumdrop.smtp;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DsnNotify}, {@link DsnReturn}, and {@link BodyType}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DSNTest {

    // ========================================================================
    // DsnNotify
    // ========================================================================

    @Test
    public void testDSNNotifyParse() {
        assertEquals(DsnNotify.NEVER, DsnNotify.parse("NEVER"));
        assertEquals(DsnNotify.SUCCESS, DsnNotify.parse("SUCCESS"));
        assertEquals(DsnNotify.FAILURE, DsnNotify.parse("FAILURE"));
        assertEquals(DsnNotify.DELAY, DsnNotify.parse("DELAY"));
    }

    @Test
    public void testDSNNotifyParseCaseInsensitive() {
        assertEquals(DsnNotify.NEVER, DsnNotify.parse("never"));
        assertEquals(DsnNotify.SUCCESS, DsnNotify.parse("Success"));
        assertEquals(DsnNotify.FAILURE, DsnNotify.parse("fAiLuRe"));
        assertEquals(DsnNotify.DELAY, DsnNotify.parse("Delay"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDSNNotifyParseNull() {
        DsnNotify.parse(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDSNNotifyParseUnknown() {
        DsnNotify.parse("INVALID");
    }

    // ========================================================================
    // DsnReturn
    // ========================================================================

    @Test
    public void testDSNReturnParse() {
        assertEquals(DsnReturn.FULL, DsnReturn.parse("FULL"));
        assertEquals(DsnReturn.HDRS, DsnReturn.parse("HDRS"));
    }

    @Test
    public void testDSNReturnParseCaseInsensitive() {
        assertEquals(DsnReturn.FULL, DsnReturn.parse("full"));
        assertEquals(DsnReturn.HDRS, DsnReturn.parse("Hdrs"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDSNReturnParseNull() {
        DsnReturn.parse(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDSNReturnParseUnknown() {
        DsnReturn.parse("BODY");
    }

    // ========================================================================
    // BodyType
    // ========================================================================

    @Test
    public void testBodyTypeParse() {
        assertEquals(BodyType.SEVEN_BIT, BodyType.parse("7BIT"));
        assertEquals(BodyType.EIGHT_BIT_MIME, BodyType.parse("8BITMIME"));
        assertEquals(BodyType.BINARY_MIME, BodyType.parse("BINARYMIME"));
    }

    @Test
    public void testBodyTypeParseCaseInsensitive() {
        assertEquals(BodyType.SEVEN_BIT, BodyType.parse("7bit"));
        assertEquals(BodyType.EIGHT_BIT_MIME, BodyType.parse("8bitmime"));
        assertEquals(BodyType.BINARY_MIME, BodyType.parse("BinaryMIME"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBodyTypeParseNull() {
        BodyType.parse(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBodyTypeParseUnknown() {
        BodyType.parse("16BIT");
    }

    @Test
    public void testBodyTypeGetKeyword() {
        assertEquals("7BIT", BodyType.SEVEN_BIT.getKeyword());
        assertEquals("8BITMIME", BodyType.EIGHT_BIT_MIME.getKeyword());
        assertEquals("BINARYMIME", BodyType.BINARY_MIME.getKeyword());
    }

    @Test
    public void testBodyTypeRequiresBdat() {
        assertFalse(BodyType.SEVEN_BIT.requiresBdat());
        assertFalse(BodyType.EIGHT_BIT_MIME.requiresBdat());
        assertTrue(BodyType.BINARY_MIME.requiresBdat());
    }

    @Test
    public void testBodyTypeToString() {
        assertEquals("7BIT", BodyType.SEVEN_BIT.toString());
        assertEquals("8BITMIME", BodyType.EIGHT_BIT_MIME.toString());
        assertEquals("BINARYMIME", BodyType.BINARY_MIME.toString());
    }
}
