/*
 * IMAPResponseTest.java
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

package org.bluezoo.gumdrop.imap.client;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link IMAPResponse}.
 */
public class IMAPResponseTest {

    // ── Tagged OK responses ──

    @Test
    public void testParseTaggedOk() {
        IMAPResponse r = IMAPResponse.parse("A001 OK LOGIN completed");
        assertNotNull(r);
        assertTrue(r.isTagged());
        assertTrue(r.isOk());
        assertEquals("A001", r.getTag());
        assertEquals("LOGIN completed", r.getMessage());
        assertNull(r.getResponseCode());
    }

    @Test
    public void testParseTaggedOkWithResponseCode() {
        IMAPResponse r = IMAPResponse.parse(
                "A002 OK [READ-WRITE] SELECT completed");
        assertNotNull(r);
        assertTrue(r.isTagged());
        assertTrue(r.isOk());
        assertEquals("A002", r.getTag());
        assertEquals("READ-WRITE", r.getResponseCode());
        assertEquals("SELECT completed", r.getMessage());
    }

    @Test
    public void testParseTaggedOkWithCapabilityCode() {
        IMAPResponse r = IMAPResponse.parse(
                "A003 OK [CAPABILITY IMAP4rev1 IDLE] Logged in");
        assertNotNull(r);
        assertTrue(r.isOk());
        assertEquals("CAPABILITY IMAP4rev1 IDLE",
                r.getResponseCode());
        assertEquals("Logged in", r.getMessage());
    }

    @Test
    public void testParseTaggedOkWithAppendUid() {
        IMAPResponse r = IMAPResponse.parse(
                "A004 OK [APPENDUID 38505 3955] APPEND completed");
        assertNotNull(r);
        assertTrue(r.isOk());
        assertEquals("APPENDUID 38505 3955", r.getResponseCode());
    }

    @Test
    public void testParseTaggedOkMinimal() {
        IMAPResponse r = IMAPResponse.parse("A001 OK");
        assertNotNull(r);
        assertTrue(r.isTagged());
        assertTrue(r.isOk());
        assertEquals("A001", r.getTag());
    }

    // ── Tagged NO responses ──

    @Test
    public void testParseTaggedNo() {
        IMAPResponse r = IMAPResponse.parse(
                "A005 NO [AUTHENTICATIONFAILED] Invalid credentials");
        assertNotNull(r);
        assertTrue(r.isTagged());
        assertTrue(r.isNo());
        assertFalse(r.isOk());
        assertEquals("A005", r.getTag());
        assertEquals("AUTHENTICATIONFAILED", r.getResponseCode());
        assertEquals("Invalid credentials", r.getMessage());
    }

    @Test
    public void testParseTaggedNoSimple() {
        IMAPResponse r = IMAPResponse.parse("A006 NO Mailbox not found");
        assertNotNull(r);
        assertTrue(r.isNo());
        assertEquals("Mailbox not found", r.getMessage());
        assertNull(r.getResponseCode());
    }

    // ── Tagged BAD responses ──

    @Test
    public void testParseTaggedBad() {
        IMAPResponse r = IMAPResponse.parse("A007 BAD Syntax error");
        assertNotNull(r);
        assertTrue(r.isTagged());
        assertTrue(r.isBad());
        assertFalse(r.isOk());
        assertFalse(r.isNo());
        assertEquals("Syntax error", r.getMessage());
    }

    // ── Untagged OK responses ──

    @Test
    public void testParseUntaggedOk() {
        IMAPResponse r = IMAPResponse.parse(
                "* OK [CAPABILITY IMAP4rev1 STARTTLS] Welcome");
        assertNotNull(r);
        assertTrue(r.isUntagged());
        assertTrue(r.isOk());
        assertNull(r.getTag());
        assertEquals("CAPABILITY IMAP4rev1 STARTTLS",
                r.getResponseCode());
        assertEquals("Welcome", r.getMessage());
    }

