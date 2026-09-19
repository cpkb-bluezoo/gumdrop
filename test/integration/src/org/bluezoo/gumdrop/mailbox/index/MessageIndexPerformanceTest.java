/*
 * MessageIndexPerformanceTest.java
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

package org.bluezoo.gumdrop.mailbox.index;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MessageIndex}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
/*
 * NOTE: wall-clock thresholds live here, not in the unit suite: unit tests must
 * be deterministic (CONTRIBUTING.md). Extracted from MessageIndexTest.
 */
public class MessageIndexPerformanceTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Path indexPath;
    private MessageIndex index;

    @Before
    public void setUp() throws Exception {
        indexPath = tempFolder.newFile("test.gidx").toPath();
        Files.deleteIfExists(indexPath);
        index = new MessageIndex(indexPath, 1000L, 1L);
    }

    @After
    public void tearDown() throws Exception {
        if (index != null) {
            index = null;
        }
    }

    // ========================================================================
    // Basic Construction Tests
    // ========================================================================







    // ========================================================================
    // Save and Load Tests
    // ========================================================================









    // ========================================================================
    // Flag Update Tests
    // ========================================================================







    // ========================================================================
    // Remove Entry Tests
    // ========================================================================







    // ========================================================================
    // Compact Tests
    // ========================================================================



    // ========================================================================
    // Sub-Index Tests (Flag BitSets)
    // ========================================================================





    // ========================================================================
    // Date Range Query Tests
    // ========================================================================



    // ========================================================================
    // Size Range Query Tests
    // ========================================================================



    // ========================================================================
    // Corruption Detection Tests
    // ========================================================================













    // ========================================================================
    // Get Entry by Message Number Tests
    // ========================================================================







    /**
     * Regression for issue #334: bulk sequence-number lookup must not scan
     * the whole index per message number.
     */
    @Test(timeout = 5000)
    public void testGetEntryByMessageNumberCostDoesNotScaleLinearlyWithMailboxSize() {
        for (int i = 0; i < 100_000; i++) {
            index.addEntry(createEntry(i + 1L, i + 1, "loc" + i,
                    "user@test.com", "Subject " + i));
        }

        long start = System.nanoTime();
        for (int msgNum = 1; msgNum <= 1000; msgNum++) {
            MessageIndexEntry entry = index.getEntryByMessageNumber(msgNum);
            assertNotNull(entry);
            assertEquals(msgNum, entry.getUid());
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue("1000 message-number lookups in a 100,000-message index took "
                        + elapsedMs + "ms -- linear scan per lookup would be far slower",
                elapsedMs < 2000);
    }

    // ========================================================================
    // Iterator Tests
    // ========================================================================



    // ========================================================================
    // search() / computeCandidateIndices() Tests (issue #304)
    // ========================================================================

























    @Test(timeout = 5000)
    public void testSearchFlagCostDoesNotScaleLinearlyWithMailboxSize() {
        // 100,000 unseen messages, 1 seen one: an unindexed scan would
        // still call matches() -- and, for the fallback case,
        // potentially parse a message from disk -- for all 100,001. The
        // sub-index answers this in effectively O(1).
        for (int i = 0; i < 100_000; i++) {
            index.addEntry(createEntryWithFlags(i + 1, i + 1,
                    EnumSet.noneOf(Flag.class)));
        }
        index.addEntry(createEntryWithFlags(100_001L, 100_001,
                EnumSet.of(Flag.SEEN)));

        long start = System.nanoTime();
        List<Integer> results = index.search(SearchCriteria.seen());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(Collections.singletonList(100_001), results);
        assertTrue("SEARCH SEEN against a 100,001-message mailbox took " + elapsedMs
                + "ms -- an unindexed scan of every message would be far slower than this",
                elapsedMs < 2000);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private MessageIndexEntry createEntry(long uid, int msgNum, String location, 
            String from, String subject) {
        return new MessageIndexEntry(
            uid, msgNum, 1000L, 1704067200000L, 1704067100000L,
            EnumSet.noneOf(Flag.class),
            location, from, "to@test.com", "", "", subject, "<msg@test.com>", ""
        );
    }

    private MessageIndexEntry createEntryWithFlags(long uid, int msgNum, Set<Flag> flags) {
        return new MessageIndexEntry(
            uid, msgNum, 1000L, 1704067200000L, 1704067100000L,
            flags,
            "location", "from@test.com", "to@test.com", "", "", "Subject", "<msg@test.com>", ""
        );
    }

    private MessageIndexEntry createEntryWithDate(long uid, int msgNum, long internalDate) {
        return new MessageIndexEntry(
            uid, msgNum, 1000L, internalDate, internalDate,
            EnumSet.noneOf(Flag.class),
            "location", "from@test.com", "to@test.com", "", "", "Subject", "<msg@test.com>", ""
        );
    }

    private MessageIndexEntry createEntryWithSize(long uid, int msgNum, long size) {
        return new MessageIndexEntry(
            uid, msgNum, size, 1704067200000L, 1704067100000L,
            EnumSet.noneOf(Flag.class),
            "location", "from@test.com", "to@test.com", "", "", "Subject", "<msg@test.com>", ""
        );
    }

}

