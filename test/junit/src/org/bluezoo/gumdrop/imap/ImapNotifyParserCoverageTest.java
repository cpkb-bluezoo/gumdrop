/*
 * ImapNotifyParserCoverageTest.java
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
 * Branch coverage for {@link ImapNotifyParser} and the NOTIFY value
 * classes: every mailbox filter, event form, quoting and error path.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapNotifyParserCoverageTest {

    private static ImapNotifyRequest parse(String input) throws Exception {
        ImapNotifyParser parser = new ImapNotifyParser(input);
        return parser.parse();
    }

    private static void rejects(String input) {
        try {
            parse(input);
            fail("expected ParseException for: " + input);
        } catch (ParseException e) {
            assertNotNull(e.getMessage());
        } catch (Exception e) {
            fail("unexpected " + e);
        }
    }

    @Test
    public void testNullAndEmptyInput() {
        rejects(null);
        rejects("");
        rejects("   ");
        rejects("(");
        rejects("BOGUS");
        rejects("NONE extra");
    }

    @Test
    public void testSetKeywordCaseInsensitive() throws Exception {
        ImapNotifyRequest req = parse("set (personal (MessageNew MessageExpunge))");
        assertEquals(ImapNotifyRequest.Form.SET, req.getForm());
        assertFalse(req.isStatusIndicator());
        req = parse("none");
        assertEquals(ImapNotifyRequest.Form.NONE, req.getForm());
    }

    @Test
    public void testAllMailboxFilters() throws Exception {
        ImapNotifyRequest req = parse("SET (selected (MessageNew)) "
                + "(inboxes (MessageNew)) (personal (MessageNew)) "
                + "(subscribed (MessageNew)) (subtree Foo (MessageNew)) "
                + "(mailboxes (A \"B C\") (MessageNew))");
        List<ImapNotifyEventGroup> groups = req.getEventGroups();
        assertEquals(6, groups.size());
        assertEquals(ImapNotifyMailboxFilter.Kind.SELECTED,
                groups.get(0).getMailboxFilter().getKind());
        assertEquals(ImapNotifyMailboxFilter.Kind.INBOXES,
                groups.get(1).getMailboxFilter().getKind());
        assertEquals(ImapNotifyMailboxFilter.Kind.PERSONAL,
                groups.get(2).getMailboxFilter().getKind());
        assertEquals(ImapNotifyMailboxFilter.Kind.SUBSCRIBED,
                groups.get(3).getMailboxFilter().getKind());
        ImapNotifyMailboxFilter subtree = groups.get(4).getMailboxFilter();
        assertEquals(ImapNotifyMailboxFilter.Kind.SUBTREE, subtree.getKind());
        assertEquals(1, subtree.getMailboxNames().size());
        assertFalse(subtree.affectsSelectedMailbox());
        ImapNotifyMailboxFilter boxes = groups.get(5).getMailboxFilter();
        assertEquals(2, boxes.getMailboxNames().size());
        assertEquals("B C", boxes.getMailboxNames().get(1));
        assertTrue(groups.get(0).getMailboxFilter().affectsSelectedMailbox());
    }

    @Test
    public void testSelectedDelayedAndQuotedEscape() throws Exception {
        ImapNotifyRequest req = parse("SET (selected-delayed (MessageNew)) "
                + "(mailboxes \"a\\\"b\" (FlagChange))");
        assertTrue(req.getEventGroups().get(0).getMailboxFilter()
                .affectsSelectedMailbox());
        assertEquals("a\"b", req.getEventGroups().get(1).getMailboxFilter()
                .getMailboxNames().get(0));
    }

    @Test
    public void testEventListForms() throws Exception {
        ImapNotifyRequest req = parse("SET (personal (NONE)) (subscribed ()) "
                + "(inboxes (MessageNew (uid \"body[header]\") MessageExpunge "
                + "FlagChange AnnotationChange MailboxName SubscriptionChange "
                + "MailboxMetadataChange ServerMetadataChange))");
        List<ImapNotifyEventGroup> groups = req.getEventGroups();
        assertTrue(groups.get(0).isEventsNone());
        assertFalse(groups.get(1).isEventsNone());
        assertTrue(groups.get(1).getEvents().isEmpty());
        List<ImapNotifyEventSpec> events = groups.get(2).getEvents();
        assertEquals(8, events.size());
        assertEquals(2, events.get(0).getMessageNewFetchAtts().size());
        assertEquals("body[header]", events.get(0).getMessageNewFetchAtts().get(1));
        assertEquals(ImapNotifyEventType.MAILBOX_NAME, events.get(4).getType());
    }

    @Test
    public void testMessageNewWithoutFetchList() throws Exception {
        ImapNotifyRequest req = parse("SET (personal (MessageNew FlagChange))");
        ImapNotifyEventSpec spec = req.getEventGroups().get(0).getEvents().get(0);
        assertTrue(spec.getMessageNewFetchAtts().isEmpty());
        req = parse("SET (personal (MessageNew))");
        spec = req.getEventGroups().get(0).getEvents().get(0);
        assertEquals(ImapNotifyEventType.MESSAGE_NEW, spec.getType());
    }

    @Test
    public void testSyntaxErrors() {
        rejects("SET");
        rejects("SET STATUS");
        rejects("SET personal (MessageNew)");
        rejects("SET (personal MessageNew)");
        rejects("SET (personal (MessageNew)");
        rejects("SET (personal (MessageNew) extra)");
        rejects("SET ((MessageNew))");
        rejects("SET (bogusfilter (MessageNew))");
        rejects("SET (subtree (MessageNew))");
        rejects("SET (subtree (A (MessageNew))");
        rejects("SET (mailboxes (MessageNew))");
        rejects("SET (mailboxes \"unterminated (MessageNew))");
        rejects("SET (personal (()))");
        rejects("SET (personal (NONE x))");
        rejects("SET (personal (NONE");
        rejects("SET (personal (MessageNew");
        rejects("SET (personal (MessageNew (uid ");
        rejects("SET (personal (MessageNew (uid)");
        rejects("SET (personal (MessageNew ())");
        rejects("SET (personal (FutureEvent))");
        rejects("SET (personal (MessageNew \"unterminated");
        rejects("SET (");
        rejects("SET (personal");
        rejects("SET (mailboxes \"");
        rejects("SET (mailboxes ");
    }

    @Test
    public void testEventTypeLookup() {
        assertNull(ImapNotifyEventType.fromImapName(null));
        assertNull(ImapNotifyEventType.fromImapName("nonsense"));
        ImapNotifyEventType[] all = ImapNotifyEventType.values();
        for (int i = 0; i < all.length; i++) {
            String name = all[i].getImapName();
            assertEquals(all[i], ImapNotifyEventType.fromImapName(name));
            String lower = name.toLowerCase();
            assertEquals(all[i], ImapNotifyEventType.fromImapName(lower));
        }
        assertTrue(ImapNotifyEventType.FLAG_CHANGE.isMessageEvent());
        assertFalse(ImapNotifyEventType.MAILBOX_NAME.isMessageEvent());
    }
}
