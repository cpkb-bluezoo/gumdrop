/*
 * WebdavLockManager.java
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
 * <p>Implements RFC 4918 §6 (locking) and §7 (write locks).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">RFC 4918</a>
 */
class WebdavLockManager {

    private final Map<String, WebdavLock> locksByToken = new ConcurrentHashMap<String, WebdavLock>();
    private final Map<Path, List<WebdavLock>> locksByPath = new ConcurrentHashMap<Path, List<WebdavLock>>();

    /**
     * Acquires a lock on a resource (RFC 4918 §9.10).
     */
    synchronized WebdavLock lock(Path path, WebdavLock.Scope scope, 
                                  WebdavLock.Type type, int depth,
                                  String owner, long timeoutSeconds) {
        if (hasConflictingLock(path, scope)) {
            return null;
        }

        WebdavLock lock = new WebdavLock(path, scope, type, depth, owner, timeoutSeconds);
        locksByToken.put(lock.getToken(), lock);

        List<WebdavLock> pathLocks = locksByPath.get(path);
        if (pathLocks == null) {
            pathLocks = new ArrayList<WebdavLock>();
            locksByPath.put(path, pathLocks);
        }
        pathLocks.add(lock);

        return lock;
    }

    /**
     * Releases a lock by token (RFC 4918 §9.11).
     */
    synchronized boolean unlock(String token) {
        WebdavLock lock = locksByToken.remove(token);
        if (lock == null) {
            return false;
        }

        List<WebdavLock> pathLocks = locksByPath.get(lock.getPath());
        if (pathLocks != null) {
            pathLocks.remove(lock);
            if (pathLocks.isEmpty()) {
                locksByPath.remove(lock.getPath());
            }
        }

        cleanExpiredLocks();
        return true;
    }

    /**
     * Refreshes a lock timeout (RFC 4918 §9.10.2).
     */
    synchronized WebdavLock refresh(String token, long timeoutSeconds) {
        WebdavLock lock = locksByToken.get(token);
        if (lock != null && !lock.isExpired()) {
            lock.refresh(timeoutSeconds);
            return lock;
        }
        return null;
    }

    WebdavLock getLock(String token) {
        WebdavLock lock = locksByToken.get(token);
        if (lock != null && lock.isExpired()) {
            synchronized (this) {
                unlock(token);
            }
            return null;
        }
        return lock;
    }

    List<WebdavLock> getLocks(Path path) {
        List<WebdavLock> result = new ArrayList<WebdavLock>();
        List<WebdavLock> pathLocks = locksByPath.get(path);
        if (pathLocks != null) {
            synchronized (this) {
                for (WebdavLock lock : pathLocks) {
                    if (!lock.isExpired()) {
                        result.add(lock);
                    }
                }
            }
        }
        return result;
    }

    List<WebdavLock> getCoveringLocks(Path path) {
        List<WebdavLock> result = new ArrayList<WebdavLock>();
        synchronized (this) {
            forEachAncestor(path, new PathVisitor() {
                @Override
                public void visit(Path ancestor) {
                    List<WebdavLock> pathLocks = locksByPath.get(ancestor);
                    if (pathLocks == null) {
                        return;
                    }
                    for (WebdavLock lock : pathLocks) {
                        if (!lock.isExpired() && lock.covers(path)) {
                            result.add(lock);
                        }
                    }
                }
            });
        }
        return result;
    }

    boolean isLocked(Path path) {
        return !getCoveringLocks(path).isEmpty();
    }

    boolean validateToken(Path path, String token) {
        WebdavLock lock = getLock(token);
        return lock != null && lock.covers(path);
    }

    /**
     * Checks for conflicting locks (RFC 4918 §6.1–6.2).
     */
    private boolean hasConflictingLock(Path path, WebdavLock.Scope requestedScope) {
        final boolean[] conflictFound = new boolean[1];
        forEachAncestor(path, new PathVisitor() {
            @Override
            public void visit(Path ancestor) {
                if (conflictFound[0]) {
                    return;
                }
                List<WebdavLock> pathLocks = locksByPath.get(ancestor);
                if (pathLocks == null) {
                    return;
                }
                for (WebdavLock existing : pathLocks) {
                    if (existing.isExpired()) {
                        continue;
                    }
                    if (existing.covers(path)) {
                        if (existing.getScope() == WebdavLock.Scope.EXCLUSIVE
                                || requestedScope == WebdavLock.Scope.EXCLUSIVE) {
                            conflictFound[0] = true;
                            return;
                        }
                    }
                }
            }
        });
        if (conflictFound[0]) {
            return true;
        }

        for (Map.Entry<Path, List<WebdavLock>> entry : locksByPath.entrySet()) {
            Path lockedPath = entry.getKey();
            if (!lockedPath.startsWith(path) || lockedPath.equals(path)) {
                continue;
            }
            for (WebdavLock existing : entry.getValue()) {
                if (existing.isExpired()) {
                    continue;
                }
                if (existing.getScope() == WebdavLock.Scope.EXCLUSIVE
                        || requestedScope == WebdavLock.Scope.EXCLUSIVE) {
                    return true;
                }
            }
        }
        return false;
    }

    synchronized void cleanExpiredLocks() {
        Iterator<Map.Entry<String, WebdavLock>> it = locksByToken.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, WebdavLock> entry = it.next();
            WebdavLock lock = entry.getValue();
            if (lock.isExpired()) {
                it.remove();
                List<WebdavLock> pathLocks = locksByPath.get(lock.getPath());
                if (pathLocks != null) {
                    pathLocks.remove(lock);
                    if (pathLocks.isEmpty()) {
                        locksByPath.remove(lock.getPath());
                    }
                }
            }
        }
    }

    private interface PathVisitor {
        void visit(Path ancestor);
    }

    /**
     * Visits {@code path} and each of its ancestors up to the root.
     */
    private static void forEachAncestor(Path path, PathVisitor visitor) {
        Path current = path;
        while (current != null) {
            visitor.visit(current);
            current = current.getParent();
        }
    }
}
