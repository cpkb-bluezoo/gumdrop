package org.bluezoo.gumdrop.telemetry;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.telemetry.protobuf.ByteBufferChannel;
import org.bluezoo.gumdrop.telemetry.protobuf.DefaultProtobufHandler;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParseException;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParser;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufHandler;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufWriter;

/**
 * JUnit 4 test class for ProtobufParser.
 * Tests the push-based protobuf parsing.
 */
public class ProtobufParserTest {

    // -- Basic field parsing tests --

    @Test
    public void testParseVarintField() throws Exception {
        // Write field 1 = 150
        ByteBuffer data = writeMessage(w -> w.writeVarintField(1, 150));

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(1, handler.varints.size());
        assertEquals(1, handler.varints.get(0).fieldNumber);
        assertEquals(150L, (long) handler.varints.get(0).value);
    }

    @Test
    public void testParseMultipleVarintFields() throws Exception {
        ByteBuffer data = writeMessage(w -> {
            w.writeVarintField(1, 42);
            w.writeVarintField(2, 100);
            w.writeVarintField(3, 256);
        });

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(3, handler.varints.size());
        assertEquals(1, handler.varints.get(0).fieldNumber);
        assertEquals(42L, (long) handler.varints.get(0).value);
        assertEquals(2, handler.varints.get(1).fieldNumber);
        assertEquals(100L, (long) handler.varints.get(1).value);
        assertEquals(3, handler.varints.get(2).fieldNumber);
        assertEquals(256L, (long) handler.varints.get(2).value);
    }

    @Test
    public void testParseBoolField() throws Exception {
        ByteBuffer data = writeMessage(w -> {
            w.writeBoolField(1, true);
            w.writeBoolField(2, false);
        });

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(2, handler.varints.size());
        assertEquals(1L, (long) handler.varints.get(0).value); // true
        assertEquals(0L, (long) handler.varints.get(1).value); // false
    }

    @Test
    public void testParseFixed64Field() throws Exception {
        ByteBuffer data = writeMessage(w -> w.writeFixed64Field(1, 0x0102030405060708L));

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(1, handler.fixed64s.size());
        assertEquals(1, handler.fixed64s.get(0).fieldNumber);
        assertEquals(0x0102030405060708L, (long) handler.fixed64s.get(0).value);
    }

    @Test
    public void testParseFixed32Field() throws Exception {
        ByteBuffer data = writeMessage(w -> w.writeFixed32Field(1, 0x01020304));

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(1, handler.fixed32s.size());
        assertEquals(1, handler.fixed32s.get(0).fieldNumber);
        assertEquals(0x01020304, (int) handler.fixed32s.get(0).value);
    }

    @Test
    public void testParseStringField() throws Exception {
        ByteBuffer data = writeMessage(w -> w.writeStringField(1, "hello"));

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(1, handler.bytes.size());
        assertEquals(1, handler.bytes.get(0).fieldNumber);
        assertEquals("hello", new String(handler.bytes.get(0).value, "UTF-8"));
    }

    @Test
    public void testParseBytesField() throws Exception {
        byte[] testData = new byte[] { 0x01, 0x02, 0x03, 0x04 };
        ByteBuffer data = writeMessage(w -> w.writeBytesField(1, testData));

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(1, handler.bytes.size());
        assertEquals(1, handler.bytes.get(0).fieldNumber);
        byte[] parsed = handler.bytes.get(0).value;
        assertEquals(4, parsed.length);
        assertEquals(0x01, parsed[0]);
        assertEquals(0x02, parsed[1]);
        assertEquals(0x03, parsed[2]);
        assertEquals(0x04, parsed[3]);
    }

    // -- Embedded message tests --

    @Test
    public void testParseEmbeddedMessage() throws Exception {
        ByteBuffer data = writeMessage(w -> {
            w.writeVarintField(1, 42); // outer field
            w.writeMessageField(2, inner -> {
                inner.writeStringField(1, "nested");
                inner.writeVarintField(2, 100);
            });
            w.writeVarintField(3, 99); // another outer field
        });

        MessageTrackingHandler handler = new MessageTrackingHandler();
        handler.messageFields.add(2); // field 2 is a message
        
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        // Check message events
        assertEquals(1, handler.messageStarts.size());
        assertEquals(2, (int) handler.messageStarts.get(0));
        assertEquals(1, handler.messageEnds);

        // Check parsed fields - push parser sees ALL varints including nested ones
        assertEquals(3, handler.varints.size()); // 42 (outer), 100 (inner), 99 (outer)
        assertEquals("nested", handler.lastString);
    }

    @Test
    public void testParseNestedMessages() throws Exception {
        // Use different field numbers for messages vs leaf fields
        ByteBuffer data = writeMessage(w -> {
            w.writeMessageField(1, level1 -> {
                level1.writeMessageField(2, level2 -> {
                    level2.writeStringField(3, "deepest");
                });
            });
        });

        MessageTrackingHandler handler = new MessageTrackingHandler();
        handler.messageFields.add(1); // field 1 is a message
        handler.messageFields.add(2); // field 2 is a message
        // field 3 is a string (not in messageFields)
        
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(data);
        parser.close();

        assertEquals(2, handler.messageStarts.size());
        assertEquals(2, handler.messageEnds);
        assertEquals("deepest", handler.lastString);
    }

    // -- Underflow tests --

    @Test
    public void testUnderflowVarint() throws Exception {
        // Write a multi-byte varint, then split it
        ByteBuffer data = writeMessage(w -> w.writeVarintField(1, 16384)); // 3-byte varint

        // Only provide partial data
        ByteBuffer partial = ByteBuffer.allocate(2);
        partial.put(data.get());
        partial.put(data.get());
        partial.flip();

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(partial);

        assertTrue(parser.isUnderflow());
        assertEquals(0, handler.varints.size()); // Nothing parsed yet
    }

