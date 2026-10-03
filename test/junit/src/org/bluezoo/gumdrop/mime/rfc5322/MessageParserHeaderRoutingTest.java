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
package org.bluezoo.gumdrop.mime.rfc5322;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.bluezoo.gumdrop.mime.MimeHandler;
import org.bluezoo.gumdrop.mime.MimeParseException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for how {@link MessageParser} routes each header family to the
 * structured parsers and falls back to obsolete syntax or an unexpected-header
 * event, feeding the message one byte at a time so every split point of the
 * header scanner is exercised.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MessageParserHeaderRoutingTest {

    private static MessageParserTest.TestMessageHandler run(String headers, boolean smtputf8)
            throws MimeParseException {
        MessageParserTest.TestMessageHandler handler = new MessageParserTest.TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setSmtputf8(smtputf8);
        parser.setMessageHandler(handler);
        byte[] bytes = (headers + "\r\nbody\r\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer pending = ByteBuffer.allocate(bytes.length + 1);
        for (int i = 0; i < bytes.length; i++) {
            pending.put(bytes[i]);
            pending.flip();
            parser.receive(pending);
            pending.compact();
        }
        parser.close();
        return handler;
    }

    private static MessageParserTest.TestMessageHandler run(String headers) throws MimeParseException {
        return run(headers, false);
    }

    @Test
    public void validAddressHeadersAreStructured() throws MimeParseException {
        MessageParserTest.TestMessageHandler h = run("To: Bob <bob@example.org>, carol@example.net\r\n"
            + "Cc: dave@example.com\r\n");
        List<EmailAddress> to = h.addressHeaders.get("To");
        assertEquals(2, to.size());
        assertEquals("Bob", to.get(0).getDisplayName());
        assertEquals("carol@example.net", to.get(1).getAddress());
        assertEquals("dave@example.com", h.addressHeaders.get("Cc").get(0).getAddress());
        assertTrue(h.obsoleteStructures.isEmpty());
    }

    @Test
    public void unparseableAddressBecomesUnexpectedHeader() throws MimeParseException {
        MessageParserTest.TestMessageHandler h = run("To: <<<\r\n");
        assertEquals("<<<", h.unexpectedHeaders.get("To"));
        assertFalse(h.addressHeaders.containsKey("To"));
    }

    @Test
    public void obsoleteAddressUsesFallbackAndReportsStructure() throws MimeParseException {
        MessageParserTest.TestMessageHandler h = run("To: @relay.example:user@host.example\r\n");
        assertEquals("user@host.example", h.addressHeaders.get("To").get(0).getAddress());
        assertEquals(1, h.obsoleteStructures.size());
        assertEquals(ObsoleteStructureType.OBSOLETE_ADDRESS_SYNTAX, h.obsoleteStructures.get(0));
    }

    @Test
    public void validMessageIdHeaders() throws MimeParseException {
        MessageParserTest.TestMessageHandler h = run("Message-ID: <abc@example.org>\r\n"
            + "References: <a@b.c> <d@e.f>\r\n");
        assertEquals(1, h.messageIDHeaders.get("Message-ID").size());
        assertEquals(2, h.messageIDHeaders.get("References").size());
        assertTrue(h.obsoleteStructures.isEmpty());
    }

    @Test
    public void bareMessageIdIsObsoleteSyntax() throws MimeParseException {
        MessageParserTest.TestMessageHandler h = run("Message-ID: abc@example.org\r\n");
        assertEquals("abc", h.messageIDHeaders.get("Message-ID").get(0).getLocalPart());
        assertEquals(ObsoleteStructureType.OBSOLETE_MESSAGE_ID_SYNTAX, h.obsoleteStructures.get(0));
    }

    @Test
    public void unparseableMessageIdBecomesUnexpectedHeader() throws MimeParseException {
        MessageParserTest.TestMessageHandler h = run("In-Reply-To: no-id-here\r\n");
        assertEquals("no-id-here", h.unexpectedHeaders.get("In-Reply-To"));
    }

    @Test
    public void dateHeaderCanonicalObsoleteAndInvalid() throws MimeParseException {
        MessageParserTest.TestMessageHandler good = run("Date: Fri, 21 Nov 1997 09:55:06 -0600\r\n");
        assertNotNull(good.dateHeaders.get("Date"));
        assertTrue(good.obsoleteStructures.isEmpty());

        MessageParserTest.TestMessageHandler obsolete = run("Date: 21 Nov 97 09:55:06 GMT\r\n");
        assertNotNull(obsolete.dateHeaders.get("Date"));
        assertEquals(ObsoleteStructureType.OBSOLETE_DATE_TIME_SYNTAX, obsolete.obsoleteStructures.get(0));

        MessageParserTest.TestMessageHandler bad = run("Date: yesterday-ish\r\n");
        assertEquals("yesterday-ish", bad.unexpectedHeaders.get("Date"));
        assertTrue(bad.dateHeaders.isEmpty());
    }

    @Test
    public void unstructuredAndReceivedHeadersAreDecoded() throws MimeParseException {
        MessageParserTest.TestMessageHandler h = run("Subject: =?UTF-8?Q?caf=C3=A9?=\r\n"
            + "X-Custom: value\r\n"
            + "Keywords: a, b\r\n"
            + "Comments: hello\r\n"
            + "Received: from a by b; Fri, 21 Nov 1997 09:55:06 -0600\r\n"
            + "X-Empty:\r\n"
            + "Ignored-Header: dropped\r\n");
        assertEquals("", h.unstructuredHeaders.get("X-Empty"));
        assertEquals("caf\u00e9", h.unstructuredHeaders.get("Subject"));
        assertEquals("value", h.unstructuredHeaders.get("X-Custom"));
        assertEquals("a, b", h.unstructuredHeaders.get("Keywords"));
        assertEquals("hello", h.unstructuredHeaders.get("Comments"));
        assertTrue(h.unstructuredHeaders.get("Received").startsWith("from a by b"));
        assertFalse(h.unstructuredHeaders.containsKey("Ignored-Header"));
        assertFalse(h.unexpectedHeaders.containsKey("Ignored-Header"));
    }

    @Test
    public void mimeHeadersAreDelegatedToTheBaseParser() throws MimeParseException {
        MessageParserTest.TestMessageHandler h = run("MIME-Version: 1.0\r\n"
            + "Content-Type: text/plain; charset=utf-8\r\n"
            + "Content-Transfer-Encoding: 7bit\r\n"
            + "Content-ID: <cid@example.org>\r\n"
            + "Content-Description: a body\r\n"
            + "Content-Disposition: inline\r\n");
        assertNotNull(h.mimeVersion);
        assertEquals("text", h.contentType.getPrimaryType());
        assertEquals("7bit", h.contentTransferEncoding);
        assertNotNull(h.contentID);
        assertEquals("a body", h.contentDescription);
        assertNotNull(h.contentDisposition);
    }

    @Test
    public void smtputf8EnablesNonAsciiAddresses() throws MimeParseException {
        MessageParser parser = new MessageParser();
        assertFalse(parser.isSmtputf8());
        parser.setSmtputf8(true);
        assertTrue(parser.isSmtputf8());
        MessageParserTest.TestMessageHandler h = run("From: <\u7528\u6237@\u4f8b.jp>\r\n"
            + "Subject: \u00fcber\r\n", true);
        assertEquals("\u7528\u6237@\u4f8b.jp", h.addressHeaders.get("From").get(0).getAddress());
        assertEquals("\u00fcber", h.unstructuredHeaders.get("Subject"));
        parser.reset();
        assertFalse(parser.isSmtputf8());
    }

    @Test
    public void structuredHeadersAreIgnoredWithoutAMessageHandler() throws MimeParseException {
        MessageParser parser = new MessageParser();
        PlainHandler plain = new PlainHandler();
        parser.setHandler(plain);
        byte[] bytes = ("Date: Fri, 21 Nov 1997 09:55:06 -0600\r\nTo: a@b.c\r\n"
            + "Message-ID: <a@b.c>\r\nReceived: x\r\nSubject: s\r\n\r\nbody\r\n")
            .getBytes(StandardCharsets.UTF_8);
        parser.receive(ByteBuffer.wrap(bytes));
        parser.close();
        assertEquals(1, plain.endHeadersCount);
    }

    @Test
    public void plainMimeHandlerDoesNotReplaceMessageHandler() throws MimeParseException {
        MessageParserTest.TestMessageHandler structured = new MessageParserTest.TestMessageHandler();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(structured);
        MimeHandler plain = new PlainHandler();
        parser.setHandler(plain);
        byte[] bytes = "To: a@b.c\r\n\r\nbody\r\n".getBytes(StandardCharsets.UTF_8);
        parser.receive(ByteBuffer.wrap(bytes));
        parser.close();
        assertEquals(1, structured.addressHeaders.size());
    }

    /** Minimal mime handler that ignores every event. */
    private static final class PlainHandler implements MimeHandler {
        int endHeadersCount;

        @Override
        public void setLocator(org.bluezoo.gumdrop.mime.MimeLocator locator) {
        }

        @Override
        public void startEntity(String boundary) {
        }

        @Override
        public void contentType(org.bluezoo.gumdrop.mime.ContentType contentType) {
        }

        @Override
        public void contentDisposition(org.bluezoo.gumdrop.mime.ContentDisposition contentDisposition) {
        }

        @Override
        public void contentTransferEncoding(String encoding) {
        }

        @Override
        public void contentID(org.bluezoo.gumdrop.mime.ContentID contentID) {
        }

        @Override
        public void contentDescription(String description) {
        }

        @Override
        public void mimeVersion(org.bluezoo.gumdrop.mime.MimeVersion version) {
        }

        @Override
        public void endHeaders() {
            endHeadersCount++;
        }

        @Override
        public void bodyContent(ByteBuffer content) {
        }

        @Override
        public void unexpectedContent(ByteBuffer content) {
        }

        @Override
        public void endEntity(String boundary) {
        }
    }
}
