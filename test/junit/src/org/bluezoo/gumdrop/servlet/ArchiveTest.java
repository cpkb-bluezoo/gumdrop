/*
 * ArchiveTest.java
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises the in-memory side of {@link Archive}, which serves WARs and
 * library jars held on any file system other than the default one (here a
 * private in-memory file system). The default-file-system side, which reads
 * a real file in place, is covered by the integration tests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ArchiveTest {

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(String[] names, byte[][] contents) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ZipOutputStream out = new ZipOutputStream(bout);
        for (int i = 0; i < names.length; i++) {
            ZipEntry entry = new ZipEntry(names[i]);
            entry.setTime(86400000L * 365L);
            out.putNextEntry(entry);
            out.write(contents[i]);
            out.closeEntry();
        }
        out.close();
        return bout.toByteArray();
    }

    private static String read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            for (int n = in.read(buf); n != -1; n = in.read(buf)) {
                bout.write(buf, 0, n);
            }
            return new String(bout.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static byte[] sample() throws IOException {
        byte[] inner = zip(new String[] { "inner.txt" }, new byte[][] { text("deep") });
        return zip(new String[] { "a.txt", "dir/", "dir/b.txt", "lib/x.jar" },
                new byte[][] { text("alpha"), new byte[0], text("beta"), inner });
    }

    @Test
    public void testEntriesAreListedInArchiveOrder() throws IOException {
        Archive archive = Archive.of(sample());
        List<String> names = archive.entryNames();
        assertEquals(4, names.size());
        assertEquals("a.txt", names.get(0));
        assertEquals("dir/", names.get(1));
        assertEquals("dir/b.txt", names.get(2));
        assertEquals("lib/x.jar", names.get(3));
        archive.close();
    }

    @Test
    public void testLookupMatchesDirectoriesWithoutTrailingSlash() throws IOException {
        Archive archive = Archive.of(sample());
        assertTrue(archive.contains("a.txt"));
        assertTrue(archive.contains("dir"));
        assertTrue(archive.contains("dir/"));
        assertFalse(archive.contains("missing"));
        assertTrue(archive.isFile("a.txt"));
        assertFalse(archive.isFile("dir"));
        assertFalse(archive.isFile("dir/"));
        assertFalse(archive.isFile("missing"));
    }

    @Test
    public void testSizeAndTime() throws IOException {
        Archive archive = Archive.of(sample());
        assertEquals(5L, archive.size("a.txt"));
        assertEquals(-1L, archive.size("missing"));
        assertTrue(archive.time("a.txt") > 0L);
        assertEquals(-1L, archive.time("missing"));
    }

    @Test
    public void testStreamsReadEntryContent() throws IOException {
        Archive archive = Archive.of(sample());
        assertEquals("alpha", read(archive.stream("a.txt")));
        assertEquals("beta", read(archive.streamOwned("dir/b.txt")));
        assertNull(archive.stream("missing"));
        assertNull(archive.streamOwned("missing"));
    }

    @Test
    public void testNestedArchiveIsOpenedFromEntryBytes() throws IOException {
        Archive archive = Archive.of(sample());
        Archive inner = archive.nested("lib/x.jar", "_x.jar");
        assertNotNull(inner);
        assertEquals("deep", read(inner.stream("inner.txt")));
        assertNull(archive.nested("lib/none.jar", "_none.jar"));
    }

    @Test
    public void testNestedNonArchiveIsRejected() throws IOException {
        Archive archive = Archive.of(sample());
        try {
            archive.nested("a.txt", "_a.txt");
            fail("expected ZipException");
        } catch (ZipException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testNonZipBytesAreRejected() throws IOException {
        try {
            Archive.of(text("not a zip file"));
            fail("expected ZipException");
        } catch (ZipException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testEmptyArchiveHasNoEntries() throws IOException {
        byte[] empty = zip(new String[0], new byte[0][]);
        Archive archive = Archive.of(empty);
        assertTrue(archive.entryNames().isEmpty());
        assertFalse(archive.contains("anything"));
    }

    @Test
    public void testOpenReadsAWholeArchiveFromAnotherFileSystem() throws IOException {
        MemoryFolder folder = new MemoryFolder();
        Path file = MemoryFolder.write(folder.getRoot().resolve("app.war"), sample());
        Archive archive = Archive.open(file);
        assertEquals("alpha", read(archive.stream("a.txt")));
        archive.close();
    }

    @Test
    public void testOpenMissingFileFails() throws IOException {
        MemoryFolder folder = new MemoryFolder();
        try {
            Archive.open(folder.getRoot().resolve("absent.war"));
            fail("expected NoSuchFileException");
        } catch (NoSuchFileException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
