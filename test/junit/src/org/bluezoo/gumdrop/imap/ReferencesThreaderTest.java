/*
 * ReferencesThreaderTest.java
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link ReferencesThreader} and {@link ThreadHeaders}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ReferencesThreaderTest {

    private static String msg(int day, String subject, String id,
            String refs, String inReplyTo) {
        StringBuilder sb = new StringBuilder();
        sb.append("From: a@x.com\r\n");
        sb.append("Subject: ").append(subject).append("\r\n");
        sb.append("Date: Mon, 0").append(day).append(" May 2025 10:00:00 +0000\r\n");
        if (id != null) {
            sb.append("Message-ID: ").append(id).append("\r\n");
        }
        if (refs != null) {
            sb.append("References: ").append(refs).append("\r\n");
        }
        if (inReplyTo != null) {
            sb.append("In-Reply-To: ").append(inReplyTo).append("\r\n");
        }
        sb.append("\r\nbody\r\n");
        return sb.toString();
    }

    private static String thread(String... bodies) throws Exception {
        MessageSorterTest.SortMailbox mbox =
                new MessageSorterTest.SortMailbox(bodies);
        List<Integer> all = new ArrayList<Integer>();
        for (int i = 1; i <= bodies.length; i++) {
            all.add(Integer.valueOf(i));
        }
        List<ThreadBranch> out = ReferencesThreader.thread(mbox, all);
        return ThreadFormatter.format(out);
    }

    @Test
    public void testEmpty() throws Exception {
        MessageSorterTest.SortMailbox mbox =
                new MessageSorterTest.SortMailbox();
        List<Integer> none = new ArrayList<Integer>();
        List<ThreadBranch> out = ReferencesThreader.thread(mbox, none);
        assertTrue(out.isEmpty());
    }

    @Test
    public void testLinearChain() throws Exception {
        String f = thread(
                msg(1, "Topic", "<a@t>", null, null),
                msg(2, "Re: Topic", "<b@t>", "<a@t>", null),
                msg(3, "Re: Topic", "<c@t>", "<a@t> <b@t>", null));
        assertEquals("(1 2 3)", f);
    }

    @Test
    public void testInReplyToOnly() throws Exception {
        String f = thread(
                msg(1, "Topic", "<a@t>", null, null),
                msg(2, "Re: Topic", "<b@t>", null, "<a@t>"));
        assertEquals("(1 2)", f);
    }

    @Test
    public void testBranching() throws Exception {
        String f = thread(
                msg(1, "Topic", "<a@t>", null, null),
                msg(2, "Re: Topic", "<b@t>", "<a@t>", null),
                msg(3, "Re: Topic", "<c@t>", "<a@t>", null));
        assertEquals("(1 (2)(3))", f);
    }

    /**
     * RFC 5256 section 2.2: the children of a missing message are not
     * promoted to the root when that would make several of them roots; they
     * stay together as siblings under the (dummy) parent.
     */
    @Test
    public void testMissingParentKeepsSiblingsTogether() throws Exception {
        String f = thread(
                msg(1, "Topic", "<b@t>", "<gone@t>", null),
                msg(2, "Re: Topic", "<c@t>", "<gone@t>", null));
        assertEquals("((1)(2))", f);
    }

    @Test
    public void testDummyWithSingleChildPruned() throws Exception {
        String f = thread(
                msg(1, "Solo", "<b@t>", "<gone@t>", null));
        assertEquals("(1)", f);
    }

    @Test
    public void testDuplicateMessageIdGetsSyntheticId() throws Exception {
        String f = thread(
                msg(1, "One", "<dup@t>", null, null),
                msg(2, "Two", "<dup@t>", null, null));
        assertTrue(f, f.contains("1"));
        assertTrue(f, f.contains("2"));
    }

    @Test
    public void testLoopInReferencesIgnored() throws Exception {
        String f = thread(
                msg(1, "Loop", "<a@t>", "<b@t>", null),
                msg(2, "Loop", "<b@t>", "<a@t>", null));
        assertTrue(f, f.contains("1"));
        assertTrue(f, f.contains("2"));
    }

    @Test
    public void testSelfReference() throws Exception {
        String f = thread(
                msg(1, "Self", "<a@t>", "<a@t>", null));
        assertEquals("(1)", f);
    }

    @Test
    public void testReparentWhenLaterReferencesChange() throws Exception {
        String f = thread(
                msg(1, "T", "<a@t>", null, null),
                msg(2, "T", "<c@t>", "<a@t>", null),
                msg(3, "T", "<c@t>", "<a@t> <b@t>", null),
                msg(4, "T", "<b@t>", "<a@t>", null));
        assertTrue(f, f.contains("4"));
    }

    @Test
    public void testSubjectMergeReplyUnderOriginal() throws Exception {
        String f = thread(
                msg(1, "Hello", "<a@t>", null, null),
                msg(2, "Re: Hello", "<b@t>", null, null));
        assertEquals("(1 2)", f);
    }

    @Test
    public void testSubjectMergeOriginalAfterReply() throws Exception {
        String f = thread(
                msg(2, "Re: Hello", "<b@t>", null, null),
                msg(1, "Hello", "<a@t>", null, null));
        assertTrue(f, f.contains("1"));
        assertTrue(f, f.contains("2"));
    }

    @Test
    public void testSubjectMergeTwoOriginalsGetDummyParent()
            throws Exception {
        String f = thread(
                msg(1, "Same", "<a@t>", null, null),
                msg(2, "Same", "<b@t>", null, null));
        assertEquals("((1)(2))", f);
    }

    @Test
    public void testSubjectMergeThreeSame() throws Exception {
        String f = thread(
                msg(1, "Same", "<a@t>", null, null),
                msg(2, "Same", "<b@t>", null, null),
                msg(3, "Same", "<c@t>", null, null));
        assertEquals("((1)(2)(3))", f);
    }

    @Test
    public void testSubjectMergeDummyRoots() throws Exception {
        String f = thread(
                msg(1, "Topic", "<a@t>", "<g1@t>", null),
                msg(2, "Topic", "<b@t>", "<g1@t>", null),
                msg(3, "Topic", "<c@t>", "<g2@t>", null),
                msg(4, "Topic", "<d@t>", "<g2@t>", null));
        assertTrue(f, f.startsWith("("));
    }

    @Test
    public void testSubjectMergeDummyAndRealRoot() throws Exception {
        String f = thread(
                msg(1, "Topic", "<a@t>", "<g1@t>", null),
                msg(2, "Topic", "<b@t>", "<g1@t>", null),
                msg(3, "Re: Topic", "<c@t>", null, null));
        assertTrue(f, f.startsWith("("));
        String g = thread(
                msg(3, "Re: Topic", "<c@t>", null, null),
                msg(1, "Topic", "<a@t>", "<g1@t>", null),
                msg(2, "Topic", "<b@t>", "<g1@t>", null));
        assertTrue(g, g.startsWith("("));
    }

    @Test
    public void testNoMessageIdUsesSynthetic() throws Exception {
        String f = thread(
                msg(1, "A", null, null, null),
                msg(2, "B", null, null, null));
        assertTrue(f, f.contains("1"));
        assertTrue(f, f.contains("2"));
    }

    @Test
    public void testThreadHeadersHelpers() {
        assertNull(ThreadHeaders.firstId(null));
        assertNull(ThreadHeaders.firstId(""));
        assertEquals("<gumdrop.thread.7@local>", ThreadHeaders.syntheticId(7));
        List<String> ids = ThreadHeaders.parseIdList("<a@t> <b@t>");
        assertEquals(Arrays.asList("<a@t>", "<b@t>"), ids);
        assertTrue(ThreadHeaders.parseIdList("garbage").isEmpty());
    }
}
