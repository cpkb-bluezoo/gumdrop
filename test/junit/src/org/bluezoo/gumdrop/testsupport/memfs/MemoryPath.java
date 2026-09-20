/*
 * MemoryPath.java
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
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Path in a {@link MemoryFileSystem}. The separator is {@code /}; there are
 * no symbolic links, so {@code ..} is resolved lexically by
 * {@link #normalize()}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MemoryPath implements Path {

    private final MemoryFileSystem fs;
    private final boolean absolute;
    private final String[] names;

    MemoryPath(MemoryFileSystem fs, boolean absolute, String[] names) {
        this.fs = fs;
        this.absolute = absolute;
        this.names = names;
    }

    static MemoryPath parse(MemoryFileSystem fs, String text) {
        boolean absolute = text.startsWith("/");
        List<String> parts = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '/') {
                if (i > start) {
                    parts.add(text.substring(start, i));
                }
                start = i + 1;
            }
        }
        return new MemoryPath(fs, absolute, parts.toArray(new String[0]));
    }

    String[] names() {
        return names;
    }

    private static MemoryPath cast(Path p) {
        if (!(p instanceof MemoryPath)) {
            throw new java.nio.file.ProviderMismatchException();
        }
        return (MemoryPath) p;
    }

    @Override
    public FileSystem getFileSystem() {
        return fs;
    }

    @Override
    public boolean isAbsolute() {
        return absolute;
    }

    @Override
    public Path getRoot() {
        return absolute ? fs.rootPath : null;
    }

    @Override
    public Path getFileName() {
        if (names.length == 0) {
            return null;
        }
        return new MemoryPath(fs, false, new String[] { names[names.length - 1] });
    }

    @Override
    public Path getParent() {
        if (names.length == 0) {
            return null;
        }
        if (names.length == 1) {
            return absolute ? fs.rootPath : null;
        }
        return new MemoryPath(fs, absolute, Arrays.copyOf(names, names.length - 1));
    }

    @Override
    public int getNameCount() {
        return names.length;
    }

    @Override
    public Path getName(int index) {
        if (index < 0 || index >= names.length) {
            throw new IllegalArgumentException();
        }
        return new MemoryPath(fs, false, new String[] { names[index] });
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        if (beginIndex < 0 || endIndex > names.length || beginIndex >= endIndex) {
            throw new IllegalArgumentException();
        }
        return new MemoryPath(fs, false, Arrays.copyOfRange(names, beginIndex, endIndex));
    }

    @Override
    public boolean startsWith(Path other) {
        if (!(other instanceof MemoryPath)) {
            return false;
        }
        MemoryPath o = (MemoryPath) other;
        if (o.absolute != absolute || o.names.length > names.length) {
            return false;
        }
        for (int i = 0; i < o.names.length; i++) {
            if (!o.names[i].equals(names[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean endsWith(Path other) {
        if (!(other instanceof MemoryPath)) {
            return false;
        }
        MemoryPath o = (MemoryPath) other;
        if (o.absolute) {
            return equals(o);
        }
        if (o.names.length > names.length) {
            return false;
        }
        int offset = names.length - o.names.length;
        for (int i = 0; i < o.names.length; i++) {
            if (!o.names[i].equals(names[offset + i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Path normalize() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < names.length; i++) {
            String n = names[i];
            if (n.equals(".")) {
                continue;
            }
            if (n.equals("..")) {
                if (!out.isEmpty() && !out.get(out.size() - 1).equals("..")) {
                    out.remove(out.size() - 1);
                    continue;
                }
                if (absolute) {
                    continue;
                }
            }
            out.add(n);
        }
        return new MemoryPath(fs, absolute, out.toArray(new String[0]));
    }

    @Override
    public Path resolve(Path other) {
        MemoryPath o = cast(other);
        if (o.absolute) {
            return o;
        }
        if (o.names.length == 0) {
            return this;
        }
        String[] joined = new String[names.length + o.names.length];
        System.arraycopy(names, 0, joined, 0, names.length);
        System.arraycopy(o.names, 0, joined, names.length, o.names.length);
        return new MemoryPath(fs, absolute, joined);
    }

    @Override
    public Path relativize(Path other) {
        MemoryPath o = cast(other);
        if (o.absolute != absolute) {
            throw new IllegalArgumentException("Cannot relativize mixed paths");
        }
        int common = 0;
        while (common < names.length && common < o.names.length
                && names[common].equals(o.names[common])) {
            common++;
        }
        List<String> out = new ArrayList<String>();
        for (int i = common; i < names.length; i++) {
            out.add("..");
        }
        for (int i = common; i < o.names.length; i++) {
            out.add(o.names[i]);
        }
        return new MemoryPath(fs, false, out.toArray(new String[0]));
    }

    @Override
    public URI toUri() {
        try {
            return new URI("memfs", null, toAbsolutePath().toString(), null);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Path toAbsolutePath() {
        return absolute ? this : fs.rootPath.resolve(this);
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        Path abs = toAbsolutePath().normalize();
        synchronized (fs.lock) {
            if (fs.provider.lookup((MemoryPath) abs) == null) {
                throw new NoSuchFileException(toString());
            }
        }
        return abs;
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
            WatchEvent.Modifier... modifiers) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int compareTo(Path other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof MemoryPath)) {
            return false;
        }
        MemoryPath o = (MemoryPath) obj;
        return o.fs == fs && o.absolute == absolute && Arrays.equals(o.names, names);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(names) * 31 + (absolute ? 1 : 0);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (absolute) {
            sb.append('/');
        }
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(names[i]);
        }
        return sb.toString();
    }
}
