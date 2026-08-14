/*
 * QPACKStrings.java
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

package org.bluezoo.gumdrop.http.qpack;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.http.hpack.Huffman;

/**
 * Huffman-or-raw string literals with an N-bit length prefix (RFC 9204
 * section 4.1.2, reusing RFC 7541 section 5.2 unmodified) -- shared by
 * the dynamic-table-aware {@link Encoder}/{@link Decoder} and the
 * encoder/decoder instruction streams, whose formats differ only in
 * which prefix width the surrounding representation allots to the
 * length.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class QPACKStrings {

    private QPACKStrings() {
    }

    /**
     * Writes {@code value} Huffman-coded if that is shorter, else raw,
     * setting the H bit ({@code 1 << prefixBits}) accordingly.
     *
     * @param out the destination buffer
     * @param value the bytes to write
     * @param prefixBits the number of low bits of the first byte
     *                   available for the length
     * @param firstByteBits the already-set high bits of the first byte
     */
    static void write(ByteBuffer out, byte[] value, int prefixBits, int firstByteBits) {
        byte[] huffman = Huffman.encode(value);
        if (huffman.length < value.length) {
            PrefixedInteger.encode(out, firstByteBits | (1 << prefixBits), huffman.length, prefixBits);
            out.put(huffman);
        } else {
            PrefixedInteger.encode(out, firstByteBits, value.length, prefixBits);
            out.put(value);
        }
    }

    /**
     * Reads a string written by {@link #write}.
     *
     * @param in the buffer to read from, positioned at the start of the string
     * @param prefixBits the number of low bits of the first byte
     *                   holding the length
     * @return the decoded bytes
     * @throws ProtocolException if the buffer underflows or the
     *         Huffman-coded bytes are malformed
     */
    static byte[] read(ByteBuffer in, int prefixBits) throws ProtocolException {
        if (!in.hasRemaining()) {
            throw new ProtocolException("QPACK string literal underflow");
        }
        int firstByte = in.get() & 0xff;
        boolean huffmanCoded = (firstByte & (1 << prefixBits)) != 0;
        long length = PrefixedInteger.decode(in, firstByte, prefixBits);
        if (length < 0 || length > in.remaining()) {
            throw new ProtocolException("QPACK string literal length exceeds available data");
        }
        byte[] raw = new byte[(int) length];
        in.get(raw);
        if (!huffmanCoded) {
            return raw;
        }
        try {
            return Huffman.decode(raw);
        } catch (IOException e) {
            throw new ProtocolException("QPACK Huffman decode failed: " + e.getMessage());
        }
    }
}
