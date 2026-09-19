/*
 * OrderedSubjectThreaderTest.java
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
import java.util.List;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
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
