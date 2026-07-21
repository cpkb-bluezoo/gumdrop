package org.bluezoo.gumdrop.mailbox.maildir;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Unit tests for Maildir body-offset precomputation
 * ({@link MaildirMailbox#detectBodyOffset} and descriptor caching).
 */
public class MaildirBodyOffsetTest {

    private Path tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("maildir-body-offset");
    }

    @After
    public void tearDown() throws Exception {
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    @Test
    public void detectBodyOffset_crlfcrlf() throws Exception {
        String msg = "From: a@b\r\nSubject: hi\r\n\r\nbody line\r\n";
        Path file = writeMessage("msg1", msg);
        assertEquals(msg.indexOf("body"), MaildirMailbox.detectBodyOffset(file));
    }

    @Test
    public void detectBodyOffset_lflf() throws Exception {
        String msg = "From: a@b\nSubject: hi\n\nbody line\n";
        Path file = writeMessage("msg2", msg);
        assertEquals(msg.indexOf("body"), MaildirMailbox.detectBodyOffset(file));
    }

    @Test
    public void detectBodyOffset_noBoundary() throws Exception {
        String msg = "From: a@b\r\nSubject: hi\r\nno blank line";
        Path file = writeMessage("msg3", msg);
        assertEquals(-1, MaildirMailbox.detectBodyOffset(file));
    }

    @Test
    public void detectBodyOffset_emptyFile() throws Exception {
        Path file = writeMessage("msg4", "");
        assertEquals(-1, MaildirMailbox.detectBodyOffset(file));
    }

    @Test
    public void descriptor_preservesBodyOffsetOnRenumber() {
        Path path = tempDir.resolve("x");
        MaildirFilename parsed = new MaildirFilename("1733356800000.1.1,S=10:2,");
        MaildirMessageDescriptor d = new MaildirMessageDescriptor(
                1, 42L, path, parsed, 17L);
        MaildirMessageDescriptor renumbered = d.withMessageNumber(5);
        assertEquals(5, renumbered.getMessageNumber());
        assertEquals(42L, renumbered.getUid());
        assertEquals(17L, renumbered.getBodyOffset());
        assertTrue(renumbered.hasResolvedBodyOffset());
    }

    @Test
    public void descriptor_unknownUntilResolved() {
        Path path = tempDir.resolve("x");
        MaildirFilename parsed = new MaildirFilename("1733356800000.1.1,S=10:2,");
        MaildirMessageDescriptor d = new MaildirMessageDescriptor(
                1, 42L, path, parsed);
        assertEquals(MaildirMessageDescriptor.UNKNOWN_BODY_OFFSET,
                d.getBodyOffset());
        assertFalse(d.hasResolvedBodyOffset());

        MaildirMessageDescriptor resolved = d.withBodyOffset(9L);
        assertEquals(9L, resolved.getBodyOffset());
        assertTrue(resolved.hasResolvedBodyOffset());
    }

    @Test
    public void mailbox_openAsyncContent_returnsCachedBodyOffset()
            throws Exception {
        Path maildir = tempDir.resolve("box");
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));

        String content = "From: a@b\r\nTo: c@d\r\n\r\nhello\r\n";
        long expectedOffset = content.indexOf("hello");
        String filename = "1733356800000.uidtest.1,S=" + content.length() + ":2,";
        Files.write(maildir.resolve("cur").resolve(filename),
                content.getBytes(StandardCharsets.UTF_8));

        MaildirMailbox mailbox = new MaildirMailbox(maildir, "INBOX", false);
        try {
            assertEquals(1, mailbox.getMessageCount());
            MaildirMessageDescriptor desc =
                    (MaildirMessageDescriptor) mailbox.getMessage(1);
            assertTrue(desc.hasResolvedBodyOffset());
            assertEquals(expectedOffset, desc.getBodyOffset());

            try (org.bluezoo.gumdrop.mailbox.AsyncMessageContent async =
                         mailbox.openAsyncContent(1)) {
                assertEquals(expectedOffset, async.bodyOffset());
                assertEquals(content.length(), async.size());
            }
        } finally {
            mailbox.close(false);
        }
    }

    /**
     * {@code bodyOffset()} must be a pure in-memory read once the descriptor
     * already has a resolved offset — never a blocking disk scan or
     * blocking async-file wait APIs.
     */
    @Test
    public void bodyOffset_isInstantWhenAlreadyCached() throws Exception {
        Path maildir = tempDir.resolve("box2");
        Files.createDirectories(maildir.resolve("cur"));
        Files.createDirectories(maildir.resolve("new"));
        Files.createDirectories(maildir.resolve("tmp"));

        String content = "From: a@b\r\nSubject: x\r\n\r\nbody\r\n";
        long expectedOffset = content.indexOf("body");
        String filename = "1733356800001.uid2.1,S=" + content.length() + ":2,";
        Files.write(maildir.resolve("cur").resolve(filename),
                content.getBytes(StandardCharsets.UTF_8));

        MaildirMailbox mailbox = new MaildirMailbox(maildir, "INBOX", false);
        try {
            MaildirMessageDescriptor desc =
                    (MaildirMessageDescriptor) mailbox.getMessage(1);
            assertTrue("scan-time cache must resolve body offset",
                    desc.hasResolvedBodyOffset());

            org.bluezoo.gumdrop.mailbox.AsyncMessageContent async =
                    mailbox.openAsyncContent(1);
            // Close the channel so any blocking async-file wait / AFC read would fail.
            async.close();

            long startNs = System.nanoTime();
            for (int i = 0; i < 10_000; i++) {
                assertEquals(expectedOffset, async.bodyOffset());
            }
            long elapsedNs = System.nanoTime() - startNs;
            // 10k field reads should finish well under 100ms even on slow CI.
            assertTrue("bodyOffset() must not block (took " + elapsedNs + " ns)",
                    elapsedNs < TimeUnit.MILLISECONDS.toNanos(100));
        } finally {
            mailbox.close(false);
        }
    }

    private Path writeMessage(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
