/*
 * QPACKCodecTest.java
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

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.util.ByteArrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Verifies the static-table-only {@link SimpleEncoder}/{@link SimpleDecoder}
 * against the RFC 9204 Appendix B.1 worked example (a literal field
 * line with a static name reference -- the only worked example in the
 * RFC that does not touch the dynamic table this implementation
 * deliberately does not support), plus round-trip coverage of the
 * other two representations this encoder produces.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#appendix-B.1">RFC 9204 Appendix B.1</a>
 */
public class QPACKCodecTest {

    // RFC 9204 Appendix B.1: Required Insert Count=0, Base=0, then a
    // Literal Field Line with Name Reference to static index 1 (:path)
    // with literal (non-Huffman) value "/index.html".
    private static final String RFC_B1_BYTES = "0000510b2f696e6465782e68746d6c";

    @Test
    public void testDecodeRfc9204AppendixB1() throws ProtocolException {
        byte[] encoded = ByteArrays.toByteArray(RFC_B1_BYTES);
        List<Header> headers = new SimpleDecoder().decode(ByteBuffer.wrap(encoded));

        assertEquals(1, headers.size());
        assertEquals(":path", headers.get(0).getName());
        assertEquals("/index.html", headers.get(0).getValue());
    }

    @Test
    public void testEncodeRfc9204AppendixB1() {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header(":path", "/index.html"));

        SimpleEncoder encoder = new SimpleEncoder();
        encoder.setAutoHuffman(false); // the RFC example encodes the value literally, not Huffman-coded
        ByteBuffer buf = ByteBuffer.allocate(64);
        encoder.encode(buf, headers);
        buf.flip();
        byte[] actual = new byte[buf.remaining()];
        buf.get(actual);

        assertEquals(RFC_B1_BYTES, ByteArrays.toHexString(actual));
    }

    @Test
    public void testIndexedFieldLineRoundTrip() throws ProtocolException {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header(":method", "GET")); // exact static table match

        List<Header> decoded = roundTrip(headers);
        assertEquals(headers, decoded);
    }

    @Test
    public void testLiteralFieldLineWithLiteralNameRoundTrip() throws ProtocolException {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header("x-gumdrop-trace-id", "9f2e1c-abcdef"));

        List<Header> decoded = roundTrip(headers);
        assertEquals(headers, decoded);
    }

    @Test
    public void testMixedHeaderSetRoundTrip() throws ProtocolException {
        List<Header> headers = new ArrayList<Header>();
        headers.add(new Header(":method", "POST")); // indexed
        headers.add(new Header(":scheme", "https")); // indexed
        headers.add(new Header(":path", "/query")); // literal with name reference
        headers.add(new Header(":authority", "doq.example.com")); // literal with name reference
        headers.add(new Header("x-request-id", "abc-123")); // literal with literal name

        List<Header> decoded = roundTrip(headers);
        assertEquals(headers, decoded);
    }

    @Test
    public void testRejectsNonZeroRequiredInsertCount() {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.put((byte) 0x02); // Required Insert Count = 2: dynamic table use, not supported
        buf.put((byte) 0x00);
        buf.flip();

        try {
            new SimpleDecoder().decode(buf);
            fail("Expected ProtocolException for non-zero Required Insert Count");
        } catch (ProtocolException expected) {
            // expected
        }
    }

    @Test
    public void testRejectsDynamicTableIndexedFieldLine() {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.put((byte) 0x00); // Required Insert Count = 0
        buf.put((byte) 0x00); // Base prefix
        buf.put((byte) 0x80); // Indexed Field Line, T=0 (dynamic table)
        buf.flip();

        try {
            new SimpleDecoder().decode(buf);
            fail("Expected ProtocolException for a dynamic table reference");
        } catch (ProtocolException expected) {
            // expected
        }
    }

    private static List<Header> roundTrip(List<Header> headers) throws ProtocolException {
        SimpleEncoder encoder = new SimpleEncoder();
        ByteBuffer buf = ByteBuffer.allocate(256);
        encoder.encode(buf, headers);
        buf.flip();
        return new SimpleDecoder().decode(buf);
    }
}
