/*
 * MailboxTypesTest.java
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

package org.bluezoo.gumdrop.mailbox;

import org.junit.Test;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for small mailbox value types and the default methods of
 * {@link Mailbox}, {@link ImapMessageDescriptor} and the search criteria
 * accessors.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MailboxTypesTest {

    /** Mailbox stub that only implements the abstract methods. */
    private static final class BareMailbox implements Mailbox {
        @Override
        public void close(boolean expunge) {
        }

        @Override
        public int getMessageCount() {
            return 0;
        }

        @Override
        public long getMailboxSize() {
            return 0;
        }

        @Override
        public Iterator<MessageDescriptor> getMessageList() {
            return Collections.<MessageDescriptor>emptyList().iterator();
        }

        @Override
        public MessageDescriptor getMessage(int messageNumber) {
            return null;
        }

        @Override
        public ReadableByteChannel getMessageContent(int messageNumber) {
            return null;
        }

        @Override
        public ReadableByteChannel getMessageTop(int messageNumber, int bodyLines) {
            return null;
        }

        @Override
        public void deleteMessage(int messageNumber) {
        }

        @Override
        public boolean isDeleted(int messageNumber) {
            return false;
        }

        @Override
        public void undeleteAll() {
        }

        @Override
        public String getUniqueId(int messageNumber) {
            return null;
        }
    }

    private static final class StubImapDescriptor implements ImapMessageDescriptor {
        private final String uid;
        private final Set<String> flags;

        StubImapDescriptor(String uid, String... flagNames) {
            this.uid = uid;
            this.flags = new HashSet<String>();
            for (String f : flagNames) {
                flags.add(f);
            }
        }

        @Override
        public int getMessageNumber() {
            return 9;
        }

        @Override
        public long getSize() {
            return 1;
        }

        @Override
        public String getUniqueId() {
            return uid;
        }

        @Override
        public Set<String> getFlags() {
            return flags;
        }

        @Override
        public OffsetDateTime getInternalDate() {
            return null;
        }
    }

    @Test
    public void mailboxDefaults() throws IOException {
        Mailbox mailbox = new BareMailbox();
        assertEquals("INBOX", mailbox.getName());
        assertFalse(mailbox.isReadOnly());
        assertNull(mailbox.openAsyncContent(1));
        assertNull(mailbox.openAsyncAppend(Collections.<Flag>emptySet(), null));
        assertNull(mailbox.getMessagePath(1));
        assertTrue(mailbox.getFlags(1).isEmpty());
        assertEquals(Flag.permanentFlags(), mailbox.getPermanentFlags());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void mailboxTopEndOffsetUnsupported() throws IOException {
        new BareMailbox().getMessageTopEndOffset(1, 5);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void mailboxSetFlagsUnsupported() throws IOException {
        new BareMailbox().setFlags(1, Collections.<Flag>emptySet(), true);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void mailboxReplaceFlagsUnsupported() throws IOException {
        new BareMailbox().replaceFlags(1, Collections.<Flag>emptySet());
    }

    @Test
    public void simpleDescriptor() {
        SimpleMessageDescriptor d = new SimpleMessageDescriptor(3, 100L, "abc");
        assertEquals(3, d.getMessageNumber());
        assertEquals(100L, d.getSize());
        assertEquals("abc", d.getUniqueId());
        assertTrue(d.toString().contains("uniqueId='abc'"));
    }

    @Test
    public void imapDescriptorUidParsingAndFallback() {
        assertEquals(1234L, new StubImapDescriptor("1234").getUID());
        assertEquals(9L, new StubImapDescriptor("not-a-number").getUID());
    }

    @Test
    public void imapDescriptorFlagShortcuts() {
        StubImapDescriptor all = new StubImapDescriptor("1", "\\Seen", "\\Answered",
            "\\Flagged", "\\Deleted", "\\Draft", "\\Recent");
        assertTrue(all.isSeen());
        assertTrue(all.isAnswered());
        assertTrue(all.isFlagged());
        assertTrue(all.isDeleted());
        assertTrue(all.isDraft());
        assertTrue(all.isRecent());
        StubImapDescriptor none = new StubImapDescriptor("1");
        assertFalse(none.isSeen());
        assertFalse(none.isAnswered());
        assertFalse(none.isFlagged());
        assertFalse(none.isDeleted());
        assertFalse(none.isDraft());
        assertFalse(none.isRecent());
        assertNull(none.getEnvelope());
        assertNull(none.getBodyStructure());
    }

    @Test
    public void mailboxAttributeAtoms() {
        assertEquals("\\Noselect", MailboxAttribute.NOSELECT.getImapAtom());
        assertEquals("\\Trash", MailboxAttribute.TRASH.toString());
        assertEquals(MailboxAttribute.JUNK, MailboxAttribute.fromImapAtom("\\junk"));
        assertNull(MailboxAttribute.fromImapAtom("\\Bogus"));
        assertNull(MailboxAttribute.fromImapAtom(""));
        assertNull(MailboxAttribute.fromImapAtom(null));
        for (MailboxAttribute a : MailboxAttribute.values()) {
            assertSame(a, MailboxAttribute.fromImapAtom(a.getImapAtom()));
        }
    }

    @Test
    public void criteriaAccessorsAndToString() {
        LocalDate day = LocalDate.of(2025, 3, 4);
        DateCriteria date = (DateCriteria) SearchCriteria.sentSince(day);
        assertEquals(DateCriteria.Field.SENT, date.getField());
        assertEquals(DateCriteria.Comparison.SINCE, date.getComparison());
        assertEquals(day, date.getDate());
        assertTrue(date.toString().contains("2025-03-04"));

        SizeCriteria size = (SizeCriteria) SearchCriteria.larger(10L);
        assertEquals(SizeCriteria.Comparison.LARGER, size.getComparison());
        assertEquals(10L, size.getThreshold());
        assertTrue(size.toString().contains("LARGER"));

        SearchCriteria seen = SearchCriteria.seen();
        SearchCriteria flagged = SearchCriteria.flagged();
        OrCriteria or = new OrCriteria(seen, flagged);
        assertSame(seen, or.getLeft());
        assertSame(flagged, or.getRight());
        assertNotNull(or.toString());

        NotCriteria not = new NotCriteria(seen);
        assertSame(seen, not.getCriteria());
        assertNotNull(not.toString());

        List<SearchCriteria> list = new ArrayList<SearchCriteria>();
        list.add(seen);
        list.add(flagged);
        AndCriteria and = new AndCriteria(list);
        assertEquals(2, and.getCriteria().size());
        assertNotNull(and.toString());
    }

    @Test
    public void messageContextLocalDateDefaults() throws IOException {
        final OffsetDateTime when = OffsetDateTime.of(2025, 1, 2, 23, 0, 0, 0, ZoneOffset.UTC);
        MessageContext withDates = new MessageContext() {
            public int getMessageNumber() { return 1; }
            public long getUID() { return 1; }
            public long getSize() { return 1; }
            public Set<Flag> getFlags() { return Collections.emptySet(); }
            public OffsetDateTime getInternalDate() { return when; }
            public String getHeader(String name) { return null; }
            public List<String> getHeaders(String name) { return Collections.emptyList(); }
            public OffsetDateTime getSentDate() { return null; }
            public CharSequence getHeadersText() { return ""; }
            public CharSequence getBodyText() { return ""; }
        };
        assertEquals(LocalDate.of(2025, 1, 2), withDates.getInternalLocalDate());
        assertNull(withDates.getSentLocalDate());
        assertTrue(withDates.getKeywords().isEmpty());
        assertEquals(0L, withDates.getModSeq());
    }
}
