/*
 * ImapBodySectionTest.java
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
 * Exact-output tests for FETCH BODY[section], BINARY, BINARY.SIZE, PREVIEW
 * and THREADID on a nested multipart message with an encapsulated
 * message/rfc822 part (RFC 9051 section 6.4.5, RFC 3516, RFC 8970,
 * RFC 8474).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapBodySectionTest extends ImapSessionHarness {

    static final String EMBEDDED_HEADER =
            "From: inner@example.com\r\n"
            + "Subject: embedded\r\n"
            + "Content-Type: multipart/mixed; boundary=EMB\r\n"
            + "\r\n";

    static final String EMBEDDED_TEXT =
            "--EMB\r\n"
            + "Content-Type: text/plain\r\n"
            + "\r\n"
            + "embedded body\r\n"
            + "--EMB--";

    static final String NESTED =
            "From: a@example.com\r\n"
            + "To: b@example.com\r\n"
            + "Subject: multi\r\n"
            + "MIME-Version: 1.0\r\n"
            + "Content-Type: multipart/mixed; boundary=\"OUT\"\r\n"
            + "\r\n"
            + "preamble\r\n"
            + "--OUT\r\n"
            + "Content-Type: text/plain; charset=us-ascii\r\n"
            + "\r\n"
            + "Hello part one\r\n"
            + "--OUT\r\n"
            + "Content-Type: multipart/alternative; boundary=IN\r\n"
            + "\r\n"
            + "--IN\r\n"
            + "Content-Type: text/plain\r\n"
            + "\r\n"
            + "alt plain\r\n"
            + "--IN\r\n"
            + "Content-Type: text/html\r\n"
            + "Content-Transfer-Encoding: quoted-printable\r\n"
            + "\r\n"
            + "<p>caf=C3=A9 =\r\n"
            + "soft</p>\r\n"
            + "--IN--\r\n"
            + "--OUT\r\n"
            + "Content-Type: message/rfc822\r\n"
            + "\r\n"
            + EMBEDDED_HEADER
            + EMBEDDED_TEXT + "\r\n"
            + "--OUT\r\n"
            + "Content-Type: application/octet-stream\r\n"
            + "Content-Transfer-Encoding: base64\r\n"
            + "Content-Disposition: attachment; filename=a.bin\r\n"
            + "\r\n"
            + "SGVsbG8gd29ybGQ=\r\n"
            + "--OUT--\r\n"
            + "epilogue\r\n";

    private void nested() throws Exception {
        login();
        appendRaw("INBOX", null, NESTED);
        ok("SELECT INBOX");
    }

    @Test(timeout = 30000)
    public void numberedPartsOfANestedMultipart() throws Exception {
        nested();
        ok("FETCH 1 (BODY.PEEK[1])");
        assertRaw("* 1 FETCH (BODY[1] {14}\r\nHello part one)\r\n");
        ok("FETCH 1 (BODY.PEEK[2.1])");
        assertRaw("BODY[2.1] {9}\r\nalt plain)");
        ok("FETCH 1 (BODY.PEEK[2.2])");
        assertRaw("BODY[2.2] {24}\r\n<p>caf=C3=A9 =\r\nsoft</p>)");
        ok("FETCH 1 (BODY.PEEK[3.1])");
        assertRaw("BODY[3.1] {13}\r\nembedded body)");
        ok("FETCH 1 (BODY.PEEK[4])");
        assertRaw("BODY[4] {16}\r\nSGVsbG8gd29ybGQ=)");
    }

    @Test(timeout = 30000)
    public void mimeHeadersOfParts() throws Exception {
        nested();
        ok("FETCH 1 (BODY.PEEK[1.MIME])");
        assertRaw("BODY[1.MIME] {46}\r\nContent-Type: text/plain; "
                + "charset=us-ascii\r\n\r\n)");
        ok("FETCH 1 (BODY.PEEK[2.2.MIME])");
        assertRaw("Content-Type: text/html\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n\r\n)");
        ok("FETCH 1 (BODY.PEEK[3.1.MIME])");
        assertRaw("BODY[3.1.MIME] {28}\r\nContent-Type: text/plain\r\n\r\n)");
    }

    @Test(timeout = 30000)
    public void encapsulatedMessageSections() throws Exception {
        nested();
        ok("FETCH 1 (BODY.PEEK[3.HEADER])");
        assertRaw("BODY[3.HEADER] {" + EMBEDDED_HEADER.length() + "}\r\n"
                + EMBEDDED_HEADER + ")");
        ok("FETCH 1 (BODY.PEEK[3.TEXT])");
        assertRaw("BODY[3.TEXT] {" + EMBEDDED_TEXT.length() + "}\r\n"
                + EMBEDDED_TEXT + ")");
        ok("FETCH 1 (BODY.PEEK[3])");
        int whole = EMBEDDED_HEADER.length() + EMBEDDED_TEXT.length();
        assertRaw("BODY[3] {" + whole + "}\r\n" + EMBEDDED_HEADER
                + EMBEDDED_TEXT + ")");
        ok("FETCH 1 (BODY.PEEK[3.HEADER.FIELDS (subject)])");
        assertRaw("BODY[3.HEADER.FIELDS (subject)] {21}\r\n"
                + "Subject: embedded\r\n\r\n)");
        ok("FETCH 1 (BODY.PEEK[3.HEADER.FIELDS.NOT (subject from)])");
        assertRaw("Content-Type: multipart/mixed; boundary=EMB\r\n\r\n)");
    }

    @Test(timeout = 30000)
    public void compositeParts() throws Exception {
        nested();
        ok("FETCH 1 (BODY.PEEK[2])");
        assertRaw("BODY[2] {");
        assertRaw("--IN\r\nContent-Type: text/plain\r\n\r\nalt plain\r\n"
                + "--IN\r\n");
        assertRaw("soft</p>\r\n--IN--)");
    }

    @Test(timeout = 30000)
    public void missingSectionsAreNil() throws Exception {
        nested();
        ok("FETCH 1 (BODY.PEEK[9] BODY.PEEK[2.3] BODY.PEEK[1.TEXT]"
                + " BODY.PEEK[2.HEADER] BODY.PEEK[1.1])");
        assertRaw("BODY[9] NIL BODY[2.3] NIL BODY[1.TEXT] NIL"
                + " BODY[2.HEADER] NIL BODY[1.1] NIL)");
    }

    @Test(timeout = 30000)
    public void partialRanges() throws Exception {
        nested();
        ok("FETCH 1 (BODY.PEEK[1]<0.5>)");
        assertRaw("BODY[1]<0> {5}\r\nHello)");
        ok("FETCH 1 (BODY.PEEK[1]<6.100>)");
        assertRaw("BODY[1]<6> {8}\r\npart one)");
        ok("FETCH 1 (BODY.PEEK[1]<50.5>)");
        assertRaw("BODY[1]<14> {0}\r\n)");
        ok("FETCH 1 (BODY.PEEK[3.1]<9.4>)");
        assertRaw("BODY[3.1]<9> {4}\r\nbody)");
    }

    @Test(timeout = 30000)
    public void binaryDecodesTransferEncodings() throws Exception {
        nested();
        ok("FETCH 1 (BINARY.PEEK[4])");
        assertRaw("* 1 FETCH (BINARY[4] {11}\r\nHello world)");
        ok("FETCH 1 (BINARY.SIZE[4])");
        assertRaw("* 1 FETCH (BINARY.SIZE[4] 11)");
        ok("FETCH 1 (BINARY.PEEK[2.2])");
        assertRaw("BINARY[2.2] {17}\r\n<p>cafÃ© soft</p>)".replace(
                "Ã©", "é"));
        ok("FETCH 1 (BINARY.SIZE[2.2])");
        assertRaw("BINARY.SIZE[2.2] 17)");
        ok("FETCH 1 (BINARY.PEEK[4]<6.5>)");
        assertRaw("BINARY[4]<6> {5}\r\nworld)");
        ok("FETCH 1 (BINARY.PEEK[1])");
        assertRaw("BINARY[1] {14}\r\nHello part one)");
    }

    @Test(timeout = 30000)
    public void binaryOfCompositeOrMissingPartsIsNil() throws Exception {
        nested();
        ok("FETCH 1 (BINARY.PEEK[2] BINARY.PEEK[3] BINARY.PEEK[7])");
        assertRaw("BINARY[2] NIL BINARY[3] NIL BINARY[7] NIL)");
    }

    @Test(timeout = 30000)
    public void binaryMarksSeenButPeekDoesNot() throws Exception {
        nested();
        ok("FETCH 1 (BINARY.PEEK[1])");
        ok("FETCH 1 (FLAGS)");
        assertNotSaw("\\Seen");
        ok("FETCH 1 (BINARY[1])");
        assertRaw("BINARY[1] {14}");
        ok("FETCH 1 (FLAGS)");
        assertSaw("\\Seen");
    }

    @Test(timeout = 30000)
    public void singlePartMessageIsPartOne() throws Exception {
        login();
        appendRaw("INBOX", null, "Subject: plain\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "\r\nonly body\r\n");
        ok("SELECT INBOX");
        ok("FETCH 1 (BODY.PEEK[1] BODY.PEEK[1.MIME] BODY.PEEK[2])");
        String mime = "Subject: plain\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n\r\n";
        assertRaw("BODY[1] {11}\r\nonly body\r\n BODY[1.MIME] {"
                + mime.length() + "}\r\n" + mime);
        assertRaw(" BODY[2] NIL)");
    }

    @Test(timeout = 30000)
    public void sectionsMixWithOtherItemsAndStreamedLiterals() throws Exception {
        nested();
        ok("FETCH 1 (UID BODY.PEEK[1] FLAGS BODY.PEEK[4] RFC822.SIZE)");
        assertRaw("* 1 FETCH (UID 1 BODY[1] {14}\r\nHello part one FLAGS (");
        assertRaw(" BODY[4] {16}\r\nSGVsbG8gd29ybGQ= RFC822.SIZE ");
        ok("FETCH 1 (BODY.PEEK[TEXT] BODY.PEEK[4])");
        assertRaw("BODY[TEXT] {");
        assertRaw("--OUT--\r\nepilogue\r\n BODY[4] {16}\r\nSGVsbG8gd29ybGQ=)");
        ok("UID FETCH 1 (BODY.PEEK[1])");
        assertRaw("* 1 FETCH (BODY[1] {14}\r\nHello part one UID 1)");
    }

    @Test(timeout = 30000)
    public void previewIsTheStartOfTheFirstTextPart() throws Exception {
        nested();
        ok("FETCH 1 (PREVIEW)");
        assertRaw("* 1 FETCH (PREVIEW \"Hello part one\")");
        ok("FETCH 1 (PREVIEW (LAZY) FLAGS)");
        assertRaw("PREVIEW \"Hello part one\" FLAGS");
    }

    @Test(timeout = 30000)
    public void previewDecodesCollapsesAndTruncates() throws Exception {
        login();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            text.append("word").append(i).append("  \r\n");
        }
        appendRaw("INBOX", null, "Subject: long\r\n"
                + "Content-Type: text/plain\r\n\r\n" + text + "\r\n");
        appendRaw("INBOX", null, "Subject: qp\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n\r\n"
                + "caf=C3=A9 \"quoted\" \\\r\n");
        appendRaw("INBOX", null, "Subject: none\r\n"
                + "Content-Type: application/octet-stream\r\n\r\nbin\r\n");
        ok("SELECT INBOX");
        ok("FETCH 1 (PREVIEW)");
        String line = rawText();
        int open = line.indexOf("PREVIEW \"");
        int close = line.indexOf("\")", open);
        String preview = line.substring(open + 9, close);
        assertTrue(preview, preview.length() <= 200);
        assertTrue(preview, preview.length() >= 198);
        assertTrue(preview, preview.startsWith("word0 word1 word2 "));
        assertFalse(preview, preview.contains("  "));
        ok("FETCH 2 (PREVIEW)");
        assertRaw("PREVIEW {");
        assertRaw("café \"quoted\" \\");
        ok("FETCH 3 (PREVIEW)");
        assertRaw("PREVIEW NIL)");
    }

    @Test(timeout = 30000)
    public void threadIdIsNilAndSaveDateIsRefused() throws Exception {
        nested();
        ok("FETCH 1 (THREADID EMAILID)");
        assertRaw("THREADID NIL");
        bad("FETCH 1 (SAVEDATE)");
        ok("FETCH 1 (BODY.PEEK[1.x])");
        assertRaw("BODY[1.x] NIL)");
    }

    @Test(timeout = 30000)
    public void capabilitiesAdvertiseOnlyWhatIsImplemented() throws Exception {
        login();
        ok("CAPABILITY");
        String line = lineStarting("* CAPABILITY");
        assertContains(line, " BINARY");
        assertContains(line, " PREVIEW");
        assertNotContains(line, "SAVEDATE");
    }
}
