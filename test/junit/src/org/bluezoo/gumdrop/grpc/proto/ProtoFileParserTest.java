/*
 * ProtoFileParserTest.java
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Unit tests for ProtoFileParser.
 */
public class ProtoFileParserTest {

    @Test
    public void testParseSimpleMessage() throws ProtoParseException {
        String proto = ""
                + "syntax = \"proto3\";\n"
                + "package example.v1;\n"
                + "message GetUserRequest {\n"
                + "  int32 user_id = 1;\n"
                + "}\n"
                + "message GetUserResponse {\n"
                + "  string name = 1;\n"
                + "  string email = 2;\n"
                + "}\n";

        ProtoFile file = ProtoFileParser.parse(proto);

        assertEquals("example.v1", file.getPackageName());
        assertEquals(2, file.getMessages().size());

        MessageDescriptor req = file.getMessage("example.v1.GetUserRequest");
        assertNotNull(req);
        assertEquals("GetUserRequest", req.getName());
        assertEquals(1, req.getFields().size());
        assertEquals("user_id", req.getFields().get(0).getName());
        assertEquals(1, req.getFields().get(0).getNumber());
        assertEquals(FieldType.INT32, req.getFields().get(0).getType());
    }

    @Test
    public void testParseService() throws ProtoParseException {
        String proto = ""
                + "syntax = \"proto3\";\n"
                + "package example.v1;\n"
                + "message GetUserRequest { int32 user_id = 1; }\n"
                + "message GetUserResponse { string name = 1; }\n"
                + "service UserService {\n"
                + "  rpc GetUser(GetUserRequest) returns (GetUserResponse);\n"
                + "}\n";

        ProtoFile file = ProtoFileParser.parse(proto);

        assertEquals(1, file.getServices().size());
        ServiceDescriptor svc = file.getService("example.v1.UserService");
        assertNotNull(svc);
        assertEquals(1, svc.getRpcs().size());
        RpcDescriptor rpc = svc.getRpc("GetUser");
        assertNotNull(rpc);
        assertEquals("example.v1.GetUserRequest", rpc.getInputTypeName());
        assertEquals("example.v1.GetUserResponse", rpc.getOutputTypeName());
        assertEquals("/example.v1.UserService/GetUser", svc.getRpcPath("GetUser"));
    }

    @Test
    public void testParseNestedMessage() throws ProtoParseException {
        String proto = ""
                + "syntax = \"proto3\";\n"
                + "package example.v1;\n"
                + "message Address {\n"
                + "  string street = 1;\n"
                + "  string city = 2;\n"
                + "}\n"
                + "message Person {\n"
                + "  string name = 1;\n"
                + "  Address address = 2;\n"
                + "}\n";

        ProtoFile file = ProtoFileParser.parse(proto);

        MessageDescriptor person = file.getMessage("example.v1.Person");
        assertNotNull(person);
        assertEquals(2, person.getFields().size());
        FieldDescriptor addrField = person.getFieldByNumber(2);
        assertNotNull(addrField);
        assertEquals(FieldType.MESSAGE, addrField.getType());
        assertEquals("example.v1.Address", addrField.getMessageTypeName());

        MessageDescriptor address = file.getMessage("example.v1.Address");
        assertNotNull(address);
        assertEquals(2, address.getFields().size());
    }

    @Test
    public void testParseEnum() throws ProtoParseException {
        String proto = ""
                + "syntax = \"proto3\";\n"
                + "package example.v1;\n"
                + "enum Status {\n"
                + "  UNKNOWN = 0;\n"
                + "  ACTIVE = 1;\n"
                + "  INACTIVE = 2;\n"
                + "}\n"
                + "message Item {\n"
                + "  string id = 1;\n"
                + "  Status status = 2;\n"
                + "}\n";

        ProtoFile file = ProtoFileParser.parse(proto);

        EnumDescriptor status = file.getEnum("example.v1.Status");
        assertNotNull(status);
        assertEquals("Status", status.getName());
        assertEquals(3, status.getValuesByNumber().size());
        assertEquals(Integer.valueOf(1), status.getValueNumber("ACTIVE"));
        assertEquals(Integer.valueOf(2), status.getValueNumber("INACTIVE"));

        MessageDescriptor item = file.getMessage("example.v1.Item");
        FieldDescriptor statusField = item.getFieldByNumber(2);
        assertNotNull(statusField);
        assertEquals(FieldType.ENUM, statusField.getType());
        assertEquals("example.v1.Status", statusField.getEnumTypeName());
    }

    @Test
    public void testParseMapField() throws ProtoParseException {
        String proto = ""
                + "syntax = \"proto3\";\n"
                + "package example.v1;\n"
                + "message Metadata {\n"
                + "  map<string, string> attrs = 1;\n"
                + "}\n";

        ProtoFile file = ProtoFileParser.parse(proto);

        MessageDescriptor metadata = file.getMessage("example.v1.Metadata");
        assertNotNull(metadata);
        FieldDescriptor attrs = metadata.getFieldByNumber(1);
        assertNotNull(attrs);
        assertEquals(FieldType.MAP, attrs.getType());
        assertEquals("string", attrs.getKeyTypeName());
        assertEquals("string", attrs.getValueTypeName());
    }

