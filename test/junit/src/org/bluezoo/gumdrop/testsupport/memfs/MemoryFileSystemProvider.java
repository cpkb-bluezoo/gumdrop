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
import java.nio.file.NotLinkException;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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

    /** The most symbolic links followed while resolving one path. */
    private static final int MAX_LINK_HOPS = 40;

    /**
     * Where a path leads: the directory that holds the final component, the
     * component's name, the node it names (null if there is none), and the
     * canonical names from the root to it.
     */
    private static final class Location {
        final MemoryNode parent;
        final String leaf;
        final MemoryNode node;
        final List<String> canonical;

        Location(MemoryNode parent, String leaf, MemoryNode node, List<String> canonical) {
            this.parent = parent;
            this.leaf = leaf;
            this.node = node;
            this.canonical = canonical;
        }
    }

    private MemoryPath absolute(Path path) {
        if (!(path instanceof MemoryPath) || path.getFileSystem() != fs) {
            throw new java.nio.file.ProviderMismatchException();
        }
        return (MemoryPath) path.toAbsolutePath();
    }

    /**
     * Walks {@code path} from the root, resolving {@code .}, {@code ..} and
     * symbolic links as a real file system does: {@code ..} steps back out
     * of the directory actually reached, which after a link is not the
     * lexical parent. The last component is followed only if
     * {@code followLast} is set.
     *
     * @throws NoSuchFileException if a directory on the way is missing
     * @throws FileSystemException if a component on the way is not a
     *         directory, or there are too many links
     */
    private Location locate(Path path, boolean followLast) throws IOException {
        MemoryPath abs = absolute(path);
        ArrayDeque<String> pending = new ArrayDeque<String>(Arrays.asList(abs.names()));
        ArrayList<MemoryNode> dirs = new ArrayList<MemoryNode>();
        ArrayList<String> names = new ArrayList<String>();
        dirs.add(fs.rootNode);
        int hops = 0;
        MemoryNode last = fs.rootNode;
        String lastLeaf = null;
        MemoryNode lastParent = null;
        while (!pending.isEmpty()) {
            String name = pending.pollFirst();
            boolean isLast = pending.isEmpty();
            if (name.equals(".")) {
                continue;
            }
            if (name.equals("..")) {
                if (dirs.size() > 1) {
                    dirs.remove(dirs.size() - 1);
                    names.remove(names.size() - 1);
                }
                continue;
            }
            MemoryNode dir = dirs.get(dirs.size() - 1);
            MemoryNode child = dir.children.get(name);
            if (child == null) {
                if (isLast) {
                    List<String> canonical = new ArrayList<String>(names);
                    canonical.add(name);
                    return new Location(dir, name, null, canonical);
                }
                throw new NoSuchFileException(path.toString());
            }
            if (child.isSymbolicLink() && (!isLast || followLast)) {
                hops++;
                if (hops > MAX_LINK_HOPS) {
                    throw new FileSystemException(path.toString(), null,
                            "Too many levels of symbolic links");
                }
                MemoryPath target = MemoryPath.parse(fs, child.linkTarget);
                if (target.isAbsolute()) {
                    while (dirs.size() > 1) {
                        dirs.remove(dirs.size() - 1);
                    }
                    names.clear();
                }
                String[] targetNames = target.names();
                for (int k = targetNames.length - 1; k >= 0; k--) {
                    pending.addFirst(targetNames[k]);
                }
                continue;
            }
            if (isLast) {
                List<String> canonical = new ArrayList<String>(names);
                canonical.add(name);
                return new Location(dir, name, child, canonical);
            }
            if (!child.directory) {
                throw new FileSystemException(path.toString(), null, "Not a directory");
            }
            dirs.add(child);
            names.add(name);
        }
        // The path ended on the root, ".", ".." or the target of a link
        // that ended that way: it names the directory now on top.
        last = dirs.get(dirs.size() - 1);
        if (dirs.size() > 1) {
            lastParent = dirs.get(dirs.size() - 2);
            lastLeaf = names.get(names.size() - 1);
        }
        return new Location(lastParent, lastLeaf, last, new ArrayList<String>(names));
    }

    /**
     * Returns the node at {@code path}, following links, or null if there is
     * none.
     */
    MemoryNode lookup(Path path) throws IOException {
        try {
            return locate(path, true).node;
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    MemoryNode require(Path path) throws IOException {
        return require(path, true);
    }

    /**
     * Returns the node at {@code path}; the last link is followed only if
     * {@code follow} is set.
     */
    MemoryNode require(Path path, boolean follow) throws IOException {
        MemoryNode node;
        try {
            node = locate(path, follow).node;
        } catch (NoSuchFileException e) {
            throw new NoSuchFileException(path.toString());
        }
        if (node == null) {
            throw new NoSuchFileException(path.toString());
        }
        return node;
    }

    /**
     * Returns the canonical path of an existing file.
     */
    Path realPath(MemoryPath path, LinkOption... options) throws IOException {
        boolean follow = follows(options);
        synchronized (fs.lock) {
            if (!follow) {
                Path lexical = path.toAbsolutePath().normalize();
                require(lexical, false);
                return lexical;
            }
            Location loc = locate(path, true);
            if (loc.node == null) {
                throw new NoSuchFileException(path.toString());
            }
            return new MemoryPath(fs, true, loc.canonical.toArray(new String[0]));
        }
    }

    private static boolean follows(LinkOption... options) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == LinkOption.NOFOLLOW_LINKS) {
                return false;
            }
        }
        return true;
    }

    private static boolean copyAttributes(CopyOption... options) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == StandardCopyOption.COPY_ATTRIBUTES) {
                return true;
            }
        }
        return false;
    }

    private static boolean follows(CopyOption... options) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == LinkOption.NOFOLLOW_LINKS) {
                return false;
            }
        }
        return true;
    }

    /**
     * Locates where a new entry named by {@code path} would go. The parent
     * must exist and be a directory, and the leaf must not be a special name.
     */
    private Location locateForCreate(Path path, boolean followLast) throws IOException {
        Location loc = locate(path, followLast);
        if (loc.parent == null || loc.leaf == null) {
            throw new FileAlreadyExistsException(path.toString());
        }
        return loc;
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
        boolean followLinks = !options.contains(LinkOption.NOFOLLOW_LINKS);
        synchronized (fs.lock) {
            if (createNew && write && locate(path, false).node != null) {
                // Exclusive creation does not follow a link: even a dangling
                // one is "already there".
                throw new FileAlreadyExistsException(path.toString());
            }
            Location loc = locate(path, followLinks);
            MemoryNode node = loc.node;
            if (node != null) {
                if (node.isSymbolicLink()) {
                    throw new FileSystemException(path.toString(), null,
                            "Too many levels of symbolic links");
                }
                if (createNew && write) {
                    throw new FileAlreadyExistsException(path.toString());
                }
                if (node.directory) {
                    throw new FileSystemException(path.toString(), null, "Is a directory");
                }
                if ((read && !node.permissions.contains(PosixFilePermission.OWNER_READ))
                        || (write && !node.permissions.contains(PosixFilePermission.OWNER_WRITE))) {
                    throw new AccessDeniedException(path.toString());
                }
                if (truncate && write) {
                    node.truncate(0);
                    node.modified = fs.tick();
                }
            } else {
                if (!write || !(create || createNew)) {
                    throw new NoSuchFileException(path.toString());
                }
                if (loc.parent == null) {
                    throw new NoSuchFileException(path.toString());
                }
                node = new MemoryNode(fs.nextId(), false, fs.tick());
                loc.parent.children.put(loc.leaf, node);
                loc.parent.modified = fs.tick();
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
            Location loc = locateForCreate(dir, false);
            if (loc.node != null) {
                throw new FileAlreadyExistsException(dir.toString());
            }
            loc.parent.children.put(loc.leaf, new MemoryNode(fs.nextId(), true, fs.tick()));
            loc.parent.modified = fs.tick();
        }
    }

    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs)
            throws IOException {
        if (!(target instanceof MemoryPath) || target.getFileSystem() != fs) {
            throw new java.nio.file.ProviderMismatchException();
        }
        synchronized (fs.lock) {
            Location loc = locateForCreate(link, false);
            if (loc.node != null) {
                throw new FileAlreadyExistsException(link.toString());
            }
            MemoryNode node = new MemoryNode(fs.nextId(), false, fs.tick());
            node.linkTarget = target.toString();
            node.permissions = PosixFilePermissions.fromString("rwxrwxrwx");
            loc.parent.children.put(loc.leaf, node);
            loc.parent.modified = fs.tick();
        }
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        synchronized (fs.lock) {
            MemoryNode node = require(link, false);
            if (!node.isSymbolicLink()) {
                throw new NotLinkException(link.toString());
            }
            return MemoryPath.parse(fs, node.linkTarget);
        }
    }

    @Override
    public void delete(Path path) throws IOException {
        synchronized (fs.lock) {
            Location loc = locate(path, false);
            if (loc.node == null) {
                throw new NoSuchFileException(path.toString());
            }
            if (loc.node == fs.rootNode || loc.parent == null) {
                throw new FileSystemException(path.toString(), null, "Cannot delete root");
            }
            if (loc.node.directory && !loc.node.children.isEmpty()) {
                throw new DirectoryNotEmptyException(path.toString());
            }
            loc.parent.children.remove(loc.leaf);
            loc.parent.modified = fs.tick();
        }
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        boolean followSource = follows(options);
        synchronized (fs.lock) {
            Location src = locate(source, followSource);
            if (src.node == null) {
                throw new NoSuchFileException(source.toString());
            }
            Location dst = prepareTarget(target, options);
            MemoryNode copy = new MemoryNode(fs.nextId(), src.node.directory, fs.tick());
            if (src.node.isSymbolicLink()) {
                copy.linkTarget = src.node.linkTarget;
            } else if (!src.node.directory) {
                copy.ensureCapacity(src.node.size);
                System.arraycopy(src.node.data, 0, copy.data, 0, src.node.size);
                copy.size = src.node.size;
            }
            copy.permissions = new HashSet<PosixFilePermission>(src.node.permissions);
            if (copyAttributes(options)) {
                // Timestamps and extended attributes travel only when asked
                // for, as with a real file system.
                copy.modified = src.node.modified;
                copy.accessed = src.node.accessed;
                copy.created = src.node.created;
                copy.xattrs.putAll(src.node.xattrs);
            }
            dst.parent.children.put(dst.leaf, copy);
            dst.parent.modified = fs.tick();
        }
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        synchronized (fs.lock) {
            Location src = locate(source, false);
            if (src.node == null) {
                throw new NoSuchFileException(source.toString());
            }
            if (src.parent == null) {
                throw new FileSystemException(source.toString(), null, "Cannot move root");
            }
            Location existing = locate(target, false);
            if (existing.node == src.node) {
                return;
            }
            MemoryPath srcLexical = (MemoryPath) source.toAbsolutePath().normalize();
            MemoryPath dstLexical = (MemoryPath) target.toAbsolutePath().normalize();
            if (dstLexical.startsWith(srcLexical)) {
                throw new FileSystemException(source.toString(), target.toString(),
                        "Cannot move a directory into itself");
            }
            Location dst = prepareTarget(target, options);
            src.parent.children.remove(src.leaf);
            dst.parent.children.put(dst.leaf, src.node);
            src.parent.modified = fs.tick();
            dst.parent.modified = fs.tick();
            src.node.modified = fs.tick();
        }
    }

    /**
     * Validates the target of a copy or move, removes an existing entry when
     * REPLACE_EXISTING was requested, and returns where the new entry goes.
     * An existing link is itself the target; it is not followed.
     */
    private Location prepareTarget(Path target, CopyOption[] options) throws IOException {
        boolean replace = false;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == StandardCopyOption.REPLACE_EXISTING) {
                replace = true;
            }
        }
        Location dst = locateForCreate(target, false);
        if (dst.node == null) {
            return dst;
        }
        if (!replace) {
            throw new FileAlreadyExistsException(target.toString());
        }
        if (dst.node.directory && !dst.node.children.isEmpty()) {
            throw new DirectoryNotEmptyException(target.toString());
        }
        dst.parent.children.remove(dst.leaf);
        return dst;
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
        synchronized (fs.lock) {
            require(path);
        }
        return fs.userAttributesDisabled(path) ? fs.plainStore : fs.store;
    }

    // Attributes

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        synchronized (fs.lock) {
            MemoryNode node = require(path, true);
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
            return (V) new MemoryFileAttributes.View(fs, absolute(path), follows(options));
        }
        if (type == UserDefinedFileAttributeView.class) {
            return (V) new MemoryUserAttributeView(fs, absolute(path), follows(options));
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
            return (A) new MemoryFileAttributes(require(path, follows(options)));
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
            a = new MemoryFileAttributes(require(path, follows(options)));
        }
        Map<String, Object> all = new HashMap<String, Object>();
        all.put("size", Long.valueOf(a.size()));
        all.put("lastModifiedTime", a.lastModifiedTime());
        all.put("lastAccessTime", a.lastAccessTime());
        all.put("creationTime", a.creationTime());
        all.put("isDirectory", Boolean.valueOf(a.isDirectory()));
        all.put("isRegularFile", Boolean.valueOf(a.isRegularFile()));
        all.put("isSymbolicLink", Boolean.valueOf(a.isSymbolicLink()));
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
                getFileAttributeView(path, PosixFileAttributeView.class, options);
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
