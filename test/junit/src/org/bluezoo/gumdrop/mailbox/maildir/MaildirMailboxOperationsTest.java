/*
 * MaildirMailboxOperationsTest.java
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
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
 * Operation-level tests for {@link MaildirMailbox} on an in-memory file
 * system: flags, expunge, MODSEQ sidecars, append state errors, search and
 * reopen behaviour.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirMailboxOperationsTest {

    private static final String M1 =
            "From: alice@example.com\r\nTo: bob@example.com\r\n"
            + "Subject: first\r\nDate: Mon, 02 Jan 2023 10:00:00 +0000\r\n"
            + "\r\nline one\r\nline two\r\nline three\r\n";
    private static final String M2 =
            "From: carol@example.com\r\nSubject: second\r\n\r\nsecond body\r\n";
    private static final String M3 = "Subject: nobody\n\nunix body\n";

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
        if (inbox != null) {
            inbox.close(false);
        }
        store.close();
    }

    private Path maildir() {
        return root.resolve("alice");
    }

    private void append(Mailbox box, String text, Set<Flag> flags) throws IOException {
        box.startAppendMessage(flags, null);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        box.appendMessageContent(buf);
        box.endAppendMessage();
    }

    private void appendAll() throws IOException {
        append(inbox, M1, null);
        append(inbox, M2, EnumSet.of(Flag.SEEN));
        append(inbox, M3, EnumSet.of(Flag.FLAGGED));
    }

    private static String readAll(ReadableByteChannel in) throws IOException {
        StringBuilder sb = new StringBuilder();
        ByteBuffer buf = ByteBuffer.allocate(32);
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
    public void testTopWithBodyLines() throws IOException {
        appendAll();
        String top0 = readAll(inbox.getMessageTop(1, 0));
        assertEquals("From: alice@example.com\r\nTo: bob@example.com\r\n"
                + "Subject: first\r\nDate: Mon, 02 Jan 2023 10:00:00 +0000\r\n\r\n", top0);
        String top2 = readAll(inbox.getMessageTop(1, 2));
        assertTrue(top2.endsWith("line one\r\nline two\r\n"));
        String topAll = readAll(inbox.getMessageTop(1, 50));
        assertEquals(M1, topAll);
        long end = inbox.getMessageTopEndOffset(1, 1);
        assertEquals(M1.indexOf("line two"), end);
        String unix = readAll(inbox.getMessageTop(3, 1));
        assertEquals(M3, unix);
    }

    @Test
    public void testMissingMessageErrors() throws IOException {
        appendAll();
        try {
            inbox.getMessageContent(99);
            fail("content");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("99"));
        }
        try {
            inbox.getMessageTop(99, 1);
            fail("top");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            inbox.getMessageTopEndOffset(99, 1);
            fail("topEnd");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            inbox.getMessagePath(99);
            fail("path");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            inbox.getUniqueId(99);
            fail("uid");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            inbox.openAsyncContent(99);
            fail("async");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        assertTrue(inbox.getFlags(99).isEmpty());
        assertEquals(0L, inbox.getModSeq(99));
        assertNull(inbox.getEmailId(99));
        assertNull(inbox.getMessage(99));
    }

    @Test
    public void testPathAndUniqueIdAndGetters() throws IOException {
        appendAll();
        Path p = inbox.getMessagePath(2);
        assertTrue(Files.exists(p));
        assertEquals("1", inbox.getUniqueId(1));
        assertEquals("INBOX", inbox.getName());
        assertFalse(inbox.isReadOnly());
        assertEquals(3, inbox.getMessageCount());
        assertEquals(M1.length() + M2.length() + M3.length(), inbox.getMailboxSize());
        assertEquals(4L, inbox.getUidNext());
        assertTrue(inbox.getUidValidity() > 0);
        assertNotNull(inbox.getMailboxId());
        assertFalse(inbox.getPermanentFlags().isEmpty());
        MaildirMailbox mm = (MaildirMailbox) inbox;
        assertEquals(maildir(), mm.getMaildirPath());
        assertNotNull(mm.getKeywords());
        Iterator<MessageDescriptor> it = inbox.getMessageList();
        int n = 0;
        while (it.hasNext()) {
            it.next();
            n++;
        }
        assertEquals(3, n);
    }

    @Test
    public void testFlagOperationsAndModSeq() throws IOException {
        appendAll();
        long before = inbox.getHighestModSeq();
        assertEquals(3L, before);
        inbox.setFlags(1, EnumSet.of(Flag.SEEN, Flag.ANSWERED), true);
        Set<Flag> flags = inbox.getFlags(1);
        assertTrue(flags.contains(Flag.SEEN));
        assertTrue(flags.contains(Flag.ANSWERED));
        assertEquals(4L, inbox.getHighestModSeq());
        assertEquals(4L, inbox.getModSeq(1));
        inbox.setFlags(1, EnumSet.of(Flag.SEEN, Flag.ANSWERED), true);
        assertEquals("no change, no new modseq", 4L, inbox.getHighestModSeq());
        inbox.setFlags(1, EnumSet.of(Flag.ANSWERED), false);
        assertFalse(inbox.getFlags(1).contains(Flag.ANSWERED));
        inbox.replaceFlags(2, EnumSet.of(Flag.DRAFT));
        Set<Flag> f2 = inbox.getFlags(2);
        assertEquals(EnumSet.of(Flag.DRAFT), f2);
        inbox.setFlags(77, EnumSet.of(Flag.SEEN), true);
        inbox.replaceFlags(77, EnumSet.of(Flag.SEEN));
        List<Long> changed = inbox.getChangedSince(3L);
        assertTrue(changed.contains(Long.valueOf(1L)));
        assertTrue(changed.contains(Long.valueOf(2L)));
        assertFalse(changed.contains(Long.valueOf(3L)));
    }

    @Test
    public void testDeleteMarksAndExpunge() throws IOException {
        appendAll();
        inbox.deleteMessage(0);
        inbox.deleteMessage(9);
        assertFalse(inbox.isDeleted(0));
        assertFalse(inbox.isDeleted(9));
        inbox.deleteMessage(2);
        assertTrue(inbox.isDeleted(2));
        inbox.undeleteAll();
        assertFalse(inbox.isDeleted(2));
        inbox.deleteMessage(2);
        inbox.setFlags(3, EnumSet.of(Flag.DELETED), true);
        List<Integer> gone = inbox.expunge();
        assertEquals(Arrays.asList(Integer.valueOf(2), Integer.valueOf(3)), gone);
        assertEquals(1, inbox.getMessageCount());
        List<Long> expunged = inbox.getExpungedSince(0L);
        assertEquals(2, expunged.size());
        assertTrue(inbox.expunge().isEmpty());
    }

    @Test
    public void testExpungeSpecificMessagesRenumbers() throws IOException {
        appendAll();
        List<Integer> only = new ArrayList<Integer>();
        only.add(Integer.valueOf(1));
        List<Integer> gone = inbox.expungeMessages(only);
        assertEquals(1, gone.size());
        assertEquals(2, inbox.getMessageCount());
        assertEquals("2", inbox.getUniqueId(1));
        assertEquals(M2, readAll(inbox.getMessageContent(1)));
        List<Integer> search = inbox.search(SearchCriteria.all());
        assertEquals(2, search.size());
    }

    @Test
    public void testCloseWithExpungeAndReopenKeepsSidecars() throws IOException {
        appendAll();
        inbox.deleteMessage(1);
        inbox.close(true);
        inbox = store.openMailbox("INBOX", false);
        assertEquals(2, inbox.getMessageCount());
        assertEquals(1, inbox.getExpungedSince(0L).size());
        assertTrue(inbox.getHighestModSeq() >= 3L);
        assertEquals(4L, inbox.getUidNext());
        List<Integer> hits = inbox.search(SearchCriteria.subject("second"));
        assertEquals(1, hits.size());
    }

    @Test
    public void testReadOnlyMailboxRefusesMutation() throws IOException {
        appendAll();
        Mailbox ro = reopen(true);
        assertTrue(ro.isReadOnly());
        try {
            ro.setFlags(1, EnumSet.of(Flag.SEEN), true);
            fail("setFlags");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("read-only"));
        }
        try {
            ro.replaceFlags(1, EnumSet.of(Flag.SEEN));
            fail("replaceFlags");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("read-only"));
        }
        try {
            ro.startAppendMessage(null, null);
            fail("append");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("read-only"));
        }
        try {
            ro.openAsyncAppend(null, null);
            fail("asyncAppend");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("read-only"));
        }
        try {
            ro.expunge();
            fail("expunge");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("read-only"));
        }
        try {
            ro.expungeMessages(new ArrayList<Integer>());
            fail("expungeMessages");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("read-only"));
        }
        assertEquals(3, ro.getMessageCount());
    }

    @Test
    public void testAppendStateErrors() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(new byte[] {1});
        try {
            inbox.appendMessageContent(buf);
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
            assertTrue(expected.getMessage().contains("in progress"));
        }
    }

    @Test
    public void testCloseAbortsPartialAppend() throws IOException {
        inbox.startAppendMessage(null, null);
        ByteBuffer buf = ByteBuffer.wrap("partial".getBytes(StandardCharsets.US_ASCII));
        inbox.appendMessageContent(buf);
        inbox.close(false);
        inbox = null;
        List<Path> left = new ArrayList<Path>();
        java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(maildir().resolve("tmp"));
        try {
            for (Path p : ds) {
                left.add(p);
            }
        } finally {
            ds.close();
        }
        assertTrue("temp file removed", left.isEmpty());
        inbox = store.openMailbox("INBOX", false);
        assertEquals(0, inbox.getMessageCount());
    }

    @Test
    public void testAppendWithInternalDateIndexesIt() throws IOException {
        inbox.startAppendMessage(null, java.time.OffsetDateTime.parse("2020-05-06T07:08:09Z"));
        ByteBuffer buf = ByteBuffer.wrap(M2.getBytes(StandardCharsets.US_ASCII));
        inbox.appendMessageContent(buf);
        inbox.endAppendMessage();
        List<Integer> hits = inbox.search(SearchCriteria.on(LocalDate.of(2020, 5, 6)));
        assertEquals(1, hits.size());
        hits = inbox.search(SearchCriteria.before(LocalDate.of(2020, 5, 6)));
        assertTrue(hits.isEmpty());
    }

    @Test
    public void testSearchCriteriaAgainstIndex() throws IOException {
        appendAll();
        assertEquals(Arrays.asList(Integer.valueOf(2)), inbox.search(SearchCriteria.seen()));
        assertEquals(Arrays.asList(Integer.valueOf(3)), inbox.search(SearchCriteria.flagged()));
        assertEquals(2, inbox.search(SearchCriteria.unseen()).size());
        assertEquals(1, inbox.search(SearchCriteria.from("carol")).size());
        assertEquals(1, inbox.search(SearchCriteria.to("bob")).size());
        assertEquals(1, inbox.search(SearchCriteria.body("second body")).size());
        assertEquals(1, inbox.search(SearchCriteria.text("second body")).size());
        assertEquals(1, inbox.search(SearchCriteria.header("Subject", "nobody")).size());
        assertEquals(2, inbox.search(SearchCriteria.not(SearchCriteria.flagged())).size());
        SearchCriteria either = SearchCriteria.or(SearchCriteria.seen(), SearchCriteria.flagged());
        assertEquals(2, inbox.search(either).size());
        SearchCriteria both = SearchCriteria.and(SearchCriteria.unseen(),
                SearchCriteria.larger(10L));
        assertEquals(2, inbox.search(both).size());
        assertEquals(1, inbox.search(SearchCriteria.uid(2L)).size());
        String id = inbox.getEmailId(2);
        assertEquals(Arrays.asList(Integer.valueOf(2)), inbox.search(SearchCriteria.emailId(id)));
        assertEquals(1, inbox.search(SearchCriteria.sequenceNumber(3)).size());
        assertEquals(3, inbox.search(SearchCriteria.smaller(100000L)).size());
        inbox.deleteMessage(1);
        assertEquals(2, inbox.search(SearchCriteria.all()).size());
    }

    @Test
    public void testEmailIdFromIndexAndStableAcrossReopen() throws IOException {
        appendAll();
        String id = inbox.getEmailId(1);
        assertNotNull(id);
        Mailbox again = reopen(false);
        assertEquals(id, again.getEmailId(1));
        assertEquals(3, again.search(SearchCriteria.all()).size());
    }

    @Test
    public void testCorruptSearchIndexIsRebuilt() throws IOException {
        appendAll();
        inbox.close(false);
        Path idx = maildir().resolve(".gidx");
        assertTrue(Files.exists(idx));
        Files.write(idx, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        inbox = store.openMailbox("INBOX", false);
        assertEquals(3, inbox.search(SearchCriteria.all()).size());
    }

    @Test
    public void testInconsistentSearchIndexIsRebuilt() throws IOException {
        appendAll();
        inbox.close(false);
        Path cur = maildir().resolve("cur");
        List<Path> files = new ArrayList<Path>();
        java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(cur);
        try {
            for (Path p : ds) {
                files.add(p);
            }
        } finally {
            ds.close();
        }
        Files.delete(files.get(0));
        Files.delete(files.get(1));
        inbox = store.openMailbox("INBOX", false);
        assertEquals(1, inbox.getMessageCount());
        assertEquals(1, inbox.search(SearchCriteria.all()).size());
    }

    @Test
    public void testCorruptSidecarsAreIgnored() throws IOException {
        appendAll();
        inbox.close(false);
        Files.write(maildir().resolve(".modseq"), Arrays.asList("HIGHEST notanumber"));
        Files.write(maildir().resolve(".expunged"), Arrays.asList("1 x", "", "novalue"));
        inbox = store.openMailbox("INBOX", false);
        assertEquals(0L, inbox.getHighestModSeq());
        assertTrue(inbox.getExpungedSince(0L).isEmpty());
        assertEquals(3, inbox.getMessageCount());
    }

    @Test
    public void testModSeqSidecarParsesBlankAndMalformedLines() throws IOException {
        appendAll();
        inbox.close(false);
        Files.write(maildir().resolve(".modseq"),
                Arrays.asList("HIGHEST 42", "", "nospace", "1 40"));
        Files.write(maildir().resolve(".expunged"), Arrays.asList("", "nospace", "9 41"));
        inbox = store.openMailbox("INBOX", false);
        assertEquals(42L, inbox.getHighestModSeq());
        assertEquals(40L, inbox.getModSeq(1));
        assertEquals(Arrays.asList(Long.valueOf(9L)), inbox.getExpungedSince(40L));
    }

    @Test
    public void testScanSkipsHiddenInvalidAndNonRegularEntries() throws IOException {
        inbox.close(false);
        Path cur = maildir().resolve("cur");
        Files.write(cur.resolve(".hidden"), new byte[] {65});
        Files.write(cur.resolve("garbage-no-dot"), new byte[] {65});
        Files.write(cur.resolve("abc.def"), new byte[] {65});
        Files.createDirectories(cur.resolve("subdir"));
        Files.write(cur.resolve("1700000000.1.host,S=1:2,S"), new byte[] {65});
        inbox = store.openMailbox("INBOX", false);
        assertEquals(1, inbox.getMessageCount());
        assertTrue(inbox.getFlags(1).contains(Flag.SEEN));
    }

    @Test
    public void testNewDirectoryMessagesMoveToCur() throws IOException {
        inbox.close(false);
        Path nw = maildir().resolve("new");
        Files.write(nw.resolve("1700000001.9.host"), "Subject: n\r\n\r\nx\r\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(nw.resolve(".skipme"), new byte[] {65});
        Files.createDirectories(nw.resolve("dir"));
        inbox = store.openMailbox("INBOX", false);
        assertEquals(1, inbox.getMessageCount());
        assertTrue(Files.exists(nw.resolve(".skipme")));
        Mailbox ro = reopen(true);
        assertEquals(1, ro.getMessageCount());
    }

    @Test
    public void testBodyOffsetDetection() throws IOException {
        Path f = maildir().resolve("body.txt");
        Files.write(f, "A: b\r\n\r\nrest".getBytes(StandardCharsets.US_ASCII));
        assertEquals(8L, MaildirMailbox.detectBodyOffset(f));
        Files.write(f, "A: b\n\nrest".getBytes(StandardCharsets.US_ASCII));
        assertEquals(6L, MaildirMailbox.detectBodyOffset(f));
        Files.write(f, "A: b\r\nC: d\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals(-1L, MaildirMailbox.detectBodyOffset(f));
        Files.write(f, new byte[0]);
        assertEquals(-1L, MaildirMailbox.detectBodyOffset(f));
        assertEquals(-1L, MaildirMailbox.detectBodyOffset(maildir().resolve("absent.txt")));
    }
}
