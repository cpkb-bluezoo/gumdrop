/*
 * MaildirExpungeFlaggedDeletedTest.java
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

package org.bluezoo.gumdrop.mailbox.maildir;

import org.bluezoo.gumdrop.mailbox.Flag;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Regression test: a message flagged {@code \Deleted} through
 * {@link MaildirMailbox#setFlags} (IMAP {@code STORE +FLAGS (\Deleted)}) must
 * be removed by {@link MaildirMailbox#expunge()} and by
 * {@link MaildirMailbox#close(boolean) close(true)}. Only POP3's
 * {@code deleteMessage} used to register a message for removal.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirExpungeFlaggedDeletedTest {

    private Path tempDir;
    private Path maildir;

    @Before
    public void setUp() throws Exception {
        tempDir = MemoryFileSystem.create().getPath("/maildir");
        Files.createDirectories(tempDir);
        maildir = tempDir.resolve("box");
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));
        addMessage("1733356800000.a.host", "one");
        addMessage("1733356800001.b.host", "two");
    }

    private void addMessage(String name, String subject) throws IOException {
        String content = "From: a@b\r\nSubject: " + subject + "\r\n\r\nbody\r\n";
        Files.write(maildir.resolve("new").resolve(
                name + ",S=" + content.length()),
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void expungeRemovesMessageFlaggedDeleted() throws Exception {
        MaildirMailbox mailbox = new MaildirMailbox(maildir, "INBOX", false);
        try {
            assertEquals(2, mailbox.getMessageCount());
            Set<Flag> deleted = EnumSet.of(Flag.DELETED);
            mailbox.setFlags(1, deleted, true);

            List<Integer> expunged = mailbox.expunge();

            assertEquals(1, expunged.size());
            assertEquals(Integer.valueOf(1), expunged.get(0));
            assertEquals(1, mailbox.getMessageCount());
        } finally {
            mailbox.close(false);
        }
        assertEquals(1, Files.list(maildir.resolve("cur")).count());
    }

    @Test
    public void closeWithExpungeRemovesMessageFlaggedDeleted() throws Exception {
        MaildirMailbox mailbox = new MaildirMailbox(maildir, "INBOX", false);
        Set<Flag> deleted = EnumSet.of(Flag.DELETED);
        mailbox.setFlags(2, deleted, true);
        mailbox.close(true);

        assertEquals(1, Files.list(maildir.resolve("cur")).count());
    }

    @Test
    public void expungeLeavesUnflaggedMessages() throws Exception {
        MaildirMailbox mailbox = new MaildirMailbox(maildir, "INBOX", false);
        try {
            List<Integer> expunged = mailbox.expunge();
            assertTrue(expunged.isEmpty());
            assertEquals(2, mailbox.getMessageCount());
        } finally {
            mailbox.close(false);
        }
    }
}
