/*
 * DotStufferAndResponseTest.java
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

package org.bluezoo.gumdrop.smtp.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link DotStuffer} (RFC 5321 section 4.5.2 transparency on
 * the sending side) and {@link SmtpResponse}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DotStufferAndResponseTest {

    /** Collects everything written to it. */
    private static final class Collector implements WritableByteChannel {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        @Override
        public int write(ByteBuffer src) throws IOException {
            int n = src.remaining();
            byte[] bytes = new byte[n];
            src.get(bytes);
            out.write(bytes);
            return n;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }

        String text() {
            return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    private static String stuff(String... chunks) throws IOException {
        DotStuffer stuffer = new DotStuffer();
        Collector out = new Collector();
        for (int i = 0; i < chunks.length; i++) {
            ByteBuffer in = ByteBuffer.wrap(chunks[i].getBytes(StandardCharsets.ISO_8859_1));
            stuffer.processChunk(in, out);
        }
        stuffer.endMessage(out);
        return out.text();
    }

    @Test
    public void testDotAfterCrlfIsStuffed() throws IOException {
        assertEquals("a\r\n..b\r\n.\r\n", stuff("a\r\n.b"));
    }

    @Test
    public void testSingleLineGetsTerminator() throws IOException {
        assertEquals("hello\r\n.\r\n", stuff("hello"));
    }

    @Test
    public void testFlushWritesPendingBytes() throws IOException {
        DotStuffer stuffer = new DotStuffer();
        Collector out = new Collector();
        stuffer.flush(out);
        assertEquals("", out.text());
        stuffer.reset();
    }

    @Test
    public void testDotNotAtLineStartIsUntouched() throws IOException {
        assertEquals("a.b\r\n.\r\n", stuff("a.b"));
    }

    @Test
    public void testSplitAcrossChunks() throws IOException {
        assertEquals("a\r\n..b\r\n.\r\n", stuff("a\r", "\n", ".b"));
        assertEquals("a\r\n..b\r\n.\r\n", stuff("a\r\n", ".", "b"));
    }

    @Test(timeout = 10000)
    public void testNoByteDuplicatedAfterCrlf() throws IOException {
        assertEquals("hello\r\nworld\r\n.\r\n", stuff("hello\r\nworld"));
    }

    @Test(timeout = 10000)
    public void testNoByteDuplicatedAfterBareCr() throws IOException {
        assertEquals("a\rb\r\n.\r\n", stuff("a\rb"));
        assertEquals("a\r\rb\r\n.\r\n", stuff("a\r\rb"));
    }

    @Test(timeout = 10000)
    public void testTrailingCrlfNotDoubled() throws IOException {
        assertEquals("hello\r\n.\r\n", stuff("hello\r\n"));
    }

    @Test(timeout = 10000)
    public void testTrailingCrThenTerminator() throws IOException {
        assertEquals("hello\r\r\n.\r\n", stuff("hello\r"));
    }

    @Test(timeout = 10000)
    public void testCrlfThenBareCrTerminates() throws IOException {
        assertEquals("a\r\n\rb\r\n.\r\n", stuff("a\r\n\rb"));
    }

    @Test(timeout = 10000)
    public void testLeadingDotIsStuffed() throws IOException {
        assertEquals("..a\r\n.\r\n", stuff(".a"));
    }

    @Test(timeout = 10000)
    public void testChunkingDoesNotChangeOutput() throws IOException {
        String[] messages = {
            "hello\r\nworld", "hello\r\n", "a\r\n\rb", "a\rb\r\n.x\r\n..y\r\n",
            ".a\r\n.\r\n\r\n.", "\r\r\n\n.\r", "", "x"
        };
        for (int m = 0; m < messages.length; m++) {
            String whole = stuff(messages[m]);
            for (int size = 1; size <= 4; size++) {
                String[] chunks = new String[(messages[m].length() + size - 1) / size];
                for (int i = 0; i < chunks.length; i++) {
                    int end = Math.min(messages[m].length(), (i + 1) * size);
                    chunks[i] = messages[m].substring(i * size, end);
                }
                assertEquals("message " + m + " chunk size " + size, whole, stuff(chunks));
            }
        }
    }

    @Test(timeout = 10000)
    public void testUnstuffedRoundTrip() throws IOException {
        String message = "l1\r\n.l2\r\n..l3\r\nl4\rl5\r\n";
        String wire = stuff(message);
        assertTrue(wire.endsWith("\r\n.\r\n"));
        String body = wire.substring(0, wire.length() - 3);
        StringBuilder out = new StringBuilder();
        boolean lineStart = true;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (lineStart && c == '.') {
                lineStart = false;
                continue;
            }
            out.append(c);
            lineStart = (c == '\n' && i > 0 && body.charAt(i - 1) == '\r');
        }
        assertEquals(message, out.toString());
    }

    @Test
    public void testResponseSingleLine() {
        SmtpResponse r = new SmtpResponse(250, "OK");
        assertEquals(250, r.getCode());
        assertEquals("OK", r.getMessage());
        assertFalse(r.isMultiLine());
        assertEquals(1, r.getLines().size());
        assertTrue(r.isSuccess());
        assertFalse(r.isIntermediate());
        assertFalse(r.isTemporaryFailure());
        assertFalse(r.isPermanentFailure());
        assertEquals("250 OK", r.toString());
    }

    @Test
    public void testResponseMultiLine() {
        List<String> lines = new ArrayList<String>();
        lines.add("mx.example.com");
        lines.add("PIPELINING");
        lines.add("HELP");
        SmtpResponse r = new SmtpResponse(250, lines);
        assertTrue(r.isMultiLine());
        assertEquals("HELP", r.getMessage());
        assertEquals(3, r.getLines().size());
        assertEquals("250-mx.example.com\n250-PIPELINING\n250 HELP", r.toString());
        try {
            r.getLines().add("x");
            fail("lines must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected);
        }
    }

    @Test
    public void testResponseEmptyLines() {
        SmtpResponse r = new SmtpResponse(220, new ArrayList<String>());
        assertEquals("", r.getMessage());
        assertFalse(r.isMultiLine());
    }

    @Test
    public void testResponseClasses() {
        assertTrue(new SmtpResponse(354, "go").isIntermediate());
        assertTrue(new SmtpResponse(451, "later").isTemporaryFailure());
        assertTrue(new SmtpResponse(550, "no").isPermanentFailure());
        assertFalse(new SmtpResponse(550, "no").isSuccess());
    }
}
