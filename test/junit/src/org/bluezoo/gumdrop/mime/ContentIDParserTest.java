/*
 * ContentIDParserTest.java
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

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ContentIDParser}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContentIDParserTest {

    private static CharsetDecoder decoder() {
        return StandardCharsets.ISO_8859_1.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    private static ByteBuffer buf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    // ========== parse(ByteBuffer, CharsetDecoder) tests ==========

    @Test
    public void testParseNull() {
        assertNull(ContentIDParser.parse(null, decoder()));
    }

    @Test
    public void testParseEmpty() {
        assertNull(ContentIDParser.parse(ByteBuffer.allocate(0), decoder()));
    }

    @Test
    public void testParseWhitespaceOnly() {
        assertNull(ContentIDParser.parse(buf("   "), decoder()));
    }

    @Test
    public void testParseWithAngleBrackets() {
        ContentID cid = ContentIDParser.parse(buf("<abc123@example.com>"), decoder());
        assertNotNull(cid);
        assertEquals("abc123", cid.getLocalPart());
        assertEquals("example.com", cid.getDomain());
    }

    @Test
    public void testParseWithLeadingTrailingWhitespace() {
        ContentID cid = ContentIDParser.parse(buf("  <msg@host.com>  "), decoder());
        assertNotNull(cid);
        assertEquals("msg", cid.getLocalPart());
        assertEquals("host.com", cid.getDomain());
    }

    @Test
    public void testParseNoAt() {
        assertNull(ContentIDParser.parse(buf("<localpart.example.com>"), decoder()));
    }

    @Test
    public void testParseAtAtStart() {
        assertNull(ContentIDParser.parse(buf("<@example.com>"), decoder()));
    }

    @Test
    public void testParseAtAtEnd() {
        ContentID cid = ContentIDParser.parse(buf("<localpart@>"), decoder());
        assertNotNull(cid);
        assertEquals("localpart", cid.getLocalPart());
        assertEquals("", cid.getDomain());
    }

    @Test
    public void testParseAngleBracketsOnly() {
        assertNull(ContentIDParser.parse(buf("<>"), decoder()));
    }

    @Test
    public void testParseSingleAngleBracket() {
        assertNull(ContentIDParser.parse(buf("<"), decoder()));
        assertNull(ContentIDParser.parse(buf(">"), decoder()));
    }

    @Test
    public void testParseDotInLocalPart() {
        ContentID cid = ContentIDParser.parse(buf("<part1.E72C5B26@example.com>"), decoder());
        assertNotNull(cid);
        assertEquals("part1.E72C5B26", cid.getLocalPart());
        assertEquals("example.com", cid.getDomain());
    }

    @Test
    public void testParseDomainLiteral() {
        ContentID cid = ContentIDParser.parse(buf("<user@[192.168.1.1]>"), decoder());
        assertNotNull(cid);
        assertEquals("user", cid.getLocalPart());
        assertEquals("[192.168.1.1]", cid.getDomain());
    }

    @Test
    public void testParseMultipleIdsReturnsNull() {
        assertNull(ContentIDParser.parse(buf("<a@x.com> <b@y.com>"), decoder()));
    }

    // ========== parseList(ByteBuffer, CharsetDecoder) tests ==========

    @Test
    public void testParseListNull() {
        List<ContentID> list = ContentIDParser.parseList(null, decoder());
        assertNotNull(list);
        assertTrue(list.isEmpty());
    }

    @Test
    public void testParseListEmpty() {
        List<ContentID> list = ContentIDParser.parseList(ByteBuffer.allocate(0), decoder());
        assertNotNull(list);
        assertTrue(list.isEmpty());
    }

    @Test
    public void testParseListSingle() {
        List<ContentID> list = ContentIDParser.parseList(buf("<one@example.com>"), decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("one", list.get(0).getLocalPart());
        assertEquals("example.com", list.get(0).getDomain());
    }

    @Test
    public void testParseListMultipleWithSpaces() {
        List<ContentID> list = ContentIDParser.parseList(buf("<a@x.com> <b@y.com> <c@z.com>"), decoder());
        assertNotNull(list);
        assertEquals(3, list.size());
        assertEquals("a", list.get(0).getLocalPart());
        assertEquals("b", list.get(1).getLocalPart());
        assertEquals("c", list.get(2).getLocalPart());
    }

    @Test
    public void testParseListMultipleWithCommas() {
        List<ContentID> list = ContentIDParser.parseList(buf("<a@x.com>,<b@y.com>,<c@z.com>"), decoder());
        assertNotNull(list);
        assertEquals(3, list.size());
    }

    @Test
    public void testParseListWithComments() {
        List<ContentID> list = ContentIDParser.parseList(buf("(comment) <id@host.com> (another)"), decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("id", list.get(0).getLocalPart());
        assertEquals("host.com", list.get(0).getDomain());
    }

    @Test
    public void testParseListMalformedUnclosedAngle() {
        List<ContentID> list = ContentIDParser.parseList(buf("<a@b.com> <unclosed@domain"), decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("a", list.get(0).getLocalPart());
    }

    @Test
    public void testParseListStopsAtNonAngle() {
        List<ContentID> list = ContentIDParser.parseList(buf("<valid@x.com> garbage <also@y.com>"), decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("valid", list.get(0).getLocalPart());
    }
}
