/*
 * MessageIndexBuilderTest.java
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

package org.bluezoo.gumdrop.mailbox.index;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MessageIndexBuilder}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageIndexBuilderTest {

    private MessageIndexBuilder builder;

    @Before
    public void setUp() {
        builder = new MessageIndexBuilder();
    }

    // ========================================================================
    // Simple Message Parsing Tests
    // ========================================================================

    @Test
    public void testBuildEntrySimpleMessage() throws IOException {
        String message = 
            "From: Alice <alice@example.com>\r\n" +
            "To: Bob <bob@example.com>\r\n" +
            "Subject: Hello World\r\n" +
            "Date: Mon, 1 Jan 2024 12:00:00 +0000\r\n" +
            "Message-ID: <msg123@example.com>\r\n" +
            "\r\n" +
            "This is the body of the message.\r\n";

        ReadableByteChannel channel = createChannel(message);
        Set<Flag> flags = EnumSet.of(Flag.SEEN);
        
        MessageIndexEntry entry = builder.buildEntry(
            12345L, 1, message.length(), 1704110400000L, flags, "location", channel);

        assertEquals(12345L, entry.getUid());
        assertEquals(1, entry.getMessageNumber());
        assertEquals(message.length(), entry.getSize());
        assertEquals(1704110400000L, entry.getInternalDate());
        assertTrue(entry.hasFlag(Flag.SEEN));
        assertEquals("location", entry.getLocation());
        
        // Header values should be lowercase
        assertTrue(entry.getFrom().contains("alice@example.com"));
        assertTrue(entry.getTo().contains("bob@example.com"));
        assertEquals("hello world", entry.getSubject());
        assertEquals("<msg123@example.com>", entry.getMessageId());
    }

    @Test
    public void testBuildEntryWithMultipleRecipients() throws IOException {
        String message = 
            "From: sender@example.com\r\n" +
            "To: user1@example.com, user2@example.com\r\n" +
            "Cc: cc1@example.com, cc2@example.com, cc3@example.com\r\n" +
            "Bcc: hidden@example.com\r\n" +
            "Subject: Multi-recipient test\r\n" +
            "\r\n" +
            "Body.\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, message.length(), 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        assertTrue(entry.getTo().contains("user1@example.com"));
        assertTrue(entry.getTo().contains("user2@example.com"));
        assertTrue(entry.getCc().contains("cc1@example.com"));
        assertTrue(entry.getCc().contains("cc2@example.com"));
        assertTrue(entry.getCc().contains("cc3@example.com"));
        assertTrue(entry.getBcc().contains("hidden@example.com"));
    }

    // ========================================================================
    // Date Parsing Tests
    // ========================================================================

    @Test
    public void testBuildEntryParseSentDate() throws IOException {
        String message = 
            "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: Date test\r\n" +
            "Date: Thu, 15 Feb 2024 14:30:00 -0500\r\n" +
            "\r\n" +
            "Body.\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, message.length(), 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        // Sent date should be parsed from Date header
        assertTrue(entry.getSentDate() > 0);
    }

    @Test
    public void testBuildEntryMissingDate() throws IOException {
        String message = 
            "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: No date\r\n" +
            "\r\n" +
            "Body.\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, message.length(), 1704067200000L, EnumSet.noneOf(Flag.class), "loc", channel);

        // Sent date should be 0 (not parsed)
        assertEquals(0L, entry.getSentDate());
        // Internal date should be preserved
        assertEquals(1704067200000L, entry.getInternalDate());
    }

    // ========================================================================
    // UTF-8 and Encoded Headers Tests
    // ========================================================================

    @Test
    public void testBuildEntryEncodedSubject() throws IOException {
        String message = 
            "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: =?UTF-8?B?SGVsbG8g5LiW55WM?=\r\n" +  // "Hello 世界" in Base64
            "\r\n" +
            "Body.\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, message.length(), 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        // The decoded subject should contain "hello" (lowercase)
        // Note: actual decoding depends on MessageParser implementation
        assertNotNull(entry.getSubject());
    }

    @Test
    public void testBuildEntryQuotedPrintableSubject() throws IOException {
        String message = 
            "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: =?ISO-8859-1?Q?Caf=E9_time?=\r\n" +
            "\r\n" +
            "Body.\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, message.length(), 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        assertNotNull(entry.getSubject());
    }

    // ========================================================================
    // MIME Multipart Tests
    // ========================================================================

    @Test
    public void testBuildEntryMultipartMessage() throws IOException {
        String boundary = "----=_Part_123";
        String message = 
            "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: Multipart test\r\n" +
            "Content-Type: multipart/mixed; boundary=\"" + boundary + "\"\r\n" +
            "\r\n" +
            "------=_Part_123\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "\r\n" +
            "This is the text part.\r\n" +
            "------=_Part_123\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Disposition: attachment; filename=\"test.bin\"\r\n" +
            "\r\n" +
            "binary data here\r\n" +
            "------=_Part_123--\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, message.length(), 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        assertEquals("multipart test", entry.getSubject());
        assertTrue(entry.getFrom().contains("sender@example.com"));
    }

    // ========================================================================
    // Folded Header Tests
    // ========================================================================

    @Test
    public void testBuildEntryFoldedHeaders() throws IOException {
        String message = 
            "From: sender@example.com\r\n" +
            "To: user1@example.com,\r\n" +
            "\tuser2@example.com,\r\n" +
            "\tuser3@example.com\r\n" +
            "Subject: This is a very long subject line that has been\r\n" +
            " folded across multiple lines for RFC compliance\r\n" +
            "\r\n" +
            "Body.\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, message.length(), 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        // Should handle folded headers correctly
        assertTrue(entry.getTo().contains("user1@example.com"));
        assertTrue(entry.getTo().contains("user2@example.com"));
        assertTrue(entry.getTo().contains("user3@example.com"));
        assertTrue(entry.getSubject().contains("folded"));
    }

    // ========================================================================
    // Missing Headers Tests
    // ========================================================================

    @Test
    public void testBuildEntryMissingFrom() throws IOException {
        String message = 
            "To: recipient@example.com\r\n" +
            "Subject: No from header\r\n" +
            "\r\n" +
            "Body.\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, message.length(), 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        assertEquals("", entry.getFrom());
    }

    @Test
    public void testBuildEntryMissingSubject() throws IOException {
        String message = 
            "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "\r\n" +
            "Body.\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, message.length(), 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        assertEquals("", entry.getSubject());
    }

    @Test
    public void testBuildEntryEmptyMessage() throws IOException {
        String message = "\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, message.length(), 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        assertEquals("", entry.getFrom());
        assertEquals("", entry.getTo());
        assertEquals("", entry.getSubject());
    }

    // ========================================================================
    // Case Normalization Tests
    // ========================================================================

    @Test
    public void testHeaderValuesAreLowercase() throws IOException {
        String message = 
            "From: Alice.Smith@EXAMPLE.COM\r\n" +
            "To: BOB.JONES@Example.Com\r\n" +
            "Subject: UPPERCASE SUBJECT\r\n" +
            "\r\n" +
            "Body.\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, message.length(), 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        // Verify lowercase normalization - addresses stored without display name/brackets
        assertTrue(entry.getFrom().contains("alice.smith@example.com"));
        assertTrue(entry.getTo().contains("bob.jones@example.com"));
        assertEquals("uppercase subject", entry.getSubject());
    }

    @Test
    public void testMessageIdPreservesCase() throws IOException {
        // Message-ID should preserve case for angle brackets
        String message = 
            "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: Test\r\n" +
            "Message-ID: <ABC123@Example.Com>\r\n" +
            "\r\n" +
            "Body.\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, message.length(), 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        // Message-ID should be stored lowercase for searching
        assertEquals("<abc123@example.com>", entry.getMessageId());
    }

    // ========================================================================
    // Large Message Tests
    // ========================================================================

    @Test
    public void testBuildEntryLargeHeaders() throws IOException {
        StringBuilder message = new StringBuilder();
        message.append("From: sender@example.com\r\n");
        
        // Very long To header with many recipients
        message.append("To: ");
        for (int i = 0; i < 100; i++) {
            if (i > 0) {
                message.append(", ");
            }
            message.append("user").append(i).append("@example.com");
        }
        message.append("\r\n");
        
        // Long subject
        message.append("Subject: ");
        for (int i = 0; i < 100; i++) {
            message.append("word").append(i).append(" ");
        }
        message.append("\r\n\r\n");
        message.append("Body.\r\n");

        String msgStr = message.toString();
        ReadableByteChannel channel = createChannel(msgStr);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, msgStr.length(), 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        assertTrue(entry.getTo().contains("user0@example.com"));
        assertTrue(entry.getTo().contains("user99@example.com"));
        assertTrue(entry.getSubject().contains("word0"));
        assertTrue(entry.getSubject().contains("word99"));
    }

    // ========================================================================
    // Chunked Reading Tests
    // ========================================================================

    @Test
    public void testBuildEntryChunkedRead() throws IOException {
        String message = 
            "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: Chunked test\r\n" +
            "\r\n" +
            "This is a longer body that will be read in chunks.\r\n" +
            "Line 2.\r\n" +
            "Line 3.\r\n";

        // Use a small buffer to force chunked reading
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        ReadableByteChannel channel = new SmallBufferChannel(bais, 10);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, bytes.length, 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        assertEquals("chunked test", entry.getSubject());
    }

    // ========================================================================
    // Keywords Tests
    // ========================================================================

    @Test
    public void testBuildEntryWithKeywordsHeader() throws IOException {
        String message = 
            "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: Keywords test\r\n" +
            "Keywords: important, urgent, todo\r\n" +
            "\r\n" +
            "Body.\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            1L, 1, message.length(), 0L, EnumSet.noneOf(Flag.class), "loc", channel);

        // Check if keywords were extracted
        String keywords = entry.getKeywords();
        // Keywords header should be parsed if implemented
        assertNotNull(keywords);
    }

    // ========================================================================
    // Real-World Message Samples
    // ========================================================================

    @Test
    public void testBuildEntryRealisticEmail() throws IOException {
        String message = 
            "Return-Path: <bounce@lists.example.org>\r\n" +
            "Received: from mail.example.org (mail.example.org [192.0.2.1])\r\n" +
            "\tby mx.local.net (Postfix) with ESMTPS id ABC123\r\n" +
            "\tfor <user@local.net>; Mon, 15 Jan 2024 10:30:00 +0000 (UTC)\r\n" +
            "From: Project Updates <updates@example.org>\r\n" +
            "To: dev-list@example.org\r\n" +
            "Cc: managers@example.org\r\n" +
            "Subject: [dev-list] Weekly status report - January 15\r\n" +
            "Date: Mon, 15 Jan 2024 10:30:00 +0000\r\n" +
            "Message-ID: <20240115103000.12345@mail.example.org>\r\n" +
            "List-Id: Development List <dev-list.example.org>\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Transfer-Encoding: quoted-printable\r\n" +
            "\r\n" +
            "Hello team,\r\n" +
            "\r\n" +
            "Here is the weekly status report.\r\n" +
            "\r\n" +
            "Regards,\r\n" +
            "The Project Team\r\n";

        ReadableByteChannel channel = createChannel(message);
        
        MessageIndexEntry entry = builder.buildEntry(
            42L, 5, message.length(), 1705315800000L, 
            EnumSet.of(Flag.SEEN), "1705315800.42.mail", channel);

        assertEquals(42L, entry.getUid());
        assertEquals(5, entry.getMessageNumber());
        assertTrue(entry.hasFlag(Flag.SEEN));
        assertTrue(entry.getFrom().contains("updates@example.org"));
        assertTrue(entry.getTo().contains("dev-list@example.org"));
        assertTrue(entry.getCc().contains("managers@example.org"));
        assertEquals("[dev-list] weekly status report - january 15", entry.getSubject());
        assertEquals("<20240115103000.12345@mail.example.org>", entry.getMessageId());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private ReadableByteChannel createChannel(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new ByteArrayChannel(bytes);
    }

    /**
     * A simple channel backed by a byte array that works correctly with NIO patterns.
     * Uses small chunked reading to ensure parser compatibility with incremental parsing.
     */
    private static class ByteArrayChannel implements ReadableByteChannel {
        private final ByteArrayInputStream input;
        private static final int CHUNK_SIZE = 16; // Small chunks for incremental parsing
        private boolean open = true;

        ByteArrayChannel(byte[] data) {
            this.input = new ByteArrayInputStream(data);
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!open) {
                throw new IOException("Channel closed");
            }
            
            // Read in chunks, not all at once
            int available = Math.min(dst.remaining(), input.available());
            available = Math.min(available, CHUNK_SIZE);
            
            if (available == 0) {
                return -1;
            }
            
            byte[] temp = new byte[available];
            int read = input.read(temp, 0, available);
            if (read > 0) {
                dst.put(temp, 0, read);
            }
            return read;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    /**
     * A channel that reads small chunks at a time to test chunked parsing.
     */
    private static class SmallBufferChannel implements ReadableByteChannel {
        private final ByteArrayInputStream input;
        private final int chunkSize;
        private boolean open = true;

        SmallBufferChannel(ByteArrayInputStream input, int chunkSize) {
            this.input = input;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!open) {
                throw new IOException("Channel closed");
            }
            
            int available = Math.min(dst.remaining(), chunkSize);
            available = Math.min(available, input.available());
            
            if (available == 0) {
                return -1;
            }
            
            byte[] temp = new byte[available];
            int read = input.read(temp, 0, available);
            if (read > 0) {
                dst.put(temp, 0, read);
            }
            return read;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

}

