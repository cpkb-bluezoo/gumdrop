/*
 * JspHotReloaderIntegrationTest.java
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

package org.bluezoo.gumdrop.servlet.jsp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.servlet.Container;
import org.bluezoo.gumdrop.servlet.Context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Integration test: the real watch service, a real thread and a real
 * directory tree, which an in-memory file system cannot provide. The unit
 * test drives the key handling with mock watch keys.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JspHotReloaderIntegrationTest {

    private Path root;

    @Before
    public void createTree() throws IOException {
        root = Files.createTempDirectory("gumdrop-hotreload");
        write("WEB-INF/web.xml",
                "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"/>");
        write("sub/inner/x.jsp", "x");
        write("WEB-INF/classes/skipped.txt", "x");
        write("META-INF/m.txt", "x");
    }

    @After
    public void deleteTree() throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
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

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void monitoringRegistersDirectoriesAndStops() throws Exception {
        Container container = new Container();
        Context context = new Context(container, "/app", root.toFile());
        container.addContext(context);
        JspHotReloader reloader = new JspHotReloader(context, root, null);
        reloader.startMonitoring();
        // root, WEB-INF, sub, sub/inner (classes and META-INF are skipped)
        assertEquals(4, reloader.watchKeys.size());
        reloader.stopMonitoring();
        reloader.join(10000L);
        assertFalse(reloader.isAlive());
        // stopping twice tolerates an already closed watch service
        reloader.stopMonitoring();
    }
}
