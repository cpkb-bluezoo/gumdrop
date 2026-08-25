/*
 * MailboxWatcherTest.java
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

package org.bluezoo.gumdrop.mailbox.index;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MailboxWatcher}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MailboxWatcherTest {

    private MailboxWatcher watcher;
    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        watcher = new MailboxWatcher();
        tempDir = Files.createTempDirectory("mailbox-watcher-test");
    }

    @After
    public void tearDown() throws IOException {
        watcher.shutdown();
        deleteRecursively(tempDir);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path visited, IOException exc) {
                try {
                    Files.deleteIfExists(visited);
                } catch (IOException ignored) {
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Test
    public void register_notifiesOnMatchingFileCreated() throws Exception {
        final CountDownLatch notified = new CountDownLatch(1);
        final List<String> names = new CopyOnWriteArrayList<>();
        watcher.register(tempDir, "target.txt", new MailboxWatcher.ChangeListener() {
            @Override
            public void onChange(String name) {
                names.add(name);
                notified.countDown();
            }
        });

        Files.createFile(tempDir.resolve("target.txt"));

        assertTrue("listener should be notified", notified.await(10, TimeUnit.SECONDS));
        assertEquals("target.txt", names.get(0));
    }

    @Test
    public void register_ignoresNonMatchingFile() throws Exception {
        final CountDownLatch notified = new CountDownLatch(1);
        watcher.register(tempDir, "target.txt", new MailboxWatcher.ChangeListener() {
            @Override public void onChange(String name) { notified.countDown(); }
        });

        Files.createFile(tempDir.resolve("other.txt"));

        assertFalse("listener should not fire for a different filename",
                notified.await(500, TimeUnit.MILLISECONDS));
    }

    @Test
    public void register_nullFilterMatchesAnyFile() throws Exception {
        final CountDownLatch notified = new CountDownLatch(1);
        final List<String> names = new CopyOnWriteArrayList<>();
        watcher.register(tempDir, null, new MailboxWatcher.ChangeListener() {
            @Override
            public void onChange(String name) {
                names.add(name);
                notified.countDown();
            }
        });

        Files.createFile(tempDir.resolve("anything.txt"));

        assertTrue(notified.await(10, TimeUnit.SECONDS));
        assertEquals("anything.txt", names.get(0));
    }

    @Test
    public void register_multipleListenersOnSameDirectoryAllNotified() throws Exception {
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch second = new CountDownLatch(1);
        watcher.register(tempDir, "shared.txt", new MailboxWatcher.ChangeListener() {
            @Override public void onChange(String name) { first.countDown(); }
        });
        watcher.register(tempDir, "shared.txt", new MailboxWatcher.ChangeListener() {
            @Override public void onChange(String name) { second.countDown(); }
        });

        Files.createFile(tempDir.resolve("shared.txt"));

        assertTrue(first.await(10, TimeUnit.SECONDS));
        assertTrue(second.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void register_notifiesOnFileModification() throws Exception {
        Path target = tempDir.resolve("modme.txt");
        Files.createFile(target);

        final CountDownLatch notified = new CountDownLatch(1);
        watcher.register(tempDir, "modme.txt", new MailboxWatcher.ChangeListener() {
            @Override public void onChange(String name) { notified.countDown(); }
        });

        Files.write(target, "changed".getBytes());

        assertTrue("listener should be notified of a modify event",
                notified.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void shutdown_stopsDispatchingEvents() throws Exception {
        final CountDownLatch notified = new CountDownLatch(1);
        watcher.register(tempDir, "afterShutdown.txt", new MailboxWatcher.ChangeListener() {
            @Override public void onChange(String name) { notified.countDown(); }
        });

        watcher.shutdown();
        Files.createFile(tempDir.resolve("afterShutdown.txt"));

        assertFalse("no events should be dispatched after shutdown",
                notified.await(500, TimeUnit.MILLISECONDS));
    }
}
