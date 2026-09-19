/*
 * ParsedMessageContextTest.java
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

package org.bluezoo.gumdrop.mailbox;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ParsedMessageContext}, parsing RFC 5322 messages
 * held in memory and exposing headers, sent date and searchable text.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ParsedMessageContextTest {

    /** Serves a fixed message from memory. */
    private static final class MemoryMailbox implements Mailbox {
        private final byte[] content;

        MemoryMailbox(String message) {
            this.content = message.getBytes(StandardCharsets.ISO_8859_1);
        }

        @Override
        public void close(boolean expunge) {
        }

        @Override
        public int getMessageCount() {
            return 1;
        }

        @Override
        public long getMailboxSize() {
            return content.length;
        }

        @Override
        public Iterator<MessageDescriptor> getMessageList() {
            return Collections.<MessageDescriptor>emptyList().iterator();
        }

        @Override
        public MessageDescriptor getMessage(int messageNumber) {
            return null;
        }

        @Override
        public ReadableByteChannel getMessageContent(int messageNumber) throws IOException {
            return Channels.newChannel(new ByteArrayInputStream(content));
        }

        @Override
        public ReadableByteChannel getMessageTop(int messageNumber, int bodyLines) throws IOException {
            return getMessageContent(messageNumber);
        }

        @Override
        public void deleteMessage(int messageNumber) {
        }

        @Override
        public boolean isDeleted(int messageNumber) {
            return false;
        }

        @Override
        public void undeleteAll() {
        }

        @Override
        public String getUniqueId(int messageNumber) {
            return "1";
        }
    }

    private static ParsedMessageContext context(String message) {
        Set<Flag> none = null;
        OffsetDateTime internal = OffsetDateTime.of(2025, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
        return new ParsedMessageContext(new MemoryMailbox(message), 1, 42L, message.length(), none, internal);
    }

    private static final String SIMPLE =
        "From: Alice <alice@example.com>\r\n"
        + "To: bob@example.com, carol@example.com\r\n"
        + "Subject: Hello\r\n"
        + "Date: Mon, 05 May 2025 10:00:00 +0000\r\n"
        + "Message-ID: <abc123@example.com>\r\n"
        + "Received: from a\r\n"
        + "Received: from b\r\n"
        + "\r\n"
        + "Body line one.\r\nBody line two.\r\n";

    @Test
    public void simpleAccessors() {
        ParsedMessageContext c = context(SIMPLE);
        assertEquals(1, c.getMessageNumber());
        assertEquals(42L, c.getUID());
        assertEquals(SIMPLE.length(), c.getSize());
        assertTrue(c.getFlags().isEmpty());
        assertNotNull(c.getInternalDate());
    }

    @Test
    public void headersAreParsedCaseInsensitively() throws Exception {
        ParsedMessageContext c = context(SIMPLE);
        assertEquals("Hello", c.getHeader("subject"));
        assertEquals("Hello", c.getHeader("SUBJECT"));
        assertNull(c.getHeader("X-Missing"));
        assertTrue(c.getHeaders("X-Missing").isEmpty());
        List<String> received = c.getHeaders("Received");
        assertEquals(2, received.size());
    }

    @Test
    public void addressAndMessageIdHeadersAreRendered() throws Exception {
        ParsedMessageContext c = context(SIMPLE);
        String to = c.getHeader("To");
        assertTrue(to.contains("bob@example.com"));
        assertTrue(to.contains("carol@example.com"));
        assertTrue(c.getHeader("From").contains("alice@example.com"));
        assertEquals("<abc123@example.com>", c.getHeader("Message-ID"));
    }

    @Test
    public void sentDateComesFromDateHeader() throws Exception {
        ParsedMessageContext c = context(SIMPLE);
        OffsetDateTime sent = c.getSentDate();
        assertNotNull(sent);
        assertEquals(2025, sent.getYear());
        assertEquals(5, sent.getMonthValue());
        assertEquals(5, sent.getDayOfMonth());
    }

    @Test
    public void headersTextAndBodyTextAreCollected() throws Exception {
        ParsedMessageContext c = context(SIMPLE);
        String headersText = c.getHeadersText().toString();
        assertTrue(headersText.contains("Subject: Hello\r\n"));
        assertTrue(headersText.endsWith("\r\n\r\n"));
        String body = c.getBodyText().toString();
        assertTrue(body.contains("Body line one."));
        assertTrue(body.contains("Body line two."));
    }

    @Test
    public void messageWithoutDateHasNullSentDate() throws Exception {
        ParsedMessageContext c = context("Subject: x\r\n\r\nhi\r\n");
        assertNull(c.getSentDate());
    }

    @Test
    public void charsetParameterDecodesBody() throws Exception {
        String message =
            "Subject: enc\r\n"
            + "MIME-Version: 1.0\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\n"
            + "\r\n"
            + "cafÃ©\r\n";
        ParsedMessageContext c = context(message);
        assertTrue(c.getBodyText().toString().contains("café"));
        assertTrue(c.getHeader("Content-Type").toLowerCase().contains("text/plain"));
        assertNotNull(c.getHeader("MIME-Version"));
    }

    @Test
    public void unknownCharsetFallsBackToLatin1() throws Exception {
        String message =
            "Subject: enc\r\n"
            + "MIME-Version: 1.0\r\n"
            + "Content-Type: text/plain; charset=no-such-charset\r\n"
            + "\r\n"
            + "plain\r\n";
        assertTrue(context(message).getBodyText().toString().contains("plain"));
    }

    @Test
    public void nonTextPartsAreExcludedFromBodyText() throws Exception {
        String message =
            "Subject: multi\r\n"
            + "MIME-Version: 1.0\r\n"
            + "Content-Type: multipart/mixed; boundary=\"XX\"\r\n"
            + "\r\n"
            + "--XX\r\n"
            + "Content-Type: text/plain\r\n"
            + "Content-Transfer-Encoding: 7bit\r\n"
            + "Content-Description: the text\r\n"
            + "\r\n"
            + "visible words\r\n"
            + "--XX\r\n"
            + "Content-Type: application/octet-stream\r\n"
            + "Content-Disposition: attachment; filename=\"a.bin\"\r\n"
            + "Content-ID: <part1@example.com>\r\n"
            + "\r\n"
            + "HIDDENBINARY\r\n"
            + "--XX--\r\n";
        ParsedMessageContext c = context(message);
        String body = c.getBodyText().toString();
        assertTrue(body.contains("visible words"));
        assertFalse(body.contains("HIDDENBINARY"));
        String headers = c.getHeadersText().toString();
        assertTrue(headers.contains("Content-Description: the text"));
        assertTrue(headers.contains("Content-Disposition:"));
        assertTrue(headers.contains("Content-ID: <part1@example.com>"));
    }

    @Test
    public void modSeqDelegatesToMailbox() throws Exception {
        assertEquals(0L, context(SIMPLE).getModSeq());
    }

    @Test
    public void parsingHappensOnceAndResultsAreStable() throws Exception {
        ParsedMessageContext c = context(SIMPLE);
        CharSequence first = c.getHeadersText();
        CharSequence second = c.getHeadersText();
        assertSame(first, second);
    }
}
