/*
 * ImapFetchBranchTest.java
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

package org.bluezoo.gumdrop.imap;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Exercises the FETCH response builders of {@link ImapProtocolHandler}:
 * streamed (async) literals mixed with other items, range and fallback
 * handling when the content cannot be streamed, cached envelope and body
 * structure rendering from a mock IMAP descriptor, and envelopes derived from
 * raw headers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapFetchBranchTest extends ImapSessionHarness {

    /**
     * RFC 9051 section 7.5.2: a FETCH response names the data item
     * {@code BODY[section]}; the {@code .PEEK} form exists only in requests.
     */
    @Test(timeout = 30000)
    public void peekIsNotEchoedInFetchResponses() throws Exception {
        selectedInbox();
        ok("FETCH 1 (BODY.PEEK[TEXT])");
        assertSaw("* 1 FETCH (BODY[TEXT] {13}");
        assertNotSaw("PEEK");
        ok("FETCH 1 (BODY.PEEK[HEADER.FIELDS (Subject)] BODY.PEEK[1])");
        assertSaw("BODY[HEADER.FIELDS (Subject)] {");
        assertSaw(" BODY[1] {");
        assertNotSaw("PEEK");
        ok("FETCH 1 (BODY.PEEK[]<0.10>)");
        assertSaw("* 1 FETCH (BODY[]<0> {10}");
        assertNotSaw("PEEK");
        ok("FETCH 1 (FLAGS BODY.PEEK[HEADER.FIELDS (To)]<0.4>)");
        assertSaw("BODY[HEADER.FIELDS (To)]<0> {4}");
        assertNotSaw("PEEK");
    }

    @Test(timeout = 30000)
    public void asyncLiteralFollowedByOtherItems() throws Exception {
        selectedInbox();
        ok("FETCH 1 (BODY.PEEK[TEXT] FLAGS UID RFC822.SIZE INTERNALDATE"
                + " ENVELOPE BODYSTRUCTURE BODY RFC822.HEADER"
                + " BODY.PEEK[HEADER.FIELDS (Subject)])");
        assertSaw("* 1 FETCH (BODY[TEXT] {");
        assertSaw("Body of one");
        assertSaw(" FLAGS (");
        assertSaw("UID 1");
        assertSaw("RFC822.SIZE ");
        assertSaw("INTERNALDATE \"");
        assertSaw("ENVELOPE (\"");
        assertSaw("BODYSTRUCTURE (\"TEXT\" \"PLAIN\" (\"CHARSET\" \"US-ASCII\")"
                + " NIL NIL \"7BIT\" 13 1 NIL NIL NIL NIL)");
        assertSaw("RFC822.HEADER {");
        assertSaw("BODY[HEADER.FIELDS (Subject)] {");
    }

    @Test(timeout = 30000)
    public void asyncLiteralAfterLeadingItemsAndUidFetch() throws Exception {
        selectedInbox();
        ok("UID FETCH 2 (FLAGS RFC822.TEXT)");
        assertSaw("* 2 FETCH (FLAGS (");
        assertSaw("RFC822.TEXT {");
        assertSaw("UID 2");
        ok("UID FETCH 1 (BODY.PEEK[]<0.10> EMAILID)");
        assertSaw("BODY[]<0> {10}");
        assertSaw("EMAILID (");
        ok("FETCH 1 (BODY.PEEK[HEADER]<4.8>)");
        assertSaw("BODY[HEADER]<4> {8}");
        ok("FETCH 1 (BODY.PEEK[TEXT]<1000.5>)");
        assertSaw("BODY[TEXT]<");
        assertSaw("{0}");
    }

    @Test(timeout = 30000)
    public void fetchMarksUnseenMessagesSeen() throws Exception {
        selectedInbox();
        ok("FETCH 1:3 (BODY[TEXT])");
        ok("FETCH 1 (FLAGS)");
        assertSaw("\\Seen");
        ok("FETCH 3 (FLAGS)");
        assertSaw("\\Seen");
    }

    @Test(timeout = 30000)
    public void noBodyOffsetFallsBackToLoadedBytes() throws Exception {
        selectedInbox();
        mock.asyncMode = ImapMockMailboxFactory.AsyncMode.NO_BODY_OFFSET;
        ok("FETCH 1 (BODY.PEEK[HEADER] FLAGS)");
        assertSaw("BODY[HEADER] {");
        assertSaw("Subject: one");
        ok("FETCH 1 (BODY.PEEK[TEXT])");
        assertSaw("Body of one");
        ok("FETCH 1 (BODY.PEEK[])");
        assertSaw("Subject: one");
        ok("FETCH 1 (RFC822.TEXT)");
        assertSaw("RFC822.TEXT {");
        assertSaw("Body of one");
        ok("FETCH 1 (BODY.PEEK[]<0.5>)");
        assertSaw("BODY[]<0> {5}");
    }

    @Test(timeout = 30000)
    public void asyncContentUnavailableLoadsBytes() throws Exception {
        selectedInbox();
        mock.asyncMode = ImapMockMailboxFactory.AsyncMode.NONE;
        ok("FETCH 1 (BODY.PEEK[TEXT] FLAGS)");
        assertSaw("Body of one");
        mock.asyncMode = ImapMockMailboxFactory.AsyncMode.THROW;
        ok("FETCH 2 (BODY.PEEK[TEXT])");
        assertSaw("Body of two");
    }

    /**
     * Once the literal size has been announced the client expects exactly
     * that many octets, so an early end of content must drop the connection
     * rather than send a short literal followed by the tagged completion.
     */
    @Test(timeout = 30000)
    public void asyncReadReturningNothingDropsTheConnection() throws Exception {
        selectedInbox();
        mock.asyncMode = ImapMockMailboxFactory.AsyncMode.READ_ZERO;
        endpoint.clearResponses();
        send("f1 FETCH 1 (BODY.PEEK[TEXT] FLAGS)\r\n");
        assertSaw("* 1 FETCH (BODY[TEXT] {13}");
        assertNotSaw("Body of one");
        assertNotSaw("f1 OK");
        assertFalse(endpoint.isOpen());
    }

    @Test(timeout = 30000)
    public void asyncReadFailureDropsTheConnection() throws Exception {
        selectedInbox();
        mock.asyncMode = ImapMockMailboxFactory.AsyncMode.READ_FAIL;
        endpoint.clearResponses();
        send("f1 FETCH 1 (BODY.PEEK[TEXT])\r\n");
        assertSaw("* 1 FETCH (BODY[TEXT] {13}");
        assertNotSaw("Body of one");
        assertNotSaw("f1 OK");
        assertFalse(endpoint.isOpen());
    }

    @Test(timeout = 30000)
    public void condstoreAddsModseqToEveryFetchShape() throws Exception {
        selectedInbox();
        mock.modSeq = 7;
        ok("ENABLE CONDSTORE");
        ok("FETCH 1 (FLAGS)");
        assertSaw("MODSEQ (7)");
        ok("FETCH 1 (BODY.PEEK[TEXT])");
        assertSaw("MODSEQ (7)");
        ok("UID FETCH 1 (BODY.PEEK[TEXT] FLAGS)");
        assertSaw("MODSEQ (7)");
        ok("FETCH 1 (ENVELOPE)");
        assertSaw("MODSEQ (7)");
        ok("FETCH 1 (UID)");
        assertSaw("(UID 1 MODSEQ (7))");
    }

    @Test(timeout = 30000)
    public void envelopeAndBodyStructureFromDescriptor() throws Exception {
        selectedInbox();
        ImapMockDescriptor desc = new ImapMockDescriptor();
        desc.envelope = new ImapMockDescriptor.Env();
        ImapMockDescriptor.Bs text = new ImapMockDescriptor.Bs();
        text.parameters.put("charset", "utf-8");
        text.parameters.put("name", "a\"b\\c");
        text.contentId = "<cid>";
        text.description = "desc";
        text.encoding = "base64";
        text.md5 = "abc";
        text.disposition = "attachment";
        text.dispositionParameters = new java.util.LinkedHashMap<String, String>();
        text.dispositionParameters.put("filename", "f.txt");
        text.language = new String[] { "en", "fr" };
        text.location = "http://x";
        ImapMockDescriptor.Bs image = new ImapMockDescriptor.Bs();
        image.type = "image";
        image.subtype = "png";
        image.language = new String[] { "de" };
        image.disposition = "inline";
        ImapMockDescriptor.Bs plain = new ImapMockDescriptor.Bs();
        plain.language = new String[0];
        ImapMockDescriptor.Bs inner = new ImapMockDescriptor.Bs();
        inner.type = "multipart";
        inner.subtype = "alternative";
        inner.parameters.put("boundary", "zz");
        inner.parts = new ImapMockDescriptor.Bs[] { plain, image };
        ImapMockDescriptor.Bs root = new ImapMockDescriptor.Bs();
        root.type = "multipart";
        root.subtype = "mixed";
        root.parts = new ImapMockDescriptor.Bs[] { inner, text };
        desc.bodyStructure = root;
        mock.descriptors.put(Integer.valueOf(1), desc);

        ok("FETCH 1 (ENVELOPE)");
        assertSaw("ENVELOPE (\"05-May-2025 10:00:00 +0000\" \"subj\""
                + " ((\"Alice \\\"A\\\"\" NIL \"alice\" \"example.com\"))"
                + " NIL NIL"
                + " ((NIL \"route\" \"bob\" \"example.com\")"
                + " (\"Carol\" NIL \"carol\" NIL)) NIL NIL"
                + " \"<irt@x>\" \"<mid@x>\")");
        ok("FETCH 1 (BODYSTRUCTURE)");
        assertSaw("BODYSTRUCTURE (((\"text\" \"plain\" NIL NIL NIL \"7BIT\" 10 1"
                + " NIL NIL NIL NIL)(\"image\" \"png\" NIL NIL NIL \"7BIT\" 10"
                + " NIL (\"inline\" NIL) \"de\" NIL) \"alternative\""
                + " (\"boundary\" \"zz\") NIL NIL)");
        assertSaw("\"base64\" 10 1 \"abc\" (\"attachment\" (\"filename\""
                + " \"f.txt\")) (\"en\" \"fr\") \"http://x\")");
        assertSaw("(\"charset\" \"utf-8\" \"name\" \"a\\\"b\\\\c\")");
        ok("FETCH 1 (BODY)");
        assertSaw("* 1 FETCH (BODY (((\"text\" \"plain\" NIL NIL NIL \"7BIT\" 10 1)");
        ok("FETCH 1 (RFC822.SIZE FLAGS)");
        assertSaw("RFC822.SIZE 100");
    }

    @Test(timeout = 30000)
    public void envelopeFromRawHeaders() throws Exception {
        login();
        appendRaw("INBOX", null, "From: \"Alice Q\" <alice@example.com>\r\n"
                + "Sender: bare@host.org\r\n"
                + "Reply-To: <>\r\n"
                + "To: nodomain\r\n"
                + "Cc: Bob <bob@example.com>,\r\n"
                + " carol@example.com\r\n"
                + "Subject: folded\r\n"
                + " subject line\r\n"
                + "Date: Mon, 05 May 2025 10:00:00 +0000\r\n"
                + "In-Reply-To: <irt@x>\r\n"
                + "Message-Id: <m@x>\r\n"
                + "\r\nbody\r\n");
        appendRaw("INBOX", null, "Subject: only subject\r\n\r\nx\r\n");
        ok("SELECT INBOX");
        ok("FETCH 1 ENVELOPE");
        assertSaw("ENVELOPE (\"Mon, 05 May 2025 10:00:00 +0000\""
                + " \"folded subject line\""
                + " ((\"Alice Q\" NIL \"alice\" \"example.com\"))"
                + " ((NIL NIL \"bare\" \"host.org\"))");
        assertSaw("((NIL NIL \"nodomain\" NIL))");
        assertSaw("\"<irt@x>\" \"<m@x>\")");
        ok("FETCH 2 ENVELOPE");
        assertSaw("ENVELOPE (NIL \"only subject\" NIL NIL NIL NIL NIL NIL NIL NIL)");
    }

    private static final String FOLDED = "Subject: folded\r\n subject\r\n"
            + "X-Other: a\r\n\tcontinued\r\n"
            + "To: bob@example.com\r\n"
            + "\r\n"
            + "body text\r\n";

    @Test(timeout = 30000)
    public void headerFieldSelectionHonoursFolding()
            throws Exception {
        login();
        appendRaw("INBOX", null, FOLDED);
        ok("SELECT INBOX");
        ok("FETCH 1 (BODY.PEEK[HEADER.FIELDS (subject TO)])");
        assertSaw("Subject: folded");
        assertSaw(" subject");
        assertSaw("To: bob@example.com");
        assertNotSaw("X-Other");
        assertNotSaw("continued");
        ok("FETCH 1 (BODY.PEEK[HEADER.FIELDS.NOT (Subject To)])");
        assertSaw("X-Other: a");
        assertSaw("\tcontinued");
        assertNotSaw("Subject: folded");
        assertNotSaw("To: bob");
        ok("FETCH 1 (BODY.PEEK[HEADER.FIELDS ()])");
        assertSaw("BODY[HEADER.FIELDS ()] {2}");
        ok("FETCH 1 (BODY.PEEK[MIME] BODY.PEEK[1] BODY.PEEK[1.MIME])");
        assertSaw("BODY[MIME] {");
        assertSaw("BODY[1] {");
    }

    @Test(timeout = 30000)
    public void messageWithoutBodySeparatorHasEmptyText() throws Exception {
        login();
        appendRaw("INBOX", null, "Subject: bare\r\nTo: x@example.com\r\n");
        ok("SELECT INBOX");
        ok("FETCH 1 (BODY.PEEK[TEXT] BODY.PEEK[HEADER])");
        assertSaw("BODY[TEXT] {0}");
        assertSaw("BODY[HEADER] {");
        assertSaw("Subject: bare");
    }

    @Test(timeout = 30000)
    public void unknownSizeIsLoadedByReadingToTheEnd() throws Exception {
        login();
        appendRaw("INBOX", null, FOLDED);
        ok("SELECT INBOX");
        mock.asyncMode = ImapMockMailboxFactory.AsyncMode.NONE;
        ImapMockDescriptor oversized = new ImapMockDescriptor();
        oversized.size = -1;
        mock.descriptors.put(Integer.valueOf(1), oversized);
        ok("FETCH 1 (BODY.PEEK[HEADER.FIELDS (Subject)] ENVELOPE)");
        assertSaw("Subject: folded");
        assertSaw("ENVELOPE (NIL \"folded subject\" NIL NIL NIL");
        oversized.size = Long.MAX_VALUE;
        ok("FETCH 1 (RFC822.HEADER)");
        assertSaw("X-Other: a");
        oversized.size = 3;
        ok("FETCH 1 (RFC822.HEADER)");
        assertSaw("RFC822.HEADER {");
    }

    @Test(timeout = 30000)
    public void fetchItemMacrosAndMalformedItems() throws Exception {
        selectedInbox();
        ok("FETCH 1 ALL");
        assertSaw("FLAGS (");
        assertSaw("ENVELOPE (");
        ok("FETCH 1 FAST");
        assertNotSaw("ENVELOPE");
        ok("FETCH 1 FULL");
        assertSaw("BODY (");
        ok("FETCH 2 (BODY.PEEK[HEADER.FIELDS (Subject From)]<0.10> UID)");
        assertSaw("BODY[HEADER.FIELDS (Subject From)]<0> {10}");
        ok("FETCH 1 (BODY.PEEK[HEADER.FIELDS (Subject)]<oops>)");
        bad("FETCH 1 (BODY.PEEK[HEADER");
        bad("FETCH 1 BODY[");
        bad("FETCH 1 (FLAGS BOGUS)");
        bad("FETCH 1 ()x");
        ok("FETCH 1 (BINARY.PEEK[1] BINARY.SIZE[1] PREVIEW THREADID)");
        ok("FETCH 1 (  UID   FLAGS  )");
        assertSaw("UID 1");
    }

    @Test(timeout = 30000)
    public void moveReportsVanishedUidsWhenQresyncIsEnabled() throws Exception {
        selectedInbox();
        ok("CREATE Archive");
        ok("ENABLE QRESYNC");
        ok("MOVE 1 Archive");
        assertSaw("VANISHED 1");
        ok("UID MOVE 2 Archive");
        assertSaw("VANISHED 2");
    }
}
