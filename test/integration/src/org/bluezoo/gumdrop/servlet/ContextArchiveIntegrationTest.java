/*
 * ContextArchiveIntegrationTest.java
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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Integration test: runs every scenario of {@link ContextArchiveTest} against
 * web applications and archives on the real (default) file system, where
 * {@link Archive} reads files in place through a {@code JarFile} and a WAR's
 * library jars are extracted to temporary files. The unit test runs the same
 * scenarios on an in-memory file system. Also covers the real temporary
 * directory the servlet context exposes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextArchiveIntegrationTest extends ContextArchiveTest {

    private Path scratch;

    @Before
    public void createScratch() throws IOException {
        scratch = Files.createTempDirectory("gumdrop-archive");
    }

    @After
    public void deleteScratch() throws IOException {
        Files.walkFileTree(scratch, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    protected Path newFolder(String name) throws IOException {
        Path dir = scratch.resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    @Override
    protected Path scratchFile(String name) throws IOException {
        return scratch.resolve(name);
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
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

    private static void zip(Path file, String[] names, byte[][] contents) throws IOException {
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file));
        try {
            for (int i = 0; i < names.length; i++) {
                out.putNextEntry(new ZipEntry(names[i]));
                out.write(contents[i]);
                out.closeEntry();
            }
        } finally {
            out.close();
        }
    }

    @Test
    public void testTempDirIsCreatedOnFirstUseAsARealDirectory() throws Exception {
        Container container = new Container();
        Context context = new Context(container, "/tmpapp", newFolder("tmpapp"));
        Object first = context.getAttribute("jakarta.servlet.context.tempdir");
        assertTrue(first instanceof File);
        assertTrue(((File) first).isDirectory());
        assertSame(first, context.getAttribute("jakarta.servlet.context.tempdir"));
        context.reset();
        Object second = context.getAttribute("jakarta.servlet.context.tempdir");
        assertNotSame(first, second);
        assertTrue(((File) second).isDirectory());
        ((File) first).delete();
        ((File) second).delete();
    }

    @Test
    public void testArchiveReadsARealFileInPlace() throws Exception {
        Path inner = scratchFile("inner.jar");
        zip(inner, new String[] { "inner.txt" }, new byte[][] { text("deep") });
        byte[] innerBytes = Files.readAllBytes(inner);
        Path war = scratchFile("real.war");
        zip(war, new String[] { "a.txt", "dir/", "lib/x.jar" },
                new byte[][] { text("alpha"), new byte[0], innerBytes });
        Archive archive = Archive.open(war);
        try {
            List<String> names = archive.entryNames();
            assertEquals(3, names.size());
            assertTrue(archive.contains("dir"));
            assertTrue(archive.isFile("a.txt"));
            assertFalse(archive.isFile("dir/"));
            assertFalse(archive.isFile("missing"));
            assertEquals(5L, archive.size("a.txt"));
            assertEquals(-1L, archive.size("missing"));
            assertTrue(archive.time("a.txt") != 0L);
            assertEquals(-1L, archive.time("missing"));
            assertEquals("alpha", read(archive.stream("a.txt")));
            assertNull(archive.stream("missing"));
            assertNull(archive.streamOwned("missing"));
            InputStream owned = archive.streamOwned("a.txt");
            Archive nested = archive.nested("lib/x.jar", "_x.jar");
            assertNotNull(nested);
            assertEquals("deep", read(nested.stream("inner.txt")));
            assertNull(archive.nested("lib/none.jar", "_none.jar"));
            nested.close();
            archive.close();
            // the owned stream outlives the archive that produced it
            assertEquals("alpha", read(owned));
        } finally {
            archive.close();
        }
    }
}