    @Test
    public void testParseUntaggedOkNoCode() {
        IMAPResponse r = IMAPResponse.parse("* OK Server ready");
        assertNotNull(r);
        assertTrue(r.isUntagged());
        assertTrue(r.isOk());
        assertNull(r.getResponseCode());
        assertEquals("Server ready", r.getMessage());
    }

    // ── Untagged data responses ──

    @Test
    public void testParseUntaggedData() {
        IMAPResponse r = IMAPResponse.parse("* 172 EXISTS");
        assertNotNull(r);
        assertTrue(r.isUntagged());
        assertNull(r.getStatus());
        assertEquals("172 EXISTS", r.getMessage());
    }

    @Test
    public void testParseUntaggedCapability() {
        IMAPResponse r = IMAPResponse.parse(
                "* CAPABILITY IMAP4rev1 STARTTLS AUTH=PLAIN");
        assertNotNull(r);
        assertTrue(r.isUntagged());
        assertNull(r.getStatus());
        assertEquals("CAPABILITY IMAP4rev1 STARTTLS AUTH=PLAIN",
                r.getMessage());
    }

    @Test
    public void testParseUntaggedList() {
        IMAPResponse r = IMAPResponse.parse(
                "* LIST (\\HasNoChildren) \"/\" \"INBOX\"");
        assertNotNull(r);
        assertTrue(r.isUntagged());
        assertEquals("LIST (\\HasNoChildren) \"/\" \"INBOX\"",
                r.getMessage());
    }

    @Test
    public void testParseUntaggedSearch() {
        IMAPResponse r = IMAPResponse.parse("* SEARCH 2 3 6");
        assertNotNull(r);
        assertTrue(r.isUntagged());
        assertEquals("SEARCH 2 3 6", r.getMessage());
    }

    @Test
    public void testParseUntaggedFetch() {
        IMAPResponse r = IMAPResponse.parse(
                "* 1 FETCH (FLAGS (\\Seen) UID 42)");
        assertNotNull(r);
        assertTrue(r.isUntagged());
        assertEquals("1 FETCH (FLAGS (\\Seen) UID 42)",
                r.getMessage());
    }

    @Test
    public void testParseUntaggedBye() {
        IMAPResponse r = IMAPResponse.parse("* BYE Server shutting down");
        assertNotNull(r);
        assertTrue(r.isUntagged());
        assertNull(r.getStatus());
        assertEquals("BYE Server shutting down", r.getMessage());
    }

    // ── Untagged OK with response codes ──

    @Test
    public void testParseUntaggedOkPermanentFlags() {
        IMAPResponse r = IMAPResponse.parse(
                "* OK [PERMANENTFLAGS (\\Deleted \\Seen \\*)] Limited");
        assertNotNull(r);
        assertTrue(r.isOk());
        assertEquals("PERMANENTFLAGS (\\Deleted \\Seen \\*)",
                r.getResponseCode());
    }

    @Test
    public void testParseUntaggedOkUidValidity() {
        IMAPResponse r = IMAPResponse.parse(
                "* OK [UIDVALIDITY 3857529045] UIDs valid");
        assertNotNull(r);
        assertTrue(r.isOk());
        assertEquals("UIDVALIDITY 3857529045",
                r.getResponseCode());
    }

    @Test
    public void testParseUntaggedOkUidNext() {
        IMAPResponse r = IMAPResponse.parse(
                "* OK [UIDNEXT 4392] Predicted next UID");
        assertNotNull(r);
        assertTrue(r.isOk());
        assertEquals("UIDNEXT 4392", r.getResponseCode());
    }

    // ── Continuation responses ──

    @Test
    public void testParseContinuation() {
        IMAPResponse r = IMAPResponse.parse("+ Ready for literal data");
        assertNotNull(r);
        assertTrue(r.isContinuation());
        assertFalse(r.isTagged());
        assertFalse(r.isUntagged());
        assertEquals("Ready for literal data", r.getMessage());
    }

    @Test
    public void testParseContinuationEmpty() {
        IMAPResponse r = IMAPResponse.parse("+");
        assertNotNull(r);
        assertTrue(r.isContinuation());
        assertEquals("", r.getMessage());
    }

