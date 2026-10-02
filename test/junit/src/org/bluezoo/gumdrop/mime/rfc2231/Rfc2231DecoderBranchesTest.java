/*
 * Rfc2231DecoderBranchesTest.java
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

package org.bluezoo.gumdrop.mime.rfc2231;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Branch tests for {@link Rfc2231Decoder}: malformed structure, charset
 * aliases, unknown charsets and bad percent escapes.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Rfc2231DecoderBranchesTest {

    private static String decode(String s) {
        ByteBuffer buf = ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
        return Rfc2231Decoder.decodeParameterValue(buf, null);
    }

    @Test
    public void testNullAndEmptyInput() {
        assertNull(Rfc2231Decoder.decodeParameterValue(null, null));
        ByteBuffer empty = ByteBuffer.allocate(0);
        assertNull(Rfc2231Decoder.decodeParameterValue(empty, null));
    }

    @Test
    public void testOnlyOpeningQuote() {
        assertNull(decode("\""));
    }

    @Test
    public void testMissingCharsetOrDelimiters() {
        assertNull(decode("'en'abc"));
        assertNull(decode("UTF-8'"));
        assertNull(decode("UTF-8'en"));
        assertNull(decode("UTF-8"));
    }

    @Test
    public void testNonAsciiInCharsetOrLanguage() {
        assertNull(decode("UTéF'en'abc"));
        assertNull(decode("UTF-8'eé'abc"));
        assertNull(decode("UTF\u0001-8'en'abc"));
    }

    @Test
    public void testCharsetAliases() {
        assertEquals("é", decode("UTF8''%C3%A9"));
        assertEquals("é", decode("utf8''%C3%A9"));
        assertEquals("é", decode("ISO88591''%E9"));
        assertEquals("é", decode("ISO-88591''%E9"));
        assertEquals("é", decode("latin1''%E9"));
        assertEquals("é", decode("ISO-8859-1''%E9"));
    }

    @Test
    public void testUnknownCharsetFallsBackToLatin1WithoutDecoder() {
        assertEquals("é", decode("x-nonexistent''%E9"));
    }

    @Test
    public void testUnknownCharsetUsesFallbackDecoder() {
        ByteBuffer buf = ByteBuffer.wrap("x-nonexistent''%C3%A9".getBytes(StandardCharsets.ISO_8859_1));
        String out = Rfc2231Decoder.decodeParameterValue(buf,
                StandardCharsets.UTF_8.newDecoder());
        assertEquals("é", out);
    }

    @Test
    public void testBadPercentEscapesAreKeptLiterally() {
        assertEquals("a%zzb", decode("us-ascii''a%zzb"));
        assertEquals("a%4", decode("us-ascii''a%4"));
        assertEquals("100%", decode("us-ascii''100%"));
        assertEquals("a%g1", decode("us-ascii''a%g1"));
        assertEquals("a%1g", decode("us-ascii''a%1g"));
        assertEquals("AaZ", decode("us-ascii''%41%61%5a"));
    }

    @Test
    public void testEmptyEncodedPartWithQuote() {
        assertEquals("", decode("\"UTF-8''\""));
        assertEquals("", decode("UTF-8'en'"));
    }

    @Test
    public void testPositionAdvancedToLimit() {
        ByteBuffer buf = ByteBuffer.wrap("UTF-8'en'abc".getBytes(StandardCharsets.ISO_8859_1));
        String out = Rfc2231Decoder.decodeParameterValue(buf, null);
        assertEquals("abc", out);
        assertEquals(buf.limit(), buf.position());
    }
}
