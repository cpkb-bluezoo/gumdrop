/*
 * ThreadFormatterTest.java
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

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
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
