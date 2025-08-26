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

    private static final Charset US_ASCII = Charset.forName("US-ASCII");

    /**
     * The headers in this list.
     */
    private ArrayList<Header> headers;

    /**
     * If true, when writing headers we will prefer Huffman encoding if it
     * results in a shorter encoded sequence. If set to false, we won't use
     * Huffman encoding of keys or values.
     */
    private boolean autoHuffman = true;

    /**
     * If true, we will not update the dynamic table when writing.
     */
    private boolean noIndexing;

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
            //System.err.println("decoding header, opcode is "+String.format("%02x",b));
            if ((b & 0x80) != 0) { // indexed header field
                int index = decodeInteger(buf, b, 7);
                //System.err.println(" indexed header field, index is "+index);
                //dumpDynamicTable(dynamicTable);
                if (index == 0) {
                    // see section 6.1
                    throw new ProtocolException("HPACK indexed header field with index 0");
                } else if (index < STATIC_TABLE_SIZE) {
                    header = STATIC_TABLE.get(index);
                    if (header.getValue() == null) {
                        throw new ProtocolException("HPACK indexed header field does not reference name+value");
                    }
                } else if ((index - STATIC_TABLE_SIZE) < dynamicTable.size()) {
                    header = dynamicTable.get(index - STATIC_TABLE_SIZE);
                } else {
                    throw new ProtocolException("HPACK indexed header field index out of range: "+index);
                }
            } else if ((b & 0x40) != 0) { // literal header field with incremental indexing
                //System.err.println(" literal header field");
                header = getLiteralHeaderField(buf, b, 6, dynamicTable);
                // evict entries: RFC 7541 section 4.4
                int newEntrySize = headerSize(header);
                while (tableSize(dynamicTable) > (maxHeaderTableSize - newEntrySize)) {
                    dynamicTable.remove(dynamicTable.size() - 1);
                }
                // The header field is added to the beginning of the dynamic
                // table, see sections 3.2, 2.3.2
                dynamicTable.add(0, header);
            } else if ((b & 0x20) != 0) { // dynamic table size update
                int maxSize = decodeInteger(buf, b, 5);
                //System.err.println(" dynamic table size update, maxSize="+maxSize);
                if (maxSize > maxHeaderTableSize) {
                    throw new ProtocolException("dynamic table size update larger than max header table size");
                }
                // evict entries: RFC 7541 section 4.3
                while (tableSize(dynamicTable) > maxSize) {
                    dynamicTable.remove(dynamicTable.size() - 1);
                }
                maxHeaderTableSize = maxSize;
                continue;
            } else { // literal header field never indexed
                // OR literal header field without indexing
                //System.err.println(" literal header field without indexing");
                header = getLiteralHeaderField(buf, b, 4, dynamicTable);
                // do not add to dynamicTable
            }
            //System.err.println("  header="+header);
            headers.add(header);
        }
    }

    /**
     * Set whether to prefer Huffman encoding.
     * If true, when writing headers we will prefer Huffman encoding if it
     * results in a shorter encoded sequence. If set to false, we won't use
     * Huffman encoding of keys or values.
     */
    public void setAutoHuffman(boolean flag) {
        autoHuffman = flag;
    }

    /**
     * Set whether to ignore the max header size and not add entries to the
     * dynamic table when writing.
     */
    public void setNoIndexing(boolean flag) {
        noIndexing = flag;
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

    private static Header getLiteralHeaderField(ByteBuffer buf, byte opcode, int nbits, List<Header> dynamicTable) throws IOException {
        //System.err.println("  getLiteralHeaderField opcode="+String.format("%02x",opcode)+" nbits="+nbits);
        int index = decodeInteger(buf, opcode, nbits);
        //System.err.println("   index="+index);
        String name, value;
        byte b;
        if (index < 1) { // new name
            b = buf.get();
            boolean huffman = (b & 0x80) != 0;
            int nameLength = decodeInteger(buf, b, 7);
            //System.err.println("   new name nameLength="+nameLength+" huffman="+huffman);
            byte[] s = new byte[nameLength];
            buf.get(s);
            if (huffman) {
                s = HPACKHuffman.decode(s);
            }
            name = new String(s, "US-ASCII");
            //System.err.println("   name="+name);
        } else { // indexed name
            if (index < STATIC_TABLE_SIZE) {
                name = STATIC_TABLE.get(index).getName();
            } else {
                int dynamicIndex = index - STATIC_TABLE_SIZE;
                if (dynamicIndex < dynamicTable.size()) {
                    name = dynamicTable.get(dynamicIndex).getName();
                } else {
                    throw new IOException("Literal header index not in dynamic table: " + index);
                }
            }
            //System.err.println("   indexed name="+name);
        }
        // value
        b = buf.get();
        boolean huffman = (b & 0x80) != 0;
        int valueLength = decodeInteger(buf, b, 7);
        //System.err.println("   valueLength="+valueLength+" huffman="+huffman);
        byte[] s = new byte[valueLength];
        buf.get(s);
        if (huffman) {
            s = HPACKHuffman.decode(s);
        }
        value = new String(s, "US-ASCII");
        //System.err.println("   value="+value);
        return new Header(name, value);
    }

    /**
     * Decode an integer.
     * @param buf the buffer to read additional bytes from
     * @param opcode the opcode byte
     * @param nbits the number of bits not in the opcode
     */
    private static int decodeInteger(ByteBuffer buf, byte opcode, int nbits) {
        // Maximum value that fits in N bits
        int nmask = (1 << nbits) - 1; // same as Math.pow(2, nbits) - 1
        int value = opcode & nmask; // Called I in spec
        if (value < nmask) { // value fits in n bits
            return value;
        } else { // the N bits were all 1
            int shift = 0; // called M in spec
            byte b;
            do {
                b = buf.get(); // called B in spec
                // add the 7 least significant bits to value
                value += (b & 0x7f) * (1 << shift);
                shift += 7;
            } while ((b & 0x80) == 0x80); // continue while MSB is 1
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
                boolean inDynamicTable = false;
                if (index == -1) {
                    index = indexOfName(dynamicTable, name);
                    if (index != -1) {
                        inDynamicTable = true;
                        index += STATIC_TABLE_SIZE;
                    }
                }
                boolean overflow = noIndexing || tableSize(dynamicTable) + headerSize(header) > maxHeaderTableSize;
                if (index > 0) { // literal header field indexed
                    if (!overflow) { // with incremental indexing
                        encodeInteger(buf, (byte) 0x40, index, 6);
                    } else { // without indexing
                        encodeInteger(buf, (byte) 0, index, 4);
                    }
                } else { // literal header field new name
                    byte[] rname = name.getBytes(US_ASCII); // raw bytes
                    byte[] hname = HPACKHuffman.encode(rname); // Huffman encoded bytes
                    boolean useHuffman = autoHuffman && (hname.length < rname.length);
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
                boolean useHuffman = autoHuffman && (hvalue.length < rvalue.length);
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
            if (header != null && name.equals(header.getName())) {
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

    // Testing
    public static void main(String[] args) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(4096);
        HPACKHeaders hh;

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
        hh = new HPACKHeaders(buf, 4096);
        for (Header header : hh) {
            System.out.println(header.toString());
        }
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
