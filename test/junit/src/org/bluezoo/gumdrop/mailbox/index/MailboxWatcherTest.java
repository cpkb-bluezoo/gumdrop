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

        notified.await();
        assertEquals("target.txt", names.get(0));
    }

    @Test
    public void register_ignoresNonMatchingFile() throws Exception {
        final CountDownLatch targetNotified = new CountDownLatch(1);
        watcher.register(tempDir, "target.txt", new MailboxWatcher.ChangeListener() {
            @Override public void onChange(String name) { targetNotified.countDown(); }
        });

        final CountDownLatch canaryNotified = new CountDownLatch(1);
        watcher.register(tempDir, "other.txt", new MailboxWatcher.ChangeListener() {
            @Override public void onChange(String name) { canaryNotified.countDown(); }
        });

        Files.createFile(tempDir.resolve("other.txt"));

        canaryNotified.await();
        assertEquals("target listener should not fire for a different filename",
                1, targetNotified.getCount());
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

        notified.await();
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

        first.await();
        second.await();
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

        notified.await();
    }

    @Test
    public void shutdown_stopsDispatchingEvents() throws Exception {
        final CountDownLatch notified = new CountDownLatch(1);
        watcher.register(tempDir, "afterShutdown.txt", new MailboxWatcher.ChangeListener() {
            @Override public void onChange(String name) { notified.countDown(); }
        });

        watcher.shutdown();
        watcher.awaitTermination();
        Files.createFile(tempDir.resolve("afterShutdown.txt"));

        assertEquals("no events should be dispatched after shutdown",
                1, notified.getCount());
    }
}
