/*
 * HuffmanTableDecoderTest.java
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

package org.bluezoo.gumdrop.http.hpack;

import org.junit.Test;

import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Characterization tests for {@link Huffman#decode}, written before
 * replacing its one-bit-at-a-time trie walk with a table-driven decoder
 * that consumes a whole byte per step (issue #289 - found profiling the
 * HTTP/2 benchmark scenario, {@code Huffman.decode} was the single largest
 * CPU-sample bucket in Gumdrop's own code).
 *
 * <p>These pin down exactly the properties a byte-at-a-time decoder must
 * preserve that a bit-at-a-time one gets for free, and which the existing
 * {@code HuffmanTest} does not specifically target:
 * <ul>
 * <li>every one of the 256 possible byte values decodes correctly on its
 *     own, whether its code is short (5 bits, e.g. digits) or long enough
 *     to span several byte-sized decode steps (up to 30 bits);</li>
 * <li>a byte-sized decode step can legitimately complete more than one
 *     symbol (two adjacent 5-bit codes fit in fewer than 8 bits) - so a
 *     table transition's output is not always 0 or 1 symbols;</li>
 * <li>random round-trips across a range of lengths, including ones that
 *     don't land on a byte boundary at the end (exercising padding);</li>
 * <li>all pre-existing malformed-input rejections still throw.</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HuffmanTableDecoderTest {

    @Test
    public void testEveryByteValueRoundTripsOnItsOwn() throws IOException {
        for (int b = 0; b <= 255; b++) {
            byte[] original = { (byte) b };
            byte[] encoded = Huffman.encode(original);
            byte[] decoded = Huffman.decode(encoded);
            assertArrayEquals("byte value " + b + " must round-trip on its own",
                    original, decoded);
        }
    }

    @Test
    public void testRunOfShortCodesRoundTrips() throws IOException {
        // '0', '1', '2' each have a 5-bit code (RFC 7541 Appendix B). Five
        // bits is the shortest code in the table, so two of them can never
        // both complete within a single *initial* 8-bit window - but once
        // a byte other than the first is reached mid-code from the
        // previous byte, the remaining bits of that code plus the rest of
        // the byte commonly do complete more than one symbol. A
        // table-driven decoder must be able to emit more than one symbol
        // per byte step, not just zero or one; this string's length is
        // chosen so encoding does not land on a byte boundary between
        // symbols throughout.
        byte[] original = "01201201201".getBytes();
        byte[] decoded = Huffman.decode(Huffman.encode(original));
        assertArrayEquals(original, decoded);
    }

    @Test
    public void testLongCodeSpanningMultipleBytesRoundTrips() throws IOException {
        // Control characters and a few others have the longest HPACK codes
        // (up to 30 bits, i.e. spanning up to 4 encoded bytes for a single
        // symbol) - a table-driven decoder must correctly carry a
        // still-incomplete code across several all-zero-symbol byte steps.
        byte[] original = { 2, 3, 4, 5 }; // each a 28-bit code
        byte[] encoded = Huffman.encode(original);
        assertTrue("28-bit codes must span multiple encoded bytes",
                encoded.length > original.length);
        byte[] decoded = Huffman.decode(encoded);
        assertArrayEquals(original, decoded);
    }

    @Test
    public void testRandomRoundTripsAcrossLengths() throws IOException {
        Random random = new Random(20260828L);
        for (int len = 0; len <= 300; len++) {
            byte[] original = new byte[len];
            for (int i = 0; i < len; i++) {
                original[i] = (byte) random.nextInt(256);
            }
            byte[] encoded = Huffman.encode(original);
            byte[] decoded = Huffman.decode(encoded);
            assertArrayEquals("length " + len + " must round-trip", original, decoded);
        }
    }

    @Test
    public void testRandomAsciiTextRoundTrips() throws IOException {
        // Representative of real HTTP header values (mostly short codes),
        // unlike the uniform-random byte test above.
        Random random = new Random(42L);
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.:/ ";
        for (int trial = 0; trial < 50; trial++) {
            int len = random.nextInt(200);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            String original = sb.toString();
            byte[] encoded = Huffman.encode(original.getBytes());
            byte[] decoded = Huffman.decode(encoded);
            assertEquals(original, new String(decoded));
        }
    }

    @Test(expected = IOException.class)
    public void testAllOnesByteIsInvalid() throws IOException {
        Huffman.decode(new byte[] { (byte) 0xff });
    }

    @Test(expected = IOException.class)
    public void testTruncatedLongCodeIsInvalid() throws IOException {
        // A 28-bit code (control character 2) truncated to 2 bytes: not a
        // complete code, and too much to be legal padding.
        byte[] full = Huffman.encode(new byte[] { 2 });
        byte[] truncated = new byte[] { full[0], full[1] };
        Huffman.decode(truncated);
    }

    @Test
    public void testEmptyInputDecodesToEmpty() throws IOException {
        assertArrayEquals(new byte[0], Huffman.decode(new byte[0]));
    }
}
