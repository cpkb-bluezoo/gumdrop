/*
 * ProtobufReader.java
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

package org.bluezoo.gumdrop.telemetry.protobuf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Zero-dependency Protobuf binary decoder.
 * Implements the wire format as per https://protobuf.dev/programming-guides/encoding/
 *
 * <p>Wire types:
 * <ul>
 *   <li>0 = VARINT (int32, int64, uint32, uint64, sint32, sint64, bool, enum)</li>
 *   <li>1 = I64 (fixed64, sfixed64, double)</li>
 *   <li>2 = LEN (string, bytes, embedded messages, packed repeated fields)</li>
 *   <li>5 = I32 (fixed32, sfixed32, float)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ProtobufReader {

    /**
     * Wire type for variable-length integers.
     */
    public static final int WIRETYPE_VARINT = 0;

    /**
     * Wire type for 64-bit fixed values.
     */
    public static final int WIRETYPE_I64 = 1;

    /**
     * Wire type for length-delimited values (strings, bytes, messages).
     */
    public static final int WIRETYPE_LEN = 2;

    /**
     * Wire type for 32-bit fixed values.
     */
    public static final int WIRETYPE_I32 = 5;

    private final ByteBuffer buffer;

    /**
     * Creates a new ProtobufReader that reads from the given buffer.
     * The buffer should be in read mode (ready for get operations).
     *
     * @param buffer the buffer to read from
     */
    public ProtobufReader(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * Returns the underlying buffer.
     */
    public ByteBuffer getBuffer() {
        return buffer;
    }

    /**
     * Returns true if there are more bytes to read.
     */
    public boolean hasRemaining() {
        return buffer.hasRemaining();
    }

    /**
     * Returns the number of bytes remaining.
     */
    public int remaining() {
        return buffer.remaining();
    }

    // -- Tag reading --

    /**
     * Reads a field tag and returns it.
     * Extract field number with: tag >>> 3
     * Extract wire type with: tag & 0x07
     *
     * @return the tag value
     * @throws IOException if there is not enough data
     */
    public int readTag() throws IOException {
        return (int) readVarint();
    }

    /**
     * Extracts the field number from a tag.
     */
    public static int getFieldNumber(int tag) {
        return tag >>> 3;
    }

    /**
     * Extracts the wire type from a tag.
     */
    public static int getWireType(int tag) {
        return tag & 0x07;
    }

    // -- Varint decoding --

    /**
     * Reads an unsigned varint (base-128 decoding).
     *
     * @return the decoded value
     * @throws IOException if there is not enough data or the varint is malformed
     */
    public long readVarint() throws IOException {
        long result = 0;
        int shift = 0;
        while (true) {
            if (!buffer.hasRemaining()) {
                throw new IOException("Unexpected end of data reading varint");
            }
            byte b = buffer.get();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift >= 64) {
                throw new IOException("Varint too long");
            }
        }
    }

    /**
     * Reads a signed varint using ZigZag decoding.
     *
     * @return the decoded signed value
     * @throws IOException if there is not enough data
     */
    public long readSVarint() throws IOException {
        long n = readVarint();
        return (n >>> 1) ^ -(n & 1);
    }

    /**
     * Reads a 32-bit signed varint using ZigZag decoding.
     *
     * @return the decoded signed value
     * @throws IOException if there is not enough data
     */
    public int readSVarint32() throws IOException {
        int n = (int) readVarint();
        return (n >>> 1) ^ -(n & 1);
    }

    // -- Fixed-size types --

    /**
     * Reads a fixed 64-bit value in little-endian order.
     *
     * @return the value
     * @throws IOException if there is not enough data
     */
    public long readFixed64() throws IOException {
        if (buffer.remaining() < 8) {
            throw new IOException("Unexpected end of data reading fixed64");
        }
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long) (buffer.get() & 0xFF)) << (i * 8);
        }
        return result;
    }

    /**
     * Reads a fixed 32-bit value in little-endian order.
     *
     * @return the value
     * @throws IOException if there is not enough data
     */
    public int readFixed32() throws IOException {
        if (buffer.remaining() < 4) {
            throw new IOException("Unexpected end of data reading fixed32");
        }
        int result = 0;
        for (int i = 0; i < 4; i++) {
            result |= ((buffer.get() & 0xFF)) << (i * 8);
        }
        return result;
    }

    /**
     * Reads a double value.
     *
     * @return the value
     * @throws IOException if there is not enough data
     */
    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readFixed64());
    }

    /**
     * Reads a float value.
     *
     * @return the value
     * @throws IOException if there is not enough data
     */
    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readFixed32());
    }

    // -- Length-delimited types --

    /**
     * Reads a length-prefixed byte array.
     *
     * @return the byte array
     * @throws IOException if there is not enough data
     */
    public byte[] readBytes() throws IOException {
        int length = (int) readVarint();
        if (buffer.remaining() < length) {
            throw new IOException("Unexpected end of data reading bytes (need " + 
                    length + ", have " + buffer.remaining() + ")");
        }
        byte[] data = new byte[length];
        buffer.get(data);
        return data;
    }

    /**
     * Reads a length-prefixed UTF-8 string.
     *
     * @return the string
     * @throws IOException if there is not enough data
     */
    public String readString() throws IOException {
        byte[] bytes = readBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Reads a length-prefixed embedded message and returns a reader for it.
     *
     * @return a new ProtobufReader for the embedded message
     * @throws IOException if there is not enough data
     */
    public ProtobufReader readMessage() throws IOException {
        byte[] bytes = readBytes();
        return new ProtobufReader(ByteBuffer.wrap(bytes));
    }

    /**
     * Reads a boolean (varint that is 0 or 1).
     *
     * @return the boolean value
     * @throws IOException if there is not enough data
     */
    public boolean readBool() throws IOException {
        return readVarint() != 0;
    }

    // -- Skip methods --

    /**
     * Skips a field based on its wire type.
     *
     * @param wireType the wire type
     * @throws IOException if there is not enough data or unknown wire type
     */
    public void skipField(int wireType) throws IOException {
        switch (wireType) {
            case WIRETYPE_VARINT:
                readVarint();
                break;
            case WIRETYPE_I64:
                skip(8);
                break;
            case WIRETYPE_LEN:
                int length = (int) readVarint();
                skip(length);
                break;
            case WIRETYPE_I32:
                skip(4);
                break;
            default:
                throw new IOException("Unknown wire type: " + wireType);
        }
    }

    /**
     * Skips the specified number of bytes.
     *
     * @param count number of bytes to skip
     * @throws IOException if there are not enough bytes
     */
    public void skip(int count) throws IOException {
        if (buffer.remaining() < count) {
            throw new IOException("Cannot skip " + count + " bytes, only " + 
                    buffer.remaining() + " available");
        }
        buffer.position(buffer.position() + count);
    }

    /**
     * Creates a limited reader that can only read the specified number of bytes.
     * Useful for reading embedded messages when you know the length.
     *
     * @param length the maximum number of bytes to read
     * @return a new reader limited to the specified bytes
     * @throws IOException if there are not enough bytes
     */
    public ProtobufReader limit(int length) throws IOException {
        if (buffer.remaining() < length) {
            throw new IOException("Cannot limit to " + length + " bytes, only " + 
                    buffer.remaining() + " available");
        }
        ByteBuffer limited = buffer.slice();
        limited.limit(length);
        buffer.position(buffer.position() + length);
        return new ProtobufReader(limited);
    }

}

