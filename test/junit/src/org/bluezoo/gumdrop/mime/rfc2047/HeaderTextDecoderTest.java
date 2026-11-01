/*
 * HeaderTextDecoderTest.java
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
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.bluezoo.gumdrop.mime.MimeParseException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link HeaderTextDecoder}, which must give the same text however
 * the bytes of the header are divided into chunks.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HeaderTextDecoderTest {

    /** Decodes bytes in chunks of the given size (zero for all at once). */
    private static String decode(byte[] bytes, int chunk, boolean smtpUtf8) throws MimeParseException {
        final StringBuilder out = new StringBuilder();
        HeaderTextDecoder.Sink sink = new HeaderTextDecoder.Sink() {
            @Override
            public void text(String text) {
                out.append(text);
            }
        };
        HeaderTextDecoder decoder = new HeaderTextDecoder(smtpUtf8);
        int size = chunk == 0 ? Math.max(1, bytes.length) : chunk;
        for (int off = 0; off < bytes.length; off += size) {
            decoder.feed(ByteBuffer.wrap(bytes, off, Math.min(size, bytes.length - off)), sink);
        }
        decoder.finish(sink);
        return out.toString();
    }

    private static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Checks the text for the input however it is chunked. */
    private static void check(String expected, byte[] input) throws MimeParseException {
        assertEquals("whole", expected, decode(input, 0, false));
        for (int chunk = 1; chunk <= input.length; chunk++) {
            assertEquals("chunk " + chunk, expected, decode(input, chunk, false));
        }
    }

    private static void check(String expected, String input) throws MimeParseException {
        check(expected, latin1(input));
    }

    @Test
    public void plainText() throws Exception {
        check("hello world", "hello world");
    }

    @Test
    public void edgesAreTrimmedAndInteriorWhitespaceKept() throws Exception {
        check("hello  world", "  hello  world \t ");
    }

    @Test
    public void emptyAndBlank() throws Exception {
        check("", "");
        check("", "   ");
    }

    @Test
    public void base64Word() throws Exception {
        check("caf\u00e9", "=?UTF-8?B?Y2Fmw6k=?=");
    }

    @Test
    public void quotedPrintableWord() throws Exception {
        check("caf\u00e9", "=?UTF-8?Q?caf=C3=A9?=");
        check("a b", "=?UTF-8?Q?a_b?=");
        check("A", "=?UTF-8?Q?=41?=");
        check("J\u00f6rg", "=?iso-8859-1?q?J=F6rg?=");
    }

    @Test
    public void whitespaceBetweenEncodedWordsIsDropped() throws Exception {
        check("ab", "=?UTF-8?Q?a?= =?UTF-8?Q?b?=");
        check("ab", "=?UTF-8?Q?a?=   \t =?UTF-8?Q?b?=");
    }

    @Test
    public void whitespaceAroundEncodedWordsAndTextIsKept() throws Exception {
        check("a b", "=?UTF-8?Q?a?= b");
        check("a b", "a =?UTF-8?Q?b?=");
        check("Re: J\u00f6rg <j@x>", "Re: =?ISO-8859-1?Q?J=F6rg?= <j@x>");
    }

    @Test
    public void anythingNotAnEncodedWordIsText() throws Exception {
        check("=?foo?", "=?foo?");
        check("a=b", "a=b");
        check("=", "=");
        check("==?x", "==?x");
        check("=?UTF-8?X?z?=", "=?UTF-8?X?z?=");
        check("=??Q?z?=", "=??Q?z?=");
        check("=?UTF-8?Q?z?", "=?UTF-8?Q?z?");
    }

    @Test
    public void aWordInTheTextOfAFailedCandidateIsStillFound() throws Exception {
        check("=?x a", "=?x =?UTF-8?Q?a?=");
    }

    @Test
    public void overlongCandidateIsText() throws Exception {
        StringBuilder sb = new StringBuilder("=?UTF-8?Q?");
        for (int i = 0; i < 300; i++) {
            sb.append('a');
        }
        sb.append("?=");
        check(sb.toString(), sb.toString());
    }

    @Test
    public void eightBitTextIsUtf8WhereValid() throws Exception {
        check("caf\u00e9", new byte[] { 'c', 'a', 'f', (byte) 0xc3, (byte) 0xa9 });
        check("\u20ac", new byte[] { (byte) 0xe2, (byte) 0x82, (byte) 0xac });
        check("\ud83d\ude00", new byte[] { (byte) 0xf0, (byte) 0x9f, (byte) 0x98, (byte) 0x80 });
    }

    @Test
    public void eightBitTextThatIsNotUtf8IsWindows1252() throws Exception {
        check("caf\u00e9", new byte[] { 'c', 'a', 'f', (byte) 0xe9 });
        check("\u20ac", new byte[] { (byte) 0x80 });
        // a lead byte that is not followed by its continuation
        check("\u00c3a", new byte[] { (byte) 0xc3, 'a' });
        // an overlong encoding is not UTF-8
        check("\u00c0\u20ac", new byte[] { (byte) 0xc0, (byte) 0x80 });
    }

    @Test
    public void smtpUtf8FallsBackToIso88591() throws Exception {
        byte[] input = { (byte) 0x80, 'a' };
        assertEquals("\u20aca", decode(input, 0, false));
        assertEquals("\u0080a", decode(input, 0, true));
        assertEquals("\u0080a", decode(input, 1, true));
    }

    @Test
    public void longHeaderIsHandedOnInPieces() throws Exception {
        final int[] largest = new int[1];
        final int[] total = new int[1];
        HeaderTextDecoder.Sink sink = new HeaderTextDecoder.Sink() {
            @Override
            public void text(String text) {
                largest[0] = Math.max(largest[0], text.length());
                total[0] += text.length();
            }
        };
        HeaderTextDecoder decoder = new HeaderTextDecoder(false);
        byte[] piece = latin1("word =?UTF-8?Q?x?= and more text ");
        for (int i = 0; i < 100000; i++) {
            decoder.feed(ByteBuffer.wrap(piece), sink);
        }
        decoder.finish(sink);
        assertTrue("pieces are bounded: " + largest[0], largest[0] < 4096);
        assertTrue(total[0] > 1000000);
    }

    @Test
    public void resetStartsAnotherHeader() throws Exception {
        final StringBuilder out = new StringBuilder();
        HeaderTextDecoder.Sink sink = new HeaderTextDecoder.Sink() {
            @Override
            public void text(String text) {
                out.append(text);
            }
        };
        HeaderTextDecoder decoder = new HeaderTextDecoder(false);
        decoder.feed(ByteBuffer.wrap(latin1("a =?UTF-8?Q?b")), sink);
        decoder.reset();
        decoder.feed(ByteBuffer.wrap(latin1("  c")), sink);
        decoder.finish(sink);
        // "a" was handed on before the reset; what was held then is gone
        assertEquals("ac", out.toString());
    }

    @Test
    public void anyChunkingGivesTheSameTextForRandomInput() throws Exception {
        String alphabet = "=?QqBb-8UTFtf aXyz\t_\u00c3\u00a9\u00e9\u0080\u00e2";
        Random random = new Random(42L);
        for (int iteration = 0; iteration < 2000; iteration++) {
            int n = random.nextInt(60);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (random.nextInt(8) == 0) {
                    sb.append("=?UTF-8?").append(random.nextBoolean() ? 'Q' : 'B').append('?');
                } else {
                    sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
            }
            byte[] input = latin1(sb.toString());
            String whole = decode(input, 0, false);
            for (int chunk = 1; chunk <= Math.min(input.length, 9); chunk++) {
                assertEquals(sb + " chunk " + chunk, whole, decode(input, chunk, false));
            }
        }
    }
}
