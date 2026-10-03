/*
 * GrpcProtobufSweepTest.java
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

package org.bluezoo.gumdrop.grpc;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

import org.bluezoo.gumdrop.grpc.proto.ProtoFile;
import org.bluezoo.gumdrop.grpc.proto.ProtoFileParser;
import org.bluezoo.gumdrop.grpc.proto.ProtoMessageHandler;
import org.bluezoo.gumdrop.grpc.proto.ProtoModelAdapter;
import org.bluezoo.gumdrop.grpc.proto.ProtoParseException;
import org.bluezoo.gumdrop.grpc.proto.ProtoLocator;
import org.bluezoo.gumdrop.testsupport.TruncationSweep;
import org.bluezoo.protobuf.ProtobufParseException;
import org.bluezoo.protobuf.ProtobufParser;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Truncation, corruption and nesting-bomb sweeps over the protobuf request
 * path of the gRPC server: {@link ProtobufParser} driving a
 * {@link ProtoModelAdapter}. Only the exception types that
 * {@code GrpcHandler} turns into a 400 response may escape.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcProtobufSweepTest {

    private static final String PROTO = ""
            + "syntax = \"proto3\";\n"
            + "package t;\n"
            + "message Node {\n"
            + "  int32 id = 1;\n"
            + "  Node child = 2;\n"
            + "  string s = 3;\n"
            + "  bytes b = 4;\n"
            + "  double d = 5;\n"
            + "  fixed32 f = 6;\n"
            + "  sint64 z = 7;\n"
            + "}\n";

    /** Accepts everything. */
    private static final class NullHandler implements ProtoMessageHandler {
        public void setLocator(ProtoLocator locator) { }
        public void startMessage(String typeName) { }
        public void endMessage() { }
        public void field(String name, Object value) { }
        public void startField(String name, String typeName) { }
        public void endField() { }
    }

    private static TruncationSweep.Target target() throws Exception {
        final ProtoFile file = ProtoFileParser.parse(PROTO);
        return new TruncationSweep.Target() {
            @Override
            public void decode(byte[] input) throws Exception {
                ProtoModelAdapter adapter = new ProtoModelAdapter(file, new NullHandler());
                ProtobufParser parser = new ProtobufParser(adapter);
                adapter.startRootMessage("t.Node");
                parser.receive(ByteBuffer.wrap(input));
                parser.close();
                adapter.endRootMessage();
            }
        };
    }

    private static byte[] cat(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    @Test
    public void truncatedAndCorruptedMessagesOnlyThrowParseExceptions() throws Exception {
        byte[] inner = cat(0x08, 0x05, 0x1a, 0x02, 'h', 'i');
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(cat(0x08, 0x96, 0x01));
        out.write(0x12);
        out.write(inner.length);
        out.write(inner, 0, inner.length);
        out.write(cat(0x1a, 0x03, 'a', 'b', 'c'));
        out.write(cat(0x22, 0x02, 1, 2));
        out.write(cat(0x29, 1, 2, 3, 4, 5, 6, 7, 8));
        out.write(cat(0x35, 1, 2, 3, 4));
        out.write(cat(0x38, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01));
        List<String> failures = TruncationSweep.allFailures(out.toByteArray(), target(),
                ProtobufParseException.class, ProtoParseException.class);
        assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test
    public void deeplyNestedMessagesDoNotOverflowTheStack() throws Exception {
        byte[] body = new byte[0];
        for (int depth = 0; depth < 20000; depth++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x12);
            int len = body.length;
            while (len >= 0x80) {
                out.write((len & 0x7f) | 0x80);
                len >>>= 7;
            }
            out.write(len);
            out.write(body, 0, body.length);
            body = out.toByteArray();
        }
        List<String> failures = TruncationSweep.inputFailures(target(), body, "nest",
                ProtobufParseException.class, ProtoParseException.class);
        assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test
    public void hugeDeclaredLengthIsRejectedOrWaitedFor() throws Exception {
        byte[] huge = cat(0x1a, 0xff, 0xff, 0xff, 0xff, 0x07, 'x');
        List<String> failures = TruncationSweep.inputFailures(target(), huge, "huge",
                ProtobufParseException.class, ProtoParseException.class);
        assertTrue(failures.toString(), failures.isEmpty());
        byte[] negative = cat(0x1a, 0xff, 0xff, 0xff, 0xff, 0x0f, 'x');
        failures = TruncationSweep.inputFailures(target(), negative, "negative",
                ProtobufParseException.class, ProtoParseException.class);
        assertTrue(failures.toString(), failures.isEmpty());
    }
}
