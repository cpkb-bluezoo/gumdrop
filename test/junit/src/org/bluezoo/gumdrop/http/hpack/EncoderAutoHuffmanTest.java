/*
 * EncoderAutoHuffmanTest.java
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

import org.bluezoo.gumdrop.http.Header;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Regression test for issue #138: {@code Encoder.encode} used to always
 * compute {@code Huffman.encode(...)} for both header name and value, even
 * when {@code autoHuffman} was false, discarding the result. Verify
 * {@code setAutoHuffman(false)} actually suppresses Huffman coding (the
 * 'H' bit stays clear even for a highly compressible value) and that a
 * round trip through {@link Decoder} still recovers the original headers.
 */
public class EncoderAutoHuffmanTest {

    // Highly repetitive text: would always win under Huffman coding, so if
    // the 'H' bit ends up set here, autoHuffman(false) was not honored.
    private static final String COMPRESSIBLE_VALUE =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    public void testAutoHuffmanFalseDisablesHuffmanCoding() throws Exception {
        Encoder encoder = new Encoder(4096, Integer.MAX_VALUE);
        encoder.setAutoHuffman(false);

        List<Header> headers = new ArrayList<>();
        headers.add(new Header("x-custom-unindexed-name", COMPRESSIBLE_VALUE));

        ByteBuffer buf = ByteBuffer.allocate(4096);
        encoder.encode(buf, headers);
        buf.flip();

        byte[] wire = new byte[buf.remaining()];
        buf.get(wire);

        // Literal header field with new name (0000 000x prefix): opcode
        // byte, then name-length byte whose top ('H') bit must be clear.
        // Name is not Huffman-coded, so its raw length is used directly.
        int nameLengthByteIndex = 1;
        int nameLength = "x-custom-unindexed-name".length();
        assertEquals("name length prefix must fit in one byte for this test",
                nameLength, wire[nameLengthByteIndex] & 0x7F);
        assertEquals("name must not be Huffman-coded when autoHuffman is false",
                0, wire[nameLengthByteIndex] & 0x80);

        int valueLengthByteIndex = nameLengthByteIndex + 1 + nameLength;
        assertEquals("value must not be Huffman-coded when autoHuffman is false",
                0, wire[valueLengthByteIndex] & 0x80);
    }

    @Test
    public void testAutoHuffmanFalseRoundTripsCorrectly() throws Exception {
        Encoder encoder = new Encoder(4096, Integer.MAX_VALUE);
        encoder.setAutoHuffman(false);

        List<Header> headers = new ArrayList<>();
        headers.add(new Header("x-custom-unindexed-name", COMPRESSIBLE_VALUE));

        ByteBuffer buf = ByteBuffer.allocate(4096);
        encoder.encode(buf, headers);
        buf.flip();

        final List<Header> decodedHeaders = new ArrayList<>();
        Decoder decoder = new Decoder(4096);
        decoder.decode(buf, new HeaderHandler() {
            @Override
            public void header(Header header) {
                decodedHeaders.add(header);
            }
        });

        assertEquals(1, decodedHeaders.size());
        assertEquals("x-custom-unindexed-name", decodedHeaders.get(0).getName());
        assertEquals(COMPRESSIBLE_VALUE, decodedHeaders.get(0).getValue());
    }

    @Test
    public void testAutoHuffmanTrueStillUsesHuffmanWhenShorter() throws Exception {
        Encoder encoder = new Encoder(4096, Integer.MAX_VALUE);
        // autoHuffman defaults to true.

        List<Header> headers = new ArrayList<>();
        headers.add(new Header("x-custom-unindexed-name", COMPRESSIBLE_VALUE));

        ByteBuffer buf = ByteBuffer.allocate(4096);
        encoder.encode(buf, headers);
        buf.flip();

        byte[] wire = new byte[buf.remaining()];
        buf.get(wire);

        int nameLengthByteIndex = 1;
        int nameLength = wire[nameLengthByteIndex] & 0x7F;
        int valueLengthByteIndex = nameLengthByteIndex + 1 + nameLength;
        assertEquals("a highly repetitive value must still be Huffman-coded "
                + "when autoHuffman is enabled (the default)",
                0x80, wire[valueLengthByteIndex] & 0x80);
    }
}
