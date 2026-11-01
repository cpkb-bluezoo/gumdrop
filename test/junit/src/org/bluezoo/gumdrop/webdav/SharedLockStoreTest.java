/*
 * SharedLockStoreTest.java
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SharedLockStore} on an in-memory file system.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SharedLockStoreTest {

    private static final WebDAVLock.Type WRITE = WebDAVLock.Type.WRITE;
    private static final WebDAVLock.Scope EXCLUSIVE = WebDAVLock.Scope.EXCLUSIVE;
    private static final WebDAVLock.Scope SHARED = WebDAVLock.Scope.SHARED;

    private MemoryFileSystem mem;
    private Path content;
    private Path locks;
    private SharedLockStore store;

    @Before
    public void setUp() throws IOException {
        mem = MemoryFileSystem.create();
        content = mem.getPath("/srv/content");
        locks = mem.getPath("/srv/locks");
        Files.createDirectories(content);
        Files.createDirectories(locks);
        store = new SharedLockStore(content, locks);
    }

    @After
    public void tearDown() {
        WebDAVLock.clockOffsetMillis = 0L;
    }

    @Test
    public void exclusiveLockIsGrantedOnce() {
        Path file = content.resolve("a.txt");
        WebDAVLock first = store.lock(file, EXCLUSIVE, WRITE, 0, "alice", 3600);
        assertNotNull(first);
        assertNull(store.lock(file, EXCLUSIVE, WRITE, 0, "bob", 3600));
        assertNull(store.lock(file, SHARED, WRITE, 0, "bob", 3600));
    }

    @Test
    public void sharedLocksCoexistButBlockExclusive() {
        Path file = content.resolve("a.txt");
        assertNotNull(store.lock(file, SHARED, WRITE, 0, "alice", 3600));
        assertNotNull(store.lock(file, SHARED, WRITE, 0, "bob", 3600));
        assertNull(store.lock(file, EXCLUSIVE, WRITE, 0, "carol", 3600));
        assertEquals(2, store.getLocksAt(file).size());
    }

    @Test
    public void infiniteDepthAncestorBlocksDescendant() {
        Path dir = content.resolve("dir");
        Path file = dir.resolve("a.txt");
        assertNotNull(store.lock(dir, EXCLUSIVE, WRITE, DavConstants.DEPTH_INFINITY, "alice", 3600));
        assertNull(store.lock(file, EXCLUSIVE, WRITE, 0, "bob", 3600));
        assertNull(store.lock(file, SHARED, WRITE, 0, "bob", 3600));
    }

    @Test
    public void depthZeroAncestorDoesNotCoverDescendant() {
        Path dir = content.resolve("dir");
        Path file = dir.resolve("a.txt");
        assertNotNull(store.lock(dir, EXCLUSIVE, WRITE, 0, "alice", 3600));
        assertNotNull(store.lock(file, EXCLUSIVE, WRITE, 0, "bob", 3600));
    }

    @Test
    public void descendantLockBlocksAncestorLock() {
        Path dir = content.resolve("dir");
        Path file = dir.resolve("sub").resolve("a.txt");
        assertNotNull(store.lock(file, EXCLUSIVE, WRITE, 0, "alice", 3600));
        assertNull(store.lock(dir, SHARED, WRITE, DavConstants.DEPTH_INFINITY, "bob", 3600));
    }

    @Test
    public void sharedDescendantDoesNotBlockSharedAncestor() {
        Path dir = content.resolve("dir");
        Path file = dir.resolve("a.txt");
        assertNotNull(store.lock(file, SHARED, WRITE, 0, "alice", 3600));
        assertNotNull(store.lock(dir, SHARED, WRITE, DavConstants.DEPTH_INFINITY, "bob", 3600));
    }

    @Test
    public void refusedGrantLeavesNoRecordBehind() {
        Path dir = content.resolve("dir");
        Path file = dir.resolve("a.txt");
        assertNotNull(store.lock(dir, EXCLUSIVE, WRITE, DavConstants.DEPTH_INFINITY, "alice", 3600));
        assertNull(store.lock(file, EXCLUSIVE, WRITE, 0, "bob", 3600));
        assertTrue(store.getLocksAt(file).isEmpty());
        // the refusal must not have poisoned the resource: once the parent
        // lock is gone the file can be locked
        WebDAVLock parent = store.getLocksAt(dir).get(0);
        assertTrue(store.unlock(dir, parent.getToken()));
        assertNotNull(store.lock(file, EXCLUSIVE, WRITE, 0, "bob", 3600));
    }

    @Test
    public void unlockRemovesLockAndRejectsUnknownToken() {
        Path file = content.resolve("a.txt");
        WebDAVLock lock = store.lock(file, EXCLUSIVE, WRITE, 0, "alice", 3600);
        assertFalse(store.unlock(file, "opaquelocktoken:nope"));
        assertTrue(store.unlock(file, lock.getToken()));
        assertFalse(store.unlock(file, lock.getToken()));
        assertNotNull(store.lock(file, EXCLUSIVE, WRITE, 0, "bob", 3600));
    }

    @Test
    public void unlockSharedLockLeavesOthers() {
        Path file = content.resolve("a.txt");
        WebDAVLock a = store.lock(file, SHARED, WRITE, 0, "alice", 3600);
        WebDAVLock b = store.lock(file, SHARED, WRITE, 0, "bob", 3600);
        assertTrue(store.unlock(file, a.getToken()));
        assertNull(store.getLock(file, a.getToken()));
        assertNotNull(store.getLock(file, b.getToken()));
    }

    @Test
    public void unlockFindsLockHeldOnCoveringAncestor() {
        Path dir = content.resolve("dir");
        Path file = dir.resolve("a.txt");
        WebDAVLock lock = store.lock(dir, EXCLUSIVE, WRITE, DavConstants.DEPTH_INFINITY, "alice", 3600);
        assertTrue(store.validateToken(file, lock.getToken()));
        assertTrue(store.unlock(file, lock.getToken()));
        assertTrue(store.getLocksAt(dir).isEmpty());
    }

    @Test
    public void tokenDoesNotValidateOutsideItsCoverage() {
        Path dir = content.resolve("dir");
        WebDAVLock lock = store.lock(dir, EXCLUSIVE, WRITE, 0, "alice", 3600);
        assertTrue(store.validateToken(dir, lock.getToken()));
        assertFalse(store.validateToken(dir.resolve("a.txt"), lock.getToken()));
        assertFalse(store.validateToken(content.resolve("other"), lock.getToken()));
    }

    @Test
    public void expiredLockIsInvisibleAndCannotBeUnlockedOrRefreshed() {
        Path file = content.resolve("a.txt");
        WebDAVLock lock = store.lock(file, EXCLUSIVE, WRITE, 0, "alice", 10);
        WebDAVLock.clockOffsetMillis = 11000L;
        assertNull(store.getLock(file, lock.getToken()));
        assertFalse(store.validateToken(file, lock.getToken()));
        assertFalse(store.unlock(file, lock.getToken()));
        assertNull(store.refresh(file, lock.getToken(), 60));
        assertTrue(store.getCoveringLocks(file).isEmpty());
    }

    @Test
    public void expiredLockIsReplacedByNewGrant() {
        Path file = content.resolve("a.txt");
        assertNotNull(store.lock(file, EXCLUSIVE, WRITE, 0, "alice", 10));
        WebDAVLock.clockOffsetMillis = 11000L;
        assertNotNull(store.lock(file, EXCLUSIVE, WRITE, 0, "bob", 3600));
    }

    @Test
    public void expiredAncestorRecordIsSweptOnConflictCheck() {
        Path dir = content.resolve("dir");
        Path file = dir.resolve("a.txt");
        assertNotNull(store.lock(dir, EXCLUSIVE, WRITE, DavConstants.DEPTH_INFINITY, "alice", 10));
        WebDAVLock.clockOffsetMillis = 11000L;
        assertNotNull(store.lock(file, EXCLUSIVE, WRITE, 0, "bob", 3600));
        assertTrue(store.getLocksAt(dir).isEmpty());
    }

    @Test
    public void expiredDescendantRecordIsSweptOnConflictCheck() {
        Path dir = content.resolve("dir");
        Path file = dir.resolve("a.txt");
        assertNotNull(store.lock(file, EXCLUSIVE, WRITE, 0, "alice", 10));
        WebDAVLock.clockOffsetMillis = 11000L;
        assertNotNull(store.lock(dir, EXCLUSIVE, WRITE, DavConstants.DEPTH_INFINITY, "bob", 3600));
        assertTrue(store.getLocksAt(file).isEmpty());
    }

    @Test
    public void refreshExtendsLifetimeAndPersists() {
        Path file = content.resolve("a.txt");
        WebDAVLock lock = store.lock(file, EXCLUSIVE, WRITE, 0, "alice", 10);
        WebDAVLock.clockOffsetMillis = 5000L;
        WebDAVLock refreshed = store.refresh(file, lock.getToken(), 100);
        assertNotNull(refreshed);
        WebDAVLock.clockOffsetMillis = 50000L;
        WebDAVLock reread = store.getLock(file, lock.getToken());
        assertNotNull("refresh must be written back to the record", reread);
        assertEquals(lock.getToken(), reread.getToken());
    }

    @Test
    public void refreshOfUnknownTokenReturnsNull() {
        Path file = content.resolve("a.txt");
        assertNull(store.refresh(file, "opaquelocktoken:nope", 60));
    }

    @Test
    public void recordRoundTripPreservesAttributes() {
        Path file = content.resolve("dir").resolve("a.txt");
        WebDAVLock lock = store.lock(file, SHARED, WRITE, 0, "alice <alice@example.com>", -1);
        WebDAVLock read = store.getLock(file, lock.getToken());
        assertNotNull(read);
        assertEquals(file, read.getPath());
        assertEquals(SHARED, read.getScope());
        assertEquals(WRITE, read.getType());
        assertEquals(0, read.getDepth());
        assertEquals("alice <alice@example.com>", read.getOwner());
        assertEquals(lock.getCreatedAt(), read.getCreatedAt());
        assertEquals(Long.MAX_VALUE, read.getExpiresAt());
    }

    @Test
    public void lockWithoutOwnerRoundTrips() {
        Path file = content.resolve("a.txt");
        WebDAVLock lock = store.lock(file, EXCLUSIVE, WRITE, 0, null, 3600);
        WebDAVLock read = store.getLock(file, lock.getToken());
        assertNotNull(read);
        assertNull(read.getOwner());
    }

    @Test
    public void contentRootItselfCanBeLocked() {
        WebDAVLock lock = store.lock(content, EXCLUSIVE, WRITE, DavConstants.DEPTH_INFINITY, "alice", 3600);
        assertNotNull(lock);
        WebDAVLock read = store.getLock(content.resolve("x").resolve("y"), lock.getToken());
        assertNotNull(read);
        assertEquals(content, read.getPath());
        assertNull(store.lock(content.resolve("x"), SHARED, WRITE, 0, "bob", 3600));
    }

    @Test
    public void coveringLocksIncludeAncestorsNearestFirst() {
        Path dir = content.resolve("dir");
        Path file = dir.resolve("a.txt");
        store.lock(content, SHARED, WRITE, DavConstants.DEPTH_INFINITY, "root", 3600);
        store.lock(dir, SHARED, WRITE, DavConstants.DEPTH_INFINITY, "dir", 3600);
        store.lock(file, SHARED, WRITE, 0, "file", 3600);
        List<WebDAVLock> covering = store.getCoveringLocks(file);
        assertEquals(3, covering.size());
        assertEquals(file, covering.get(0).getPath());
        assertEquals(dir, covering.get(1).getPath());
        assertEquals(content, covering.get(2).getPath());
        assertEquals(1, store.getLocksAt(dir).size());
    }

    @Test
    public void resourceNamedLikeRecordIsNotConfused() {
        Path exclusive = content.resolve("exclusive");
        Path shared = content.resolve("shared");
        assertNotNull(store.lock(content, EXCLUSIVE, WRITE, 0, "carol", 3600));
        assertNotNull(store.lock(exclusive, EXCLUSIVE, WRITE, 0, "alice", 3600));
        assertNotNull(store.lock(shared, EXCLUSIVE, WRITE, 0, "bob", 3600));
        assertEquals(1, store.getLocksAt(exclusive).size());
        assertEquals(1, store.getLocksAt(shared).size());
    }

    @Test
    public void pathOutsideContentRootIsRejected() {
        try {
            store.lock(mem.getPath("/elsewhere/a.txt"), EXCLUSIVE, WRITE, 0, "alice", 3600);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("/elsewhere/a.txt"));
        }
    }

    @Test
    public void serversMountingTheTreeAtDifferentPathsShareLocks() throws IOException {
        Path otherContent = mem.getPath("/mnt/other");
        Files.createDirectories(otherContent);
        SharedLockStore other = new SharedLockStore(otherContent, locks);
        WebDAVLock lock = store.lock(content.resolve("a.txt"), EXCLUSIVE, WRITE, 0, "alice", 3600);
        assertNotNull(lock);
        assertNull(other.lock(otherContent.resolve("a.txt"), EXCLUSIVE, WRITE, 0, "bob", 3600));
        WebDAVLock seen = other.getLock(otherContent.resolve("a.txt"), lock.getToken());
        assertNotNull(seen);
        assertEquals(otherContent.resolve("a.txt"), seen.getPath());
        assertTrue(other.unlock(otherContent.resolve("a.txt"), lock.getToken()));
        assertNotNull(store.lock(content.resolve("a.txt"), EXCLUSIVE, WRITE, 0, "carol", 3600));
    }

    @Test
    public void abandonedUnreadableRecordIsReplaced() throws IOException {
        Path file = content.resolve("a.txt");
        Path dir = locks.resolve("_a.txt");
        Files.createDirectories(dir);
        // a record without the mandatory fields reads as null; the in-memory
        // file system's logical clock is far in the past, so it is abandoned
        Files.write(dir.resolve("exclusive"), "garbage".getBytes(StandardCharsets.UTF_8));
        assertNotNull(store.lock(file, EXCLUSIVE, WRITE, 0, "alice", 3600));
    }

    @Test
    public void abandonedUnreadableAncestorRecordIsSweptAndIgnored() throws IOException {
        Path dir = locks.resolve("_dir");
        Files.createDirectories(dir);
        Files.write(dir.resolve("exclusive"), "garbage".getBytes(StandardCharsets.UTF_8));
        Path file = content.resolve("dir").resolve("a.txt");
        assertNotNull(store.lock(file, EXCLUSIVE, WRITE, 0, "alice", 3600));
        assertFalse(Files.exists(dir.resolve("exclusive")));
    }

    @Test
    public void abandonedUnreadableDescendantRecordIsSweptAndIgnored() throws IOException {
        Path below = locks.resolve("_dir").resolve("_a.txt");
        Files.createDirectories(below);
        Files.write(below.resolve("exclusive"), "garbage".getBytes(StandardCharsets.UTF_8));
        Path dir = content.resolve("dir");
        assertNotNull(store.lock(dir, EXCLUSIVE, WRITE, DavConstants.DEPTH_INFINITY, "alice", 3600));
        assertFalse(Files.exists(below.resolve("exclusive")));
    }

    @Test
    public void tempFilesInLockTreeAreNotTreatedAsRecords() throws IOException {
        Path dir = locks.resolve("_a.txt").resolve("shared");
        Files.createDirectories(dir);
        Files.write(dir.resolve(".tmp-123"), "partial".getBytes(StandardCharsets.UTF_8));
        Path file = content.resolve("a.txt");
        assertNotNull(store.lock(file, EXCLUSIVE, WRITE, 0, "alice", 3600));
        assertEquals(1, store.getLocksAt(file).size());
    }

    @Test
    public void tokenWithSeparatorsMapsToSafeFileName() {
        Path file = content.resolve("a.txt");
        WebDAVLock lock = store.lock(file, SHARED, WRITE, 0, "alice", 3600);
        assertNotNull(store.getLock(file, lock.getToken()));
        // a token that merely contains separators must not escape the record directory
        assertNull(store.getLock(file, "../../evil"));
        assertNull(store.getLock(file, "a\\b/c"));
    }
}
