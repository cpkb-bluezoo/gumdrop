/*
 * ImapParserEdgeCasesTest.java
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

import java.text.ParseException;

import org.junit.Test;

import org.bluezoo.gumdrop.mailbox.SearchCriteria;

import static org.junit.Assert.*;

/**
 * Malformed and boundary input for {@link SearchParser}: every error branch
 * of the grammar plus the valid shorthand forms.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapParserEdgeCasesTest {

    private static SearchCriteria ok(String input) throws Exception {
        SearchCriteria c = new SearchParser(input).parse();
        assertNotNull(input, c);
        return c;
    }

    private static void bad(String input) {
        try {
            new SearchParser(input).parse();
            fail("expected ParseException for: " + input);
        } catch (ParseException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testSearchEmptyAndFlags() throws Exception {
        ok("");
        ok("   ");
        String[] keys = {"ALL", "ANSWERED", "DELETED", "DRAFT", "FLAGGED",
                "NEW", "OLD", "RECENT", "SEEN", "UNANSWERED", "UNDELETED",
                "UNDRAFT", "UNFLAGGED", "UNSEEN"};
        for (int i = 0; i < keys.length; i++) {
            ok(keys[i]);
            ok(keys[i].toLowerCase());
        }
        ok("SEEN FLAGGED UNDELETED");
    }

    @Test
    public void testSearchStringKeys() throws Exception {
        ok("BCC a CC b FROM c SUBJECT d TO e BODY f TEXT g");
        ok("SUBJECT \"quoted \\\"text\\\"\"");
        ok("SUBJECT {5}\r\nhello");
        ok("SUBJECT {5}\nhello");
        ok("SUBJECT {5}hello");
        ok("HEADER X-Thing value");
        ok("HEADER X-Thing \"two words\"");
        ok("EMAILID abc123");
        ok("KEYWORD $label UNKEYWORD other");
    }

    @Test
    public void testSearchNumbersDatesAndSets() throws Exception {
        ok("LARGER 10 SMALLER 20");
        ok("BEFORE 1-Jan-2025 ON \"02-Feb-2025\" SINCE 3-Mar-2025");
        ok("SENTBEFORE 1-Jan-2025 SENTON 2-Feb-2025 SENTSINCE 3-Mar-2025");
        ok("1");
        ok("1:5");
        ok("1,3,5:*");
        ok("*");
        ok("*:3");
        ok("UID 1");
        ok("UID 1:*");
        ok("UID 1,2:4,*");
        ok("MODSEQ 5");
        ok("MODSEQ \"/flags/\\\\Seen\" all 7");
    }

    @Test
    public void testSearchGrouping() throws Exception {
        ok("(SEEN)");
        ok("()");
        ok("(SEEN (FLAGGED UNSEEN)) DELETED");
        ok("NOT SEEN");
        ok("NOT (SEEN FLAGGED)");
        ok("OR SEEN FLAGGED");
        ok("OR (SEEN DELETED) NOT FLAGGED");
    }

    @Test
    public void testSearchMalformed() {
        bad("BOGUS");
        bad("(SEEN");
        bad("SEEN)");
        bad("NOT");
        bad("NOT )");
        bad("OR SEEN");
        bad("OR");
        bad("LARGER");
        bad("LARGER abc");
        bad("LARGER 99999999999999999999");
        bad("SUBJECT");
        bad("SUBJECT \"unterminated");
        bad("SUBJECT {abc}");
        bad("SUBJECT {5");
        bad("SUBJECT {99}\r\nshort");
        bad("SUBJECT {99999999999}x");
        bad("BEFORE");
        bad("BEFORE notadate");
        bad("BEFORE 32-Jan-2025");
        bad("BEFORE \"\"");
        bad("UID");
        bad("UID x");
        bad("UID 1:");
        bad("1:");
        bad("MODSEQ");
        bad("MODSEQ x");
        bad("MODSEQ \"entry\" all");
        bad("99999999999999999999");
    }
}
