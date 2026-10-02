/*
 * MboxMailboxOperationsTest.java
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

package org.bluezoo.gumdrop.mailbox.mbox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Operation-level tests for {@link MboxMailbox} on an in-memory file system:
 * append escaping, search, index reload, expunge persistence and read-only
 * behaviour.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MboxMailboxOperationsTest {

    private static final String M1 =
            "From: alice@example.com\r\nTo: bob@example.com\r\nSubject: first\r\n"
            + "\r\nline one\r\nFrom the start of a line\r\nline three\r\n";
    private static final String M2 =
            "From: carol@example.com\r\nSubject: second\r\n\r\nsecond body\r\n";
    private static final String M3 = "Subject: third\r\n\r\nthird body\r\n";

    private Path root;
    private MboxMailboxStore store;
    private Mailbox inbox;

    @Before
    public void setUp() throws IOException {
        MemoryFileSystem mem = MemoryFileSystem.create();
        root = mem.getPath("/mail");
        Files.createDirectories(root);
        store = new MboxMailboxStore(root);
        store.open("bob");
        inbox = store.openMailbox("INBOX", false);
    }

    @After
    public void tearDown() throws IOException {
        if (inbox != null) {
            inbox.close(false);
        }
        store.close();
    }

    private void append(String text) throws IOException {
        inbox.startAppendMessage(null, null);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        inbox.appendMessageContent(buf);
        inbox.endAppendMessage();
    }

    private void appendAll() throws IOException {
        append(M1);
        append(M2);
        append(M3);
    }

    private static String readAll(ReadableByteChannel in) throws IOException {
        StringBuilder sb = new StringBuilder();
        ByteBuffer buf = ByteBuffer.allocate(16);
        try {
            while (in.read(buf) >= 0) {
                buf.flip();
                CharSequence cs = StandardCharsets.US_ASCII.decode(buf);
                sb.append(cs);
                buf.clear();
            }
        } finally {
            in.close();
        }
        return sb.toString();
    }

    private Mailbox reopen(boolean readOnly) throws IOException {
        inbox.close(false);
        inbox = store.openMailbox("INBOX", readOnly);
        return inbox;
    }

    @Test
    public void testAppendedMessagesRoundTripWithFromEscaping() throws IOException {
        appendAll();
        assertEquals(3, inbox.getMessageCount());
        assertEquals(M1, readAll(inbox.getMessageContent(1)));
        assertEquals(M2, readAll(inbox.getMessageContent(2)));
        Mailbox again = reopen(false);
        assertEquals(3, again.getMessageCount());
        assertEquals(M1, readAll(again.getMessageContent(1)));
        assertEquals(M3, readAll(again.getMessageContent(3)));
    }

    @Test
    public void testTopVariants() throws IOException {
        appendAll();
        String headersOnly = readAll(inbox.getMessageTop(1, 0));
        assertEquals(M1.substring(0, M1.indexOf("line one")), headersOnly);
        String two = readAll(inbox.getMessageTop(1, 2));
        assertEquals(M1.substring(0, M1.indexOf("line three")), two);
        assertEquals(M1, readAll(inbox.getMessageTop(1, 99)));
        long end = inbox.getMessageTopEndOffset(1, 0);
        assertTrue(end > 0);
    }

    @Test
    public void testMissingMessageAccessors() throws IOException {
        appendAll();
        assertNull(inbox.getMessage(0));
        assertNull(inbox.getMessage(9));
        try {
            inbox.getMessageContent(9);
            fail("content");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            inbox.getMessageTop(9, 1);
            fail("top");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            inbox.getUniqueId(9);
            fail("uid");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        assertNull(inbox.getEmailId(9));
        assertFalse(inbox.isDeleted(0));
        assertFalse(inbox.isDeleted(9));
        try {
            inbox.deleteMessage(0);
            fail("delete 0");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("0"));
        }
        try {
            inbox.deleteMessage(9);
            fail("delete 9");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("9"));
        }
    }

    @Test
    public void testGettersAndAppendStateErrors() throws IOException {
        appendAll();
        assertEquals("INBOX", inbox.getName());
        assertFalse(inbox.isReadOnly());
        assertTrue(inbox.getMailboxSize() > 0);
        assertNotNull(inbox.getMailboxId());
        assertFalse(inbox.getUniqueId(1).isEmpty());
        assertFalse(inbox.getUniqueId(1).equals(inbox.getUniqueId(2)));
        try {
            inbox.appendMessageContent(ByteBuffer.wrap(new byte[] {1}));
            fail("no append");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            inbox.endAppendMessage();
            fail("no append");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
        inbox.startAppendMessage(null, null);
        try {
            inbox.startAppendMessage(null, null);
            fail("double start");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testSearchCriteria() throws IOException {
        appendAll();
        assertEquals(3, inbox.search(SearchCriteria.all()).size());
        assertEquals(1, inbox.search(SearchCriteria.from("carol")).size());
        assertEquals(1, inbox.search(SearchCriteria.to("bob")).size());
        assertEquals(1, inbox.search(SearchCriteria.subject("third")).size());
        assertEquals(1, inbox.search(SearchCriteria.body("second body")).size());
        assertEquals(1, inbox.search(SearchCriteria.text("third body")).size());
        assertEquals(2, inbox.search(SearchCriteria.not(SearchCriteria.from("alice"))).size());
        SearchCriteria either = SearchCriteria.or(SearchCriteria.from("alice"), SearchCriteria.from("carol"));
        assertEquals(2, inbox.search(either).size());
        assertEquals(1, inbox.search(SearchCriteria.uid(2L)).size());
        String id = inbox.getEmailId(2);
        assertEquals(Arrays.asList(Integer.valueOf(2)), inbox.search(SearchCriteria.emailId(id)));
        assertEquals(Arrays.asList(Integer.valueOf(3)), inbox.search(SearchCriteria.sequenceNumber(3)));
        assertEquals(3, inbox.search(SearchCriteria.larger(5L)).size());
        inbox.deleteMessage(2);
        assertEquals(2, inbox.search(SearchCriteria.all()).size());
    }

    @Test
    public void testEmailIdStableAcrossReopenAndIndexReload() throws IOException {
        appendAll();
        String id = inbox.getEmailId(2);
        assertNotNull(id);
        Mailbox again = reopen(false);
        assertEquals(id, again.getEmailId(2));
        assertEquals(1, again.search(SearchCriteria.from("carol")).size());
    }

    @Test
    public void testCorruptIndexIsRebuilt() throws IOException {
        appendAll();
        inbox.close(false);
        Path dir = root.resolve("bob");
        java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir);
        int corrupted = 0;
        try {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.endsWith(".gidx")) {
                    Files.write(p, new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
                    corrupted++;
                }
            }
        } finally {
            ds.close();
        }
        assertTrue("an index file was written", corrupted > 0);
        inbox = store.openMailbox("INBOX", false);
        assertEquals(3, inbox.search(SearchCriteria.all()).size());
    }

    @Test
    public void testDeleteUndeleteAndExpungePersist() throws IOException {
        appendAll();
        inbox.deleteMessage(1);
        assertTrue(inbox.isDeleted(1));
        inbox.undeleteAll();
        assertFalse(inbox.isDeleted(1));
        inbox.deleteMessage(1);
        inbox.deleteMessage(3);
        List<Integer> gone = inbox.expunge();
        assertEquals(Arrays.asList(Integer.valueOf(1), Integer.valueOf(3)), gone);
        assertEquals(1, inbox.getMessageCount());
        Mailbox again = reopen(false);
        assertEquals(1, again.getMessageCount());
        assertEquals(M2, readAll(again.getMessageContent(1)));
        again.deleteMessage(1);
        again.close(true);
        inbox = store.openMailbox("INBOX", false);
        assertEquals(0, inbox.getMessageCount());
    }

    @Test
    public void testReadOnlyMailbox() throws IOException {
        appendAll();
        Mailbox ro = reopen(true);
        assertTrue(ro.isReadOnly());
        assertEquals(3, ro.getMessageCount());
        try {
            ro.startAppendMessage(null, null);
            fail("append");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            ro.expunge();
            fail("expunge");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            ro.deleteMessage(1);
            fail("delete");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("read-only"));
        }
        assertFalse(ro.isDeleted(1));
    }

    @Test
    public void testReadOnlyOpenOfMissingFileFails() throws IOException {
        try {
            MboxMailbox missing = new MboxMailbox(root.resolve("absent.mbox"), "absent", true);
            missing.close(false);
            fail("missing file");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("does not exist"));
        }
    }

    @Test
    public void testCreateMissingFileWhenWritable() throws IOException {
        Path p = root.resolve("fresh.mbox");
        MboxMailbox box = new MboxMailbox(p, "fresh", false);
        assertEquals(0, box.getMessageCount());
        box.close(false);
        assertTrue(Files.exists(p));
    }

    @Test
    public void testFlagsAreUnsupported() throws IOException {
        appendAll();
        try {
            inbox.setFlags(1, EnumSet.of(Flag.SEEN), true);
            fail("flags are not supported by mbox");
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            inbox.replaceFlags(1, EnumSet.of(Flag.SEEN));
            fail("flags are not supported by mbox");
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected.getMessage());
        }
        assertNotNull(inbox.getPermanentFlags());
    }
}
