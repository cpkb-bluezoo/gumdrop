/*
 * MimeParserBodyStreamingTest.java
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
package org.bluezoo.gumdrop.mime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests that {@link MimeParser} consumes a body as it arrives, however long
 * its lines are, so that the caller's buffer never has to hold a line.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MimeParserBodyStreamingTest {

    /** Counts content and remembers the largest piece. */
    static final class Counter implements MimeHandler {
        long body;
        long unexpected;
        int largest;
        int starts;
        int ends;
        byte first = -1;
        byte last = -1;
        final StringBuilder text = new StringBuilder();

        @Override
        public void setLocator(MimeLocator locator) {
        }

        @Override
        public void startEntity(String boundary) {
            starts++;
        }

        @Override
        public void contentType(ContentType contentType) {
        }

        @Override
        public void contentDisposition(ContentDisposition contentDisposition) {
        }

        @Override
        public void contentTransferEncoding(String encoding) {
        }

        @Override
        public void contentID(ContentID contentID) {
        }

        @Override
        public void contentDescription(String description) {
        }

        @Override
        public void mimeVersion(MimeVersion version) {
        }

        @Override
        public void endHeaders() {
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            largest = Math.max(largest, data.remaining());
            body += data.remaining();
            while (data.hasRemaining()) {
                byte b = data.get();
                if (first < 0) {
                    first = b;
                }
                last = b;
            }
        }

        @Override
        public void unexpectedContent(ByteBuffer data) {
            largest = Math.max(largest, data.remaining());
            unexpected += data.remaining();
            data.position(data.limit());
        }

        @Override
        public void endEntity(String boundary) {
            ends++;
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Feeds the text through a buffer of the given capacity, a buffer's worth
     * at a time, and checks that every byte of a body state is consumed.
     */
    private static void feed(MimeParser parser, byte[] data, int capacity,
            boolean mustConsumeAll) throws MimeParseException {
        ByteBuffer buf = ByteBuffer.allocate(capacity);
        int off = 0;
        while (off < data.length) {
            int n = Math.min(buf.remaining(), data.length - off);
            buf.put(data, off, n);
            off += n;
            buf.flip();
            parser.receive(buf);
            if (mustConsumeAll) {
                assertFalse("the parser must not wait for a line ending",
                    buf.hasRemaining());
            }
            buf.compact();
        }
        buf.flip();
        parser.receive(buf);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    public void bodyWithNoLineEndingIsConsumedAsItArrives() throws Exception {
        Counter counter = new Counter();
        MimeParser parser = new MimeParser();
        parser.setHandler(counter);
        String text = "Content-Type: application/octet-stream\r\n\r\n" + repeat('x', 1000000);
        // 128 bytes holds the header lines; the body is a single line of 1 MB
        ByteBuffer headers = ByteBuffer.wrap(bytes(text.substring(0, 42)));
        parser.receive(headers);
        feed(parser, bytes(text.substring(42)), 128, true);
        parser.close();
        assertEquals(1000000L, counter.body);
        assertTrue("pieces are bounded: " + counter.largest, counter.largest <= 4096);
        assertEquals(1, counter.ends);
    }

    @Test
    public void multipartPartWithOneEnormousLineIsStreamed() throws Exception {
        Counter counter = new Counter();
        MimeParser parser = new MimeParser();
        parser.setHandler(counter);
        String head = "Content-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\n\r\n";
        String text = head + repeat('y', 500000) + "\r\n--b--\r\n";
        byte[] data = bytes(text);
        // the headers (about 40 bytes a line) fit in 128 bytes
        feed(parser, data, 128, false);
        parser.close();
        // The line ending before the delimiter is not part of the content
        assertEquals(500000L, counter.body);
        assertTrue("pieces are bounded: " + counter.largest, counter.largest <= 4096);
        assertEquals(2, counter.starts);
        assertEquals(2, counter.ends);
    }

    @Test
    public void multipartBodyIsConsumedAsItArrives() throws Exception {
        Counter counter = new Counter();
        MimeParser parser = new MimeParser();
        parser.setHandler(counter);
        ByteBuffer head = ByteBuffer.wrap(
            bytes("Content-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\n\r\n"));
        parser.receive(head);
        // the part is a single line that is longer than any buffer
        feed(parser, bytes(repeat('z', 300000)), 64, true);
        assertEquals(300000L, counter.body);
    }

    @Test
    public void enormousLineStartingWithHyphensIsContent() throws Exception {
        Counter counter = new Counter();
        MimeParser parser = new MimeParser();
        parser.setHandler(counter);
        String head = "Content-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\n\r\n";
        byte[] data = bytes(head + repeat('-', 200000) + "\r\n--b--\r\n");
        feed(parser, data, 128, false);
        parser.close();
        assertEquals(200000L, counter.body);
    }

    @Test
    public void transportPaddingAfterBoundaryIsAccepted() throws Exception {
        Counter counter = new Counter();
        MimeParser parser = new MimeParser();
        parser.setHandler(counter);
        String text = "Content-Type: multipart/mixed; boundary=b\r\n\r\n"
            + "--b \t \r\n\r\npart\r\n--b--  \r\n";
        feed(parser, bytes(text), 4096, false);
        parser.close();
        assertEquals(4L, counter.body);
        assertEquals(2, counter.starts);
        assertEquals(2, counter.ends);
    }

    @Test
    public void paddingBeyondTheLimitIsContent() throws Exception {
        Counter counter = new Counter();
        MimeParser parser = new MimeParser();
        parser.setHandler(counter);
        String text = "Content-Type: multipart/mixed; boundary=b\r\n\r\n"
            + "--b\r\n\r\nA\r\n--b" + repeat(' ', 200) + "\r\nB\r\n--b--\r\n";
        feed(parser, bytes(text), 4096, false);
        parser.close();
        // the over-padded line is not a delimiter, so it and B are content
        assertEquals(1, counter.starts - 1);
        assertEquals(1L + 2 + 3 + 200 + 2 + 1, counter.body);
    }

    @Test
    public void bodySplitAtEveryPositionGivesTheSameContent() throws Exception {
        String text = "Content-Type: multipart/mixed; boundary=bb\r\n\r\npre\r\n"
            + "--bb\r\n\r\n-a line\r\n--\r\n--b\r\n\r\n--bb--\r\nepi";
        byte[] data = bytes(text);
        String expected = null;
        for (int chunk = 1; chunk <= data.length; chunk++) {
            Counter counter = new Counter();
            MimeParser parser = new MimeParser();
            parser.setHandler(counter);
            ByteBuffer buf = ByteBuffer.allocate(data.length + 8);
            for (int off = 0; off < data.length; off += chunk) {
                buf.put(data, off, Math.min(chunk, data.length - off));
                buf.flip();
                parser.receive(buf);
                buf.compact();
            }
            buf.flip();
            parser.receive(buf);
            try {
                parser.close();
            } catch (MimeParseException e) {
                // an unterminated epilogue line is reported identically
            }
            String got = counter.body + "/" + counter.unexpected
                + "/" + counter.starts + "/" + counter.ends;
            if (expected == null) {
                expected = got;
            }
            assertEquals("chunk " + chunk, expected, got);
        }
        // "-a line", "--" and "--b" with their endings; the ending of the
        // blank line before the closing delimiter belongs to the delimiter
        assertTrue(expected, expected.startsWith("18/"));
    }
}
