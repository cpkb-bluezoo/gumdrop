/*
 * Huffman.java
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

package org.bluezoo.gumdrop.http.hpack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Huffman decoder and encoder for HPACK-encoded byte arrays. This implementation uses the static
 * Huffman code defined in RFC 7541, Appendix B. It builds an in-memory decoding tree (trie) from
 * the predefined codes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc7541.html#appendix-B
 */
public class Huffman {

    // Represents a node in the Huffman decoding tree (trie).
    private static class HuffmanNode {

        // The decoded byte value if this is a leaf node. -1 if not a leaf or not yet decoded.
        // 0x100 (256) is used as a placeholder for the End-of-String (EOS) symbol.
        short value = -1;

        // Flag to indicate if this node represents a valid end of a Huffman code.
        // This is important because some codes are prefixes of others, but only
        // a leaf node that is also a 'terminal' node should yield a character.
        boolean terminal;

        // Child node for bit 0
        HuffmanNode left;

        // Child node for bit 1
        HuffmanNode right;
    }

    // Record to hold a Huffman code value and its bit length.
    private static class HuffmanCodeInfo {

        // value data
        int bits;

        // number of bits
        short numBits;

        HuffmanCodeInfo(int bits, short numBits) {
            this.bits = bits;
            this.numBits = numBits;
        }
    }

    // The root of the static Huffman decoding tree.
    private static final HuffmanNode ROOT = new HuffmanNode();

    // We use a short value of 0x100 (256) to represent EOS, as actual byte values are 0-255.
    private static final short EOS_VALUE_PLACEHOLDER = 0x100;

    // Static block to initialize the Huffman decoding tree.
    // This table contains a subset of common ASCII characters and the EOS symbol
    // as defined in RFC 7541, Appendix B
    // In a full implementation, the entire table would be used.
    private static final Map<Short, HuffmanCodeInfo> HPACK_HUFFMAN_CODES = new TreeMap<>();

    // Helper to convert binary string to int
    private static int binaryStringToInt(String binaryString) {
        return Integer.parseInt(binaryString, 2);
    }

