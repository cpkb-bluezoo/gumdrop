/*
 * OrderedSubjectThreaderTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class OrderedSubjectThreaderTest {

    @Test
    public void testGroupsByBaseSubject() throws Exception {
        MessageSorterTest.SortMailbox mbox =
                new MessageSorterTest.SortMailbox(
                MessageSorterTest.msg(1, "Re: Topic", "a@x.com"),
                MessageSorterTest.msg(2, "Topic", "b@x.com"),
                MessageSorterTest.msg(3, "Other", "c@x.com"));
        List<ThreadBranch> threads = OrderedSubjectThreader.thread(mbox,
                Arrays.asList(1, 2, 3));
        assertEquals(2, threads.size());
        String formatted = ThreadFormatter.format(threads);
        assertTrue(formatted.contains("(1 2)"));
        assertTrue(formatted.contains("(3)"));
    }
}
