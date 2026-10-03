/*
 * StreamH2WebSocketUpgradeTest.java
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
package org.bluezoo.gumdrop.mime;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Branch tests for the small MIME value and utility classes:
 * {@link Parameter} wire encoding, {@link MimeUtils} character classes,
 * equality and parameter lookup of {@link ContentType} and
 * {@link ContentDisposition}, {@link ContentID}, and the input validation of
 * the Content-Disposition and Content-ID parsers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MimeValueClassBranchesTest {

    private static List<Parameter> params(String... pairs) {
        List<Parameter> list = new ArrayList<Parameter>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            list.add(new Parameter(pairs[i], pairs[i + 1]));
        }
        return list;
    }

    private static CharsetDecoder latin1() {
        return StandardCharsets.ISO_8859_1.newDecoder();
    }

    private static ByteBuffer bytes(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    public void parameterHeaderValueEncodingArms() {
        assertEquals("a=token", new Parameter("a", "token").toHeaderValue());
        assertEquals("a=\"two words\"", new Parameter("a", "two words").toHeaderValue());
        assertEquals("a=\"q\\\"b\\\\s\"", new Parameter("a", "q\"b\\s").toHeaderValue());
        assertEquals("a=\"\"", new Parameter("a", "").toHeaderValue());
        String allAttrChars = "é!#$&+-.^_`|~AZaz09";
        assertEquals("n*=UTF-8''%C3%A9!#$&+-.^_`|~AZaz09",
            new Parameter("n", allAttrChars).toHeaderValue());
        assertEquals("n*=UTF-8''%C3%A9%20%25%2A%27%28%29%2C%3B%3D",
            new Parameter("n", "é %*'(),;=").toHeaderValue());
        assertEquals("n=v", new Parameter("n", "v").toString());
    }

    @Test
    public void parameterEqualityIgnoresNameCaseOnly() {
        Parameter a = new Parameter("Charset", "utf-8");
        assertTrue(a.equals(new Parameter("charset", "utf-8")));
        assertEquals(a.hashCode(), new Parameter("CHARSET", "utf-8").hashCode());
        assertFalse(a.equals(new Parameter("charset", "UTF-8")));
        assertFalse(a.equals(new Parameter("other", "utf-8")));
        assertFalse(a.equals("charset=utf-8"));
    }

    @Test
    public void tokenAndSpecialAndBoundaryCharacterClasses() {
        String tokenPunct = "!#$%&'*+-.^_`{|}~";
        String specials = "()<>@,;:\\\"/[]?=";
        String boundaryPunct = "'()+_,-./:=?";
        for (char c = 0; c < 128; c++) {
            boolean alnum = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            assertEquals("token " + (int) c, alnum || tokenPunct.indexOf(c) >= 0, MimeUtils.isTokenChar(c));
            assertEquals("special " + (int) c, specials.indexOf(c) >= 0, MimeUtils.isSpecial(c));
            assertEquals("boundary " + (int) c, alnum || boundaryPunct.indexOf(c) >= 0,
                MimeUtils.isBoundaryChar(c));
        }
        assertFalse(MimeUtils.isTokenChar('é'));
    }

    @Test
    public void tokenAndBoundaryStrings() {
        assertFalse(MimeUtils.isToken(null));
        assertFalse(MimeUtils.isToken(""));
        assertTrue(MimeUtils.isToken("text"));
        assertFalse(MimeUtils.isToken("te xt"));
        assertFalse(MimeUtils.isValidBoundary(null));
        assertFalse(MimeUtils.isValidBoundary(""));
        StringBuilder seventy = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            seventy.append('b');
        }
        assertTrue(MimeUtils.isValidBoundary(seventy.toString()));
        assertFalse(MimeUtils.isValidBoundary(seventy + "b"));
        assertFalse(MimeUtils.isValidBoundary("bad boundary"));
        assertTrue(MimeUtils.isValidBoundary("ok-1_2.3"));
    }

    @Test
    public void contentTypeConstructionAndLookup() {
        try {
            new ContentType(null, "plain", null);
            fail("null primary type accepted");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("primaryType"));
        }
        try {
            new ContentType("text", null, null);
            fail("null subtype accepted");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("subType"));
        }
        ContentType none = new ContentType("Text", "Plain", new ArrayList<Parameter>());
        assertNull(none.getParameters());
        assertNull(none.getParameter("charset"));
        assertFalse(none.hasParameter("charset"));
        assertEquals("Text/Plain", none.toString());
        assertEquals("Text/Plain", none.toHeaderValue());
        ContentType with = new ContentType("text", "plain", params("Charset", "utf-8", "charset", "other"));
        assertEquals("utf-8", with.getParameter("CHARSET"));
        assertTrue(with.hasParameter("charset"));
        assertFalse(with.hasParameter("format"));
        assertEquals("text/plain; Charset=utf-8; charset=other", with.toString());
        assertEquals("text/plain; Charset=utf-8; charset=other", with.toHeaderValue());
    }

    @Test
    public void contentTypeMatching() {
        ContentType t = new ContentType("text", "plain", null);
        assertTrue(t.isPrimaryType("TEXT"));
        assertFalse(t.isPrimaryType("image"));
        assertTrue(t.isSubType("PLAIN"));
        assertFalse(t.isSubType("html"));
        assertTrue(t.isMimeType("TEXT", "Plain"));
        assertFalse(t.isMimeType("text", "html"));
        assertFalse(t.isMimeType("image", "plain"));
        assertTrue(t.isMimeType("Text/Plain"));
        assertFalse(t.isMimeType("textplain"));
        assertFalse(t.isMimeType((String) null));
        assertFalse(t.isMimeType("text/html"));
    }

    @Test
    public void contentTypeEquality() {
        ContentType a = new ContentType("text", "plain", params("a", "1"));
        ContentType same = new ContentType("TEXT", "PLAIN", params("A", "1"));
        assertTrue(a.equals(same));
        assertEquals(a.hashCode(), same.hashCode());
        assertFalse(a.equals(new ContentType("text", "plain", null)));
        assertFalse(new ContentType("text", "plain", null).equals(a));
        assertTrue(new ContentType("text", "plain", null).equals(new ContentType("text", "plain", null)));
        assertFalse(a.equals(new ContentType("text", "html", params("a", "1"))));
        assertFalse(a.equals(new ContentType("image", "plain", params("a", "1"))));
        assertFalse(a.equals(new ContentType("text", "plain", params("a", "2"))));
        assertFalse(a.equals("text/plain"));
    }

    @Test
    public void contentDispositionConstructionLookupAndEquality() {
        try {
            new ContentDisposition(null, null);
            fail("null type accepted");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("dispositionType"));
        }
        ContentDisposition bare = new ContentDisposition("inline", new ArrayList<Parameter>());
        assertNull(bare.getParameters());
        assertNull(bare.getParameter("filename"));
        assertFalse(bare.hasParameter("filename"));
        assertEquals("inline", bare.toString());
        assertEquals("inline", bare.toHeaderValue());
        ContentDisposition with = new ContentDisposition("attachment",
            params("filename", "a b.txt", "FileName", "dup"));
        assertEquals("a b.txt", with.getParameter("FILENAME"));
        assertTrue(with.hasParameter("filename"));
        assertFalse(with.hasParameter("size"));
        assertEquals("attachment; filename=a b.txt; FileName=dup", with.toString());
        assertEquals("attachment; filename=\"a b.txt\"; FileName=dup", with.toHeaderValue());
        ContentDisposition same = new ContentDisposition("ATTACHMENT",
            params("FILENAME", "a b.txt", "filename", "dup"));
        assertTrue(with.equals(same));
        assertEquals(with.hashCode(), same.hashCode());
        assertFalse(with.equals(bare));
        assertFalse(bare.equals(with));
        assertTrue(bare.equals(new ContentDisposition("INLINE", null)));
        assertFalse(with.equals(new ContentDisposition("inline", params("filename", "a b.txt", "FileName", "dup"))));
        assertFalse(with.equals(new ContentDisposition("attachment", params("filename", "x"))));
        assertFalse(with.equals("attachment"));
    }

    @Test
    public void contentIdValueSemantics() {
        try {
            new ContentID(null, "d");
            fail("null local part accepted");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("localPart"));
        }
        try {
            new ContentID("l", null);
            fail("null domain accepted");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("domain"));
        }
        ContentID id = new ContentID("l", "d.example");
        assertEquals("<l@d.example>", id.toString());
        assertTrue(id.equals(new ContentID("l", "d.example")));
        assertEquals(id.hashCode(), new ContentID("l", "d.example").hashCode());
        assertFalse(id.equals(new ContentID("L", "d.example")));
        assertFalse(id.equals(new ContentID("l", "other")));
        assertFalse(id.equals("<l@d.example>"));
    }

    @Test
    public void contentIdParserAcceptsExactlyOneId() {
        CharsetDecoder decoder = latin1();
        assertNull(ContentIDParser.parse(null, decoder));
        assertNull(ContentIDParser.parse(ByteBuffer.allocate(0), decoder));
        assertNull(ContentIDParser.parse(bytes("<a@b> <c@d>"), decoder));
        assertNull(ContentIDParser.parse(bytes("junk"), decoder));
        ByteBuffer one = bytes("<a@b.c> (comment)");
        ContentID id = ContentIDParser.parse(one, decoder);
        assertNotNull(id);
        assertEquals("a", id.getLocalPart());
        assertTrue(one.position() > 0);
        List<ContentID> list = ContentIDParser.parseList(bytes("<a@b>, <c@d>"), decoder);
        assertEquals(2, list.size());
    }

    @Test
    public void contentDispositionParserRejectsBadInput() {
        CharsetDecoder decoder = latin1();
        assertNull(ContentDispositionParser.parse((ByteBuffer) null, decoder));
        assertNull(ContentDispositionParser.parse(ByteBuffer.allocate(0), decoder));
        assertNull(ContentDispositionParser.parse(bytes("ab"), decoder));
        assertNull(ContentDispositionParser.parse(bytes("in line"), decoder));
        assertNull(ContentDispositionParser.parse(bytes(";x=1"), decoder));
        assertNull(ContentDispositionParser.parse((String) null));
        assertNull(ContentDispositionParser.parse(""));
        ContentDisposition plain = ContentDispositionParser.parse("inline");
        assertEquals("inline", plain.getDispositionType());
        assertNull(plain.getParameters());
        ContentDisposition semi = ContentDispositionParser.parse("attachment;");
        assertEquals("attachment", semi.getDispositionType());
        ContentDisposition full = ContentDispositionParser.parse("attachment; filename=\"a.txt\"; size=10");
        assertEquals("a.txt", full.getParameter("filename"));
        assertEquals("10", full.getParameter("size"));
    }
}
