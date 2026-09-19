/*
 * ThreadFormatterTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class ThreadFormatterTest {

    @Test
    public void testSingleMessageThread() {
        ThreadBranch b = new ThreadBranch();
        b.addMember(2L);
        assertEquals("(2)", ThreadFormatter.format(Collections.singletonList(b)));
    }

    @Test
    public void testChainAndNested() {
        ThreadBranch b = new ThreadBranch();
        b.addMember(3L);
        b.addMember(6L);
        ThreadBranch nested1 = new ThreadBranch();
        nested1.addMember(4L);
        nested1.addMember(23L);
        ThreadBranch nested2 = new ThreadBranch();
        nested2.addMember(44L);
        nested2.addMember(7L);
        nested2.addMember(96L);
        b.addNested(nested1);
        b.addNested(nested2);
        assertEquals("(3 6 (4 23)(44 7 96))",
                ThreadFormatter.format(Collections.singletonList(b)));
    }

    @Test
    public void testMultipleTopLevelThreads() {
        ThreadBranch a = new ThreadBranch();
        a.addMember(1L);
        ThreadBranch b = new ThreadBranch();
        b.addMember(2L);
        b.addMember(3L);
        assertEquals("(1)(2 3)",
                ThreadFormatter.format(Arrays.asList(a, b)));
    }
}
