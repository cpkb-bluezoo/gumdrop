/*
 * ContextCorruptArchiveTest.java
 * Copyright (C) 2025 Chris Burdess
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

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests how {@link Context} resource lookups behave when the web
 * application archive, or a library jar inside it, cannot be read: the
 * failure is logged and the resource is reported as absent rather than the
 * lookup throwing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ContextCorruptArchiveTest {

    public MemoryFolder tmp = new MemoryFolder();

    private Container container;

    @Before
    public void setUp() {
        container = SharedContainer.get();
    }

    private static byte[] garbage() {
        return "this is not a zip archive at all".getBytes(StandardCharsets.UTF_8);
    }

    private static void entry(JarOutputStream out, String name, byte[] data) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(data);
        out.closeEntry();
    }

    private void assertAbsent(Context c) throws Exception {
        assertNull(c.getResource("/nothere.txt"));
        assertNull(c.getResourcePaths("/nothere/"));
        assertNull(c.getResourceAsStream("/nothere.txt"));
    }

    @Test
    public void testUnreadableWarFileYieldsNoResources() throws Exception {
        Path war = tmp.newFile("broken.war");
        MemoryFolder.write(war, garbage());
        Context c = new Context(container, "/broken", war);
        assertAbsent(c);
    }

    @Test
    public void testUnreadableLibraryJarInExplodedWar() throws Exception {
        Path dir = tmp.newFolder("explodedbad");
        MemoryFolder.write(dir, "WEB-INF/web.xml", "<web-app/>");
        MemoryFolder.write(dir, "WEB-INF/lib/bad.jar", garbage());
        Context c = new Context(container, "/explodedbad", dir);
        assertAbsent(c);
        assertTrue(Files.isDirectory(dir));
    }

    @Test
    public void testUnreadableLibraryJarInsideWar() throws Exception {
        Path war = tmp.newFile("libbad.war");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JarOutputStream out = new JarOutputStream(bytes);
        entry(out, "WEB-INF/web.xml", "<web-app/>".getBytes(StandardCharsets.UTF_8));
        entry(out, "WEB-INF/lib/bad.jar", garbage());
        out.close();
        MemoryFolder.write(war, bytes.toByteArray());
        Context c = new Context(container, "/libbad", war);
        assertAbsent(c);
    }
}
