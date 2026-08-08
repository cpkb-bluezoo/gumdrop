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

import java.io.IOException;
import java.util.Arrays;

/**
 * Huffman decoder and encoder for HPACK string literals
 * (RFC 7541 section 5.2).
 *
 * <p>Uses the static Huffman code defined in RFC 7541 Appendix B.
 * Builds an in-memory decoding trie from the predefined codes.
 *
 * <p>Both the decoding trie and the encoding table are backed by
 * primitive arrays rather than boxed objects/maps: {@link #decode}
 * walks a flat {@code int[]}-indexed trie instead of chasing {@code
 * HuffmanNode} object pointers and writing each decoded byte through a
 * {@code synchronized ByteArrayOutputStream.write(int)} call, and
 * {@link #encode} looks a byte's code up via a direct array index
 * ({@code CODE_BITS[value]}/{@code CODE_LENGTH[value]}) instead of a
 * {@code Map<Short, ...>} lookup that autoboxes every input byte. This
 * is the per-header CPU/allocation cost of every HTTP/2 request and
 * response, so both paths avoid boxing and per-symbol object
 * allocation on the hot path.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7541#appendix-B">RFC 7541 Appendix B</a>
 */
public class Huffman {

    // Index used for the EOS pseudo-symbol in the CODE_BITS/CODE_LENGTH
    // tables and as the decoded "value" placeholder - actual byte values
    // are 0-255, so 256 is unambiguous.
    private static final int EOS_INDEX = 0x100;

    // Huffman code (right-aligned) and bit length for each of the 256
    // possible byte values, plus EOS_INDEX. Parallel primitive arrays
    // instead of a Map<Short, HuffmanCodeInfo> so encode() never
    // autoboxes the input byte to look up its code.
    private static final int[] CODE_BITS = new int[EOS_INDEX + 1];
    private static final byte[] CODE_LENGTH = new byte[EOS_INDEX + 1];


    // Flat array-based trie for decoding: node 0 is the root. child index
    // -1 means "no such child". Backed by primitive arrays instead of
    // HuffmanNode objects so decode() walks array indices (cache-friendly,
    // no per-node object header/pointer-chasing) rather than heap
    // pointers.
    private static int[] leftChild = new int[512];
    private static int[] rightChild = new int[512];
    private static short[] nodeValue = new short[512];
    private static boolean[] nodeTerminal = new boolean[512];
    private static int nodeCount;

    private static final int ROOT = newNode();

    private static int newNode() {
        if (nodeCount == leftChild.length) {
            int newCap = leftChild.length * 2;
            leftChild = Arrays.copyOf(leftChild, newCap);
            rightChild = Arrays.copyOf(rightChild, newCap);
            nodeValue = Arrays.copyOf(nodeValue, newCap);
            nodeTerminal = Arrays.copyOf(nodeTerminal, newCap);
        }
        int id = nodeCount++;
        leftChild[id] = -1;
        rightChild[id] = -1;
        nodeValue[id] = -1;
        nodeTerminal[id] = false;
        return id;
    }

    /**
     * Builds the array-based Huffman decoding trie from CODE_BITS/
     * CODE_LENGTH.
     */
    private static void buildHuffmanTree() {
        for (int codeValue = 0; codeValue <= EOS_INDEX; codeValue++) {
            byte numBits = CODE_LENGTH[codeValue];
            if (numBits == 0) {
                continue; // not a real entry (shouldn't happen; every index 0-256 is populated)
            }
            int codeBits = CODE_BITS[codeValue];
            int currentNode = ROOT;

            // Iterate through bits from MSB to LSB of the Huffman code
            for (int i = 0; i < numBits; i++) {
                int bit = (codeBits >> (numBits - 1 - i)) & 1;
                if (bit == 0) {
                    if (leftChild[currentNode] == -1) {
                        // Not "leftChild[currentNode] = newNode()" in one
                        // statement: the array reference for the
                        // assignment target is captured before newNode()
                        // runs, so if newNode() grows the arrays (field
                        // reassigned to a new array), the write would land
                        // in the stale, discarded array instead of the new
                        // one referenced by the field afterward.
                        int child = newNode();
                        leftChild[currentNode] = child;
                    }
                    currentNode = leftChild[currentNode];
                } else {
                    if (rightChild[currentNode] == -1) {
                        int child = newNode();
                        rightChild[currentNode] = child;
                    }
                    currentNode = rightChild[currentNode];
                }
            }
            nodeTerminal[currentNode] = true;
            nodeValue[currentNode] = (short) codeValue;
        }
    }

