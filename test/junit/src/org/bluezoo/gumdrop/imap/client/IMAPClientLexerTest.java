/*
 * IMAPClientLexerTest.java
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

package org.bluezoo.gumdrop.imap.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.ByteStreamLexer;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link IMAPClientLexer}, verifying the outer {@code
 * KEYWORD [SP TEXT] CRLF} shape independent of {@link
 * IMAPClientProtocolHandler}'s response dispatch — this lexer knows
 * nothing about IMAP literals at all (see its class Javadoc).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPClientLexerTest {

    static class Event {
        final IMAPClientLexer.Token type;
        final String text;
        Event(IMAPClientLexer.Token type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    static class RecordingHandler implements ByteStreamLexer.Handler<IMAPClientLexer.Token> {
        final List<Event> events = new ArrayList<Event>();
        final StringBuilder reconstructed = new StringBuilder();

        @Override
        public boolean token(IMAPClientLexer.Token type, ByteBuffer window) {
            byte[] copy = new byte[window.remaining()];
            window.get(copy);
            String text = new String(copy, StandardCharsets.US_ASCII);
            events.add(new Event(type, text));
            if (type == IMAPClientLexer.Token.KEYWORD || type == IMAPClientLexer.Token.TEXT) {
                reconstructed.append(text);
            } else if (type == IMAPClientLexer.Token.SP) {
                reconstructed.append(' ');
            }
            return type == IMAPClientLexer.Token.SP;
        }

        @Override
        public void rawBytes(ByteBuffer slice) {
            fail("IMAPClientLexer should never enter raw mode itself");
        }

        @Override
        public void tokenTooLong() {
            fail("IMAPClientLexer has no cap");
        }
    }

    private static ByteBuffer bytesOf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void testTaggedOkReply() {
        RecordingHandler handler = new RecordingHandler();
        IMAPClientLexer lexer = new IMAPClientLexer(handler, Integer.MAX_VALUE);
        lexer.feed(bytesOf("a1 OK LOGIN completed\r\n"));
        assertEquals("a1 OK LOGIN completed", handler.reconstructed.toString());
        assertEquals(IMAPClientLexer.Token.CRLF,
                handler.events.get(handler.events.size() - 1).type);
    }

    @Test
    public void testUntaggedReply() {
        RecordingHandler handler = new RecordingHandler();
        IMAPClientLexer lexer = new IMAPClientLexer(handler, Integer.MAX_VALUE);
        lexer.feed(bytesOf("* 10 EXISTS\r\n"));
        assertEquals("* 10 EXISTS", handler.reconstructed.toString());
    }

    @Test
    public void testContinuationReply() {
        RecordingHandler handler = new RecordingHandler();
        IMAPClientLexer lexer = new IMAPClientLexer(handler, Integer.MAX_VALUE);
        lexer.feed(bytesOf("+ idling\r\n"));
        assertEquals("+ idling", handler.reconstructed.toString());
    }

    @Test
    public void testBareWordNoArgs() {
        RecordingHandler handler = new RecordingHandler();
        IMAPClientLexer lexer = new IMAPClientLexer(handler, Integer.MAX_VALUE);
        lexer.feed(bytesOf(")\r\n"));
        assertEquals(2, handler.events.size());
        assertEquals(IMAPClientLexer.Token.KEYWORD, handler.events.get(0).type);
        assertEquals(")", handler.events.get(0).text);
    }

    @Test
    public void testTrailingLiteralSpecPreservedVerbatim() {
        // The lexer has no concept of literals; a trailing "{n}" is just
        // more content — IMAPClientProtocolHandler detects and acts on it
        // after the line is fully dispatched.
        RecordingHandler handler = new RecordingHandler();
        IMAPClientLexer lexer = new IMAPClientLexer(handler, Integer.MAX_VALUE);
        lexer.feed(bytesOf("* 1 FETCH (BODY[TEXT] {11}\r\n"));
        assertEquals("* 1 FETCH (BODY[TEXT] {11}", handler.reconstructed.toString());
    }

    @Test
    public void testEmptyLineEmitsNoKeyword() {
        RecordingHandler handler = new RecordingHandler();
        IMAPClientLexer lexer = new IMAPClientLexer(handler, Integer.MAX_VALUE);
        lexer.feed(bytesOf("\r\n"));
        assertEquals(1, handler.events.size());
        assertEquals(IMAPClientLexer.Token.CRLF, handler.events.get(0).type);
    }

    @Test
    public void testSlicedAtEveryChunkSizeReproducesSameLine() {
        String line = "* 1 FETCH (FLAGS (\\Seen) UID 42)\r\n";
        byte[] wire = line.getBytes(StandardCharsets.US_ASCII);

        RecordingHandler whole = new RecordingHandler();
        new IMAPClientLexer(whole, Integer.MAX_VALUE).feed(bytesOf(line));
        String expected = whole.reconstructed.toString();

        for (int chunkSize = 1; chunkSize <= wire.length; chunkSize++) {
            RecordingHandler handler = new RecordingHandler();
            IMAPClientLexer lexer = new IMAPClientLexer(handler, Integer.MAX_VALUE);
            ByteBuffer netIn = ByteBuffer.allocate(256);
            int offset = 0;
            while (offset < wire.length) {
                int len = Math.min(chunkSize, wire.length - offset);
                netIn.put(wire, offset, len);
                offset += len;
                netIn.flip();
                lexer.feed(netIn);
                netIn.compact();
            }
            assertEquals("chunk size " + chunkSize, expected, handler.reconstructed.toString());
        }
    }
}
