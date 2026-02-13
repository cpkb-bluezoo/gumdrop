/*
 * ObsoleteParserUtilsTest.java
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
 * Unit tests for {@link ObsoleteParserUtils}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ObsoleteParserUtilsTest {

    private static CharsetDecoder decoder() {
        return StandardCharsets.ISO_8859_1.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    // ========== parseObsoleteAddressList tests ==========

    @Test
    public void testParseObsoleteAddressListNull() {
        assertNull(ObsoleteParserUtils.parseObsoleteAddressList(null, decoder()));
    }

    @Test
    public void testParseObsoleteAddressListEmpty() {
        ByteBuffer buf = ByteBuffer.allocate(0);
        assertNull(ObsoleteParserUtils.parseObsoleteAddressList(buf, decoder()));
    }

    @Test
    public void testParseObsoleteAddressListSingleBare() {
        ByteBuffer buf = ByteBuffer.wrap("user@example.com".getBytes(StandardCharsets.ISO_8859_1));
        List<EmailAddress> list = ObsoleteParserUtils.parseObsoleteAddressList(buf, decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("user", list.get(0).getLocalPart());
        assertEquals("example.com", list.get(0).getDomain());
        assertNull(list.get(0).getDisplayName());
    }

    @Test
    public void testParseObsoleteAddressListCommaSeparated() {
        ByteBuffer buf = ByteBuffer.wrap("a@x.com, b@y.com".getBytes(StandardCharsets.ISO_8859_1));
        List<EmailAddress> list = ObsoleteParserUtils.parseObsoleteAddressList(buf, decoder());
        assertNotNull(list);
        assertEquals(2, list.size());
        assertEquals("a", list.get(0).getLocalPart());
        assertEquals("b", list.get(1).getLocalPart());
    }

    @Test
    public void testParseObsoleteAddressListDisplayNameAndAngle() {
        ByteBuffer buf = ByteBuffer.wrap("John Doe <john@example.com>".getBytes(StandardCharsets.ISO_8859_1));
        List<EmailAddress> list = ObsoleteParserUtils.parseObsoleteAddressList(buf, decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("John Doe", list.get(0).getDisplayName());
        assertEquals("john", list.get(0).getLocalPart());
        assertEquals("example.com", list.get(0).getDomain());
    }

    @Test
    public void testParseObsoleteAddressListSourceRoute() {
        ByteBuffer buf = ByteBuffer.wrap("@relay1,@relay2:final@destination.com".getBytes(StandardCharsets.ISO_8859_1));
        List<EmailAddress> list = ObsoleteParserUtils.parseObsoleteAddressList(buf, decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("final", list.get(0).getLocalPart());
        assertEquals("destination.com", list.get(0).getDomain());
    }

    @Test
    public void testParseObsoleteAddressListWhitespaceOnlySegmentSkipped() {
        ByteBuffer buf = ByteBuffer.wrap("  , user@example.com".getBytes(StandardCharsets.ISO_8859_1));
        List<EmailAddress> list = ObsoleteParserUtils.parseObsoleteAddressList(buf, decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
    }

    // ========== parseObsoleteMessageIDList tests ==========

    @Test
    public void testParseObsoleteMessageIDListNull() {
        assertNull(ObsoleteParserUtils.parseObsoleteMessageIDList(null, decoder()));
    }

    @Test
    public void testParseObsoleteMessageIDListEmpty() {
        ByteBuffer buf = ByteBuffer.allocate(0);
        assertNull(ObsoleteParserUtils.parseObsoleteMessageIDList(buf, decoder()));
    }

    @Test
    public void testParseObsoleteMessageIDListSingleWithBrackets() {
        ByteBuffer buf = ByteBuffer.wrap("<msg@domain.com>".getBytes(StandardCharsets.ISO_8859_1));
        List<ContentID> list = ObsoleteParserUtils.parseObsoleteMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("msg", list.get(0).getLocalPart());
        assertEquals("domain.com", list.get(0).getDomain());
    }

    @Test
    public void testParseObsoleteMessageIDListBareWithoutBrackets() {
        ByteBuffer buf = ByteBuffer.wrap("msg@domain.com".getBytes(StandardCharsets.ISO_8859_1));
        List<ContentID> list = ObsoleteParserUtils.parseObsoleteMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("msg", list.get(0).getLocalPart());
        assertEquals("domain.com", list.get(0).getDomain());
    }

    @Test
    public void testParseObsoleteMessageIDListSpaceSeparated() {
        ByteBuffer buf = ByteBuffer.wrap("a@x.com b@y.com".getBytes(StandardCharsets.ISO_8859_1));
        List<ContentID> list = ObsoleteParserUtils.parseObsoleteMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(2, list.size());
        assertEquals("a", list.get(0).getLocalPart());
        assertEquals("b", list.get(1).getLocalPart());
    }

    @Test
    public void testParseObsoleteMessageIDListCommaSeparated() {
        ByteBuffer buf = ByteBuffer.wrap("id1@d.com,id2@d.com".getBytes(StandardCharsets.ISO_8859_1));
        List<ContentID> list = ObsoleteParserUtils.parseObsoleteMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(2, list.size());
    }

    @Test
    public void testParseObsoleteMessageIDListWithComments() {
        ByteBuffer buf = ByteBuffer.wrap("(comment)bare@host.com(another)".getBytes(StandardCharsets.ISO_8859_1));
        List<ContentID> list = ObsoleteParserUtils.parseObsoleteMessageIDList(buf, decoder());
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("bare", list.get(0).getLocalPart());
        assertEquals("host.com", list.get(0).getDomain());
    }

    @Test
    public void testParseObsoleteMessageIDListConsumesBuffer() {
        byte[] bytes = "id@host.com".getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        ObsoleteParserUtils.parseObsoleteMessageIDList(buf, decoder());
        assertEquals(bytes.length, buf.position());
    }

    @Test
    public void testParseObsoleteMessageIDListInvalidDomainNoDotReturnsNull() {
        ByteBuffer buf = ByteBuffer.wrap("local@nodot".getBytes(StandardCharsets.ISO_8859_1));
        List<ContentID> list = ObsoleteParserUtils.parseObsoleteMessageIDList(buf, decoder());
        assertNull(list);
    }
}
