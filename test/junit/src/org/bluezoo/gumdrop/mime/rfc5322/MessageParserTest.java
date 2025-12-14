/*
 * MessageParserTest.java
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

package org.bluezoo.gumdrop.mime.rfc5322;

import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.MIMELocator;
import org.bluezoo.gumdrop.mime.MIMEParseException;
import org.bluezoo.gumdrop.mime.MIMEVersion;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MessageParser}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageParserTest {

    /**
     * Test handler that records events for verification.
     */
    static class TestMessageHandler implements MessageHandler {
        
        List<String> events = new ArrayList<>();
        Map<String, OffsetDateTime> dateHeaders = new HashMap<>();
        Map<String, List<EmailAddress>> addressHeaders = new HashMap<>();
        Map<String, List<ContentID>> messageIDHeaders = new HashMap<>();
        Map<String, String> unstructuredHeaders = new HashMap<>();
        Map<String, String> unexpectedHeaders = new HashMap<>();
        List<ObsoleteStructureType> obsoleteStructures = new ArrayList<>();
        ContentType contentType;
        ContentDisposition contentDisposition;
        String contentTransferEncoding;
        ContentID contentID;
        String contentDescription;
        MIMEVersion mimeVersion;
        StringBuilder body = new StringBuilder();
        int entityCount = 0;
        
        @Override
        public void setLocator(MIMELocator locator) {
            events.add("setLocator");
        }
        
        @Override
        public void startEntity(String boundary) throws MIMEParseException {
            events.add("startEntity:" + (boundary != null ? boundary : "null"));
            entityCount++;
        }
        
        @Override
        public void contentType(ContentType ct) throws MIMEParseException {
            events.add("contentType:" + ct);
            this.contentType = ct;
        }
        
        @Override
        public void contentDisposition(ContentDisposition cd) throws MIMEParseException {
            events.add("contentDisposition:" + cd);
            this.contentDisposition = cd;
        }
        
        @Override
        public void contentTransferEncoding(String encoding) throws MIMEParseException {
            events.add("contentTransferEncoding:" + encoding);
            this.contentTransferEncoding = encoding;
        }
        
        @Override
        public void contentID(ContentID cid) throws MIMEParseException {
            events.add("contentID:" + cid);
            this.contentID = cid;
        }
        
        @Override
        public void contentDescription(String description) throws MIMEParseException {
            events.add("contentDescription:" + description);
            this.contentDescription = description;
        }
        
        @Override
        public void mimeVersion(MIMEVersion version) throws MIMEParseException {
            events.add("mimeVersion:" + version);
            this.mimeVersion = version;
        }
        
        @Override
        public void endHeaders() throws MIMEParseException {
            events.add("endHeaders");
        }
        
        @Override
        public void bodyContent(ByteBuffer content) throws MIMEParseException {
            byte[] bytes = new byte[content.remaining()];
            content.get(bytes);
            body.append(new String(bytes, StandardCharsets.UTF_8));
            events.add("bodyContent");
        }
        
        @Override
        public void unexpectedContent(ByteBuffer content) throws MIMEParseException {
            events.add("unexpectedContent");
        }
        
        @Override
        public void endEntity(String boundary) throws MIMEParseException {
            events.add("endEntity:" + (boundary != null ? boundary : "null"));
        }
        
        // MessageHandler-specific methods
        
        @Override
        public void header(String name, String value) throws MIMEParseException {
            events.add("header:" + name + "=" + value);
            unstructuredHeaders.put(name, value);
        }
        
        @Override
        public void unexpectedHeader(String name, String value) throws MIMEParseException {
            events.add("unexpectedHeader:" + name + "=" + value);
            unexpectedHeaders.put(name, value);
        }
        
        @Override
        public void dateHeader(String name, OffsetDateTime date) throws MIMEParseException {
            events.add("dateHeader:" + name + "=" + date);
            dateHeaders.put(name, date);
        }
        
        @Override
        public void addressHeader(String name, List<EmailAddress> addresses) throws MIMEParseException {
            events.add("addressHeader:" + name + "=" + addresses);
            addressHeaders.put(name, new ArrayList<>(addresses));
        }
        
        @Override
        public void messageIDHeader(String name, List<ContentID> contentIDs) throws MIMEParseException {
            events.add("messageIDHeader:" + name + "=" + contentIDs);
            messageIDHeaders.put(name, new ArrayList<>(contentIDs));
        }
        
        @Override
        public void obsoleteStructure(ObsoleteStructureType type) throws MIMEParseException {
            events.add("obsoleteStructure:" + type);
            obsoleteStructures.add(type);
        }
    }
    
    private void parse(MessageParser parser, String content) throws MIMEParseException {
        ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
        parser.receive(buffer);
        parser.close();
    }
    
    // Date header tests
    
    @Test
    public void testDateHeader() throws MIMEParseException {
        // Using full RFC 5322 date format with time
        String content = "Date: Sat, 7 Dec 2024 14:30:00 +0000\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.dateHeaders.containsKey("Date"));
        OffsetDateTime date = handler.dateHeaders.get("Date");
        assertEquals(2024, date.getYear());
        assertEquals(12, date.getMonthValue());
        assertEquals(7, date.getDayOfMonth());
        assertEquals(14, date.getHour());
        assertEquals(30, date.getMinute());
        assertEquals(ZoneOffset.UTC, date.getOffset());
    }
    
    @Test
    public void testDateHeaderWithTimezone() throws MIMEParseException {
        String content = "Date: Fri, 6 Dec 2024 09:15:30 -0500\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.dateHeaders.containsKey("Date"));
        OffsetDateTime date = handler.dateHeaders.get("Date");
        assertEquals(ZoneOffset.ofHours(-5), date.getOffset());
    }
    
    @Test
    public void testResentDateHeader() throws MIMEParseException {
        String content = "Resent-Date: Mon, 9 Dec 2024 10:00:00 +0100\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.dateHeaders.containsKey("Resent-Date"));
    }
    
    // Address header tests
    
    @Test
    public void testFromHeader() throws MIMEParseException {
        String content = "From: alice@example.com\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.addressHeaders.containsKey("From"));
        List<EmailAddress> addresses = handler.addressHeaders.get("From");
        assertEquals(1, addresses.size());
        assertEquals("alice@example.com", addresses.get(0).getAddress());
    }
    
    @Test
    public void testFromHeaderWithDisplayName() throws MIMEParseException {
        String content = "From: Alice Smith <alice@example.com>\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        List<EmailAddress> addresses = handler.addressHeaders.get("From");
        assertEquals(1, addresses.size());
        assertEquals("alice@example.com", addresses.get(0).getAddress());
        assertEquals("Alice Smith", addresses.get(0).getDisplayName());
    }
    
    @Test
    public void testToHeaderMultipleRecipients() throws MIMEParseException {
        String content = "To: alice@example.com, bob@example.com\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.addressHeaders.containsKey("To"));
        List<EmailAddress> addresses = handler.addressHeaders.get("To");
        assertEquals(2, addresses.size());
        assertEquals("alice@example.com", addresses.get(0).getAddress());
        assertEquals("bob@example.com", addresses.get(1).getAddress());
    }
    
    @Test
    public void testCcHeader() throws MIMEParseException {
        String content = "Cc: manager@example.com\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.addressHeaders.containsKey("Cc"));
    }
    
    @Test
    public void testBccHeader() throws MIMEParseException {
        String content = "Bcc: secret@example.com\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.addressHeaders.containsKey("Bcc"));
    }
    
    @Test
    public void testReplyToHeader() throws MIMEParseException {
        String content = "Reply-To: replies@example.com\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.addressHeaders.containsKey("Reply-To"));
    }
    
    @Test
    public void testSenderHeader() throws MIMEParseException {
        String content = "Sender: secretary@example.com\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.addressHeaders.containsKey("Sender"));
    }
    
    // Message-ID header tests
    
    @Test
    public void testMessageIDHeader() throws MIMEParseException {
        String content = "Message-ID: <unique123@example.com>\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.messageIDHeaders.containsKey("Message-ID"));
        List<ContentID> ids = handler.messageIDHeaders.get("Message-ID");
        assertEquals(1, ids.size());
        assertEquals("unique123", ids.get(0).getLocalPart());
        assertEquals("example.com", ids.get(0).getDomain());
    }
    
    @Test
    public void testInReplyToHeader() throws MIMEParseException {
        String content = "In-Reply-To: <original123@example.com>\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.messageIDHeaders.containsKey("In-Reply-To"));
    }
    
    @Test
    public void testReferencesHeaderMultiple() throws MIMEParseException {
        String content = "References: <msg1@example.com> <msg2@example.com> <msg3@example.com>\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.messageIDHeaders.containsKey("References"));
        List<ContentID> ids = handler.messageIDHeaders.get("References");
        assertEquals(3, ids.size());
    }
    
    // Unstructured header tests
    
    @Test
    public void testSubjectHeader() throws MIMEParseException {
        String content = "Subject: Test email subject\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.unstructuredHeaders.containsKey("Subject"));
        assertEquals("Test email subject", handler.unstructuredHeaders.get("Subject"));
    }
    
    @Test
    public void testCommentsHeader() throws MIMEParseException {
        String content = "Comments: This is a comment\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.unstructuredHeaders.containsKey("Comments"));
    }
    
    @Test
    public void testReceivedHeader() throws MIMEParseException {
        String content = "Received: from mail.example.com by mx.example.org; Sat, 7 Dec 2024 12:00:00 +0000\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        // Received is treated as unstructured for now
        assertTrue(handler.unstructuredHeaders.containsKey("Received"));
    }
    
    @Test
    public void testCustomXHeader() throws MIMEParseException {
        String content = "X-Custom-Header: custom value\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.unstructuredHeaders.containsKey("X-Custom-Header"));
        assertEquals("custom value", handler.unstructuredHeaders.get("X-Custom-Header"));
    }
    
    // MIME headers (delegated to parent)
    
    @Test
    public void testContentTypeHeader() throws MIMEParseException {
        String content = "Content-Type: text/plain; charset=utf-8\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertNotNull(handler.contentType);
        assertTrue(handler.contentType.isMimeType("text", "plain"));
        assertEquals("utf-8", handler.contentType.getParameter("charset"));
    }
    
    @Test
    public void testMIMEVersionHeader() throws MIMEParseException {
        String content = "MIME-Version: 1.0\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        assertNotNull(handler.mimeVersion);
        assertEquals(MIMEVersion.VERSION_1_0, handler.mimeVersion);
    }
    
    // Complete message tests
    
    @Test
    public void testCompleteEmailMessage() throws MIMEParseException {
        String content = 
            "Date: Sat, 7 Dec 2024 14:30:00 +0000\r\n" +
            "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: Test message\r\n" +
            "Message-ID: <unique-id@example.com>\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "This is the message body.\r\n";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        // Verify all headers were parsed
        assertTrue(handler.dateHeaders.containsKey("Date"));
        assertTrue(handler.addressHeaders.containsKey("From"));
        assertTrue(handler.addressHeaders.containsKey("To"));
        assertTrue(handler.unstructuredHeaders.containsKey("Subject"));
        assertTrue(handler.messageIDHeaders.containsKey("Message-ID"));
        assertNotNull(handler.mimeVersion);
        assertNotNull(handler.contentType);
        
        // Verify body (body content includes line terminator, use trim to compare)
        assertEquals("This is the message body.", handler.body.toString().trim());
    }
    
    @Test
    public void testMultipartEmail() throws MIMEParseException {
        String content = 
            "Date: Sat, 7 Dec 2024 14:30:00 +0000\r\n" +
            "From: sender@example.com\r\n" +
            "To: recipient@example.com\r\n" +
            "Subject: Multipart test\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/mixed; boundary=boundary123\r\n" +
            "\r\n" +
            "--boundary123\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "Plain text part\r\n" +
            "--boundary123\r\n" +
            "Content-Type: text/html\r\n" +
            "\r\n" +
            "<p>HTML part</p>\r\n" +
            "--boundary123--\r\n";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        // Should have 3 entities: root + 2 parts
        assertEquals(3, handler.entityCount);
        assertTrue(handler.dateHeaders.containsKey("Date"));
        assertTrue(handler.addressHeaders.containsKey("From"));
    }
    
    // Invalid header tests
    
    @Test
    public void testInvalidDateHeader() throws MIMEParseException {
        String content = "Date: not a valid date\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        // Should be reported as unexpected
        assertTrue(handler.unexpectedHeaders.containsKey("Date"));
        assertFalse(handler.dateHeaders.containsKey("Date"));
    }
    
    @Test
    public void testInvalidAddressHeader() throws MIMEParseException {
        String content = "From: not a valid email address\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        // Should be reported as unexpected
        assertTrue(handler.unexpectedHeaders.containsKey("From"));
        assertFalse(handler.addressHeaders.containsKey("From"));
    }
    
    @Test
    public void testInvalidMessageIDHeader() throws MIMEParseException {
        String content = "Message-ID: not-a-valid-id\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        // Should be reported as unexpected
        assertTrue(handler.unexpectedHeaders.containsKey("Message-ID"));
        assertFalse(handler.messageIDHeaders.containsKey("Message-ID"));
    }
    
    // Parser configuration tests
    
    @Test
    public void testSetHandlerWithMessageHandler() throws MIMEParseException {
        String content = "Subject: Test\r\n\r\nBody";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        // Use setHandler instead of setMessageHandler
        parser.setHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.unstructuredHeaders.containsKey("Subject"));
    }
    
    @Test
    public void testReset() throws MIMEParseException {
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        // First parse
        parse(parser, "From: alice@example.com\r\n\r\nFirst");
        assertTrue(handler.addressHeaders.containsKey("From"));
        
        // Reset and parse again
        parser.reset();
        handler.addressHeaders.clear();
        handler.body = new StringBuilder();
        
        parse(parser, "From: bob@example.com\r\n\r\nSecond");
        assertEquals("bob@example.com", handler.addressHeaders.get("From").get(0).getAddress());
    }
    
    // Header case insensitivity tests
    
    @Test
    public void testHeaderCaseInsensitivity() throws MIMEParseException {
        String content = 
            "DATE: Sat, 7 Dec 2024 14:30:00 +0000\r\n" +
            "from: sender@example.com\r\n" +
            "MESSAGE-id: <test@example.com>\r\n" +
            "subject: Test\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        // All headers should be recognized regardless of case
        assertTrue(handler.dateHeaders.containsKey("DATE"));
        assertTrue(handler.addressHeaders.containsKey("from"));
        assertTrue(handler.messageIDHeaders.containsKey("MESSAGE-id"));
        assertTrue(handler.unstructuredHeaders.containsKey("subject"));
    }
    
    // Address with angle brackets
    
    @Test
    public void testAddressAngleBracketOnly() throws MIMEParseException {
        String content = "From: <alice@example.com>\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        List<EmailAddress> addresses = handler.addressHeaders.get("From");
        assertNotNull(addresses);
        assertEquals(1, addresses.size());
        assertEquals("alice@example.com", addresses.get(0).getAddress());
    }
    
    @Test
    public void testAddressQuotedDisplayName() throws MIMEParseException {
        String content = "From: \"Smith, Alice\" <alice@example.com>\r\n" +
            "\r\n" +
            "Body";
        
        TestMessageHandler handler = new TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        
        parse(parser, content);
        
        List<EmailAddress> addresses = handler.addressHeaders.get("From");
        assertNotNull(addresses);
        assertEquals(1, addresses.size());
        assertEquals("alice@example.com", addresses.get(0).getAddress());
        // The RFC5322AddressParser keeps quotes in quoted-string display names
        assertEquals("\"Smith, Alice\"", addresses.get(0).getDisplayName());
    }
}

