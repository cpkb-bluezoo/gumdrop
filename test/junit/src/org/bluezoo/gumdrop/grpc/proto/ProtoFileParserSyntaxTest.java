/*
 * ProtoFileParserSyntaxTest.java
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Broad syntax coverage for {@link ProtoFileParser}: every statement kind,
 * option and constant forms, comment styles, type resolution, and the
 * malformed-input error branches.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ProtoFileParserSyntaxTest {

    private static final String RICH = ""
            + "// leading comment\n"
            + "/* block\n comment */\n"
            + "syntax = \"proto3\";\n"
            + ";\n"
            + "package acme.v1;\n"
            + "import \"other.proto\";\n"
            + "option java_package = 'com.acme';\n"
            + "option deprecated = true;\n"
            + "option optimize = SPEED;\n"
            + "option level = -3;\n"
            + "option off = false;\n"
            + "enum Color {\n"
            + "  option allow_alias = true;\n"
            + "  reserved 9;\n"
            + "  RED = 0;\n"
            + "  GREEN = 1 [deprecated = true];\n"
            + "  ;\n"
            + "}\n"
            + "message Outer {\n"
            + "  option deprecated = false;\n"
            + "  reserved 1, 2, 5 to 7, \"old\", 'older';\n"
            + "  string name = 10 [json_name = \"n\", deprecated = true];\n"
            + "  repeated int32 ids = 11;\n"
            + "  optional bool flag = 12;\n"
            + "  Color color = 13;\n"
            + "  Inner inner = 14;\n"
            + "  .acme.v1.Inner other = 15;\n"
            + "  map<string, Inner> by_name = 16;\n"
            + "  map<int32, string> labels = 17;\n"
            + "  oneof choice {\n"
            + "    int64 as_int = 20;\n"
            + "    string as_str = 21;\n"
            + "  }\n"
            + "  ;\n"
            + "  message Inner { sint64 v = 1; }\n"
            + "  enum Mode { A = 0; B = 1; }\n"
            + "  Mode mode = 22;\n"
            + "}\n"
            + "message Inner { fixed32 a = 1; sfixed64 b = 2; double d = 3; float f = 4; bytes raw = 5;"
            + " uint32 u = 6; uint64 w = 7; sint32 s = 8; fixed64 g = 9; sfixed32 h = 10; int64 l = 11; }\n"
            + "service Api {\n"
            + "  option deprecated = true;\n"
            + "  ;\n"
            + "  rpc Unary (Outer) returns (Inner);\n"
            + "  rpc Up (stream Outer) returns (Inner) { option idem = true; }\n"
            + "  rpc Down (Outer) returns (stream .acme.v1.Inner);\n"
            + "  rpc Both (stream other.Msg) returns (stream other.Msg);\n"
            + "  rpc Dotted (pkg.Req) returns (pkg.Res) {}\n"
            + "}\n";

    private static void assertRejected(String source) {
        try {
            ProtoFileParser.parse(source);
            fail("expected ProtoParseException for: " + source);
        } catch (ProtoParseException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void richFileParsesIntoModel() throws Exception {
        ProtoFile f = ProtoFileParser.parse(RICH);
        assertEquals("acme.v1", f.getPackageName());
        assertEquals(2, f.getMessages().size());
        assertEquals(1, f.getEnums().size());
        assertEquals(1, f.getServices().size());

        MessageDescriptor outer = f.getMessage("acme.v1.Outer");
        assertNotNull(outer);
        assertEquals(FieldType.STRING, outer.getFieldByNumber(10).getType());
        assertTrue(outer.getFieldByNumber(11).isRepeated());
        assertTrue(outer.getFieldByNumber(12).isOptional());
        assertEquals(FieldType.ENUM, outer.getFieldByNumber(13).getType());
        assertEquals("acme.v1.Color", outer.getFieldByNumber(13).getEnumTypeName());
        assertEquals(FieldType.MESSAGE, outer.getFieldByNumber(14).getType());
        assertEquals("acme.v1.Inner", outer.getFieldByNumber(15).getMessageTypeName());
        assertEquals(FieldType.MAP, outer.getFieldByNumber(16).getType());
        assertEquals("string", outer.getFieldByNumber(16).getKeyTypeName());
        assertEquals("Inner", outer.getFieldByNumber(16).getValueTypeName());
        assertEquals(FieldType.MAP, outer.getFieldByNumber(17).getType());
        assertEquals(FieldType.INT64, outer.getFieldByNumber(20).getType());
        assertEquals(FieldType.STRING, outer.getFieldByNumber(21).getType());
        assertEquals(1, outer.getNestedMessages().size());
        assertEquals(1, outer.getNestedEnums().size());
        assertNull(outer.getFieldByNumber(99));

        EnumDescriptor color = f.getEnum("acme.v1.Color");
        assertEquals("GREEN", color.getValueName(1));
        assertEquals(Integer.valueOf(0), color.getValueNumber("RED"));

        ServiceDescriptor api = f.getService("acme.v1.Api");
        assertEquals(5, api.getRpcs().size());
        assertTrue(api.getRpc("Up").isClientStreaming());
        assertFalse(api.getRpc("Up").isServerStreaming());
        assertTrue(api.getRpc("Down").isServerStreaming());
        assertEquals("acme.v1.Inner", api.getRpc("Down").getOutputTypeName());
        assertTrue(api.getRpc("Both").isClientStreaming());
        assertTrue(api.getRpc("Both").isServerStreaming());
        assertEquals("acme.v1.other.Msg", api.getRpc("Both").getInputTypeName());
        assertEquals("acme.v1.pkg.Req", api.getRpc("Dotted").getInputTypeName());
        assertEquals("/acme.v1.Api/Unary", api.getRpcPath("Unary"));
        assertNotNull(f.getRpcByPath("/acme.v1.Api/Down"));
    }

    @Test
    public void reservedRangesUseToAndMax() throws Exception {
        ProtoFile f = ProtoFileParser.parse(
                "message M { reserved 2 to 4, 10 to max, 20; int32 a = 1; }"
                + " enum E { reserved 5 to 6; A = 0; }");
        assertNotNull(f.getMessage("M").getFieldByNumber(1));
        assertEquals("A", f.getEnum("E").getValueName(0));
    }

    @Test
    public void multipleFieldOptionsAreCommaSeparated() throws Exception {
        ProtoFile f = ProtoFileParser.parse(
                "message M { string a = 1 [deprecated = true, json_name = \"x\"]; }"
                + " enum E { A = 0 [deprecated = true, other = 1]; }");
        assertEquals("a", f.getMessage("M").getFieldByNumber(1).getName());
        assertEquals("A", f.getEnum("E").getValueName(0));
    }

    @Test
    public void fullyQualifiedFieldTypeWithLeadingDot() throws Exception {
        ProtoFile f = ProtoFileParser.parse(
                "package p; message A { int32 x = 1; } message B { .p.A a = 1; repeated .p.A more = 2; }");
        FieldDescriptor a = f.getMessage("p.B").getFieldByNumber(1);
        assertEquals(FieldType.MESSAGE, a.getType());
        assertEquals("p.A", a.getMessageTypeName());
        assertTrue(f.getMessage("p.B").getFieldByNumber(2).isRepeated());
    }

    @Test
    public void rpcOptionsBlockMayFollowWhitespace() throws Exception {
        ProtoFile f = ProtoFileParser.parse(
                "service S {\n  rpc A (X) returns (Y) {\n    option deprecated = true;\n  }\n"
                + "  rpc B (X) returns (Y) { }\n  rpc C (X) returns (Y)\n  ;\n}");
        assertEquals(3, f.getService("S").getRpcs().size());
    }

    private static final String HTTP_API = ""
            + "syntax = \"proto3\";\npackage api.v1;\n"
            + "import \"google/api/annotations.proto\";\n"
            + "option (my.file_opt) = { a: 1 b: { c: \"}\" } };\n"
            + "message Req { option (my.msg_opt) = { x: \"a{b\\\"}\" }; string id = 1 [(my.opt).sub = 1, (.abs.opt) = true, (v) = { k: 2 }]; }\n"
            + "enum E { option (my.enum_opt) = {}; A = 0 [(ev) = { z: 1 }]; }\n"
            + "message Res { string v = 1; }\n"
            + "service S {\n"
            + "  option (my.svc_opt) = { n: 1 };\n"
            + "  rpc Get (Req) returns (Res) {\n"
            + "    option (google.api.http) = {\n"
            + "      get: \"/v1/{id}\"  // a } comment\n"
            + "      /* block } */ body: \"*\"\n"
            + "      additional_bindings { post: \"/v1/x\" body: \"*\" }\n"
            + "    };\n"
            + "    option deprecated = true;\n"
            + "  }\n"
            + "}\n";

    @Test
    public void extensionAndAggregateOptionsAreAccepted() throws Exception {
        ProtoFile f = ProtoFileParser.parse(HTTP_API);
        assertEquals(1, f.getService("api.v1.S").getRpcs().size());
        assertEquals("id", f.getMessage("api.v1.Req").getFieldByNumber(1).getName());
        assertEquals("A", f.getEnum("api.v1.E").getValueName(0));
    }

    @Test
    public void extensionAndAggregateOptionsSurviveOneByteFeeds() throws Exception {
        ProtoFileParser p = new ProtoFileParser();
        byte[] all = HTTP_API.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < all.length; i++) {
            p.receive(ByteBuffer.wrap(all, i, 1));
        }
        assertNotNull(p.close().getService("api.v1.S"));
    }

    @Test
    public void invalidOptionAndFieldSyntaxIsRejected() {
        assertRejected("option = 1;");
        assertRejected("message M { int32 = 1; }");
        assertRejected("message M { map<string, string> = 1; }");
        assertRejected("message M { reserved ; }");
        assertRejected("enum E { reserved ; A = 0; }");
        assertRejected("option () = 1;");
        assertRejected("option (a.b = 1;");
        assertRejected("option (a) = { x: 1 ;");
        assertRejected("option (a) = { x: \"unterminated };");
        assertRejected("message M { int32 a = 1 [(x) = ]; }");
    }

    @Test
    public void rejectionMessagesCarryLineNumber() {
        try {
            ProtoFileParser.parse("\n\noption = 1;");
            fail("expected ProtoParseException");
        } catch (ProtoParseException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("line 3"));
        }
    }

    @Test
    public void proto2SyntaxAccepted() throws Exception {
        ProtoFile f = ProtoFileParser.parse("syntax = \"proto2\"; message M { optional int32 a = 1; }");
        assertEquals("", f.getPackageName());
        assertNotNull(f.getMessage("M"));
    }

    @Test
    public void emptyAndCommentOnlySourcesParse() throws Exception {
        assertTrue(ProtoFileParser.parse("").getMessages().isEmpty());
        assertTrue(ProtoFileParser.parse("// nothing\n/* at all */").getMessages().isEmpty());
        assertTrue(ProtoFileParser.parse("   \n\t\r\n ; ;").getServices().isEmpty());
    }

    @Test
    public void escapesInStringsAreDecoded() throws Exception {
        ProtoFile f = ProtoFileParser.parse(
                "option a = \"x\\ny\\tz\\rq\\\\w\\'e\\\"r\\u\";\nmessage M { int32 a = 1; }");
        assertNotNull(f.getMessage("M"));
    }

    @Test
    public void chunkedReceiveAssemblesInput() throws Exception {
        ProtoFileParser p = new ProtoFileParser();
        byte[] all = RICH.getBytes(StandardCharsets.UTF_8);
        int step = 7;
        for (int i = 0; i < all.length; i += step) {
            int n = Math.min(step, all.length - i);
            p.receive(ByteBuffer.wrap(all, i, n));
        }
        ProtoFile f = p.close();
        assertNotNull(f.getMessage("acme.v1.Outer"));
    }

    @Test
    public void closedParserRejectsFurtherUse() throws Exception {
        ProtoFileParser p = new ProtoFileParser();
        p.receive(ByteBuffer.wrap("message M { int32 a = 1; }".getBytes(StandardCharsets.UTF_8)));
        p.close();
        try {
            p.receive(ByteBuffer.allocate(1));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            p.close();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void malformedTopLevelRejected() {
        assertRejected("syntax = \"proto9\";");
        assertRejected("syntax = proto3;");
        assertRejected("syntax \"proto3\";");
        assertRejected("package ;");
        assertRejected("package a.b");
        assertRejected("import foo;");
        assertRejected("option x = ;");
        assertRejected("bogus thing;");
        assertRejected("message {}");
        assertRejected("enum {}");
        assertRejected("service {}");
        assertRejected("message M {");
        assertRejected("message M { int32 a = 1;");
        assertRejected("message M { int32 a = 1 }");
        assertRejected("message M { int32 a 1; }");
        assertRejected("message M { int32 a = x; }");
        assertRejected("message M { int32 a = -; }");
        assertRejected("message M { int32 a = 99999999999999999999; }");
        assertRejected("message M { int32 a = 1; int32 b = 1; }");
    }

    @Test
    public void malformedMembersRejected() {
        assertRejected("message M { map<string Foo> m = 1; }");
        assertRejected("message M { map<nope, string> m = 1; }");
        assertRejected("message M { map<string, string> m = 1; map<string, string> n = 1; }");
        assertRejected("message M { map<string, string> m = 1 }");
        assertRejected("message M { oneof o { int32 a = 1; int32 b = 1; } }");
        assertRejected("message M { reserved 1 }");
        assertRejected("message M { int32 a = 1 [x]; }");
        assertRejected("message M { int32 a = 1 [x = ]; }");
        assertRejected("message M { int32 a = 1 [x = 1; }");
        assertRejected("enum E { A = ; }");
        assertRejected("enum E { A 1; }");
        assertRejected("enum E { A = 1 }");
        assertRejected("enum E { A = 1;");
        assertRejected("service S { rpc }");
        assertRejected("service S { rpc A(X) retuns (Y); }");
        assertRejected("service S { rpc A(X) returns (Y) }");
        assertRejected("service S { rpc A(X returns (Y); }");
        assertRejected("service S { rpc A(X) returns (Y) { option }");
        assertRejected("service S { option }");
        assertRejected("service S {");
        assertRejected("option s = \"unterminated");
        assertRejected("option s = \"bad escape\\");
    }
}
