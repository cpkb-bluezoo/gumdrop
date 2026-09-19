/*
 * MaildirBodyOffsetPerformanceTest.java
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Unit tests for Maildir body-offset precomputation
 * ({@link MaildirMailbox#detectBodyOffset} and descriptor caching).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
/*
 * NOTE: wall-clock thresholds live here, not in the unit suite: unit tests must
 * be deterministic (CONTRIBUTING.md). Extracted from MaildirBodyOffsetTest.
 */
public class MaildirBodyOffsetPerformanceTest {

    private Path tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("maildir-body-offset");
    }

    @After
    public void tearDown() throws Exception {
        if (tempDir != null) {
            Files.walkFileTree(tempDir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (Exception ignored) {
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
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
     * {@code bodyOffset()} must be a pure in-memory read once the descriptor
     * already has a resolved offset — never a blocking disk scan or
     * blocking async-file wait APIs.
     */
    @Test
    public void bodyOffset_isInstantWhenAlreadyCached() throws Exception {
        Path maildir = tempDir.resolve("box2");
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));

        String content = "From: a@b\r\nSubject: x\r\n\r\nbody\r\n";
        long expectedOffset = content.indexOf("body");
        // See mailbox_openAsyncContent_returnsCachedBodyOffset above
        // (issue #287) for why this has no ":2,<flags>" suffix.
        String filename = "1733356800001.uid2.1,S=" + content.length();
        Files.write(maildir.resolve("cur").resolve(filename),
                content.getBytes(StandardCharsets.UTF_8));

        MaildirMailbox mailbox = new MaildirMailbox(maildir, "INBOX", false);
        try {
            // Body offset is unresolved until first content access (issue
            // #133); resolve it here via openAsyncContent so the loop below
            // is actually exercising the "already cached" fast path it's
            // meant to test, not a cold resolve.
            org.bluezoo.gumdrop.mailbox.AsyncMessageContent async =
                    mailbox.openAsyncContent(1);
            MaildirMessageDescriptor desc =
                    (MaildirMessageDescriptor) mailbox.getMessage(1);
            assertTrue("openAsyncContent must resolve and cache body offset",
                    desc.hasResolvedBodyOffset());

            // Close the channel so any blocking async-file wait / AFC read would fail.
            async.close();

            long startNs = System.nanoTime();
            for (int i = 0; i < 10_000; i++) {
                assertEquals(expectedOffset, async.bodyOffset());
            }
            long elapsedNs = System.nanoTime() - startNs;
            // 10k field reads should finish well under 100ms even on slow CI.
            assertTrue("bodyOffset() must not block (took " + elapsedNs + " ns)",
                    elapsedNs < TimeUnit.MILLISECONDS.toNanos(100));
        } finally {
            mailbox.close(false);
        }
    }

    private Path writeMessage(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
