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
            "Hello, World!\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        parse(parser, content);
        
        assertTrue(handler.events.contains("startEntity:null"));
        assertTrue(handler.events.contains("contentType:text/plain"));
        assertTrue(handler.events.contains("endHeaders"));
        assertTrue(handler.events.contains("bodyContent"));
        assertTrue(handler.events.contains("endEntity:null"));
        assertEquals("Hello, World!\r\n", handler.body.toString());
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
            "Plain text body\r\n";
        
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
        
        // Feed data in small chunks with proper buffer management
        String content = "Content-Type: text/plain\r\n\r\nHello, World!\r\n";
        byte[][] chunks = {
            "Content-Type".getBytes(StandardCharsets.UTF_8),
            ": text/plain\r\n".getBytes(StandardCharsets.UTF_8),
            "\r\n".getBytes(StandardCharsets.UTF_8),
            "Hello".getBytes(StandardCharsets.UTF_8),
            ", World!\r\n".getBytes(StandardCharsets.UTF_8)
        };
        
        parseWithCompact(parser, chunks);
        
        assertNotNull(handler.contentType);
        assertEquals("Hello, World!\r\n", handler.body.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Split Token Tests - verifying buffer compact() contract
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Helper to parse content with proper buffer management.
     * Simulates a channel read loop with compact() calls.
     * 
     * <p>Follows the standard non-blocking I/O pattern:
     * read → flip → receive → compact → repeat
     */
    private void parseWithCompact(MIMEParser parser, byte[][] chunks) throws MIMEParseException {
        ByteBuffer buffer = ByteBuffer.allocate(256);
        for (byte[] chunk : chunks) {
            // Simulate reading chunk into buffer
            buffer.put(chunk);
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
        // At EOF, ensure no unconsumed data remains
        assertFalse("Parser has unconsumed data at EOF", parser.isUnderflow());
        parser.close();
    }

    /**
     * Helper to split a string into chunks at specific positions.
     */
    private byte[][] splitAt(String content, int... positions) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[][] result = new byte[positions.length + 1][];
        int prev = 0;
        for (int i = 0; i < positions.length; i++) {
            int pos = positions[i];
            result[i] = new byte[pos - prev];
            System.arraycopy(bytes, prev, result[i], 0, pos - prev);
            prev = pos;
        }
        result[positions.length] = new byte[bytes.length - prev];
        System.arraycopy(bytes, prev, result[positions.length], 0, bytes.length - prev);
        return result;
    }

    // ── Header Line CRLF Split Tests ──

    @Test
    public void testSplitCRLF() throws MIMEParseException {
        // Split between CR and LF - content must end with newline
        String content = "Content-Type: text/plain\r\n\r\nBody\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split right between \r and \n of first header line
        parseWithCompact(parser, splitAt(content, 25)); // after \r, before \n
        
        assertNotNull(handler.contentType);
        assertEquals("text/plain", handler.contentType.toString());
        assertEquals("Body\r\n", handler.body.toString());
    }

    @Test
    public void testSplitEmptyLineCRLF() throws MIMEParseException {
        // Split the empty line CRLF that ends headers
        String content = "Content-Type: text/plain\r\n\r\nBody text\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split between the two \r\n of the empty line
        parseWithCompact(parser, splitAt(content, 27)); // after first \r\n, in middle of empty line
        
        assertTrue(handler.events.contains("endHeaders"));
        assertEquals("Body text\r\n", handler.body.toString());
    }

    @Test
    public void testSplitHeaderName() throws MIMEParseException {
        // Split in the middle of a header name
        String content = "Content-Type: text/plain\r\n\r\nBody\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split "Content-Type" as "Cont" + "ent-Type"
        parseWithCompact(parser, splitAt(content, 4));
        
        assertNotNull(handler.contentType);
        assertEquals("text/plain", handler.contentType.toString());
    }

    @Test
    public void testSplitHeaderValue() throws MIMEParseException {
        // Split in the middle of a header value
        String content = "Content-Type: text/plain\r\n\r\nBody\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split "text/plain" as "text/" + "plain"
        parseWithCompact(parser, splitAt(content, 19));
        
        assertNotNull(handler.contentType);
        assertEquals("text/plain", handler.contentType.toString());
    }

    @Test
    public void testSplitFoldedHeader() throws MIMEParseException {
        // Split a folded header at the fold point
        String content = "Content-Type: text/plain;\r\n charset=utf-8\r\n\r\nBody\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split at the fold (after first line CRLF)
        parseWithCompact(parser, splitAt(content, 27));
        
        assertNotNull(handler.contentType);
        assertEquals("utf-8", handler.contentType.getParameter("charset"));
    }

    @Test
    public void testSplitFoldedHeaderMidContinuation() throws MIMEParseException {
        // Split in the middle of the continuation line
        String content = "Content-Type: text/plain;\r\n charset=utf-8\r\n\r\nBody\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split the continuation line "charset=utf-8"
        parseWithCompact(parser, splitAt(content, 35)); // in middle of "charset"
        
        assertNotNull(handler.contentType);
        assertEquals("utf-8", handler.contentType.getParameter("charset"));
    }

    // ── Multipart Boundary Split Tests ──

    @Test
    public void testSplitBoundaryDashes() throws MIMEParseException {
        // Split the "--" prefix of a boundary
        String content = "Content-Type: multipart/mixed; boundary=abc\r\n\r\n" +
            "--abc\r\n\r\nPart1\r\n--abc--\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split right between the two dashes of "--abc"
        int boundaryStart = content.indexOf("--abc");
        parseWithCompact(parser, splitAt(content, boundaryStart + 1)); // between first and second dash
        
        assertEquals(2, handler.entityCount); // root + 1 part
    }

    @Test
    public void testSplitBoundaryText() throws MIMEParseException {
        // Split in the middle of the boundary text
        String content = "Content-Type: multipart/mixed; boundary=boundary123\r\n\r\n" +
            "--boundary123\r\n\r\nPart1\r\n--boundary123--\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split "boundary123" as "bound" + "ary123"
        int boundaryStart = content.indexOf("--boundary123");
        parseWithCompact(parser, splitAt(content, boundaryStart + 7)); // in middle of "boundary"
        
        assertEquals(2, handler.entityCount); // root + 1 part
    }

    @Test
    public void testSplitEndBoundaryMarker() throws MIMEParseException {
        // Split the "--" suffix of an end boundary
        String content = "Content-Type: multipart/mixed; boundary=abc\r\n\r\n" +
            "--abc\r\n\r\nPart\r\n--abc--\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split "--abc--" between the second pair of dashes
        int endBoundary = content.lastIndexOf("--abc--");
        parseWithCompact(parser, splitAt(content, endBoundary + 6)); // between trailing --
        
        assertEquals(2, handler.entityCount);
        assertTrue(handler.events.stream().anyMatch(e -> e.contains("endEntity:abc")));
    }

    @Test
    public void testSplitBoundaryCRLF() throws MIMEParseException {
        // Split the CRLF after a boundary line
        String content = "Content-Type: multipart/mixed; boundary=abc\r\n\r\n" +
            "--abc\r\n\r\nPart1\r\n--abc--\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split after "--abc" but before \r\n
        int afterBoundary = content.indexOf("--abc") + 5;
        parseWithCompact(parser, splitAt(content, afterBoundary + 1)); // between \r and \n
        
        assertEquals(2, handler.entityCount);
    }

    // ── BASE64 Encoded Content Split Tests ──

    @Test
    public void testSplitBase64Character() throws MIMEParseException {
        // "Hello" in Base64 is "SGVsbG8="
        String content = "Content-Transfer-Encoding: base64\r\n\r\nSGVsbG8=\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split in middle of base64 content
        parseWithCompact(parser, splitAt(content, 40)); // in middle of "SGVsbG8="
        
        assertEquals("Hello", handler.body.toString().trim());
    }

    @Test
    public void testSplitBase64Padding() throws MIMEParseException {
        // "Hi" in Base64 is "SGk=" (with padding)
        String content = "Content-Transfer-Encoding: base64\r\n\r\nSGk=\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split right before the padding
        int paddingPos = content.indexOf("=");
        parseWithCompact(parser, splitAt(content, paddingPos));
        
        assertEquals("Hi", handler.body.toString().trim());
    }

    @Test
    public void testSplitBase64MultiLine() throws MIMEParseException {
        // Longer content that spans multiple base64 lines
        // "Hello, World!" = "SGVsbG8sIFdvcmxkIQ=="
        String content = "Content-Transfer-Encoding: base64\r\n\r\n" +
            "SGVsbG8sIFdv\r\ncmxkIQ==\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split at the line break in base64
        int crlfPos = content.indexOf("\r\n", 37);
        parseWithCompact(parser, splitAt(content, crlfPos + 1)); // between \r and \n
        
        assertEquals("Hello, World!", handler.body.toString().trim());
    }

    // ── Quoted-Printable Encoded Content Split Tests ──

    @Test
    public void testSplitQuotedPrintableEncoded() throws MIMEParseException {
        // "=" is encoded as "=3D"
        String content = "Content-Transfer-Encoding: quoted-printable\r\n\r\na=3Db\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split "=3D" as "=" + "3D"
        int encodedPos = content.indexOf("=3D");
        parseWithCompact(parser, splitAt(content, encodedPos + 1));
        
        assertEquals("a=b", handler.body.toString().trim());
    }

    @Test
    public void testSplitQuotedPrintableHexDigits() throws MIMEParseException {
        // Split in the middle of hex digits
        String content = "Content-Transfer-Encoding: quoted-printable\r\n\r\na=3Db\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split "=3D" as "=3" + "D"
        int encodedPos = content.indexOf("=3D");
        parseWithCompact(parser, splitAt(content, encodedPos + 2));
        
        assertEquals("a=b", handler.body.toString().trim());
    }

    @Test
    public void testSplitQuotedPrintableSoftLineBreak() throws MIMEParseException {
        // Soft line break "=\r\n" should be removed
        String content = "Content-Transfer-Encoding: quoted-printable\r\n\r\nHello=\r\nWorld\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split after the "=" soft break marker
        int softBreak = content.indexOf("=\r\n");
        parseWithCompact(parser, splitAt(content, softBreak + 1)); // after =, before \r
        
        String body = handler.body.toString().trim();
        assertTrue(body.contains("Hello") && body.contains("World"));
    }

    // ── Multiple Split Points Tests ──

    @Test
    public void testMultipleSplitPoints() throws MIMEParseException {
        // Split at multiple points - content must end with newline
        String content = "Content-Type: text/plain\r\n\r\nHello, World!\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split at multiple points: header, CRLF, body
        parseWithCompact(parser, splitAt(content, 10, 20, 28, 35));
        
        assertNotNull(handler.contentType);
        assertEquals("Hello, World!\r\n", handler.body.toString());
    }

    @Test
    public void testByteByByteParsing() throws MIMEParseException {
        // Feed content byte by byte - extreme case
        String content = "Content-Type: text/plain\r\n\r\nHi\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Create byte-by-byte chunks
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[][] chunks = new byte[bytes.length][];
        for (int i = 0; i < bytes.length; i++) {
            chunks[i] = new byte[] { bytes[i] };
        }
        
        parseWithCompact(parser, chunks);
        
        assertNotNull(handler.contentType);
        assertEquals("Hi\r\n", handler.body.toString());
    }

    @Test
    public void testMultipartByteByByte() throws MIMEParseException {
        // Multipart message byte by byte - tests boundary detection with splits
        String content = "Content-Type: multipart/mixed; boundary=X\r\n\r\n" +
            "--X\r\n\r\nA\r\n--X--\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Create byte-by-byte chunks
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[][] chunks = new byte[bytes.length][];
        for (int i = 0; i < bytes.length; i++) {
            chunks[i] = new byte[] { bytes[i] };
        }
        
        parseWithCompact(parser, chunks);
        
        assertEquals(2, handler.entityCount); // root + 1 part
    }

    // ── Edge Cases ──

    @Test
    public void testSplitAtEveryPosition() throws MIMEParseException {
        // Test splitting at every possible position in a simple message
        String content = "Content-Type: text/plain\r\n\r\nBody\r\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        
        for (int splitPos = 1; splitPos < bytes.length; splitPos++) {
            TestHandler handler = new TestHandler();
            MIMEParser parser = new MIMEParser();
            parser.setHandler(handler);
            
            parseWithCompact(parser, splitAt(content, splitPos));
            
            assertNotNull("Split at " + splitPos + " failed", handler.contentType);
            assertEquals("Split at " + splitPos + " wrong body", "Body\r\n", handler.body.toString());
        }
    }

    @Test
    public void testSplitLongBoundary() throws MIMEParseException {
        // Test with a longer boundary to ensure all split positions work
        String boundary = "----=_Part_0_1234567890.1234567890";
        String content = "Content-Type: multipart/mixed; boundary=\"" + boundary + "\"\r\n\r\n" +
            "--" + boundary + "\r\n\r\nContent\r\n" +
            "--" + boundary + "--\r\n";
        
        TestHandler handler = new TestHandler();
        MIMEParser parser = new MIMEParser();
        parser.setHandler(handler);
        
        // Split in the middle of the long boundary
        int boundaryStart = content.indexOf("--" + boundary);
        parseWithCompact(parser, splitAt(content, boundaryStart + 20));
        
        assertEquals(2, handler.entityCount);
    }
}

