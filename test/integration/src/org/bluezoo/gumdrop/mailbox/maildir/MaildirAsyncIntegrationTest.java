/*
 * MaildirAsyncIntegrationTest.java
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
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.mailbox.AsyncMessageContent;
import org.bluezoo.gumdrop.mailbox.AsyncMessageWriter;
import org.bluezoo.gumdrop.mailbox.Flag;
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
 * Integration tests for the asynchronous append and content classes of
 * {@link MaildirMailbox}, which need a file system with real
 * {@code AsynchronousFileChannel} support: streamed append with finish,
 * abort and close semantics, and positional reads of stored messages.
 * Completions are awaited on latches released by the handlers, never by
 * polling.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirAsyncIntegrationTest {

    private static final String MESSAGE =
            "From: a@example.com\r\nSubject: async\r\n\r\nstreamed body\r\n";

    private Path tempDir;
    private Path maildir;
    private MaildirMailbox mailbox;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("maildir-async");
        maildir = tempDir.resolve("box");
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));
        mailbox = new MaildirMailbox(maildir, "INBOX", false);
    }

    @After
    public void tearDown() throws Exception {
        if (mailbox != null) {
            mailbox.close(false);
        }
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
                        IOException exc) {
                    try {
                        Files.deleteIfExists(dir);
                    } catch (Exception ignored) {
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /** Records the outcome of one asynchronous operation. */
    private static final class Outcome<T, A> implements CompletionHandler<T, A> {
        final CountDownLatch done = new CountDownLatch(1);
        volatile T result;
        volatile Throwable failure;

        @Override
        public void completed(T value, A attachment) {
            result = value;
            done.countDown();
        }

        @Override
        public void failed(Throwable exc, A attachment) {
            failure = exc;
            done.countDown();
        }

        void await() throws InterruptedException {
            assertTrue("operation never completed", done.await(10, TimeUnit.SECONDS));
        }
    }

    private static int writeAll(AsyncMessageWriter writer, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        Outcome<Integer, ByteBuffer> outcome = new Outcome<Integer, ByteBuffer>();
        writer.write(ByteBuffer.wrap(bytes), outcome);
        outcome.await();
        assertNull(outcome.failure);
        return outcome.result.intValue();
    }

    private int tempFileCount() throws IOException {
        int count = 0;
        DirectoryStream<Path> stream = Files.newDirectoryStream(maildir.resolve("tmp"));
        try {
            for (Path p : stream) {
                count++;
            }
        } finally {
            stream.close();
        }
        return count;
    }

    @Test(timeout = 30000)
    public void streamedAppendBecomesAMessage() throws Exception {
        AsyncMessageWriter writer = mailbox.openAsyncAppend(EnumSet.of(Flag.SEEN), null);
        assertNotNull(writer);
        assertFalse(writer.wantsPause());
        int split = 30;
        assertEquals(split, writeAll(writer, MESSAGE.substring(0, split)));
        assertEquals(MESSAGE.length() - split, writeAll(writer, MESSAGE.substring(split)));
        Outcome<Long, Void> finished = new Outcome<Long, Void>();
        writer.finish(finished);
        finished.await();
        assertNull(finished.failure);
        assertTrue(finished.result.longValue() > 0);
        assertEquals(0, tempFileCount());
        assertEquals(1, mailbox.getMessageCount());
        assertTrue(mailbox.getFlags(1).contains(Flag.SEEN));
        writer.close();
    }

    @Test(timeout = 30000)
    public void finishingTwiceFailsTheSecondTime() throws Exception {
        AsyncMessageWriter writer = mailbox.openAsyncAppend(null, null);
        assertNotNull(writer);
        writeAll(writer, MESSAGE);
        Outcome<Long, Void> first = new Outcome<Long, Void>();
        writer.finish(first);
        first.await();
        assertNull(first.failure);
        Outcome<Long, Void> second = new Outcome<Long, Void>();
        writer.finish(second);
        second.await();
        assertTrue(second.failure instanceof IllegalStateException);
        assertEquals(1, mailbox.getMessageCount());
    }

    @Test(timeout = 30000)
    public void abortAndCloseDiscardTheTemporaryFile() throws Exception {
        AsyncMessageWriter aborted = mailbox.openAsyncAppend(null, null);
        writeAll(aborted, "partial");
        assertEquals(1, tempFileCount());
        aborted.abort();
        assertEquals(0, tempFileCount());
        AsyncMessageWriter closed = mailbox.openAsyncAppend(null, null);
        writeAll(closed, "partial");
        closed.close();
        assertEquals(0, tempFileCount());
        assertEquals(0, mailbox.getMessageCount());
        AsyncMessageWriter finished = mailbox.openAsyncAppend(null, null);
        writeAll(finished, MESSAGE);
        Outcome<Long, Void> outcome = new Outcome<Long, Void>();
        finished.finish(outcome);
        outcome.await();
        finished.close();
        assertEquals(1, mailbox.getMessageCount());
    }

    @Test(timeout = 30000)
    public void readOnlyMailboxRefusesAsyncAppend() throws Exception {
        mailbox.close(false);
        mailbox = new MaildirMailbox(maildir, "INBOX", true);
        try {
            mailbox.openAsyncAppend(null, null);
            fail("async append on a read-only mailbox");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("read-only"));
        }
    }

    @Test(timeout = 30000)
    public void storedMessageIsReadableAtAnyPosition() throws Exception {
        AsyncMessageWriter writer = mailbox.openAsyncAppend(null, null);
        writeAll(writer, MESSAGE);
        Outcome<Long, Void> finished = new Outcome<Long, Void>();
        writer.finish(finished);
        finished.await();
        AsyncMessageContent content = mailbox.openAsyncContent(1);
        assertNotNull(content);
        try {
            assertEquals(MESSAGE.length(), content.size());
            assertEquals(MESSAGE.indexOf("streamed"), content.bodyOffset());
            ByteBuffer dst = ByteBuffer.allocate(8);
            Outcome<Integer, ByteBuffer> read = new Outcome<Integer, ByteBuffer>();
            content.read(dst, MESSAGE.indexOf("Subject"), read);
            read.await();
            assertNull(read.failure);
            assertEquals(8, read.result.intValue());
            dst.flip();
            assertEquals("Subject:", StandardCharsets.ISO_8859_1.decode(dst).toString());
        } finally {
            content.close();
        }
    }

    @Test(timeout = 30000)
    public void asyncContentOfMissingOrDeletedMessageIsRefused() throws Exception {
        try {
            mailbox.openAsyncContent(1);
            fail("content of a message that does not exist");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("not found"));
        }
        AsyncMessageWriter writer = mailbox.openAsyncAppend(null, null);
        writeAll(writer, MESSAGE);
        Outcome<Long, Void> finished = new Outcome<Long, Void>();
        writer.finish(finished);
        finished.await();
        mailbox.deleteMessage(1);
        try {
            mailbox.openAsyncContent(1);
            fail("content of a deleted message");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("not found"));
        }
    }
}
