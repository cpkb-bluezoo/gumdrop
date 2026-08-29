/*
 * WebDAVLockManagerTest.java
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

import org.junit.Test;

import static org.junit.Assert.*;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Regression coverage for issue #305: lock conflict and covering-lock
 * checks previously scanned every lock on the server via
 * {@code locksByToken.values()} even though {@code locksByPath} already
 * indexes locks by resource path.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebDAVLockManagerTest {

    private static final WebDAVLock.Type WRITE = WebDAVLock.Type.WRITE;

    @Test
    public void ancestorInfinityLockCoversDescendant() {
        WebDAVLockManager manager = new WebDAVLockManager();
        Path collection = Paths.get("/data");
        Path file = Paths.get("/data/file.txt");

        WebDAVLock lock = manager.lock(collection,
                WebDAVLock.Scope.EXCLUSIVE, WRITE,
                DAVConstants.DEPTH_INFINITY, "owner", 3600);
        assertNotNull(lock);
        assertTrue(manager.isLocked(file));
        assertEquals(1, manager.getCoveringLocks(file).size());
    }

    @Test
    public void exclusiveLockOnDescendantBlocksParentLock() {
        WebDAVLockManager manager = new WebDAVLockManager();
        Path parent = Paths.get("/data");
        Path child = Paths.get("/data/nested.txt");

        assertNotNull(manager.lock(child, WebDAVLock.Scope.EXCLUSIVE, WRITE,
                0, "child", 3600));
        assertNull("exclusive lock on a descendant must block a new parent lock",
                manager.lock(parent, WebDAVLock.Scope.SHARED, WRITE,
                        DAVConstants.DEPTH_INFINITY, "parent", 3600));
    }

    @Test(timeout = 10000)
    public void lockCheckCostDoesNotScaleWithUnrelatedLocks() {
        WebDAVLockManager manager = new WebDAVLockManager();
        Path target = Paths.get("/target/resource.txt");

        for (int i = 0; i < 50; i++) {
            assertNotNull(manager.lock(Paths.get("/other/lock" + i),
                    WebDAVLock.Scope.SHARED, WRITE, 0, "owner", 3600));
        }
        long baselineMs = timeLockChecks(manager, target, 2000);

        for (int i = 50; i < 5000; i++) {
            assertNotNull(manager.lock(Paths.get("/other/lock" + i),
                    WebDAVLock.Scope.SHARED, WRITE, 0, "owner", 3600));
        }
        long withManyMs = timeLockChecks(manager, target, 2000);

        assertTrue("covering-lock check took " + withManyMs + "ms with 5000 "
                + "unrelated locks vs " + baselineMs + "ms with 50 -- an "
                + "unindexed server-wide scan would be far slower",
                withManyMs < baselineMs * 5 + 50);
    }

    private static long timeLockChecks(WebDAVLockManager manager, Path target,
            int iterations) {
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            assertFalse(manager.isLocked(target));
        }
        return (System.nanoTime() - start) / 1_000_000;
    }
}
