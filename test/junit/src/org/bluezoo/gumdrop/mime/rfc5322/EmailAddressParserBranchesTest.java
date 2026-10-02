/*
 * EmailAddressParserBranchesTest.java
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
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Branch tests for {@link EmailAddressParser}: the String list parser, groups,
 * comments, envelope validation and the ByteBuffer list parser.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EmailAddressParserBranchesTest {

    private static CharsetDecoder decoder() {
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder();
        d.onMalformedInput(CodingErrorAction.REPLACE);
        d.onUnmappableCharacter(CodingErrorAction.REPLACE);
        return d;
    }

    private static List<EmailAddress> bytes(String s) {
        ByteBuffer buf = ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
        return EmailAddressParser.parseEmailAddressList(buf, decoder());
    }

    // ===== String list parser =====

    @Test
    public void testListNullEmptyAndBlank() {
        assertTrue(EmailAddressParser.parseEmailAddressList((String) null).isEmpty());
        assertTrue(EmailAddressParser.parseEmailAddressList("").isEmpty());
        assertTrue(EmailAddressParser.parseEmailAddressList("  \t ").isEmpty());
        assertTrue(EmailAddressParser.parseEmailAddressList("(only a comment)").isEmpty());
    }

    @Test
    public void testListMixedAddresses() {
        String v = "Alice <a@x.org>, b@y.org (Bee), \"Q, R\" <q@z.org>";
        List<EmailAddress> l = EmailAddressParser.parseEmailAddressList(v);
        assertEquals(3, l.size());
        assertEquals("Alice", l.get(0).getDisplayName());
        assertEquals("b@y.org", l.get(1).getAddress());
        assertTrue(l.get(1).isSimpleAddress());
        assertEquals("Q, R", l.get(2).getDisplayName());
        assertFalse(l.get(2).isSimpleAddress());
    }

    @Test
    public void testListGroup() {
        List<EmailAddress> l = EmailAddressParser.parseEmailAddressList(
                "Team: a@x.org, B <b@x.org>;, c@x.org");
        assertEquals(2, l.size());
        assertTrue(l.get(0) instanceof GroupEmailAddress);
        GroupEmailAddress g = (GroupEmailAddress) l.get(0);
        assertEquals("Team", g.getGroupName());
        assertEquals(2, g.getMembers().size());
        List<EmailAddress> empty = EmailAddressParser.parseEmailAddressList("Nobody:;");
        assertEquals(1, empty.size());
        assertEquals(0, ((GroupEmailAddress) empty.get(0)).getMembers().size());
    }

    @Test
    public void testListErrorsReturnNull() {
        assertNull(EmailAddressParser.parseEmailAddressList("a@x.org b@y.org"));
        assertNull(EmailAddressParser.parseEmailAddressList("G: a@x.org"));
        assertNull(EmailAddressParser.parseEmailAddressList("G: a@x.org b@y.org;"));
        assertNull(EmailAddressParser.parseEmailAddressList(": a@x.org;"));
        assertNull(EmailAddressParser.parseEmailAddressList("Name <a@x.org"));
        assertNull(EmailAddressParser.parseEmailAddressList("Name a@x.org>"));
        assertNull(EmailAddressParser.parseEmailAddressList("<a@x.org>x"));
        assertNull(EmailAddressParser.parseEmailAddressList("localonly"));
        assertNull(EmailAddressParser.parseEmailAddressList("a@"));
        assertNull(EmailAddressParser.parseEmailAddressList("a@[1.2.3.4"));
        assertNull(EmailAddressParser.parseEmailAddressList("a@[1.2é]"));
        assertNull(EmailAddressParser.parseEmailAddressList("\"unterminated@x.org"));
        assertNull(EmailAddressParser.parseEmailAddressList("a@x.org (unterminated"));
        assertNull(EmailAddressParser.parseEmailAddressList("@x.org"));
        assertNull(EmailAddressParser.parseEmailAddressList("a..@x.org, <"));
    }

    @Test
    public void testListDomainLiteralAndQuotedLocal() {
        List<EmailAddress> l = EmailAddressParser.parseEmailAddressList(
                "\"john doe\"@[10.0.0.1], <\"a\\\"b\"@[IPv6:::1]>, x@[a\\]b]");
        assertEquals(3, l.size());
        assertEquals("\"john doe\"", l.get(0).getLocalPart());
        assertEquals("[10.0.0.1]", l.get(0).getDomain());
        assertEquals("[IPv6:::1]", l.get(1).getDomain());
        assertEquals("[a\\]b]", l.get(2).getDomain());
    }

    @Test
    public void testListSmtpUtf8() {
        assertNull(EmailAddressParser.parseEmailAddressList("用户@例え.jp", false));
        List<EmailAddress> l = EmailAddressParser.parseEmailAddressList(
                "用户@例え.jp, Élan <e@x.org>", true);
        assertEquals(2, l.size());
        assertEquals("用户", l.get(0).getLocalPart());
        assertEquals("Élan", l.get(1).getDisplayName());
    }

    @Test
    public void testListDisplayNameForms() {
        List<EmailAddress> l = EmailAddressParser.parseEmailAddressList(
                "John (the man) Smith <j@x.org>, \"Dr. J\" <d@x.org>, <bare@x.org>");
        assertEquals(3, l.size());
        assertEquals("John Smith", l.get(0).getDisplayName());
        assertEquals("Dr. J", l.get(1).getDisplayName());
        assertNull(l.get(2).getDisplayName());
        List<EmailAddress> dots = EmailAddressParser.parseEmailAddressList("a.b.c@x.y.z");
        assertEquals("a.b.c", dots.get(0).getLocalPart());
    }

    // ===== parseEmailAddress =====

    @Test
    public void testParseSingleAddress() {
        assertNull(EmailAddressParser.parseEmailAddress(null));
        assertNull(EmailAddressParser.parseEmailAddress(""));
        EmailAddress a = EmailAddressParser.parseEmailAddress("  Bob <b@x.org> (c) ");
        assertEquals("Bob", a.getDisplayName());
        assertNull(EmailAddressParser.parseEmailAddress("a@x.org trailing"));
        assertNull(EmailAddressParser.parseEmailAddress("junk"));
        assertNull(EmailAddressParser.parseEmailAddress("(just a comment)"));
    }

    // ===== envelope addresses =====

    @Test
    public void testEnvelopeStructure() {
        assertNull(EmailAddressParser.parseEnvelopeAddress(null));
        assertNull(EmailAddressParser.parseEnvelopeAddress(""));
        assertNull(EmailAddressParser.parseEnvelopeAddress("noat"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("@x.org"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a@"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a@b@c"));
        assertNotNull(EmailAddressParser.parseEnvelopeAddress("\"a@b\"@x.org"));
        assertNotNull(EmailAddressParser.parseEnvelopeAddress("\"a\\\"@b\"@x.org"));
    }

    @Test
    public void testEnvelopeLocalPartValidation() {
        StringBuilder longLocal = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            longLocal.append('a');
        }
        assertNull(EmailAddressParser.parseEnvelopeAddress(longLocal + "@x.org"));
        assertNull(EmailAddressParser.parseEnvelopeAddress(".a@x.org"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a.@x.org"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a..b@x.org"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a b@x.org"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("\"abc@x.org"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("\"a\u0001b\"@x.org"));
        assertNotNull(EmailAddressParser.parseEnvelopeAddress("\"a b\\ c\"@x.org"));
        assertNotNull(EmailAddressParser.parseEnvelopeAddress("a!#$%&'*+-/=?^_`{|}~@x.org"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("é@x.org"));
        assertNotNull(EmailAddressParser.parseEnvelopeAddress("é@x.org", true));
        assertNotNull(EmailAddressParser.parseEnvelopeAddress("\"é\"@x.org", true));
        assertNull(EmailAddressParser.parseEnvelopeAddress("\"é\"@x.org", false));
        assertNull(EmailAddressParser.parseEnvelopeAddress("\"\u0001\"@x.org", true));
    }

    @Test
    public void testEnvelopeDomainValidation() {
        assertNotNull(EmailAddressParser.parseEnvelopeAddress("a@[10.0.0.1]"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a@[10.0.0.1"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a@[10 0]"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a@[1[2]"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a@[1\\2]"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a@.x.org"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a@x.org."));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a@x..org"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a@x_y.org"));
        assertNull(EmailAddressParser.parseEnvelopeAddress("a@例.jp"));
        assertNotNull(EmailAddressParser.parseEnvelopeAddress("a@例.jp", true));
        assertNotNull(EmailAddressParser.parseEnvelopeAddress("a@x-1.org"));
        StringBuilder longDomain = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            longDomain.append('a');
        }
        assertNull(EmailAddressParser.parseEnvelopeAddress("a@" + longDomain));
    }

    // ===== package helpers =====

    @Test
    public void testClassifiers() {
        assertTrue(EmailAddressParser.isWhitespace(' '));
        assertTrue(EmailAddressParser.isWhitespace('\n'));
        assertFalse(EmailAddressParser.isWhitespace('x'));
        assertTrue(EmailAddressParser.isAtom('a'));
        assertFalse(EmailAddressParser.isAtom('.'));
        assertFalse(EmailAddressParser.isAtom(' '));
        assertFalse(EmailAddressParser.isAtom('é'));
        assertTrue(EmailAddressParser.isAtom('é', true));
        assertTrue(EmailAddressParser.isDtextChar('1'));
        assertFalse(EmailAddressParser.isDtextChar('['));
        assertFalse(EmailAddressParser.isDtextChar('\\'));
        assertFalse(EmailAddressParser.isDtextChar(' '));
    }

    @Test
    public void testSkipCommentAndQuotedHelpers() {
        char[] c = "(a (nested) \\) b) tail".toCharArray();
        int[] pos = new int[] {0};
        EmailAddressParser.skipComment(c, c.length, pos);
        assertEquals("(a (nested) \\) b)".length(), pos[0]);
        pos[0] = 1;
        EmailAddressParser.skipComment(c, c.length, pos);
        assertEquals(1, pos[0]);
        char[] open = "(open".toCharArray();
        pos[0] = 0;
        try {
            EmailAddressParser.skipComment(open, open.length, pos);
            assertTrue("expected failure", false);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        StringBuilder sb = new StringBuilder();
        char[] q = "no quote".toCharArray();
        pos[0] = 0;
        try {
            EmailAddressParser.parseQuotedString(q, q.length, pos, sb);
            assertTrue("expected failure", false);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        char[] q2 = "\"a\\".toCharArray();
        pos[0] = 0;
        try {
            EmailAddressParser.parseQuotedString(q2, q2.length, pos, sb);
            assertTrue("expected failure", false);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testParseDomainHelperErrors() {
        StringBuilder sb = new StringBuilder();
        int[] pos = new int[] {0};
        try {
            EmailAddressParser.parseDomain(new char[0], 0, pos, sb);
            assertTrue("expected failure", false);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        char[] atom = "a.b.c rest".toCharArray();
        sb.setLength(0);
        pos[0] = 0;
        EmailAddressParser.parseDomain(atom, atom.length, pos, sb);
        assertEquals("a.b.c", sb.toString());
        char[] bad = "a..b".toCharArray();
        sb.setLength(0);
        pos[0] = 0;
        try {
            EmailAddressParser.parseDomain(bad, bad.length, pos, sb);
            assertTrue("expected failure", false);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    // ===== ByteBuffer list parser =====

    @Test
    public void testBytesNullAndEmpty() {
        assertTrue(EmailAddressParser.parseEmailAddressList((ByteBuffer) null, decoder()).isEmpty());
        assertTrue(bytes("").isEmpty());
        assertTrue(bytes("   ").isEmpty());
        assertTrue(bytes("(comment only)").isEmpty());
    }

    @Test
    public void testBytesAngleAndBareAddresses() {
        List<EmailAddress> l = bytes("Alice <a@x.org>, <b@x.org>, c@x.org, d@x.org");
        assertEquals(4, l.size());
        assertEquals("Alice", l.get(0).getDisplayName());
        assertEquals("a@x.org", l.get(0).getAddress());
        assertEquals("b@x.org", l.get(1).getAddress());
        assertTrue(l.get(2).isSimpleAddress());
        assertEquals("c@x.org", l.get(2).getAddress());
        assertEquals("d@x.org", l.get(3).getAddress());
    }

    @Test
    public void testBytesQuotedDisplayNameAndEncodedWord() {
        List<EmailAddress> l = bytes("\"Doe, J\" <j@x.org>, =?UTF-8?Q?J=C3=B6rg?= <jo@x.org>");
        assertEquals(2, l.size());
        assertEquals("Doe, J", l.get(0).getDisplayName());
        assertEquals("Jörg", l.get(1).getDisplayName());
    }

    @Test
    public void testBytesQuotedLocalPartInAngles() {
        List<EmailAddress> l = bytes("<\"john doe\"@x.org>, <z@x.org>");
        assertEquals(2, l.size());
        assertEquals("john doe", l.get(0).getLocalPart());
        assertEquals("z@x.org", l.get(1).getAddress());
    }

    @Test
    public void testBytesDomainLiteral() {
        List<EmailAddress> l = bytes("<a@[10.0.0.1]>, B <b@[a\\]b]>");
        assertEquals(2, l.size());
        assertEquals("[10.0.0.1]", l.get(0).getDomain());
        assertEquals("[a\\]b]", l.get(1).getDomain());
    }

    @Test
    public void testBytesGroupsAreSkipped() {
        List<EmailAddress> l = bytes("Team: a@x.org, b@x.org; c@x.org");
        assertFalse(l.isEmpty());
        assertEquals("c@x.org", l.get(l.size() - 1).getAddress());
    }

    @Test
    public void testBytesMalformedStopsParsing() {
        assertEquals(1, bytes("<a@x.org>, <broken").size());
        assertEquals(0, bytes("<>").size());
        assertEquals(0, bytes("<a>").size());
        assertEquals(0, bytes("<a@>").size());
        assertEquals(0, bytes("<a@x.org").size());
        assertEquals(0, bytes("<\"unterminated@x.org>").size());
        assertEquals(0, bytes("<a@[1.2.3.4>").size());
        assertEquals(0, bytes("Name <").size());
        assertEquals(0, bytes("noatsign").size());
        assertEquals(0, bytes("@").size());
        assertEquals(1, bytes("a@x.org, junk").size());
        assertEquals(1, bytes("(c) a@x.org (d)").size());
        assertEquals(1, bytes("(nested (c) \\) ) a@x.org").size());
    }

    @Test
    public void testBytesPositionRespected() {
        ByteBuffer buf = ByteBuffer.wrap("xxx<a@x.org>".getBytes(StandardCharsets.UTF_8));
        buf.position(3);
        List<EmailAddress> l = EmailAddressParser.parseEmailAddressList(buf, decoder());
        assertEquals(1, l.size());
    }
}
