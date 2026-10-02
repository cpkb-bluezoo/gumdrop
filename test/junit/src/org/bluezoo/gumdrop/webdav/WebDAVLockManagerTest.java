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
                DavConstants.DEPTH_INFINITY, "owner", 3600);
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
                        DavConstants.DEPTH_INFINITY, "parent", 3600));
    }
}
