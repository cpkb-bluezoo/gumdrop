/*
 * MaildirConcurrentSessionTest.java
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

package org.bluezoo.gumdrop.mailbox.maildir;

import org.bluezoo.gumdrop.mailbox.Flag;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Regression coverage for issue #293: two {@link MaildirMailbox} sessions
 * on the same maildir path in the same JVM must not serialise against each
 * other for their whole lifetime. Multiple clients accessing one mailbox
 * concurrently -- a phone and a desktop both idling on INBOX, or IMAP and
 * POP3 open at once -- is Maildir's whole reason for existing (lock-free
 * concurrent access via atomic per-file renames); a second session blocking
 * on the first session's full open-to-close lifetime defeats that.
 */
public class MaildirConcurrentSessionTest {

    private Path tempDir;
    private Path maildir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("maildir-concurrent-session");
        maildir = tempDir.resolve("box");
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));
    }

    @After
    public void tearDown() throws Exception {
        if (tempDir != null) {
            Files.walkFileTree(tempDir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (Exception ignored) {
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir,
                        java.io.IOException exc) {
                    try {
                        Files.deleteIfExists(dir);
                    } catch (Exception ignored) {
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * The core regression case: opening a second session on the same
     * maildir path must not wait for a first, still-open session to close.
     */
    @Test(timeout = 15000)
    public void secondSessionDoesNotBlockOnFirstSessionsWholeLifetime() throws Exception {
        final CountDownLatch firstSessionOpened = new CountDownLatch(1);
        final CountDownLatch releaseFirstSession = new CountDownLatch(1);
        final AtomicReference<Exception> firstSessionError = new AtomicReference<>();

        Thread firstSessionThread = new Thread(() -> {
            try {
                MaildirMailbox first = new MaildirMailbox(maildir, "INBOX", false);
                firstSessionOpened.countDown();
                // Hold the session open (simulating a live IMAP IDLE client)
                // well beyond how long the second session's own open should
                // ever legitimately take.
                releaseFirstSession.await(10, TimeUnit.SECONDS);
                first.close(false);
            } catch (Exception e) {
                firstSessionError.set(e);
            }
        });
        firstSessionThread.start();

        assertTrue("first session never finished opening",
                firstSessionOpened.await(5, TimeUnit.SECONDS));

        long startNs = System.nanoTime();
        MaildirMailbox second = new MaildirMailbox(maildir, "INBOX", false);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        second.close(false);

        releaseFirstSession.countDown();
        firstSessionThread.join(TimeUnit.SECONDS.toMillis(10));

        assertNull("first session thread failed", firstSessionError.get());
        assertTrue("second session must not block on the first session's "
                + "whole lifetime (took " + elapsedMs + "ms)",
                elapsedMs < 2000);
    }

    /**
     * Two sessions appending concurrently must each get a distinct UID
     * with no lost update to the shared {@code .uidlist} file -- the
     * correctness property the old whole-session lock incidentally
     * provided by preventing genuine concurrency in the first place.
     */
    @Test(timeout = 15000)
    public void concurrentAppendsFromTwoSessionsGetDistinctUids() throws Exception {
        final CountDownLatch bothReady = new CountDownLatch(2);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicReference<Exception> errorA = new AtomicReference<>();
        final AtomicReference<Exception> errorB = new AtomicReference<>();
        final AtomicLong uidA = new AtomicLong(-1);
        final AtomicLong uidB = new AtomicLong(-1);

        Thread threadA = new Thread(() -> {
            try {
                MaildirMailbox mailbox = new MaildirMailbox(maildir, "INBOX", false);
                bothReady.countDown();
                go.await(10, TimeUnit.SECONDS);
                uidA.set(appendMessage(mailbox,
                        "From: a@example.com\r\nSubject: A\r\n\r\nmessage A\r\n"));
                mailbox.close(false);
            } catch (Exception e) {
                errorA.set(e);
            }
        });
        Thread threadB = new Thread(() -> {
            try {
                MaildirMailbox mailbox = new MaildirMailbox(maildir, "INBOX", false);
                bothReady.countDown();
                go.await(10, TimeUnit.SECONDS);
                uidB.set(appendMessage(mailbox,
                        "From: b@example.com\r\nSubject: B\r\n\r\nmessage B\r\n"));
                mailbox.close(false);
            } catch (Exception e) {
                errorB.set(e);
            }
        });

        threadA.start();
        threadB.start();
        assertTrue("both sessions never finished opening",
                bothReady.await(5, TimeUnit.SECONDS));
        go.countDown();
        threadA.join(TimeUnit.SECONDS.toMillis(10));
        threadB.join(TimeUnit.SECONDS.toMillis(10));

        assertNull("session A failed", errorA.get());
        assertNull("session B failed", errorB.get());
        assertTrue("session A never assigned a UID", uidA.get() > 0);
        assertTrue("session B never assigned a UID", uidB.get() > 0);
        assertNotEquals("concurrent appends must not be assigned the same UID",
                uidA.get(), uidB.get());

        MaildirMailbox verify = new MaildirMailbox(maildir, "INBOX", false);
        try {
            assertEquals("both concurrently-appended messages must be present",
                    2, verify.getMessageCount());
            Set<String> seenUids = new HashSet<>();
            java.util.Iterator<org.bluezoo.gumdrop.mailbox.MessageDescriptor> descriptors =
                    verify.getMessageList();
            while (descriptors.hasNext()) {
                seenUids.add(descriptors.next().getUniqueId());
            }
            assertTrue("session A's UID must have been persisted",
                    seenUids.contains(Long.toString(uidA.get())));
            assertTrue("session B's UID must have been persisted",
                    seenUids.contains(Long.toString(uidB.get())));
        } finally {
            verify.close(false);
        }
    }

    private static long appendMessage(MaildirMailbox mailbox, String content) throws Exception {
        mailbox.startAppendMessage(java.util.EnumSet.noneOf(Flag.class), OffsetDateTime.now());
        mailbox.appendMessageContent(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
        return mailbox.endAppendMessage();
    }
}
