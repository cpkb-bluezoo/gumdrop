/*
 * EmailAddressParserTest.java
 * Copyright (C) 2025 Chris Burdess
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

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link EmailAddressParser}.
 * Tests parsing of email addresses per RFC 5322 and RFC 6531 (SMTPUTF8).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class EmailAddressParserTest {

    // ========== parseEnvelopeAddress tests ==========

    @Test
    public void testParseEnvelopeAddressSimple() {
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress("user@example.com");
        
        assertNotNull(addr);
        assertEquals("user", addr.getLocalPart());
        assertEquals("example.com", addr.getDomain());
        assertNull(addr.getDisplayName());
    }

    @Test
    public void testParseEnvelopeAddressWithDots() {
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress("john.doe@mail.example.com");
        
        assertNotNull(addr);
        assertEquals("john.doe", addr.getLocalPart());
        assertEquals("mail.example.com", addr.getDomain());
    }

    @Test
    public void testParseEnvelopeAddressWithPlusTag() {
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress("user+tag@example.com");
        
        assertNotNull(addr);
        assertEquals("user+tag", addr.getLocalPart());
    }

    @Test
    public void testParseEnvelopeAddressQuotedLocalPart() {
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress("\"john doe\"@example.com");
        
        assertNotNull(addr);
        assertEquals("\"john doe\"", addr.getLocalPart());
        assertEquals("example.com", addr.getDomain());
    }

    @Test
    public void testParseEnvelopeAddressIPLiteral() {
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress("user@[192.168.1.1]");
        
        assertNotNull(addr);
        assertEquals("user", addr.getLocalPart());
        assertEquals("[192.168.1.1]", addr.getDomain());
    }

    @Test
    public void testParseEnvelopeAddressNull() {
        assertNull(EmailAddressParser.parseEnvelopeAddress(null));
    }

    @Test
    public void testParseEnvelopeAddressEmpty() {
        assertNull(EmailAddressParser.parseEnvelopeAddress(""));
    }

    @Test
    public void testParseEnvelopeAddressNoAt() {
        assertNull(EmailAddressParser.parseEnvelopeAddress("userexample.com"));
    }

    @Test
    public void testParseEnvelopeAddressMultipleAt() {
        assertNull(EmailAddressParser.parseEnvelopeAddress("user@domain@example.com"));
    }

    @Test
    public void testParseEnvelopeAddressEmptyLocalPart() {
        assertNull(EmailAddressParser.parseEnvelopeAddress("@example.com"));
    }

    @Test
    public void testParseEnvelopeAddressEmptyDomain() {
        assertNull(EmailAddressParser.parseEnvelopeAddress("user@"));
    }

    // ========== SMTPUTF8 envelope address tests ==========

    @Test
    public void testParseEnvelopeAddressUtf8LocalPart() {
        // Without SMTPUTF8, UTF-8 in local-part should fail
        assertNull(EmailAddressParser.parseEnvelopeAddress("用户@example.com", false));
        
        // With SMTPUTF8, UTF-8 in local-part should succeed
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress("用户@example.com", true);
        assertNotNull(addr);
        assertEquals("用户", addr.getLocalPart());
        assertEquals("example.com", addr.getDomain());
    }

    @Test
    public void testParseEnvelopeAddressUtf8Domain() {
        // Without SMTPUTF8, UTF-8 in domain should fail
        assertNull(EmailAddressParser.parseEnvelopeAddress("user@例え.jp", false));
        
        // With SMTPUTF8, UTF-8 in domain (U-label) should succeed
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress("user@例え.jp", true);
        assertNotNull(addr);
        assertEquals("user", addr.getLocalPart());
        assertEquals("例え.jp", addr.getDomain());
    }

    @Test
    public void testParseEnvelopeAddressUtf8Both() {
        // Full internationalized email address
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress("用户@例え.jp", true);
        
        assertNotNull(addr);
        assertEquals("用户", addr.getLocalPart());
        assertEquals("例え.jp", addr.getDomain());
    }

    @Test
    public void testParseEnvelopeAddressUtf8Cyrillic() {
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress("почта@пример.рф", true);
        
        assertNotNull(addr);
        assertEquals("почта", addr.getLocalPart());
        assertEquals("пример.рф", addr.getDomain());
    }

    @Test
    public void testParseEnvelopeAddressUtf8Arabic() {
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress("بريد@مثال.مصر", true);
        
        assertNotNull(addr);
        assertEquals("بريد", addr.getLocalPart());
        assertEquals("مثال.مصر", addr.getDomain());
    }

    @Test
    public void testParseEnvelopeAddressUtf8MixedScript() {
        // Mixed ASCII and UTF-8
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress("user用户@example例え.com", true);
        
        assertNotNull(addr);
        assertEquals("user用户", addr.getLocalPart());
        assertEquals("example例え.com", addr.getDomain());
    }

    // ========== parseEmailAddress tests ==========

    @Test
    public void testParseEmailAddressSimple() {
        EmailAddress addr = EmailAddressParser.parseEmailAddress("user@example.com");
        
        assertNotNull(addr);
        assertEquals("user", addr.getLocalPart());
        assertEquals("example.com", addr.getDomain());
    }

    @Test
    public void testParseEmailAddressWithDisplayName() {
        EmailAddress addr = EmailAddressParser.parseEmailAddress("John Doe <john@example.com>");
        
        assertNotNull(addr);
        assertEquals("John Doe", addr.getDisplayName());
        assertEquals("john", addr.getLocalPart());
        assertEquals("example.com", addr.getDomain());
    }

    @Test
    public void testParseEmailAddressQuotedDisplayName() {
        EmailAddress addr = EmailAddressParser.parseEmailAddress("\"John Q. Doe\" <john@example.com>");
        
        assertNotNull(addr);
        // Canonical display name (no surrounding quotes)
        assertEquals("John Q. Doe", addr.getDisplayName());
        assertEquals("john", addr.getLocalPart());
    }

    @Test
    public void testParseEmailAddressWithComment() {
        EmailAddress addr = EmailAddressParser.parseEmailAddress("john@example.com (John Doe)");
        
        // Comments are typically stripped or ignored during parsing
        assertNotNull(addr);
        assertEquals("john", addr.getLocalPart());
        assertEquals("example.com", addr.getDomain());
    }

    // ========== parseEmailAddressList tests ==========

    @Test
    public void testParseEmailAddressListEmpty() {
        List<EmailAddress> addrs = EmailAddressParser.parseEmailAddressList("");
        
        assertNotNull(addrs);
        assertTrue(addrs.isEmpty());
    }

    @Test
    public void testParseEmailAddressListNull() {
        List<EmailAddress> addrs = EmailAddressParser.parseEmailAddressList(null);
        
        assertNotNull(addrs);
        assertTrue(addrs.isEmpty());
    }

    @Test
    public void testParseEmailAddressListSingle() {
        List<EmailAddress> addrs = EmailAddressParser.parseEmailAddressList("user@example.com");
        
        assertNotNull(addrs);
        assertEquals(1, addrs.size());
        assertEquals("user", addrs.get(0).getLocalPart());
    }

    @Test
    public void testParseEmailAddressListMultiple() {
        List<EmailAddress> addrs = EmailAddressParser.parseEmailAddressList(
            "user1@example.com, user2@example.com, user3@example.com");
        
        assertNotNull(addrs);
        assertEquals(3, addrs.size());
        assertEquals("user1", addrs.get(0).getLocalPart());
        assertEquals("user2", addrs.get(1).getLocalPart());
        assertEquals("user3", addrs.get(2).getLocalPart());
    }

    @Test
    public void testParseEmailAddressListWithDisplayNames() {
        List<EmailAddress> addrs = EmailAddressParser.parseEmailAddressList(
            "John Doe <john@example.com>, Jane Smith <jane@example.com>");
        
        assertNotNull(addrs);
        assertEquals(2, addrs.size());
        assertEquals("John Doe", addrs.get(0).getDisplayName());
        assertEquals("jane", addrs.get(1).getLocalPart());
    }

    // ========== SMTPUTF8 email address list tests ==========

    @Test
    public void testParseEmailAddressListUtf8() {
        List<EmailAddress> addrs = EmailAddressParser.parseEmailAddressList(
            "用户 <用户@例え.jp>, почта@пример.рф", true);
        
        assertNotNull(addrs);
        assertEquals(2, addrs.size());
        
        // First address with display name
        assertEquals("用户", addrs.get(0).getDisplayName());
        assertEquals("用户", addrs.get(0).getLocalPart());
        assertEquals("例え.jp", addrs.get(0).getDomain());
        
        // Second address without display name
        assertEquals("почта", addrs.get(1).getLocalPart());
        assertEquals("пример.рф", addrs.get(1).getDomain());
    }

    @Test
    public void testParseEmailAddressListUtf8DisplayName() {
        // UTF-8 display name with ASCII email
        List<EmailAddress> addrs = EmailAddressParser.parseEmailAddressList(
            "日本語名前 <user@example.com>", true);
        
        assertNotNull(addrs);
        assertEquals(1, addrs.size());
        assertEquals("日本語名前", addrs.get(0).getDisplayName());
        assertEquals("user", addrs.get(0).getLocalPart());
        assertEquals("example.com", addrs.get(0).getDomain());
    }

    @Test
    public void testParseEmailAddressListUtf8Fails_Without_Flag() {
        // Without SMTPUTF8 flag, UTF-8 addresses should fail to parse
        List<EmailAddress> addrs = EmailAddressParser.parseEmailAddressList(
            "用户@例え.jp", false);
        
        // Parser returns null on failure (not an empty list)
        assertNull(addrs);
    }

    // ========== Group address tests ==========

    @Test
    public void testParseGroupAddress() {
        List<EmailAddress> addrs = EmailAddressParser.parseEmailAddressList(
            "Team: user1@example.com, user2@example.com;");
        
        assertNotNull(addrs);
        assertEquals(1, addrs.size());
        assertTrue(addrs.get(0) instanceof GroupEmailAddress);
        
        GroupEmailAddress group = (GroupEmailAddress) addrs.get(0);
        assertEquals("Team", group.getGroupName());
        assertEquals(2, group.getMembers().size());
    }

    @Test
    public void testParseGroupAddressEmpty() {
        List<EmailAddress> addrs = EmailAddressParser.parseEmailAddressList("EmptyGroup:;");
        
        assertNotNull(addrs);
        assertEquals(1, addrs.size());
        assertTrue(addrs.get(0) instanceof GroupEmailAddress);
        
        GroupEmailAddress group = (GroupEmailAddress) addrs.get(0);
        assertEquals("EmptyGroup", group.getGroupName());
        assertTrue(group.getMembers().isEmpty());
    }

    // ========== Edge cases ==========

    @Test
    public void testParseAddressWithWhitespace() {
        EmailAddress addr = EmailAddressParser.parseEmailAddress("   user@example.com   ");
        
        assertNotNull(addr);
        assertEquals("user", addr.getLocalPart());
    }

    @Test
    public void testParseAddressSpecialCharsInLocalPart() {
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress("user!#$%&'*+-/=?^_`{|}~@example.com");
        
        assertNotNull(addr);
        assertEquals("user!#$%&'*+-/=?^_`{|}~", addr.getLocalPart());
    }

    @Test
    public void testParseAddressDotsInLocalPart() {
        // Multiple dots
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress("first.middle.last@example.com");
        assertNotNull(addr);
        assertEquals("first.middle.last", addr.getLocalPart());
        
        // Leading dot - invalid
        assertNull(EmailAddressParser.parseEnvelopeAddress(".user@example.com"));
        
        // Trailing dot - invalid
        assertNull(EmailAddressParser.parseEnvelopeAddress("user.@example.com"));
        
        // Consecutive dots - invalid
        assertNull(EmailAddressParser.parseEnvelopeAddress("user..name@example.com"));
    }

    @Test
    public void testParseAddressLongLocalPart() {
        // 64 characters is the max for local-part per RFC 5321
        String longLocal = "a".repeat(64);
        EmailAddress addr = EmailAddressParser.parseEnvelopeAddress(longLocal + "@example.com");
        assertNotNull(addr);
        assertEquals(longLocal, addr.getLocalPart());
        
        // 65 characters should fail
        String tooLong = "a".repeat(65);
        assertNull(EmailAddressParser.parseEnvelopeAddress(tooLong + "@example.com"));
    }
}

