/*
 * MaildirStoreCopyTest.java
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
import org.bluezoo.gumdrop.mailbox.Mailbox;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tests {@link MaildirMailboxStore#copyMessages} and
 * {@link MaildirMailboxStore#moveMessages}: content, flags and UID
 * assignment across mailboxes of one store.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirStoreCopyTest {

    private static final String BODY_ONE =
            "From: a@b\r\nSubject: one\r\n\r\nfirst body\r\n";
    private static final String BODY_TWO =
            "From: a@b\r\nSubject: two\r\n\r\nsecond body\r\n";

    private Path root;
    private MaildirMailboxStore store;

    @Before
    public void setUp() throws Exception {
        root = MemoryFileSystem.create().getPath("/maildir");
        Files.createDirectories(root);
        Path inbox = root.resolve("editor");
        makeMaildir(inbox);
        writeNew(inbox, "1733356800000.a.host", BODY_ONE);
        writeNew(inbox, "1733356800001.b.host", BODY_TWO);
        store = new MaildirMailboxStore(root);
        store.open("editor");
        store.createMailbox("Dest");
    }

    private static void makeMaildir(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("cur"));
        Files.createDirectories(dir.resolve("new"));
        Files.createDirectories(dir.resolve("tmp"));
    }

    private static void writeNew(Path maildir, String name, String content)
            throws IOException {
        Files.write(maildir.resolve("new").resolve(
                name + ",S=" + content.length()),
                content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Mailbox mailbox, int number)
            throws IOException {
        ReadableByteChannel channel = mailbox.getMessageContent(number);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(4096);
        try {
            while (channel.read(buf) >= 0) {
                buf.flip();
                out.write(buf.array(), 0, buf.limit());
                buf.clear();
            }
        } finally {
            channel.close();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static List<Integer> numbers(int... values) {
        List<Integer> list = new ArrayList<Integer>();
        for (int i = 0; i < values.length; i++) {
            list.add(Integer.valueOf(values[i]));
        }
        return list;
    }

    @Test
    public void copyPreservesContentAndFlags() throws Exception {
        Mailbox inbox = store.openMailbox("INBOX", false);
        String first;
        String second;
        try {
            first = read(inbox, 1);
            second = read(inbox, 2);
            assertTrue(first.equals(BODY_ONE) || first.equals(BODY_TWO));
            assertFalse(first.equals(second));
            Set<Flag> flags = EnumSet.of(Flag.SEEN, Flag.FLAGGED);
            inbox.setFlags(2, flags, true);

            Map<Integer, Long> uids = store.copyMessages(inbox,
                    numbers(1, 2), "Dest");

            assertEquals(2, uids.size());
            assertNotNull(uids.get(Integer.valueOf(1)));
            assertNotNull(uids.get(Integer.valueOf(2)));
            assertTrue(uids.get(Integer.valueOf(1)).longValue()
                    != uids.get(Integer.valueOf(2)).longValue());
            // the source is untouched
            assertEquals(2, inbox.getMessageCount());
        } finally {
            inbox.close(false);
        }

        Mailbox dest = store.openMailbox("Dest", true);
        try {
            assertEquals(2, dest.getMessageCount());
            assertEquals(first, read(dest, 1));
            assertEquals(second, read(dest, 2));
            Set<Flag> copied = dest.getFlags(2);
            assertTrue(copied.contains(Flag.SEEN));
            assertTrue(copied.contains(Flag.FLAGGED));
            assertFalse(dest.getFlags(1).contains(Flag.SEEN));
        } finally {
            dest.close(false);
        }
    }

    @Test
    public void copyToMissingMailboxFails() throws Exception {
        Mailbox inbox = store.openMailbox("INBOX", false);
        try {
            try {
                store.copyMessages(inbox, numbers(1), "NoSuchBox");
                fail("expected IOException");
            } catch (IOException expected) {
                // TRYCREATE case
            }
        } finally {
            inbox.close(false);
        }
    }

    @Test
    public void copyWithinSameMailboxAssignsNewUids() throws Exception {
        Mailbox inbox = store.openMailbox("INBOX", false);
        try {
            long uidNextBefore = inbox.getUidNext();
            String expected = read(inbox, 1);
            Map<Integer, Long> uids = store.copyMessages(inbox,
                    numbers(1), "INBOX");

            assertEquals(1, uids.size());
            assertTrue(uids.get(Integer.valueOf(1)).longValue()
                    >= uidNextBefore);
            assertEquals(3, inbox.getMessageCount());
            assertEquals(expected, read(inbox, 3));
        } finally {
            inbox.close(false);
        }
    }

    @Test
    public void copyReportsDestinationUidValidity() throws Exception {
        Mailbox inbox = store.openMailbox("INBOX", false);
        try {
            store.copyMessages(inbox, numbers(1), "Dest");
        } finally {
            inbox.close(false);
        }
        Mailbox dest = store.openMailbox("Dest", true);
        try {
            assertTrue(dest.getUidValidity() > 0);
            assertEquals(1, dest.getMessageCount());
        } finally {
            dest.close(false);
        }
    }

    @Test
    public void moveTransfersMessagesAndRemovesThemFromSource()
            throws Exception {
        Mailbox inbox = store.openMailbox("INBOX", false);
        String first;
        String second;
        try {
            first = read(inbox, 1);
            second = read(inbox, 2);

            Map<Integer, Long> uids = store.moveMessages(inbox,
                    numbers(1), "Dest");

            assertEquals(1, uids.size());
            assertEquals(1, inbox.getMessageCount());
            assertEquals(second, read(inbox, 1));
        } finally {
            inbox.close(false);
        }

        Mailbox dest = store.openMailbox("Dest", true);
        try {
            assertEquals(1, dest.getMessageCount());
            assertEquals(first, read(dest, 1));
        } finally {
            dest.close(false);
        }
        assertEquals(1, Files.list(root.resolve("editor").resolve("cur"))
                .count());
    }

    /**
     * MOVE removes only the moved messages: another message the client has
     * flagged \Deleted but not yet expunged must survive.
     */
    @Test
    public void moveLeavesOtherDeletedFlaggedMessagesInPlace()
            throws Exception {
        Mailbox inbox = store.openMailbox("INBOX", false);
        try {
            inbox.setFlags(2, EnumSet.of(Flag.DELETED), true);

            store.moveMessages(inbox, numbers(1), "Dest");

            assertEquals(1, inbox.getMessageCount());
            assertTrue(inbox.getFlags(1).contains(Flag.DELETED));
        } finally {
            inbox.close(false);
        }
    }

    @Test
    public void moveToMissingMailboxKeepsSource() throws Exception {
        Mailbox inbox = store.openMailbox("INBOX", false);
        try {
            try {
                store.moveMessages(inbox, numbers(1), "NoSuchBox");
                fail("expected IOException");
            } catch (IOException expected) {
                // TRYCREATE case
            }
            assertEquals(2, inbox.getMessageCount());
        } finally {
            inbox.close(false);
        }
    }
}
