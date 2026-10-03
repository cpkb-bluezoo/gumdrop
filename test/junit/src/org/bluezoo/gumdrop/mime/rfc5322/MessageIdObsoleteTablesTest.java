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
package org.bluezoo.gumdrop.mime.rfc5322;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.bluezoo.gumdrop.mime.ContentID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Table-driven tests for {@link MessageIDParser} and {@link ObsoleteParserUtils}:
 * each input is rendered to a canonical string and compared with the expected
 * result, covering the accept and reject arm of every validation rule.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageIdObsoleteTablesTest {

    private static String renderIds(List<ContentID> list) {
        if (list == null) {
            return "NULL";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentID c : list) {
            sb.append(c.getLocalPart());
            sb.append('@');
            sb.append(c.getDomain());
            sb.append(',');
        }
        return sb.toString();
    }

    private static String renderAddresses(List<EmailAddress> list) {
        if (list == null) {
            return "NULL";
        }
        StringBuilder sb = new StringBuilder();
        for (EmailAddress a : list) {
            String display = a.getDisplayName();
            if (display == null) {
                display = "";
            }
            sb.append(display);
            sb.append('<');
            sb.append(a.getLocalPart());
            sb.append('@');
            sb.append(a.getDomain());
            sb.append(">,");
        }
        return sb.toString();
    }

    private static ByteBuffer latin1(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static final String[][] MESSAGE_IDS = {
        {"<a@b.c>", "a@b.c,"},
        {"<a@b.c> <d@e.f>", "a@b.c,d@e.f,"},
        {"<a@b.c>,<d@e.f>", "a@b.c,d@e.f,"},
        {"(c) <a@b.c>", "a@b.c,"},
        {"<a@[1.2.3.4]>", "a@[1.2.3.4],"},
        {"<a@[1\\]2]>", "a@[1\\]2],"},
        {"<a@[1.2>", ""},
        {"a@b.c", ""},
        {"<a.b@c.d>", "a.b@c.d,"},
        {"<.a@b.c>", ""},
        {"<a.@b.c>", ""},
        {"<a..b@c.d>", ""},
        {"<a@b.>", ""},
        {"<a@.b>", ""},
        {"<a b@c.d>", ""},
        {"<a@b c>", ""},
        {"<@b.c>", ""},
        {"<a@>", "a@,"},
        {"<a@b.c", ""},
        {"<ab>", ""},
        {"<a@b.c> junk", "a@b.c,"},
        {"<a@b.c> (c(d)e\\)) <x@y.z>", "a@b.c,x@y.z,"},
        {"<a@[x y]>", ""},
        {"<a@[]>", "a@[],"},
        {"<a@[>", ""},
        {"<a@[x\\]y]>", "a@[x\\]y],"},
        {"<a@b@c>", ""},
        {"<a(b@c.d>", ""},
        {"  ", ""},
        {"<\u00e9@b.c>", ""},
        {"<a@[\\x]>", "a@[\\x],"},
        {"<a@[x\\]", ""},
        {"<a@[x[y]>", ""},
    };

    private static final String[][] OBSOLETE_ADDRESSES = {
        {"@d1,@d2:u@host.com", "<u@host.com>,"},
        {"@d1:", "NULL"},
        {"@d1:u v@x", "NULL"},
        {"<a@b.c>", "<a@b.c>,"},
        {"Name <a@b.c>", "Name<a@b.c>,"},
        {"\"Q \\\"x\\\"\" <a@b.c>", "Q \"x\"<a@b.c>,"},
        {"a@b.c", "<a@b.c>,"},
        {"junk", "NULL"},
        {"a@", "NULL"},
        {"@b", "NULL"},
        {"<a>", "NULL"},
        {"Name <a>", "NULL"},
        {"<@b>", "NULL"},
        {"a@b.c, d@e.f", "<a@b.c>,<d@e.f>,"},
        {"<x@y>, junk, z@w", "<x@y>,<z@w>,"},
        {"  ,  ", "NULL"},
        {"Name > < a@b.c", "NULL"},
        {"@d1:u@h", "<u@h>,"},
        {" <a@b> , @r:x@y.z", "<a@b>,<x@y.z>,"},
        {"\"\" <a@b.c>", "<a@b.c>,"},
        {"a@b@c.d", "<a@b@c.d>,"},
    };

    private static final String[][] OBSOLETE_IDS = {
        {"<a@b.com>", "a@b.com,"},
        {"a@b.com", "a@b.com,"},
        {"(c)a@b.com(d)", "a@b.com,"},
        {"a@b.com c@d.com", "a@b.com,c@d.com,"},
        {"a@b.com,c@d.org", "a@b.com,c@d.org,"},
        {"a@b", "NULL"},
        {"a@.com", "NULL"},
        {"a@b.", "NULL"},
        {"a@-b.com", "NULL"},
        {"a@b-.com", "a@b-.com,"},
        {"a@b_c.com", "NULL"},
        {"a b@c.com", "b@c.com,"},
        {"a$b@c.com", "a$b@c.com,"},
        {"a!b@c.com", "NULL"},
        {"<a@b.com", "NULL"},
        {"a@b.com>", "NULL"},
        {"@b.com", "NULL"},
        {"a@", "NULL"},
        {"junk", "NULL"},
        {"<>", "NULL"},
        {"()", "NULL"},
        {"  ", "NULL"},
        {"a#b+c=d%e@x.y", "a#b+c=d%e@x.y,"},
        {"a-b_c.d@x-y.z", "a-b_c.d@x-y.z,"},
        {"x@y.z\tq@r.s", "x@y.z,q@r.s,"},
        {"x@y.z\nq@r.s\r", "x@y.z,q@r.s,"},
    };

    @Test
    public void messageIdTable() {
        CharsetDecoder decoder = StandardCharsets.ISO_8859_1.newDecoder();
        for (int i = 0; i < MESSAGE_IDS.length; i++) {
            String input = MESSAGE_IDS[i][0];
            List<ContentID> parsed = MessageIDParser.parseMessageIDList(latin1(input), decoder);
            assertEquals("input: " + input, MESSAGE_IDS[i][1], renderIds(parsed));
        }
    }

    @Test
    public void messageIdEmptyInputGivesEmptyList() {
        CharsetDecoder decoder = StandardCharsets.ISO_8859_1.newDecoder();
        assertEquals(0, MessageIDParser.parseMessageIDList(null, decoder).size());
        assertEquals(0, MessageIDParser.parseMessageIDList(ByteBuffer.allocate(0), decoder).size());
    }

    @Test
    public void messageIdUtf8DecoderAllowsNonAscii() {
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder();
        byte[] bytes = "<\u00e9@\u00fc.de>".getBytes(StandardCharsets.UTF_8);
        List<ContentID> parsed = MessageIDParser.parseMessageIDList(ByteBuffer.wrap(bytes), utf8);
        assertEquals("\u00e9@\u00fc.de,", renderIds(parsed));
        CharsetDecoder latin = StandardCharsets.ISO_8859_1.newDecoder();
        List<ContentID> strict = MessageIDParser.parseMessageIDList(ByteBuffer.wrap(bytes), latin);
        assertEquals("", renderIds(strict));
    }

    @Test
    public void obsoleteAddressTable() {
        CharsetDecoder decoder = StandardCharsets.ISO_8859_1.newDecoder();
        for (int i = 0; i < OBSOLETE_ADDRESSES.length; i++) {
            String input = OBSOLETE_ADDRESSES[i][0];
            List<EmailAddress> parsed = ObsoleteParserUtils.parseObsoleteAddressList(latin1(input), decoder);
            assertEquals("input: " + input, OBSOLETE_ADDRESSES[i][1], renderAddresses(parsed));
        }
        assertNull(ObsoleteParserUtils.parseObsoleteAddressList(null, decoder));
        assertNull(ObsoleteParserUtils.parseObsoleteAddressList(ByteBuffer.allocate(0), decoder));
    }

    @Test
    public void obsoleteMessageIdTable() {
        CharsetDecoder decoder = StandardCharsets.ISO_8859_1.newDecoder();
        for (int i = 0; i < OBSOLETE_IDS.length; i++) {
            String input = OBSOLETE_IDS[i][0];
            List<ContentID> parsed = ObsoleteParserUtils.parseObsoleteMessageIDList(latin1(input), decoder);
            assertEquals("input: " + input, OBSOLETE_IDS[i][1], renderIds(parsed));
        }
        assertNull(ObsoleteParserUtils.parseObsoleteMessageIDList(null, decoder));
        assertNull(ObsoleteParserUtils.parseObsoleteMessageIDList(ByteBuffer.allocate(0), decoder));
    }
}
