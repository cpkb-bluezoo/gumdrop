/*
 * MaildirIndexNewMessagesTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.mailbox.maildir;

import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Regression tests for issue #317: {@code indexNewMessages()} must jump to
 * the tail via {@link org.bluezoo.gumdrop.mailbox.index.MessageIndex#getUidNext()}
 * instead of walking every descriptor with a per-message lookup on every
 * open when the persisted search index is already up to date.
 */
public class MaildirIndexNewMessagesTest {

    private Path tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("maildir-index-new-msgs");
    }

    @After
    public void tearDown() throws Exception {
        if (tempDir != null) {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testIndexNewMessagesDoesNotLookupEveryUidWhenIndexIsCurrent()
            throws Exception {
        Path maildir = createPopulatedMaildir(2_000);
        MaildirMailbox mailbox = new MaildirMailbox(maildir, "INBOX", false);
        try {
            AtomicInteger uidLookups = installUidLookupCounter(mailbox);
            invokeIndexNewMessages(mailbox);
            assertEquals("a current index should not scan every descriptor",
                    0, uidLookups.get());
        } finally {
            mailbox.close(false);
        }
    }

    @Test
    public void testSearchFindsMessageAppendedBetweenOpens() throws Exception {
        Path maildir = createPopulatedMaildir(2);
        MaildirMailbox mailbox = new MaildirMailbox(maildir, "INBOX", false);
        mailbox.close(false);

        String content =
                "From: third@example.com\r\n" +
                "To: recipient@example.com\r\n" +
                "Subject: Third message\r\n" +
                "\r\n" +
                "Hello, this is message three.\r\n";
        writeCurMessage(maildir, 3, content);

        MaildirMailbox reopened = new MaildirMailbox(maildir, "INBOX", false);
        try {
            assertEquals(3, reopened.getMessageCount());
            List<Integer> matches = reopened.search(
                    SearchCriteria.subject("Third message"));
            assertEquals(1, matches.size());
            assertEquals(Integer.valueOf(3), matches.get(0));
        } finally {
            reopened.close(false);
        }
    }

    private static AtomicInteger installUidLookupCounter(MaildirMailbox mailbox)
            throws Exception {
        Field searchIndexField = MaildirMailbox.class.getDeclaredField("searchIndex");
        searchIndexField.setAccessible(true);
        Object searchIndex = searchIndexField.get(mailbox);

        Field uidToIndexField = searchIndex.getClass().getDeclaredField("uidToIndex");
        uidToIndexField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Long, Integer> original =
                (Map<Long, Integer>) uidToIndexField.get(searchIndex);

        AtomicInteger uidLookups = new AtomicInteger();
        Map<Long, Integer> counting = new AbstractMap<Long, Integer>() {
            @Override
            public Integer get(Object key) {
                uidLookups.incrementAndGet();
                return original.get(key);
            }

            @Override
            public Set<Entry<Long, Integer>> entrySet() {
                return original.entrySet();
            }
        };
        uidToIndexField.set(searchIndex, counting);
        return uidLookups;
    }

    private static void invokeIndexNewMessages(MaildirMailbox mailbox) throws Exception {
        Method indexNewMessages =
                MaildirMailbox.class.getDeclaredMethod("indexNewMessages");
        indexNewMessages.setAccessible(true);
        indexNewMessages.invoke(mailbox);
    }

    private Path createPopulatedMaildir(int messageCount) throws IOException {
        Path maildir = tempDir.resolve("box-" + messageCount);
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));
        for (int i = 1; i <= messageCount; i++) {
            String content =
                    "From: user" + i + "@example.com\r\n" +
                    "Subject: Message " + i + "\r\n" +
                    "\r\n" +
                    "Body " + i + "\r\n";
            writeCurMessage(maildir, i, content);
        }
        return maildir;
    }

    private static void writeCurMessage(Path maildir, int sequence, String content)
            throws IOException {
        String filename = (1_733_356_800_000L + sequence) + ".m" + sequence
                + ".host,S=" + content.length();
        Files.write(maildir.resolve("cur").resolve(filename),
                content.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.deleteIfExists(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
}
