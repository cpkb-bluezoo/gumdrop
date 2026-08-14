/*
 * Decoder.java
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.hpack.Huffman;

/**
 * QPACK header decoder (RFC 9204).
 *
 * <p>Static table only -- see the package documentation for why. A
 * field section whose Required Insert Count is not 0, or that contains
 * any representation referencing the dynamic table (a {@code T=0}
 * indexed field line or name reference, or either post-Base
 * representation), is rejected: this decoder can only be talking to a
 * peer that respects a {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY} of 0,
 * so such a field section indicates either a non-conformant peer or an
 * H3 layer bug that sent the wrong settings value.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Encoder
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204">RFC 9204</a>
 */
public class Decoder extends QPACKConstants {

    private static final Charset US_ASCII = StandardCharsets.US_ASCII;

    /**
     * Decodes an encoded field section (RFC 9204 section 4.5.1).
     *
     * @param buf the buffer to read from, positioned at the start of
     *            the field section, consumed fully on return
     * @return the decoded headers, in wire order
     * @throws ProtocolException if the field section is malformed, or
     *         references the dynamic table (see the class documentation)
     */
    public List<Header> decode(ByteBuffer buf) throws ProtocolException {
        if (buf.remaining() < 2) {
            throw new ProtocolException("QPACK field section underflow reading prefix");
        }
        int requiredInsertCountByte = buf.get() & 0xff;
        long requiredInsertCount = PrefixedInteger.decode(buf, requiredInsertCountByte, 8);
        int deltaBaseByte = buf.get() & 0xff;
        PrefixedInteger.decode(buf, deltaBaseByte, 7); // Sign + Delta Base, unused (see class docs)

        if (requiredInsertCount != 0) {
            throw new ProtocolException(
                    "QPACK dynamic table is not supported: Required Insert Count must be 0, was "
                    + requiredInsertCount);
        }

        List<Header> headers = new ArrayList<Header>();
        while (buf.hasRemaining()) {
            int b = buf.get() & 0xff;
            if ((b & 0x80) != 0) {
                headers.add(decodeIndexedFieldLine(buf, b));
            } else if ((b & 0x40) != 0) {
                headers.add(decodeLiteralFieldLineWithNameReference(buf, b));
            } else if ((b & 0x20) != 0) {
                headers.add(decodeLiteralFieldLineWithLiteralName(buf, b));
            } else if ((b & 0x10) != 0) {
                // RFC 9204 section 4.5.3: Indexed Field Line with Post-Base Index
                throw new ProtocolException(
                        "QPACK dynamic table is not supported: post-Base indexed field line");
            } else {
                // RFC 9204 section 4.5.5: Literal Field Line with Post-Base Name Reference
                throw new ProtocolException(
                        "QPACK dynamic table is not supported: post-Base name reference");
            }
        }
        return headers;
    }

    // RFC 9204 section 4.5.2: '1|T|Index(6+)'
    private Header decodeIndexedFieldLine(ByteBuffer buf, int firstByte) throws ProtocolException {
        boolean staticTable = (firstByte & 0x40) != 0;
        long index = PrefixedInteger.decode(buf, firstByte, 6);
        if (!staticTable) {
            throw new ProtocolException("QPACK dynamic table is not supported: indexed field line");
        }
        if (index < 0 || index >= STATIC_TABLE_SIZE) {
            throw new ProtocolException("QPACK static table index out of range: " + index);
        }
        return STATIC_TABLE.get((int) index);
    }

    // RFC 9204 section 4.5.4: '01|N|T|NameIndex(4+)' + value string literal
    private Header decodeLiteralFieldLineWithNameReference(ByteBuffer buf, int firstByte)
            throws ProtocolException {
        boolean staticTable = (firstByte & 0x10) != 0;
        long nameIndex = PrefixedInteger.decode(buf, firstByte, 4);
        String value = readStringLiteral(buf);
        if (!staticTable) {
            throw new ProtocolException("QPACK dynamic table is not supported: name reference");
        }
        if (nameIndex < 0 || nameIndex >= STATIC_TABLE_SIZE) {
            throw new ProtocolException("QPACK static table index out of range: " + nameIndex);
        }
        String name = STATIC_TABLE.get((int) nameIndex).getName();
        return new Header(name, value);
    }

    // RFC 9204 section 4.5.6: '001|N|H|NameLen(3+)' + name bytes + value string literal
    private Header decodeLiteralFieldLineWithLiteralName(ByteBuffer buf, int firstByte)
            throws ProtocolException {
        boolean nameHuffman = (firstByte & 0x08) != 0;
        long nameLength = PrefixedInteger.decode(buf, firstByte, 3);
        String name = readStringLiteralBody(buf, (int) nameLength, nameHuffman);
        String value = readStringLiteral(buf);
        return new Header(name, value);
    }

    // RFC 9204 section 4.1.2 (reusing RFC 7541 section 5.2 unmodified):
    // an 8-bit prefix string literal, H bit + Length + bytes.
    private String readStringLiteral(ByteBuffer buf) throws ProtocolException {
        if (!buf.hasRemaining()) {
            throw new ProtocolException("QPACK string literal underflow");
        }
        int b = buf.get() & 0xff;
        boolean huffman = (b & 0x80) != 0;
        long length = PrefixedInteger.decode(buf, b, 7);
        return readStringLiteralBody(buf, (int) length, huffman);
    }

    private String readStringLiteralBody(ByteBuffer buf, int length, boolean huffman)
            throws ProtocolException {
        if (length < 0 || length > buf.remaining()) {
            throw new ProtocolException("QPACK string literal length exceeds payload");
        }
        byte[] raw = new byte[length];
        buf.get(raw);
        if (huffman) {
            try {
                raw = Huffman.decode(raw);
            } catch (IOException e) {
                throw new ProtocolException("QPACK Huffman decode failed: " + e.getMessage());
            }
        }
        return new String(raw, US_ASCII);
    }
}
