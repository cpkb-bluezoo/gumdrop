/*
 * Archive.java
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

package org.bluezoo.gumdrop.servlet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import org.bluezoo.gumdrop.util.JarInputStream;

/**
 * A read-only ZIP archive (a WAR or a library JAR) opened from a
 * {@link Path}. Archives on the default file system are read in place
 * through a {@link JarFile}, which gives random access without loading the
 * archive, exactly as before this class existed. Archives on any other file
 * system (or built from a byte array, such as a library JAR nested inside a
 * WAR held on such a file system) are read once into memory and indexed by
 * entry name; they are intended for small archives.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class Archive implements Closeable {

    /** First bytes of an archive with no entries (end-of-central-directory). */
    private static final byte[] EMPTY_ARCHIVE_SIGNATURE = { 'P', 'K', 5, 6 };

    private final JarFile jarFile;          // default file system archives
    private final File file;                // backing file of jarFile
    private final Map<String, Mem> entries; // in-memory archives

    /** An in-memory entry. */
    private static final class Mem {
        final String name;
        final byte[] data;
        final long time;

        Mem(String name, byte[] data, long time) {
            this.name = name;
            this.data = data;
            this.time = time;
        }

        boolean isDirectory() {
            return name.endsWith("/");
        }
    }

    private Archive(JarFile jarFile, File file, Map<String, Mem> entries) {
        this.jarFile = jarFile;
        this.file = file;
        this.entries = entries;
    }

    /**
     * Opens the archive at the given path.
     *
     * @param path the archive
     * @return the opened archive
     * @throws IOException if the archive cannot be read or is not a ZIP
     */
    static Archive open(Path path) throws IOException {
        if (path.getFileSystem() == FileSystems.getDefault()) {
            File f = path.toFile();
            JarFile jar = new JarFile(f);
            return new Archive(jar, f, null);
        }
        byte[] data = Files.readAllBytes(path);
        return of(data);
    }

    /**
     * Opens an archive held in memory.
     *
     * @param data the bytes of the archive
     * @return the archive
     * @throws IOException if the bytes are not a ZIP
     */
    static Archive of(byte[] data) throws IOException {
        Map<String, Mem> map = new LinkedHashMap<String, Mem>();
        ByteArrayInputStream bin = new ByteArrayInputStream(data);
        ZipInputStream zin = new ZipInputStream(bin);
        try {
            ZipEntry entry = zin.getNextEntry();
            while (entry != null) {
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n = zin.read(buf); n != -1; n = zin.read(buf)) {
                    sink.write(buf, 0, n);
                }
                String name = entry.getName();
                map.put(name, new Mem(name, sink.toByteArray(), entry.getTime()));
                zin.closeEntry();
                entry = zin.getNextEntry();
            }
        } finally {
            zin.close();
        }
        if (map.isEmpty() && !startsWith(data, EMPTY_ARCHIVE_SIGNATURE)) {
            throw new ZipException("zip END header not found");
        }
        return new Archive(null, null, map);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the names of all entries in archive order.
     */
    List<String> entryNames() {
        List<String> names = new ArrayList<String>();
        if (jarFile != null) {
            Enumeration<JarEntry> e = jarFile.entries();
            while (e.hasMoreElements()) {
                JarEntry entry = e.nextElement();
                names.add(entry.getName());
            }
        } else {
            names.addAll(entries.keySet());
        }
        return names;
    }

    private Mem mem(String name) {
        Mem m = entries.get(name);
        if (m == null && !name.endsWith("/")) {
            m = entries.get(name + "/");
        }
        return m;
    }

    /**
     * Returns whether the archive has an entry of this name (as
     * {@link JarFile#getJarEntry}: a directory entry is found without its
     * trailing slash).
     */
    boolean contains(String name) {
        if (jarFile != null) {
            return jarFile.getJarEntry(name) != null;
        }
        return mem(name) != null;
    }

    /**
     * Returns whether the named entry exists and is not a directory.
     */
    boolean isFile(String name) {
        if (jarFile != null) {
            JarEntry entry = jarFile.getJarEntry(name);
            return entry != null && !entry.isDirectory();
        }
        Mem m = mem(name);
        return m != null && !m.isDirectory();
    }

    /**
     * Returns the uncompressed size of the entry, or -1.
     */
    long size(String name) {
        if (jarFile != null) {
            JarEntry entry = jarFile.getJarEntry(name);
            return (entry != null) ? entry.getSize() : -1L;
        }
        Mem m = mem(name);
        return (m != null) ? m.data.length : -1L;
    }

    /**
     * Returns the modification time of the entry in milliseconds, or -1.
     */
    long time(String name) {
        if (jarFile != null) {
            JarEntry entry = jarFile.getJarEntry(name);
            return (entry != null) ? entry.getTime() : -1L;
        }
        Mem m = mem(name);
        return (m != null) ? m.time : -1L;
    }

    /**
     * Opens a stream over an entry, valid while this archive stays open.
     * The caller closes the stream; that does not close the archive.
     *
     * @return the stream, or null if there is no such entry
     */
    InputStream stream(String name) throws IOException {
        if (jarFile != null) {
            JarEntry entry = jarFile.getJarEntry(name);
            if (entry == null) {
                return null;
            }
            return jarFile.getInputStream(entry);
        }
        Mem m = mem(name);
        return (m != null) ? new ByteArrayInputStream(m.data) : null;
    }

    /**
     * Opens a stream over an entry that stays valid after this archive is
     * closed: for a file-backed archive the stream owns its own handle on
     * the file and releases it when closed.
     *
     * @return the stream, or null if there is no such entry
     */
    InputStream streamOwned(String name) throws IOException {
        if (jarFile != null) {
            JarFile own = new JarFile(file);
            JarEntry entry = own.getJarEntry(name);
            if (entry == null) {
                own.close();
                return null;
            }
            return new JarInputStream(own, entry);
        }
        return stream(name);
    }

    /**
     * Opens the named entry, itself an archive, as an archive. A
     * file-backed archive extracts the entry to a temporary file (deleted on
     * JVM exit) so that it too can be read in place; an in-memory archive
     * parses the entry bytes.
     *
     * @param name the entry holding the nested archive
     * @param tempSuffix suffix for the temporary file
     * @return the nested archive, or null if there is no such entry
     */
    Archive nested(String name, String tempSuffix) throws IOException {
        InputStream in = stream(name);
        if (in == null) {
            return null;
        }
        try {
            if (jarFile == null) {
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n = in.read(buf); n != -1; n = in.read(buf)) {
                    sink.write(buf, 0, n);
                }
                return of(sink.toByteArray());
            }
            File tmp = File.createTempFile("gumdrop", tempSuffix);
            tmp.deleteOnExit();
            Path tmpPath = tmp.toPath();
            Files.copy(in, tmpPath, StandardCopyOption.REPLACE_EXISTING);
            JarFile nestedJar = new JarFile(tmp);
            return new Archive(nestedJar, tmp, null);
        } finally {
            in.close();
        }
    }

    @Override
    public void close() throws IOException {
        if (jarFile != null) {
            jarFile.close();
        }
    }

}
