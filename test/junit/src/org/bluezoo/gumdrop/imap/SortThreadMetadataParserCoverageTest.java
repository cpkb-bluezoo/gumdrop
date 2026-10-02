/*
 * SortThreadMetadataParserCoverageTest.java
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
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Argument parsing for SORT, THREAD, GETMETADATA and SETMETADATA: every
 * accepted form and each malformed-input branch, plus the small value
 * classes the parsers produce.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SortThreadMetadataParserCoverageTest {

    private static void sortRejects(String input) {
        SortParser parser = new SortParser(input);
        try {
            parser.parse();
            fail("expected ParseException for: " + input);
        } catch (ParseException e) {
            assertNotNull(e.getMessage());
        }
    }

    private static void threadRejects(String input) {
        ThreadParser parser = new ThreadParser(input);
        try {
            parser.parse();
            fail("expected ParseException for: " + input);
        } catch (ParseException e) {
            assertNotNull(e.getMessage());
        }
    }

    private static void getRejects(String input) {
        ImapMetadataParser parser = new ImapMetadataParser(input);
        try {
            parser.parseGet();
            fail("expected ParseException for: " + input);
        } catch (ParseException e) {
            assertNotNull(e.getMessage());
        }
    }

    private static void setRejects(String input) {
        ImapMetadataParser parser = new ImapMetadataParser(input);
        try {
            parser.parseSet();
            fail("expected ParseException for: " + input);
        } catch (ParseException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testSortAccepted() throws Exception {
        SortParser parser = new SortParser(
                "(REVERSE DATE subject \"from\") \"UTF-8\" ALL");
        SortRequest request = parser.parse();
        List<SortCriterion> program = request.getSortProgram();
        assertEquals(3, program.size());
        assertTrue(program.get(0).isReverse());
        assertEquals(SortKey.DATE, program.get(0).getKey());
        assertFalse(program.get(1).isReverse());
        assertEquals(SortKey.SUBJECT, program.get(1).getKey());
        assertEquals(SortKey.FROM, program.get(2).getKey());
        assertEquals("UTF-8", request.getCharset());
        assertNotNull(request.getSearchCriteria());
        parser = new SortParser("  (SIZE)\tUS-ASCII SEEN");
        request = parser.parse();
        assertEquals("US-ASCII", request.getCharset());
        assertEquals(SortKey.SIZE, request.getSortProgram().get(0).getKey());
        parser = new SortParser("(ARRIVAL CC TO) \"a\\\"b\" ALL");
        request = parser.parse();
        assertEquals("a\"b", request.getCharset());
    }

    @Test
    public void testSortRejected() {
        sortRejects(null);
        sortRejects("");
        sortRejects("DATE UTF-8 ALL");
        sortRejects("(DATE");
        sortRejects("()");
        sortRejects("(BOGUS) UTF-8 ALL");
        sortRejects("(REVERSE) UTF-8 ALL");
        sortRejects("(DATE)");
        sortRejects("(DATE) UTF-8");
        sortRejects("(DATE) \"unterminated");
        sortRejects("(\"unterminated");
        sortRejects("(DATE) UTF-8 BOGUSKEY");
        sortRejects("(()) UTF-8 ALL");
    }

    @Test
    public void testThreadAccepted() throws Exception {
        ThreadParser parser = new ThreadParser("REFERENCES UTF-8 ALL");
        ThreadRequest request = parser.parse();
        assertEquals(ThreadAlgorithm.REFERENCES, request.getAlgorithm());
        assertEquals("UTF-8", request.getCharset());
        assertNotNull(request.getSearchCriteria());
        parser = new ThreadParser("orderedsubject \"UTF-8\" SEEN");
        request = parser.parse();
        assertEquals(ThreadAlgorithm.ORDEREDSUBJECT, request.getAlgorithm());
        parser = new ThreadParser("REFERENCES \"a\\\\b\" ALL");
        request = parser.parse();
        assertEquals("a\\b", request.getCharset());
    }

    @Test
    public void testThreadRejected() {
        threadRejects(null);
        threadRejects("");
        threadRejects("BOGUS UTF-8 ALL");
        threadRejects("(REFERENCES");
        threadRejects("REFERENCES");
        threadRejects("REFERENCES UTF-8");
        threadRejects("REFERENCES \"unterminated");
        threadRejects("REFERENCES UTF-8 BOGUSKEY");
        threadRejects("REFERENCES (");
    }

    @Test
    public void testAlgorithmAndKeyTokens() {
        assertNull(ThreadAlgorithm.fromToken(null));
        assertNull(ThreadAlgorithm.fromToken("nope"));
        assertEquals(ThreadAlgorithm.REFERENCES,
                ThreadAlgorithm.fromToken("references"));
    }

    @Test
    public void testGetMetadataForms() throws Exception {
        ImapMetadataParser parser = new ImapMetadataParser(
                "INBOX /private/comment");
        ImapMetadataGetRequest request = parser.parseGet();
        assertEquals("INBOX", request.getMailboxName());
        assertEquals(-1, request.getMaxSize());
        assertEquals(0, request.getDepth());
        assertEquals("/private/comment", request.getEntryNames().get(0));
        assertFalse(request.isServerMetadata());

        parser = new ImapMetadataParser("\"\" (/shared/a private/b)");
        request = parser.parseGet();
        assertTrue(request.isServerMetadata());
        assertEquals(2, request.getEntryNames().size());
        assertEquals("/private/b", request.getEntryNames().get(1));

        parser = new ImapMetadataParser("(MAXSIZE 20 DEPTH infinity) INBOX /private");
        request = parser.parseGet();
        assertEquals(20, request.getMaxSize());
        assertEquals(Integer.MAX_VALUE, request.getDepth());

        parser = new ImapMetadataParser("INBOX (DEPTH 1) (/private/a)");
        request = parser.parseGet();
        assertEquals(1, request.getDepth());
        assertEquals(1, request.getEntryNames().size());

        parser = new ImapMetadataParser("INBOX (DEPTH 0 MAXSIZE 7) \"/private/x\"");
        request = parser.parseGet();
        assertEquals(0, request.getDepth());
        assertEquals(7, request.getMaxSize());
        assertEquals("/private/x", request.getEntryNames().get(0));

        parser = new ImapMetadataParser("(/private/a /private/b)");
        request = parser.parseGet();
        assertEquals("", request.getMailboxName());
        assertEquals(2, request.getEntryNames().size());
    }

    @Test
    public void testGetMetadataRejected() {
        getRejects(null);
        getRejects("");
        getRejects("INBOX");
        getRejects("INBOX (MAXSIZE x) /private");
        getRejects("INBOX (DEPTH 7) /private");
        getRejects("INBOX (DEPTH) /private");
        getRejects("INBOX (/private/a");
        getRejects("\"unterminated");
        getRejects("INBOX (DEPTH 1)");
    }

    @Test
    public void testSetMetadataForms() throws Exception {
        ImapMetadataParser parser = new ImapMetadataParser(
                "INBOX (/private/comment \"hello \\\"x\\\"\" /private/n NIL "
                + "private/atom value)");
        ImapMetadataSetRequest request = parser.parseSet();
        assertEquals("INBOX", request.getMailboxName());
        List<ImapMetadataSetRequest.EntryValue> entries = request.getEntries();
        assertEquals(3, entries.size());
        assertEquals("hello \"x\"", entries.get(0).value);
        assertNull(entries.get(1).value);
        assertEquals("/private/atom", entries.get(2).entryName);
        assertEquals("value", entries.get(2).value);

        parser = new ImapMetadataParser("\"\" (/shared/admin \"x\")");
        request = parser.parseSet();
        assertEquals("", request.getMailboxName());
        parser = new ImapMetadataParser("INBOX ()");
        request = parser.parseSet();
        assertTrue(request.getEntries().isEmpty());
    }

    @Test
    public void testSetMetadataRejected() {
        setRejects(null);
        setRejects("");
        setRejects("INBOX");
        setRejects("INBOX /private/a");
        setRejects("INBOX (/private/a");
        setRejects("INBOX (/private/a)");
        setRejects("INBOX (/private/a {5})");
        setRejects("INBOX (/private/a \"unterminated)");
    }

    @Test
    public void testDepthMustBeZeroOneOrInfinity() throws Exception {
        getRejects("INBOX (DEPTH 10) /private");
        getRejects("INBOX (DEPTH 2) /private");
        getRejects("INBOX (DEPTH 1x) /private");
        ImapMetadataParser parser = new ImapMetadataParser("INBOX (DEPTH INFINITY) /private");
        assertEquals(Integer.MAX_VALUE, parser.parseGet().getDepth());
        parser = new ImapMetadataParser("INBOX (DEPTH 1) /private");
        assertEquals(1, parser.parseGet().getDepth());
        parser = new ImapMetadataParser("INBOX (DEPTH 0) /private");
        assertEquals(0, parser.parseGet().getDepth());
    }

    @Test
    public void testTrailingInputRejected() {
        getRejects("INBOX ()x");
        getRejects("INBOX (/private/a) x");
        getRejects("INBOX /private/a x");
        getRejects("INBOX (DEPTH 1) /private/a x");
        setRejects("INBOX (/private/a \"v\") x");
    }

    @Test
    public void testEntryNamesAndScope() {
        assertFalse(ImapMetadataEntryNames.isValidEntryName(null));
        assertFalse(ImapMetadataEntryNames.isValidEntryName(""));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private/*"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private/%"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private//x"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private/x/"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("private/x"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/other/x"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private/é"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private/vendor/a"));
        assertTrue(ImapMetadataEntryNames.isValidEntryName("/private/vendor/a/b"));
        assertTrue(ImapMetadataEntryNames.isValidEntryName("/shared/comment"));
        assertTrue(ImapMetadataEntryNames.isReadOnly("/SHARED/ADMIN"));
        assertFalse(ImapMetadataEntryNames.isReadOnly("/shared/comment"));
        assertNull(ImapMetadataEntryNames.canonicalEntryName(null));
        assertEquals("/shared/comment",
                ImapMetadataEntryNames.canonicalEntryName("/Shared/Comment"));
        assertNull(ImapMetadataScope.fromEntryName(null));
        assertNull(ImapMetadataScope.fromEntryName("/other"));
        assertEquals(ImapMetadataScope.SHARED,
                ImapMetadataScope.fromEntryName("/Shared"));
        assertEquals(ImapMetadataScope.PRIVATE,
                ImapMetadataScope.fromEntryName("/private/x"));
        assertEquals("/private", ImapMetadataScope.PRIVATE.getPrefix());
    }
}
