/*
 * HPACKHeaders.java
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
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.net.ProtocolException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;

/**
 * An HTTP/2 HPACK header list.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc7541
 */
public class HPACKHeaders extends AbstractList<Header> {

    /**
     * @see https://www.rfc-editor.org/rfc/rfc7541.html#appendix-A
     */
    private static final List<Header> STATIC_TABLE = Collections.unmodifiableList(Arrays.asList(new Header[] {
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

    private static final int STATIC_TABLE_SIZE = STATIC_TABLE.size(); // 62

    private static final Charset US_ASCII = Charset.forName("US-USCII");

    /**
     * The headers in this list.
     */
    private ArrayList<Header> headers;

    /**
     * Constructor from a collection of headers.
     * @param headers the headers
     */
    public HPACKHeaders(Collection<Header> headers) {
        this.headers = new ArrayList<>(headers);
    }

    /**
     * Constructor from an HPACK-encoded sequence of bytes.
     * @param buf the header block
     */
    public HPACKHeaders(ByteBuffer buf, int maxHeaderTableSize) throws IOException {
        headers = new ArrayList<>();
        List<Header> dynamicTable = new ArrayList<>(maxHeaderTableSize);
        // decode
        while (buf.hasRemaining()) {
            byte b = buf.get();
            Header header;
            if ((b & 0x80) != 0) { // indexed header field
                int index = decodeInteger(buf, b, 7);
                if (index > 0 && index < STATIC_TABLE_SIZE) {
                    header = STATIC_TABLE.get(index);
                    if (header.getValue() == null) {
                        throw new ProtocolException("HPACK indexed header field does not reference name+value");
                    }
                } else if ((index - STATIC_TABLE_SIZE) < dynamicTable.size()) {
                    header = dynamicTable.get(index - STATIC_TABLE_SIZE);
                } else {
                    throw new ProtocolException("HPACK indexed header field does not reference entry in static or dynamic table");
                }
            } else if ((b & 0x40) != 0) { // literal header field with incremental indexing
                header = getLiteralHeaderField(buf, b, 6, dynamicTable);
                dynamicTable.add(0, header);
                // evict entries
                while (tableSize(dynamicTable) > maxHeaderTableSize) {
                    dynamicTable.remove(dynamicTable.size() - 1);
                }
            } else if ((b & 0x20) != 0) { // dynamic table size update
                int maxSize = decodeInteger(buf, b, 5);
                if (maxSize > maxHeaderTableSize) {
                    throw new ProtocolException("dynamic table size update larger than max header table size");
                }
                // evict entries
                while (tableSize(dynamicTable) > maxSize) {
                    dynamicTable.remove(dynamicTable.size() - 1);
                }
                maxHeaderTableSize = maxSize;
                continue;
            } else { // literal header field never indexed
                // OR literal header field without indexing
                header = getLiteralHeaderField(buf, b, 4, dynamicTable);
                // do not add to dynamicTable
            }
            headers.add(header);
        }
    }

    /**
     * The size of the dynamic table is the sum of the size of its entries.
     */
    private static int tableSize(List<Header> table) {
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
    private static int headerSize(Header header) {
        return header.getName().length() + header.getValue().length() + 32;
    }

    private static Header getLiteralHeaderField(ByteBuffer buf, byte opcode, int nbits, List<Header> dynamicTable) throws BufferUnderflowException, IOException {
        int index = decodeInteger(buf, opcode, 4);
        String name, value;
        byte b;
        if (index < 1) { // new name
            b = buf.get();
            boolean huffman = (b & 0x80) != 0;
            int nameLength = decodeInteger(buf, b, 7);
            byte[] s = new byte[nameLength];
            buf.get(s);
            if (huffman) {
                s = HPACKHuffman.decode(s);
            }
            name = new String(s, "US-ASCII");
        } else { // indexed name
            if (index < STATIC_TABLE_SIZE) {
                name = STATIC_TABLE.get(index).getName();
            } else {
                name = dynamicTable.get(index - STATIC_TABLE_SIZE).getName();
            }
        }
        // value
        b = buf.get();
        boolean huffman = (b & 0x80) != 0;
        int valueLength = decodeInteger(buf, b, 7);
        byte[] s = new byte[valueLength];
        buf.get(s);
        if (huffman) {
            s = HPACKHuffman.decode(s);
        }
        value = new String(s, "US-ASCII");
        return new Header(name, value);
    }

    /**
     * Decode an integer.
     * @param buf the buffer to read additional bytes from
     * @param opcode the opcode byte
     * @param nbits the number of bits not in the opcode
     */
    private static int decodeInteger(ByteBuffer buf, byte opcode, int nbits) throws BufferUnderflowException {
        // Maximum value that fits in N bits
        int nmask = (1 << nbits) - 1; // same as Math.pow(2, nbits) - 1
        int value = opcode & nmask;
        if (value < nmask) { // value fits in n bits
            return value;
        } else { // read continuation bytes
                 // the N bits were all 1
            int shift = 0;
            byte b;
            do {
                if (!buf.hasRemaining()) {
                    throw new BufferUnderflowException();
                }
                b = buf.get();
                // add the 7 least significant bits to value
                value += (b & 0x7f) * (1 << shift);
                shift += 7;
            } while ((b & 0x80) != 0); // continue while MSB is 1
            return value;
        }
    }

    /**
     * Writes this header list to a byte buffer.
     * @param buf the buffer
     * @param maxHeaderTableSize the maximum allowed size for the dynamic
     * header table
     */
    public void write(ByteBuffer buf, int maxHeaderTableSize) {
        List<Header> dynamicTable = new ArrayList<>();
        for (Header header : headers) {
            // Determine which type of representation to use
            int index = STATIC_TABLE.indexOf(header); // will only match name+value pairs
            if (index == -1) {
                index = dynamicTable.indexOf(header);
                if (index != -1) {
                    index += STATIC_TABLE_SIZE;
                }
            }
            if (index > 0) { // indexed header field
                encodeInteger(buf, (byte) 0x80, index, 7);
            } else {
                String name = header.getName();
                String value = header.getValue();
                index = indexOfName(STATIC_TABLE, name);
                if (index == -1) {
                    index = indexOfName(dynamicTable, name);
                    if (index != -1) {
                        index += STATIC_TABLE_SIZE;
                    }
                }
                boolean overflow = tableSize(dynamicTable) + headerSize(header) > maxHeaderTableSize;
                if (index > 0) { // literal header field indexed
                    if (!overflow) { // with incremental indexing
                        encodeInteger(buf, (byte) 0x40, index, 6);
                    } else { // without indexing
                        encodeInteger(buf, (byte) 0, index, 4);
                    }
                } else { // literal header field new name
                    byte[] rname = name.getBytes(US_ASCII); // raw bytes
                    byte[] hname = HPACKHuffman.encode(rname); // Huffman encoded bytes
                    boolean useHuffman = (hname.length < rname.length);
                    byte hbit = (byte) (useHuffman ? 0x80 : 0);
                    int nameLength = useHuffman ? hname.length : rname.length;
                    if (!overflow) { // with incremental indexing
                        encodeInteger(buf, (byte) 0x40, 0, 6);
                        encodeInteger(buf, hbit, nameLength, 7);
                    } else { // without indexing
                        encodeInteger(buf, (byte) 0, 0, 4);
                        encodeInteger(buf, hbit, nameLength, 7);
                    }
                    buf.put(useHuffman ? hname : rname);
                }
                // value
                byte[] rvalue = value.getBytes(US_ASCII); // raw bytes
                byte[] hvalue = HPACKHuffman.encode(rvalue); // huffman encoded bytes
                boolean useHuffman = (hvalue.length < rvalue.length);
                byte hbit = (byte) (useHuffman ? 0x80 : 0);
                int valueLength = useHuffman ? hvalue.length : rvalue.length;
                encodeInteger(buf, hbit, valueLength, 7);
                buf.put(useHuffman ? hvalue : rvalue);
            }
        }
    }

    private static int indexOfName(List<Header> table, String name) {
        for (int i = 0; i < table.size(); i++) {
            Header header = table.get(i);
            if (name.equals(header.getName())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Encode an integer.
     * @param buf the buffer to write to
     * @param opcode byte with the high bits set to the opcode to set
     * @param value the integer value
     * @param nbits the number of bits not in the opcode
     */
    private static void encodeInteger(ByteBuffer buf, byte opcode, int value, int nbits) {
        if (value < 0) {
            throw new IllegalArgumentException("Value must be positive");
        }
        if (nbits < 1 || nbits > 7) {
            throw new IllegalArgumentException("Prefix size N must be between 1 and 7");
        }
        // Maximum value that fits in N bits
        int nmask = (1 << nbits) - 1; // same as Math.pow(2, nbits) - 1
        if (value < nmask) { // value fits in N bits
            buf.put((byte) (opcode | value));
        } else { // value does not fit in N bits
                 // prefix is set to 1s and remaining value is encoded in 7-bit
                 // chunks with continuation bit
            buf.put((byte) (opcode | nmask)); // prefix
            value -= nmask;
            while (value >= 128) {
                // set MSB to 1 for continuation
                buf.put((byte) ((value % 128) | 0x80));
                value /= 128;
            }
            // set MSB to 0 for end of continuation
            buf.put((byte) value);
        }
    }

    // -- AbstractList --

    @Override
    public int size() {
        return headers.size();
    }

    @Override
    public Header get(int index) {
        return headers.get(index);
    }

    @Override
    public Header set(int index, Header header) {
        return headers.set(index, header);
    }

    @Override
    public void add(int index, Header header) {
        headers.add(index, header);
    }

    @Override
    public Header remove(int index) {
        return headers.remove(index);
    }

    @Override
    public void clear() {
        headers.clear();
    }

    @Override
    public int hashCode() {
        return headers.hashCode();
    }

}