    @Test
    public void testParseOptionalAndRepeated() throws ProtoParseException {
        String proto = ""
                + "syntax = \"proto3\";\n"
                + "package example.v1;\n"
                + "message Example {\n"
                + "  optional int32 opt = 1;\n"
                + "  repeated string tags = 2;\n"
                + "}\n";

        ProtoFile file = ProtoFileParser.parse(proto);

        MessageDescriptor example = file.getMessage("example.v1.Example");
        FieldDescriptor opt = example.getFieldByNumber(1);
        assertTrue(opt.isOptional());
        assertEquals(FieldType.INT32, opt.getType());

        FieldDescriptor tags = example.getFieldByNumber(2);
        assertTrue(tags.isRepeated());
        assertEquals(FieldType.STRING, tags.getType());
    }

    @Test
    public void testParseReserved() throws ProtoParseException {
        String proto = ""
                + "syntax = \"proto3\";\n"
                + "package example.v1;\n"
                + "message Reserved {\n"
                + "  reserved 1, 2;\n"
                + "  reserved \"foo\", \"bar\";\n"
                + "  int32 value = 3;\n"
                + "}\n";

        ProtoFile file = ProtoFileParser.parse(proto);

        MessageDescriptor reserved = file.getMessage("example.v1.Reserved");
        assertNotNull(reserved);
        assertEquals(1, reserved.getFields().size());
        assertEquals(3, reserved.getFields().get(0).getNumber());
    }

    @Test
    public void testParseWithComments() throws ProtoParseException {
        String proto = ""
                + "syntax = \"proto3\";\n"
                + "// package comment\n"
                + "package example.v1;\n"
                + "/* block comment */\n"
                + "message Foo {\n"
                + "  int32 x = 1;  // inline\n"
                + "}\n";

        ProtoFile file = ProtoFileParser.parse(proto);

        assertEquals("example.v1", file.getPackageName());
        MessageDescriptor foo = file.getMessage("example.v1.Foo");
        assertNotNull(foo);
        assertEquals(1, foo.getFields().size());
    }

    @Test
    public void testParseStreamingReceive() throws ProtoParseException {
        String proto = "syntax = \"proto3\";\npackage p;\nmessage M { int32 x = 1; }\n";

        ProtoFileParser parser = new ProtoFileParser();
        parser.receive(ByteBuffer.wrap(proto.getBytes(StandardCharsets.UTF_8)));
        ProtoFile file = parser.close();

        assertNotNull(file);
        assertEquals("p", file.getPackageName());
        assertNotNull(file.getMessage("p.M"));
    }

    @Test
    public void testParseErrorInvalidSyntax() {
        String proto = "syntax = \"proto4\";\npackage p;\nmessage M {}\n";
        try {
            ProtoFileParser.parse(proto);
            fail("Expected ProtoParseException");
        } catch (ProtoParseException e) {
            assertTrue(e.getMessage().contains("syntax") || e.getMessage().contains("Invalid"));
        }
    }

    @Test
    public void testParseErrorDuplicateFieldNumber() {
        String proto = ""
                + "syntax = \"proto3\";\n"
                + "package p;\n"
                + "message M {\n"
                + "  int32 a = 1;\n"
                + "  int32 b = 1;\n"
                + "}\n";
        try {
            ProtoFileParser.parse(proto);
            fail("Expected ProtoParseException");
        } catch (ProtoParseException e) {
            assertTrue(e.getMessage().contains("1") || e.getMessage().contains("Duplicate"));
        }
    }

    @Test
    public void testParseErrorUnexpectedEof() {
        String proto = "syntax = \"proto3\";\npackage ";
        try {
            ProtoFileParser.parse(proto);
            fail("Expected ProtoParseException");
        } catch (ProtoParseException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testParseNoPackage() throws ProtoParseException {
        String proto = ""
                + "syntax = \"proto3\";\n"
                + "message TopLevel {\n"
                + "  int32 x = 1;\n"
                + "}\n";

        ProtoFile file = ProtoFileParser.parse(proto);

        assertTrue(file.getPackageName() == null || file.getPackageName().isEmpty());
        assertNotNull(file.getMessage("TopLevel"));
    }

    @Test
    public void testParseStreamingRpc() throws ProtoParseException {
        String proto = ""
                + "syntax = \"proto3\";\n"
                + "package example.v1;\n"
                + "message Req { int32 x = 1; }\n"
                + "message Resp { string y = 1; }\n"
                + "service S {\n"
                + "  rpc ClientStream(stream Req) returns (Resp);\n"
                + "  rpc ServerStream(Req) returns (stream Resp);\n"
                + "}\n";

        ProtoFile file = ProtoFileParser.parse(proto);

        ServiceDescriptor svc = file.getService("example.v1.S");
        RpcDescriptor clientStream = svc.getRpc("ClientStream");
        assertTrue(clientStream.isClientStreaming());
        assertFalse(clientStream.isServerStreaming());

        RpcDescriptor serverStream = svc.getRpc("ServerStream");
        assertFalse(serverStream.isClientStreaming());
        assertTrue(serverStream.isServerStreaming());
    }
}
