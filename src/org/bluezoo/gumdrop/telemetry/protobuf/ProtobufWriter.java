/*
 * ProtobufWriter.java
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Zero-dependency Protobuf binary encoder.
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
public class ProtobufWriter {

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
    private boolean overflow;

    /**
     * Creates a new ProtobufWriter that writes to the given buffer.
     * The buffer should be in write mode (ready for put operations).
     *
     * @param buffer the buffer to write to
     */
    public ProtobufWriter(ByteBuffer buffer) {
        this.buffer = buffer;
        this.overflow = false;
    }

    /**
     * Returns the underlying buffer.
     */
    public ByteBuffer getBuffer() {
        return buffer;
    }

    /**
     * Returns true if an overflow occurred during writing.
     */
    public boolean isOverflow() {
        return overflow;
    }

    /**
     * Returns the write result.
     */
    public WriteResult getResult() {
        return overflow ? WriteResult.OVERFLOW : WriteResult.SUCCESS;
    }

    /**
     * Returns the number of bytes written so far.
     */
    public int getBytesWritten() {
        return buffer.position();
    }

    // -- Tag writing --

    /**
     * Writes a field tag.
     * Tag format: (fieldNumber << 3) | wireType
     *
     * @param fieldNumber the field number (1-based)
     * @param wireType the wire type
     * @return this writer for chaining
     */
    public ProtobufWriter writeTag(int fieldNumber, int wireType) {
        return writeVarint((fieldNumber << 3) | wireType);
    }

    // -- Varint encoding --

    /**
     * Writes an unsigned varint (base-128 encoding).
     * Each byte has 7 bits of data and MSB as continuation bit.
     *
     * @param value the value to write
     * @return this writer for chaining
     */
    public ProtobufWriter writeVarint(long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                writeByte((int) value);
                return this;
            }
            writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    /**
     * Writes a signed varint using ZigZag encoding.
     * Maps signed to unsigned: 0→0, -1→1, 1→2, -2→3, etc.
     *
     * @param value the signed value to write
     * @return this writer for chaining
     */
    public ProtobufWriter writeSVarint(long value) {
        return writeVarint((value << 1) ^ (value >> 63));
    }

    /**
     * Writes a signed 32-bit varint using ZigZag encoding.
     *
     * @param value the signed value to write
     * @return this writer for chaining
     */
    public ProtobufWriter writeSVarint32(int value) {
        return writeVarint((value << 1) ^ (value >> 31));
    }

    // -- Fixed-size types --

    /**
     * Writes a fixed 64-bit value in little-endian order.
     *
     * @param value the value to write
     * @return this writer for chaining
     */
    public ProtobufWriter writeFixed64(long value) {
        if (buffer.remaining() >= 8) {
            // Write in little-endian order
            writeByte((int) (value & 0xFF));
            writeByte((int) ((value >> 8) & 0xFF));
            writeByte((int) ((value >> 16) & 0xFF));
            writeByte((int) ((value >> 24) & 0xFF));
            writeByte((int) ((value >> 32) & 0xFF));
            writeByte((int) ((value >> 40) & 0xFF));
            writeByte((int) ((value >> 48) & 0xFF));
            writeByte((int) ((value >> 56) & 0xFF));
        } else {
            overflow = true;
        }
        return this;
    }

    /**
     * Writes a fixed 32-bit value in little-endian order.
     *
     * @param value the value to write
     * @return this writer for chaining
     */
    public ProtobufWriter writeFixed32(int value) {
        if (buffer.remaining() >= 4) {
            writeByte(value & 0xFF);
            writeByte((value >> 8) & 0xFF);
            writeByte((value >> 16) & 0xFF);
            writeByte((value >> 24) & 0xFF);
        } else {
            overflow = true;
        }
        return this;
    }

    /**
     * Writes a double value.
     *
     * @param value the value to write
     * @return this writer for chaining
     */
    public ProtobufWriter writeDouble(double value) {
        return writeFixed64(Double.doubleToRawLongBits(value));
    }

    /**
     * Writes a float value.
     *
     * @param value the value to write
     * @return this writer for chaining
     */
    public ProtobufWriter writeFloat(float value) {
        return writeFixed32(Float.floatToRawIntBits(value));
    }

    // -- Field writers (tag + value) --

    /**
     * Writes a varint field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @return this writer for chaining
     */
    public ProtobufWriter writeVarintField(int fieldNumber, long value) {
        writeTag(fieldNumber, WIRETYPE_VARINT);
        return writeVarint(value);
    }

    /**
     * Writes a signed varint field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @return this writer for chaining
     */
    public ProtobufWriter writeSVarintField(int fieldNumber, long value) {
        writeTag(fieldNumber, WIRETYPE_VARINT);
        return writeSVarint(value);
    }

    /**
     * Writes a boolean field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @return this writer for chaining
     */
    public ProtobufWriter writeBoolField(int fieldNumber, boolean value) {
        writeTag(fieldNumber, WIRETYPE_VARINT);
        return writeVarint(value ? 1 : 0);
    }

    /**
     * Writes a fixed64 field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @return this writer for chaining
     */
    public ProtobufWriter writeFixed64Field(int fieldNumber, long value) {
        writeTag(fieldNumber, WIRETYPE_I64);
        return writeFixed64(value);
    }

    /**
     * Writes a fixed32 field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @return this writer for chaining
     */
    public ProtobufWriter writeFixed32Field(int fieldNumber, int value) {
        writeTag(fieldNumber, WIRETYPE_I32);
        return writeFixed32(value);
    }

    /**
     * Writes a double field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @return this writer for chaining
     */
    public ProtobufWriter writeDoubleField(int fieldNumber, double value) {
        writeTag(fieldNumber, WIRETYPE_I64);
        return writeDouble(value);
    }

    /**
     * Writes a float field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @return this writer for chaining
     */
    public ProtobufWriter writeFloatField(int fieldNumber, float value) {
        writeTag(fieldNumber, WIRETYPE_I32);
        return writeFloat(value);
    }

    // -- Length-delimited types --

    /**
     * Writes raw bytes with length prefix.
     *
     * @param fieldNumber the field number
     * @param data the bytes to write
     * @return this writer for chaining
     */
    public ProtobufWriter writeBytesField(int fieldNumber, byte[] data) {
        if (data == null) {
            return this;
        }
        writeTag(fieldNumber, WIRETYPE_LEN);
        writeVarint(data.length);
        if (buffer.remaining() >= data.length) {
            buffer.put(data);
        } else {
            overflow = true;
        }
        return this;
    }

    /**
     * Writes a string field (UTF-8 encoded).
     *
     * @param fieldNumber the field number
     * @param value the string to write
     * @return this writer for chaining
     */
    public ProtobufWriter writeStringField(int fieldNumber, String value) {
        if (value == null) {
            return this;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return writeBytesField(fieldNumber, bytes);
    }

    /**
     * Writes an embedded message field.
     * The content is written using a callback that receives a nested writer.
     *
     * @param fieldNumber the field number
     * @param content the message content writer
     * @return this writer for chaining
     */
    public ProtobufWriter writeMessageField(int fieldNumber, MessageContent content) {
        if (content == null) {
            return this;
        }
        // Calculate message size by writing to a temporary buffer
        ByteBuffer temp = ByteBuffer.allocate(buffer.remaining());
        ProtobufWriter tempWriter = new ProtobufWriter(temp);
        content.writeTo(tempWriter);

        if (tempWriter.isOverflow()) {
            overflow = true;
            return this;
        }

        int messageSize = tempWriter.getBytesWritten();
        temp.flip();

        writeTag(fieldNumber, WIRETYPE_LEN);
        writeVarint(messageSize);

        if (buffer.remaining() >= messageSize) {
            buffer.put(temp);
        } else {
            overflow = true;
        }
        return this;
    }

    /**
     * Writes length prefix and copies pre-encoded message bytes.
     * Use this when the message is already serialized.
     *
     * @param fieldNumber the field number
     * @param encodedMessage the pre-encoded message bytes
     * @return this writer for chaining
     */
    public ProtobufWriter writeEncodedMessageField(int fieldNumber, ByteBuffer encodedMessage) {
        if (encodedMessage == null || !encodedMessage.hasRemaining()) {
            return this;
        }
        writeTag(fieldNumber, WIRETYPE_LEN);
        writeVarint(encodedMessage.remaining());
        if (buffer.remaining() >= encodedMessage.remaining()) {
            buffer.put(encodedMessage);
        } else {
            overflow = true;
        }
        return this;
    }

    // -- Helper methods --

    private void writeByte(int b) {
        if (buffer.hasRemaining()) {
            buffer.put((byte) b);
        } else {
            overflow = true;
        }
    }

    /**
     * Calculates the size of a varint in bytes.
     *
     * @param value the value
     * @return the size in bytes (1-10)
     */
    public static int varintSize(long value) {
        if ((value & (0xffffffffffffffffL << 7)) == 0) {
            return 1;
        }
        if ((value & (0xffffffffffffffffL << 14)) == 0) {
            return 2;
        }
        if ((value & (0xffffffffffffffffL << 21)) == 0) {
            return 3;
        }
        if ((value & (0xffffffffffffffffL << 28)) == 0) {
            return 4;
        }
        if ((value & (0xffffffffffffffffL << 35)) == 0) {
            return 5;
        }
        if ((value & (0xffffffffffffffffL << 42)) == 0) {
            return 6;
        }
        if ((value & (0xffffffffffffffffL << 49)) == 0) {
            return 7;
        }
        if ((value & (0xffffffffffffffffL << 56)) == 0) {
            return 8;
        }
        if ((value & (0xffffffffffffffffL << 63)) == 0) {
            return 9;
        }
        return 10;
    }

    /**
     * Interface for writing embedded message content.
     */
    public interface MessageContent {
        /**
         * Writes the message content to the given writer.
         *
         * @param writer the writer to write to
         */
        void writeTo(ProtobufWriter writer);
    }

}

