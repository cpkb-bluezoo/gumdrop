/*
 * StreamH2WebSocketUpgradeTest.java
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
package org.bluezoo.gumdrop.mailbox.mbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.MessageContext;
import org.bluezoo.gumdrop.mailbox.ParsedMessageContext;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests that SINCE, BEFORE and ON on an mbox use one internal date whether the
 * message is answered from the search index or by parsing it: the date of the
 * "From " line, else the Date header, else no date (never a match).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MboxInternalDateSearchTest {

    private static final String RAW =
        "From a@x Mon Jan  1 00:00:00 2024\r\nSubject: from line only\r\n\r\none\r\n\r\n"
        + "From b@x Thu Jan  2 00:00:00 2025\r\nDate: Wed, 1 Jan 2020 10:00:00 +0000\r\n"
        + "Subject: both\r\n\r\ntwo\r\n\r\n"
        + "From c@x\r\nDate: Mon, 5 May 2025 10:00:00 +0000\r\nSubject: header only\r\n\r\nthree\r\n\r\n"
        + "From d@x\r\nSubject: no date at all\r\n\r\nfour\r\n\r\n";

    private MboxMailbox box;

    @Before
    public void setUp() throws IOException {
        Path dir = MemoryFileSystem.create().getPath("/d");
        Files.createDirectories(dir);
        Path file = dir.resolve("box.mbox");
        Files.write(file, RAW.getBytes(StandardCharsets.ISO_8859_1));
        box = new MboxMailbox(file, "box", false);
    }

    private static List<Integer> numbers(Integer... n) {
        return Arrays.asList(n);
    }

    @Test
    public void indexedSearchUsesFromLineThenDateHeader() throws IOException {
        assertEquals(numbers(2, 3), box.search(SearchCriteria.since(LocalDate.of(2025, 1, 1))));
        assertEquals(numbers(1), box.search(SearchCriteria.before(LocalDate.of(2025, 1, 1))));
        assertEquals(numbers(2), box.search(SearchCriteria.on(LocalDate.of(2025, 1, 2))));
        assertEquals(numbers(3), box.search(SearchCriteria.on(LocalDate.of(2025, 5, 5))));
        assertEquals(numbers(1, 2, 3), box.search(SearchCriteria.since(LocalDate.of(1990, 1, 1))));
        assertEquals(numbers(1, 2, 3), box.search(SearchCriteria.before(LocalDate.of(2030, 1, 1))));
        box.close(false);
    }

    @Test
    public void parsedContextWithoutInternalDateFallsBackToTheDateHeader() throws IOException {
        MessageContext headerOnly = new ParsedMessageContext(box, 3, 3L, 10L,
            EnumSet.noneOf(Flag.class), null);
        MessageContext none = new ParsedMessageContext(box, 4, 4L, 10L,
            EnumSet.noneOf(Flag.class), null);
        SearchCriteria since = SearchCriteria.since(LocalDate.of(2025, 5, 5));
        SearchCriteria on = SearchCriteria.on(LocalDate.of(2025, 5, 5));
        SearchCriteria before = SearchCriteria.before(LocalDate.of(2025, 5, 6));
        assertTrue(since.matches(headerOnly));
        assertTrue(on.matches(headerOnly));
        assertTrue(before.matches(headerOnly));
        assertFalse(since.matches(none));
        assertFalse(on.matches(none));
        assertFalse(before.matches(none));
        box.close(false);
    }
}
