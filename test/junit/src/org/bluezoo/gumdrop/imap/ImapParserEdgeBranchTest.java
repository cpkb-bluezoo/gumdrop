/*
 * ImapParserEdgeBranchTest.java
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
import java.text.ParseException;
import java.util.List;
import java.util.zip.DataFormatException;

import org.junit.Test;

import org.bluezoo.gumdrop.mailbox.SearchCriteria;

import static org.junit.Assert.*;

/**
 * Error and boundary inputs for the IMAP argument parsers (SEARCH, SORT,
 * THREAD, METADATA, NOTIFY), base-subject extraction and the deflate layer.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapParserEdgeBranchTest {

    private static SearchCriteria search(String in) throws ParseException {
        SearchParser parser = new SearchParser(in);
        return parser.parse();
    }

    private static SortRequest sort(String in) throws ParseException {
        SortParser parser = new SortParser(in);
        return parser.parse();
    }

    private static ThreadRequest thread(String in) throws ParseException {
        ThreadParser parser = new ThreadParser(in);
        return parser.parse();
    }

    private static ImapMetadataGetRequest metaGet(String in)
            throws ParseException {
        ImapMetadataParser parser = new ImapMetadataParser(in);
        return parser.parseGet();
    }

    private static ImapMetadataSetRequest metaSet(String in)
            throws ParseException {
        ImapMetadataParser parser = new ImapMetadataParser(in);
        return parser.parseSet();
    }

    private static ImapNotifyRequest notify(String in) throws ParseException {
        ImapNotifyParser parser = new ImapNotifyParser(in);
        return parser.parse();
    }

    private static void assertMessage(ParseException e, String fragment) {
        String message = e.getMessage();
        assertTrue(message, message.contains(fragment));
    }

    private static void sortFails(String input, String fragment) {
        try {
            sort(input);
            fail("expected ParseException for: " + input);
        } catch (ParseException e) {
            assertMessage(e, fragment);
        }
    }

    private static void threadFails(String input, String fragment) {
        try {
            thread(input);
            fail("expected ParseException for: " + input);
        } catch (ParseException e) {
            assertMessage(e, fragment);
        }
    }

    private static void searchFails(String input, String fragment) {
        try {
            search(input);
            fail("expected ParseException for: " + input);
        } catch (ParseException e) {
            assertMessage(e, fragment);
        }
    }

    private static void getFails(String input, String fragment) {
        try {
            metaGet(input);
            fail("expected ParseException for: " + input);
        } catch (ParseException e) {
            assertMessage(e, fragment);
        }
    }

    private static void setFails(String input, String fragment) {
        try {
            metaSet(input);
            fail("expected ParseException for: " + input);
        } catch (ParseException e) {
            assertMessage(e, fragment);
        }
    }

    private static void notifyFails(String input, String fragment) {
        try {
            notify(input);
            fail("expected ParseException for: " + input);
        } catch (ParseException e) {
            assertMessage(e, fragment);
        }
    }

    @Test
    public void sortProgramErrors() {
        sortFails("SUBJECT UTF-8 ALL", "sort program");
        sortFails("", "sort program");
        sortFails("((SUBJECT)) UTF-8 ALL", "Expected sort criterion");
        sortFails("(REVERSE) UTF-8 ALL", "after REVERSE");
        sortFails("() UTF-8 ALL", "must not be empty");
        sortFails("(SUBJECT", "closing parenthesis");
        sortFails("(BOGUS) UTF-8 ALL", "Unknown sort key");
        sortFails("(SUBJECT)", "Expected charset");
        sortFails("(SUBJECT) UTF-8", "search criteria after charset");
        sortFails("(SUBJECT) \"UTF-8", "Unterminated");
        sortFails("(SUBJECT) UTF-8 BOGUS", "Invalid search criteria");
    }

    @Test
    public void sortProgramAcceptsQuotedTokensAndAnyWhitespace()
            throws ParseException {
        SortRequest request = sort("\t(\"SUBJECT\" REVERSE DATE)\r\n\"ut\\f-8\"\nALL");
        List<SortCriterion> program = request.getSortProgram();
        assertEquals(2, program.size());
        SortCriterion first = program.get(0);
        SortCriterion second = program.get(1);
        assertFalse(first.isReverse());
        assertTrue(second.isReverse());
        String charset = request.getCharset();
        assertEquals("utf-8", charset);
        assertNotNull(request.getSearchCriteria());
    }

    @Test
    public void threadRequestErrors() {
        threadFails("", "Unknown threading algorithm");
        threadFails("BOGUS UTF-8 ALL", "Unknown threading algorithm");
        threadFails("REFERENCES", "Expected charset");
        threadFails("REFERENCES UTF-8", "search criteria after charset");
        threadFails("REFERENCES \"UTF-8", "Unterminated");
        threadFails("REFERENCES UTF-8 BOGUS", "Invalid search criteria");
        threadFails("(REFERENCES) UTF-8 ALL", "Expected atom");
    }

    @Test
    public void threadRequestAcceptsQuotedCharset() throws ParseException {
        ThreadRequest request = thread("\tORDEREDSUBJECT \"ut\\f-8\"\r\nALL");
        ThreadAlgorithm algorithm = request.getAlgorithm();
        assertEquals(ThreadAlgorithm.ORDEREDSUBJECT, algorithm);
        String charset = request.getCharset();
        assertEquals("utf-8", charset);
    }

    @Test
    public void searchGroupsSetsAndStrings() throws ParseException {
        assertNotNull(search("(SEEN (FLAGGED))"));
        assertNotNull(search("1,3:5,* SEEN"));
        assertNotNull(search("UID 4,7:9,*"));
        assertNotNull(search("SUBJECT \"a\\\"b\""));
        assertNotNull(search("SUBJECT {3}\r\nabc"));
        assertNotNull(search("SUBJECT {3}\nabc"));
        assertNotNull(search("SUBJECT x SEEN"));
    }

    @Test
    public void searchErrors() {
        searchFails("(SEEN", "Missing closing parenthesis");
        searchFails("SUBJECT", "Expected");
        searchFails("SUBJECT \"abc", "Unterminated quoted string");
        searchFails("SUBJECT {abc}", "Invalid literal syntax");
        searchFails("SUBJECT {3", "Invalid literal syntax");
        searchFails("SUBJECT {99999999999}", "Invalid literal length");
        searchFails("SUBJECT {10}\r\nabc", "Literal extends beyond input");
        searchFails("UID", "Empty UID set");
        searchFails("UID abc", "Empty UID set");
        searchFails("BOGUSKEY", "Unknown search key");
        searchFails("1:", "Expected sequence number");
    }

    @Test
    public void metadataGetShapes() throws ParseException {
        ImapMetadataGetRequest r = metaGet("(MAXSIZE 20 DEPTH 1) INBOX /private/x");
        assertEquals(20, r.getMaxSize());
        assertEquals(1, r.getDepth());
        assertEquals("INBOX", r.getMailboxName());
        r = metaGet("INBOX (DEPTH infinity) (/a /b)");
        assertEquals(Integer.MAX_VALUE, r.getDepth());
        List<String> names = r.getEntryNames();
        assertEquals(2, names.size());
        r = metaGet("INBOX (DEPTH 0) /a");
        assertEquals(0, r.getDepth());
        r = metaGet("\"My Box\" (private/x)");
        assertEquals("My Box", r.getMailboxName());
        names = r.getEntryNames();
        assertEquals("/private/x", names.get(0));
        r = metaGet("(/a) ");
        assertEquals("", r.getMailboxName());
        r = metaGet("INBOX \"/quoted entry\"");
        names = r.getEntryNames();
        assertEquals("/quoted entry", names.get(0));
    }

    @Test
    public void metadataGetErrors() {
        getFails("", "Expected mailbox name");
        getFails("INBOX", "Expected entry list");
        getFails("INBOX ()", "Expected entry list");
        getFails("INBOX (MAXSIZE x) /a", "Invalid MAXSIZE");
        getFails("INBOX (DEPTH 7) /a", "Invalid DEPTH");
        getFails("INBOX /a extra", "Unexpected trailing input");
        getFails("INBOX (/a", "Unbalanced parentheses");
        getFails("\"unterminated", "Unterminated quoted string");
        getFails("(MAXSIZE 5)", "Expected mailbox name");
    }

    @Test
    public void metadataSetShapes() throws ParseException {
        ImapMetadataSetRequest r = metaSet("INBOX (/a \"va\\\"l\" /b NIL /c bare)");
        List<ImapMetadataSetRequest.EntryValue> entries = r.getEntries();
        assertEquals(3, entries.size());
        assertEquals("va\"l", entries.get(0).value);
        assertNull(entries.get(1).value);
        assertEquals("bare", entries.get(2).value);
    }

    @Test
    public void metadataSetErrors() {
        setFails("INBOX", "Expected entry values");
        setFails("INBOX ()", "Expected entry values");
        setFails("INBOX (/a)", "Expected value");
        setFails("INBOX (/a {3})", "Literal values");
        setFails("INBOX (/a \"x\") junk", "Unexpected trailing input");
        setFails("INBOX (/a \"unterminated)", "Unterminated quoted string");
    }

    @Test
    public void notifyShapes() throws ParseException {
        ImapNotifyRequest none = notify("NONE");
        assertEquals(ImapNotifyRequest.Form.NONE, none.getForm());
        ImapNotifyRequest set = notify("SET STATUS (selected-delayed (MessageNew (UID \"BODY[]\" FLAGS)"
                + " MessageExpunge)) (subtree (INBOX \"Sent Items\") (FlagChange))"
                + " (mailboxes Drafts (NONE)) (personal ()) (inboxes (MessageNew))"
                + " (subscribed (MailboxName))");
        assertTrue(set.isStatusIndicator());
        List<ImapNotifyEventGroup> groups = set.getEventGroups();
        assertEquals(6, groups.size());
    }

    @Test
    public void notifyErrors() {
        notifyFails("", "Expected NOTIFY SET");
        notifyFails("()", "Expected NOTIFY SET");
        notifyFails("BOGUS", "Expected NOTIFY SET");
        notifyFails("NONE extra", "Unexpected input after NOTIFY NONE");
        notifyFails("SET", "at least one event group");
        notifyFails("SET STATUS", "at least one event group");
        notifyFails("SET selected", "Expected '(' starting event group");
        notifyFails("SET (", "Expected mailbox filter");
        notifyFails("SET (bogus (MessageNew))", "Unknown mailbox filter");
        notifyFails("SET (subtree (a b", "Expected ')' ending mailbox list");
        notifyFails("SET (subtree", "Expected mailbox name");
        notifyFails("SET (mailboxes ())", "Expected '(' starting events list");
        notifyFails("SET (selected", "Expected '(' starting events list");
        notifyFails("SET (selected (", "Expected event name");
        notifyFails("SET (selected (NONE x))", "Expected ')' after NONE");
        notifyFails("SET (selected (MessageNew", "Expected ')' ending events list");
        notifyFails("SET (selected (Bogus))", "Unknown NOTIFY event");
        notifyFails("SET (selected (MessageNew (UID", "Expected ')' ending MessageNew");
        notifyFails("SET (selected (MessageNew)", "Expected ')' ending event group");
        notifyFails("SET (selected (MessageNew) (FlagChange) x", "Expected ')' ending event group");
        notifyFails("SET (subtree \"unterminated", "Unterminated quoted string");
    }

    @Test
    public void baseSubjectBoundaries() {
        assertEquals("", BaseSubject.extract(null));
        assertEquals("hello", BaseSubject.extract("  [list]   Re: hello (fwd)  "));
        assertEquals("inner", BaseSubject.extract("[Fwd: Re: inner]"));
        assertEquals("[Fwd: ]", BaseSubject.extract("[Fwd: ]"));
        assertEquals("[unclosed", BaseSubject.extract("[unclosed"));
        assertEquals("[tag]", BaseSubject.extract("[tag]"));
        assertEquals("x", BaseSubject.extract("fw: x"));
        assertEquals("x", BaseSubject.extract("FWD: x"));
        assertEquals("", BaseSubject.extract("   "));
        assertTrue(BaseSubject.isReplyOrForward("Re: x"));
        assertTrue(BaseSubject.isReplyOrForward("x (fwd)"));
        assertTrue(BaseSubject.isReplyOrForward("[Fwd: x]"));
        assertFalse(BaseSubject.isReplyOrForward("x"));
        assertFalse(BaseSubject.isReplyOrForward(null));
        assertFalse(BaseSubject.isReplyOrForward(""));
    }

    @Test
    public void deflateLayerRoundTripsAndRejectsCorruptInput()
            throws DataFormatException {
        ImapDeflateLayer sender = new ImapDeflateLayer();
        ImapDeflateLayer receiver = new ImapDeflateLayer();
        byte[] first = sender.compressAndFlush(
                "a1 NOOP\r\n".getBytes(StandardCharsets.US_ASCII));
        byte[] second = sender.compressAndFlush(new byte[0]);
        assertEquals(0, second.length);
        byte[] plain = receiver.inflate(ByteBuffer.wrap(first));
        assertEquals("a1 NOOP\r\n", new String(plain, StandardCharsets.US_ASCII));
        assertEquals(0, receiver.inflate(null).length);
        assertEquals(0, receiver.inflate(ByteBuffer.allocate(0)).length);
        ImapDeflateLayer broken = new ImapDeflateLayer();
        try {
            broken.inflate(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }));
            fail("expected DataFormatException");
        } catch (DataFormatException e) {
            assertNotNull(e.getMessage());
        }
        sender.close();
        receiver.close();
        broken.close();
    }
}