    @Test
    public void testParseContinuationSaslChallenge() {
        IMAPResponse r = IMAPResponse.parse("+ dGVzdA==");
        assertNotNull(r);
        assertTrue(r.isContinuation());
        assertEquals("dGVzdA==", r.getMessage());
    }

    // ── Literal size parsing ──

    @Test
    public void testParseLiteralSize() {
        assertEquals(342,
                IMAPResponse.parseLiteralSize(
                        "* 1 FETCH (BODY[TEXT] {342}"));
    }

    @Test
    public void testParseLiteralSizeLarge() {
        assertEquals(100000,
                IMAPResponse.parseLiteralSize(
                        "BODY[] {100000}"));
    }

    @Test
    public void testParseLiteralSizeNoLiteral() {
        assertEquals(-1,
                IMAPResponse.parseLiteralSize(
                        "* 1 FETCH (FLAGS (\\Seen))"));
    }

    @Test
    public void testParseLiteralSizeNull() {
        assertEquals(-1, IMAPResponse.parseLiteralSize(null));
    }

    @Test
    public void testParseLiteralSizeInvalid() {
        assertEquals(-1,
                IMAPResponse.parseLiteralSize("{abc}"));
    }

    // ── Invalid input ──

    @Test
    public void testParseNull() {
        assertNull(IMAPResponse.parse(null));
    }

    @Test
    public void testParseEmpty() {
        assertNull(IMAPResponse.parse(""));
    }

    @Test
    public void testParseNoSpace() {
        assertNull(IMAPResponse.parse("garbage"));
    }

    // ── toString ──

    @Test
    public void testToStringTaggedOk() {
        IMAPResponse r = IMAPResponse.parse("A001 OK Done");
        assertNotNull(r);
        String s = r.toString();
        assertTrue(s.contains("A001"));
        assertTrue(s.contains("OK"));
        assertTrue(s.contains("Done"));
    }

    @Test
    public void testToStringUntagged() {
        IMAPResponse r = IMAPResponse.parse("* 5 EXISTS");
        assertNotNull(r);
        String s = r.toString();
        assertTrue(s.contains("*"));
        assertTrue(s.contains("5 EXISTS"));
    }

    @Test
    public void testToStringContinuation() {
        IMAPResponse r = IMAPResponse.parse("+ go ahead");
        assertNotNull(r);
        String s = r.toString();
        assertTrue(s.contains("+"));
        assertTrue(s.contains("go ahead"));
    }

    @Test
    public void testToStringWithResponseCode() {
        IMAPResponse r = IMAPResponse.parse(
                "A001 OK [READ-WRITE] Selected");
        assertNotNull(r);
        String s = r.toString();
        assertTrue(s.contains("[READ-WRITE]"));
    }

    // ── Type/Status accessors ──

    @Test
    public void testTypeAccessors() {
        IMAPResponse tagged = IMAPResponse.parse("A001 OK Done");
        assertNotNull(tagged);
        assertEquals(IMAPResponse.Type.TAGGED, tagged.getType());

        IMAPResponse untagged = IMAPResponse.parse("* OK Hello");
        assertNotNull(untagged);
        assertEquals(IMAPResponse.Type.UNTAGGED, untagged.getType());

        IMAPResponse cont = IMAPResponse.parse("+ ready");
        assertNotNull(cont);
        assertEquals(IMAPResponse.Type.CONTINUATION, cont.getType());
    }

    @Test
    public void testStatusAccessors() {
        IMAPResponse ok = IMAPResponse.parse("A001 OK Done");
        assertNotNull(ok);
        assertEquals(IMAPResponse.Status.OK, ok.getStatus());

        IMAPResponse no = IMAPResponse.parse("A001 NO Fail");
        assertNotNull(no);
        assertEquals(IMAPResponse.Status.NO, no.getStatus());

        IMAPResponse bad = IMAPResponse.parse("A001 BAD Error");
        assertNotNull(bad);
        assertEquals(IMAPResponse.Status.BAD, bad.getStatus());
    }
}
