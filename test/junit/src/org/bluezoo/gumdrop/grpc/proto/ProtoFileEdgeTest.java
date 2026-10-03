/*
 * ProtoFileEdgeTest.java
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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Edge cases of {@link ProtoFile} path resolution and builder defaults and of
 * {@link ProtoFileParser}: comment forms, option constants, qualified RPC
 * types, RPC bodies, map fields and malformed input.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ProtoFileEdgeTest {

    private static final String SERVICE = ""
            + "syntax = \"proto3\";\n"
            + "package pkg;\n"
            + "message Req { int32 id = 1; }\n"
            + "message Resp { string name = 1; }\n"
            + "service Svc { rpc Get(Req) returns (Resp); }\n";

    @Test
    public void rpcPathResolutionRejectsEveryMalformedOrUnknownPath() throws ProtoParseException {
        ProtoFile file = ProtoFileParser.parse(SERVICE);
        assertNull(file.getRpcByPath(null));
        assertNull(file.getRpcByPath("pkg.Svc/Get"));
        assertNull(file.getRpcByPath("/pkg.Svc"));
        assertNull(file.getRpcByPath("/pkg.Svc/"));
        assertNull(file.getRpcByPath("/other.Svc/Get"));
        assertNull(file.getRpcByPath("/pkg.Svc/Missing"));
        RpcDescriptor rpc = file.getRpcByPath("/pkg.Svc/Get");
        assertNotNull(rpc);
        assertEquals("pkg.Req", rpc.getInputTypeName());
        assertEquals("pkg.Resp", rpc.getOutputTypeName());
        assertFalse(rpc.isClientStreaming());
    }

    @Test
    public void builderDefaultsNullPackageAndSyntax() {
        ProtoFile file = ProtoFile.builder().packageName(null).syntax(null).build();
        assertEquals("", file.getPackageName());
        assertEquals("proto3", file.getSyntax());
        ProtoFile named = ProtoFile.builder().packageName("a.b").syntax("proto2").build();
        assertEquals("a.b", named.getPackageName());
        assertEquals("proto2", named.getSyntax());
    }

    @Test
    public void commentsAtTheEndOfInputAreAccepted() throws ProtoParseException {
        ProtoFile line = ProtoFileParser.parse("message A { int32 x = 1; } // trailing, no newline");
        assertNotNull(line.getMessage("A"));
        ProtoFile block = ProtoFileParser.parse("/* a\nmultiline\ncomment */ message D { /* inline */ int32 x = 1; }");
        assertNotNull(block.getMessage("D"));
    }

    @Test
    public void multiByteCharactersSplitAcrossReceivesAreDecodedIntact() throws Exception {
        byte[] bytes = "message Caf\u00e9 { int32 x = 1; }".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ProtoFileParser parser = new ProtoFileParser();
        for (int i = 0; i < bytes.length; i++) {
            parser.receive(java.nio.ByteBuffer.wrap(bytes, i, 1));
        }
        ProtoFile file = parser.close();
        assertNotNull(file.getMessage("Caf\u00e9"));
    }

    @Test
    public void optionConstantsOfEveryKindAreSkipped() throws ProtoParseException {
        ProtoFile file = ProtoFileParser.parse(""
                + "syntax = 'proto3';\n"
                + "option java_multiple_files = true;\n"
                + "option a = false;\n"
                + "option b = -5;\n"
                + "option c = +3;\n"
                + "option d = 'single';\n"
                + "option e = 99999999999;\n"
                + "option f = some.ident;\n"
                + "option g = { a: \"}\" b: { c: 1 } // }\n }; \n"
                + ";\n"
                + "message M { int32 x = 1 [deprecated = true, (custom).opt = 2]; }\n");
        assertNotNull(file.getMessage("M"));
    }

    @Test
    public void qualifiedRpcTypesAndRpcBodies() throws ProtoParseException {
        ProtoFile file = ProtoFileParser.parse(""
                + "package pkg;\n"
                + "service S {\n"
                + "  option deprecated = true;\n"
                + "  ;\n"
                + "  rpc A(.other.Req) returns (other.Resp);\n"
                + "  rpc B(stream .a.B) returns (stream C) { option deprecated = true; ; }\n"
                + "  rpc C(stream pkg.D) returns (E);\n"
                + "}\n");
        ServiceDescriptor svc = file.getService("pkg.S");
        RpcDescriptor a = svc.getRpc("A");
        assertEquals("other.Req", a.getInputTypeName());
        assertEquals("pkg.other.Resp", a.getOutputTypeName());
        RpcDescriptor b = svc.getRpc("B");
        assertTrue(b.isClientStreaming());
        assertTrue(b.isServerStreaming());
        assertEquals("a.B", b.getInputTypeName());
        assertEquals("pkg.C", b.getOutputTypeName());
        RpcDescriptor c = svc.getRpc("C");
        assertTrue(c.isClientStreaming());
        assertFalse(c.isServerStreaming());
    }

    @Test
    public void rpcWithoutPackageKeepsBareTypeNames() throws ProtoParseException {
        ProtoFile file = ProtoFileParser.parse("service S { rpc A(Req) returns (Resp); }");
        RpcDescriptor a = file.getService("S").getRpc("A");
        assertEquals("Req", a.getInputTypeName());
        assertEquals("Resp", a.getOutputTypeName());
    }

    @Test
    public void mapFieldsAndNestedTypesResolveTheirTypes() throws ProtoParseException {
        ProtoFile file = ProtoFileParser.parse(""
                + "package p.q;\n"
                + "enum Color { RED = 0; }\n"
                + "message Outer {\n"
                + "  map<string, int32> counts = 1;\n"
                + "  map<string, Outer> children = 2 [deprecated = true];\n"
                + "  map<int32, .p.q.Other> others = 3;\n"
                + "  Color color = 4;\n"
                + "  .p.q.Outer parent = 5;\n"
                + "  Inner inner = 6;\n"
                + "  optional string note = 7;\n"
                + "  repeated int64 ids = 8;\n"
                + "  reserved 9, 10 to 12, 15 to max;\n"
                + "  reserved \"old\", 'older';\n"
                + "  oneof choice { string s = 20; int32 i = 21; }\n"
                + "  message Inner { enum Kind { A = 0; } Kind kind = 1; }\n"
                + "  enum Mode { M = 0; }\n"
                + "}\n"
                + "message Other { }\n");
        MessageDescriptor outer = file.getMessage("p.q.Outer");
        assertEquals(FieldType.MAP, outer.getFieldByNumber(1).getType());
        assertEquals("int32", outer.getFieldByNumber(1).getValueTypeName());
        assertEquals("Outer", outer.getFieldByNumber(2).getValueTypeName());
        assertEquals(FieldType.ENUM, outer.getFieldByNumber(4).getType());
        assertEquals("p.q.Outer", outer.getFieldByNumber(5).getMessageTypeName());
        assertEquals("p.q.Inner", outer.getFieldByNumber(6).getMessageTypeName());
        assertEquals(FieldType.STRING, outer.getFieldByNumber(20).getType());
        assertEquals(1, outer.getNestedMessages().size());
        assertEquals(1, outer.getNestedEnums().size());
    }

    @Test
    public void fieldsOutsideAPackageKeepTheirTypeNames() throws ProtoParseException {
        ProtoFile file = ProtoFileParser.parse("message A { B b = 1; }");
        assertEquals("B", file.getMessage("A").getFieldByNumber(1).getMessageTypeName());
    }

    @Test
    public void importsAndStraySemicolonsAreTolerated() throws ProtoParseException {
        ProtoFile file = ProtoFileParser.parse("import \"other.proto\";;\nmessage A { ; int32 x = 1; ; }");
        assertNotNull(file.getMessage("A"));
    }

    private static void assertRejected(String proto) {
        try {
            ProtoFileParser.parse(proto);
            fail("accepted: " + proto);
        } catch (ProtoParseException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void malformedInputIsRejected() {
        assertRejected("syntax = \"proto9\";");
        assertRejected("syntax = proto3;");
        assertRejected("package ;");
        assertRejected("bogus A {}");
        assertRejected("message A { int32 x = 1; }\n/* never closed");
        assertRejected("message A { int32 x = 1; }\n/* never closed;");
        assertRejected("message {}");
        assertRejected("message A { int32 = 1; }");
        assertRejected("message A { int32 x = 1; int32 y = 1; }");
        assertRejected("message A { map<string, int32> = 1; }");
        assertRejected("message A { map<string, int32> m = 1; map<string, int32> n = 1; }");
        assertRejected("message A { int32 x = 99999999999999999999; }");
        assertRejected("message A { int32 x = -; }");
        assertRejected("message A { reserved ; }");
        assertRejected("enum { }");
        assertRejected("service S { rpc A(Req) returns (Resp) { bogus } }");
        assertRejected("service S { rpc A(Req) gives (Resp); }");
        assertRejected("service S { rpc A(Req) returns (Resp)");
        assertRejected("option g = { a: 1 ");
        assertRejected("option = 1;");
        assertRejected("message A { int32 x = 1 [deprecated true]; }");
    }
}
