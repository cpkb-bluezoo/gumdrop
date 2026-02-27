/*
 * DotUnstufferTest.java
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

package org.bluezoo.gumdrop.pop3.client;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DotUnstuffer}.
 */
public class DotUnstufferTest {

    private ByteArrayOutputStream collected;
    private boolean completed;
    private DotUnstuffer unstuffer;

    @Before
    public void setUp() {
        collected = new ByteArrayOutputStream();
        completed = false;
        unstuffer = new DotUnstuffer(new DotUnstuffer.Callback() {
            @Override
            public void content(ByteBuffer data) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                collected.write(bytes, 0, bytes.length);
            }

            @Override
            public void complete() {
                completed = true;
            }
        });
    }

    private ByteBuffer wrap(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    private String collectedString() {
        return collected.toString(StandardCharsets.US_ASCII);
    }

    // ── Simple termination ──

    @Test
    public void testEmptyMessageTermination() {
        boolean more = unstuffer.process(wrap(".\r\n"));
        assertFalse(more);
        assertTrue(completed);
        assertEquals("", collectedString());
    }

    @Test
    public void testSingleLineMessage() {
        unstuffer.process(wrap("Hello world\r\n"));
        assertFalse(completed);
        boolean more = unstuffer.process(wrap(".\r\n"));
        assertFalse(more);
        assertTrue(completed);
        assertEquals("Hello world\r\n", collectedString());
    }

    @Test
    public void testMultiLineMessage() {
        unstuffer.process(wrap("Line 1\r\n"));
        unstuffer.process(wrap("Line 2\r\n"));
        unstuffer.process(wrap("Line 3\r\n"));
        unstuffer.process(wrap(".\r\n"));
        assertTrue(completed);
        assertEquals("Line 1\r\nLine 2\r\nLine 3\r\n",
                collectedString());
    }

    // ── Dot-unstuffing ──

    @Test
    public void testDotUnstuffing() {
        unstuffer.process(wrap("..This line starts with a dot\r\n"));
        unstuffer.process(wrap(".\r\n"));
        assertTrue(completed);
        assertEquals(".This line starts with a dot\r\n",
                collectedString());
    }

    @Test
    public void testMultipleDotUnstuffing() {
        unstuffer.process(wrap("Normal line\r\n"));
        unstuffer.process(wrap("..Dotted line\r\n"));
        unstuffer.process(wrap("Another normal\r\n"));
        unstuffer.process(wrap("..Another dotted\r\n"));
        unstuffer.process(wrap(".\r\n"));
        assertTrue(completed);
        assertEquals(
                "Normal line\r\n"
                + ".Dotted line\r\n"
                + "Another normal\r\n"
                + ".Another dotted\r\n",
                collectedString());
    }

    @Test
    public void testDoubleDotOnly() {
        unstuffer.process(wrap("..\r\n"));
        unstuffer.process(wrap(".\r\n"));
        assertTrue(completed);
        assertEquals(".\r\n", collectedString());
    }

    // ── All data in single chunk ──

    @Test
    public void testAllInOneChunk() {
        boolean more = unstuffer.process(
                wrap("Subject: Test\r\n\r\nBody\r\n.\r\n"));
        assertFalse(more);
        assertTrue(completed);
        assertEquals("Subject: Test\r\n\r\nBody\r\n",
                collectedString());
    }

    @Test
    public void testAllInOneChunkWithDotStuffing() {
        boolean more = unstuffer.process(
                wrap("..Dot line\r\nNormal\r\n.\r\n"));
        assertFalse(more);
        assertTrue(completed);
        assertEquals(".Dot line\r\nNormal\r\n",
                collectedString());
    }

    // ── Chunk boundary splitting ──

    @Test
    public void testTerminatorSplitAcrossChunks() {
        unstuffer.process(wrap("data\r\n"));
        unstuffer.process(wrap("."));
        assertFalse(completed);
        unstuffer.process(wrap("\r\n"));
        assertTrue(completed);
        assertEquals("data\r\n", collectedString());
    }

    @Test
    public void testCrlfSplitAcrossChunks() {
        unstuffer.process(wrap("data\r"));
        assertFalse(completed);
        unstuffer.process(wrap("\n.\r\n"));
        assertTrue(completed);
        assertEquals("data\r\n", collectedString());
    }

    @Test
    public void testDotStuffingSplitAcrossChunks() {
        unstuffer.process(wrap("line1\r\n."));
        assertFalse(completed);
        unstuffer.process(wrap(".rest of line\r\n.\r\n"));
        assertTrue(completed);
        assertEquals("line1\r\n.rest of line\r\n",
                collectedString());
    }

    @Test
    public void testTerminatorDotCrSplitFromLf() {
        unstuffer.process(wrap("data\r\n.\r"));
        assertFalse(completed);
        unstuffer.process(wrap("\n"));
        assertTrue(completed);
        assertEquals("data\r\n", collectedString());
    }

    // ── Byte-at-a-time feeding ──

    @Test
    public void testByteAtATime() {
        String input = "Hello\r\n..World\r\n.\r\n";
        for (int i = 0; i < input.length(); i++) {
            unstuffer.process(wrap(input.substring(i, i + 1)));
        }
        assertTrue(completed);
        assertEquals("Hello\r\n.World\r\n", collectedString());
    }

    // ── Reset ──

    @Test
    public void testResetAndReuse() {
        unstuffer.process(wrap("first\r\n.\r\n"));
        assertTrue(completed);
        assertEquals("first\r\n", collectedString());

        collected.reset();
        completed = false;
        unstuffer.reset();

        unstuffer.process(wrap("second\r\n.\r\n"));
        assertTrue(completed);
        assertEquals("second\r\n", collectedString());
    }

    // ── Data after terminator ──

    @Test
    public void testReturnsFalseOnTermination() {
        boolean more = unstuffer.process(wrap("data\r\n.\r\n"));
        assertFalse(more);
        assertTrue(completed);
    }

    @Test
    public void testReturnsTrueWhenMoreExpected() {
        boolean more = unstuffer.process(wrap("data\r\n"));
        assertTrue(more);
        assertFalse(completed);
    }

    // ── Real-world message content ──

    @Test
    public void testRealisticEmailMessage() {
        unstuffer.process(wrap(
                "From: alice@example.com\r\n"
                + "To: bob@example.com\r\n"
                + "Subject: Test\r\n"
                + "\r\n"
                + "This is the body.\r\n"
                + "..This line was dot-stuffed.\r\n"
                + "End of message.\r\n"));
        unstuffer.process(wrap(".\r\n"));
        assertTrue(completed);

        String expected =
                "From: alice@example.com\r\n"
                + "To: bob@example.com\r\n"
                + "Subject: Test\r\n"
                + "\r\n"
                + "This is the body.\r\n"
                + ".This line was dot-stuffed.\r\n"
                + "End of message.\r\n";
        assertEquals(expected, collectedString());
    }
}
