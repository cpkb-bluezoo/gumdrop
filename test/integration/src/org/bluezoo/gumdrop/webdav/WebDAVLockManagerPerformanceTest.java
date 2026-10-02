/*
 * WebDAVLockManagerPerformanceTest.java
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

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Wall-clock regression test for issue #305: the covering-lock check must
 * not scan every lock on the server. Wall-clock assertions do not belong in
 * the deterministic unit suite; run with
 * {@code ant integration-test-performance}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebDAVLockManagerPerformanceTest {

    private static final WebDAVLock.Type WRITE = WebDAVLock.Type.WRITE;

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
