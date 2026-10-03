/*
 * ImapStructureTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Exact-output tests for ENVELOPE, BODY and BODYSTRUCTURE computed from the
 * message by the streaming MIME parser (RFC 9051 section 7.5.2), plus
 * chunk-split robustness of the parser itself.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapStructureTest extends ImapSessionHarness {

    private static final String P1 = "(\"TEXT\" \"PLAIN\" (\"CHARSET\" \"us-ascii\")"
            + " NIL NIL \"7BIT\" 14 1";
    private static final String P21 = "(\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 9 1";
    private static final String P22 = "(\"TEXT\" \"HTML\" NIL NIL NIL"
            + " \"QUOTED-PRINTABLE\" 24 2";
    private static final String P4 = "(\"APPLICATION\" \"OCTET-STREAM\" NIL NIL NIL"
            + " \"BASE64\" 16";
    private static final String INNER_ENV = "(NIL \"embedded\""
            + " ((NIL NIL \"inner\" \"example.com\"))"
            + " ((NIL NIL \"inner\" \"example.com\"))"
            + " ((NIL NIL \"inner\" \"example.com\")) NIL NIL NIL NIL NIL)";
    private static final String INNER_BASIC =
            "((\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 13 1) \"MIXED\")";
    private static final String INNER_EXT =
            "((\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 13 1 NIL NIL NIL NIL)"
            + " \"MIXED\" (\"BOUNDARY\" \"EMB\") NIL NIL NIL)";

    private static int innerSize() {
        return ImapBodySectionTest.EMBEDDED_HEADER.length()
                + ImapBodySectionTest.EMBEDDED_TEXT.length();
    }

    private void nested() throws Exception {
        login();
        appendRaw("INBOX", null, ImapBodySectionTest.NESTED);
        ok("SELECT INBOX");
    }

    @Test(timeout = 30000)
    public void bodyStructureOfNestedMultipart() throws Exception {
        nested();
        ok("FETCH 1 (BODYSTRUCTURE)");
        String expected = "BODYSTRUCTURE ("
                + P1 + " NIL NIL NIL NIL)"
                + "(" + P21 + " NIL NIL NIL NIL)"
                + P22 + " NIL NIL NIL NIL) \"ALTERNATIVE\""
                + " (\"BOUNDARY\" \"IN\") NIL NIL NIL)"
                + "(\"MESSAGE\" \"RFC822\" NIL NIL NIL \"7BIT\" " + innerSize()
                + " " + INNER_ENV + " " + INNER_EXT + " 9 NIL NIL NIL NIL)"
                + P4 + " NIL (\"ATTACHMENT\" (\"FILENAME\" \"a.bin\")) NIL NIL)"
                + " \"MIXED\" (\"BOUNDARY\" \"OUT\") NIL NIL NIL)";
        assertRaw(expected);
    }

    @Test(timeout = 30000)
    public void bodyHasNoExtensionData() throws Exception {
        nested();
        ok("FETCH 1 (BODY)");
        String expected = "BODY ("
                + P1 + ")"
                + "(" + P21 + ")" + P22 + ") \"ALTERNATIVE\")"
                + "(\"MESSAGE\" \"RFC822\" NIL NIL NIL \"7BIT\" " + innerSize()
                + " " + INNER_ENV + " " + INNER_BASIC + " 9)"
                + P4 + ") \"MIXED\")";
        assertRaw(expected);
    }

    @Test(timeout = 30000)
    public void envelopeFromHeadersWithGroupsAndEncodedWords() throws Exception {
        login();
        appendRaw("INBOX", null, "Date: Mon, 05 May 2025 10:00:00 +0000\r\n"
                + "Subject: =?UTF-8?Q?caf=C3=A9?= menu\r\n"
                + "From: \"Alice Q\" <alice@example.com>\r\n"
                + "To: Team: bob@example.com, carol@example.com;,"
                + " Dave <dave@example.org>\r\n"
                + "Cc: undisclosed-recipients:;\r\n"
                + "In-Reply-To: <irt@x>\r\n"
                + "Message-ID: <m@x>\r\n"
                + "\r\nbody\r\n");
        ok("SELECT INBOX");
        ok("FETCH 1 (ENVELOPE)");
        String from = "((\"Alice Q\" NIL \"alice\" \"example.com\"))";
        assertRaw("ENVELOPE (\"Mon, 05 May 2025 10:00:00 +0000\""
                + " \"=?UTF-8?Q?caf=C3=A9?= menu\" " + from + " " + from
                + " " + from
                + " ((NIL NIL \"Team\" NIL)(NIL NIL \"bob\" \"example.com\")"
                + "(NIL NIL \"carol\" \"example.com\")(NIL NIL NIL NIL)"
                + "(\"Dave\" NIL \"dave\" \"example.org\"))"
                + " ((NIL NIL \"undisclosed-recipients\" NIL)(NIL NIL NIL NIL))"
                + " NIL \"<irt@x>\" \"<m@x>\")");
    }

    @Test(timeout = 30000)
    public void nonAsciiValuesUseLiterals() throws Exception {
        login();
        appendRaw("INBOX", null, "Subject: café\r\n"
                + "From: \"Zürich\" <z@example.com>\r\n"
                + "Content-Type: text/plain; charset=utf-8;"
                + " name*=utf-8''r%C3%A9sum%C3%A9.txt\r\n"
                + "Content-Description: déjà\r\n"
                + "\r\nx\r\n");
        ok("SELECT INBOX");
        ok("FETCH 1 (ENVELOPE BODYSTRUCTURE)");
        assertRaw("ENVELOPE (NIL {5}\r\ncafé ((");
        assertRaw("{7}\r\nZürich NIL \"z\" \"example.com\"))");
        assertRaw("(\"CHARSET\" \"utf-8\" \"NAME\" {12}\r\nrésumé.txt)");
        assertRaw(" NIL {6}\r\ndéjà \"7BIT\" 3 1");
    }

    @Test(timeout = 30000)
    public void missingHeadersGiveNilAndDefaults() throws Exception {
        login();
        appendRaw("INBOX", null, "X-Nothing: here\r\n\r\nline one\r\nline two\r\n");
        ok("SELECT INBOX");
        ok("FETCH 1 (ENVELOPE BODYSTRUCTURE BODY)");
        assertRaw("ENVELOPE (NIL NIL NIL NIL NIL NIL NIL NIL NIL NIL)");
        assertRaw("BODYSTRUCTURE (\"TEXT\" \"PLAIN\" (\"CHARSET\" \"US-ASCII\")"
                + " NIL NIL \"7BIT\" 20 2 NIL NIL NIL NIL)");
        assertRaw(" BODY (\"TEXT\" \"PLAIN\" (\"CHARSET\" \"US-ASCII\")"
                + " NIL NIL \"7BIT\" 20 2))");
    }

    @Test(timeout = 30000)
    public void extensionDataIdDescriptionMd5LanguageLocation()
            throws Exception {
        login();
        appendRaw("INBOX", null, "Content-Type: image/png; name=\"a b.png\"\r\n"
                + "Content-ID: <id1@x>\r\n"
                + "Content-Description: A picture\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "Content-MD5: Q2hlY2sgSW50ZWdyaXR5IQ==\r\n"
                + "Content-Disposition: inline; filename=a.png; size=3\r\n"
                + "Content-Language: en, fr\r\n"
                + "Content-Location: http://x/a.png\r\n"
                + "\r\nAAAA\r\n");
        ok("SELECT INBOX");
        ok("FETCH 1 (BODYSTRUCTURE)");
        assertRaw("BODYSTRUCTURE (\"IMAGE\" \"PNG\" (\"NAME\" \"a b.png\")"
                + " \"<id1@x>\" \"A picture\" \"BASE64\" 6"
                + " \"Q2hlY2sgSW50ZWdyaXR5IQ==\""
                + " (\"INLINE\" (\"FILENAME\" \"a.png\" \"SIZE\" \"3\"))"
                + " (\"en\" \"fr\") \"http://x/a.png\")");
    }

    @Test(timeout = 30000)
    public void envelopeOnlyFetchDoesNotNeedTheBody() throws Exception {
        nested();
        ok("FETCH 1 (ENVELOPE)");
        assertRaw("ENVELOPE (NIL \"multi\" ((NIL NIL \"a\" \"example.com\"))");
    }

    @Test(timeout = 30000)
    public void structureIsIndependentOfChunking() {
        byte[] data = ImapBodySectionTest.NESTED.getBytes(
                StandardCharsets.ISO_8859_1);
        String expected = structure(data, 100000);
        int[] chunks = new int[] {1, 2, 3, 5, 17, 64};
        for (int i = 0; i < chunks.length; i++) {
            assertEquals("chunk " + chunks[i], expected,
                    structure(data, chunks[i]));
        }
        assertTrue(expected, expected.startsWith("((\"TEXT\" \"PLAIN\""));
    }

    private static String structure(byte[] data, int chunk) {
        MimeSectionParser parser = MimeSectionParser.forStructure(false);
        ByteBuffer buf = ByteBuffer.allocate(MimeSectionParser.MAX_LINE * 4);
        int pos = 0;
        while (pos < data.length && !parser.isDone()) {
            int n = Math.min(Math.min(chunk, data.length - pos), buf.remaining());
            buf.put(data, pos, n);
            pos += n;
            buf.flip();
            parser.receive(buf);
            buf.compact();
        }
        buf.flip();
        parser.finish(buf);
        return parser.bodyStructure() + "|" + parser.body() + "|"
                + parser.envelope();
    }
}
