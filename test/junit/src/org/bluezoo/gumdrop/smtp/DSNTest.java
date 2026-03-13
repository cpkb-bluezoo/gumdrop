package org.bluezoo.gumdrop.smtp;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DSNNotify}, {@link DSNReturn}, and {@link BodyType}.
 */
public class DSNTest {

    // ========================================================================
    // DSNNotify
    // ========================================================================

    @Test
    public void testDSNNotifyParse() {
        assertEquals(DSNNotify.NEVER, DSNNotify.parse("NEVER"));
        assertEquals(DSNNotify.SUCCESS, DSNNotify.parse("SUCCESS"));
        assertEquals(DSNNotify.FAILURE, DSNNotify.parse("FAILURE"));
        assertEquals(DSNNotify.DELAY, DSNNotify.parse("DELAY"));
    }

    @Test
    public void testDSNNotifyParseCaseInsensitive() {
        assertEquals(DSNNotify.NEVER, DSNNotify.parse("never"));
        assertEquals(DSNNotify.SUCCESS, DSNNotify.parse("Success"));
        assertEquals(DSNNotify.FAILURE, DSNNotify.parse("fAiLuRe"));
        assertEquals(DSNNotify.DELAY, DSNNotify.parse("Delay"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDSNNotifyParseNull() {
        DSNNotify.parse(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDSNNotifyParseUnknown() {
        DSNNotify.parse("INVALID");
    }

    // ========================================================================
    // DSNReturn
    // ========================================================================

    @Test
    public void testDSNReturnParse() {
        assertEquals(DSNReturn.FULL, DSNReturn.parse("FULL"));
        assertEquals(DSNReturn.HDRS, DSNReturn.parse("HDRS"));
    }

    @Test
    public void testDSNReturnParseCaseInsensitive() {
        assertEquals(DSNReturn.FULL, DSNReturn.parse("full"));
        assertEquals(DSNReturn.HDRS, DSNReturn.parse("Hdrs"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDSNReturnParseNull() {
        DSNReturn.parse(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDSNReturnParseUnknown() {
        DSNReturn.parse("BODY");
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
