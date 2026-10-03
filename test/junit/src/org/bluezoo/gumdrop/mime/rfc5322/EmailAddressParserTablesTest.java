/*
 * EmailAddressParserTablesTest.java
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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Table-driven tests for {@link EmailAddressParser}: every input of the String
 * list parser, the envelope validator and the ByteBuffer list parser is
 * rendered to a canonical string and compared with the expected result,
 * covering the error and edge arm of each production.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EmailAddressParserTablesTest {

    private static String render(EmailAddress a) {
        if (a instanceof GroupEmailAddress) {
            GroupEmailAddress g = (GroupEmailAddress) a;
            StringBuilder sb = new StringBuilder("G[");
            sb.append(g.getGroupName());
            sb.append(':');
            for (EmailAddress m : g.getMembers()) {
                sb.append(render(m));
                sb.append(';');
            }
            sb.append(']');
            return sb.toString();
        }
        String display = a.getDisplayName();
        if (display == null) {
            display = "";
        }
        return display + "<" + a.getLocalPart() + "@" + a.getDomain() + ">";
    }

    private static String renderList(List<EmailAddress> list) {
        if (list == null) {
            return "NULL";
        }
        StringBuilder sb = new StringBuilder();
        for (EmailAddress a : list) {
            sb.append(render(a));
            sb.append(',');
        }
        return sb.toString();
    }

    private static final String[][] STRING_LISTS = {
        {"a@b.com, c@d.com", "<a@b.com>,<c@d.com>,"},
        {"a@b.com c@d.com", "NULL"},
        {"   ", ""},
        {"Team: a@b.com, c@d.com;", "G[Team:<a@b.com>;<c@d.com>;],"},
        {"Team: a@b.com x@y;", "NULL"},
        {"Team: a@b.com", "NULL"},
        {"Team:;", "G[Team:],"},
        {": a@b", "NULL"},
        {"Name <a@b.com>", "Name<a@b.com>,"},
        {"<a@b.com>", "<a@b.com>,"},
        {"Name a@b.com", "NULL"},
        {"Name <a@b.com", "NULL"},
        {"Name x <a@b.com>", "Name x<a@b.com>,"},
        {"\"Quoted, Name\" <a@b.com>", "Quoted, Name<a@b.com>,"},
        {"(comment) a@b.com (c)", "<a@b.com>,"},
        {"a@[127.0.0.1]", "<a@[127.0.0.1]>,"},
        {"a@[1.2\\]3]", "<a@[1.2\\]3]>,"},
        {"a@[bad[x]", "NULL"},
        {"a@[unterm", "NULL"},
        {"\"quoted local\"@example.com", "<\"quoted local\"@example.com>,"},
        {"\"unterminated@x", "NULL"},
        {"a@", "NULL"},
        {"@b.com", "NULL"},
        {"a.b@c.d", "<a.b@c.d>,"},
        {"a..b@c", "NULL"},
        {"Joe (hi) <a@b.c>", "Joe<a@b.c>,"},
        {"Jo@e <a@b.c>", "NULL"},
        {"(abc a@b", "NULL"},
        {"(a\\)b) x@y.z", "<x@y.z>,"},
        {"Name <a@b.c>, G: <x@y.z>;, q@r.s", "Name<a@b.c>,G[G:<x@y.z>;],<q@r.s>,"},
        {"A <a@b.c>, B: c@d.e", "NULL"},
        {"Name <\"a b\"@c.d>", "Name<\"a b\"@c.d>,"},
        {"a@b.c,", "<a@b.c>,"},
        {"a@b.c, ,d@e.f", "NULL"},
        {"Name <a@b.c> junk", "NULL"},
        {"Name <a@b.c>x", "NULL"},
        {"G: a@b.c, d", "NULL"},
        {"\"\" <a@b.c>", "<a@b.c>,"},
        {"<a@b.c>, <d@e.f>", "<a@b.c>,<d@e.f>,"},
        {"a@b.c;", "NULL"},
        {"Tom: a@b.c;, x@y.z", "G[Tom:<a@b.c>;],<x@y.z>,"},
    };

    @Test
    public void stringListTable() {
        for (int i = 0; i < STRING_LISTS.length; i++) {
            String input = STRING_LISTS[i][0];
            List<EmailAddress> parsed = EmailAddressParser.parseEmailAddressList(input);
            assertEquals("input: " + input, STRING_LISTS[i][1], renderList(parsed));
        }
        assertEquals(0, EmailAddressParser.parseEmailAddressList((String) null).size());
        assertEquals(0, EmailAddressParser.parseEmailAddressList("").size());
    }

    @Test
    public void smtputf8AllowsNonAsciiOnlyWhenEnabled() {
        String input = "\u7528\u6237@\u4f8b\u3048.jp";
        List<EmailAddress> on = EmailAddressParser.parseEmailAddressList(input, true);
        assertEquals("<\u7528\u6237@\u4f8b\u3048.jp>,", renderList(on));
        assertNull(EmailAddressParser.parseEmailAddressList(input, false));
        List<EmailAddress> named = EmailAddressParser.parseEmailAddressList(
            "\u00e9 <\u7528\u6237@\u4f8b\u3048.jp>", true);
        assertEquals("\u00e9<\u7528\u6237@\u4f8b\u3048.jp>,", renderList(named));
    }

    private static final String[][] SINGLES = {
        {"a@b.c", "<a@b.c>"},
        {"a@b.c junk", "NULL"},
        {"", "NULL"},
        {"(c) a@b.c", "<a@b.c>"},
        {"G: a@b.c;", "NULL"},
        {"Name <a@b.c> x", "NULL"},
    };

    @Test
    public void singleAddressTable() {
        for (int i = 0; i < SINGLES.length; i++) {
            EmailAddress a = EmailAddressParser.parseEmailAddress(SINGLES[i][0]);
            String actual;
            if (a == null) {
                actual = "NULL";
            } else {
                actual = render(a);
            }
            assertEquals("input: " + SINGLES[i][0], SINGLES[i][1], actual);
        }
        assertNull(EmailAddressParser.parseEmailAddress(null));
    }

    /** Input, valid with SMTPUTF8 off, valid with SMTPUTF8 on. */
    private static final String[][] ENVELOPES = {
        {"a@b.c", "1", "1"},
        {"a@@b", "0", "0"},
        {"\"a@b\"@c.com", "1", "1"},
        {"@b", "0", "0"},
        {"a@", "0", "0"},
        {"a@b@c", "0", "0"},
        {"a.@b.c", "0", "0"},
        {".a@b.c", "0", "0"},
        {"a..b@c.d", "0", "0"},
        {"a b@c.d", "0", "0"},
        {"\"a b\"@c.com", "1", "1"},
        {"\"a@c.com", "0", "0"},
        {"\"ab\"x@c.com", "0", "0"},
        {"\"a\\\"b\"@c.com", "1", "1"},
        {"\"a\u0001b\"@c.com", "0", "0"},
        {"\"a\u00e9\"@c.com", "0", "1"},
        {"\"\"@c.com", "1", "1"},
        {"a!#$%&'*+-/=?^_`{|}~@b.com", "1", "1"},
        {"a@[1.2.3.4]", "1", "1"},
        {"a@[1.2.3.4", "0", "0"},
        {"a@[1 2]", "0", "0"},
        {"a@[1[2]", "0", "0"},
        {"a@[1\\2]", "0", "0"},
        {"a@.b.com", "0", "0"},
        {"a@b.com.", "0", "0"},
        {"a@b..com", "0", "0"},
        {"a@b_c.com", "0", "0"},
        {"\u00e9@b.com", "0", "1"},
        {"a@\u00e9.com", "0", "1"},
        {"a@b-c.com", "1", "1"},
    };

    @Test
    public void envelopeTable() {
        for (int i = 0; i < ENVELOPES.length; i++) {
            String input = ENVELOPES[i][0];
            boolean off = EmailAddressParser.parseEnvelopeAddress(input) != null;
            boolean on = EmailAddressParser.parseEnvelopeAddress(input, true) != null;
            assertEquals("off: " + input, "1".equals(ENVELOPES[i][1]), off);
            assertEquals("on: " + input, "1".equals(ENVELOPES[i][2]), on);
        }
        assertNull(EmailAddressParser.parseEnvelopeAddress(null));
        assertNull(EmailAddressParser.parseEnvelopeAddress(""));
    }

    @Test
    public void envelopeLengthLimits() {
        StringBuilder local = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            local.append('a');
        }
        assertNull(EmailAddressParser.parseEnvelopeAddress(local + "@b.com"));
        StringBuilder domain = new StringBuilder("a@");
        for (int i = 0; i < 260; i++) {
            domain.append('b');
        }
        assertNull(EmailAddressParser.parseEnvelopeAddress(domain.toString()));
        assertNotNull(EmailAddressParser.parseEnvelopeAddress("a@b.com"));
    }

    private static final String[][] BUFFERS = {
        {"a@b.com", "<a@b.com>,"},
        {"Name <a@b.com>, c@d.com", "Name<a@b.com>,<c@d.com>,"},
        {"<a@b.com>", "<a@b.com>,"},
        {"\"Q\" <a@b.com>", "Q<a@b.com>,"},
        {"G: a@b.com;", ""},
        {"G: x@y.z; <a@b.com>", "<a@b.com>,"},
        {": a@b", ""},
        {";", ""},
        {"a@b.com, <c@d.com>", "<a@b.com>,<c@d.com>,"},
        {"junk", ""},
        {"junk, a@b.com", ""},
        {"<a@b.com", ""},
        {"<a b.com>", ""},
        {"<\"a b\"@c.com>", "<a b@c.com>,"},
        {"<\"a\\\"b\"@c.com>", "<a\\\"b@c.com>,"},
        {"<a@[1.2.3.4]>", "<a@[1.2.3.4]>,"},
        {"<a@[1.2>", ""},
        {"<\"unterminated@x>", ""},
        {"<@x>", ""},
        {"<a@>", ""},
        {"<a@b", ""},
        {"(c) a@b.com", "<a@b.com>,"},
        {"(a(b)c\\)) a@b.com", "<a@b.com>,"},
        {"a>b@c", ""},
        {"=?UTF-8?Q?J=C3=B6?= <a@b.com>", "J\u00f6<a@b.com>,"},
        {"  ", ""},
        {"<a@b.com> <c@d.com>", "<a@b.com>,<c@d.com>,"},
        {"n <a@[x\\]y]>", "n<a@[x\\]y]>,"},
        {"a@b.com;x@y.z", "<a@b.com>,<x@y.z>,"},
        {"(unterminated", ""},
        {"Name <a@b.com>, ", "Name<a@b.com>,"},
        {" , ,a@b.c", "<a@b.c>,"},
        {"a@b.com (c)", "<a@b.com>,"},
        {"\"Q\" bare@x.y", ""},
        {"A B bare@x.y, <c@d.e>", ""},
    };

    @Test
    public void bufferListTable() {
        CharsetDecoder decoder = StandardCharsets.ISO_8859_1.newDecoder();
        for (int i = 0; i < BUFFERS.length; i++) {
            String input = BUFFERS[i][0];
            byte[] bytes = input.getBytes(StandardCharsets.ISO_8859_1);
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            List<EmailAddress> parsed = EmailAddressParser.parseEmailAddressList(buf, decoder);
            assertEquals("input: " + input, BUFFERS[i][1], renderList(parsed));
        }
        assertEquals(0, EmailAddressParser.parseEmailAddressList((ByteBuffer) null, decoder).size());
    }

    @Test
    public void bufferListUtf8LocalPart() {
        byte[] bytes = "<\u7528\u6237@\u4f8b.jp>".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        List<EmailAddress> parsed = EmailAddressParser.parseEmailAddressList(buf,
            StandardCharsets.UTF_8.newDecoder());
        assertEquals("<\u7528\u6237@\u4f8b.jp>,", renderList(parsed));
    }
}
