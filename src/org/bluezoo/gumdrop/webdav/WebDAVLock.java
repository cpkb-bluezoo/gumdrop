/*
 * WebDAVLock.java
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
import java.util.UUID;

/**
 * Represents a WebDAV lock on a resource.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class WebDAVLock {

    enum Scope { EXCLUSIVE, SHARED }
    enum Type { WRITE }

    private final String token;
    private final Path path;
    private final Scope scope;
    private final Type type;
    private final int depth;
    private final String owner;
    private final long createdAt;
    private long expiresAt;

    WebDAVLock(Path path, Scope scope, Type type, int depth,
               String owner, long timeoutSeconds) {
        this.token = DAVConstants.LOCK_TOKEN_SCHEME + UUID.randomUUID().toString();
        this.path = path;
        this.scope = scope;
        this.type = type;
        this.depth = depth;
        this.owner = owner;
        this.createdAt = System.currentTimeMillis();
        this.expiresAt = timeoutSeconds < 0 ? Long.MAX_VALUE 
                : createdAt + (timeoutSeconds * 1000);
    }

    String getToken() {
        return token;
    }

    Path getPath() {
        return path;
    }

    Scope getScope() {
        return scope;
    }

    Type getType() {
        return type;
    }

    int getDepth() {
        return depth;
    }

    String getOwner() {
        return owner;
    }

    boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    long getRemainingTimeoutSeconds() {
        if (expiresAt == Long.MAX_VALUE) {
            return -1;
        }
        long remaining = (expiresAt - System.currentTimeMillis()) / 1000;
        return remaining > 0 ? remaining : 0;
    }

    void refresh(long timeoutSeconds) {
        if (timeoutSeconds < 0) {
            this.expiresAt = Long.MAX_VALUE;
        } else {
            this.expiresAt = System.currentTimeMillis() + (timeoutSeconds * 1000);
        }
    }

    boolean covers(Path targetPath) {
        if (path.equals(targetPath)) {
            return true;
        }
        if (depth == DAVConstants.DEPTH_INFINITY) {
            return targetPath.startsWith(path);
        }
        if (depth == DAVConstants.DEPTH_1) {
            Path parent = targetPath.getParent();
            return parent != null && parent.equals(path);
        }
        return false;
    }

    @Override
    public String toString() {
        return "WebDAVLock{token=" + token + ", path=" + path + 
               ", scope=" + scope + ", depth=" + depth + "}";
    }
}
