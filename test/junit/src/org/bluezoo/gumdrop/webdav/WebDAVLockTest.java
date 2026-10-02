/*
 * WebDAVLockTest.java
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

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Value semantics of {@link WebDAVLock}: coverage by depth, expiry and
 * refresh against a deterministic clock offset.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebDAVLockTest {

    private static final Path ROOT = Paths.get("/data/dir");

    @After
    public void resetClock() {
        WebDAVLock.clockOffsetMillis = 0L;
    }

    private WebDAVLock lock(int depth, long timeoutSeconds) {
        return new WebDAVLock(ROOT, WebDAVLock.Scope.SHARED, WebDAVLock.Type.WRITE,
                depth, "owner", timeoutSeconds);
    }

    @Test
    public void gettersReflectConstruction() {
        WebDAVLock l = lock(DavConstants.DEPTH_1, 60);
        assertTrue(l.getToken().startsWith(DavConstants.LOCK_TOKEN_SCHEME));
        assertEquals(ROOT, l.getPath());
        assertEquals(WebDAVLock.Scope.SHARED, l.getScope());
        assertEquals(WebDAVLock.Type.WRITE, l.getType());
        assertEquals(DavConstants.DEPTH_1, l.getDepth());
        assertEquals("owner", l.getOwner());
        assertEquals(l.getCreatedAt() + 60000L, l.getExpiresAt());
        assertTrue(l.toString().contains(l.getToken()));
    }

    @Test
    public void coversByDepth() {
        Path child = ROOT.resolve("c");
        Path grandchild = child.resolve("g");
        WebDAVLock zero = lock(DavConstants.DEPTH_0, 60);
        assertTrue(zero.covers(ROOT));
        assertFalse(zero.covers(child));
        WebDAVLock one = lock(DavConstants.DEPTH_1, 60);
        assertTrue(one.covers(child));
        assertFalse(one.covers(grandchild));
        assertFalse(one.covers(Paths.get("/")));
        WebDAVLock inf = lock(DavConstants.DEPTH_INFINITY, 60);
        assertTrue(inf.covers(grandchild));
        assertFalse(inf.covers(Paths.get("/data/other")));
    }

    @Test
    public void expiryFollowsTheClock() {
        WebDAVLock l = lock(0, 60);
        assertFalse(l.isExpired());
        WebDAVLock.clockOffsetMillis = 59000L;
        assertFalse(l.isExpired());
        assertEquals(0L, l.getRemainingTimeoutSeconds() > 1 ? 1L : 0L);
        WebDAVLock.clockOffsetMillis = 120000L;
        assertTrue(l.isExpired());
        assertEquals(0L, l.getRemainingTimeoutSeconds());
    }

    @Test
    public void infiniteLockNeverExpires() {
        WebDAVLock l = lock(0, -1);
        WebDAVLock.clockOffsetMillis = 1000000000L;
        assertFalse(l.isExpired());
        assertEquals(-1L, l.getRemainingTimeoutSeconds());
    }

    @Test
    public void refreshExtendsOrMakesInfinite() {
        WebDAVLock l = lock(0, 10);
        WebDAVLock.clockOffsetMillis = 60000L;
        assertTrue(l.isExpired());
        l.refresh(600);
        assertFalse(l.isExpired());
        l.refresh(-1);
        assertEquals(Long.MAX_VALUE, l.getExpiresAt());
    }

    @Test
    public void restoredLockKeepsRecordedTimes() {
        WebDAVLock l = new WebDAVLock("opaquelocktoken:fixed", ROOT,
                WebDAVLock.Scope.EXCLUSIVE, WebDAVLock.Type.WRITE, 0, "o", 1L, 2L);
        assertEquals("opaquelocktoken:fixed", l.getToken());
        assertEquals(1L, l.getCreatedAt());
        assertTrue(l.isExpired());
    }
}
