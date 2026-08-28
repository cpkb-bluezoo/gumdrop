/*
 * HuffmanTableEncoderTest.java
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
 * Characterization tests for {@link Huffman#encode}, written before
 * replacing its one-bit-at-a-time {@code BitBuffer} accumulation with a
 * wide-accumulator, whole-byte-at-a-time packer (issue #296 -- the encode
 * side was left bit-at-a-time when the decoder was made table-driven for
 * issue #289; unlike the decoder, the encoder needs no trie/state across
 * byte boundaries -- {@code CODE_BITS}/{@code CODE_LENGTH} are already O(1)
 * per input byte -- so the fix is packing the known code bits into whole
 * output bytes directly instead of one bit at a time).
 *
 * <p>Huffman encoding is canonical per RFC 7541: there is exactly one
 * correct encoded byte sequence for any given input, so unlike a typical
 * refactor these tests check exact output bytes, not just that encode and
 * decode remain each other's inverse. These pin down the properties a
 * rewritten encoder must preserve:
 * <ul>
 * <li>every one of the 256 possible byte values encodes to its documented
 *     RFC 7541 Appendix B code, whether short (5 bits) or long enough to
 *     span several output bytes on its own (up to 28 bits);</li>
 * <li>runs of short codes that don't align to byte boundaries pack
 *     correctly, including runs whose bit length is a non-multiple of 8
 *     (exercising the final-byte padding path);</li>
 * <li>the well-known RFC 7541 Appendix C.4.1 example strings still
 *     produce their documented exact byte sequences (already covered by
 *     {@code HuffmanTest}, re-asserted here as the exact-output baseline
 *     this rewrite must not disturb);</li>
 * <li>random round-trips across a range of lengths.</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HuffmanTableEncoderTest {

    @Test
    public void testEveryByteValueEncodesToItsDocumentedCode() throws IOException {
        for (int b = 0; b <= 255; b++) {
            byte[] encoded = Huffman.encode(new byte[] { (byte) b });
            byte[] expected = expectedEncoding(new int[] { b });
            assertArrayEquals("byte value " + b + " must encode to its "
                    + "RFC 7541 Appendix B code", expected, encoded);
        }
    }

    @Test
    public void testRunOfShortCodesPacksExactly() throws IOException {
        // '0','1','2' each have a 5-bit code (RFC 7541 Appendix B): eleven
        // of them is 55 bits, not a multiple of 8, so this exercises both
        // mid-run byte-boundary crossings and the final padding path.
        byte[] original = "01201201201".getBytes();
        byte[] encoded = Huffman.encode(original);
        byte[] expected = expectedEncoding(new int[] {
                '0', '1', '2', '0', '1', '2', '0', '1', '2', '0', '1'
        });
        assertArrayEquals(expected, encoded);
        assertArrayEquals(original, Huffman.decode(encoded));
    }

    @Test
    public void testLongCodeSpanningMultipleBytesEncodesExactly() throws IOException {
        // Control characters 2-8 etc. have 28-bit codes -- a single symbol
        // spans more than three output bytes on its own.
        byte[] original = { 2, 3, 4, 5 };
        byte[] encoded = Huffman.encode(original);
        byte[] expected = expectedEncoding(new int[] { 2, 3, 4, 5 });
        assertArrayEquals(expected, encoded);
        assertTrue("28-bit codes must span multiple encoded bytes",
                encoded.length > original.length);
    }

    @Test
    public void testRfc7541ExampleStringsEncodeToDocumentedBytes() throws IOException {
        assertArrayEquals(
                new byte[] {
                        (byte) 0xf1, (byte) 0xe3, (byte) 0xc2, (byte) 0xe5, (byte) 0xf2,
                        ':', 'k', (byte) 0xa0, (byte) 0xab, (byte) 0x90, (byte) 0xf4,
                        (byte) 0xff
                },
                Huffman.encode("www.example.com".getBytes()));
        assertArrayEquals(
                new byte[] {
                        (byte) 0xa8, (byte) 0xeb, (byte) 0x10, 'd', (byte) 0x9c, (byte) 0xbf
                },
                Huffman.encode("no-cache".getBytes()));
        assertArrayEquals(
                new byte[] {
                        '%', (byte) 0xa8, 'I', (byte) 0xe9, '[', (byte) 0xa9, '}', (byte) 0x7f
                },
                Huffman.encode("custom-key".getBytes()));
    }

    @Test
    public void testEmptyInputEncodesToEmpty() {
        assertEquals(0, Huffman.encode(new byte[0]).length);
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
            byte[] expected = expectedEncoding(toIntArray(original));
            assertArrayEquals("length " + len + " must encode exactly",
                    expected, encoded);
            assertArrayEquals("length " + len + " must round-trip",
                    original, Huffman.decode(encoded));
        }
    }

    // ── reference (bit-at-a-time) encoder, kept deliberately independent
    // of Huffman.encode()'s own implementation, so these tests still mean
    // something after Huffman.encode() itself is rewritten -- otherwise a
    // bug shared by both would go undetected. ──

    private static byte[] expectedEncoding(int[] byteValues) {
        StringBuilder bits = new StringBuilder();
        for (int value : byteValues) {
            int len = CODE_LENGTH(value);
            int code = CODE_BITS(value);
            for (int i = len - 1; i >= 0; i--) {
                bits.append((code >> i) & 1);
            }
        }
        while (bits.length() % 8 != 0) {
            bits.append('1');
        }
        byte[] out = new byte[bits.length() / 8];
        for (int i = 0; i < out.length; i++) {
            int b = Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
            out[i] = (byte) b;
        }
        return out;
    }

    private static int[] toIntArray(byte[] bytes) {
        int[] result = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            result[i] = bytes[i] & 0xFF;
        }
        return result;
    }

    // Reflective access to Huffman's own RFC 7541 Appendix B tables: the
    // point of this reference implementation is to be a genuinely
    // independent bit-packing algorithm over the SAME published code
    // table, not a second copy of the table itself, which would just be
    // more code to keep in sync for no extra correctness signal.
    private static int CODE_BITS(int value) {
        try {
            java.lang.reflect.Field f = Huffman.class.getDeclaredField("CODE_BITS");
            f.setAccessible(true);
            return ((int[]) f.get(null))[value];
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int CODE_LENGTH(int value) {
        try {
            java.lang.reflect.Field f = Huffman.class.getDeclaredField("CODE_LENGTH");
            f.setAccessible(true);
            return ((byte[]) f.get(null))[value];
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
