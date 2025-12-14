package org.bluezoo.gumdrop.telemetry;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.telemetry.protobuf.ByteBufferChannel;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufWriter;

/**
 * JUnit 4 test class for ProtobufWriter.
 * Tests the binary protobuf encoding following the wire format specification
 * from https://protobuf.dev/programming-guides/encoding/
 */
public class ProtobufWriterTest {

    // -- Varint encoding tests --

    @Test
    public void testVarintSmallValue() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        // Value 1 should encode to single byte 0x01
        writer.writeVarint(1);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(1, buffer.remaining());
        assertEquals((byte) 0x01, buffer.get());
    }

    @Test
    public void testVarintValue150() throws IOException {
        // From protobuf encoding spec: 150 encodes to 0x9601
        // 150 = 10010110 binary
        // Split into 7-bit chunks: 0010110 (22) and 0000001 (1)
        // With continuation bits: 10010110 (0x96) and 00000001 (0x01)
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        writer.writeVarint(150);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(2, buffer.remaining());
        assertEquals((byte) 0x96, buffer.get());
        assertEquals((byte) 0x01, buffer.get());
    }

    @Test
    public void testVarintValue300() throws IOException {
        // 300 = 100101100 binary
        // Split into 7-bit chunks: 0101100 (44) and 0000010 (2)
        // With continuation bits: 10101100 (0xAC) and 00000010 (0x02)
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        writer.writeVarint(300);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(2, buffer.remaining());
        assertEquals((byte) 0xAC, buffer.get());
        assertEquals((byte) 0x02, buffer.get());
    }

    @Test
    public void testVarintLargeValue() throws IOException {
        // Test a larger value that requires more bytes
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        // 16384 = 2^14 requires 3 bytes
        writer.writeVarint(16384);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(3, buffer.remaining());
        // 16384 = 0x4000 = 100 0000 0000 0000 binary
        // 7-bit chunks: 0000000, 0000000, 0000001
        // With continuation: 10000000, 10000000, 00000001
        assertEquals((byte) 0x80, buffer.get());
        assertEquals((byte) 0x80, buffer.get());
        assertEquals((byte) 0x01, buffer.get());
    }

    @Test
    public void testVarintMaxInt() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        writer.writeVarint(Integer.MAX_VALUE);

        ByteBuffer buffer = channel.toByteBuffer();
        // Max int (2^31 - 1) requires 5 bytes
        assertEquals(5, buffer.remaining());
    }

    @Test
    public void testVarintMaxLong() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        writer.writeVarint(Long.MAX_VALUE);

        ByteBuffer buffer = channel.toByteBuffer();
        // Max long requires 9 bytes
        assertEquals(9, buffer.remaining());
    }

    // -- ZigZag encoding tests --

    @Test
    public void testSVarintZigZag() throws IOException {
        // ZigZag encoding: 0 -> 0, -1 -> 1, 1 -> 2, -2 -> 3
        ByteBufferChannel channel;
        ProtobufWriter writer;
        ByteBuffer buffer;

        // 0 encodes to 0
        channel = new ByteBufferChannel(16);
        writer = new ProtobufWriter(channel);
        writer.writeSVarint(0);
        buffer = channel.toByteBuffer();
        assertEquals((byte) 0x00, buffer.get());

        // -1 encodes to 1
        channel = new ByteBufferChannel(16);
        writer = new ProtobufWriter(channel);
        writer.writeSVarint(-1);
        buffer = channel.toByteBuffer();
        assertEquals((byte) 0x01, buffer.get());

        // 1 encodes to 2
        channel = new ByteBufferChannel(16);
        writer = new ProtobufWriter(channel);
        writer.writeSVarint(1);
        buffer = channel.toByteBuffer();
        assertEquals((byte) 0x02, buffer.get());

        // -2 encodes to 3
        channel = new ByteBufferChannel(16);
        writer = new ProtobufWriter(channel);
        writer.writeSVarint(-2);
        buffer = channel.toByteBuffer();
        assertEquals((byte) 0x03, buffer.get());
    }

    // -- Fixed-size type tests --

    @Test
    public void testFixed64() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        // Little-endian encoding
        writer.writeFixed64(0x0102030405060708L);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(8, buffer.remaining());
        // Should be in little-endian order
        assertEquals((byte) 0x08, buffer.get());
        assertEquals((byte) 0x07, buffer.get());
        assertEquals((byte) 0x06, buffer.get());
        assertEquals((byte) 0x05, buffer.get());
        assertEquals((byte) 0x04, buffer.get());
        assertEquals((byte) 0x03, buffer.get());
        assertEquals((byte) 0x02, buffer.get());
        assertEquals((byte) 0x01, buffer.get());
    }

    @Test
    public void testFixed32() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        // Little-endian encoding
        writer.writeFixed32(0x01020304);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(4, buffer.remaining());
        assertEquals((byte) 0x04, buffer.get());
        assertEquals((byte) 0x03, buffer.get());
        assertEquals((byte) 0x02, buffer.get());
        assertEquals((byte) 0x01, buffer.get());
    }

    // -- Tag encoding tests --

    @Test
    public void testTagEncoding() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        // Field 1, wire type VARINT (0): (1 << 3) | 0 = 0x08
        writer.writeTag(1, ProtobufWriter.WIRETYPE_VARINT);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals((byte) 0x08, buffer.get());
    }

    @Test
    public void testTagEncodingField2Len() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        // Field 2, wire type LEN (2): (2 << 3) | 2 = 0x12
        writer.writeTag(2, ProtobufWriter.WIRETYPE_LEN);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals((byte) 0x12, buffer.get());
    }

    @Test
    public void testTagEncodingLargeFieldNumber() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        // Field 16, wire type VARINT (0): (16 << 3) | 0 = 128 = 0x80 0x01
        writer.writeTag(16, ProtobufWriter.WIRETYPE_VARINT);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(2, buffer.remaining());
        assertEquals((byte) 0x80, buffer.get());
        assertEquals((byte) 0x01, buffer.get());
    }

    // -- Field writer tests --

    @Test
    public void testVarintField() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        // Field 1 = 150
        writer.writeVarintField(1, 150);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(3, buffer.remaining());
        assertEquals((byte) 0x08, buffer.get()); // tag: field 1, varint
        assertEquals((byte) 0x96, buffer.get()); // value: 150 (byte 1)
        assertEquals((byte) 0x01, buffer.get()); // value: 150 (byte 2)
    }

    @Test
    public void testBoolField() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        writer.writeBoolField(1, true);
        writer.writeBoolField(2, false);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(4, buffer.remaining());
        assertEquals((byte) 0x08, buffer.get()); // tag: field 1, varint
        assertEquals((byte) 0x01, buffer.get()); // true
        assertEquals((byte) 0x10, buffer.get()); // tag: field 2, varint
        assertEquals((byte) 0x00, buffer.get()); // false
    }

    @Test
    public void testStringField() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(32);
        ProtobufWriter writer = new ProtobufWriter(channel);

        writer.writeStringField(1, "hello");

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(7, buffer.remaining());
        assertEquals((byte) 0x0A, buffer.get()); // tag: field 1, LEN
        assertEquals((byte) 0x05, buffer.get()); // length: 5
        assertEquals((byte) 'h', buffer.get());
        assertEquals((byte) 'e', buffer.get());
        assertEquals((byte) 'l', buffer.get());
        assertEquals((byte) 'l', buffer.get());
        assertEquals((byte) 'o', buffer.get());
    }

    @Test
    public void testBytesField() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(32);
        ProtobufWriter writer = new ProtobufWriter(channel);

        byte[] data = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        writer.writeBytesField(1, data);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(6, buffer.remaining());
        assertEquals((byte) 0x0A, buffer.get()); // tag: field 1, LEN
        assertEquals((byte) 0x04, buffer.get()); // length: 4
        assertEquals((byte) 0x01, buffer.get());
        assertEquals((byte) 0x02, buffer.get());
        assertEquals((byte) 0x03, buffer.get());
        assertEquals((byte) 0x04, buffer.get());
    }

    @Test
    public void testFixed64Field() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        writer.writeFixed64Field(1, 0x0807060504030201L);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(9, buffer.remaining());
        assertEquals((byte) 0x09, buffer.get()); // tag: field 1, I64
        // Little-endian value
        assertEquals((byte) 0x01, buffer.get());
        assertEquals((byte) 0x02, buffer.get());
        assertEquals((byte) 0x03, buffer.get());
        assertEquals((byte) 0x04, buffer.get());
        assertEquals((byte) 0x05, buffer.get());
        assertEquals((byte) 0x06, buffer.get());
        assertEquals((byte) 0x07, buffer.get());
        assertEquals((byte) 0x08, buffer.get());
    }

    // -- Embedded message tests --

    @Test
    public void testMessageField() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(64);
        ProtobufWriter writer = new ProtobufWriter(channel);

        // Write an embedded message with field 1 = "test"
        writer.writeMessageField(1, new TestMessageContent());

        ByteBuffer buffer = channel.toByteBuffer();
        // tag (1 byte) + length (1 byte) + inner tag (1 byte) + inner length (1 byte) + "test" (4 bytes)
        assertEquals(8, buffer.remaining());
        assertEquals((byte) 0x0A, buffer.get()); // tag: field 1, LEN
        assertEquals((byte) 0x06, buffer.get()); // length of inner message
        assertEquals((byte) 0x0A, buffer.get()); // inner tag: field 1, LEN
        assertEquals((byte) 0x04, buffer.get()); // inner length: 4
        assertEquals((byte) 't', buffer.get());
        assertEquals((byte) 'e', buffer.get());
        assertEquals((byte) 's', buffer.get());
        assertEquals((byte) 't', buffer.get());
    }

    private static class TestMessageContent implements ProtobufWriter.MessageContent {
        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            writer.writeStringField(1, "test");
        }
    }

    // -- Varint size calculation tests --

    @Test
    public void testVarintSize() {
        assertEquals(1, ProtobufWriter.varintSize(0));
        assertEquals(1, ProtobufWriter.varintSize(1));
        assertEquals(1, ProtobufWriter.varintSize(127));
        assertEquals(2, ProtobufWriter.varintSize(128));
        assertEquals(2, ProtobufWriter.varintSize(150));
        assertEquals(2, ProtobufWriter.varintSize(16383));
        assertEquals(3, ProtobufWriter.varintSize(16384));
        assertEquals(5, ProtobufWriter.varintSize(Integer.MAX_VALUE));
        assertEquals(9, ProtobufWriter.varintSize(Long.MAX_VALUE));
    }

    // -- Null handling tests --

    @Test
    public void testNullString() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        // Null strings should be skipped
        writer.writeStringField(1, null);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(0, buffer.remaining());
    }

    @Test
    public void testNullBytes() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        // Null bytes should be skipped
        writer.writeBytesField(1, (byte[]) null);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(0, buffer.remaining());
    }

    // -- Double and Float tests --

    @Test
    public void testDoubleField() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        writer.writeDoubleField(1, 3.14159);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(9, buffer.remaining()); // tag (1) + 8 bytes
        assertEquals((byte) 0x09, buffer.get()); // tag: field 1, I64
    }

    @Test
    public void testFloatField() throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(16);
        ProtobufWriter writer = new ProtobufWriter(channel);

        writer.writeFloatField(1, 3.14f);

        ByteBuffer buffer = channel.toByteBuffer();
        assertEquals(5, buffer.remaining()); // tag (1) + 4 bytes
        assertEquals((byte) 0x0D, buffer.get()); // tag: field 1, I32
    }
}
