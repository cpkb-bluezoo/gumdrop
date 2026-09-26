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
 * <p>Implements RFC 4918 §6 (locking) and §7 (write locks).
 *
 * <p>By default locks live in this server's memory, which is the right
 * authority for one server on a private content tree. Given a lock root
 * (see {@link SharedLockStore}) every lock is a file there instead, so that
 * servers sharing the tree also share the locks, and the maps are not
 * consulted at all: a conflict check that ignored another server's records
 * would grant two exclusive locks. In that mode every method does blocking
 * file I/O and belongs on a storage thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4918">RFC 4918</a>
 */
class WebDAVLockManager {

    private final Map<String, WebDAVLock> locksByToken = new ConcurrentHashMap<String, WebDAVLock>();
    private final Map<Path, List<WebDAVLock>> locksByPath = new ConcurrentHashMap<Path, List<WebDAVLock>>();
    private final SharedLockStore shared;

    /** Locks held in memory. */
    WebDAVLockManager() {
        this.shared = null;
    }

    /**
     * Locks kept as files under {@code lockRoot}, keyed by each resource's
     * path relative to {@code contentRoot}.
     *
     * @param contentRoot the content tree the locked resources are in
     * @param lockRoot the directory holding the lock records, or
     *        {@code null} to keep locks in memory
     */
    WebDAVLockManager(Path contentRoot, Path lockRoot) {
        this.shared = lockRoot == null ? null : new SharedLockStore(contentRoot, lockRoot);
    }

    /**
     * Acquires a lock on a resource (RFC 4918 §9.10).
     */
    synchronized WebDAVLock lock(Path path, WebDAVLock.Scope scope, 
                                  WebDAVLock.Type type, int depth,
                                  String owner, long timeoutSeconds) {
        if (shared != null) {
            return shared.lock(path, scope, type, depth, owner, timeoutSeconds);
        }
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

    /**
     * Releases the lock with this token that covers {@code path}
     * (RFC 4918 §9.11).
     */
    synchronized boolean unlock(Path path, String token) {
        if (shared != null) {
            return shared.unlock(path, token);
        }
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

        cleanExpiredLocks();
        return true;
    }

    /**
     * Refreshes a lock timeout (RFC 4918 §9.10.2).
     */
    synchronized WebDAVLock refresh(Path path, String token, long timeoutSeconds) {
        if (shared != null) {
            return shared.refresh(path, token, timeoutSeconds);
        }
        WebDAVLock lock = locksByToken.get(token);
        if (lock != null && !lock.isExpired()) {
            lock.refresh(timeoutSeconds);
            return lock;
        }
        return null;
    }

    WebDAVLock getLock(Path path, String token) {
        if (shared != null) {
            return shared.getLock(path, token);
        }
        WebDAVLock lock = locksByToken.get(token);
        if (lock != null && lock.isExpired()) {
            synchronized (this) {
                unlock(lock.getPath(), token);
            }
            return null;
        }
        return lock;
    }

    List<WebDAVLock> getLocks(Path path) {
        if (shared != null) {
            return shared.getLocksAt(path);
        }
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
        if (shared != null) {
            return shared.getCoveringLocks(path);
        }
        List<WebDAVLock> result = new ArrayList<WebDAVLock>();
        synchronized (this) {
            forEachAncestor(path, new PathVisitor() {
                @Override
                public void visit(Path ancestor) {
                    List<WebDAVLock> pathLocks = locksByPath.get(ancestor);
                    if (pathLocks == null) {
                        return;
                    }
                    for (WebDAVLock lock : pathLocks) {
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
        if (shared != null) {
            return shared.validateToken(path, token);
        }
        WebDAVLock lock = getLock(path, token);
        return lock != null && lock.covers(path);
    }

    /**
     * Checks for conflicting locks (RFC 4918 §6.1–6.2).
     */
    private boolean hasConflictingLock(Path path, WebDAVLock.Scope requestedScope) {
        final boolean[] conflictFound = new boolean[1];
        forEachAncestor(path, new PathVisitor() {
            @Override
            public void visit(Path ancestor) {
                if (conflictFound[0]) {
                    return;
                }
                List<WebDAVLock> pathLocks = locksByPath.get(ancestor);
                if (pathLocks == null) {
                    return;
                }
                for (WebDAVLock existing : pathLocks) {
                    if (existing.isExpired()) {
                        continue;
                    }
                    if (existing.covers(path)) {
                        if (existing.getScope() == WebDAVLock.Scope.EXCLUSIVE
                                || requestedScope == WebDAVLock.Scope.EXCLUSIVE) {
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

        for (Map.Entry<Path, List<WebDAVLock>> entry : locksByPath.entrySet()) {
            Path lockedPath = entry.getKey();
            if (!lockedPath.startsWith(path) || lockedPath.equals(path)) {
                continue;
            }
            for (WebDAVLock existing : entry.getValue()) {
                if (existing.isExpired()) {
                    continue;
                }
                if (existing.getScope() == WebDAVLock.Scope.EXCLUSIVE
                        || requestedScope == WebDAVLock.Scope.EXCLUSIVE) {
                    return true;
                }
            }
        }
        return false;
    }

    synchronized void cleanExpiredLocks() {
        if (shared != null) {
            // records are removed by the grant that finds them expired
            return;
        }
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
