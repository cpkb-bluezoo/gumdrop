/*
 * HPACKConstants.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.http.hpack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;

/**
 * Static constants for HPACK decoder and encoder.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc7541
 */
abstract class HPACKConstants {

    /**
     * @see https://www.rfc-editor.org/rfc/rfc7541.html#appendix-A
     */
    protected static final List<Header> STATIC_TABLE = Collections.unmodifiableList(Arrays.asList(new Header[] {
        null,
        new Header(":authority", null),
        new Header(":method", "GET"),
        new Header(":method", "POST"),
        new Header(":path", "/"),
        new Header(":path", "/index.html"),
        new Header(":scheme", "http"),
        new Header(":scheme", "https"),
        new Header(":status", "200"),
        new Header(":status", "204"),
        new Header(":status", "206"),
        new Header(":status", "304"),
        new Header(":status", "400"),
        new Header(":status", "404"),
        new Header(":status", "500"),
        new Header("accept-charset", null),
        new Header("accept-encoding", "gzip, deflate"),
        new Header("accept-language", null),
        new Header("accept-ranges", null),
        new Header("accept", null),
        new Header("access-control-allow-origin", null),
        new Header("age", null),
        new Header("allow", null),
        new Header("authorization", null),
        new Header("cache-control", null),
        new Header("content-disposition", null),
        new Header("content-encoding", null),
        new Header("content-language", null),
        new Header("content-length", null),
        new Header("content-location", null),
        new Header("content-range", null),
        new Header("content-type", null),
        new Header("cookie", null),
        new Header("date", null),
        new Header("etag", null),
        new Header("expect", null),
        new Header("expires", null),
        new Header("from", null),
        new Header("host", null),
        new Header("if-match", null),
        new Header("if-modified-since", null),
        new Header("if-none-match", null),
        new Header("if-range", null),
        new Header("if-unmodified-since", null),
        new Header("last-modified", null),
        new Header("link", null),
        new Header("location", null),
        new Header("max-forwards", null),
        new Header("proxy-authenticate", null),
        new Header("proxy-authorization", null),
        new Header("range", null),
        new Header("referer", null),
        new Header("refresh", null),
        new Header("retry-after", null),
        new Header("server", null),
        new Header("set-cookie", null),
        new Header("strict-transport-security", null),
        new Header("transfer-encoding", null),
        new Header("user-agent", null),
        new Header("vary", null),
        new Header("via", null),
        new Header("www-authenticate", null)
    }));

    protected static final int STATIC_TABLE_SIZE = STATIC_TABLE.size(); // 62

    /**
     * The size of a dynamic table is the sum of the size of its entries.
     */
    protected static int tableSize(List<Header> table) {
        int acc = 0;
        for (Header header : table) {
            acc += headerSize(header);
        }
        return acc;
    }

    /**
     * The size of an entry is the sum of its name's length in octets (as
     * defined in Section 5.2), its value's length in octets, and 32.
     * The size of an entry is calculated using the length of its name and
     * value without any Huffman encoding applied.
     */
    protected static int headerSize(Header header) {
        return header.getName().length() + header.getValue().length() + 32;
    }

    // Testing
    public static void main(String[] args) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(4096);
        Decoder decoder;
        HeaderHandler handler = new HeaderHandler() {
            public void header(Header header) {
                System.out.println(header.toString());
            }
        };