    static {
        // Table from RFC 7541, Appendix B
        HPACK_HUFFMAN_CODES.put((short) 0, new HuffmanCodeInfo(0x1ff8, (short) 13));
        HPACK_HUFFMAN_CODES.put((short) 1, new HuffmanCodeInfo(0x7fffd8, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 2, new HuffmanCodeInfo(0xfffffe2, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 3, new HuffmanCodeInfo(0xfffffe3, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 4, new HuffmanCodeInfo(0xfffffe4, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 5, new HuffmanCodeInfo(0xfffffe5, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 6, new HuffmanCodeInfo(0xfffffe6, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 7, new HuffmanCodeInfo(0xfffffe7, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 8, new HuffmanCodeInfo(0xfffffe8, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 9, new HuffmanCodeInfo(0xffffea, (short) 24));
        HPACK_HUFFMAN_CODES.put((short) 10, new HuffmanCodeInfo(0x3ffffffc, (short) 30));
        HPACK_HUFFMAN_CODES.put((short) 11, new HuffmanCodeInfo(0xfffffe9, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 12, new HuffmanCodeInfo(0xfffffea, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 13, new HuffmanCodeInfo(0x3ffffffd, (short) 30));
        HPACK_HUFFMAN_CODES.put((short) 14, new HuffmanCodeInfo(0xfffffeb, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 15, new HuffmanCodeInfo(0xfffffec, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 16, new HuffmanCodeInfo(0xfffffed, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 17, new HuffmanCodeInfo(0xfffffee, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 18, new HuffmanCodeInfo(0xfffffef, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 19, new HuffmanCodeInfo(0xffffff0, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 20, new HuffmanCodeInfo(0xffffff1, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 21, new HuffmanCodeInfo(0xffffff2, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 22, new HuffmanCodeInfo(0x3ffffffe, (short) 30));
        HPACK_HUFFMAN_CODES.put((short) 23, new HuffmanCodeInfo(0xffffff3, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 24, new HuffmanCodeInfo(0xffffff4, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 25, new HuffmanCodeInfo(0xffffff5, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 26, new HuffmanCodeInfo(0xffffff6, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 27, new HuffmanCodeInfo(0xffffff7, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 28, new HuffmanCodeInfo(0xffffff8, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 29, new HuffmanCodeInfo(0xffffff9, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 30, new HuffmanCodeInfo(0xffffffa, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 31, new HuffmanCodeInfo(0xffffffb, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 32, new HuffmanCodeInfo(0x14, (short) 6)); // ' '
        HPACK_HUFFMAN_CODES.put((short) 33, new HuffmanCodeInfo(0x3f8, (short) 10)); // '!'
        HPACK_HUFFMAN_CODES.put((short) 34, new HuffmanCodeInfo(0x3f9, (short) 10)); // '"'
        HPACK_HUFFMAN_CODES.put((short) 35, new HuffmanCodeInfo(0xffa, (short) 12)); // '#'
        HPACK_HUFFMAN_CODES.put((short) 36, new HuffmanCodeInfo(0x1ff9, (short) 13)); // '$'
        HPACK_HUFFMAN_CODES.put((short) 37, new HuffmanCodeInfo(0x15, (short) 6)); // '%'
        HPACK_HUFFMAN_CODES.put((short) 38, new HuffmanCodeInfo(0xf8, (short) 8)); // '&'
        HPACK_HUFFMAN_CODES.put((short) 39, new HuffmanCodeInfo(0x7fa, (short) 11)); // '''
        HPACK_HUFFMAN_CODES.put((short) 40, new HuffmanCodeInfo(0x3fa, (short) 10)); // '('
        HPACK_HUFFMAN_CODES.put((short) 41, new HuffmanCodeInfo(0x3fb, (short) 10)); // ')'
        HPACK_HUFFMAN_CODES.put((short) 42, new HuffmanCodeInfo(0xf9, (short) 8)); // '*'
        HPACK_HUFFMAN_CODES.put((short) 43, new HuffmanCodeInfo(0x7fb, (short) 11)); // '+'
        HPACK_HUFFMAN_CODES.put((short) 44, new HuffmanCodeInfo(0xfa, (short) 8)); // ','
        HPACK_HUFFMAN_CODES.put((short) 45, new HuffmanCodeInfo(0x16, (short) 6)); // '-'
        HPACK_HUFFMAN_CODES.put((short) 46, new HuffmanCodeInfo(0x17, (short) 6)); // '.'
        HPACK_HUFFMAN_CODES.put((short) 47, new HuffmanCodeInfo(0x18, (short) 6)); // '/'
        HPACK_HUFFMAN_CODES.put((short) 48, new HuffmanCodeInfo(0x0, (short) 5)); // '0'
        HPACK_HUFFMAN_CODES.put((short) 49, new HuffmanCodeInfo(0x1, (short) 5)); // '1'
        HPACK_HUFFMAN_CODES.put((short) 50, new HuffmanCodeInfo(0x2, (short) 5)); // '2'
        HPACK_HUFFMAN_CODES.put((short) 51, new HuffmanCodeInfo(0x19, (short) 6)); // '3'
        HPACK_HUFFMAN_CODES.put((short) 52, new HuffmanCodeInfo(0x1a, (short) 6)); // '4'
        HPACK_HUFFMAN_CODES.put((short) 53, new HuffmanCodeInfo(0x1b, (short) 6)); // '5'
        HPACK_HUFFMAN_CODES.put((short) 54, new HuffmanCodeInfo(0x1c, (short) 6)); // '6'
        HPACK_HUFFMAN_CODES.put((short) 55, new HuffmanCodeInfo(0x1d, (short) 6)); // '7'
        HPACK_HUFFMAN_CODES.put((short) 56, new HuffmanCodeInfo(0x1e, (short) 6)); // '8'
        HPACK_HUFFMAN_CODES.put((short) 57, new HuffmanCodeInfo(0x1f, (short) 6)); // '9'
        HPACK_HUFFMAN_CODES.put((short) 58, new HuffmanCodeInfo(0x5c, (short) 7)); // ':'
        HPACK_HUFFMAN_CODES.put((short) 59, new HuffmanCodeInfo(0xfb, (short) 8)); // ';'
        HPACK_HUFFMAN_CODES.put((short) 60, new HuffmanCodeInfo(0x7ffc, (short) 15)); // '<'
        HPACK_HUFFMAN_CODES.put((short) 61, new HuffmanCodeInfo(0x20, (short) 6)); // '='
        HPACK_HUFFMAN_CODES.put((short) 62, new HuffmanCodeInfo(0xffb, (short) 12)); // '>'
        HPACK_HUFFMAN_CODES.put((short) 63, new HuffmanCodeInfo(0x3fc, (short) 10)); // '?'
        HPACK_HUFFMAN_CODES.put((short) 64, new HuffmanCodeInfo(0x1ffa, (short) 13)); // '@'
        HPACK_HUFFMAN_CODES.put((short) 65, new HuffmanCodeInfo(0x21, (short) 6)); // 'A'
        HPACK_HUFFMAN_CODES.put((short) 66, new HuffmanCodeInfo(0x5d, (short) 7)); // 'B'
        HPACK_HUFFMAN_CODES.put((short) 67, new HuffmanCodeInfo(0x5e, (short) 7)); // 'C'
        HPACK_HUFFMAN_CODES.put((short) 68, new HuffmanCodeInfo(0x5f, (short) 7)); // 'D'
        HPACK_HUFFMAN_CODES.put((short) 69, new HuffmanCodeInfo(0x60, (short) 7)); // 'E'
        HPACK_HUFFMAN_CODES.put((short) 70, new HuffmanCodeInfo(0x61, (short) 7)); // 'F'
        HPACK_HUFFMAN_CODES.put((short) 71, new HuffmanCodeInfo(0x62, (short) 7)); // 'G'
        HPACK_HUFFMAN_CODES.put((short) 72, new HuffmanCodeInfo(0x63, (short) 7)); // 'H'
        HPACK_HUFFMAN_CODES.put((short) 73, new HuffmanCodeInfo(0x64, (short) 7)); // 'I'
        HPACK_HUFFMAN_CODES.put((short) 74, new HuffmanCodeInfo(0x65, (short) 7)); // 'J'
        HPACK_HUFFMAN_CODES.put((short) 75, new HuffmanCodeInfo(0x66, (short) 7)); // 'K'
        HPACK_HUFFMAN_CODES.put((short) 76, new HuffmanCodeInfo(0x67, (short) 7)); // 'L'
        HPACK_HUFFMAN_CODES.put((short) 77, new HuffmanCodeInfo(0x68, (short) 7)); // 'M'
        HPACK_HUFFMAN_CODES.put((short) 78, new HuffmanCodeInfo(0x69, (short) 7)); // 'N'
        HPACK_HUFFMAN_CODES.put((short) 79, new HuffmanCodeInfo(0x6a, (short) 7)); // 'O'
        HPACK_HUFFMAN_CODES.put((short) 80, new HuffmanCodeInfo(0x6b, (short) 7)); // 'P'
        HPACK_HUFFMAN_CODES.put((short) 81, new HuffmanCodeInfo(0x6c, (short) 7)); // 'Q'
        HPACK_HUFFMAN_CODES.put((short) 82, new HuffmanCodeInfo(0x6d, (short) 7)); // 'R'
        HPACK_HUFFMAN_CODES.put((short) 83, new HuffmanCodeInfo(0x6e, (short) 7)); // 'S'
        HPACK_HUFFMAN_CODES.put((short) 84, new HuffmanCodeInfo(0x6f, (short) 7)); // 'T'
        HPACK_HUFFMAN_CODES.put((short) 85, new HuffmanCodeInfo(0x70, (short) 7)); // 'U'
        HPACK_HUFFMAN_CODES.put((short) 86, new HuffmanCodeInfo(0x71, (short) 7)); // 'V'
        HPACK_HUFFMAN_CODES.put((short) 87, new HuffmanCodeInfo(0x72, (short) 7)); // 'W'
        HPACK_HUFFMAN_CODES.put((short) 88, new HuffmanCodeInfo(0xfc, (short) 8)); // 'X'
        HPACK_HUFFMAN_CODES.put((short) 89, new HuffmanCodeInfo(0x73, (short) 7)); // 'Y'
        HPACK_HUFFMAN_CODES.put((short) 90, new HuffmanCodeInfo(0xfd, (short) 8)); // 'Z'
        HPACK_HUFFMAN_CODES.put((short) 91, new HuffmanCodeInfo(0x1ffb, (short) 13)); // '['
        HPACK_HUFFMAN_CODES.put((short) 92, new HuffmanCodeInfo(0x7fff0, (short) 19)); // '\'
        HPACK_HUFFMAN_CODES.put((short) 93, new HuffmanCodeInfo(0x1ffc, (short) 13)); // ']'
        HPACK_HUFFMAN_CODES.put((short) 94, new HuffmanCodeInfo(0x3ffc, (short) 14)); // '^'
        HPACK_HUFFMAN_CODES.put((short) 95, new HuffmanCodeInfo(0x22, (short) 6)); // '_'
        HPACK_HUFFMAN_CODES.put((short) 96, new HuffmanCodeInfo(0x7ffd, (short) 15)); // '`'
        HPACK_HUFFMAN_CODES.put((short) 97, new HuffmanCodeInfo(0x3, (short) 5)); // 'a'
        HPACK_HUFFMAN_CODES.put((short) 98, new HuffmanCodeInfo(0x23, (short) 6)); // 'b'
        HPACK_HUFFMAN_CODES.put((short) 99, new HuffmanCodeInfo(0x4, (short) 5)); // 'c'
        HPACK_HUFFMAN_CODES.put((short) 100, new HuffmanCodeInfo(0x24, (short) 6)); // 'd'
        HPACK_HUFFMAN_CODES.put((short) 101, new HuffmanCodeInfo(0x5, (short) 5)); // 'e'
        HPACK_HUFFMAN_CODES.put((short) 102, new HuffmanCodeInfo(0x25, (short) 6)); // 'f'
        HPACK_HUFFMAN_CODES.put((short) 103, new HuffmanCodeInfo(0x26, (short) 6)); // 'g'
        HPACK_HUFFMAN_CODES.put((short) 104, new HuffmanCodeInfo(0x27, (short) 6)); // 'h'
        HPACK_HUFFMAN_CODES.put((short) 105, new HuffmanCodeInfo(0x6, (short) 5)); // 'i'
        HPACK_HUFFMAN_CODES.put((short) 106, new HuffmanCodeInfo(0x74, (short) 7)); // 'j'
        HPACK_HUFFMAN_CODES.put((short) 107, new HuffmanCodeInfo(0x75, (short) 7)); // 'k'
        HPACK_HUFFMAN_CODES.put((short) 108, new HuffmanCodeInfo(0x28, (short) 6)); // 'l'
        HPACK_HUFFMAN_CODES.put((short) 109, new HuffmanCodeInfo(0x29, (short) 6)); // 'm'
        HPACK_HUFFMAN_CODES.put((short) 110, new HuffmanCodeInfo(0x2a, (short) 6)); // 'n'
        HPACK_HUFFMAN_CODES.put((short) 111, new HuffmanCodeInfo(0x7, (short) 5)); // 'o'
        HPACK_HUFFMAN_CODES.put((short) 112, new HuffmanCodeInfo(0x2b, (short) 6)); // 'p'
        HPACK_HUFFMAN_CODES.put((short) 113, new HuffmanCodeInfo(0x76, (short) 7)); // 'q'
        HPACK_HUFFMAN_CODES.put((short) 114, new HuffmanCodeInfo(0x2c, (short) 6)); // 'r'
        HPACK_HUFFMAN_CODES.put((short) 115, new HuffmanCodeInfo(0x8, (short) 5)); // 's'
        HPACK_HUFFMAN_CODES.put((short) 116, new HuffmanCodeInfo(0x9, (short) 5)); // 't'
        HPACK_HUFFMAN_CODES.put((short) 117, new HuffmanCodeInfo(0x2d, (short) 6)); // 'u'
        HPACK_HUFFMAN_CODES.put((short) 118, new HuffmanCodeInfo(0x77, (short) 7)); // 'v'
        HPACK_HUFFMAN_CODES.put((short) 119, new HuffmanCodeInfo(0x78, (short) 7)); // 'w'
        HPACK_HUFFMAN_CODES.put((short) 120, new HuffmanCodeInfo(0x79, (short) 7)); // 'x'
        HPACK_HUFFMAN_CODES.put((short) 121, new HuffmanCodeInfo(0x7a, (short) 7)); // 'y'
        HPACK_HUFFMAN_CODES.put((short) 122, new HuffmanCodeInfo(0x7b, (short) 7)); // 'z'
        HPACK_HUFFMAN_CODES.put((short) 123, new HuffmanCodeInfo(0x7ffe, (short) 15)); // '{'
        HPACK_HUFFMAN_CODES.put((short) 124, new HuffmanCodeInfo(0x7fc, (short) 11)); // '|'
        HPACK_HUFFMAN_CODES.put((short) 125, new HuffmanCodeInfo(0x3ffd, (short) 14)); // '}'
        HPACK_HUFFMAN_CODES.put((short) 126, new HuffmanCodeInfo(0x1ffd, (short) 13)); // '~'
        HPACK_HUFFMAN_CODES.put((short) 127, new HuffmanCodeInfo(0xffffffc, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 128, new HuffmanCodeInfo(0xfffe6, (short) 20));
        HPACK_HUFFMAN_CODES.put((short) 129, new HuffmanCodeInfo(0x3fffd2, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 130, new HuffmanCodeInfo(0xfffe7, (short) 20));
        HPACK_HUFFMAN_CODES.put((short) 131, new HuffmanCodeInfo(0xfffe8, (short) 20));
        HPACK_HUFFMAN_CODES.put((short) 132, new HuffmanCodeInfo(0x3fffd3, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 133, new HuffmanCodeInfo(0x3fffd4, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 134, new HuffmanCodeInfo(0x3fffd5, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 135, new HuffmanCodeInfo(0x7fffd9, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 136, new HuffmanCodeInfo(0x3fffd6, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 137, new HuffmanCodeInfo(0x7fffda, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 138, new HuffmanCodeInfo(0x7fffdb, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 139, new HuffmanCodeInfo(0x7fffdc, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 140, new HuffmanCodeInfo(0x7fffdd, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 141, new HuffmanCodeInfo(0x7fffde, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 142, new HuffmanCodeInfo(0xffffeb, (short) 24));
        HPACK_HUFFMAN_CODES.put((short) 143, new HuffmanCodeInfo(0x7fffdf, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 144, new HuffmanCodeInfo(0xffffec, (short) 24));
        HPACK_HUFFMAN_CODES.put((short) 145, new HuffmanCodeInfo(0xffffed, (short) 24));
        HPACK_HUFFMAN_CODES.put((short) 146, new HuffmanCodeInfo(0x3fffd7, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 147, new HuffmanCodeInfo(0x7fffe0, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 148, new HuffmanCodeInfo(0xffffee, (short) 24));
        HPACK_HUFFMAN_CODES.put((short) 149, new HuffmanCodeInfo(0x7fffe1, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 150, new HuffmanCodeInfo(0x7fffe2, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 151, new HuffmanCodeInfo(0x7fffe3, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 152, new HuffmanCodeInfo(0x7fffe4, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 153, new HuffmanCodeInfo(0x1fffdc, (short) 21));
        HPACK_HUFFMAN_CODES.put((short) 154, new HuffmanCodeInfo(0x3fffd8, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 155, new HuffmanCodeInfo(0x7fffe5, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 156, new HuffmanCodeInfo(0x3fffd9, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 157, new HuffmanCodeInfo(0x7fffe6, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 158, new HuffmanCodeInfo(0x7fffe7, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 159, new HuffmanCodeInfo(0xffffef, (short) 24));
        HPACK_HUFFMAN_CODES.put((short) 160, new HuffmanCodeInfo(0x3fffda, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 161, new HuffmanCodeInfo(0x1fffdd, (short) 21));
        HPACK_HUFFMAN_CODES.put((short) 162, new HuffmanCodeInfo(0xfffe9, (short) 20));
        HPACK_HUFFMAN_CODES.put((short) 163, new HuffmanCodeInfo(0x3fffdb, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 164, new HuffmanCodeInfo(0x3fffdc, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 165, new HuffmanCodeInfo(0x7fffe8, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 166, new HuffmanCodeInfo(0x7fffe9, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 167, new HuffmanCodeInfo(0x1fffde, (short) 21));
        HPACK_HUFFMAN_CODES.put((short) 168, new HuffmanCodeInfo(0x7fffea, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 169, new HuffmanCodeInfo(0x3fffdd, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 170, new HuffmanCodeInfo(0x3fffde, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 171, new HuffmanCodeInfo(0xfffff0, (short) 24));
        HPACK_HUFFMAN_CODES.put((short) 172, new HuffmanCodeInfo(0x1fffdf, (short) 21));
        HPACK_HUFFMAN_CODES.put((short) 173, new HuffmanCodeInfo(0x3fffdf, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 174, new HuffmanCodeInfo(0x7fffeb, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 175, new HuffmanCodeInfo(0x7fffec, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 176, new HuffmanCodeInfo(0x1fffe0, (short) 21));
        HPACK_HUFFMAN_CODES.put((short) 177, new HuffmanCodeInfo(0x1fffe1, (short) 21));
        HPACK_HUFFMAN_CODES.put((short) 178, new HuffmanCodeInfo(0x3fffe0, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 179, new HuffmanCodeInfo(0x1fffe2, (short) 21));
        HPACK_HUFFMAN_CODES.put((short) 180, new HuffmanCodeInfo(0x7fffed, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 181, new HuffmanCodeInfo(0x3fffe1, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 182, new HuffmanCodeInfo(0x7fffee, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 183, new HuffmanCodeInfo(0x7fffef, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 184, new HuffmanCodeInfo(0xfffea, (short) 20));
        HPACK_HUFFMAN_CODES.put((short) 185, new HuffmanCodeInfo(0x3fffe2, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 186, new HuffmanCodeInfo(0x3fffe3, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 187, new HuffmanCodeInfo(0x3fffe4, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 188, new HuffmanCodeInfo(0x7ffff0, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 189, new HuffmanCodeInfo(0x3fffe5, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 190, new HuffmanCodeInfo(0x3fffe6, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 191, new HuffmanCodeInfo(0x7ffff1, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 192, new HuffmanCodeInfo(0x3ffffe0, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 193, new HuffmanCodeInfo(0x3ffffe1, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 194, new HuffmanCodeInfo(0xfffeb, (short) 20));
        HPACK_HUFFMAN_CODES.put((short) 195, new HuffmanCodeInfo(0x7fff1, (short) 19));
        HPACK_HUFFMAN_CODES.put((short) 196, new HuffmanCodeInfo(0x3fffe7, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 197, new HuffmanCodeInfo(0x7ffff2, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 198, new HuffmanCodeInfo(0x3fffe8, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 199, new HuffmanCodeInfo(0x1ffffec, (short) 25));
        HPACK_HUFFMAN_CODES.put((short) 200, new HuffmanCodeInfo(0x3ffffe2, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 201, new HuffmanCodeInfo(0x3ffffe3, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 202, new HuffmanCodeInfo(0x3ffffe4, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 203, new HuffmanCodeInfo(0x7ffffde, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 204, new HuffmanCodeInfo(0x7ffffdf, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 205, new HuffmanCodeInfo(0x3ffffe5, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 206, new HuffmanCodeInfo(0xfffff1, (short) 24));
        HPACK_HUFFMAN_CODES.put((short) 207, new HuffmanCodeInfo(0x1ffffed, (short) 25));
        HPACK_HUFFMAN_CODES.put((short) 208, new HuffmanCodeInfo(0x7fff2, (short) 19));
        HPACK_HUFFMAN_CODES.put((short) 209, new HuffmanCodeInfo(0x1fffe3, (short) 21));
        HPACK_HUFFMAN_CODES.put((short) 210, new HuffmanCodeInfo(0x3ffffe6, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 211, new HuffmanCodeInfo(0x7ffffe0, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 212, new HuffmanCodeInfo(0x7ffffe1, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 213, new HuffmanCodeInfo(0x3ffffe7, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 214, new HuffmanCodeInfo(0x7ffffe2, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 215, new HuffmanCodeInfo(0xfffff2, (short) 24));
        HPACK_HUFFMAN_CODES.put((short) 216, new HuffmanCodeInfo(0x1fffe4, (short) 21));
        HPACK_HUFFMAN_CODES.put((short) 217, new HuffmanCodeInfo(0x1fffe5, (short) 21));
        HPACK_HUFFMAN_CODES.put((short) 218, new HuffmanCodeInfo(0x3ffffe8, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 219, new HuffmanCodeInfo(0x3ffffe9, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 220, new HuffmanCodeInfo(0xffffffd, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 221, new HuffmanCodeInfo(0x7ffffe3, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 222, new HuffmanCodeInfo(0x7ffffe4, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 223, new HuffmanCodeInfo(0x7ffffe5, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 224, new HuffmanCodeInfo(0xfffec, (short) 20));
        HPACK_HUFFMAN_CODES.put((short) 225, new HuffmanCodeInfo(0xfffff3, (short) 24));
        HPACK_HUFFMAN_CODES.put((short) 226, new HuffmanCodeInfo(0xfffed, (short) 20));
        HPACK_HUFFMAN_CODES.put((short) 227, new HuffmanCodeInfo(0x1fffe6, (short) 21));
        HPACK_HUFFMAN_CODES.put((short) 228, new HuffmanCodeInfo(0x3fffe9, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 229, new HuffmanCodeInfo(0x1fffe7, (short) 21));
        HPACK_HUFFMAN_CODES.put((short) 230, new HuffmanCodeInfo(0x1fffe8, (short) 21));
        HPACK_HUFFMAN_CODES.put((short) 231, new HuffmanCodeInfo(0x7ffff3, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 232, new HuffmanCodeInfo(0x3fffea, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 233, new HuffmanCodeInfo(0x3fffeb, (short) 22));
        HPACK_HUFFMAN_CODES.put((short) 234, new HuffmanCodeInfo(0x1ffffee, (short) 25));
        HPACK_HUFFMAN_CODES.put((short) 235, new HuffmanCodeInfo(0x1ffffef, (short) 25));
        HPACK_HUFFMAN_CODES.put((short) 236, new HuffmanCodeInfo(0xfffff4, (short) 24));
        HPACK_HUFFMAN_CODES.put((short) 237, new HuffmanCodeInfo(0xfffff5, (short) 24));
        HPACK_HUFFMAN_CODES.put((short) 238, new HuffmanCodeInfo(0x3ffffea, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 239, new HuffmanCodeInfo(0x7ffff4, (short) 23));
        HPACK_HUFFMAN_CODES.put((short) 240, new HuffmanCodeInfo(0x3ffffeb, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 241, new HuffmanCodeInfo(0x7ffffe6, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 242, new HuffmanCodeInfo(0x3ffffec, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 243, new HuffmanCodeInfo(0x3ffffed, (short) 26));
        HPACK_HUFFMAN_CODES.put((short) 244, new HuffmanCodeInfo(0x7ffffe7, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 245, new HuffmanCodeInfo(0x7ffffe8, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 246, new HuffmanCodeInfo(0x7ffffe9, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 247, new HuffmanCodeInfo(0x7ffffea, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 248, new HuffmanCodeInfo(0x7ffffeb, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 249, new HuffmanCodeInfo(0xffffffe, (short) 28));
        HPACK_HUFFMAN_CODES.put((short) 250, new HuffmanCodeInfo(0x7ffffec, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 251, new HuffmanCodeInfo(0x7ffffed, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 252, new HuffmanCodeInfo(0x7ffffee, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 253, new HuffmanCodeInfo(0x7ffffef, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 254, new HuffmanCodeInfo(0x7fffff0, (short) 27));
        HPACK_HUFFMAN_CODES.put((short) 255, new HuffmanCodeInfo(0x3ffffee, (short) 26));

        // The special EOS symbol (End-of-String) is represented by 31 ones.
        // This is a sentinel value, not a decodable character.
        HPACK_HUFFMAN_CODES.put(EOS_VALUE_PLACEHOLDER, new HuffmanCodeInfo(0x3fffffff, (short) 30));

        buildHuffmanTree();
    }

    /**
     * Builds the Huffman decoding tree from the predefined HPACK Huffman codes.
     */
    private static void buildHuffmanTree() {
        for (Map.Entry<Short, HuffmanCodeInfo> entry : HPACK_HUFFMAN_CODES.entrySet()) {
            short value = entry.getKey();
            HuffmanCodeInfo codeInfo = entry.getValue();
            int codeBits = codeInfo.bits;
            short numBits = codeInfo.numBits;
            HuffmanNode currentNode = ROOT;

            // Iterate through bits from MSB to LSB of the Huffman code
            for (short i = 0; i < numBits; i++) {
                // Extract the bit at current position (from MSB of the code)
                int bit = (codeBits >> (numBits - 1 - i)) & 1;

                if (bit == 0) {
                    if (currentNode.left == null) {
                        currentNode.left = new HuffmanNode();
                    }
                    currentNode = currentNode.left;
                } else { // bit == 1
                    if (currentNode.right == null) {
                        currentNode.right = new HuffmanNode();
                    }
                    currentNode = currentNode.right;
                }
            }
            // Mark the end of a code path.
            if (value != EOS_VALUE_PLACEHOLDER) {
                currentNode.terminal = true;
                currentNode.value = value;
            } else {
                // Mark the EOS node as terminal, but its value is the EOS special placeholder.
                currentNode.terminal = true;
                currentNode.value = EOS_VALUE_PLACEHOLDER;
            }
        }
    }

    /**
     * Decodes an array of HPACK Huffman-encoded bytes into plaintext bytes.
     *
     * @param encodedBytes The byte array containing the HPACK Huffman-encoded data
     * @return A byte array containing the decoded plaintext data
     * @throws IOException If the input is malformed (e.g., invalid Huffman code, incorrect padding,
     *     or unexpected EOS symbol).
     */
    public static byte[] decode(byte[] encodedBytes) throws IOException {

        ByteArrayOutputStream decodedStream = new ByteArrayOutputStream();
        HuffmanNode currentNode = ROOT;
        int lastDecodedBitPosition = 0;

        for (int i = 0; i < encodedBytes.length; i++) {
            byte currentByte = encodedBytes[i];

            // Process each bit in the current byte, from MSB to LSB
            for (int bitIndex = 7; bitIndex >= 0; bitIndex--) {
                // Get the current bit (0 or 1)
                int bit = (currentByte >> bitIndex) & 1;

                // Traverse the Huffman tree
                if (bit == 0) {
                    if (currentNode.left == null) {
                        throw new IOException("Malformed Huffman data: Invalid bit sequence (0)");
                    }
                    currentNode = currentNode.left;
                } else { // bit == 1
                    if (currentNode.right == null) {
                        throw new IOException("Malformed Huffman data: Invalid bit sequence (1)");
                    }
                    currentNode = currentNode.right;
                }

                // If we reached a terminal node
                if (currentNode.terminal) {
                    // RFC 7541, Section 5.2: "A Huffman-encoded string literal containing the EOS
                    // symbol MUST be treated as a decoding error."
                    if (currentNode.value != -1 && currentNode.value == EOS_VALUE_PLACEHOLDER) {
                        throw new IOException(
                                "Decoding error: EOS symbol found within string literal.");
                    }

                    // If it's a regular character, write it to the output stream
                    if (currentNode.value == -1) {
                        // Should not happen if buildHuffmanTree is correct and marks all terminals
                        throw new IOException("Malformed Huffman data: Terminal node has no value");
                    }
                    decodedStream.write((byte) currentNode.value);
                    currentNode = ROOT; // Reset to root for the next character

                    lastDecodedBitPosition = (i * 8) + (7 - bitIndex) + 1;
                }
            }
        }

        // After processing all bytes, perform padding validation.
        // The RFC states: "Upon decoding, an incomplete code at the end of the encoded data is to
        // be considered as padding and discarded."
        // AND "A padding not corresponding to the most significant bits of the code for the EOS
        // symbol MUST be treated as a decoding error."
        // Since the EOS symbol is all '1's, this means any padding must consist solely of '1's.
        int totalInputBits = encodedBytes.length * 8;
        int actualPaddingBitsCount = totalInputBits - lastDecodedBitPosition;
        if (actualPaddingBitsCount > 0) {
            // RFC 7541, Section 5.2: "A padding strictly longer than 7 bits MUST be treated as a
            // decoding error."
            if (actualPaddingBitsCount > 7) {
                throw new IOException(
                        "Malformed Huffman data: Padding strictly longer than 7 bits.");
            }

            // The padding bits are the actualPaddingBitsCount least significant bits of the last
            // byte.
            byte lastByte = encodedBytes[encodedBytes.length - 1];

            // Create a mask for actualPaddingBitsCount ones at the LSB.
            int expectedPaddingMask = (1 << actualPaddingBitsCount) - 1;

            // Extract the actual padding bits from the last byte.
            int actualPaddingValue = lastByte & expectedPaddingMask;

            if (actualPaddingValue != expectedPaddingMask) {
                throw new IOException("Malformed Huffman data: Invalid padding (not all 1s).");
            }
        }

        return decodedStream.toByteArray();
    }

    /**
     * Encodes an array of plaintext bytes into an HPACK Huffman-encoded byte array.
     *
     * @param plaintextBytes The byte array containing the plaintext data to encode.
     * @return A byte array containing the HPACK Huffman-encoded data.
     * @throws IllegalStateException If a character in the plaintext is not found in the Huffman table.
     */
    public static byte[] encode(byte[] plaintextBytes) {

        // Use a BitBuffer to accumulate bits efficiently
        BitBuffer bitBuffer = new BitBuffer();

        for (byte b : plaintextBytes) {
            short charValue = (short) (b & 0xFF); // Convert byte to unsigned short
            HuffmanCodeInfo codeInfo = HPACK_HUFFMAN_CODES.get(charValue);
            if (codeInfo == null) {
                // All 256 bytes are represented above
                throw new IllegalStateException(
                        "Character '"
                                + (char) b
                                + "' (byte value: "
                                + charValue
                                + ") not found in Huffman encoding table.");
            }
            bitBuffer.appendBits(codeInfo.bits, codeInfo.numBits);
        }

        // BitBuffer.toByteArray will pad any remaining bits with 1
        return bitBuffer.toByteArray();
    }

    /**
     * Helper class to build a sequence of bits and convert them to a byte array.
     */
    private static class BitBuffer {

        private final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        private int currentByte = 0;
        private int bitsInCurrentByte = 0;

        /**
         * Appends a given number of bits from an integer value to the buffer. Bits are appended
         * from MSB to LSB of the input value.
         *
         * @param value the integer containing the bits to append
         * @param numBits the number of bits to append from the value
         */
        public void appendBits(int value, int numBits) {
            for (int i = numBits - 1; i >= 0; i--) {
                int bit = (value >> i) & 1;
                appendBit(bit);
            }
        }

        /**
         * Appends a single bit (0 or 1) to the buffer.
         *
         * @param bit The bit to append (0 or 1).
         */
        private void appendBit(int bit) {
            currentByte = (currentByte << 1) | bit;
            bitsInCurrentByte++;

            if (bitsInCurrentByte == 8) {
                byteStream.write((byte) currentByte);
                currentByte = 0;
                bitsInCurrentByte = 0;
            }
        }

        /**
         * Converts the accumulated bits into a byte array. Pads the last byte with 1s if it's not a
         * full byte.
         *
         * @return the byte array representation of the bits
         */
        public byte[] toByteArray() {
            if (bitsInCurrentByte > 0) {
                // Pad the remaining bits with 1s to fill the last byte
                currentByte =
                        (currentByte << (8 - bitsInCurrentByte))
                                | ((1 << (8 - bitsInCurrentByte)) - 1);
                byteStream.write((byte) currentByte);
            }
            return byteStream.toByteArray();
        }
    }

    // --- Main method for demonstration ---
    // TODO move to test
    public static void main(String[] args) {
        // Example 1: "abc"
        // Manual encoding of "abc" using the subset codes:
        // 'a': |00011
        // 'b': |100011
        // 'c': |00100
        // Resulting bits: |00011100|01100100| (no padding necessary)
        // Bytes: 0x1c 0x64
        byte[] encodedBytes1 = new byte[] {(byte) 0x1c, (byte) 0x64}; // Encoded "abc"

        System.out.println("--- Test Case 1: Encode 'abc' ---");
        try {
            byte[] plaintext = new byte[] {(byte) 'a', (byte) 'b', (byte) 'c'};
            byte[] testEncode = Huffman.encode(plaintext);
            System.out.println("Expected bytes: " + bytesToHex(encodedBytes1));
            System.out.println("Encoded bytes: " + bytesToHex(testEncode));
            System.out.println("Match: " + equals(testEncode, encodedBytes1));
        } catch (Exception e) {
            System.err.println("Encoding failed: " + e.getMessage());
        }

        System.out.println("--- Test Case 1: Decode 'abc' ---");
        try {
            byte[] decodedBytes1 = Huffman.decode(encodedBytes1);
            String decodedString1 =
                    new String(decodedBytes1, java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("Encoded bytes: " + bytesToHex(encodedBytes1));
            System.out.println("Decoded String: " + decodedString1);
            System.out.println("Expected String: abc");
            System.out.println("Match: " + decodedString1.equals("abc"));
        } catch (IOException e) {
            System.err.println("Decoding failed: " + e.getMessage());
        }
        System.out.println("\n");

        // Example 2: "Hello, world!"
        // 'H': |1100011
        // 'e': |00101
        // 'l': |101000
        // 'l': |101000
        // 'o': |00111
        // ',': |11111010
        // ' ': |010100
        // 'w': |1111000
        // 'o': |00111
        // 'r': |101100
        // 'l': |101000
        // 'd': |100100
        // '!': |11111110|00
        // Resulting bits:
        //  H      e     l      l      o    ,        _      w       o    r      l      d      !
        //     pad
        // |11000110|01011010|00101000|00111111|11010010|10011110|00001111|01100101|00010010|01111111|00011111
        // Bytes: 0xc6 0x5a 0x28 0x3f 0xd2 0x9e 0x0f 0x65 0x12 0x7f 0x1f
        byte[] encodedBytes2 =
                new byte[] {
                    (byte) 0xc6,
                    (byte) 0x5a,
                    (byte) 0x28,
                    (byte) 0x3f,
                    (byte) 0xd2,
                    (byte) 0x9e,
                    (byte) 0x0f,
                    (byte) 0x65,
                    (byte) 0x12,
                    (byte) 0x7f,
                    (byte) 0x1f
                };

        System.out.println("--- Test Case 2: Encode 'Hello, world!' ---");
        try {
            byte[] plaintext =
                    new byte[] {
                        (byte) 'H',
                        (byte) 'e',
                        (byte) 'l',
                        (byte) 'l',
                        (byte) 'o',
                        (byte) ',',
                        (byte) ' ',
                        (byte) 'w',
                        (byte) 'o',
                        (byte) 'r',
                        (byte) 'l',
                        (byte) 'd',
                        (byte) '!'
                    };
            byte[] testEncode = Huffman.encode(plaintext);
            System.out.println("Expected bytes: " + bytesToHex(encodedBytes2));
            System.out.println("Encoded bytes: " + bytesToHex(testEncode));
            System.out.println("Match: " + equals(testEncode, encodedBytes2));
        } catch (Exception e) {
            System.err.println("Encoding failed: " + e.getMessage());
        }

        System.out.println("--- Test Case 2: Decoding 'Hello, world!' ---");
        try {
            byte[] decodedBytes2 = Huffman.decode(encodedBytes2);
            String decodedString2 =
                    new String(decodedBytes2, java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("Encoded bytes: " + bytesToHex(encodedBytes2));
            System.out.println("Decoded String: " + decodedString2);
            System.out.println("Expected String: Hello, world!");
            System.out.println("Match: " + decodedString2.equals("Hello, world!"));
        } catch (IOException e) {
            System.err.println("Decoding failed: " + e.getMessage());
        }
        System.out.println("\n");

        // Example 3: Malformed input
        byte[] encodedBytes3 = new byte[] {(byte) 0x06};
        System.out.println("--- Test Case 3: Malformed Input: Invalid bit sequence ---");
        try {
            byte[] b = Huffman.decode(encodedBytes3);
            System.out.println(
                    "Failed: decoded " + bytesToHex(b) + " from " + bytesToHex(encodedBytes3));
        } catch (IOException e) {
            System.err.println("Decoding failed as expected: " + e.getMessage());
        }
        System.out.println("\n");

        // Example 4: Malformed input (EOS present)
        // If we see the full EOS (30 1s), this is a decoder error according
        // to RFC 7541 Section 5.2
        // 'a': |00011
        // 'b': |100011
        // EOS: |11111111|11111111|11111111|111111
        // Resulting bits: |00011100|01111111|11111111|11111111|11111111|11111111 (padded last 7
        // bits)
        // Bytes: 0x1c 0x7f 0xff 0xff 0xff 0xff
        System.out.println("--- Test Case 4: Malformed Input (EOS in literal) ---");
        byte[] encodedBytes4 =
                new byte[] {
                    (byte) 0x1c, (byte) 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
                };
        try {
            Huffman.decode(encodedBytes4);
        } catch (IOException e) {
            System.err.println("Decoding failed as expected: " + e.getMessage());
        }
        System.out.println("\n");

        // Example 5: Empty input
        System.out.println("--- Test Case 5: Empty Input ---");
        byte[] emptyBytes = new byte[] {};
        try {
            byte[] decodedEmpty = Huffman.decode(emptyBytes);
            System.out.println("Decoded empty string: " + new String(decodedEmpty));
            System.out.println("Match: " + (decodedEmpty.length == 0));
        } catch (IOException e) {
            System.err.println("Decoding failed: " + e.getMessage());
        }
    }

    private static boolean equals(byte[] b1, byte[] b2) {
        if (b1.length != b2.length) {
            return false;
        }
        for (int i = 0; i < b1.length; i++) {
            if (b1[i] != b2[i]) {
                return false;
            }
        }
        return true;
    }

    /** Helper method to convert byte array to hex string for display. */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
