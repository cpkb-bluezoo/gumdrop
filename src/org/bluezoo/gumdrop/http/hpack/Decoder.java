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
import java.nio.charset.StandardCharsets;
import java.net.ProtocolException;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HeaderFieldHandler;

/**
 * HPACK header block decoder (RFC 7541).
 *
 * <p>Decodes a compressed field block into header fields, delivered to a
 * {@link HeaderFieldHandler} as the octets that were on the wire (RFC 7541
 * section 5.2 gives them no meaning). The decoder does not judge whether a
 * field is acceptable HTTP: it must consume the whole block to keep its
 * dynamic table in step with the peer's (RFC 9113 section 4.3), and the
 * receiver decides about a field afterwards. It uses:
 * <ul>
 * <li>Indexed header field representation (RFC 7541 section 6.1)</li>
 * <li>Literal header field with incremental indexing (section 6.2.1)</li>
 * <li>Literal header field without indexing (section 6.2.2)</li>
 * <li>Literal header field never indexed (section 6.2.3)</li>
 * <li>Dynamic table size update (section 6.3)</li>
 * <li>Integer representation (section 5.1)</li>
 * <li>String literal / Huffman decoding (section 5.2)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7541">RFC 7541</a>
 */
public class Decoder extends HpackConstants {

    /**
     * The dynamic table.
     */
    private final DecoderTable dynamicTable = new DecoderTable();

    /**
     * The static table as octets, built once from {@link #STATIC_TABLE}. A
     * name-only entry has an empty value.
     */
    private static final byte[][] STATIC_NAMES = new byte[STATIC_TABLE_SIZE][];
    private static final byte[][] STATIC_VALUES = new byte[STATIC_TABLE_SIZE][];

