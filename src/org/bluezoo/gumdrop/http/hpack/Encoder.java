/*
 * Encoder.java
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

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;

/**
 * An HPACK HTTP/2 header encoder.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc7541
 */
public class Encoder extends HPACKConstants {

    private static final Charset US_ASCII = Charset.forName("US-ASCII");

    /**
     * The dynamic table for this encoder.
     */
    private List<Header> dynamicTable = new ArrayList<>();

    /**
     * The SETTINGS_HEADER_TABLE_SIZE value from the SETTINGS frame.
     */
    private int headerTableSize;

    /**
     * The SETTINGS_MAX_HEADER_LIST_SIZE value from the SETTINGS frame.
     */
    private int maxHeaderListSize;

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
     * Constructor.
     * @param headerTableSize the maximum size for the dynamic table
     * @param maxHeaderListSize the maximum size of any list of headers to
     * encode
     */
    public Encoder(int headerTableSize, int maxHeaderListSize) {
        this.headerTableSize = headerTableSize;
        this.maxHeaderListSize = maxHeaderListSize;
    }

    /**
     * Sets a new value for SETTINGS_HEADER_TABLE_SIZE.
     * If this value is less than the current dynamic table size,
     * The first thing we need to do in an <code>encode</code> is to issue a
     * dynamic table size update and evict entries from the dynamic table.
     */
    public void setHeaderTableSize(int size) {
        headerTableSize = size;
    }

    /**
     * Sets the maximum uncompressed size of a complete header list.
     * If the list of headers supplied to <code>encode</code> exceeds this
     * value in size, it will throw a ProtocolException. The server should
     * then send a HPACK_DECOMPRESSION_FAILURE error and terminate the
     * connection.
     */
    public void setMaxHeaderListSize(int size) {
        maxHeaderListSize = size;
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
     * Writes the specified header list to a byte buffer.
     * @param buf the buffer to write to
     * @param headers the headers to write
     * @exception ProtocolException if the size of the headers exceeds the
     * maximum header list size
     */
    public void encode(ByteBuffer buf, List<Header> headers) throws ProtocolException {
        int totalSize = tableSize(headers);
        if (totalSize > maxHeaderListSize) {
            throw new ProtocolException("Total header size " + totalSize + " exceeds maximum header list size " + maxHeaderListSize);
        }
        int dynamicTableSize = tableSize(dynamicTable);
        if (dynamicTableSize > headerTableSize) {
            // Send dynamic table size update
            encodeInteger(buf, (byte) 0x20, headerTableSize, 5); // opcode
            // Evict entries until we comply
            while (dynamicTableSize > headerTableSize) {
                Header header = dynamicTable.remove(dynamicTable.size() - 1);
                dynamicTableSize -= headerSize(header);
            }
        }
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
                encodeInteger(buf, (byte) 0x80, index, 7); // opcode
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
                boolean indexed = !noIndexing && (tableSize(dynamicTable) + headerSize(header) <= headerTableSize);
                if (index > 0) { // literal header field indexed
                    if (indexed) { // with incremental indexing
                        encodeInteger(buf, (byte) 0x40, index, 6); // opcode
                    } else { // without indexing
                        encodeInteger(buf, (byte) 0, index, 4); // opcode
                    }
                } else { // literal header field new name
                    byte[] rname = name.toLowerCase().getBytes(US_ASCII); // raw bytes, always lowercase
                    byte[] hname = Huffman.encode(rname); // Huffman encoded bytes
                    boolean useHuffman = autoHuffman && (hname.length < rname.length);
                    byte hbit = (byte) (useHuffman ? 0x80 : 0);
                    int nameLength = useHuffman ? hname.length : rname.length;
                    if (indexed) { // with incremental indexing
                        encodeInteger(buf, (byte) 0x40, 0, 6); // opcode
                        encodeInteger(buf, hbit, nameLength, 7);
                    } else { // without indexing
                        encodeInteger(buf, (byte) 0, 0, 4); // opcode
                        encodeInteger(buf, hbit, nameLength, 7);
                    }
                    buf.put(useHuffman ? hname : rname);
                }
                // value payload
                byte[] rvalue = value.getBytes(US_ASCII); // raw bytes
                byte[] hvalue = Huffman.encode(rvalue); // huffman encoded bytes
                boolean useHuffman = autoHuffman && (hvalue.length < rvalue.length);
                byte hbit = (byte) (useHuffman ? 0x80 : 0);
                int valueLength = useHuffman ? hvalue.length : rvalue.length;
                encodeInteger(buf, hbit, valueLength, 7);
                buf.put(useHuffman ? hvalue : rvalue);
                if (indexed) {
                    // add to dynamic table
                    dynamicTable.add(0, header);
                }
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

    // Testing
    public static void main(String[] args) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(4096);
        Encoder encoder;
        byte b;

        /*// C.1.1 encode 10 using 5-bit prefix
        buf.clear();
        int val = decodeInteger(buf, (byte) 0x6a, 5);
        System.err.println("decodeInteger returned "+val+", should be 10");

        buf.clear();
        encodeInteger(buf, (byte) 0x60, 10, 5);
        buf.flip();
        b = buf.get();
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
        System.err.println("encodeInteger encoded "+sb.toString()+", should be 0x7f 0x9a 0x0a");*/

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
        encoder = new Encoder(4096, 4096);
        encoder.setAutoHuffman(false);
        encoder.encode(buf, Collections.singletonList(header));
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
        encoder = new Encoder(4096, 4096);
        encoder.setAutoHuffman(false);
        encoder.setNoIndexing(true);
        encoder.encode(buf, Collections.singletonList(header));
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

    }

}
