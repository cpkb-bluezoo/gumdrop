/*
 * MIMEParserTest.java
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

package org.bluezoo.gumdrop.mime;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MIMEParser}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MIMEParserTest {

    /**
     * Test handler that records events for verification.
     */
    static class TestHandler implements MIMEHandler {
        
        List<String> events = new ArrayList<>();
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
    }
    
    private void parse(MIMEParser parser, String content) throws MIMEParseException {
        ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
        parser.receive(buffer);
        parser.close();
    }
    
    @Test
    public void testSimpleEntity() throws MIMEParseException {
        String content = "Content-Type: text/plain\r\n" +
            "\r\n" +
            "Hello, World!";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.events.contains("startEntity:null"));
        assertTrue(handler.events.contains("contentType:text/plain"));
        assertTrue(handler.events.contains("endHeaders"));
        assertTrue(handler.events.contains("bodyContent"));
        assertTrue(handler.events.contains("endEntity:null"));
        assertEquals("Hello, World!", handler.body.toString());
    }
    
    @Test
    public void testContentTypeWithCharset() throws MIMEParseException {
        String content = "Content-Type: text/html; charset=utf-8\r\n" +
            "\r\n" +
            "<html>Test</html>";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        parse(parser, content);
        
        assertNotNull(handler.contentType);
        assertTrue(handler.contentType.isMimeType("text", "html"));
        assertEquals("utf-8", handler.contentType.getParameter("charset"));
    }
    
    @Test
    public void testContentDisposition() throws MIMEParseException {
        String content = "Content-Disposition: attachment; filename=\"report.pdf\"\r\n" +
            "\r\n" +
            "PDF content";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        parse(parser, content);
        
        assertNotNull(handler.contentDisposition);
        assertTrue(handler.contentDisposition.isDispositionType("attachment"));
        assertEquals("report.pdf", handler.contentDisposition.getParameter("filename"));
    }
    
    @Test
    public void testContentTransferEncoding() throws MIMEParseException {
        String content = "Content-Transfer-Encoding: base64\r\n" +
            "\r\n" +
            "SGVsbG8=";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        parse(parser, content);
        
        assertEquals("base64", handler.contentTransferEncoding);
    }
    
    @Test
    public void testMIMEVersion() throws MIMEParseException {
        String content = "MIME-Version: 1.0\r\n" +
            "\r\n" +
            "Body";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        parse(parser, content);
        
        assertNotNull(handler.mimeVersion);
        assertEquals(MIMEVersion.VERSION_1_0, handler.mimeVersion);
    }
    
    @Test
    public void testMultipartBasic() throws MIMEParseException {
        String content = "Content-Type: multipart/mixed; boundary=boundary123\r\n" +
            "\r\n" +
            "--boundary123\r\n" +
            "\r\n" +
            "Part 1\r\n" +
            "--boundary123\r\n" +
            "\r\n" +
            "<p>Part 2</p>\r\n" +
            "--boundary123--\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        parse(parser, content);
        
        // Should have 3 entities: root + 2 parts
        assertEquals(3, handler.entityCount);
    }
    
    @Test
    public void testEmptyBody() throws MIMEParseException {
        String content = "Content-Type: text/plain\r\n" +
            "\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.events.contains("endHeaders"));
        assertEquals("", handler.body.toString());
    }
    
    @Test
    public void testNoContentType() throws MIMEParseException {
        // Without Content-Type, default is text/plain
        String content = "Subject: Test\r\n" +
            "\r\n" +
            "Plain text body";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.events.contains("startEntity:null"));
        assertTrue(handler.events.contains("endHeaders"));
        assertTrue(handler.body.toString().contains("Plain text body"));
    }
    
    @Test
    public void testFoldedHeader() throws MIMEParseException {
        String content = "Content-Type: text/plain;\r\n" +
            "  charset=utf-8\r\n" +
            "\r\n" +
            "Body";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        parse(parser, content);
        
        assertNotNull(handler.contentType);
        assertEquals("utf-8", handler.contentType.getParameter("charset"));
    }
    
    @Test
    public void testReset() throws MIMEParseException {
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        String content1 = "Content-Type: text/plain\r\n\r\nFirst";
        parse(parser, content1);
        
        assertEquals(1, handler.entityCount);
        
        // Reset and parse again
        parser.reset();
        handler.entityCount = 0;
        handler.events.clear();
        handler.body = new StringBuilder();
        
        String content2 = "Content-Type: text/html\r\n\r\n<p>Second</p>";
        parse(parser, content2);
        
        assertEquals(1, handler.entityCount);
        assertTrue(handler.contentType.isMimeType("text", "html"));
    }
    
    @Test
    public void testIncrementalParsing() throws MIMEParseException {
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Feed data in small chunks
        parser.receive(ByteBuffer.wrap("Content-Type".getBytes(StandardCharsets.UTF_8)));
        parser.receive(ByteBuffer.wrap(": text/plain\r\n".getBytes(StandardCharsets.UTF_8)));
        parser.receive(ByteBuffer.wrap("\r\n".getBytes(StandardCharsets.UTF_8)));
        parser.receive(ByteBuffer.wrap("Hello".getBytes(StandardCharsets.UTF_8)));
        parser.receive(ByteBuffer.wrap(", World!".getBytes(StandardCharsets.UTF_8)));
        parser.close();
        
        assertNotNull(handler.contentType);
        assertEquals("Hello, World!", handler.body.toString());
    }
}

