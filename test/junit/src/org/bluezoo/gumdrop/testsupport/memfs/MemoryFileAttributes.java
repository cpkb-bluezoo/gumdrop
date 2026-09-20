/*
 * MemoryFileAttributes.java
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

package org.bluezoo.gumdrop.testsupport.memfs;

import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Snapshot of a {@link MemoryNode}'s attributes, plus the live attribute
 * view over a path.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MemoryFileAttributes implements PosixFileAttributes {

    /** The single owner and group reported for every node. */
    static final class Principal implements UserPrincipal, GroupPrincipal {
        @Override
        public String getName() {
            return "memfs";
        }

        @Override
        public String toString() {
            return getName();
        }
    }

    private static final Principal PRINCIPAL = new Principal();

    private final MemoryNode node;
    private final long size;
    private final long modified;
    private final long accessed;
    private final long created;
    private final Set<PosixFilePermission> permissions;

    MemoryFileAttributes(MemoryNode node) {
        this.node = node;
        this.size = node.directory ? 0 : node.size;
        this.modified = node.modified;
        this.accessed = node.accessed;
        this.created = node.created;
        this.permissions = new HashSet<PosixFilePermission>(node.permissions);
    }

    @Override
    public FileTime lastModifiedTime() {
        return FileTime.fromMillis(modified);
    }

    @Override
    public FileTime lastAccessTime() {
        return FileTime.fromMillis(accessed);
    }

    @Override
    public FileTime creationTime() {
        return FileTime.fromMillis(created);
    }

    @Override
    public boolean isRegularFile() {
        return !node.directory;
    }

    @Override
    public boolean isDirectory() {
        return node.directory;
    }

    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    @Override
    public boolean isOther() {
        return false;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public Object fileKey() {
        return Long.valueOf(node.id);
    }

    @Override
    public UserPrincipal owner() {
        return PRINCIPAL;
    }

    @Override
    public GroupPrincipal group() {
        return PRINCIPAL;
    }

    @Override
    public Set<PosixFilePermission> permissions() {
        return permissions;
    }

    /**
     * Live view: reads always reflect the current node, writes mutate it.
     */
    static final class View implements PosixFileAttributeView, BasicFileAttributeView {

        private final MemoryFileSystem fs;
        private final MemoryPath path;

        View(MemoryFileSystem fs, MemoryPath path) {
            this.fs = fs;
            this.path = path;
        }

        @Override
        public String name() {
            return "posix";
        }

        @Override
        public PosixFileAttributes readAttributes() throws IOException {
            synchronized (fs.lock) {
                return new MemoryFileAttributes(fs.provider.require(path));
            }
        }

        @Override
        public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime,
                FileTime createTime) throws IOException {
            synchronized (fs.lock) {
                MemoryNode node = fs.provider.require(path);
                if (lastModifiedTime != null) {
                    node.modified = lastModifiedTime.toMillis();
                }
                if (lastAccessTime != null) {
                    node.accessed = lastAccessTime.toMillis();
                }
                if (createTime != null) {
                    node.created = createTime.toMillis();
                }
            }
        }

        @Override
        public void setPermissions(Set<PosixFilePermission> perms) throws IOException {
            synchronized (fs.lock) {
                fs.provider.require(path).permissions =
                        new HashSet<PosixFilePermission>(perms);
            }
        }

        @Override
        public void setGroup(GroupPrincipal group) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserPrincipal getOwner() throws IOException {
            return PRINCIPAL;
        }

        @Override
        public void setOwner(UserPrincipal owner) throws IOException {
            throw new UnsupportedOperationException();
        }
    }
}
