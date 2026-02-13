/*
 * MessageIDParserTest.java
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

package org.bluezoo.gumdrop.mime.rfc5322;

import org.bluezoo.gumdrop.mime.ContentID;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MessageIDParser}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageIDParserTest {

    private static CharsetDecoder decoder() {
        return StandardCharsets.ISO_8859_1.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    private static ByteBuffer buf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    // ========== parseMessageIDList(ByteBuffer, CharsetDecoder) tests ==========

    @Test
    public void testParseNull() {
        List<ContentID> list = MessageIDParser.parseMessageIDList((ByteBuffer) null, decoder());
        assertNotNull(list);
        assertTrue(list.isEmpty());
    }

    @Test
    public void testParseEmpty() {
        ByteBuffer buf = ByteBuffer.allocate(0);
        List<ContentID> list = MessageIDParser.parseMessageIDList(buf, decoder());
        assertNotNull(list);
        assertTrue(list.isEmpty());
    }

    @Test
    public void testParseSingle() {
        ByteBuffer buf = buf("<unique123@example.com>");
        List<ContentID> list = MessageIDParser.parseMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("unique123", list.get(0).getLocalPart());
        assertEquals("example.com", list.get(0).getDomain());
        assertEquals(buf.limit(), buf.position());
    }

    @Test
    public void testParseMultipleWithSpaces() {
        ByteBuffer buf = buf("<msg1@domain.com> <msg2@domain.com>");
        List<ContentID> list = MessageIDParser.parseMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(2, list.size());
        assertEquals("msg1", list.get(0).getLocalPart());
        assertEquals("msg2", list.get(1).getLocalPart());
    }

    @Test
    public void testParseMultipleWithCommas() {
        ByteBuffer buf = buf("<msg1@domain.com>,<msg2@domain.com>");
        List<ContentID> list = MessageIDParser.parseMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(2, list.size());
        assertEquals("msg1", list.get(0).getLocalPart());
        assertEquals("msg2", list.get(1).getLocalPart());
    }

    @Test
    public void testParseWithCfws() {
        ByteBuffer buf = buf("  (comment) <id@host.com>  ");
        List<ContentID> list = MessageIDParser.parseMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("id", list.get(0).getLocalPart());
        assertEquals("host.com", list.get(0).getDomain());
    }

    @Test
    public void testParseReferencesStyle() {
        ByteBuffer buf = buf("<msg1@example.com> <msg2@example.com> <msg3@example.com>");
        List<ContentID> list = MessageIDParser.parseMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(3, list.size());
    }

    @Test
    public void testParseAdvancesPosition() {
        ByteBuffer buf = buf("<a@x.com> <b@y.com>");
        List<ContentID> list = MessageIDParser.parseMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(2, list.size());
        assertEquals(buf.limit(), buf.position());
    }

    @Test
    public void testParseDomainLiteral() {
        ByteBuffer buf = buf("<user@[192.168.1.1]>");
        List<ContentID> list = MessageIDParser.parseMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("user", list.get(0).getLocalPart());
        assertEquals("[192.168.1.1]", list.get(0).getDomain());
    }

    @Test
    public void testParseStopsAtNonAngle() {
        ByteBuffer buf = buf("<only@valid.com> trailing");
        List<ContentID> list = MessageIDParser.parseMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("only", list.get(0).getLocalPart());
    }

    @Test
    public void testParseSubrange() {
        ByteBuffer buf = buf("prefix <id@host.com> suffix");
        buf.position(7);
        buf.limit(21);
        List<ContentID> list = MessageIDParser.parseMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("id", list.get(0).getLocalPart());
        assertEquals("host.com", list.get(0).getDomain());
        assertEquals(21, buf.position());
    }
}
