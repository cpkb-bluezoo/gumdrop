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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
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
 * <p>This writer outputs to a {@link WritableByteChannel}, handling non-blocking
 * channels by retrying writes until all bytes are written. For writing to a
 * {@link ByteBuffer}, use {@link ByteBufferChannel}.
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

    private final WritableByteChannel channel;
    private final ByteBuffer writeBuffer;
    private long bytesWritten;

    /**
     * Creates a new ProtobufWriter that writes to the given channel.
     *
     * @param channel the channel to write to
     */
    public ProtobufWriter(WritableByteChannel channel) {
        this.channel = channel;
        this.writeBuffer = ByteBuffer.allocate(16); // Enough for any single primitive
        this.bytesWritten = 0;
    }

    /**
     * Returns the number of bytes written so far.
     *
     * @return the byte count
     */
    public long getBytesWritten() {
        return bytesWritten;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tag writing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a field tag.
     * Tag format: (fieldNumber &lt;&lt; 3) | wireType
     *
     * @param fieldNumber the field number (1-based)
     * @param wireType the wire type
     * @throws IOException if an I/O error occurs
     */
    public void writeTag(int fieldNumber, int wireType) throws IOException {
        writeVarint((fieldNumber << 3) | wireType);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Varint encoding
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes an unsigned varint (base-128 encoding).
     * Each byte has 7 bits of data and MSB as continuation bit.
     *
     * @param value the value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeVarint(long value) throws IOException {
        writeBuffer.clear();
        while (true) {
            if ((value & ~0x7FL) == 0) {
                writeBuffer.put((byte) value);
                break;
            }
            writeBuffer.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        writeBuffer.flip();
        writeToChannel(writeBuffer);
    }

    /**
     * Writes a signed varint using ZigZag encoding.
     * Maps signed to unsigned: 0→0, -1→1, 1→2, -2→3, etc.
     *
     * @param value the signed value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeSVarint(long value) throws IOException {
        writeVarint((value << 1) ^ (value >> 63));
    }

    /**
     * Writes a signed 32-bit varint using ZigZag encoding.
     *
     * @param value the signed value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeSVarint32(int value) throws IOException {
        writeVarint((value << 1) ^ (value >> 31));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fixed-size types
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a fixed 64-bit value in little-endian order.
     *
     * @param value the value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeFixed64(long value) throws IOException {
        writeBuffer.clear();
        writeBuffer.put((byte) (value & 0xFF));
        writeBuffer.put((byte) ((value >> 8) & 0xFF));
        writeBuffer.put((byte) ((value >> 16) & 0xFF));
        writeBuffer.put((byte) ((value >> 24) & 0xFF));
        writeBuffer.put((byte) ((value >> 32) & 0xFF));
        writeBuffer.put((byte) ((value >> 40) & 0xFF));
        writeBuffer.put((byte) ((value >> 48) & 0xFF));
        writeBuffer.put((byte) ((value >> 56) & 0xFF));
        writeBuffer.flip();
        writeToChannel(writeBuffer);
    }

    /**
     * Writes a fixed 32-bit value in little-endian order.
     *
     * @param value the value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeFixed32(int value) throws IOException {
        writeBuffer.clear();
        writeBuffer.put((byte) (value & 0xFF));
        writeBuffer.put((byte) ((value >> 8) & 0xFF));
        writeBuffer.put((byte) ((value >> 16) & 0xFF));
        writeBuffer.put((byte) ((value >> 24) & 0xFF));
        writeBuffer.flip();
        writeToChannel(writeBuffer);
    }

    /**
     * Writes a double value.
     *
     * @param value the value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeDouble(double value) throws IOException {
        writeFixed64(Double.doubleToRawLongBits(value));
    }

    /**
     * Writes a float value.
     *
     * @param value the value to write
     * @throws IOException if an I/O error occurs
     */
    public void writeFloat(float value) throws IOException {
        writeFixed32(Float.floatToRawIntBits(value));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Field writers (tag + value)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a varint field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeVarintField(int fieldNumber, long value) throws IOException {
        writeTag(fieldNumber, WIRETYPE_VARINT);
        writeVarint(value);
    }

    /**
     * Writes a signed varint field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeSVarintField(int fieldNumber, long value) throws IOException {
        writeTag(fieldNumber, WIRETYPE_VARINT);
        writeSVarint(value);
    }

    /**
     * Writes a boolean field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeBoolField(int fieldNumber, boolean value) throws IOException {
        writeTag(fieldNumber, WIRETYPE_VARINT);
        writeVarint(value ? 1 : 0);
    }

    /**
     * Writes a fixed64 field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeFixed64Field(int fieldNumber, long value) throws IOException {
        writeTag(fieldNumber, WIRETYPE_I64);
        writeFixed64(value);
    }

    /**
     * Writes a fixed32 field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeFixed32Field(int fieldNumber, int value) throws IOException {
        writeTag(fieldNumber, WIRETYPE_I32);
        writeFixed32(value);
    }

    /**
     * Writes a double field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeDoubleField(int fieldNumber, double value) throws IOException {
        writeTag(fieldNumber, WIRETYPE_I64);
        writeDouble(value);
    }

    /**
     * Writes a float field.
     *
     * @param fieldNumber the field number
     * @param value the value
     * @throws IOException if an I/O error occurs
     */
    public void writeFloatField(int fieldNumber, float value) throws IOException {
        writeTag(fieldNumber, WIRETYPE_I32);
        writeFloat(value);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Length-delimited types
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes raw bytes with length prefix.
     *
     * @param fieldNumber the field number
     * @param data the bytes to write
     * @throws IOException if an I/O error occurs
     */
    public void writeBytesField(int fieldNumber, byte[] data) throws IOException {
        if (data == null) {
            return;
        }
        writeTag(fieldNumber, WIRETYPE_LEN);
        writeVarint(data.length);
        writeToChannel(ByteBuffer.wrap(data));
    }

    /**
     * Writes a string field (UTF-8 encoded).
     *
     * @param fieldNumber the field number
     * @param value the string to write
     * @throws IOException if an I/O error occurs
     */
    public void writeStringField(int fieldNumber, String value) throws IOException {
        if (value == null) {
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeBytesField(fieldNumber, bytes);
    }

    /**
     * Writes an embedded message field.
     * The content is written using a callback that receives a nested writer.
     *
     * <p>Note: This method buffers the message content internally to compute
     * its length before writing, which is required by the protobuf wire format.
     *
     * @param fieldNumber the field number
     * @param content the message content writer
     * @throws IOException if an I/O error occurs
     */
    public void writeMessageField(int fieldNumber, MessageContent content) throws IOException {
        if (content == null) {
            return;
        }
        
        // Calculate message size by writing to a temporary buffer
        ByteBufferChannel tempChannel = new ByteBufferChannel(64 * 1024); // 64KB initial
        ProtobufWriter tempWriter = new ProtobufWriter(tempChannel);
        content.writeTo(tempWriter);

        ByteBuffer messageData = tempChannel.toByteBuffer();
        int messageSize = messageData.remaining();

        writeTag(fieldNumber, WIRETYPE_LEN);
        writeVarint(messageSize);
        writeToChannel(messageData);
    }

    /**
     * Writes length prefix and copies pre-encoded message bytes.
     * Use this when the message is already serialized.
     *
     * @param fieldNumber the field number
     * @param encodedMessage the pre-encoded message bytes
     * @throws IOException if an I/O error occurs
     */
    public void writeEncodedMessageField(int fieldNumber, ByteBuffer encodedMessage) throws IOException {
        if (encodedMessage == null || !encodedMessage.hasRemaining()) {
            return;
        }
        writeTag(fieldNumber, WIRETYPE_LEN);
        writeVarint(encodedMessage.remaining());
        writeToChannel(encodedMessage);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Channel writing with retry
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a buffer to the channel, retrying if the channel is non-blocking.
     *
     * @param buffer the buffer to write
     * @throws IOException if an I/O error occurs
     */
    private void writeToChannel(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written > 0) {
                bytesWritten += written;
            } else if (written == 0) {
                // Non-blocking channel not ready, yield and retry
                Thread.yield();
            }
            // written < 0 would indicate end of stream (shouldn't happen for writable)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static utilities
    // ─────────────────────────────────────────────────────────────────────────

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
         * @throws IOException if an I/O error occurs
         */
        void writeTo(ProtobufWriter writer) throws IOException;
    }
}
