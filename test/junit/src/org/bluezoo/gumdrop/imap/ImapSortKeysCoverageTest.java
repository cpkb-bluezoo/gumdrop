/*
 * ImapSortKeysCoverageTest.java
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.mailbox.MessageContext;

import static org.junit.Assert.*;

/**
 * Covers {@link MessageSorter}, {@link SortKeyAccess}, {@link SortSentDate},
 * {@link BaseSubject}, {@link ImapUnicodeCasemap}, {@link ImapCharset} and
 * {@link ImapNotifyEventType}: every sort key, reversal, missing messages,
 * and the subject and collation edge cases.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapSortKeysCoverageTest {

    private static String msg(int day, String subject, String from,
            String to, String cc, int pad) {
        StringBuilder sb = new StringBuilder();
        if (from != null) {
            sb.append("From: ").append(from).append("\r\n");
        }
        if (to != null) {
            sb.append("To: ").append(to).append("\r\n");
        }
        if (cc != null) {
            sb.append("Cc: ").append(cc).append("\r\n");
        }
        if (subject != null) {
            sb.append("Subject: ").append(subject).append("\r\n");
        }
        String[] names = {"Thu", "Fri", "Sat"};
        sb.append("Date: ").append(names[day - 1]).append(", 0").append(day)
                .append(" May 2025 10:00:00 +0000\r\n");
        sb.append("\r\n");
        for (int i = 0; i < pad; i++) {
            sb.append('x');
        }
        sb.append("\r\n");
        return sb.toString();
    }

    private static List<Integer> sorted(MessageSorterTest.SortMailbox mbox,
            List<Integer> nums, SortKey key, boolean reverse)
            throws Exception {
        List<SortCriterion> prog = new ArrayList<SortCriterion>();
        prog.add(new SortCriterion(key, reverse));
        MessageSorter.sort(mbox, nums, prog);
        return nums;
    }

    private static MessageSorterTest.SortMailbox mailbox() {
        return new MessageSorterTest.SortMailbox(
                msg(3, "Banana", "carol@x.com", "zed@x.com", "m@x.com", 100),
                msg(1, "Apple", "alice@x.com", "yan@x.com", null, 10),
                msg(2, "Cherry", null, null, "a@x.com", 300));
    }

    private static List<Integer> all() {
        return new ArrayList<Integer>(Arrays.asList(1, 2, 3));
    }

    @Test
    public void testSortByFrom() throws Exception {
        assertEquals(Arrays.asList(3, 2, 1),
                sorted(mailbox(), all(), SortKey.FROM, false));
        assertEquals(Arrays.asList(1, 2, 3),
                sorted(mailbox(), all(), SortKey.FROM, true));
    }

    @Test
    public void testSortByTo() throws Exception {
        List<Integer> out = sorted(mailbox(), all(), SortKey.TO, false);
        assertEquals(Arrays.asList(3, 2, 1), out);
    }

    @Test
    public void testSortByCc() throws Exception {
        List<Integer> out = sorted(mailbox(), all(), SortKey.CC, false);
        assertEquals(Arrays.asList(2, 3, 1), out);
    }

    @Test
    public void testSortBySizeReverse() throws Exception {
        List<Integer> out = sorted(mailbox(), all(), SortKey.SIZE, true);
        assertEquals(Arrays.asList(3, 1, 2), out);
    }

    @Test
    public void testSortByDate() throws Exception {
        List<Integer> out = sorted(mailbox(), all(), SortKey.DATE, false);
        assertEquals(Arrays.asList(2, 3, 1), out);
    }

    @Test
    public void testSortByArrival() throws Exception {
        List<Integer> out = sorted(mailbox(), all(), SortKey.ARRIVAL, true);
        assertEquals(Arrays.asList(3, 2, 1), out);
    }

    @Test
    public void testSortBySubjectReverse() throws Exception {
        List<Integer> out = sorted(mailbox(), all(), SortKey.SUBJECT, true);
        assertEquals(Arrays.asList(3, 1, 2), out);
    }

    @Test
    public void testSortMultipleCriteriaAndTrivialInputs() throws Exception {
        MessageSorterTest.SortMailbox mbox = mailbox();
        List<Integer> one = new ArrayList<Integer>(Arrays.asList(2));
        sorted(mbox, one, SortKey.SIZE, false);
        assertEquals(Arrays.asList(2), one);
        List<Integer> none = all();
        MessageSorter.sort(mbox, none, new ArrayList<SortCriterion>());
        assertEquals(Arrays.asList(1, 2, 3), none);
        List<SortCriterion> prog = new ArrayList<SortCriterion>();
        prog.add(new SortCriterion(SortKey.CC, false));
        prog.add(new SortCriterion(SortKey.SIZE, true));
        List<Integer> nums = all();
        MessageSorter.sort(mbox, nums, prog);
        assertEquals(Arrays.asList(2, 3, 1), nums);
    }

    @Test
    public void testSortIgnoresMessagesWithoutContext() throws Exception {
        MessageSorterTest.SortMailbox mbox = new MessageSorterTest.SortMailbox(
                msg(1, "Apple", "a@x.com", "b@x.com", null, 10));
        List<Integer> nums = new ArrayList<Integer>(Arrays.asList(1, 9));
        assertEquals(Arrays.asList(9, 1),
                sorted(mbox, nums, SortKey.ARRIVAL, false));
        nums = new ArrayList<Integer>(Arrays.asList(1, 9));
        assertEquals(Arrays.asList(9, 1),
                sorted(mbox, nums, SortKey.DATE, false));
        nums = new ArrayList<Integer>(Arrays.asList(1, 9));
        assertEquals(Arrays.asList(9, 1),
                sorted(mbox, nums, SortKey.SIZE, false));
        nums = new ArrayList<Integer>(Arrays.asList(1, 9));
        assertEquals(Arrays.asList(9, 1),
                sorted(mbox, nums, SortKey.SUBJECT, false));
        nums = new ArrayList<Integer>(Arrays.asList(1, 9));
        assertEquals(Arrays.asList(9, 1),
                sorted(mbox, nums, SortKey.FROM, false));
        nums = new ArrayList<Integer>(Arrays.asList(1, 9));
        assertEquals(Arrays.asList(9, 1),
                sorted(mbox, nums, SortKey.TO, false));
        nums = new ArrayList<Integer>(Arrays.asList(1, 9));
        assertEquals(Arrays.asList(1, 9),
                sorted(mbox, nums, SortKey.CC, false));
    }

    @Test
    public void testSortKeyAccessValues() throws Exception {
        MessageSorterTest.SortMailbox mbox = mailbox();
        MessageContext c1 = mbox.getMessageContext(1);
        MessageContext c2 = mbox.getMessageContext(2);
        MessageContext c3 = mbox.getMessageContext(3);
        assertEquals("carol@x.com", SortKeyAccess.from(c1));
        assertEquals("", SortKeyAccess.from(c3));
        assertEquals("zed@x.com", SortKeyAccess.to(c1));
        assertEquals("", SortKeyAccess.cc(c2));
        assertEquals("a@x.com", SortKeyAccess.cc(c3));
        assertEquals("Banana", SortKeyAccess.subject(c1));
        assertTrue(SortKeyAccess.size(c1) > 0);
        assertNotNull(SortKeyAccess.arrival(c1));
        assertNotNull(SortKeyAccess.date(c1));
        MessageSorterTest.SortMailbox nosubj = new MessageSorterTest.SortMailbox(
                msg(1, null, "garbage", "<>", null, 1));
        MessageContext c = nosubj.getMessageContext(1);
        assertEquals("", SortKeyAccess.subject(c));
    }

    @Test
    public void testSortSentDate() {
        OffsetDateTime sent = OffsetDateTime.of(
                2025, 5, 1, 12, 0, 0, 0, ZoneOffset.ofHours(2));
        OffsetDateTime internal = OffsetDateTime.of(
                2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        assertEquals(sent.toInstant(), SortSentDate.toSortInstant(sent, internal));
        assertEquals(internal.toInstant(),
                SortSentDate.toSortInstant(null, internal));
        assertEquals(Instant.EPOCH, SortSentDate.toSortInstant(null, null));
    }

    @Test
    public void testBaseSubjectExtract() {
        assertEquals("", BaseSubject.extract(null));
        assertEquals("topic", BaseSubject.extract("  Re:  Fw: FWD: topic (fwd) "));
        assertEquals("topic", BaseSubject.extract("[list] Re: [other] topic"));
        assertEquals("[only]", BaseSubject.extract("[only]"));
        assertEquals("[unterminated", BaseSubject.extract("[unterminated"));
        assertEquals("inner", BaseSubject.extract("[Fwd: Re: inner]"));
        assertEquals("[Fwd:]", BaseSubject.extract("[Fwd:]"));
        assertEquals("topic", BaseSubject.extract("topic (FWD)(fwd)"));
        assertEquals("", BaseSubject.extract("   "));
        assertEquals("a b", BaseSubject.extract("a \t\r\n b"));
        assertEquals("plain", BaseSubject.extract("=?UTF-8?Q?Re=3A_plain?="));
    }

    @Test
    public void testBaseSubjectIsReplyOrForward() {
        assertFalse(BaseSubject.isReplyOrForward(null));
        assertFalse(BaseSubject.isReplyOrForward(""));
        assertFalse(BaseSubject.isReplyOrForward("plain topic"));
        assertTrue(BaseSubject.isReplyOrForward("Re: topic"));
        assertTrue(BaseSubject.isReplyOrForward("topic (fwd)"));
        assertTrue(BaseSubject.isReplyOrForward("fw: topic"));
        assertTrue(BaseSubject.isReplyOrForward("[Fwd: topic]"));
        assertTrue(BaseSubject.isReplyOrForward("[list] topic"));
        assertFalse(BaseSubject.isReplyOrForward("[Fwd: topic"));
        assertFalse(BaseSubject.isReplyOrForward("[only]"));
    }

    @Test
    public void testCasemap() {
        assertEquals(0, ImapUnicodeCasemap.compare("ABC", "abc"));
        assertEquals(0, ImapUnicodeCasemap.compare(null, ""));
        assertTrue(ImapUnicodeCasemap.compare("a", "b") < 0);
        assertTrue(ImapUnicodeCasemap.compare("ab", "a") > 0);
        assertTrue(ImapUnicodeCasemap.compare("a", "ab") < 0);
        assertEquals(0, ImapUnicodeCasemap.compare("İ", "i"));
        assertEquals(0, ImapUnicodeCasemap.compare("K", "k"));
        assertEquals(0, ImapUnicodeCasemap.compare("Å", "å"));
        assertEquals(0, ImapUnicodeCasemap.compare("𝐀", "𝐀"));
        assertTrue(ImapUnicodeCasemap.isEmpty(null));
        assertTrue(ImapUnicodeCasemap.isEmpty(""));
        assertFalse(ImapUnicodeCasemap.isEmpty("x"));
        assertEquals("", ImapUnicodeCasemap.normalizeSpaces(null));
        assertEquals("", ImapUnicodeCasemap.normalizeSpaces(""));
        assertEquals("", ImapUnicodeCasemap.normalizeSpaces(" \t "));
        assertEquals("a b c", ImapUnicodeCasemap.normalizeSpaces("  a \r\n\t b  c "));
        assertEquals("x𝐀", ImapUnicodeCasemap.normalizeSpaces("x𝐀"));
    }

    @Test
    public void testCharset() {
        assertFalse(ImapCharset.isSortThreadSupported(null));
        assertTrue(ImapCharset.isSortThreadSupported(" utf-8 "));
        assertTrue(ImapCharset.isSortThreadSupported("us-ascii"));
        assertFalse(ImapCharset.isSortThreadSupported("iso-8859-1"));
        assertEquals("UTF-8", ImapCharset.normalize(" utf-8"));
    }

    @Test
    public void testNotifyEventType() {
        for (ImapNotifyEventType t : ImapNotifyEventType.values()) {
            assertSame(t, ImapNotifyEventType.fromImapName(t.getImapName()));
            assertSame(t, ImapNotifyEventType.fromImapName(
                    t.getImapName().toLowerCase()));
            assertSame(t, ImapNotifyEventType.fromImapName(
                    t.name().replace("_", "")));
        }
        assertNull(ImapNotifyEventType.fromImapName(null));
        assertNull(ImapNotifyEventType.fromImapName("Bogus"));
        assertTrue(ImapNotifyEventType.MESSAGE_NEW.isMessageEvent());
        assertFalse(ImapNotifyEventType.MAILBOX_NAME.isMessageEvent());
    }
}