    @Test
    public void testUnderflowRecovery() throws Exception {
        // Write two fields
        ByteBuffer data = writeMessage(w -> {
            w.writeVarintField(1, 16384);
            w.writeVarintField(2, 42);
        });

        // Split after first partial tag
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);

        // First chunk: just 2 bytes (partial)
        ByteBuffer chunk1 = ByteBuffer.wrap(bytes, 0, 2);
        // Second chunk: rest of data
        ByteBuffer chunk2 = ByteBuffer.wrap(bytes, 0, bytes.length);

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);

        parser.receive(chunk1);
        assertTrue(parser.isUnderflow());
        assertEquals(0, chunk1.position()); // Position should be at start of incomplete field

        // Simulate compact() + more data
        parser.receive(chunk2);
        assertFalse(parser.isUnderflow());
        parser.close();

        assertEquals(2, handler.varints.size());
    }

    @Test
    public void testCloseWithUnderflowThrows() throws Exception {
        ByteBuffer data = writeMessage(w -> w.writeStringField(1, "hello world"));

        // Only provide partial data
        ByteBuffer partial = ByteBuffer.allocate(3);
        partial.put(data.get());
        partial.put(data.get());
        partial.put(data.get());
        partial.flip();

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);
        parser.receive(partial);

        assertTrue(parser.isUnderflow());

        try {
            parser.close();
            fail("Expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            assertTrue(e.getMessage().contains("Incomplete"));
        }
    }

    // -- Error handling tests --

    @Test
    public void testInvalidFieldNumber() throws Exception {
        // Manually create a tag with field number 0 (invalid)
        ByteBuffer data = ByteBuffer.allocate(2);
        data.put((byte) 0x00); // tag with field 0, wire type 0
        data.put((byte) 0x01); // value
        data.flip();

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);

        try {
            parser.receive(data);
            fail("Expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            assertTrue(e.getMessage().contains("field number 0"));
        }
    }

    @Test
    public void testUnknownWireType() throws Exception {
        // Create a tag with wire type 3 (deprecated) or 4 (deprecated)
        ByteBuffer data = ByteBuffer.allocate(2);
        data.put((byte) 0x0B); // field 1, wire type 3
        data.put((byte) 0x00);
        data.flip();

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);

        try {
            parser.receive(data);
            fail("Expected ProtobufParseException");
        } catch (ProtobufParseException e) {
            assertTrue(e.getMessage().contains("wire type"));
        }
    }

    // -- Reset test --

    @Test
    public void testReset() throws Exception {
        ByteBuffer data1 = writeMessage(w -> w.writeVarintField(1, 42));
        ByteBuffer data2 = writeMessage(w -> w.writeVarintField(1, 99));

        RecordingHandler handler = new RecordingHandler();
        ProtobufParser parser = new ProtobufParser(handler);

        parser.receive(data1);
        parser.close();
        assertEquals(1, handler.varints.size());
        assertEquals(42L, (long) handler.varints.get(0).value);

        // Reset and parse new message
        parser.reset();
        handler.varints.clear();

        parser.receive(data2);
        parser.close();
        assertEquals(1, handler.varints.size());
        assertEquals(99L, (long) handler.varints.get(0).value);
    }

    // -- Helper methods --

    private interface MessageWriter {
        void write(ProtobufWriter writer) throws IOException;
    }

    private ByteBuffer writeMessage(MessageWriter writer) throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter protoWriter = new ProtobufWriter(channel);
        writer.write(protoWriter);
        return channel.toByteBuffer();
    }

    // -- Helper handler classes --

    private static class RecordingHandler extends DefaultProtobufHandler {
        final List<FieldValue<Long>> varints = new ArrayList<>();
        final List<FieldValue<Long>> fixed64s = new ArrayList<>();
        final List<FieldValue<Integer>> fixed32s = new ArrayList<>();
        final List<FieldValue<byte[]>> bytes = new ArrayList<>();

        @Override
        public void handleVarint(int fieldNumber, long value) {
            varints.add(new FieldValue<>(fieldNumber, value));
        }

        @Override
        public void handleFixed64(int fieldNumber, long value) {
            fixed64s.add(new FieldValue<>(fieldNumber, value));
        }

        @Override
        public void handleFixed32(int fieldNumber, int value) {
            fixed32s.add(new FieldValue<>(fieldNumber, value));
        }

        @Override
        public void handleBytes(int fieldNumber, ByteBuffer data) {
            byte[] arr = new byte[data.remaining()];
            data.get(arr);
            bytes.add(new FieldValue<>(fieldNumber, arr));
        }
    }

    private static class FieldValue<T> {
        final int fieldNumber;
        final T value;

        FieldValue(int fieldNumber, T value) {
            this.fieldNumber = fieldNumber;
            this.value = value;
        }
    }

    private static class MessageTrackingHandler extends DefaultProtobufHandler {
        final List<Integer> messageFields = new ArrayList<>();
        final List<Integer> messageStarts = new ArrayList<>();
        int messageEnds = 0;
        final List<FieldValue<Long>> varints = new ArrayList<>();
        String lastString;

        @Override
        public boolean isMessage(int fieldNumber) {
            return messageFields.contains(fieldNumber);
        }

        @Override
        public void startMessage(int fieldNumber) {
            messageStarts.add(fieldNumber);
        }

        @Override
        public void endMessage() {
            messageEnds++;
        }

        @Override
        public void handleVarint(int fieldNumber, long value) {
            varints.add(new FieldValue<>(fieldNumber, value));
        }

        @Override
        public void handleBytes(int fieldNumber, ByteBuffer data) {
            lastString = asString(data);
        }
    }
}

