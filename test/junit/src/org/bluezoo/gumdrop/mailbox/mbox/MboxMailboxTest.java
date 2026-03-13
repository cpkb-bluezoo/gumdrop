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
}
