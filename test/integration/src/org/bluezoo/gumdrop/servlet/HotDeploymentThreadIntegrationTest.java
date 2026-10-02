/*
 * HotDeploymentThreadIntegrationTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Integration test: the hot-deployment watcher over the real watch service
 * and a real directory tree, which an in-memory file system cannot provide.
 * The unit test drives the same logic with mock watch keys.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HotDeploymentThreadIntegrationTest {

    private Path root;
    private HotDeploymentThread watcher;

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("gumdrop-hotdeploy");
        Files.createDirectories(root.resolve("WEB-INF/classes/pkg"));
        Files.write(root.resolve("WEB-INF/web.xml"),
                "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\"/>"
                        .getBytes(StandardCharsets.UTF_8));
        watcher = new HotDeploymentThread(new Container());
    }

    @After
    public void tearDown() throws IOException {
        watcher.watchService.close();
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

    @Test
    public void testDirectoryContextRegistersRealWatchKeys() throws Exception {
        Context context = new Context(new Container(), "/app", root.toFile());
        watcher.init(context);
        // root, WEB-INF, WEB-INF/classes, WEB-INF/classes/pkg
        assertEquals(4, watcher.watchKeys.size());
        assertTrue(watcher.warLastModified.isEmpty());
    }

    @Test
    public void testRunStopsWhenInterrupted() throws Exception {
        Context context = new Context(new Container(), "/app", root.toFile());
        watcher.container.contexts.add(context);
        Thread.currentThread().interrupt();
        try {
            watcher.run();
        } finally {
            Thread.interrupted();
        }
        assertEquals(4, watcher.watchKeys.size());
    }
}
