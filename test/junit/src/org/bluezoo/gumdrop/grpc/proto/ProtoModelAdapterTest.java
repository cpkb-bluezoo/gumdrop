/*
 * ProtoModelAdapterTest.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.grpc.proto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.telemetry.protobuf.ByteBufferChannel;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParser;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufWriter;
import org.junit.Test;

/**
 * Unit tests for ProtoModelAdapter and round-trip serialize/parse.
 */
public class ProtoModelAdapterTest {

    private static final String PROTO_SIMPLE = ""
            + "syntax = \"proto3\";\n"
            + "package example.v1;\n"
            + "message Simple {\n"
            + "  int32 x = 1;\n"
            + "  string s = 2;\n"
            + "  bool b = 3;\n"
            + "}\n";

    private static final String PROTO_NESTED = ""
            + "syntax = \"proto3\";\n"
            + "package example.v1;\n"
            + "message Inner {\n"
            + "  string value = 1;\n"
            + "}\n"
            + "message Outer {\n"
            + "  int32 id = 1;\n"
            + "  Inner inner = 2;\n"
            + "}\n";

    @Test
    public void testRoundTripSimple() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO_SIMPLE);

        ByteBuffer serialized = serialize(protoFile, "example.v1.Simple", (s, w) -> {
            s.startMessage(w, "example.v1.Simple");
            s.field(w, "x", 42);
            s.field(w, "s", "hello");
            s.field(w, "b", true);
            s.endMessage();
        });

        RecordingHandler handler = new RecordingHandler();
        ProtoModelAdapter adapter = new ProtoModelAdapter(protoFile, handler);
        ProtobufParser parser = new ProtobufParser(adapter);

        adapter.startRootMessage("example.v1.Simple");
        parser.receive(serialized);
        parser.close();
        adapter.endRootMessage();

        assertEquals(1, handler.starts.size());
        assertEquals("example.v1.Simple", handler.starts.get(0));
        assertEquals(3, handler.fields.size());
        assertEquals("x", handler.fields.get(0).name);
        assertEquals(42, handler.fields.get(0).value);
        assertEquals("s", handler.fields.get(1).name);
        assertEquals("hello", handler.fields.get(1).value);
        assertEquals("b", handler.fields.get(2).name);
        assertEquals(true, handler.fields.get(2).value);
        assertEquals(1, handler.ends);
    }

    @Test
    public void testRoundTripNested() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO_NESTED);

        ByteBuffer serialized = serialize(protoFile, "example.v1.Outer", (s, w) -> {
            s.startMessage(w, "example.v1.Outer");
            s.field(w, "id", 1);
            s.messageField(w, "inner", "example.v1.Inner", (s2, w2) -> {
                s2.startMessage(w2, "example.v1.Inner");
                s2.field(w2, "value", "nested");
                s2.endMessage();
            });
            s.endMessage();
        });

        RecordingHandler handler = new RecordingHandler();
        ProtoModelAdapter adapter = new ProtoModelAdapter(protoFile, handler);
        ProtobufParser parser = new ProtobufParser(adapter);

        adapter.startRootMessage("example.v1.Outer");
        parser.receive(serialized);
        parser.close();
        adapter.endRootMessage();

        assertEquals(2, handler.starts.size());
        assertEquals("example.v1.Outer", handler.starts.get(0));
        assertEquals("example.v1.Inner", handler.starts.get(1));
        assertEquals(1, handler.fieldStarts.size());
        assertEquals("inner", handler.fieldStarts.get(0).name);
        assertEquals("example.v1.Inner", handler.fieldStarts.get(0).typeName);
        assertEquals(2, handler.fields.size());
        FieldValue idField = handler.fields.stream().filter(f -> "id".equals(f.name)).findFirst().orElse(null);
        assertNotNull(idField);
        assertEquals(1, ((Number) idField.value).intValue());
        FieldValue valueField = handler.fields.stream().filter(f -> "value".equals(f.name)).findFirst().orElse(null);
        assertNotNull(valueField);
        assertEquals("nested", valueField.value);
        assertEquals(1, handler.fieldEnds);
        assertEquals(2, handler.ends);
    }

    @Test
    public void testParseEmptyMessage() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO_SIMPLE);

        ByteBuffer serialized = serialize(protoFile, "example.v1.Simple", (s, w) -> {
            s.startMessage(w, "example.v1.Simple");
            s.endMessage();
        });

        RecordingHandler handler = new RecordingHandler();
        ProtoModelAdapter adapter = new ProtoModelAdapter(protoFile, handler);
        ProtobufParser parser = new ProtobufParser(adapter);

        adapter.startRootMessage("example.v1.Simple");
        parser.receive(serialized);
        parser.close();
        adapter.endRootMessage();

        assertEquals(1, handler.starts.size());
        assertEquals(0, handler.fields.size());
        assertEquals(1, handler.ends);
    }

    private ByteBuffer serialize(ProtoFile protoFile, String typeName,
                                  SerializeCallback callback) throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);
        callback.serialize(serializer, writer);
        return channel.toByteBuffer();
    }

    @FunctionalInterface
    private interface SerializeCallback {
        void serialize(ProtoModelSerializer s, ProtobufWriter w) throws Exception;
    }

    private static class RecordingHandler extends ProtoDefaultHandler {
        final List<String> starts = new ArrayList<>();
        final List<FieldValue> fields = new ArrayList<>();
        final List<FieldStart> fieldStarts = new ArrayList<>();
        int fieldEnds;
        int ends;

        @Override
        public void startMessage(String typeName) {
            starts.add(typeName);
        }

        @Override
        public void endMessage() {
            ends++;
        }

        @Override
        public void field(String name, Object value) {
            fields.add(new FieldValue(name, value));
        }

        @Override
        public void startField(String name, String typeName) {
            fieldStarts.add(new FieldStart(name, typeName));
        }

        @Override
        public void endField() {
            fieldEnds++;
        }
    }

    private static class FieldValue {
        final String name;
        final Object value;

        FieldValue(String name, Object value) {
            this.name = name;
            this.value = value;
        }
    }

    private static class FieldStart {
        final String name;
        final String typeName;

        FieldStart(String name, String typeName) {
            this.name = name;
            this.typeName = typeName;
        }
    }
}
