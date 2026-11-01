/*
 * ProtoStreamingFieldsTest.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.protobuf.ByteBufferChannel;
import org.bluezoo.protobuf.ProtobufParser;
import org.bluezoo.protobuf.ProtobufWriter;
import org.junit.Test;

/**
 * Tests that a ProtoMessageHandler can ask for a bytes or string field to
 * be streamed, and receives it in pieces rather than as one value.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ProtoStreamingFieldsTest {

    private static final String PROTO = ""
            + "syntax = \"proto3\";\n"
            + "package example.v1;\n"
            + "message Blob {\n"
            + "  int32 id = 1;\n"
            + "  bytes data = 2;\n"
            + "  string note = 3;\n"
            + "}\n";

    private ByteBuffer blob(byte[] data, String note) throws Exception {
        ByteBufferChannel channel = new ByteBufferChannel(256);
        ProtobufWriter w = new ProtobufWriter(channel);
        w.writeVarintField(1, 7);
        w.writeBytesField(2, data);
        w.writeBytesField(3, note.getBytes(StandardCharsets.UTF_8));
        return channel.toByteBuffer();
    }

    @Test
    public void testStreamedFieldArrivesInPieces() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO);
        byte[] data = new byte[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        ByteBuffer wire = blob(data, "hello");

        final List<String> events = new ArrayList<>();
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        ProtoDefaultHandler handler = new ProtoDefaultHandler() {
            @Override public boolean isStreaming(String name) {
                return "data".equals(name);
            }
            @Override public void startBytes(String name, int length) {
                events.add("start " + name + " " + length);
            }
            @Override public void bytesContent(ByteBuffer d) {
                while (d.hasRemaining()) {
                    content.write(d.get());
                }
            }
            @Override public void endBytes() {
                events.add("end");
            }
            @Override public void field(String n, Object v) {
                events.add("field " + n + "=" + v);
            }
        };
        ProtoModelAdapter adapter = new ProtoModelAdapter(protoFile, handler);
        ProtobufParser parser = new ProtobufParser(adapter);
        adapter.startRootMessage("example.v1.Blob");
        // feed in small chunks, so the field spans several
        int chunk = 64;
        ByteBuffer carry = ByteBuffer.allocate(0);
        while (wire.hasRemaining()) {
            int n = Math.min(chunk, wire.remaining());
            ByteBuffer piece = ByteBuffer.allocate(carry.remaining() + n);
            piece.put(carry);
            for (int i = 0; i < n; i++) {
                piece.put(wire.get());
            }
            piece.flip();
            parser.receive(piece);
            carry = ByteBuffer.allocate(piece.remaining());
            carry.put(piece);
            carry.flip();
        }
        parser.close();
        adapter.endRootMessage();

        assertEquals("[field id=7, start data 1000, end, field note=hello]",
                events.toString());
        assertEquals(1000, content.size());
        byte[] got = content.toByteArray();
        for (int i = 0; i < got.length; i++) {
            assertEquals((byte) i, got[i]);
        }
    }

    @Test
    public void testFieldNotStreamedWhenHandlerDeclines() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO);
        ByteBuffer wire = blob(new byte[] { 1, 2, 3 }, "x");
        final List<String> events = new ArrayList<>();
        ProtoDefaultHandler handler = new ProtoDefaultHandler() {
            @Override public void field(String n, Object v) {
                events.add(n + (v instanceof byte[] ? "[" + ((byte[]) v).length + "]" : "=" + v));
            }
        };
        ProtoModelAdapter adapter = new ProtoModelAdapter(protoFile, handler);
        ProtobufParser parser = new ProtobufParser(adapter);
        adapter.startRootMessage("example.v1.Blob");
        parser.receive(wire);
        parser.close();
        adapter.endRootMessage();
        assertEquals("[id=7, data[3], note=x]", events.toString());
    }

    @Test
    public void testStreamedStringDeliversUtf8Bytes() throws Exception {
        ProtoFile protoFile = ProtoFileParser.parse(PROTO);
        ByteBuffer wire = blob(new byte[0], "héllo");
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        final List<String> events = new ArrayList<>();
        ProtoDefaultHandler handler = new ProtoDefaultHandler() {
            @Override public boolean isStreaming(String name) {
                return "note".equals(name);
            }
            @Override public void startBytes(String name, int length) {
                events.add("start " + name + " " + length);
            }
            @Override public void bytesContent(ByteBuffer d) {
                while (d.hasRemaining()) {
                    content.write(d.get());
                }
            }
        };
        ProtoModelAdapter adapter = new ProtoModelAdapter(protoFile, handler);
        ProtobufParser parser = new ProtobufParser(adapter);
        adapter.startRootMessage("example.v1.Blob");
        parser.receive(wire);
        parser.close();
        adapter.endRootMessage();
        assertEquals("[start note 6]", events.toString());
        assertEquals("héllo", new String(content.toByteArray(), StandardCharsets.UTF_8));
    }
}
