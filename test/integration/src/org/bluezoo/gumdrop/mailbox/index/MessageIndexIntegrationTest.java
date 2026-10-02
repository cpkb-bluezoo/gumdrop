/*
 * MessageIndexIntegrationTest.java
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
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Integration tests for {@link MessageIndex}.
 *
 * <p>Integration test: saves concurrently from real threads to prove distinct
 * temp files are used.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageIndexIntegrationTest {

    private MemoryFileSystem mem;
    private Path indexPath;
    private MessageIndex index;

    @Before
    public void setUp() throws Exception {
        mem = MemoryFileSystem.create();
        indexPath = mem.getPath("/indexes/test.gidx");
        Files.createDirectories(indexPath.getParent());
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
    // Save and Load Tests
    // ========================================================================

    /**
     * Regression for issue #337: two sessions saving the same {@code .gidx}
     * path concurrently must not share one literal temp filename.
     */
    @Test(timeout = 15000)
    public void testConcurrentSavesUseDistinctTempFiles() throws Exception {
        Path maildirIndex = mem.getPath("/maildir/.gidx");
        Files.createDirectories(maildirIndex.getParent());
        Files.deleteIfExists(maildirIndex);

        MessageIndex indexA = new MessageIndex(maildirIndex, 1000L, 1L);
        indexA.addEntry(createEntry(1L, 1, "loc1", "a@test.com", "Subject A"));

        MessageIndex indexB = new MessageIndex(maildirIndex, 1000L, 1L);
        indexB.addEntry(createEntry(2L, 2, "loc2", "b@test.com", "Subject B"));

        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Exception> errorA = new AtomicReference<>();
        AtomicReference<Exception> errorB = new AtomicReference<>();

        Thread threadA = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    indexA.save();
                } catch (Exception e) {
                    errorA.set(e);
                }
            }
        });
        Thread threadB = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    indexB.save();
                } catch (Exception e) {
                    errorB.set(e);
                }
            }
        });

        threadA.start();
        threadB.start();
        go.countDown();
        threadA.join(TimeUnit.SECONDS.toMillis(10));
        threadB.join(TimeUnit.SECONDS.toMillis(10));

        assertNull("first concurrent save failed", errorA.get());
        assertNull("second concurrent save failed", errorB.get());
        assertTrue(Files.exists(maildirIndex));
        assertFalse(Files.exists(maildirIndex.resolveSibling(".gidx.tmp")));
    }

    // ========================================================================
    // Flag Update Tests
    // ========================================================================
    // Remove Entry Tests
    // ========================================================================
    // Compact Tests
    // ========================================================================
    // Sub-Index Tests (Flag BitSets)
    // ========================================================================
    // Date Range Query Tests
    // ========================================================================
    // Size Range Query Tests
    // ========================================================================
    // Corruption Detection Tests
    // ========================================================================
    // Get Entry by Message Number Tests
    // ========================================================================
    // Iterator Tests
    // ========================================================================
    // search() / computeCandidateIndices() Tests (issue #304)
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
}
