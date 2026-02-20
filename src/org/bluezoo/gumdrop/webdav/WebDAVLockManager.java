/*
 * WebDAVLockManager.java
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages WebDAV locks for resources.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class WebDAVLockManager {

    private final Map<String, WebDAVLock> locksByToken = new ConcurrentHashMap<String, WebDAVLock>();
    private final Map<Path, List<WebDAVLock>> locksByPath = new ConcurrentHashMap<Path, List<WebDAVLock>>();

    synchronized WebDAVLock lock(Path path, WebDAVLock.Scope scope, 
                                  WebDAVLock.Type type, int depth,
                                  String owner, long timeoutSeconds) {
        cleanExpiredLocks();

        if (hasConflictingLock(path, scope)) {
            return null;
        }

        WebDAVLock lock = new WebDAVLock(path, scope, type, depth, owner, timeoutSeconds);
        locksByToken.put(lock.getToken(), lock);

        List<WebDAVLock> pathLocks = locksByPath.get(path);
        if (pathLocks == null) {
            pathLocks = new ArrayList<WebDAVLock>();
            locksByPath.put(path, pathLocks);
        }
        pathLocks.add(lock);

        return lock;
    }

    synchronized boolean unlock(String token) {
        WebDAVLock lock = locksByToken.remove(token);
        if (lock == null) {
            return false;
        }

        List<WebDAVLock> pathLocks = locksByPath.get(lock.getPath());
        if (pathLocks != null) {
            pathLocks.remove(lock);
            if (pathLocks.isEmpty()) {
                locksByPath.remove(lock.getPath());
            }
        }

        return true;
    }

    synchronized WebDAVLock refresh(String token, long timeoutSeconds) {
        WebDAVLock lock = locksByToken.get(token);
        if (lock != null && !lock.isExpired()) {
            lock.refresh(timeoutSeconds);
            return lock;
        }
        return null;
    }

    WebDAVLock getLock(String token) {
        WebDAVLock lock = locksByToken.get(token);
        if (lock != null && lock.isExpired()) {
            synchronized (this) {
                unlock(token);
            }
            return null;
        }
        return lock;
    }

    List<WebDAVLock> getLocks(Path path) {
        List<WebDAVLock> result = new ArrayList<WebDAVLock>();
        List<WebDAVLock> pathLocks = locksByPath.get(path);
        if (pathLocks != null) {
            synchronized (this) {
                for (WebDAVLock lock : pathLocks) {
                    if (!lock.isExpired()) {
                        result.add(lock);
                    }
                }
            }
        }
        return result;
    }

    List<WebDAVLock> getCoveringLocks(Path path) {
        List<WebDAVLock> result = new ArrayList<WebDAVLock>();
        synchronized (this) {
            for (WebDAVLock lock : locksByToken.values()) {
                if (!lock.isExpired() && lock.covers(path)) {
                    result.add(lock);
                }
            }
        }
        return result;
    }

    boolean isLocked(Path path) {
        return !getCoveringLocks(path).isEmpty();
    }

    boolean validateToken(Path path, String token) {
        WebDAVLock lock = getLock(token);
        return lock != null && lock.covers(path);
    }

    private boolean hasConflictingLock(Path path, WebDAVLock.Scope requestedScope) {
        for (WebDAVLock existing : locksByToken.values()) {
            if (existing.isExpired()) {
                continue;
            }

            if (existing.covers(path)) {
                if (existing.getScope() == WebDAVLock.Scope.EXCLUSIVE) {
                    return true;
                }
                if (requestedScope == WebDAVLock.Scope.EXCLUSIVE) {
                    return true;
                }
            }

            if (path.equals(existing.getPath()) || existing.getPath().startsWith(path)) {
                if (existing.getScope() == WebDAVLock.Scope.EXCLUSIVE 
                        || requestedScope == WebDAVLock.Scope.EXCLUSIVE) {
                    return true;
                }
            }
        }
        return false;
    }

    synchronized void cleanExpiredLocks() {
        Iterator<Map.Entry<String, WebDAVLock>> it = locksByToken.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, WebDAVLock> entry = it.next();
            WebDAVLock lock = entry.getValue();
            if (lock.isExpired()) {
                it.remove();
                List<WebDAVLock> pathLocks = locksByPath.get(lock.getPath());
                if (pathLocks != null) {
                    pathLocks.remove(lock);
                    if (pathLocks.isEmpty()) {
                        locksByPath.remove(lock.getPath());
                    }
                }
            }
        }
    }
}
