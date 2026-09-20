/*
 * MemoryFileSystemProvider.java
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

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provider backing exactly one {@link MemoryFileSystem}. It is never
 * registered with the JDK; tests obtain paths from
 * {@link MemoryFileSystem#getPath(String, String...)}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MemoryFileSystemProvider extends FileSystemProvider {

    private final MemoryFileSystem fs;

    MemoryFileSystemProvider(MemoryFileSystem fs) {
        this.fs = fs;
    }

    @Override
    public String getScheme() {
        return "memfs";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Path getPath(URI uri) {
        throw new UnsupportedOperationException();
    }

    // Node resolution. Callers must hold fs.lock.

    private MemoryPath resolved(Path path) {
        if (!(path instanceof MemoryPath) || path.getFileSystem() != fs) {
            throw new java.nio.file.ProviderMismatchException();
        }
        return (MemoryPath) path.toAbsolutePath().normalize();
    }

    /**
     * Returns the node at {@code path}, or null if any component is missing.
     */
    MemoryNode lookup(MemoryPath path) {
        MemoryPath abs = resolved(path);
        MemoryNode node = fs.rootNode;
        String[] names = abs.names();
        for (int i = 0; i < names.length; i++) {
            if (!node.directory) {
                return null;
            }
            node = node.children.get(names[i]);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    MemoryNode require(Path path) throws IOException {
        MemoryNode node = lookup((MemoryPath) resolved(path));
        if (node == null) {
            throw new NoSuchFileException(path.toString());
        }
        return node;
    }

    private MemoryNode requireParentDirectory(Path path) throws IOException {
        MemoryPath abs = resolved(path);
        Path parent = abs.getParent();
        if (parent == null) {
            throw new FileAlreadyExistsException(path.toString());
        }
        MemoryNode node = lookup((MemoryPath) parent);
        if (node == null) {
            throw new NoSuchFileException(path.toString());
        }
        if (!node.directory) {
            throw new NotDirectoryException(parent.toString());
        }
        return node;
    }

    private static String leafName(MemoryPath abs) {
        String[] names = abs.names();
        return names[names.length - 1];
    }

    // Channels

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
            FileAttribute<?>... attrs) throws IOException {
        return newFileChannel(path, options, attrs);
    }

    @Override
    public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options,
            FileAttribute<?>... attrs) throws IOException {
        boolean append = options.contains(StandardOpenOption.APPEND);
        boolean write = options.contains(StandardOpenOption.WRITE) || append;
        boolean read = options.contains(StandardOpenOption.READ) || !write;
        if (read && append) {
            throw new IllegalArgumentException("READ + APPEND not allowed");
        }
        boolean create = options.contains(StandardOpenOption.CREATE);
        boolean createNew = options.contains(StandardOpenOption.CREATE_NEW);
        boolean truncate = options.contains(StandardOpenOption.TRUNCATE_EXISTING);
        if (options.contains(StandardOpenOption.DELETE_ON_CLOSE)) {
            throw new UnsupportedOperationException("DELETE_ON_CLOSE");
        }
        synchronized (fs.lock) {
            MemoryPath abs = resolved(path);
            MemoryNode node = lookup(abs);
            if (node != null) {
                if (createNew && write) {
                    throw new FileAlreadyExistsException(path.toString());
                }
                if (node.directory) {
                    throw new FileSystemException(path.toString(), null, "Is a directory");
                }
                if (truncate && write) {
                    node.truncate(0);
                    node.modified = fs.tick();
                }
            } else {
                if (!write || !(create || createNew)) {
                    throw new NoSuchFileException(path.toString());
                }
                MemoryNode parent = requireParentDirectory(abs);
                node = new MemoryNode(fs.nextId(), false, fs.tick());
                parent.children.put(leafName(abs), node);
                parent.modified = fs.tick();
            }
            return new MemoryFileChannel(fs, node, read, write, append);
        }
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir,
            DirectoryStream.Filter<? super Path> filter) throws IOException {
        List<Path> entries = new ArrayList<Path>();
        synchronized (fs.lock) {
            MemoryNode node = require(dir);
            if (!node.directory) {
                throw new NotDirectoryException(dir.toString());
            }
            for (String name : node.children.keySet()) {
                Path child = dir.resolve(name);
                if (filter == null || filter.accept(child)) {
                    entries.add(child);
                }
            }
        }
        final Iterator<Path> it = entries.iterator();
        return new DirectoryStream<Path>() {
            private boolean iterated;

            @Override
            public Iterator<Path> iterator() {
                if (iterated) {
                    throw new IllegalStateException("Iterator already obtained");
                }
                iterated = true;
                return it;
            }

            @Override
            public void close() {
            }
        };
    }

    // Structure

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        synchronized (fs.lock) {
            MemoryPath abs = resolved(dir);
            if (lookup(abs) != null) {
                throw new FileAlreadyExistsException(dir.toString());
            }
            MemoryNode parent = requireParentDirectory(abs);
            parent.children.put(leafName(abs), new MemoryNode(fs.nextId(), true, fs.tick()));
            parent.modified = fs.tick();
        }
    }

    @Override
    public void delete(Path path) throws IOException {
        synchronized (fs.lock) {
            MemoryPath abs = resolved(path);
            MemoryNode node = require(abs);
            if (node == fs.rootNode) {
                throw new FileSystemException(path.toString(), null, "Cannot delete root");
            }
            if (node.directory && !node.children.isEmpty()) {
                throw new DirectoryNotEmptyException(path.toString());
            }
            MemoryNode parent = requireParentDirectory(abs);
            parent.children.remove(leafName(abs));
            parent.modified = fs.tick();
        }
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        synchronized (fs.lock) {
            MemoryNode src = require(source);
            MemoryPath dst = resolved(target);
            prepareTarget(dst, target, options);
            MemoryNode copy = new MemoryNode(fs.nextId(), src.directory, fs.tick());
            if (!src.directory) {
                copy.ensureCapacity(src.size);
                System.arraycopy(src.data, 0, copy.data, 0, src.size);
                copy.size = src.size;
            }
            copy.permissions = new HashSet<PosixFilePermission>(src.permissions);
            requireParentDirectory(dst).children.put(leafName(dst), copy);
        }
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        synchronized (fs.lock) {
            MemoryPath src = resolved(source);
            MemoryNode node = require(src);
            MemoryPath dst = resolved(target);
            if (src.equals(dst)) {
                return;
            }
            if (dst.startsWith(src)) {
                throw new FileSystemException(source.toString(), target.toString(),
                        "Cannot move a directory into itself");
            }
            prepareTarget(dst, target, options);
            MemoryNode dstParent = requireParentDirectory(dst);
            requireParentDirectory(src).children.remove(leafName(src));
            dstParent.children.put(leafName(dst), node);
            node.modified = fs.tick();
        }
    }

    /**
     * Validates the target of a copy or move and removes an existing entry
     * when REPLACE_EXISTING was requested.
     */
    private void prepareTarget(MemoryPath dst, Path target, CopyOption[] options)
            throws IOException {
        boolean replace = false;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == StandardCopyOption.REPLACE_EXISTING) {
                replace = true;
            }
        }
        MemoryNode existing = lookup(dst);
        if (existing == null) {
            requireParentDirectory(dst);
            return;
        }
        if (!replace) {
            throw new FileAlreadyExistsException(target.toString());
        }
        if (existing.directory && !existing.children.isEmpty()) {
            throw new DirectoryNotEmptyException(target.toString());
        }
        requireParentDirectory(dst).children.remove(leafName(dst));
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
        synchronized (fs.lock) {
            return require(path) == require(path2);
        }
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
        Path name = path.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        throw new UnsupportedOperationException();
    }

    // Attributes

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        synchronized (fs.lock) {
            MemoryNode node = require(path);
            for (int i = 0; i < modes.length; i++) {
                PosixFilePermission needed;
                switch (modes[i]) {
                    case READ:
                        needed = PosixFilePermission.OWNER_READ;
                        break;
                    case WRITE:
                        needed = PosixFilePermission.OWNER_WRITE;
                        break;
                    default:
                        needed = PosixFilePermission.OWNER_EXECUTE;
                        break;
                }
                if (!node.permissions.contains(needed)) {
                    throw new AccessDeniedException(path.toString());
                }
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
            LinkOption... options) {
        if (type == BasicFileAttributeView.class || type == PosixFileAttributeView.class
                || type == FileOwnerAttributeView.class) {
            return (V) new MemoryFileAttributes.View(fs, resolved(path));
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
            LinkOption... options) throws IOException {
        if (type != BasicFileAttributes.class && type != PosixFileAttributes.class) {
            throw new UnsupportedOperationException(type.getName());
        }
        synchronized (fs.lock) {
            return (A) new MemoryFileAttributes(require(path));
        }
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes,
            LinkOption... options) throws IOException {
        String view = "basic";
        String names = attributes;
        int colon = attributes.indexOf(':');
        if (colon >= 0) {
            view = attributes.substring(0, colon);
            names = attributes.substring(colon + 1);
        }
        if (!view.equals("basic") && !view.equals("posix")) {
            throw new UnsupportedOperationException(view);
        }
        MemoryFileAttributes a;
        synchronized (fs.lock) {
            a = new MemoryFileAttributes(require(path));
        }
        Map<String, Object> all = new HashMap<String, Object>();
        all.put("size", Long.valueOf(a.size()));
        all.put("lastModifiedTime", a.lastModifiedTime());
        all.put("lastAccessTime", a.lastAccessTime());
        all.put("creationTime", a.creationTime());
        all.put("isDirectory", Boolean.valueOf(a.isDirectory()));
        all.put("isRegularFile", Boolean.valueOf(a.isRegularFile()));
        all.put("isSymbolicLink", Boolean.FALSE);
        all.put("isOther", Boolean.FALSE);
        all.put("fileKey", a.fileKey());
        if (view.equals("posix")) {
            all.put("permissions", a.permissions());
            all.put("owner", a.owner());
            all.put("group", a.group());
        }
        if (names.equals("*")) {
            return all;
        }
        Map<String, Object> selected = new HashMap<String, Object>();
        for (String name : names.split(",")) {
            if (!all.containsKey(name)) {
                throw new IllegalArgumentException("'" + name + "' not recognized");
            }
            selected.put(name, all.get(name));
        }
        return selected;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setAttribute(Path path, String attribute, Object value,
            LinkOption... options) throws IOException {
        String name = attribute.substring(attribute.indexOf(':') + 1);
        PosixFileAttributeView view =
                getFileAttributeView(path, PosixFileAttributeView.class);
        if (name.equals("lastModifiedTime")) {
            view.setTimes((FileTime) value, null, null);
        } else if (name.equals("lastAccessTime")) {
            view.setTimes(null, (FileTime) value, null);
        } else if (name.equals("creationTime")) {
            view.setTimes(null, null, (FileTime) value);
        } else if (name.equals("permissions")) {
            view.setPermissions((Set<PosixFilePermission>) value);
        } else {
            throw new UnsupportedOperationException(attribute);
        }
    }
}
