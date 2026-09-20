/*
 * MemoryFileSystem.java
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
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * A purely in-memory {@link FileSystem} for unit tests. Production code
 * that works in terms of {@link Path} and {@link java.nio.file.Files} can be
 * pointed at a path from this file system, so program logic is exercised
 * without touching the disk.
 *
 * <p>Behaviour is deterministic: file times come from a logical clock that
 * advances by one millisecond per modification, never from the wall clock.
 * Supported: regular files and directories, {@link java.nio.channels.FileChannel},
 * directory streams, copy/move, and basic and POSIX attribute views.
 * Not supported: symbolic links, watch services, memory mapping, and
 * asynchronous file channels.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MemoryFileSystem extends FileSystem {

    private static final long CLOCK_START = 1000000000000L;

    final MemoryFileSystemProvider provider;
    final Object lock = new Object();
    final MemoryFileStore store = new MemoryFileStore(true);
    final MemoryFileStore plainStore = new MemoryFileStore(false);
    private volatile int maxTransfer = Integer.MAX_VALUE;
    private volatile int maxXattrValueSize = Integer.MAX_VALUE;
    private final java.util.List<Path> noUserAttributes =
            new java.util.concurrent.CopyOnWriteArrayList<Path>();
    final MemoryNode rootNode;
    final MemoryPath rootPath;
    private long clock = CLOCK_START;
    private long nextId = 1;
    private boolean open = true;

    private MemoryFileSystem() {
        this.provider = new MemoryFileSystemProvider(this);
        this.rootPath = new MemoryPath(this, true, new String[0]);
        this.rootNode = new MemoryNode(nextId(), true, clock);
    }

    /**
     * Creates a new, empty file system containing only the root directory.
     */
    public static MemoryFileSystem create() {
        return new MemoryFileSystem();
    }

    /**
     * Limits how many bytes a single read or write on a channel of this file
     * system moves, so that code which wrongly assumes a transfer is complete
     * can be tested deterministically. Real files may transfer fewer bytes
     * than asked, though most tests never see it.
     *
     * @param bytes the most bytes one read or write moves; must be positive
     */
    public void setMaxTransfer(int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("bytes must be positive");
        }
        this.maxTransfer = bytes;
    }

    /**
     * Limits the size of one extended attribute value, as file systems do
     * (a few kilobytes on some), so that code with a fallback for values that
     * do not fit can be tested.
     *
     * @param bytes the largest value accepted; must not be negative
     */
    public void setMaxXattrValueSize(int bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative");
        }
        this.maxXattrValueSize = bytes;
    }

    /**
     * Makes everything under {@code directory} refuse extended attributes,
     * as a mount whose file system has none does, while the rest of this file
     * system keeps them.
     *
     * @param directory the top of the tree without extended attributes
     */
    public void disableUserAttributesUnder(Path directory) {
        noUserAttributes.add(directory.toAbsolutePath().normalize());
    }

    boolean userAttributesDisabled(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path zone : noUserAttributes) {
            if (normalized.startsWith(zone)) {
                return true;
            }
        }
        return false;
    }

    int maxXattrValueSize() {
        return maxXattrValueSize;
    }

    int maxTransfer() {
        return maxTransfer;
    }

    /**
     * Returns the next logical timestamp. Caller must hold {@link #lock}.
     */
    long tick() {
        clock++;
        return clock;
    }

    long nextId() {
        return nextId++;
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() throws IOException {
        open = false;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return Collections.<Path>singletonList(rootPath);
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return Collections.<FileStore>emptyList();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        Set<String> views = new HashSet<String>();
        views.add("basic");
        views.add("posix");
        views.add("user");
        return views;
    }

    @Override
    public Path getPath(String first, String... more) {
        StringBuilder sb = new StringBuilder(first);
        for (int i = 0; i < more.length; i++) {
            if (more[i].isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(more[i]);
        }
        return MemoryPath.parse(this, sb.toString());
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        int colon = syntaxAndPattern.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Missing syntax: " + syntaxAndPattern);
        }
        String syntax = syntaxAndPattern.substring(0, colon);
        String pattern = syntaxAndPattern.substring(colon + 1);
        final Pattern regex;
        if ("glob".equalsIgnoreCase(syntax)) {
            regex = Pattern.compile(globToRegex(pattern));
        } else if ("regex".equalsIgnoreCase(syntax)) {
            regex = Pattern.compile(pattern);
        } else {
            throw new UnsupportedOperationException("Syntax: " + syntax);
        }
        return new PathMatcher() {
            @Override
            public boolean matches(Path path) {
                return regex.matcher(path.toString()).matches();
            }
        };
    }

    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        boolean inGroup = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++;
                    } else {
                        sb.append("[^/]*");
                    }
                    break;
                case '?':
                    sb.append("[^/]");
                    break;
                case '{':
                    sb.append("(?:");
                    inGroup = true;
                    break;
                case '}':
                    sb.append(')');
                    inGroup = false;
                    break;
                case ',':
                    sb.append(inGroup ? "|" : ",");
                    break;
                case '[':
                case ']':
                    sb.append(c);
                    break;
                default:
                    if ("\\.^$+()|".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() throws IOException {
        throw new UnsupportedOperationException();
    }
}