        /*// C.1.1 encode 10 using 5-bit prefix
        buf.clear();
        int val = decodeInteger(buf, (byte) 0x6a, 5);
        System.err.println("decodeInteger returned "+val+", should be 10");

        buf.clear();
        encodeInteger(buf, (byte) 0x60, 10, 5);
        buf.flip();
        byte b = buf.get();
        System.err.println("encodeInteger encoded "+String.format("0x%02x", (b & 0xff))+", should be 0x6a");

        // C.1.2 encode 1337 using 5-bit prefix
        buf.clear();
        buf.put((byte) 0x9a);
        buf.put((byte) 0xa);
        buf.flip();
        val = decodeInteger(buf, (byte) 0x7f, 5);
        System.err.println("decodeInteger returned "+val+", should be 1337");

        buf.clear();
        encodeInteger(buf, (byte) 0x60, 1337, 5);
        buf.flip();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            b = buf.get();
            sb.append(String.format("0x%02x ", (b & 0xff)));
        }
        System.err.println("encodeInteger encoded "+sb.toString()+", should be 0x7f 0x9a 0x0a");

        // C.2.1 Literal Header Field with Indexing
        Header header = new Header("custom-key", "custom-header");
        byte[] encoded = new byte[] {
            (byte) 0x40, (byte) 0x0a, (byte) 0x63, (byte) 0x75, (byte) 0x73, (byte) 0x74,
            (byte) 0x6f, (byte) 0x6d, (byte) 0x2d, (byte) 0x6b, (byte) 0x65, (byte) 0x79,
            (byte) 0x0d, (byte) 0x63, (byte) 0x75, (byte) 0x73, (byte) 0x74, (byte) 0x6f,
            (byte) 0x6d, (byte) 0x2d, (byte) 0x68, (byte) 0x65, (byte) 0x61, (byte) 0x64,
            (byte) 0x65, (byte) 0x72
        };
        buf.clear();
        buf.put(encoded);
        buf.flip();
        HPACKHeaders hh = new HPACKHeaders(buf, 4096);
        System.err.println(header + " should be "+hh.get(0));

        buf.clear();
        hh = new HPACKHeaders(Collections.singletonList(header));
        hh.setAutoHuffman(false);
        hh.write(buf, 4096);
        buf.flip();
        boolean success = true;
        for (int i = 0; i < encoded.length && success; i++) {
            b = buf.get(i);
            if (b != encoded[i]) {
                System.err.println("Failed encoding of C.2.1 at index "+i+" encoded="+String.format("%02x", encoded[i])+" written="+String.format("%02x", b));
                success = false;
            }
        }
        if (success) {
            System.err.println("Successfully encoded C.2.1");
        }

        // C.2.2 Literal Header Field without Indexing
        header = new Header(":path", "/sample/path");
        encoded = new byte[] {
            (byte) 0x04, (byte) 0x0c, (byte) 0x2f, (byte) 0x73, (byte) 0x61, (byte) 0x6d,
            (byte) 0x70, (byte) 0x6c, (byte) 0x65, (byte) 0x2f, (byte) 0x70, (byte) 0x61,
            (byte) 0x74, (byte) 0x68
        };
        buf.clear();
        buf.put(encoded);
        buf.flip();
        hh = new HPACKHeaders(buf, 4096);
        System.err.println(header + " should be "+hh.get(0));

        buf.clear();
        hh = new HPACKHeaders(Collections.singletonList(header));
        hh.setAutoHuffman(false);
        hh.setNoIndexing(true);
        hh.write(buf, 4096);
        buf.flip();
        success = true;
        for (int i = 0; i < encoded.length && success; i++) {
            b = buf.get(i);
            if (b != encoded[i]) {
                System.err.println("Failed encoding of C.2.2 at index "+i+" encoded="+String.format("%02x", encoded[i])+" written="+String.format("%02x", b));
                success = false;
            }
        }
        if (success) {
            System.err.println("Successfully encoded C.2.2");
        }

        // C.2.3 Literal Header Field Never Indexed
        header = new Header("password", "secret");
        encoded = new byte[] {
            (byte) 0x10, (byte) 0x08, (byte) 0x70, (byte) 0x61, (byte) 0x73, (byte) 0x73,
            (byte) 0x77, (byte) 0x6f, (byte) 0x72, (byte) 0x64, (byte) 0x06, (byte) 0x73,
            (byte) 0x65, (byte) 0x63, (byte) 0x72, (byte) 0x65, (byte) 0x74
        };
        buf.clear();
        buf.put(encoded);
        buf.flip();
        hh = new HPACKHeaders(buf, 4096);
        System.err.println(header + " should be "+hh.get(0));*/

        String s = args[0];
        byte[] encoded = toByteArray(s);
        buf.clear();
        buf.put(encoded);
        buf.flip();
        decoder = new Decoder(4096);
        decoder.decode(buf, handler);
    }

    public static byte[] toByteArray(String hexString) {
        if (hexString == null || hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must not be null and must have an even length.");
        }
        byte[] byteArray = new byte[hexString.length() / 2];
        for (int i = 0; i < hexString.length(); i += 2) {
            String hexPair = hexString.substring(i, i + 2);
            byteArray[i / 2] = (byte) Integer.parseInt(hexPair, 16);
        }
        return byteArray;
    }

    private void dumpDynamicTable(List<Header> dynamicTable) {
        for (int i = 0; i < dynamicTable.size(); i++) {
            System.err.println((i + STATIC_TABLE_SIZE)+" = "+dynamicTable.get(i));
        }
    }

}
