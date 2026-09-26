/*
 * SharedLockRootTest.java
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

package org.bluezoo.gumdrop.webdav;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #499: with a lock root, locks are files keyed by each resource's path
 * relative to the content root, so servers that share the lock root agree
 * about them wherever they mount the content tree.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SharedLockRootTest {

    private static final WebDAVLock.Type WRITE = WebDAVLock.Type.WRITE;
    private static final WebDAVLock.Scope EXCLUSIVE = WebDAVLock.Scope.EXCLUSIVE;
    private static final WebDAVLock.Scope SHARED = WebDAVLock.Scope.SHARED;
    private static final int INFINITY = DavConstants.DEPTH_INFINITY;

    private Path base;
    private Path contentA;
    private Path contentB;
    private Path lockRoot;
    private WebDAVLockManager podA;
    private WebDAVLockManager podB;

    @Before
    public void setUp() throws IOException {
        base = Files.createTempDirectory("shared-locks");
        // one logical tree mounted at two different absolute paths
        contentA = Files.createDirectories(base.resolve("podA/data"));
        contentB = Files.createDirectories(base.resolve("podB/mnt/data"));
        lockRoot = Files.createDirectories(base.resolve("locks"));
        podA = new WebDAVLockManager(contentA, lockRoot);
        podB = new WebDAVLockManager(contentB, lockRoot);
    }

    @After
    public void tearDown() throws IOException {
        Files.walkFileTree(base, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static Path in(Path content, String relative) {
        return content.resolve(relative);
    }

    private static long fileCount(Path dir) throws IOException {
        final long[] total = new long[1];
        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) {
                total[0]++;
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    @Test
    public void withNoLockRootLocksStayInMemoryAndWriteNothing() throws IOException {
        WebDAVLockManager memory = new WebDAVLockManager(contentA, null);
        assertNotNull(memory.lock(in(contentA, "doc.txt"), EXCLUSIVE, WRITE, 0, "me", 3600));
        assertEquals(0, fileCount(lockRoot));
        assertEquals(0, fileCount(base.resolve("podA")));
        // and another manager knows nothing of it
        assertNotNull(new WebDAVLockManager(contentA, null).lock(in(contentA, "doc.txt"), EXCLUSIVE, WRITE, 0, "you", 3600));
    }

    @Test
    public void secondExclusiveLockOnTheSameRelativePathIsRefused() {
        assertNotNull(podA.lock(in(contentA, "docs/a.txt"), EXCLUSIVE, WRITE, 0, "a", 3600));
        assertNull(podB.lock(in(contentB, "docs/a.txt"), EXCLUSIVE, WRITE, 0, "b", 3600));
        assertNull("a shared lock also conflicts with an exclusive one",
                podB.lock(in(contentB, "docs/a.txt"), SHARED, WRITE, 0, "b", 3600));
    }

    @Test
    public void sharedLocksCoexistAndBlockAnExclusiveOne() {
        assertNotNull(podA.lock(in(contentA, "a.txt"), SHARED, WRITE, 0, "a", 3600));
        assertNotNull(podB.lock(in(contentB, "a.txt"), SHARED, WRITE, 0, "b", 3600));
        assertNull(podB.lock(in(contentB, "a.txt"), EXCLUSIVE, WRITE, 0, "b", 3600));
        assertEquals(2, podA.getCoveringLocks(in(contentA, "a.txt")).size());
    }

    @Test
    public void parentInfinityLockConflictsWithAChildLockOnAnotherServer() {
        assertNotNull(podA.lock(in(contentA, "docs"), EXCLUSIVE, WRITE, INFINITY, "a", 3600));
        assertNull(podB.lock(in(contentB, "docs/sub/child.txt"), EXCLUSIVE, WRITE, 0, "b", 3600));
        assertNotNull("an unrelated path is unaffected",
                podB.lock(in(contentB, "other/child.txt"), EXCLUSIVE, WRITE, 0, "b", 3600));
    }

    @Test
    public void childLockConflictsWithALaterParentLock() {
        assertNotNull(podA.lock(in(contentA, "docs/child.txt"), EXCLUSIVE, WRITE, 0, "a", 3600));
        assertNull(podB.lock(in(contentB, "docs"), EXCLUSIVE, WRITE, INFINITY, "b", 3600));
    }

    @Test
    public void unlockOnOneServerMakesThePathLockableOnTheOther() {
        WebDAVLock lock = podA.lock(in(contentA, "a.txt"), EXCLUSIVE, WRITE, 0, "a", 3600);
        assertNotNull(lock);
        assertTrue(podA.unlock(in(contentA, "a.txt"), lock.getToken()));
        assertNotNull(podB.lock(in(contentB, "a.txt"), EXCLUSIVE, WRITE, 0, "b", 3600));
    }

    @Test
    public void anotherServerCanUnlockAndValidateATokenItDidNotIssue() {
        WebDAVLock lock = podA.lock(in(contentA, "docs/a.txt"), EXCLUSIVE, WRITE, 0, "a", 3600);
        assertTrue(podB.validateToken(in(contentB, "docs/a.txt"), lock.getToken()));
        assertFalse(podB.validateToken(in(contentB, "docs/other.txt"), lock.getToken()));
        assertFalse("an unknown token unlocks nothing",
                podB.unlock(in(contentB, "docs/a.txt"), "opaquelocktoken:unknown"));
        assertTrue(podB.unlock(in(contentB, "docs/a.txt"), lock.getToken()));
        assertFalse(podA.validateToken(in(contentA, "docs/a.txt"), lock.getToken()));
    }

    @Test
    public void aTokenOfADepthInfinityLockUnlocksThroughADescendant() {
        WebDAVLock lock = podA.lock(in(contentA, "docs"), EXCLUSIVE, WRITE, INFINITY, "a", 3600);
        assertTrue(podB.validateToken(in(contentB, "docs/sub/x.txt"), lock.getToken()));
        assertTrue(podB.unlock(in(contentB, "docs/sub/x.txt"), lock.getToken()));
        assertTrue(podA.getCoveringLocks(in(contentA, "docs")).isEmpty());
    }

    @Test
    public void refreshOnOneServerIsSeenByTheOther() {
        WebDAVLock lock = podA.lock(in(contentA, "a.txt"), EXCLUSIVE, WRITE, 0, "a", 60);
        WebDAVLock refreshed = podB.refresh(in(contentB, "a.txt"), lock.getToken(), 7200);
        assertNotNull(refreshed);
        WebDAVLock seen = podA.getLock(in(contentA, "a.txt"), lock.getToken());
        assertTrue(seen.getRemainingTimeoutSeconds() > 3600);
        assertNull(podA.refresh(in(contentA, "a.txt"), "opaquelocktoken:unknown", 60));
    }

    @Test
    public void anExpiredRecordDoesNotBlockANewGrant() {
        WebDAVLock lock = podA.lock(in(contentA, "a.txt"), EXCLUSIVE, WRITE, 0, "a", 0);
        assertNotNull(lock);
        while (!lock.isExpired()) {
            Thread.onSpinWait();
        }
        assertTrue(podB.getCoveringLocks(in(contentB, "a.txt")).isEmpty());
        assertNotNull(podB.lock(in(contentB, "a.txt"), EXCLUSIVE, WRITE, 0, "b", 3600));
    }

    @Test
    public void anExpiredParentRecordDoesNotBlockAChild() {
        WebDAVLock lock = podA.lock(in(contentA, "docs"), EXCLUSIVE, WRITE, INFINITY, "a", 0);
        while (!lock.isExpired()) {
            Thread.onSpinWait();
        }
        assertNotNull(podB.lock(in(contentB, "docs/a.txt"), EXCLUSIVE, WRITE, 0, "b", 3600));
    }

    @Test
    public void aResourceNamedLikeARecordIsNotConfusedWithOne() {
        assertNotNull(podA.lock(in(contentA, "docs/exclusive"), EXCLUSIVE, WRITE, 0, "a", 3600));
        assertNotNull(podB.lock(in(contentB, "docs/shared"), EXCLUSIVE, WRITE, 0, "b", 3600));
        assertNotNull("docs itself is not locked by locks on its children's names",
                podB.lock(in(contentB, "docs/other"), EXCLUSIVE, WRITE, 0, "b", 3600));
        assertEquals(1, podA.getCoveringLocks(in(contentA, "docs/exclusive")).size());
        assertEquals(1, podA.getCoveringLocks(in(contentA, "docs/shared")).size());
        assertTrue(podA.getCoveringLocks(in(contentA, "docs")).isEmpty());
    }

    @Test
    public void recordsAreKeyedByRelativePath() throws IOException {
        podA.lock(in(contentA, "docs/a.txt"), EXCLUSIVE, WRITE, 0, "a", 3600);
        assertTrue(Files.isRegularFile(lockRoot.resolve("_docs").resolve("_a.txt").resolve("exclusive")));
    }

    /**
     * Runs two lock attempts at the same moment, each on its own thread, and
     * returns what each got.
     */
    private static WebDAVLock[] race(final Callable<WebDAVLock> first, final Callable<WebDAVLock> second)
            throws Exception {
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final WebDAVLock[] results = new WebDAVLock[2];
        final Throwable[] failures = new Throwable[2];
        Thread[] threads = new Thread[2];
        final Callable<WebDAVLock>[] attempts = new Callable[] { first, second };
        for (int i = 0; i < 2; i++) {
            final int index = i;
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                        results[index] = attempts[index].call();
                    } catch (Throwable t) {
                        failures[index] = t;
                    }
                }
            });
            threads[i].start();
        }
        for (int i = 0; i < 2; i++) {
            threads[i].join();
            if (failures[i] != null) {
                throw new AssertionError(failures[i]);
            }
        }
        return results;
    }

    @Test
    public void concurrentExclusiveGrantsLeaveOneWinner() throws Exception {
        for (int round = 0; round < 50; round++) {
            final String name = "race" + round + ".txt";
            WebDAVLock[] got = race(new Callable<WebDAVLock>() {
                @Override
                public WebDAVLock call() {
                    return podA.lock(in(contentA, name), EXCLUSIVE, WRITE, 0, "a", 3600);
                }
            }, new Callable<WebDAVLock>() {
                @Override
                public WebDAVLock call() {
                    return podB.lock(in(contentB, name), EXCLUSIVE, WRITE, 0, "b", 3600);
                }
            });
            int winners = (got[0] != null ? 1 : 0) + (got[1] != null ? 1 : 0);
            assertEquals("round " + round, 1, winners);
        }
    }

    @Test
    public void concurrentParentAndChildGrantsLeaveAtMostOne() throws Exception {
        for (int round = 0; round < 50; round++) {
            final String parent = "dir" + round;
            WebDAVLock[] got = race(new Callable<WebDAVLock>() {
                @Override
                public WebDAVLock call() {
                    return podA.lock(in(contentA, parent), EXCLUSIVE, WRITE, INFINITY, "a", 3600);
                }
            }, new Callable<WebDAVLock>() {
                @Override
                public WebDAVLock call() {
                    return podB.lock(in(contentB, parent + "/child.txt"), EXCLUSIVE, WRITE, 0, "b", 3600);
                }
            });
            int winners = (got[0] != null ? 1 : 0) + (got[1] != null ? 1 : 0);
            // both refusing (each seeing the other) is allowed: the client retries
            assertTrue("round " + round + ": both granted", winners <= 1);
        }
    }

    @Test
    public void refusedGrantLeavesNoRecordBehind() throws IOException {
        assertNotNull(podA.lock(in(contentA, "a.txt"), EXCLUSIVE, WRITE, 0, "a", 3600));
        assertNull(podB.lock(in(contentB, "a.txt"), EXCLUSIVE, WRITE, 0, "b", 3600));
        assertNull(podB.lock(in(contentB, "a.txt"), SHARED, WRITE, 0, "b", 3600));
        assertEquals(1, fileCount(lockRoot));
    }

    @Test
    public void getLocksListsOnlyTheLocksOnThePathItself() {
        podA.lock(in(contentA, "docs"), SHARED, WRITE, INFINITY, "a", 3600);
        podA.lock(in(contentA, "docs/a.txt"), SHARED, WRITE, 0, "a", 3600);
        List<WebDAVLock> here = new ArrayList<WebDAVLock>(podB.getLocks(in(contentB, "docs/a.txt")));
        assertEquals(1, here.size());
    }
}
