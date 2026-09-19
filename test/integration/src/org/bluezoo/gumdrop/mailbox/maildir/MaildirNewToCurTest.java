/*
 * MaildirNewToCurTest.java
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * Regression coverage for {@code MaildirMailbox#moveNewToCur}, which
 * moves a message from {@code new/} to {@code cur/} on first scan,
 * appending an info section. issue #287 simplified this method to rely
 * entirely on {@link MaildirFilename}'s own (now dual-separator-aware)
 * parsing rather than duplicating its own hand-rolled detection of
 * whether an info section was already present -- these tests lock in
 * that the resulting {@code cur/} filename is still correctly formed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirNewToCurTest {

    private Path tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("maildir-new-to-cur");
    }

    @After
    public void tearDown() {
        if (tempDir == null) {
            return;
        }
        try {
            Files.walkFileTree(tempDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try {
                        Files.deleteIfExists(dir);
                    } catch (IOException ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    @Test
    public void newMessageWithoutInfoSectionIsMovedToCurWithOne() throws Exception {
        Path maildir = tempDir.resolve("box");
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));

        String content = "From: a@b\r\nSubject: hi\r\n\r\nbody\r\n";
        // A genuine new/ message: no info section at all yet -- the
        // normal case moveNewToCur() exists to handle.
        String filename = "1733356800000.uidtest.1,S=" + content.length();
        Files.write(maildir.resolve("new").resolve(filename),
                content.getBytes(StandardCharsets.UTF_8));

        MaildirMailbox mailbox = new MaildirMailbox(maildir, "INBOX", false);
        try {
            assertEquals(1, mailbox.getMessageCount());
        } finally {
            mailbox.close(false);
        }

        assertEquals("the message must have been moved out of new/",
                0, countFiles(maildir.resolve("new")));
        java.util.List<String> curFiles = listFiles(maildir.resolve("cur"));
        assertEquals(1, curFiles.size());
        String curFilename = curFiles.get(0);
        assertTrue("moved filename must carry an info section using this "
                        + "platform's separator ('" + MaildirFilename.INFO_SEPARATOR
                        + "'): " + curFilename,
                curFilename.endsWith(MaildirFilename.INFO_SEPARATOR));
        // Round-trips through MaildirFilename correctly, preserving the
        // original size, with no flags (a message straight out of new/
        // has never been flagged).
        MaildirFilename parsed = new MaildirFilename(curFilename);
        assertEquals(content.length(), parsed.getSize());
        assertTrue(parsed.getFlags().isEmpty());
    }

    @Test
    public void newMessageWithCommaFormInfoSectionIsRecognisedAndMoved() throws Exception {
        // A new/ message that already carries the Windows-safe comma-form
        // info section (issue #287) -- e.g. produced on a Windows
        // deployment but the directory later inspected on this platform
        // -- must still be recognised as already having an info section,
        // not have a second one appended.
        Path maildir = tempDir.resolve("box2");
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));

        String content = "From: a@b\r\nSubject: hi\r\n\r\nbody\r\n";
        String filename = "1733356800001.uidtest.2,S=" + content.length() + ",2,S";
        Files.write(maildir.resolve("new").resolve(filename),
                content.getBytes(StandardCharsets.UTF_8));

        MaildirMailbox mailbox = new MaildirMailbox(maildir, "INBOX", false);
        try {
            assertEquals(1, mailbox.getMessageCount());
        } finally {
            mailbox.close(false);
        }

        java.util.List<String> curFiles = listFiles(maildir.resolve("cur"));
        assertEquals(1, curFiles.size());
        MaildirFilename parsed = new MaildirFilename(curFiles.get(0));
        assertEquals(content.length(), parsed.getSize());
        assertTrue("the pre-existing Seen flag must be preserved, not "
                + "discarded as if no info section had been recognised",
                parsed.getFlags().contains(org.bluezoo.gumdrop.mailbox.Flag.SEEN));
    }

    private static int countFiles(Path dir) throws IOException {
        return listFiles(dir).size();
    }

    private static java.util.List<String> listFiles(Path dir) throws IOException {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    names.add(p.getFileName().toString());
                }
            }
        }
        return names;
    }
}