    static {
        for (int i = 0; i < STATIC_TABLE_SIZE; i++) {
            Header h = STATIC_TABLE.get(i);
            if (h == null) {
                // index 0 is not a valid HPACK index (RFC 7541 section 2.3.3)
                STATIC_NAMES[i] = new byte[0];
                STATIC_VALUES[i] = new byte[0];
                continue;
            }
            STATIC_NAMES[i] = h.getName().getBytes(StandardCharsets.ISO_8859_1);
            String v = h.getValue();
            STATIC_VALUES[i] = v == null
                    ? new byte[0] : v.getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * The negotiated maximum size of the dynamic table.
     * This corresponds to the SETTINGS_HEADER_TABLE_SIZE from the SETTINGS
     * frame.
     */
    private int headerTableSize;

    /**
     * The current maximum size of the dynamic table. It starts at the
     * negotiated maximum (RFC 7541 section 4.2) and the encoder can lower it,
     * but never raise it above that, with a dynamic table size update.
     */
    private int maxSize;

    /** RFC 9113 SETTINGS_MAX_HEADER_LIST_SIZE. */
    private int maxHeaderListSize = Integer.MAX_VALUE;

    /** Maximum single HPACK string literal length. */
    private static final int MAX_FIELD_LENGTH = 65536;

    private int decodedHeaderListSize;

    /**
     * Constructor.
     * @param headerTableSize the negotiated maximum size in bytes that the
     * dynamic table is allowed to reach
     */
    public Decoder(int headerTableSize) {
        this(headerTableSize, Integer.MAX_VALUE);
    }

    /**
     * Constructor with header list size limit.
     */
    public Decoder(int headerTableSize, int maxHeaderListSize) {
        this.headerTableSize = headerTableSize;
        this.maxSize = headerTableSize;
        this.maxHeaderListSize = maxHeaderListSize;
    }

    public void setMaxHeaderListSize(int size) {
        maxHeaderListSize = size;
    }

    /**
     * Set the value of the SETTINGS_HEADER_TABLE_SIZE setting.
     */
    public void setHeaderTableSize(int size) {
        headerTableSize = size;
    }

    /**
     * Decode an HPACK-encoded sequence of bytes aka header block.
     *
     * <p>Every field in the block is delivered to {@code handler} as octets;
     * none is refused for what it contains. An {@code IOException} means the
     * block itself is malformed or breaks a limit, which RFC 9113 section 4.3
     * makes a connection error.
     *
     * @param buf the header block
     * @param handler receives each decoded field
     */
    public void decode(ByteBuffer buf, HeaderFieldHandler handler) throws IOException {
        try {
            decodeUnchecked(buf, handler);
        } catch (java.nio.BufferUnderflowException e) {
            // A malformed field can claim more bytes than the block
            // actually has (e.g. truncated mid-field); report it as a
            // decode error rather than letting an unchecked NIO exception
            // escape past this method's declared IOException contract.
            throw new IOException("HPACK: unexpected end of header block", e);
        }
    }

    private void decodeUnchecked(ByteBuffer buf, HeaderFieldHandler handler) throws IOException {
        decodedHeaderListSize = 0;
        while (buf.hasRemaining()) {
            byte b = buf.get();
            byte[] name;
            byte[] value;
            if ((b & 0x80) != 0) { // RFC 7541 section 6.1: indexed header field
                int index = decodeInteger(buf, b, 7);
                if (index == 0) {
                    // see section 6.1
                    throw new ProtocolException("HPACK indexed header field with index 0");
                } else if (index < STATIC_TABLE_SIZE) {
                    name = STATIC_NAMES[index];
                    value = STATIC_VALUES[index];
                } else if ((index - STATIC_TABLE_SIZE) < dynamicTable.size()) {
                    TableEntry entry = dynamicTable.get(index - STATIC_TABLE_SIZE);
                    name = entry.name;
                    value = entry.value;
                } else {
                    throw new ProtocolException("HPACK indexed header field index out of range: "+index);
                }
                // RFC 7541 section 4.1: each emitted header counts against the
                // header list size regardless of representation type.
                addToHeaderListSize(name.length + value.length + TableEntry.OVERHEAD);
            } else if ((b & 0x40) != 0) { // RFC 7541 section 6.2.1: literal with incremental indexing
                TableEntry entry = getLiteralHeaderField(buf, b, 6);
                name = entry.name;
                value = entry.value;
                // Evict older entries as needed and prepend (RFC 7541 sections
                // 4.4, 3.2, 2.3.2). The table tracks its own running size, so
                // this is O(entries-evicted) rather than O(entries^2).
                dynamicTable.insert(entry, maxSize);
            } else if ((b & 0x20) != 0) { // RFC 7541 section 6.3: dynamic table size update
                int newMax = decodeInteger(buf, b, 5);
                if (newMax > headerTableSize) {
                    throw new ProtocolException("dynamic table size update "+ newMax + " larger than SETTINGS_HEADER_TABLE_SIZE "+headerTableSize);
                }
                // evict entries: RFC 7541 section 4.3
                dynamicTable.evictToFit(newMax);
                this.maxSize = newMax;
                continue;
            } else { // RFC 7541 section 6.2.2/6.2.3: literal without indexing / never indexed
                TableEntry entry = getLiteralHeaderField(buf, b, 4);
                name = entry.name;
                value = entry.value;
                // do not add to the dynamic table
            }
            handler.field(ByteBuffer.wrap(name).asReadOnlyBuffer(),
                    ByteBuffer.wrap(value).asReadOnlyBuffer());
        }
    }

    private static void checkFieldLength(int length, int remaining) throws ProtocolException {
        if (length < 0 || length > remaining || length > MAX_FIELD_LENGTH) {
            throw new ProtocolException("Invalid HPACK string length: " + length);
        }
    }

    /**
     * Reads a string literal (RFC 7541 section 5.2) and returns its octets,
     * Huffman-decoded if the representation says so.
     */
    private static byte[] readString(ByteBuffer buf) throws IOException {
        byte b = buf.get();
        boolean huffman = (b & 0x80) != 0;
        int length = decodeInteger(buf, b, 7);
        checkFieldLength(length, buf.remaining());
        byte[] s = new byte[length];
        buf.get(s);
        return huffman ? Huffman.decode(s) : s;
    }

    /**
     * Reads a literal header field representation: a name (new, or by index
     * into either table) and a value, as octets. Nothing about the content
     * is checked; that is for the receiver.
     */
    private TableEntry getLiteralHeaderField(ByteBuffer buf, byte opcode, int nbits) throws IOException {
        int index = decodeInteger(buf, opcode, nbits);
        byte[] name;
        if (index < 1) { // new name
            name = readString(buf);
        } else { // indexed name
            if (index < STATIC_TABLE_SIZE) {
                name = STATIC_NAMES[index];
            } else {
                int dynamicIndex = index - STATIC_TABLE_SIZE;
                if (dynamicIndex < dynamicTable.size()) {
                    name = dynamicTable.get(dynamicIndex).name;
                } else {
                    throw new IOException("Literal header index not in dynamic table: " + index);
                }
            }
        }
        byte[] value = readString(buf);
        addToHeaderListSize(name.length + value.length + TableEntry.OVERHEAD);
        return new TableEntry(name, value);
    }

    private void addToHeaderListSize(int added) throws ProtocolException {
        decodedHeaderListSize += added;
        if (decodedHeaderListSize > maxHeaderListSize) {
            throw new ProtocolException("Header list size exceeds limit");
        }
    }

    /**
     * Decode an integer.
     * @param buf the buffer to read additional bytes from
     * @param opcode the opcode byte
     * @param nbits the number of bits not in the opcode
     */
    private static int decodeInteger(ByteBuffer buf, byte opcode, int nbits)
            throws ProtocolException {
        // Maximum value that fits in N bits
        int nmask = (1 << nbits) - 1; // same as Math.pow(2, nbits) - 1
        int value = opcode & nmask; // Called I in spec
        if (value < nmask) { // value fits in n bits
            return value;
        } else { // the N bits were all 1
            int shift = 0; // called M in spec
            byte b;
            do {
                if (shift > 28) {
                    // RFC 7541 section 5.1: integers MUST NOT exceed 2^31-1;
                    // 5 continuation bytes (shift=28 after 4) would overflow int.
                    throw new ProtocolException("HPACK integer overflow");
                }
                b = buf.get(); // called B in spec
                // add the 7 least significant bits to value
                value += (b & 0x7f) << shift;
                if (value < 0) {
                    throw new ProtocolException("HPACK integer overflow");
                }
                shift += 7;
            } while ((b & 0x80) == 0x80); // continue while MSB is 1
            return value;
        }
    }

}
