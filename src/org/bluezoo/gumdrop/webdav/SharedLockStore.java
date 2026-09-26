/*
 * SharedLockStore.java
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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * WebDAV lock records kept in a directory that several servers can share.
 *
 * <p>Each lock is a file under the lock root at the resource's path
 * <em>relative to the content root</em>, so servers that mount the same
 * tree at different absolute paths agree on where a lock is. For a resource
 * the record directory holds {@code exclusive} (the exclusive lock) and
 * {@code shared/<token>} (one file per shared lock). Each component of the
 * relative path is prefixed {@code _} in the record tree, so a resource
 * that is itself named {@code exclusive} or {@code shared} cannot be
 * confused with a record.
 *
 * <p>A lock is granted by creating its record with {@code CREATE_NEW}, then
 * checking the ancestors' records for a lock that covers the path and the
 * descendants' records for one this lock would cover. Any conflict, including
 * a record another server created at the same moment, removes the new record
 * and refuses the lock; if two servers each see the other, both refuse and
 * the clients retry.
 *
 * <p>This needs a file system where {@code CREATE_NEW} is atomic and a
 * record one server writes is visible to the others before they finish their
 * conflict walk: a local disk or a typical ReadWriteOnce volume. Where that
 * does not hold (NFS attribute caching, for one) two grants can both
 * succeed, so use a coherent file system or run one replica.
 *
 * <p>All methods do blocking file I/O and belong on a storage thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class SharedLockStore {

    private static final String EXCLUSIVE = "exclusive";
    private static final String SHARED = "shared";
    private static final String TEMP_PREFIX = ".tmp-";
    private static final String COMPONENT_PREFIX = "_";

    /** A record that cannot be read for this long was abandoned mid-write. */
    private static final long ABANDONED_RECORD_MS = 10000L;

    /** The content root with symbolic links resolved: the form request paths take once bound. */
    private final Path contentRoot;
    private final Path configuredRoot;
    private final Path lockRoot;

    SharedLockStore(Path contentRoot, Path lockRoot) {
        this.configuredRoot = contentRoot;
        this.contentRoot = canonicalRoot(contentRoot);
        this.lockRoot = lockRoot;
    }

    private static Path canonicalRoot(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException e) {
            return root.toAbsolutePath().normalize();
        }
    }

    /**
     * Returns {@code path} under the resolved content root. A resource path
     * may be given as the root was configured or already resolved.
     */
    private Path canon(Path path) {
        if (path.startsWith(contentRoot)) {
            return path;
        }
        if (path.startsWith(configuredRoot)) {
            return contentRoot.resolve(configuredRoot.relativize(path).toString());
        }
        throw new IllegalArgumentException("not under the content root: " + path);
    }

    /**
     * Grants a lock, or returns {@code null} if it conflicts with another.
     */
    WebDAVLock lock(Path resource, WebDAVLock.Scope scope, WebDAVLock.Type type, int depth,
            String owner, long timeoutSeconds) {
        Path path = canon(resource);
        WebDAVLock lock = new WebDAVLock(path, scope, type, depth, owner, timeoutSeconds);
        try {
            Path dir = recordDirectory(path);
            Path record;
            if (scope == WebDAVLock.Scope.EXCLUSIVE) {
                Files.createDirectories(dir);
                record = dir.resolve(EXCLUSIVE);
            } else {
                Path sharedDir = dir.resolve(SHARED);
                Files.createDirectories(sharedDir);
                record = sharedDir.resolve(tokenFileName(lock.getToken()));
            }
            if (!createRecord(record, lock)) {
                return null;
            }
            if (conflicts(path, scope, record)) {
                Files.deleteIfExists(record);
                return null;
            }
            return lock;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Removes the lock with this token that covers {@code path}.
     */
    boolean unlock(Path resource, String token) {
        Path path = canon(resource);
        try {
            Found found = find(path, token);
            if (found == null || found.lock.isExpired()) {
                return false;
            }
            return Files.deleteIfExists(found.record);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Extends the lock with this token, or returns {@code null} if there is
     * no live lock of that token covering {@code path}.
     */
    WebDAVLock refresh(Path resource, String token, long timeoutSeconds) {
        Path path = canon(resource);
        try {
            Found found = find(path, token);
            if (found == null || found.lock.isExpired()) {
                return null;
            }
            found.lock.refresh(timeoutSeconds);
            Path temp = found.record.resolveSibling(TEMP_PREFIX + UUID.randomUUID());
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW)) {
                write(found.lock, out);
            }
            Files.move(temp, found.record, StandardCopyOption.REPLACE_EXISTING);
            return found.lock;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the live lock with this token covering {@code path}, or null. */
    WebDAVLock getLock(Path resource, String token) {
        Path path = canon(resource);
        try {
            Found found = find(path, token);
            return found != null && !found.lock.isExpired() ? found.lock : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether a live lock with this token covers {@code path}. */
    boolean validateToken(Path resource, String token) {
        return getLock(resource, token) != null;
    }

    /** Returns the live locks placed on {@code path} itself. */
    List<WebDAVLock> getLocksAt(Path resource) {
        Path path = canon(resource);
        List<WebDAVLock> result = new ArrayList<WebDAVLock>();
        for (WebDAVLock lock : getCoveringLocks(path)) {
            if (lock.getPath().equals(path)) {
                result.add(lock);
            }
        }
        return result;
    }

    /** Returns the live locks that cover {@code path}. */
    List<WebDAVLock> getCoveringLocks(Path resource) {
        Path path = canon(resource);
        List<WebDAVLock> result = new ArrayList<WebDAVLock>();
        try {
            for (Path ancestor : ancestors(path)) {
                for (Path record : records(recordDirectory(ancestor))) {
                    WebDAVLock lock = read(record);
                    if (lock != null && !lock.isExpired() && lock.covers(path)) {
                        result.add(lock);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    // -- Records --

    /**
     * Creates {@code record} for {@code lock}; false if it is already held
     * by a live lock. An expired or abandoned record is replaced.
     */
    private boolean createRecord(Path record, WebDAVLock lock) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (OutputStream out = Files.newOutputStream(record, StandardOpenOption.CREATE_NEW)) {
                write(lock, out);
                return true;
            } catch (FileAlreadyExistsException e) {
                WebDAVLock existing = read(record);
                if (existing != null && !existing.isExpired()) {
                    return false;
                }
                if (existing == null && !abandoned(record)) {
                    // another server is still writing it
                    return false;
                }
                Files.deleteIfExists(record);
            }
        }
        return false;
    }

    private boolean conflicts(Path path, WebDAVLock.Scope scope, Path own) throws IOException {
        for (Path ancestor : ancestors(path)) {
            for (Path record : records(recordDirectory(ancestor))) {
                if (record.equals(own)) {
                    continue;
                }
                WebDAVLock existing = read(record);
                if (existing == null) {
                    if (!Files.exists(record)) {
                        continue; // removed since it was listed
                    }
                    if (abandoned(record)) {
                        Files.deleteIfExists(record);
                        continue;
                    }
                    return true;
                }
                if (existing.isExpired()) {
                    Files.deleteIfExists(record);
                    continue;
                }
                if (existing.covers(path) && clash(existing.getScope(), scope)) {
                    return true;
                }
            }
        }
        return descendantConflict(path, scope, own);
    }

    private static boolean clash(WebDAVLock.Scope existing, WebDAVLock.Scope requested) {
        return existing == WebDAVLock.Scope.EXCLUSIVE || requested == WebDAVLock.Scope.EXCLUSIVE;
    }

    /** Whether any lock below {@code path} would clash with a lock on it. */
    private boolean descendantConflict(final Path path, final WebDAVLock.Scope scope, final Path own)
            throws IOException {
        final Path base = recordDirectory(path);
        if (!Files.isDirectory(base)) {
            return false;
        }
        final boolean[] found = new boolean[1];
        Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (isRecordName(file) && !file.equals(own) && !isAtOrBelowOwn(file, base)) {
                    WebDAVLock existing = read(file);
                    if (existing == null) {
                        if (!Files.exists(file)) {
                            // removed since it was listed
                        } else if (abandoned(file)) {
                            Files.deleteIfExists(file);
                        } else {
                            found[0] = true;
                        }
                    } else if (existing.isExpired()) {
                        Files.deleteIfExists(file);
                    } else if (clash(existing.getScope(), scope)) {
                        found[0] = true;
                    }
                }
                return found[0] ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // another server removed it (a refused grant rolling back)
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0];
    }

    /** Whether {@code file} is one of the records of the resource whose directory is {@code base}. */
    private static boolean isAtOrBelowOwn(Path file, Path base) {
        Path parent = file.getParent();
        if (parent == null) {
            return false;
        }
        if (parent.equals(base)) {
            return true;
        }
        Path grand = parent.getParent();
        return grand != null && grand.equals(base) && SHARED.equals(parent.getFileName().toString());
    }

    private static boolean isRecordName(Path file) {
        Path name = file.getFileName();
        if (name == null || name.toString().startsWith(TEMP_PREFIX)) {
            return false;
        }
        if (EXCLUSIVE.equals(name.toString())) {
            return true;
        }
        Path parent = file.getParent();
        return parent != null && SHARED.equals(parent.getFileName().toString());
    }

    private static boolean abandoned(Path record) {
        try {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(record).toMillis();
            return age > ABANDONED_RECORD_MS;
        } catch (IOException e) {
            return true;
        }
    }

    private static final class Found {
        final Path record;
        final WebDAVLock lock;

        Found(Path record, WebDAVLock lock) {
            this.record = record;
            this.lock = lock;
        }
    }

    /** Finds the record of the lock with this token that covers {@code path}. */
    private Found find(Path path, String token) throws IOException {
        String name = tokenFileName(token);
        for (Path ancestor : ancestors(path)) {
            Path dir = recordDirectory(ancestor);
            Path shared = dir.resolve(SHARED).resolve(name);
            WebDAVLock lock = read(shared);
            if (lock != null && token.equals(lock.getToken()) && lock.covers(path)) {
                return new Found(shared, lock);
            }
            Path exclusive = dir.resolve(EXCLUSIVE);
            lock = read(exclusive);
            if (lock != null && token.equals(lock.getToken()) && lock.covers(path)) {
                return new Found(exclusive, lock);
            }
        }
        return null;
    }

    /** The records (exclusive and shared) of one resource. */
    private static List<Path> records(Path dir) throws IOException {
        List<Path> result = new ArrayList<Path>();
        Path exclusive = dir.resolve(EXCLUSIVE);
        if (Files.isRegularFile(exclusive)) {
            result.add(exclusive);
        }
        Path shared = dir.resolve(SHARED);
        if (Files.isDirectory(shared)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(shared)) {
                for (Path file : stream) {
                    if (!file.getFileName().toString().startsWith(TEMP_PREFIX)) {
                        result.add(file);
                    }
                }
            }
        }
        return result;
    }

    // -- Keys --

    /** {@code path} and each of its ancestors up to the content root, nearest first. */
    private List<Path> ancestors(Path path) {
        List<Path> result = new ArrayList<Path>();
        Path current = path;
        while (current != null && current.startsWith(contentRoot)) {
            result.add(current);
            if (current.equals(contentRoot)) {
                break;
            }
            current = current.getParent();
        }
        return result;
    }

    private Path recordDirectory(Path resource) {
        Path dir = lockRoot;
        Path relative = contentRoot.relativize(resource);
        for (Path name : relative) {
            String component = name.toString();
            if (!component.isEmpty()) {
                dir = dir.resolve(COMPONENT_PREFIX + component);
            }
        }
        return dir;
    }

    private static String tokenFileName(String token) {
        int colon = token.lastIndexOf(':');
        String name = colon >= 0 ? token.substring(colon + 1) : token;
        return name.replace('/', '_').replace('\\', '_');
    }

    // -- Serialisation --

    private void write(WebDAVLock lock, OutputStream out) throws IOException {
        Properties props = new Properties();
        props.setProperty("token", lock.getToken());
        props.setProperty("path", relativeString(lock.getPath()));
        props.setProperty("scope", lock.getScope().name());
        props.setProperty("type", lock.getType().name());
        props.setProperty("depth", String.valueOf(lock.getDepth()));
        if (lock.getOwner() != null) {
            props.setProperty("owner", lock.getOwner());
        }
        props.setProperty("created", String.valueOf(lock.getCreatedAt()));
        props.setProperty("expires", String.valueOf(lock.getExpiresAt()));
        props.store(out, null);
    }

    private String relativeString(Path resource) {
        Path relative = contentRoot.relativize(resource);
        StringBuilder sb = new StringBuilder();
        for (Path name : relative) {
            if (name.toString().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(name.toString());
        }
        return sb.toString();
    }

    /** Reads a record, or returns null if it is missing, still being written or unreadable. */
    private WebDAVLock read(Path record) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(record)) {
            props.load(in);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
        try {
            String token = props.getProperty("token");
            String relative = props.getProperty("path");
            if (token == null || relative == null) {
                return null;
            }
            Path resource = relative.isEmpty() ? contentRoot : contentRoot.resolve(relative);
            return new WebDAVLock(token, resource,
                    WebDAVLock.Scope.valueOf(props.getProperty("scope")),
                    WebDAVLock.Type.valueOf(props.getProperty("type")),
                    Integer.parseInt(props.getProperty("depth")),
                    props.getProperty("owner"),
                    Long.parseLong(props.getProperty("created")),
                    Long.parseLong(props.getProperty("expires")));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
