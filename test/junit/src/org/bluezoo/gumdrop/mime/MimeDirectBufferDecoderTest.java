/*
 * MimeDirectBufferDecoderTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Exercises the non-array-backed (direct buffer) paths of
 * {@link Base64Decoder} and {@link QuotedPrintableDecoder}, checking that
 * they agree with the array-backed paths.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MimeDirectBufferDecoderTest {

    private static ByteBuffer heap(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static ByteBuffer direct(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocateDirect(bytes.length);
        buf.put(bytes);
        buf.flip();
        return buf;
    }

    private static String drain(ByteBuffer dst) {
        dst.flip();
        byte[] out = new byte[dst.remaining()];
        dst.get(out);
        return new String(out, StandardCharsets.ISO_8859_1);
    }

    private static void assertBase64Same(String input, boolean eos, int max) {
        ByteBuffer heapSrc = heap(input);
        ByteBuffer heapDst = ByteBuffer.allocate(64);
        int heapConsumed = Base64Decoder.decode(heapSrc, heapDst, max, eos);
        ByteBuffer dirSrc = direct(input);
        ByteBuffer dirDst = ByteBuffer.allocateDirect(64);
        int dirConsumed = Base64Decoder.decode(dirSrc, dirDst, max, eos);
        assertEquals(input, heapConsumed, dirConsumed);
        assertEquals(input, drain(heapDst), drain(dirDst));
    }

    private static void assertQpSame(String input, boolean eos, int max) {
        ByteBuffer heapSrc = heap(input);
        ByteBuffer heapDst = ByteBuffer.allocate(64);
        int heapConsumed = QuotedPrintableDecoder.decode(heapSrc, heapDst, max, eos);
        ByteBuffer dirSrc = direct(input);
        ByteBuffer dirDst = ByteBuffer.allocateDirect(64);
        int dirConsumed = QuotedPrintableDecoder.decode(dirSrc, dirDst, max, eos);
        assertEquals(input, heapConsumed, dirConsumed);
        assertEquals(input, drain(heapDst), drain(dirDst));
    }

    @Test
    public void base64DirectMatchesHeap() {
        String[] inputs = {
            "aGVsbG8gd29ybGQ=", "aGVsbG8=", "aGVsbA==", "aGVs\r\nbG8=", "aGVsb",
            "aGVsbG8gd29ybGQh", "", "  \t\n", "aGV$bG8=", "YQ", "YWI"
        };
        for (String input : inputs) {
            assertBase64Same(input, false, 64);
            assertBase64Same(input, true, 64);
        }
    }

    @Test
    public void base64DirectRespectsMaxOutput() {
        assertBase64Same("aGVsbG8gd29ybGQh", true, 4);
        assertBase64Same("aGVsbG8gd29ybGQh", false, 3);
    }

    @Test
    public void base64DirectDecodesHelloWorld() {
        ByteBuffer dst = ByteBuffer.allocateDirect(32);
        Base64Decoder.decode(direct("aGVsbG8gd29ybGQ="), dst, 32, true);
        assertEquals("hello world", drain(dst));
    }

    @Test(expected = IllegalArgumentException.class)
    public void base64StrictLineLengthRejectsLongLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            sb.append('A');
        }
        ByteBuffer dst = ByteBuffer.allocate(128);
        Base64Decoder.decode(heap(sb.toString()), dst, 128, true, true);
    }

    @Test
    public void base64StrictLineLengthAcceptsShortLines() {
        ByteBuffer dst = ByteBuffer.allocate(32);
        Base64Decoder.decode(heap("aGVs\r\nbG8="), dst, 32, true, true);
        assertEquals("hello", drain(dst));
    }

    @Test
    public void quotedPrintableDirectMatchesHeap() {
        String[] inputs = {
            "abc", "a=3Db", "a=3", "a=", "a=\r\nb", "a=\nb", "a=\r", "a=\rx", "a=ZZb",
            "=41=42=43", "a=3d", "trailing=", "soft=\r\n", "x=\r", "=4"
        };
        for (String input : inputs) {
            assertQpSame(input, false, 64);
            assertQpSame(input, true, 64);
        }
    }

    @Test
    public void quotedPrintableDirectRespectsMaxOutput() {
        assertQpSame("=41=42=43=44", true, 2);
        assertQpSame("abcdef", true, 3);
    }

    @Test
    public void quotedPrintableDecodesEscapes() {
        ByteBuffer dst = ByteBuffer.allocateDirect(32);
        QuotedPrintableDecoder.decode(direct("caf=C3=A9 =\r\nok"), dst, 32, true);
        assertEquals("caf\u00C3\u00A9 ok", drain(dst));
    }

    @Test
    public void quotedPrintableEstimateAndDefaultDecode() {
        assertEquals(10, QuotedPrintableDecoder.estimateDecodedSize(10));
        ByteBuffer dst = ByteBuffer.allocate(8);
        int consumed = QuotedPrintableDecoder.decode(heap("a=3Db"), dst, 8);
        assertEquals(5, consumed);
        assertEquals("a=b", drain(dst));
    }
}
