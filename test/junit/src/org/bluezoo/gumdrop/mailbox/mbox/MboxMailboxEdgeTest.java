/*
 * StreamH2WebSocketUpgradeTest.java
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
import java.time.LocalDate;
import java.util.List;

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
 * Edge-case tests for {@link MboxMailbox} on an in-memory file system:
 * invalid and deleted message numbers, files with LF-only line endings or no
 * final newline, header-only messages, partial "From " prefixes in message
 * bodies, expunge variants and the search criteria that need the full
 * message context.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MboxMailboxEdgeTest {

    private Path dir;
    private Path file;

    @Before
    public void setUp() throws IOException {
        dir = MemoryFileSystem.create().getPath("/edge");
        Files.createDirectories(dir);
        file = dir.resolve("box.mbox");
    }

    @After
    public void tearDown() {
        MboxMailbox.beforeJvmGateAcquire = null;
    }

    private MboxMailbox open(String raw, boolean readOnly) throws IOException {
        Files.write(file, raw.getBytes(StandardCharsets.ISO_8859_1));
        return new MboxMailbox(file, "box", readOnly);
    }

    private static String read(ReadableByteChannel in) throws IOException {
        StringBuilder sb = new StringBuilder();
        ByteBuffer buf = ByteBuffer.allocate(7);
        try {
            while (in.read(buf) >= 0) {
                buf.flip();
                sb.append(StandardCharsets.ISO_8859_1.decode(buf));
                buf.clear();
            }
        } finally {
            in.close();
        }
        return sb.toString();
    }

    private static void append(Mailbox box, String text) throws IOException {
        box.startAppendMessage(null, null);
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        box.appendMessageContent(ByteBuffer.wrap(bytes));
        box.endAppendMessage();
    }

    private static final String TWO =
            "From a@x Mon Jan  1 00:00:00 2025\r\nSubject: one\r\n\r\nbody one\r\n\r\n"
            + "From b@x Tue Jan  2 00:00:00 2025\r\nSubject: two\r\n\r\nbody two\r\n\r\n";

    @Test
    public void invalidAndDeletedMessageNumbersAreRejected() throws IOException {
        MboxMailbox box = open(TWO, false);
        try {
            int[] bad = {0, 3, -1};
            for (int i = 0; i < bad.length; i++) {
                try {
                    box.getMessageContent(bad[i]);
                    fail("content of " + bad[i]);
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("Invalid message number"));
                }
                try {
                    box.openAsyncContent(bad[i]);
                    fail("async content of " + bad[i]);
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("Invalid message number"));
                }
                try {
                    box.getMessageTopEndOffset(bad[i], 0);
                    fail("top offset of " + bad[i]);
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("Invalid message number"));
                }
                try {
                    box.getUniqueId(bad[i]);
                    fail("unique id of " + bad[i]);
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("Invalid message number"));
                }
            }
            assertNull(box.getEmailId(99));
            box.deleteMessage(1);
            assertTrue(box.isDeleted(1));
            try {
                box.getMessageContent(1);
                fail("deleted message content");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("deleted"));
            }
            assertNull(box.getMessage(1));
            assertEquals(1, box.getMessageCount());
        } finally {
            box.close(false);
        }
    }

    @Test
    public void topOffsetOfHeaderOnlyMessageIsWholeMessage() throws IOException {
        MboxMailbox box = open("From a@x Mon Jan  1 00:00:00 2025\r\nSubject: only headers\r\n", false);
        try {
            assertEquals(1, box.getMessageCount());
            String content = read(box.getMessageContent(1));
            assertEquals("Subject: only headers", content);
            assertEquals(content.length(), box.getMessageTopEndOffset(1, 0));
            assertEquals(content.length(), box.getMessageTopEndOffset(1, 5));
        } finally {
            box.close(false);
        }
    }

    @Test
    public void topOffsetStopsAtRequestedBodyLinesOrMessageEnd() throws IOException {
        String raw = "From a@x Mon Jan  1 00:00:00 2025\r\nSubject: s\r\n\r\nl1\r\nl2\r\nl3 no newline";
        MboxMailbox box = open(raw, false);
        try {
            String content = read(box.getMessageContent(1));
            int headerEnd = content.indexOf("l1");
            assertEquals(headerEnd, box.getMessageTopEndOffset(1, 0));
            assertEquals(content.indexOf("l2"), box.getMessageTopEndOffset(1, 1));
            assertEquals(content.indexOf("l3"), box.getMessageTopEndOffset(1, 2));
            assertEquals(content.length(), box.getMessageTopEndOffset(1, 3));
            assertEquals(content.length(), box.getMessageTopEndOffset(1, 50));
            assertEquals(content.substring(0, content.indexOf("l3")), read(box.getMessageTop(1, 2)));
        } finally {
            box.close(false);
        }
    }

    @Test
    public void lfOnlyFileIsIndexedAndUnescaped() throws IOException {
        String raw = "From a@x Mon Jan  1 00:00:00 2025\nSubject: lf\n\n>From inside\nplain\n\n"
            + "From b@x Mon Jan  1 00:00:00 2025\nSubject: lf2\n\nbody\n\n";
        MboxMailbox box = open(raw, false);
        try {
            assertEquals(2, box.getMessageCount());
            assertEquals("Subject: lf\n\nFrom inside\nplain\n", read(box.getMessageContent(1)));
            assertEquals("Subject: lf2\n\nbody\n", read(box.getMessageContent(2)));
            assertEquals("Subject: lf\n\n", read(box.getMessageTop(1, 0)));
        } finally {
            box.close(false);
        }
    }

    @Test
    public void mixedLineEndingsFindTheBlankLine() throws IOException {
        String raw = "From a@x Mon Jan  1 00:00:00 2025\r\nSubject: m\n\r\nbody\r\n\r\n";
        MboxMailbox box = open(raw, true);
        try {
            assertEquals("Subject: m\n\r\n", read(box.getMessageTop(1, 0)));
        } finally {
            box.close(false);
        }
    }

    @Test
    public void appendCompletesAnUnterminatedLastLine() throws IOException {
        MboxMailbox box = open("From a@x Mon Jan  1 00:00:00 2025\r\nSubject: x\r\n\r\nno final newline", false);
        try {
            assertEquals(1, box.getMessageCount());
            append(box, "Subject: y\r\n\r\nz");
            assertEquals(2, box.getMessageCount());
            assertEquals("Subject: x\r\n\r\nno final newline", read(box.getMessageContent(1)));
            assertEquals("Subject: y\r\n\r\nz\r\n", read(box.getMessageContent(2)));
        } finally {
            box.close(false);
        }
    }

    @Test
    public void appendOfEmptyMessageYieldsABlankLineMessage() throws IOException {
        MboxMailbox box = open("", false);
        try {
            append(box, "");
            assertEquals(1, box.getMessageCount());
            assertEquals("\r\n", read(box.getMessageContent(1)));
        } finally {
            box.close(false);
        }
    }

    @Test
    public void partialFromPrefixesInBodiesAreNotEscaped() throws IOException {
        MboxMailbox box = open("", false);
        try {
            String message = "Subject: p\r\n\r\nFro\r\nFromage\r\nFrom\r\nFrom real\r\nfrom lower\r\n";
            append(box, message);
            append(box, "Subject: q\r\n\r\nafter\r\n");
            assertEquals(2, box.getMessageCount());
            assertEquals(message, read(box.getMessageContent(1)));
            String stored = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            assertTrue(stored.contains("\r\n>From real\r\n"));
            assertTrue(stored.contains("\r\nFromage\r\n"));
        } finally {
            box.close(false);
        }
    }

    @Test
    public void expungeWithNothingDeletedLeavesTheFileUntouched() throws IOException {
        MboxMailbox box = open(TWO, false);
        try {
            byte[] before = Files.readAllBytes(file);
            assertTrue(box.expunge().isEmpty());
            assertEquals(before.length, Files.readAllBytes(file).length);
            box.undeleteAll();
            assertEquals(2, box.getMessageCount());
        } finally {
            box.close(true);
        }
    }

    @Test
    public void expungeOnReadOnlyMailboxIsRefused() throws IOException {
        MboxMailbox box = open(TWO, true);
        try {
            try {
                box.expunge();
                fail("expunge on read-only mailbox");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("read-only"));
            }
            try {
                box.startAppendMessage(null, null);
                fail("append on read-only mailbox");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("read-only"));
            }
        } finally {
            box.close(true);
        }
    }

    @Test
    public void closeWithExpungeRemovesDeletedMessages() throws IOException {
        MboxMailbox box = open(TWO, false);
        box.deleteMessage(2);
        box.close(true);
        MboxMailbox again = new MboxMailbox(file, "box", true);
        try {
            assertEquals(1, again.getMessageCount());
            assertEquals("Subject: one\r\n\r\nbody one\r\n", read(again.getMessageContent(1)));
        } finally {
            again.close(false);
        }
    }

    @Test
    public void closeWithoutExpungeKeepsDeletedMessagesOnDisk() throws IOException {
        MboxMailbox box = open(TWO, false);
        box.deleteMessage(1);
        assertEquals(1, box.getMessageCount());
        assertTrue(box.getMailboxSize() > 0);
        box.close(false);
        MboxMailbox again = new MboxMailbox(file, "box", true);
        try {
            assertEquals(2, again.getMessageCount());
        } finally {
            again.close(false);
        }
    }

    @Test
    public void expungeReturnsSortedNumbersAndReindexes() throws IOException {
        String three = TWO
            + "From c@x Wed Jan  3 00:00:00 2025\r\nSubject: three\r\n\r\nbody three\r\n\r\n";
        MboxMailbox box = open(three, false);
        try {
            box.deleteMessage(3);
            box.deleteMessage(1);
            List<Integer> gone = box.expunge();
            assertEquals(2, gone.size());
            assertEquals(1, gone.get(0).intValue());
            assertEquals(3, gone.get(1).intValue());
            assertEquals(1, box.getMessageCount());
            assertEquals("Subject: two\r\n\r\nbody two\r\n", read(box.getMessageContent(1)));
            append(box, "Subject: four\r\n\r\nbody four\r\n");
            assertEquals(2, box.getMessageCount());
        } finally {
            box.close(false);
        }
    }

    @Test
    public void searchesNeedingTheFullMessageContext() throws IOException {
        String raw = "From a@x Mon Jan  1 00:00:00 2025\r\nFrom: alice@x\r\nTo: bob@x\r\nCc: cc@x\r\n"
            + "Date: Mon, 1 Jan 2024 10:00:00 +0000\r\nSubject: hello world\r\nX-Tag: red\r\n\r\n"
            + "the body text\r\n\r\n"
            + "From b@x Tue Jan  2 00:00:00 2025\r\nFrom: carol@x\r\nSubject: other\r\n\r\nnothing\r\n\r\n";
        MboxMailbox box = open(raw, false);
        try {
            assertEquals(1, box.search(SearchCriteria.body("body text")).size());
            assertEquals(1, box.search(SearchCriteria.text("hello")).size());
            assertEquals(1, box.search(SearchCriteria.header("X-Tag", "red")).size());
            assertEquals(1, box.search(SearchCriteria.text("x-tag: red")).size());
            assertEquals(0, box.search(SearchCriteria.header("X-Tag", "blue")).size());
            assertEquals(1, box.search(SearchCriteria.cc("cc@x")).size());
            assertEquals(1, box.search(SearchCriteria.sentOn(LocalDate.of(2024, 1, 1))).size());
            assertEquals(1, box.search(SearchCriteria.sentSince(LocalDate.of(2024, 1, 1))).size());
            assertEquals(0, box.search(SearchCriteria.sentBefore(LocalDate.of(2024, 1, 1))).size());
            assertEquals(0, box.search(SearchCriteria.keyword("nothing")).size());
            assertEquals(2, box.search(SearchCriteria.unkeyword("nothing")).size());
            assertEquals(2, box.search(SearchCriteria.smaller(100000L)).size());
            assertEquals(2, box.search(SearchCriteria.unseen()).size());
            assertEquals(0, box.search(SearchCriteria.seen()).size());
            assertEquals(1, box.search(SearchCriteria.uidRange(2L, 5L)).size());
            assertEquals(2, box.search(SearchCriteria.sequenceRange(1, 2)).size());
        } finally {
            box.close(false);
        }
    }

    @Test
    public void searchAfterCloseUsesTheDefaultImplementation() throws IOException {
        MboxMailbox box = open(TWO, false);
        box.close(false);
        assertNull(box.getEmailId(1));
    }

    @Test
    public void unreadableWarmGateIsSkippedOnlyForTheIndexerThread() throws IOException {
        MboxMailbox first = open(TWO, false);
        try {
            assertNotNull(first.getMailboxId());
            assertFalse(first.isReadOnly());
            assertEquals("box", first.getName());
        } finally {
            first.close(false);
        }
    }
}
