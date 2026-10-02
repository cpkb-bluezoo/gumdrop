/*
 * Rfc2047DecoderBranchesTest.java
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

package org.bluezoo.gumdrop.mime.rfc2047;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Branch tests for {@link Rfc2047Decoder}: the ByteBuffer APIs, malformed
 * encoded-words, adjacency rules, 8-bit fallbacks and RFC 2231 parameters.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Rfc2047DecoderBranchesTest {

    private static CharsetDecoder latin1() {
        CharsetDecoder d = StandardCharsets.ISO_8859_1.newDecoder();
        d.onMalformedInput(CodingErrorAction.REPLACE);
        d.onUnmappableCharacter(CodingErrorAction.REPLACE);
        return d;
    }

    private static CharsetDecoder utf8() {
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder();
        d.onMalformedInput(CodingErrorAction.REPLACE);
        d.onUnmappableCharacter(CodingErrorAction.REPLACE);
        return d;
    }

    private static ByteBuffer buf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String unstructured(String s, boolean strip) {
        return Rfc2047Decoder.decodeUnstructuredHeaderValue(buf(s), latin1(), strip, false);
    }

    private static String name(String s, String stops) {
        byte[] stopBytes = stops.getBytes(StandardCharsets.ISO_8859_1);
        return Rfc2047Decoder.decodeDisplayName(buf(s), latin1(), false, stopBytes);
    }

    private static String param(String s) {
        return Rfc2047Decoder.decodeParameterValue(buf(s), latin1(), false);
    }

    @Test
    public void testHeaderBytesNullAndEmpty() {
        assertEquals("", Rfc2047Decoder.decodeHeaderValue((byte[]) null));
        assertEquals("", Rfc2047Decoder.decodeHeaderValue(new byte[0]));
    }

    @Test
    public void testHeaderBytesSmtpUtf8() {
        byte[] utf = "café 中".getBytes(StandardCharsets.UTF_8);
        assertEquals("café 中", Rfc2047Decoder.decodeHeaderValue(utf, true));
        byte[] bad = new byte[] {'a', (byte) 0xe9, 'b'};
        assertEquals("aéb", Rfc2047Decoder.decodeHeaderValue(bad, true));
        assertEquals("aéb", Rfc2047Decoder.decodeHeaderValue(bad, false));
    }

    @Test
    public void testHeaderBytesLegacyUtf8Heuristic() {
        byte[] utf = "café".getBytes(StandardCharsets.UTF_8);
        assertEquals("café", Rfc2047Decoder.decodeHeaderValue(utf));
        byte[] euro = new byte[] {'x', (byte) 0x80};
        assertEquals("x€", Rfc2047Decoder.decodeHeaderValue(euro));
        byte[] nul = new byte[] {'x', 0, 'y'};
        String s = Rfc2047Decoder.decodeHeaderValue(nul);
        assertEquals(3, s.length());
    }

    @Test
    public void testHeaderStringNullEmptyAndRaw() {
        assertNull(Rfc2047Decoder.decodeHeaderValue((String) null));
        assertEquals("", Rfc2047Decoder.decodeHeaderValue(""));
        assertEquals("plain", Rfc2047Decoder.decodeHeaderValue("plain", true));
        assertEquals("中 é", Rfc2047Decoder.decodeHeaderValue("中 é", false));
        assertEquals("café", Rfc2047Decoder.decodeHeaderValue("cafÃ©", true));
        assertEquals("café", Rfc2047Decoder.decodeHeaderValue("cafÃ©", false));
        assertEquals("aé", Rfc2047Decoder.decodeHeaderValue("aé", true));
        assertEquals("a€", Rfc2047Decoder.decodeHeaderValue("a\u0080", false));
    }

    @Test
    public void testEncodedWordsNullEmptyAndMalformed() {
        assertNull(Rfc2047Decoder.decodeEncodedWords(null));
        assertEquals("", Rfc2047Decoder.decodeEncodedWords(""));
        assertEquals("=?UTF-8", Rfc2047Decoder.decodeEncodedWords("=?UTF-8"));
        assertEquals("=?UTF-8?", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?"));
        assertEquals("=?UTF-8?X?abc?=", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?X?abc?="));
        assertEquals("=?UTF-8?B", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?B"));
        assertEquals("=?UTF-8?BX", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?BX"));
        assertEquals("=?UTF-8?B?abc", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?B?abc"));
        assertEquals("a =? b", Rfc2047Decoder.decodeEncodedWords("a =? b"));
    }

    @Test
    public void testInvalidBase64IsKeptVerbatim() {
        assertEquals("=?UTF-8?B?abc?=", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?B?abc?="));
        assertEquals("=?UTF-8?B?ab=c?=", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?B?ab=c?="));
        assertEquals("=?UTF-8?B?a===?=", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?B?a===?="));
        assertEquals("=?UTF-8?B?a!bc?=", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?B?a!bc?="));
        assertEquals("=?UTF-8?B?==ab?=", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?B?==ab?="));
        assertEquals("", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?B??="));
        assertEquals("a", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?B?YQ==?="));
    }

    @Test
    public void testQEncodingEdgeCases() {
        assertEquals("a=", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?Q?a=?="));
        assertEquals("a=4", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?Q?a=4?="));
        assertEquals("a=GG!", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?Q?a=GG!?="));
        assertEquals("é!", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?Q?=c3=a9!?="));
        assertEquals("", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?Q??="));
    }

    @Test
    public void testCharsetNormalisationAndUnknownCharset() {
        assertEquals("é", Rfc2047Decoder.decodeEncodedWords("=?latin1?Q?=E9?="));
        assertEquals("é", Rfc2047Decoder.decodeEncodedWords("=?ISO88591?Q?=E9?="));
        assertEquals("é", Rfc2047Decoder.decodeEncodedWords("=?ISO-8859-1?Q?=E9?="));
        assertEquals("€", Rfc2047Decoder.decodeEncodedWords("=?win1252?Q?=80?="));
        assertEquals("€", Rfc2047Decoder.decodeEncodedWords("=?windows-1252?Q?=80?="));
        assertEquals("а", Rfc2047Decoder.decodeEncodedWords("=?koi8r?Q?=C1?="));
        assertEquals("é", Rfc2047Decoder.decodeEncodedWords("=?utf8?Q?=C3=A9?="));
        assertEquals("é", Rfc2047Decoder.decodeEncodedWords("=?x-unknown?Q?=C3=A9?="));
        assertEquals("é", Rfc2047Decoder.decodeEncodedWords("=?ISO885915?Q?=E9?="));
    }

    @Test
    public void testAdjacentEncodedWordsAndSurroundingText() {
        assertEquals("ab", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?Q?a?= =?UTF-8?Q?b?="));
        assertEquals("ab", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?Q?a?=\r\n =?UTF-8?Q?b?="));
        assertEquals("a x b", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?Q?a?= x =?UTF-8?Q?b?="));
        assertEquals("a  b", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?Q?a?=  b"));
        assertEquals("x a", Rfc2047Decoder.decodeEncodedWords("x =?UTF-8?Q?a?="));
        assertEquals("ab", Rfc2047Decoder.decodeEncodedWords("=?UTF-8?Q?a?==?UTF-8?Q?b?="));
    }

    @Test
    public void testUnstructuredFolding() {
        assertEquals("one two three", unstructured("one\r\n two\n\tthree", true));
        ByteBuffer b = buf("a\r\n b");
        Rfc2047Decoder.decodeUnstructuredHeaderValue(b, latin1(), false, false);
        assertEquals(b.limit(), b.position());
        assertEquals("", Rfc2047Decoder.decodeUnstructuredHeaderValue(null, latin1(), true, false));
        ByteBuffer empty = ByteBuffer.allocate(0);
        assertEquals("", Rfc2047Decoder.decodeUnstructuredHeaderValue(empty, latin1(), true, false));
        assertEquals("a", unstructured(" a ", true));
        assertEquals("a", unstructured("a\r\n", true));
        assertEquals("a", unstructured("a\n", true));
        assertEquals("x é", unstructured("x\r\n =?UTF-8?Q?=C3=A9?=", true));
    }

    @Test
    public void testDisplayNameStopsAndQuotes() {
        ByteBuffer b = buf("\"Doe, John\" <j@x>");
        byte[] stops = new byte[] {'<', ','};
        String out = Rfc2047Decoder.decodeDisplayName(b, latin1(), false, stops);
        assertEquals("Doe, John", out);
        assertEquals('<', b.get(b.position()));
        assertEquals("Plain Name", name("Plain Name, other", "<,"));
        assertEquals("", name("<x>", "<"));
        assertEquals("", Rfc2047Decoder.decodeDisplayName(null, latin1(), false, stops));
        assertEquals("esc\\\"aped", name("\"esc\\\"aped\" <x>", "<"));
        assertEquals("\"unterminated", name("\"unterminated", "<"));
        assertEquals("\"a\\", name("\"a\\", "<"));
        assertEquals("Jörg", name("=?UTF-8?Q?J=C3=B6rg?= <x>", "<"));
    }

    @Test
    public void testParameterValueQuotedAndToken() {
        ByteBuffer b = buf("\"a b\\\"c\"; next");
        assertEquals("a b\\\"c", Rfc2047Decoder.decodeParameterValue(b, latin1(), false));
        assertEquals(';', b.get(b.position()));
        assertEquals("token", param("token; next"));
        assertEquals("tok", param("tok\"en"));
        assertEquals("tab", param("tab\tx"));
        assertEquals("unterminated", param("\"unterminated"));
        String s = param("\"x\\");
        assertTrue(s.startsWith("x"));
        assertEquals("", Rfc2047Decoder.decodeParameterValue(null, latin1(), false));
        ByteBuffer empty = ByteBuffer.allocate(0);
        assertEquals("", Rfc2047Decoder.decodeParameterValue(empty, latin1(), false));
        assertEquals("é", param("\"=?UTF-8?Q?=C3=A9?=\""));
        assertEquals("", param("\"\""));
    }

    @Test
    public void testSegmentDecoderErrorFallsBackToCharsetDecode() {
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder();
        strict.onMalformedInput(CodingErrorAction.REPORT);
        strict.onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer b = ByteBuffer.wrap(new byte[] {'a', (byte) 0xff, 'b'});
        String out = Rfc2047Decoder.decodeUnstructuredHeaderValue(b, strict, true, false);
        assertEquals(3, out.length());
        assertEquals('a', out.charAt(0));
    }

    @Test
    public void testUtf8DecoderForUnstructured() {
        ByteBuffer b = ByteBuffer.wrap("café".getBytes(StandardCharsets.UTF_8));
        assertEquals("café", Rfc2047Decoder.decodeUnstructuredHeaderValue(b, utf8(), true, false));
    }

    @Test
    public void testRfc2231Parameter() {
        assertNull(Rfc2047Decoder.decodeRFC2231Parameter(null));
        assertEquals("", Rfc2047Decoder.decodeRFC2231Parameter(""));
        assertEquals("plain", Rfc2047Decoder.decodeRFC2231Parameter("plain"));
        assertEquals("*=x", Rfc2047Decoder.decodeRFC2231Parameter("*=x"));
        assertEquals("n*=utf-8", Rfc2047Decoder.decodeRFC2231Parameter("n*=utf-8"));
        assertEquals("n*=utf-8'en", Rfc2047Decoder.decodeRFC2231Parameter("n*=utf-8'en"));
        assertEquals("Hello World", Rfc2047Decoder.decodeRFC2231Parameter("n*=utf-8'en'Hello%20World"));
        assertEquals("café", Rfc2047Decoder.decodeRFC2231Parameter("n*=utf-8''caf%C3%A9"));
        assertEquals("", Rfc2047Decoder.decodeRFC2231Parameter("n*=utf-8''"));
    }

    @Test
    public void testRfc2231PlusIsLiteral() {
        assertEquals("a+b", Rfc2047Decoder.decodeRFC2231Parameter("n*=utf-8''a+b"));
    }

    @Test
    public void testRfc2231BadEscapeKeptAsIs() {
        assertEquals("n*=utf-8''a%zz", Rfc2047Decoder.decodeRFC2231Parameter("n*=utf-8''a%zz"));
    }
}