    static {
        // Table from RFC 7541, Appendix B
        CODE_BITS[0] = 0x1ff8; CODE_LENGTH[0] = (byte) 13;
        CODE_BITS[1] = 0x7fffd8; CODE_LENGTH[1] = (byte) 23;
        CODE_BITS[2] = 0xfffffe2; CODE_LENGTH[2] = (byte) 28;
        CODE_BITS[3] = 0xfffffe3; CODE_LENGTH[3] = (byte) 28;
        CODE_BITS[4] = 0xfffffe4; CODE_LENGTH[4] = (byte) 28;
        CODE_BITS[5] = 0xfffffe5; CODE_LENGTH[5] = (byte) 28;
        CODE_BITS[6] = 0xfffffe6; CODE_LENGTH[6] = (byte) 28;
        CODE_BITS[7] = 0xfffffe7; CODE_LENGTH[7] = (byte) 28;
        CODE_BITS[8] = 0xfffffe8; CODE_LENGTH[8] = (byte) 28;
        CODE_BITS[9] = 0xffffea; CODE_LENGTH[9] = (byte) 24;
        CODE_BITS[10] = 0x3ffffffc; CODE_LENGTH[10] = (byte) 30;
        CODE_BITS[11] = 0xfffffe9; CODE_LENGTH[11] = (byte) 28;
        CODE_BITS[12] = 0xfffffea; CODE_LENGTH[12] = (byte) 28;
        CODE_BITS[13] = 0x3ffffffd; CODE_LENGTH[13] = (byte) 30;
        CODE_BITS[14] = 0xfffffeb; CODE_LENGTH[14] = (byte) 28;
        CODE_BITS[15] = 0xfffffec; CODE_LENGTH[15] = (byte) 28;
        CODE_BITS[16] = 0xfffffed; CODE_LENGTH[16] = (byte) 28;
        CODE_BITS[17] = 0xfffffee; CODE_LENGTH[17] = (byte) 28;
        CODE_BITS[18] = 0xfffffef; CODE_LENGTH[18] = (byte) 28;
        CODE_BITS[19] = 0xffffff0; CODE_LENGTH[19] = (byte) 28;
        CODE_BITS[20] = 0xffffff1; CODE_LENGTH[20] = (byte) 28;
        CODE_BITS[21] = 0xffffff2; CODE_LENGTH[21] = (byte) 28;
        CODE_BITS[22] = 0x3ffffffe; CODE_LENGTH[22] = (byte) 30;
        CODE_BITS[23] = 0xffffff3; CODE_LENGTH[23] = (byte) 28;
        CODE_BITS[24] = 0xffffff4; CODE_LENGTH[24] = (byte) 28;
        CODE_BITS[25] = 0xffffff5; CODE_LENGTH[25] = (byte) 28;
        CODE_BITS[26] = 0xffffff6; CODE_LENGTH[26] = (byte) 28;
        CODE_BITS[27] = 0xffffff7; CODE_LENGTH[27] = (byte) 28;
        CODE_BITS[28] = 0xffffff8; CODE_LENGTH[28] = (byte) 28;
        CODE_BITS[29] = 0xffffff9; CODE_LENGTH[29] = (byte) 28;
        CODE_BITS[30] = 0xffffffa; CODE_LENGTH[30] = (byte) 28;
        CODE_BITS[31] = 0xffffffb; CODE_LENGTH[31] = (byte) 28;
        CODE_BITS[32] = 0x14; CODE_LENGTH[32] = (byte) 6; // ' '
        CODE_BITS[33] = 0x3f8; CODE_LENGTH[33] = (byte) 10; // '!'
        CODE_BITS[34] = 0x3f9; CODE_LENGTH[34] = (byte) 10; // '"'
        CODE_BITS[35] = 0xffa; CODE_LENGTH[35] = (byte) 12; // '#'
        CODE_BITS[36] = 0x1ff9; CODE_LENGTH[36] = (byte) 13; // '$'
        CODE_BITS[37] = 0x15; CODE_LENGTH[37] = (byte) 6; // '%'
        CODE_BITS[38] = 0xf8; CODE_LENGTH[38] = (byte) 8; // '&'
        CODE_BITS[39] = 0x7fa; CODE_LENGTH[39] = (byte) 11; // '''
        CODE_BITS[40] = 0x3fa; CODE_LENGTH[40] = (byte) 10; // '('
        CODE_BITS[41] = 0x3fb; CODE_LENGTH[41] = (byte) 10; // ')'
        CODE_BITS[42] = 0xf9; CODE_LENGTH[42] = (byte) 8; // '*'
        CODE_BITS[43] = 0x7fb; CODE_LENGTH[43] = (byte) 11; // '+'
        CODE_BITS[44] = 0xfa; CODE_LENGTH[44] = (byte) 8; // ','
        CODE_BITS[45] = 0x16; CODE_LENGTH[45] = (byte) 6; // '-'
        CODE_BITS[46] = 0x17; CODE_LENGTH[46] = (byte) 6; // '.'
        CODE_BITS[47] = 0x18; CODE_LENGTH[47] = (byte) 6; // '/'
        CODE_BITS[48] = 0x0; CODE_LENGTH[48] = (byte) 5; // '0'
        CODE_BITS[49] = 0x1; CODE_LENGTH[49] = (byte) 5; // '1'
        CODE_BITS[50] = 0x2; CODE_LENGTH[50] = (byte) 5; // '2'
        CODE_BITS[51] = 0x19; CODE_LENGTH[51] = (byte) 6; // '3'
        CODE_BITS[52] = 0x1a; CODE_LENGTH[52] = (byte) 6; // '4'
        CODE_BITS[53] = 0x1b; CODE_LENGTH[53] = (byte) 6; // '5'
        CODE_BITS[54] = 0x1c; CODE_LENGTH[54] = (byte) 6; // '6'
        CODE_BITS[55] = 0x1d; CODE_LENGTH[55] = (byte) 6; // '7'
        CODE_BITS[56] = 0x1e; CODE_LENGTH[56] = (byte) 6; // '8'
        CODE_BITS[57] = 0x1f; CODE_LENGTH[57] = (byte) 6; // '9'
        CODE_BITS[58] = 0x5c; CODE_LENGTH[58] = (byte) 7; // ':'
        CODE_BITS[59] = 0xfb; CODE_LENGTH[59] = (byte) 8; // ';'
        CODE_BITS[60] = 0x7ffc; CODE_LENGTH[60] = (byte) 15; // '<'
        CODE_BITS[61] = 0x20; CODE_LENGTH[61] = (byte) 6; // '='
        CODE_BITS[62] = 0xffb; CODE_LENGTH[62] = (byte) 12; // '>'
        CODE_BITS[63] = 0x3fc; CODE_LENGTH[63] = (byte) 10; // '?'
        CODE_BITS[64] = 0x1ffa; CODE_LENGTH[64] = (byte) 13; // '@'
        CODE_BITS[65] = 0x21; CODE_LENGTH[65] = (byte) 6; // 'A'
        CODE_BITS[66] = 0x5d; CODE_LENGTH[66] = (byte) 7; // 'B'
        CODE_BITS[67] = 0x5e; CODE_LENGTH[67] = (byte) 7; // 'C'
        CODE_BITS[68] = 0x5f; CODE_LENGTH[68] = (byte) 7; // 'D'
        CODE_BITS[69] = 0x60; CODE_LENGTH[69] = (byte) 7; // 'E'
        CODE_BITS[70] = 0x61; CODE_LENGTH[70] = (byte) 7; // 'F'
        CODE_BITS[71] = 0x62; CODE_LENGTH[71] = (byte) 7; // 'G'
        CODE_BITS[72] = 0x63; CODE_LENGTH[72] = (byte) 7; // 'H'
        CODE_BITS[73] = 0x64; CODE_LENGTH[73] = (byte) 7; // 'I'
        CODE_BITS[74] = 0x65; CODE_LENGTH[74] = (byte) 7; // 'J'
        CODE_BITS[75] = 0x66; CODE_LENGTH[75] = (byte) 7; // 'K'
        CODE_BITS[76] = 0x67; CODE_LENGTH[76] = (byte) 7; // 'L'
        CODE_BITS[77] = 0x68; CODE_LENGTH[77] = (byte) 7; // 'M'
        CODE_BITS[78] = 0x69; CODE_LENGTH[78] = (byte) 7; // 'N'
        CODE_BITS[79] = 0x6a; CODE_LENGTH[79] = (byte) 7; // 'O'
        CODE_BITS[80] = 0x6b; CODE_LENGTH[80] = (byte) 7; // 'P'
        CODE_BITS[81] = 0x6c; CODE_LENGTH[81] = (byte) 7; // 'Q'
        CODE_BITS[82] = 0x6d; CODE_LENGTH[82] = (byte) 7; // 'R'
        CODE_BITS[83] = 0x6e; CODE_LENGTH[83] = (byte) 7; // 'S'
        CODE_BITS[84] = 0x6f; CODE_LENGTH[84] = (byte) 7; // 'T'
        CODE_BITS[85] = 0x70; CODE_LENGTH[85] = (byte) 7; // 'U'
        CODE_BITS[86] = 0x71; CODE_LENGTH[86] = (byte) 7; // 'V'
        CODE_BITS[87] = 0x72; CODE_LENGTH[87] = (byte) 7; // 'W'
        CODE_BITS[88] = 0xfc; CODE_LENGTH[88] = (byte) 8; // 'X'
        CODE_BITS[89] = 0x73; CODE_LENGTH[89] = (byte) 7; // 'Y'
        CODE_BITS[90] = 0xfd; CODE_LENGTH[90] = (byte) 8; // 'Z'
        CODE_BITS[91] = 0x1ffb; CODE_LENGTH[91] = (byte) 13; // '['
        CODE_BITS[92] = 0x7fff0; CODE_LENGTH[92] = (byte) 19; // '\'
        CODE_BITS[93] = 0x1ffc; CODE_LENGTH[93] = (byte) 13; // ']'
        CODE_BITS[94] = 0x3ffc; CODE_LENGTH[94] = (byte) 14; // '^'
        CODE_BITS[95] = 0x22; CODE_LENGTH[95] = (byte) 6; // '_'
        CODE_BITS[96] = 0x7ffd; CODE_LENGTH[96] = (byte) 15; // '`'
        CODE_BITS[97] = 0x3; CODE_LENGTH[97] = (byte) 5; // 'a'
        CODE_BITS[98] = 0x23; CODE_LENGTH[98] = (byte) 6; // 'b'
        CODE_BITS[99] = 0x4; CODE_LENGTH[99] = (byte) 5; // 'c'
        CODE_BITS[100] = 0x24; CODE_LENGTH[100] = (byte) 6; // 'd'
        CODE_BITS[101] = 0x5; CODE_LENGTH[101] = (byte) 5; // 'e'
        CODE_BITS[102] = 0x25; CODE_LENGTH[102] = (byte) 6; // 'f'
        CODE_BITS[103] = 0x26; CODE_LENGTH[103] = (byte) 6; // 'g'
        CODE_BITS[104] = 0x27; CODE_LENGTH[104] = (byte) 6; // 'h'
        CODE_BITS[105] = 0x6; CODE_LENGTH[105] = (byte) 5; // 'i'
        CODE_BITS[106] = 0x74; CODE_LENGTH[106] = (byte) 7; // 'j'
        CODE_BITS[107] = 0x75; CODE_LENGTH[107] = (byte) 7; // 'k'
        CODE_BITS[108] = 0x28; CODE_LENGTH[108] = (byte) 6; // 'l'
        CODE_BITS[109] = 0x29; CODE_LENGTH[109] = (byte) 6; // 'm'
        CODE_BITS[110] = 0x2a; CODE_LENGTH[110] = (byte) 6; // 'n'
        CODE_BITS[111] = 0x7; CODE_LENGTH[111] = (byte) 5; // 'o'
        CODE_BITS[112] = 0x2b; CODE_LENGTH[112] = (byte) 6; // 'p'
        CODE_BITS[113] = 0x76; CODE_LENGTH[113] = (byte) 7; // 'q'
        CODE_BITS[114] = 0x2c; CODE_LENGTH[114] = (byte) 6; // 'r'
        CODE_BITS[115] = 0x8; CODE_LENGTH[115] = (byte) 5; // 's'
        CODE_BITS[116] = 0x9; CODE_LENGTH[116] = (byte) 5; // 't'
        CODE_BITS[117] = 0x2d; CODE_LENGTH[117] = (byte) 6; // 'u'
        CODE_BITS[118] = 0x77; CODE_LENGTH[118] = (byte) 7; // 'v'
        CODE_BITS[119] = 0x78; CODE_LENGTH[119] = (byte) 7; // 'w'
        CODE_BITS[120] = 0x79; CODE_LENGTH[120] = (byte) 7; // 'x'
        CODE_BITS[121] = 0x7a; CODE_LENGTH[121] = (byte) 7; // 'y'
        CODE_BITS[122] = 0x7b; CODE_LENGTH[122] = (byte) 7; // 'z'
        CODE_BITS[123] = 0x7ffe; CODE_LENGTH[123] = (byte) 15; // '{'
        CODE_BITS[124] = 0x7fc; CODE_LENGTH[124] = (byte) 11; // '|'
        CODE_BITS[125] = 0x3ffd; CODE_LENGTH[125] = (byte) 14; // '}'
        CODE_BITS[126] = 0x1ffd; CODE_LENGTH[126] = (byte) 13; // '~'
        CODE_BITS[127] = 0xffffffc; CODE_LENGTH[127] = (byte) 28;
        CODE_BITS[128] = 0xfffe6; CODE_LENGTH[128] = (byte) 20;
        CODE_BITS[129] = 0x3fffd2; CODE_LENGTH[129] = (byte) 22;
        CODE_BITS[130] = 0xfffe7; CODE_LENGTH[130] = (byte) 20;
        CODE_BITS[131] = 0xfffe8; CODE_LENGTH[131] = (byte) 20;
        CODE_BITS[132] = 0x3fffd3; CODE_LENGTH[132] = (byte) 22;
        CODE_BITS[133] = 0x3fffd4; CODE_LENGTH[133] = (byte) 22;
        CODE_BITS[134] = 0x3fffd5; CODE_LENGTH[134] = (byte) 22;
        CODE_BITS[135] = 0x7fffd9; CODE_LENGTH[135] = (byte) 23;
        CODE_BITS[136] = 0x3fffd6; CODE_LENGTH[136] = (byte) 22;
        CODE_BITS[137] = 0x7fffda; CODE_LENGTH[137] = (byte) 23;
        CODE_BITS[138] = 0x7fffdb; CODE_LENGTH[138] = (byte) 23;
        CODE_BITS[139] = 0x7fffdc; CODE_LENGTH[139] = (byte) 23;
        CODE_BITS[140] = 0x7fffdd; CODE_LENGTH[140] = (byte) 23;
        CODE_BITS[141] = 0x7fffde; CODE_LENGTH[141] = (byte) 23;
        CODE_BITS[142] = 0xffffeb; CODE_LENGTH[142] = (byte) 24;
        CODE_BITS[143] = 0x7fffdf; CODE_LENGTH[143] = (byte) 23;
        CODE_BITS[144] = 0xffffec; CODE_LENGTH[144] = (byte) 24;
        CODE_BITS[145] = 0xffffed; CODE_LENGTH[145] = (byte) 24;
        CODE_BITS[146] = 0x3fffd7; CODE_LENGTH[146] = (byte) 22;
        CODE_BITS[147] = 0x7fffe0; CODE_LENGTH[147] = (byte) 23;
        CODE_BITS[148] = 0xffffee; CODE_LENGTH[148] = (byte) 24;
        CODE_BITS[149] = 0x7fffe1; CODE_LENGTH[149] = (byte) 23;
        CODE_BITS[150] = 0x7fffe2; CODE_LENGTH[150] = (byte) 23;
        CODE_BITS[151] = 0x7fffe3; CODE_LENGTH[151] = (byte) 23;
        CODE_BITS[152] = 0x7fffe4; CODE_LENGTH[152] = (byte) 23;
        CODE_BITS[153] = 0x1fffdc; CODE_LENGTH[153] = (byte) 21;
        CODE_BITS[154] = 0x3fffd8; CODE_LENGTH[154] = (byte) 22;
        CODE_BITS[155] = 0x7fffe5; CODE_LENGTH[155] = (byte) 23;
        CODE_BITS[156] = 0x3fffd9; CODE_LENGTH[156] = (byte) 22;
        CODE_BITS[157] = 0x7fffe6; CODE_LENGTH[157] = (byte) 23;
        CODE_BITS[158] = 0x7fffe7; CODE_LENGTH[158] = (byte) 23;
        CODE_BITS[159] = 0xffffef; CODE_LENGTH[159] = (byte) 24;
        CODE_BITS[160] = 0x3fffda; CODE_LENGTH[160] = (byte) 22;
        CODE_BITS[161] = 0x1fffdd; CODE_LENGTH[161] = (byte) 21;
        CODE_BITS[162] = 0xfffe9; CODE_LENGTH[162] = (byte) 20;
        CODE_BITS[163] = 0x3fffdb; CODE_LENGTH[163] = (byte) 22;
        CODE_BITS[164] = 0x3fffdc; CODE_LENGTH[164] = (byte) 22;
        CODE_BITS[165] = 0x7fffe8; CODE_LENGTH[165] = (byte) 23;
        CODE_BITS[166] = 0x7fffe9; CODE_LENGTH[166] = (byte) 23;
        CODE_BITS[167] = 0x1fffde; CODE_LENGTH[167] = (byte) 21;
        CODE_BITS[168] = 0x7fffea; CODE_LENGTH[168] = (byte) 23;
        CODE_BITS[169] = 0x3fffdd; CODE_LENGTH[169] = (byte) 22;
        CODE_BITS[170] = 0x3fffde; CODE_LENGTH[170] = (byte) 22;
        CODE_BITS[171] = 0xfffff0; CODE_LENGTH[171] = (byte) 24;
        CODE_BITS[172] = 0x1fffdf; CODE_LENGTH[172] = (byte) 21;
        CODE_BITS[173] = 0x3fffdf; CODE_LENGTH[173] = (byte) 22;
        CODE_BITS[174] = 0x7fffeb; CODE_LENGTH[174] = (byte) 23;
        CODE_BITS[175] = 0x7fffec; CODE_LENGTH[175] = (byte) 23;
        CODE_BITS[176] = 0x1fffe0; CODE_LENGTH[176] = (byte) 21;
        CODE_BITS[177] = 0x1fffe1; CODE_LENGTH[177] = (byte) 21;
        CODE_BITS[178] = 0x3fffe0; CODE_LENGTH[178] = (byte) 22;
        CODE_BITS[179] = 0x1fffe2; CODE_LENGTH[179] = (byte) 21;
        CODE_BITS[180] = 0x7fffed; CODE_LENGTH[180] = (byte) 23;
        CODE_BITS[181] = 0x3fffe1; CODE_LENGTH[181] = (byte) 22;
        CODE_BITS[182] = 0x7fffee; CODE_LENGTH[182] = (byte) 23;
        CODE_BITS[183] = 0x7fffef; CODE_LENGTH[183] = (byte) 23;
        CODE_BITS[184] = 0xfffea; CODE_LENGTH[184] = (byte) 20;
        CODE_BITS[185] = 0x3fffe2; CODE_LENGTH[185] = (byte) 22;
        CODE_BITS[186] = 0x3fffe3; CODE_LENGTH[186] = (byte) 22;
        CODE_BITS[187] = 0x3fffe4; CODE_LENGTH[187] = (byte) 22;
        CODE_BITS[188] = 0x7ffff0; CODE_LENGTH[188] = (byte) 23;
        CODE_BITS[189] = 0x3fffe5; CODE_LENGTH[189] = (byte) 22;
        CODE_BITS[190] = 0x3fffe6; CODE_LENGTH[190] = (byte) 22;
        CODE_BITS[191] = 0x7ffff1; CODE_LENGTH[191] = (byte) 23;
        CODE_BITS[192] = 0x3ffffe0; CODE_LENGTH[192] = (byte) 26;
        CODE_BITS[193] = 0x3ffffe1; CODE_LENGTH[193] = (byte) 26;
        CODE_BITS[194] = 0xfffeb; CODE_LENGTH[194] = (byte) 20;
        CODE_BITS[195] = 0x7fff1; CODE_LENGTH[195] = (byte) 19;
        CODE_BITS[196] = 0x3fffe7; CODE_LENGTH[196] = (byte) 22;
        CODE_BITS[197] = 0x7ffff2; CODE_LENGTH[197] = (byte) 23;
        CODE_BITS[198] = 0x3fffe8; CODE_LENGTH[198] = (byte) 22;
        CODE_BITS[199] = 0x1ffffec; CODE_LENGTH[199] = (byte) 25;
        CODE_BITS[200] = 0x3ffffe2; CODE_LENGTH[200] = (byte) 26;
        CODE_BITS[201] = 0x3ffffe3; CODE_LENGTH[201] = (byte) 26;
        CODE_BITS[202] = 0x3ffffe4; CODE_LENGTH[202] = (byte) 26;
        CODE_BITS[203] = 0x7ffffde; CODE_LENGTH[203] = (byte) 27;
        CODE_BITS[204] = 0x7ffffdf; CODE_LENGTH[204] = (byte) 27;
        CODE_BITS[205] = 0x3ffffe5; CODE_LENGTH[205] = (byte) 26;
        CODE_BITS[206] = 0xfffff1; CODE_LENGTH[206] = (byte) 24;
        CODE_BITS[207] = 0x1ffffed; CODE_LENGTH[207] = (byte) 25;
        CODE_BITS[208] = 0x7fff2; CODE_LENGTH[208] = (byte) 19;
        CODE_BITS[209] = 0x1fffe3; CODE_LENGTH[209] = (byte) 21;
        CODE_BITS[210] = 0x3ffffe6; CODE_LENGTH[210] = (byte) 26;
        CODE_BITS[211] = 0x7ffffe0; CODE_LENGTH[211] = (byte) 27;
        CODE_BITS[212] = 0x7ffffe1; CODE_LENGTH[212] = (byte) 27;
        CODE_BITS[213] = 0x3ffffe7; CODE_LENGTH[213] = (byte) 26;
        CODE_BITS[214] = 0x7ffffe2; CODE_LENGTH[214] = (byte) 27;
        CODE_BITS[215] = 0xfffff2; CODE_LENGTH[215] = (byte) 24;
        CODE_BITS[216] = 0x1fffe4; CODE_LENGTH[216] = (byte) 21;
        CODE_BITS[217] = 0x1fffe5; CODE_LENGTH[217] = (byte) 21;
        CODE_BITS[218] = 0x3ffffe8; CODE_LENGTH[218] = (byte) 26;
        CODE_BITS[219] = 0x3ffffe9; CODE_LENGTH[219] = (byte) 26;
        CODE_BITS[220] = 0xffffffd; CODE_LENGTH[220] = (byte) 28;
        CODE_BITS[221] = 0x7ffffe3; CODE_LENGTH[221] = (byte) 27;
        CODE_BITS[222] = 0x7ffffe4; CODE_LENGTH[222] = (byte) 27;
        CODE_BITS[223] = 0x7ffffe5; CODE_LENGTH[223] = (byte) 27;
        CODE_BITS[224] = 0xfffec; CODE_LENGTH[224] = (byte) 20;
        CODE_BITS[225] = 0xfffff3; CODE_LENGTH[225] = (byte) 24;
        CODE_BITS[226] = 0xfffed; CODE_LENGTH[226] = (byte) 20;
        CODE_BITS[227] = 0x1fffe6; CODE_LENGTH[227] = (byte) 21;
        CODE_BITS[228] = 0x3fffe9; CODE_LENGTH[228] = (byte) 22;
        CODE_BITS[229] = 0x1fffe7; CODE_LENGTH[229] = (byte) 21;
        CODE_BITS[230] = 0x1fffe8; CODE_LENGTH[230] = (byte) 21;
        CODE_BITS[231] = 0x7ffff3; CODE_LENGTH[231] = (byte) 23;
        CODE_BITS[232] = 0x3fffea; CODE_LENGTH[232] = (byte) 22;
        CODE_BITS[233] = 0x3fffeb; CODE_LENGTH[233] = (byte) 22;
        CODE_BITS[234] = 0x1ffffee; CODE_LENGTH[234] = (byte) 25;
        CODE_BITS[235] = 0x1ffffef; CODE_LENGTH[235] = (byte) 25;
        CODE_BITS[236] = 0xfffff4; CODE_LENGTH[236] = (byte) 24;
        CODE_BITS[237] = 0xfffff5; CODE_LENGTH[237] = (byte) 24;
        CODE_BITS[238] = 0x3ffffea; CODE_LENGTH[238] = (byte) 26;
        CODE_BITS[239] = 0x7ffff4; CODE_LENGTH[239] = (byte) 23;
        CODE_BITS[240] = 0x3ffffeb; CODE_LENGTH[240] = (byte) 26;
        CODE_BITS[241] = 0x7ffffe6; CODE_LENGTH[241] = (byte) 27;
        CODE_BITS[242] = 0x3ffffec; CODE_LENGTH[242] = (byte) 26;
        CODE_BITS[243] = 0x3ffffed; CODE_LENGTH[243] = (byte) 26;
        CODE_BITS[244] = 0x7ffffe7; CODE_LENGTH[244] = (byte) 27;
        CODE_BITS[245] = 0x7ffffe8; CODE_LENGTH[245] = (byte) 27;
        CODE_BITS[246] = 0x7ffffe9; CODE_LENGTH[246] = (byte) 27;
        CODE_BITS[247] = 0x7ffffea; CODE_LENGTH[247] = (byte) 27;
        CODE_BITS[248] = 0x7ffffeb; CODE_LENGTH[248] = (byte) 27;
        CODE_BITS[249] = 0xffffffe; CODE_LENGTH[249] = (byte) 28;
        CODE_BITS[250] = 0x7ffffec; CODE_LENGTH[250] = (byte) 27;
        CODE_BITS[251] = 0x7ffffed; CODE_LENGTH[251] = (byte) 27;
        CODE_BITS[252] = 0x7ffffee; CODE_LENGTH[252] = (byte) 27;
        CODE_BITS[253] = 0x7ffffef; CODE_LENGTH[253] = (byte) 27;
        CODE_BITS[254] = 0x7fffff0; CODE_LENGTH[254] = (byte) 27;
        CODE_BITS[255] = 0x3ffffee; CODE_LENGTH[255] = (byte) 26;

        // The special EOS symbol (End-of-String) is represented by 31 ones.
        // This is a sentinel value, not a decodable character.
        CODE_BITS[EOS_INDEX] = 0x3fffffff; CODE_LENGTH[EOS_INDEX] = (byte) 30;

        buildHuffmanTree();
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

        // Decoded output can never be longer than the input (the shortest
        // HPACK code is 5 bits, i.e. <= 1.6 output bytes per input byte at
        // best, so a same-size buffer, grown if ever needed, avoids
        // reallocating for the common case).
        byte[] out = new byte[Math.max(16, encodedBytes.length)];
        int outLen = 0;
        int currentNode = ROOT;
        int lastDecodedBitPosition = 0;

        for (int i = 0; i < encodedBytes.length; i++) {
            int currentByte = encodedBytes[i];

            // Process each bit in the current byte, from MSB to LSB
            for (int bitIndex = 7; bitIndex >= 0; bitIndex--) {
                int bit = (currentByte >> bitIndex) & 1;

                currentNode = (bit == 0) ? leftChild[currentNode] : rightChild[currentNode];
                if (currentNode == -1) {
                    throw new IOException("Malformed Huffman data: Invalid bit sequence (" + bit + ")");
                }

                if (nodeTerminal[currentNode]) {
                    short value = nodeValue[currentNode];
                    // RFC 7541, Section 5.2: "A Huffman-encoded string literal containing the EOS
                    // symbol MUST be treated as a decoding error."
                    if (value == EOS_INDEX) {
                        throw new IOException(
                                "Decoding error: EOS symbol found within string literal.");
                    }

                    if (outLen == out.length) {
                        out = Arrays.copyOf(out, out.length * 2);
                    }
                    out[outLen++] = (byte) value;
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

        return Arrays.copyOf(out, outLen);
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
            int charValue = b & 0xFF; // Convert byte to unsigned int index; no boxing
            byte numBits = CODE_LENGTH[charValue];
            bitBuffer.appendBits(CODE_BITS[charValue], numBits);
        }

        // BitBuffer.toByteArray will pad any remaining bits with 1
        return bitBuffer.toByteArray();
    }

    /**
     * Helper class to build a sequence of bits and convert them to a byte array.
     */
    private static class BitBuffer {

        private byte[] bytes = new byte[64];
        private int byteLen = 0;
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
                append((byte) currentByte);
                currentByte = 0;
                bitsInCurrentByte = 0;
            }
        }

        private void append(byte b) {
            if (byteLen == bytes.length) {
                bytes = Arrays.copyOf(bytes, bytes.length * 2);
            }
            bytes[byteLen++] = b;
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
                append((byte) currentByte);
            }
            return Arrays.copyOf(bytes, byteLen);
        }
    }

}
