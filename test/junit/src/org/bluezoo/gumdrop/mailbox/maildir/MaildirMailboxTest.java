/*
 * MaildirMailboxTest.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MaildirMailbox} on an in-memory file system.
 *
 * <p>An in-memory provider has no asynchronous file channels, which is the
 * same situation as any other file system that lacks them (a zip file
 * system, for instance). {@link Mailbox} says that a mailbox which cannot
 * offer async I/O returns {@code null} so callers use the blocking calls.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirMailboxTest {

    private static final String MESSAGE =
            "From: a@example.com\r\nSubject: hi\r\n\r\nbody\r\n";

    private Path root;
    private MaildirMailboxStore store;
    private Mailbox inbox;

    @Before
    public void setUp() throws IOException {
        MemoryFileSystem mem = MemoryFileSystem.create();
        root = mem.getPath("/mail");
        Files.createDirectories(root);
        store = new MaildirMailboxStore(root);
        store.open("alice");
        inbox = store.openMailbox("INBOX", false);
    }

    @After
    public void tearDown() throws IOException {
        inbox.close(false);
        store.close();
    }

    private void append(String text) throws IOException {
        inbox.startAppendMessage(null, null);
        inbox.appendMessageContent(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
        inbox.endAppendMessage();
    }

    private static String readAll(ReadableByteChannel in) throws IOException {
        StringBuilder sb = new StringBuilder();
        ByteBuffer buf = ByteBuffer.allocate(64);
        try {
            while (in.read(buf) >= 0) {
                buf.flip();
                sb.append(StandardCharsets.US_ASCII.decode(buf));
                buf.clear();
            }
        } finally {
            in.close();
        }
        return sb.toString();
    }

    @Test
    public void testBlockingAppendAndReadBack() throws IOException {
        append(MESSAGE);
        assertEquals(1, inbox.getMessageCount());
        assertEquals(MESSAGE, readAll(inbox.getMessageContent(1)));
    }

    @Test
    public void testAsyncContentUnsupportedWithoutAsyncChannels() throws IOException {
        append(MESSAGE);
        assertNull(inbox.openAsyncContent(1));
        assertEquals("the blocking path still works", MESSAGE,
                readAll(inbox.getMessageContent(1)));
    }

    @Test
    public void testAsyncAppendUnsupportedLeavesNoTempFile() throws IOException {
        assertNull(inbox.openAsyncAppend(null, null));
        DirectoryStream<Path> tmp = Files.newDirectoryStream(root.resolve("alice/tmp"));
        try {
            assertTrue("no orphaned temp file", !tmp.iterator().hasNext());
        } finally {
            tmp.close();
        }
    }
}
