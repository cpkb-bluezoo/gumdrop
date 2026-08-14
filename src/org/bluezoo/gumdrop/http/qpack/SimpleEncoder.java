/*
 * SimpleEncoder.java
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

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.hpack.Huffman;

/**
 * Stateless, static-table-only QPACK header encoder (RFC 9204).
 *
 * <p>{@link Encoder} is the real encoder -- this one exists as a simple,
 * dependency-free building block: no dynamic table, no per-connection
 * state, safe to use from a single static call. Every encoded field
 * section it produces has Required Insert Count 0 and Base 0 (RFC 9204
 * section 4.5.1), and uses only:
 * <ul>
 * <li>Indexed field line, static table (section 4.5.2)</li>
 * <li>Literal field line with name reference, static table (section 4.5.4)</li>
 * <li>Literal field line with literal name (section 4.5.6)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SimpleDecoder
 * @see Encoder
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204">RFC 9204</a>
 */
public class SimpleEncoder extends QPACKConstants {

    private static final Charset US_ASCII = StandardCharsets.US_ASCII;

    /**
     * If true, when writing a literal name or value we will prefer
     * Huffman encoding if it results in a shorter encoded sequence.
     */
    private boolean autoHuffman = true;

    /**
     * Set whether to prefer Huffman encoding.
     *
     * @param flag if true, use Huffman encoding whenever it is shorter
     */
    public void setAutoHuffman(boolean flag) {
        this.autoHuffman = flag;
    }

    /**
     * Writes an encoded field section (RFC 9204 section 4.5.1) for the
     * given headers to {@code buf}.
     *
     * @param buf the buffer to write to
     * @param headers the headers to write
     */
    public void encode(ByteBuffer buf, List<Header> headers) {
        // Required Insert Count = 0, Base = 0 (Sign = 0, Delta Base = 0):
        // this encoder never references the dynamic table, so every
        // field section prefix is the fixed two-byte sequence 0x00 0x00.
        PrefixedInteger.encode(buf, 0, 0, 8);
        PrefixedInteger.encode(buf, 0, 0, 7);

        for (Header header : headers) {
            int index = STATIC_TABLE.indexOf(header);
            if (index != -1) {
                // RFC 9204 section 4.5.2: Indexed Field Line, T=1 (static)
                PrefixedInteger.encode(buf, 0xc0, index, 6);
                continue;
            }

            String name = header.getName();
            String value = header.getValue();
            int nameIndex = indexOfName(STATIC_TABLE, name);
            if (nameIndex != -1) {
                // RFC 9204 section 4.5.4: Literal Field Line with Name
                // Reference, N=0, T=1 (static)
                PrefixedInteger.encode(buf, 0x50, nameIndex, 4);
                writeStringLiteral(buf, value);
                continue;
            }

            writeLiteralNameAndValue(buf, name, value);
        }
    }

    private static int indexOfName(List<Header> table, String name) {
        for (int i = 0; i < table.size(); i++) {
            Header header = table.get(i);
            if (header != null && name.equals(header.getName())) {
                return i;
            }
        }
        return -1;
    }

    // RFC 9204 section 4.5.6: Literal Field Line with Literal Name, N=0.
    // Unlike the standalone string-literal format used elsewhere, this
    // representation's Name Length prefix and Huffman flag share the
    // same opcode byte as the '001' pattern and the N bit.
    private void writeLiteralNameAndValue(ByteBuffer buf, String name, String value) {
        byte[] rawName = name.toLowerCase().getBytes(US_ASCII);
        byte[] huffmanName = autoHuffman ? Huffman.encode(rawName) : null;
        boolean useHuffmanName = huffmanName != null && huffmanName.length < rawName.length;
        int nameHuffmanBit = useHuffmanName ? 0x08 : 0;
        int nameLength = useHuffmanName ? huffmanName.length : rawName.length;

        PrefixedInteger.encode(buf, 0x20 | nameHuffmanBit, nameLength, 3);
        buf.put(useHuffmanName ? huffmanName : rawName);

        writeStringLiteral(buf, value);
    }

    // RFC 9204 section 4.1.2 (reusing RFC 7541 section 5.2 unmodified):
    // an 8-bit prefix string literal, H bit + Length + bytes.
    private void writeStringLiteral(ByteBuffer buf, String value) {
        byte[] raw = value.getBytes(US_ASCII);
        byte[] huffman = autoHuffman ? Huffman.encode(raw) : null;
        boolean useHuffman = huffman != null && huffman.length < raw.length;
        int huffmanBit = useHuffman ? 0x80 : 0;
        int length = useHuffman ? huffman.length : raw.length;

        PrefixedInteger.encode(buf, huffmanBit, length, 7);
        buf.put(useHuffman ? huffman : raw);
    }
}
