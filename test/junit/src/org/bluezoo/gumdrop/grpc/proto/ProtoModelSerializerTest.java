/*
 * ProtoModelSerializerTest.java
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.telemetry.protobuf.ByteBufferChannel;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufWriter;
import org.junit.Test;

/**
 * Unit tests for ProtoModelSerializer.
 */
public class ProtoModelSerializerTest {

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

    private static final String PROTO_SCALARS = ""
            + "syntax = \"proto3\";\n"
            + "package example.v1;\n"
            + "message AllScalars {\n"
            + "  int32 i32 = 1;\n"
            + "  int64 i64 = 2;\n"
            + "  float f = 3;\n"
            + "  double d = 4;\n"
            + "  bool b = 5;\n"
            + "  string str = 6;\n"
            + "  bytes by = 7;\n"
            + "}\n";

    @Test
    public void testSerializeInt32() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO_SIMPLE);
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);

        serializer.startMessage(writer, "example.v1.Simple");
        serializer.field(writer, "x", 42);
        serializer.endMessage();

        ByteBuffer result = channel.toByteBuffer();
        assertNotNull(result);
        assertTrue(result.remaining() > 0);
    }

    @Test
    public void testSerializeString() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO_SIMPLE);
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);

        serializer.startMessage(writer, "example.v1.Simple");
        serializer.field(writer, "s", "hello");
        serializer.endMessage();

        ByteBuffer result = channel.toByteBuffer();
        assertNotNull(result);
        assertTrue(result.remaining() > 0);
    }

    @Test
    public void testSerializeBool() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO_SIMPLE);
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);

        serializer.startMessage(writer, "example.v1.Simple");
        serializer.field(writer, "b", true);
        serializer.endMessage();

        ByteBuffer result = channel.toByteBuffer();
        assertNotNull(result);
    }

    @Test
    public void testSerializeMultipleFields() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO_SIMPLE);
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);

        serializer.startMessage(writer, "example.v1.Simple");
        serializer.field(writer, "x", 100);
        serializer.field(writer, "s", "world");
        serializer.field(writer, "b", false);
        serializer.endMessage();

        ByteBuffer result = channel.toByteBuffer();
        assertNotNull(result);
        assertTrue(result.remaining() > 0);
    }

    @Test
    public void testSerializeNestedMessage() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO_NESTED);
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);

        serializer.startMessage(writer, "example.v1.Outer");
        serializer.field(writer, "id", 1);
        serializer.messageField(writer, "inner", "example.v1.Inner", (s, w2) -> {
            s.startMessage(w2, "example.v1.Inner");
            s.field(w2, "value", "nested");
            s.endMessage();
        });
        serializer.endMessage();

        ByteBuffer result = channel.toByteBuffer();
        assertNotNull(result);
        assertTrue(result.remaining() > 0);
    }

    @Test
    public void testSerializeBytes() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO_SCALARS);
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);

        byte[] data = new byte[] { 1, 2, 3, 4, 5 };
        serializer.startMessage(writer, "example.v1.AllScalars");
        serializer.field(writer, "by", data);
        serializer.endMessage();

        ByteBuffer result = channel.toByteBuffer();
        assertNotNull(result);
    }

    @Test
    public void testSerializeIgnoresNull() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO_SIMPLE);
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);

        serializer.startMessage(writer, "example.v1.Simple");
        serializer.field(writer, "x", 1);
        serializer.field(writer, "s", null);
        serializer.field(writer, "b", true);
        serializer.endMessage();

        ByteBuffer result = channel.toByteBuffer();
        assertNotNull(result);
    }

    @Test
    public void testSerializeUnknownTypeThrows() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO_SIMPLE);
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter writer = new ProtobufWriter(channel);
        ProtoModelSerializer serializer = new ProtoModelSerializer(protoFile);

        try {
            serializer.startMessage(writer, "example.v1.UnknownMessage");
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Unknown"));
        }
    }
}
