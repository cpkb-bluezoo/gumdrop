package org.bluezoo.gumdrop.mailbox.mbox;

import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MboxMailbox}.
 */
public class MboxMailboxTest {

    private Path tempDir;
    private Path mboxFile;

    private static final String SAMPLE_MBOX =
            "From sender@example.com Mon Jan  1 00:00:00 2025\r\n" +
            "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: First message\r\n" +
            "\r\n" +
            "Hello, this is message one.\r\n" +
            "\r\n" +
            "From another@example.com Tue Jan  2 00:00:00 2025\r\n" +
            "From: another@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: Second message\r\n" +
            "\r\n" +
            "Hello, this is message two.\r\n";

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("mboxtest");
        mboxFile = tempDir.resolve("test.mbox");
    }

    @After
    public void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException e) { /* ignore */ }
                });
    }

    private MboxMailbox openSampleMailbox(boolean readOnly) throws IOException {
        Files.write(mboxFile, SAMPLE_MBOX.getBytes(StandardCharsets.US_ASCII));
        return new MboxMailbox(mboxFile, "test", readOnly);
    }

    private String readChannel(ReadableByteChannel ch) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8192);
        StringBuilder sb = new StringBuilder();
        while (ch.read(buf) > 0) {
            buf.flip();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            sb.append(new String(data, StandardCharsets.US_ASCII));
            buf.clear();
        }
        ch.close();
        return sb.toString();
    }

    @Test
    public void testOpenAndBasicProperties() throws IOException {
        MboxMailbox mbox = openSampleMailbox(true);
        try {
            assertEquals("test", mbox.getName());
            assertTrue(mbox.isReadOnly());
            assertEquals(2, mbox.getMessageCount());
            assertTrue(mbox.getMailboxSize() > 0);
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testGetMessage() throws IOException {
        MboxMailbox mbox = openSampleMailbox(true);
        try {
            MessageDescriptor msg1 = mbox.getMessage(1);
            assertNotNull(msg1);
            assertEquals(1, msg1.getMessageNumber());
            assertTrue(msg1.getSize() > 0);

            MessageDescriptor msg2 = mbox.getMessage(2);
            assertNotNull(msg2);
            assertEquals(2, msg2.getMessageNumber());
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testGetMessageOutOfRange() throws IOException {
        MboxMailbox mbox = openSampleMailbox(true);
        try {
            assertNull(mbox.getMessage(0));
            assertNull(mbox.getMessage(3));
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testGetMessageContent() throws IOException {
        MboxMailbox mbox = openSampleMailbox(true);
        try {
            String content = readChannel(mbox.getMessageContent(1));
            assertTrue(content.contains("From: sender@example.com"));
            assertTrue(content.contains("Subject: First message"));
            assertTrue(content.contains("Hello, this is message one."));
            assertFalse(content.startsWith("From "));
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testGetMessageList() throws IOException {
        MboxMailbox mbox = openSampleMailbox(true);
        try {
            Iterator<MessageDescriptor> it = mbox.getMessageList();
            assertTrue(it.hasNext());
            assertEquals(1, it.next().getMessageNumber());
            assertTrue(it.hasNext());
            assertEquals(2, it.next().getMessageNumber());
            assertFalse(it.hasNext());
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testDeleteAndUndelete() throws IOException {
        MboxMailbox mbox = openSampleMailbox(false);
        try {
            assertFalse(mbox.isDeleted(1));
            mbox.deleteMessage(1);
            assertTrue(mbox.isDeleted(1));
            assertEquals(1, mbox.getMessageCount());

            mbox.undeleteAll();
            assertFalse(mbox.isDeleted(1));
            assertEquals(2, mbox.getMessageCount());
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testDeletedMessageExcludedFromList() throws IOException {
        MboxMailbox mbox = openSampleMailbox(false);
        try {
            mbox.deleteMessage(1);
            Iterator<MessageDescriptor> it = mbox.getMessageList();
            assertTrue(it.hasNext());
            assertEquals(2, it.next().getMessageNumber());
            assertFalse(it.hasNext());
        } finally {
            mbox.close(false);
        }
    }

    @Test(expected = IOException.class)
    public void testDeleteOnReadOnly() throws IOException {
        MboxMailbox mbox = openSampleMailbox(true);
        try {
            mbox.deleteMessage(1);
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testExpunge() throws IOException {
        MboxMailbox mbox = openSampleMailbox(false);
        mbox.deleteMessage(1);
        List<Integer> expunged = mbox.expunge();
        assertEquals(1, expunged.size());
        assertEquals(Integer.valueOf(1), expunged.get(0));
        mbox.close(false);

        // Reopen to verify the file was rewritten correctly
        MboxMailbox reopened = new MboxMailbox(mboxFile, "test", true);
        try {
            assertEquals(1, reopened.getMessageCount());
            String content = readChannel(reopened.getMessageContent(1));
            assertTrue(content.contains("Subject: Second message"));
        } finally {
            reopened.close(false);
        }
    }

    @Test
    public void testGetUniqueId() throws IOException {
        MboxMailbox mbox = openSampleMailbox(true);
        try {
            String uid1 = mbox.getUniqueId(1);
            String uid2 = mbox.getUniqueId(2);
            assertNotNull(uid1);
            assertNotNull(uid2);
            assertNotEquals(uid1, uid2);
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testAppendMessage() throws IOException {
        MboxMailbox mbox = openSampleMailbox(false);
        try {
            int before = mbox.getMessageCount();
            String newMsg = "From: new@example.com\r\nSubject: New\r\n\r\nNew body.\r\n";
            mbox.startAppendMessage(null, null);
            mbox.appendMessageContent(ByteBuffer.wrap(newMsg.getBytes(StandardCharsets.US_ASCII)));
            mbox.endAppendMessage();

            assertEquals(before + 1, mbox.getMessageCount());

            String content = readChannel(mbox.getMessageContent(before + 1));
            assertTrue(content.contains("Subject: New"));
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testOpenEmptyFile() throws IOException {
        Files.createFile(mboxFile);
        MboxMailbox mbox = new MboxMailbox(mboxFile, "empty", false);
        try {
            assertEquals(0, mbox.getMessageCount());
            assertEquals(0, mbox.getMailboxSize());
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testGetMessageTop() throws IOException {
        MboxMailbox mbox = openSampleMailbox(true);
        try {
            String top = readChannel(mbox.getMessageTop(1, 0));
            assertTrue(top.contains("From: sender@example.com"));
            assertTrue(top.contains("Subject: First message"));
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testFromLineEscaping() throws IOException {
        String mboxWithFrom =
                "From sender@example.com Mon Jan  1 00:00:00 2025\r\n" +
                "From: sender@example.com\r\n" +
                "Subject: Test\r\n" +
                "\r\n" +
                "Line one.\r\n" +
                ">From someone in the body.\r\n" +
                "Line three.\r\n";
        Files.write(mboxFile, mboxWithFrom.getBytes(StandardCharsets.US_ASCII));
        MboxMailbox mbox = new MboxMailbox(mboxFile, "test", true);
        try {
            String content = readChannel(mbox.getMessageContent(1));
            assertTrue(content.contains("From someone in the body."));
            assertFalse(content.contains(">From someone"));
        } finally {
            mbox.close(false);
        }
    }

    // Regression tests for the bulk-span rewrite of unescapeFromLines: byte
    // offsets are trickier to get right when substitutions are combined
    // with a running "unwritten span" instead of a per-byte copy loop, so
    // these check exact byte-for-byte output rather than substring presence.

    @Test
    public void testUnescapeExactOutputWithNoEscapedLines() throws IOException {
        String body = "Line one.\r\nLine two.\r\nLine three.\r\n";
        MboxMailbox mbox = openMailboxWithSingleMessageBody(body);
        try {
            assertEquals(headerThenBody(body), readChannel(mbox.getMessageContent(1)));
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testUnescapeConsecutiveEscapedLines() throws IOException {
        String body = ">From one\r\n>From two\r\n>From three\r\n";
        String expected = "From one\r\nFrom two\r\nFrom three\r\n";
        MboxMailbox mbox = openMailboxWithSingleMessageBody(body);
        try {
            assertEquals(headerThenBody(expected), readChannel(mbox.getMessageContent(1)));
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testUnescapeEscapedLineAtVeryStartOfBody() throws IOException {
        String body = ">From immediately at body start\r\nrest\r\n";
        String expected = "From immediately at body start\r\nrest\r\n";
        MboxMailbox mbox = openMailboxWithSingleMessageBody(body);
        try {
            assertEquals(headerThenBody(expected), readChannel(mbox.getMessageContent(1)));
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testUnescapeEscapedLineWithNoTrailingNewline() throws IOException {
        String body = "before\r\n>From no trailing newline";
        String expected = "before\r\nFrom no trailing newline";
        MboxMailbox mbox = openMailboxWithSingleMessageBody(body);
        try {
            assertEquals(headerThenBody(expected), readChannel(mbox.getMessageContent(1)));
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testUnescapeDoesNotTouchMidLineFrom() throws IOException {
        // ">From " only unescapes at the start of a line; mid-line occurrences
        // (which could not have been produced by escaping) are left as-is.
        String body = "some text >From not at line start\r\n";
        MboxMailbox mbox = openMailboxWithSingleMessageBody(body);
        try {
            assertEquals(headerThenBody(body), readChannel(mbox.getMessageContent(1)));
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testOpenAsyncContentMatchesGetMessageContent() throws IOException {
        String body = "Line one.\r\n>From escaped.\r\nLine three.\r\n";
        MboxMailbox mbox = openMailboxWithSingleMessageBody(body);
        try {
            String sync = readChannel(mbox.getMessageContent(1));
            org.bluezoo.gumdrop.mailbox.AsyncMessageContent async =
                    mbox.openAsyncContent(1);
            assertNotNull("mbox must support openAsyncContent", async);
            try {
                ByteBuffer buf = ByteBuffer.allocate((int) async.size());
                readAsyncFully(async, buf);
                buf.flip();
                String asyncContent = StandardCharsets.US_ASCII.decode(buf).toString();
                assertEquals(sync, asyncContent);
            } finally {
                async.close();
            }
        } finally {
            mbox.close(false);
        }
    }

    @Test
    public void testGetMessageTopEndOffsetMatchesGetMessageTop() throws IOException {
        String body = "Body line one.\r\n>From escaped.\r\nBody line three.\r\nBody line four.\r\n";
        MboxMailbox mbox = openMailboxWithSingleMessageBody(body);
        try {
            String top = readChannel(mbox.getMessageTop(1, 2));
            long endOffset = mbox.getMessageTopEndOffset(1, 2);
            assertEquals("getMessageTopEndOffset must agree with getMessageTop's "
                            + "actual output length",
                    top.getBytes(StandardCharsets.US_ASCII).length, endOffset);
        } finally {
            mbox.close(false);
        }
    }

    private static void readAsyncFully(
            org.bluezoo.gumdrop.mailbox.AsyncMessageContent async, ByteBuffer dst)
            throws IOException {
        long position = 0;
        while (dst.hasRemaining()) {
            final java.util.concurrent.atomic.AtomicInteger result =
                    new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            async.read(dst, position,
                    new java.nio.channels.CompletionHandler<Integer, ByteBuffer>() {
                        @Override
                        public void completed(Integer n, ByteBuffer attachment) {
                            result.set(n);
                            latch.countDown();
                        }
                        @Override
                        public void failed(Throwable exc, ByteBuffer attachment) {
                            result.set(-1);
                            latch.countDown();
                        }
                    });
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (result.get() <= 0) {
                break;
            }
            position += result.get();
        }
    }

    private static final String SINGLE_MESSAGE_HEADER =
            "From sender@example.com Mon Jan  1 00:00:00 2025\r\n"
            + "From: sender@example.com\r\n"
            + "Subject: Test\r\n"
            + "\r\n";

    private static String headerThenBody(String body) {
        // The last (only) message in the file has no following "From "
        // envelope line, so indexMessages() trims exactly one trailing
        // line terminator off the end of its content.
        String trimmed = body.endsWith("\r\n")
                ? body.substring(0, body.length() - 2) : body;
        return "From: sender@example.com\r\nSubject: Test\r\n\r\n" + trimmed;
    }

    private MboxMailbox openMailboxWithSingleMessageBody(String body)
            throws IOException {
        Files.write(mboxFile,
                (SINGLE_MESSAGE_HEADER + body).getBytes(StandardCharsets.US_ASCII));
        return new MboxMailbox(mboxFile, "test", true);
    }

    // Regression test for issue #124: indexNewMessages() previously walked
    // every message in the mailbox on every open, even when the persisted
    // search index was already fully up to date. Appending a message
    // externally (simulating another MTA delivering mail) and reopening
    // exercises the loadOrBuildSearchIndex -> indexNewMessages path; the
    // new message must be found via the search index without requiring a
    // full rebuild.
    @Test
    public void testSearchFindsMessageAppendedBetweenOpens() throws IOException {
        MboxMailbox mbox = openSampleMailbox(false);
        mbox.close(false);

        String newMsg =
                "From third@example.com Wed Jan  3 00:00:00 2025\r\n" +
                "From: third@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "Subject: Third message\r\n" +
                "\r\n" +
                "Hello, this is message three.\r\n";
        Files.write(mboxFile, newMsg.getBytes(StandardCharsets.US_ASCII),
                java.nio.file.StandardOpenOption.APPEND);

        MboxMailbox reopened = new MboxMailbox(mboxFile, "test", true);
        try {
            assertEquals(3, reopened.getMessageCount());
            List<Integer> matches = reopened.search(
                    org.bluezoo.gumdrop.mailbox.SearchCriteria.subject("Third message"));
            assertEquals(1, matches.size());
            assertEquals(Integer.valueOf(3), matches.get(0));
        } finally {
            reopened.close(false);
        }
    }

    // Regression test for issue #135: two MboxMailbox instances on the same
    // file in the same JVM used to race straight to the OS-level FileLock
    // and the second one would throw OverlappingFileLockException instead
    // of blocking/queueing. A second open on a background thread must now
    // block until the first session closes, then succeed - not fail.
    @Test(timeout = 10000)
    public void testConcurrentSameJvmSessionsQueueInsteadOfCrashing()
            throws Exception {
        MboxMailbox first = openSampleMailbox(true);

        final java.util.concurrent.CountDownLatch secondStarted =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<MboxMailbox> secondRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> secondError =
                new java.util.concurrent.atomic.AtomicReference<>();

        Thread opener = new Thread(() -> {
            secondStarted.countDown();
            try {
                secondRef.set(new MboxMailbox(mboxFile, "test", true));
            } catch (Throwable t) {
                secondError.set(t);
            }
        });
        opener.start();

        assertTrue(secondStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        // Give the second open every chance to have raced ahead and thrown
        // if the fix were absent; it must instead still be blocked.
        Thread.sleep(200);
        assertTrue("second open must still be blocked behind the first "
                        + "session, not have failed or returned",
                opener.isAlive());

        first.close(false);
        opener.join(5000);

        assertNull("second open must not have thrown "
                        + "OverlappingFileLockException or any other error",
                secondError.get());
        MboxMailbox second = secondRef.get();
        assertNotNull("second open must have succeeded once the first "
                        + "session closed", second);
        try {
            assertEquals(2, second.getMessageCount());
        } finally {
            second.close(false);
        }
    }
}
