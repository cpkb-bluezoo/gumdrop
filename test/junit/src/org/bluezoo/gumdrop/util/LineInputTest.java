/*
 * LineInputTest.java
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

package org.bluezoo.gumdrop.util;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for LineInput interface.
 */
public class LineInputTest {

    /**
     * Simple implementation of LineInput for testing.
     */
    static class TestLineInput implements LineInput {

        private ByteBuffer buffer;
        private CharBuffer charBuffer;

        TestLineInput(String content) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            this.buffer = ByteBuffer.allocate(bytes.length);
            this.buffer.put(bytes);
            this.buffer.flip();
        }

        TestLineInput(byte[] content) {
            this.buffer = ByteBuffer.allocate(content.length);
            this.buffer.put(content);
            this.buffer.flip();
        }

        TestLineInput(int capacity) {
            this.buffer = ByteBuffer.allocate(capacity);
        }

        void appendContent(String content) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            int pos = buffer.position();
            buffer.position(buffer.limit());
            buffer.limit(buffer.capacity());
            buffer.put(bytes);
            buffer.position(pos);
            buffer.limit(buffer.position() + pos + bytes.length);
            buffer.flip();
        }

        @Override
        public ByteBuffer getLineInputBuffer() {
            return buffer;
        }

        @Override
        public CharBuffer getOrCreateLineInputCharacterSink(int capacity) {
            if (charBuffer == null || charBuffer.capacity() < capacity) {
                charBuffer = CharBuffer.allocate(capacity);
            }
            return charBuffer;
        }
    }

    private CharsetDecoder utf8Decoder() {
        return StandardCharsets.UTF_8.newDecoder();
    }

    @Test
    public void testSingleLine() throws IOException {
        TestLineInput input = new TestLineInput("Hello, World!\r\n");

        String line = input.readLine(utf8Decoder());
        assertEquals("Hello, World!", line);
    }

    @Test
    public void testMultipleLines() throws IOException {
        TestLineInput input = new TestLineInput("Line 1\r\nLine 2\r\nLine 3\r\n");

        assertEquals("Line 1", input.readLine(utf8Decoder()));
        assertEquals("Line 2", input.readLine(utf8Decoder()));
        assertEquals("Line 3", input.readLine(utf8Decoder()));
    }

    @Test
    public void testEmptyLine() throws IOException {
        TestLineInput input = new TestLineInput("\r\n");

        String line = input.readLine(utf8Decoder());
        assertEquals("", line);
    }

    @Test
    public void testMultipleEmptyLines() throws IOException {
        TestLineInput input = new TestLineInput("\r\n\r\n\r\n");

        assertEquals("", input.readLine(utf8Decoder()));
        assertEquals("", input.readLine(utf8Decoder()));
        assertEquals("", input.readLine(utf8Decoder()));
    }

    @Test
    public void testNoCRLF() throws IOException {
        TestLineInput input = new TestLineInput("No line ending");

        String line = input.readLine(utf8Decoder());
        assertNull(line); // No CRLF found
    }

    @Test
    public void testOnlyCR() throws IOException {
        TestLineInput input = new TestLineInput("Only CR\r");

        String line = input.readLine(utf8Decoder());
        assertNull(line); // Need both CR and LF
    }

    @Test
    public void testOnlyLF() throws IOException {
        TestLineInput input = new TestLineInput("Only LF\n");

        String line = input.readLine(utf8Decoder());
        assertNull(line); // Need CR before LF
    }

    @Test
    public void testLFThenCR() throws IOException {
        TestLineInput input = new TestLineInput("Wrong order\n\r");

        String line = input.readLine(utf8Decoder());
        assertNull(line); // Need CR before LF, not after
    }

    @Test
    public void testMixedLineEndings() throws IOException {
        // Only CRLF lines should be recognized
        TestLineInput input = new TestLineInput("Line 1\r\nLine 2\nLine 3\r");

        assertEquals("Line 1", input.readLine(utf8Decoder()));
        assertNull(input.readLine(utf8Decoder())); // Remaining content has no proper CRLF
    }

    @Test
    public void testHttpRequest() throws IOException {
        String request = "GET /index.html HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "User-Agent: Test\r\n" +
            "\r\n";
        TestLineInput input = new TestLineInput(request);

        assertEquals("GET /index.html HTTP/1.1", input.readLine(utf8Decoder()));
        assertEquals("Host: localhost", input.readLine(utf8Decoder()));
        assertEquals("User-Agent: Test", input.readLine(utf8Decoder()));
        assertEquals("", input.readLine(utf8Decoder())); // Empty line marks end of headers
    }

    @Test
    public void testUTF8Content() throws IOException {
        TestLineInput input = new TestLineInput("Héllo, Wörld! 日本語\r\n");

        String line = input.readLine(utf8Decoder());
        assertEquals("Héllo, Wörld! 日本語", line);
    }

    @Test
    public void testLongLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("x");
        }
        sb.append("\r\n");

        TestLineInput input = new TestLineInput(sb.toString());

        String line = input.readLine(utf8Decoder());
        assertNotNull(line);
        assertEquals(1000, line.length());
    }

    @Test
    public void testSpecialCharacters() throws IOException {
        TestLineInput input = new TestLineInput("Tab\tand spaces   end\r\n");

        assertEquals("Tab\tand spaces   end", input.readLine(utf8Decoder()));
    }

    @Test
    public void testBufferCompaction() throws IOException {
        // Test that buffer is properly compacted after reading a line
        TestLineInput input = new TestLineInput("Short\r\nLonger line here\r\n");

        assertEquals("Short", input.readLine(utf8Decoder()));
        assertEquals("Longer line here", input.readLine(utf8Decoder()));
    }

    @Test
    public void testToStringHelper() {
        ByteBuffer buf = ByteBuffer.wrap("Test\r\n".getBytes(StandardCharsets.UTF_8));
        String result = LineInput.toString(buf);
        assertEquals("Test\r\n", result);
        assertEquals(0, buf.position()); // Position should be restored
    }

    @Test
    public void testEmptyBuffer() throws IOException {
        TestLineInput input = new TestLineInput("");

        String line = input.readLine(utf8Decoder());
        assertNull(line);
    }

    @Test
    public void testBinaryWithCRLF() throws IOException {
        // Binary data that happens to contain CRLF
        byte[] data = new byte[] { 0x00, 0x01, 0x02, '\r', '\n' };
        TestLineInput input = new TestLineInput(data);

        String line = input.readLine(utf8Decoder());
        assertNotNull(line);
        assertEquals(3, line.length());
    }

    @Test
    public void testHttpHeaders() throws IOException {
        String headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: 1234\r\n" +
            "Set-Cookie: session=abc123; Path=/\r\n" +
            "\r\n";
        TestLineInput input = new TestLineInput(headers);

        assertEquals("HTTP/1.1 200 OK", input.readLine(utf8Decoder()));
        assertEquals("Content-Type: text/html; charset=utf-8", input.readLine(utf8Decoder()));
        assertEquals("Content-Length: 1234", input.readLine(utf8Decoder()));
        assertEquals("Set-Cookie: session=abc123; Path=/", input.readLine(utf8Decoder()));
        assertEquals("", input.readLine(utf8Decoder()));
    }

    @Test
    public void testColonInContent() throws IOException {
        // HTTP headers contain colons
        TestLineInput input = new TestLineInput("Header: Value: With: Colons\r\n");

        assertEquals("Header: Value: With: Colons", input.readLine(utf8Decoder()));
    }

    @Test
    public void testQuotedContent() throws IOException {
        TestLineInput input = new TestLineInput("Content-Disposition: attachment; filename=\"test.txt\"\r\n");

        assertEquals("Content-Disposition: attachment; filename=\"test.txt\"", input.readLine(utf8Decoder()));
    }

    @Test
    public void testEmbeddedCR() throws IOException {
        // CR not followed by LF should not trigger line end
        TestLineInput input = new TestLineInput("Line with\rembedded CR\r\n");

        String line = input.readLine(utf8Decoder());
        assertEquals("Line with\rembedded CR", line);
    }

    @Test
    public void testConsecutiveCRLFs() throws IOException {
        // Multiple CRLF in a row
        TestLineInput input = new TestLineInput("Line1\r\n\r\n\r\nLine2\r\n");

        assertEquals("Line1", input.readLine(utf8Decoder()));
        assertEquals("", input.readLine(utf8Decoder()));
        assertEquals("", input.readLine(utf8Decoder()));
        assertEquals("Line2", input.readLine(utf8Decoder()));
    }
}

