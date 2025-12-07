/*
 * Decoder.java
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
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.util.ByteArrays;

/**
 * An HPACK HTTP/2 header block decoder.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc7541
 */
public class Decoder extends HPACKConstants {

    /**
     * The dynamic table.
     */
    private List<Header> dynamicTable = new ArrayList<>();

    /**
     * The negotiated maximum size of the dynamic table.
     * This corresponds to the SETTINGS_HEADER_TABLE_SIZE from the SETTINGS
     * frame.
     */
    private int headerTableSize;

    /**
     * The current maximum size of the dynamic table. This can be lower but
     * not higher than the negotiated maximum size.
     */
    private int maxSize = Integer.MAX_VALUE;

    /**
     * Constructor.
     * @param headerTableSize the negotiated maximum size in bytes that the
     * dynamic table is allowed to reach
     */
    public Decoder(int headerTableSize) {
        this.headerTableSize = headerTableSize;
    }

    /**
     * Set the value of the SETTINGS_HEADER_TABLE_SIZE setting.
     */
    public void setHeaderTableSize(int size) {
        headerTableSize = size;
    }

    /**
     * Decode an HPACK-encoded sequence of bytes aka header block.
     * @param buf the header block
     */
    public void decode(ByteBuffer buf, HeaderHandler handler) throws IOException {
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
                        header = new Header(header.getName(), "");
                        //throw new ProtocolException("HPACK indexed header field does not reference name+value");
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
                while (tableSize(dynamicTable) > (maxSize - newEntrySize)) {
                    dynamicTable.remove(dynamicTable.size() - 1);
                }
                // The header field is added to the beginning of the dynamic
                // table, see sections 3.2, 2.3.2
                dynamicTable.add(0, header);
            } else if ((b & 0x20) != 0) { // dynamic table size update
                int maxSize = decodeInteger(buf, b, 5);
                //System.err.println(" dynamic table size update, maxSize="+maxSize);
                if (maxSize > headerTableSize) {
                    throw new ProtocolException("dynamic table size update "+ maxSize + " larger than SETTINGS_HEADER_TABLE_SIZE "+headerTableSize);
                }
                // evict entries: RFC 7541 section 4.3
                while (tableSize(dynamicTable) > maxSize) {
                    dynamicTable.remove(dynamicTable.size() - 1);
                }
                this.maxSize = maxSize;
                continue;
            } else { // literal header field never indexed
                // OR literal header field without indexing
                //System.err.println(" literal header field without indexing");
                header = getLiteralHeaderField(buf, b, 4, dynamicTable);
                // do not add to dynamicTable
            }
            //System.err.println("  header="+header);
            handler.header(header);
        }
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
                s = Huffman.decode(s);
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
            s = Huffman.decode(s);
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

    // Testing
    public static void main(String[] args) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(4096);
        Decoder decoder;
        List<Header> decoded = new ArrayList<>();
        HeaderHandler handler = new HeaderHandler() {
            public void header(Header header) {
                decoded.add(header);
            }
        };

        /*// C.2.1 Literal Header Field with Indexing
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
        decoder = new Decoder(4096);
        decoder.decode(buf, handler);
        System.err.println(header + " should be "+decoded.get(0));
        decoded.clear();

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
        decoder = new Decoder(4096);
        decoder.decode(buf, handler);
        System.err.println(header + " should be "+decoded.get(0));
        decoded.clear();

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
        decoder = new Decoder(4096);
        decoder.decode(buf, handler);
        System.err.println(header + " should be "+decoded.get(0));
        decoded.clear();*/

        // Process arg
        handler = new HeaderHandler() {
            public void header(Header header) {
                System.out.println(header.toString());
            }
        };
        byte[] encoded = ByteArrays.toByteArray(args[0]);
        buf.clear();
        buf.put(encoded);
        buf.flip();
        decoder = new Decoder(4096);
        decoder.decode(buf, handler);
    }


}
