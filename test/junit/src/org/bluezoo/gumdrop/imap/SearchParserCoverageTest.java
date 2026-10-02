/*
 * SearchParserCoverageTest.java
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

import java.io.IOException;
import java.text.ParseException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.MessageContext;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;

import static org.junit.Assert.*;

/**
 * Evaluates the criteria produced by {@link SearchParser} against stub
 * messages, plus the quoting, literal and error paths of the parser.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SearchParserCoverageTest {

    private static MessageContext message(final int number, final long uid) {
        return new MessageContext() {
            @Override
            public int getMessageNumber() {
                return number;
            }

            @Override
            public long getUID() {
                return uid;
            }

            @Override
            public long getSize() {
                return 100L;
            }

            @Override
            public Set<Flag> getFlags() {
                return EnumSet.of(Flag.SEEN);
            }

            @Override
            public OffsetDateTime getInternalDate() {
                return OffsetDateTime.of(2025, 5, 5, 10, 0, 0, 0, ZoneOffset.UTC);
            }

            @Override
            public String getHeader(String name) {
                return null;
            }

            @Override
            public List<String> getHeaders(String name) {
                return Collections.emptyList();
            }

            @Override
            public OffsetDateTime getSentDate() {
                return OffsetDateTime.of(2025, 5, 5, 10, 0, 0, 0, ZoneOffset.UTC);
            }

            @Override
            public CharSequence getHeadersText() {
                return "Subject: hi";
            }

            @Override
            public CharSequence getBodyText() {
                return "body";
            }
        };
    }

    private static SearchCriteria parse(String input) throws ParseException {
        SearchParser parser = new SearchParser(input);
        return parser.parse();
    }

    private static boolean matches(String input, int number, long uid)
            throws Exception {
        SearchCriteria criteria = parse(input);
        MessageContext context = message(number, uid);
        return criteria.matches(context);
    }

    private static void rejects(String input) {
        try {
            parse(input);
            fail("expected ParseException for: " + input);
        } catch (ParseException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testSequenceSetOfThreeMatchesEveryMember() throws Exception {
        assertTrue(matches("1,2,3", 1, 10L));
        assertTrue(matches("1,2,3", 2, 20L));
        assertTrue(matches("1,2,3", 3, 30L));
        assertFalse(matches("1,2,3", 4, 40L));
    }

    @Test
    public void testSequenceSetMixedRanges() throws Exception {
        assertTrue(matches("1,5:7,9,11:*", 6, 1L));
        assertTrue(matches("1,5:7,9,11:*", 9, 1L));
        assertTrue(matches("1,5:7,9,11:*", 50, 1L));
        assertFalse(matches("1,5:7,9,11:*", 8, 1L));
        assertTrue(matches("1,2", 2, 1L));
        assertTrue(matches("*", Integer.MAX_VALUE, 1L));
    }

    @Test
    public void testUidSets() throws Exception {
        assertTrue(matches("UID 10", 1, 10L));
        assertTrue(matches("UID 10:20", 1, 15L));
        assertTrue(matches("UID 5,10:20,30", 1, 30L));
        assertTrue(matches("UID 5,10:20,30", 1, 5L));
        assertFalse(matches("UID 5,10:20,30", 1, 25L));
        assertTrue(matches("UID 1:*", 1, 99999L));
        rejects("UID");
        rejects("UID x");
        rejects("1:");
        rejects("UID 1:");
    }

    @Test
    public void testSimpleKeywords() throws Exception {
        String[] keys = new String[] {
            "ALL", "ANSWERED", "DELETED", "DRAFT", "FLAGGED", "NEW", "OLD",
            "RECENT", "SEEN", "UNANSWERED", "UNDELETED", "UNDRAFT",
            "UNFLAGGED", "UNSEEN"
        };
        for (int i = 0; i < keys.length; i++) {
            SearchCriteria c = parse(keys[i].toLowerCase());
            assertNotNull(keys[i], c);
        }
        assertTrue(matches("SEEN", 1, 1L));
        assertFalse(matches("UNSEEN", 1, 1L));
        assertFalse(matches("DELETED", 1, 1L));
    }

    @Test
    public void testArgumentKeywords() throws Exception {
        String[] keys = new String[] {
            "BCC a", "CC a", "FROM a", "SUBJECT \"a b\"", "TO a",
            "HEADER Subject hi", "HEADER X-Thing {3}\r\nabc", "BODY a",
            "TEXT {2}\r\nab", "BEFORE 1-Jan-2099", "ON 5-May-2025",
            "SINCE \"1-Jan-2000\"", "SENTBEFORE 1-Jan-2099",
            "SENTON 5-May-2025", "SENTSINCE 1-Jan-2000", "LARGER 10",
            "SMALLER 1000", "KEYWORD foo", "UNKEYWORD foo", "EMAILID abc",
            "MODSEQ 5", "MODSEQ \"/flags/\\\\Seen\" all 5"
        };
        for (int i = 0; i < keys.length; i++) {
            SearchCriteria c = parse(keys[i]);
            assertNotNull(keys[i], c);
        }
        assertTrue(matches("LARGER 10", 1, 1L));
        assertFalse(matches("SMALLER 10", 1, 1L));
        assertTrue(matches("SINCE 1-Jan-2000", 1, 1L));
        assertTrue(matches("BEFORE 1-Jan-2099", 1, 1L));
    }

    @Test
    public void testBooleanOperatorsAndGroups() throws Exception {
        assertTrue(matches("NOT UNSEEN", 1, 1L));
        assertTrue(matches("OR UNSEEN SEEN", 1, 1L));
        assertFalse(matches("OR UNSEEN DELETED", 1, 1L));
        assertTrue(matches("(SEEN LARGER 10)", 1, 1L));
        assertFalse(matches("(SEEN (DELETED))", 1, 1L));
        assertTrue(matches("SEEN 1:5 LARGER 1", 3, 1L));
        assertTrue(matches("", 1, 1L));
        assertTrue(matches("()", 1, 1L));
    }

    @Test
    public void testErrors() {
        rejects("BOGUS");
        rejects("(SEEN");
        rejects("SEEN)");
        rejects("FROM");
        rejects("FROM \"unterminated");
        rejects("FROM {");
        rejects("FROM {x}");
        rejects("FROM {5}\r\nab");
        rejects("FROM {99999999999}");
        rejects("LARGER");
        rejects("LARGER x");
        rejects("LARGER 99999999999999999999999");
        rejects("BEFORE");
        rejects("BEFORE notadate");
        rejects("BEFORE \"\"");
        rejects("MODSEQ");
        rejects("MODSEQ x");
        rejects("NOT");
        rejects("OR SEEN");
        rejects("1:");
        rejects("HEADER");
        rejects("EMAILID");
    }
}
