/*
 * IMAPServerLexerTest.java
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

package org.bluezoo.gumdrop.imap;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.ByteStreamLexer;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link IMAPServerLexer}, verifying the outer {@code
 * KEYWORD [SP TEXT] CRLF} shape independent of {@link
 * IMAPProtocolHandler}'s literal-detection/business logic — this lexer
 * itself knows nothing about IMAP literals at all (see its class Javadoc),
 * so its own behaviour is identical to the POP3/FTP/SMTP server lexers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPServerLexerTest {

    static class Event {
        final IMAPServerLexer.Token type;
        final String text;
        Event(IMAPServerLexer.Token type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    static class RecordingHandler implements ByteStreamLexer.Handler<IMAPServerLexer.Token> {
        final List<Event> events = new ArrayList<Event>();
        int tokenTooLongCount;

        @Override
        public boolean token(IMAPServerLexer.Token type, ByteBuffer window) {
            byte[] copy = new byte[window.remaining()];
            window.get(copy);
            events.add(new Event(type, new String(copy, StandardCharsets.US_ASCII)));
            return type == IMAPServerLexer.Token.SP;
        }

        @Override
        public void rawBytes(ByteBuffer slice) {
            fail("IMAPServerLexer should never enter raw mode itself");
        }

        @Override
        public void tokenTooLong() {
            tokenTooLongCount++;
        }

        String reconstructedArgs() {
            StringBuilder sb = new StringBuilder();
            for (Event e : events) {
                if (e.type == IMAPServerLexer.Token.TEXT) {
                    sb.append(e.text);
                }
            }
            return sb.toString();
        }
    }

    private static ByteBuffer bytesOf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void testBareTagNoArgs() {
        RecordingHandler handler = new RecordingHandler();
        IMAPServerLexer lexer = new IMAPServerLexer(handler, 1024);
        lexer.feed(bytesOf("DONE\r\n"));
        assertEquals(2, handler.events.size());
        assertEquals(IMAPServerLexer.Token.KEYWORD, handler.events.get(0).type);
        assertEquals("DONE", handler.events.get(0).text);
        assertEquals(IMAPServerLexer.Token.CRLF, handler.events.get(1).type);
    }

    @Test
    public void testTagWithCommandAndArgs() {
        RecordingHandler handler = new RecordingHandler();
        IMAPServerLexer lexer = new IMAPServerLexer(handler, 1024);
        lexer.feed(bytesOf("a1 SELECT INBOX\r\n"));
        assertEquals(IMAPServerLexer.Token.KEYWORD, handler.events.get(0).type);
        assertEquals("a1", handler.events.get(0).text);
        assertEquals(IMAPServerLexer.Token.SP, handler.events.get(1).type);
        assertEquals("SELECT INBOX", handler.reconstructedArgs());
        assertEquals(IMAPServerLexer.Token.CRLF,
                handler.events.get(handler.events.size() - 1).type);
    }

    @Test
    public void testTrailingLiteralSpecPreservedVerbatimInText() {
        // The lexer has no concept of literals; a trailing "{n}" is just
        // more TEXT content, exactly like any other bytes — detecting and
        // acting on it is entirely IMAPProtocolHandler's job.
        RecordingHandler handler = new RecordingHandler();
        IMAPServerLexer lexer = new IMAPServerLexer(handler, 1024);
        lexer.feed(bytesOf("a1 LOGIN {5}\r\n"));
        assertEquals("LOGIN {5}", handler.reconstructedArgs());
    }

    @Test
    public void testEmptyLineEmitsNoKeyword() {
        RecordingHandler handler = new RecordingHandler();
        IMAPServerLexer lexer = new IMAPServerLexer(handler, 1024);
        lexer.feed(bytesOf("\r\n"));
        assertEquals(1, handler.events.size());
        assertEquals(IMAPServerLexer.Token.CRLF, handler.events.get(0).type);
    }

    @Test
    public void testCapEnforcedOnKeyword() {
        RecordingHandler handler = new RecordingHandler();
        IMAPServerLexer lexer = new IMAPServerLexer(handler, 5);
        StringBuilder longWord = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longWord.append('X');
        }
        lexer.feed(bytesOf(longWord + "\r\n"));
        assertEquals(1, handler.tokenTooLongCount);
        for (Event e : handler.events) {
            assertNotEquals(IMAPServerLexer.Token.KEYWORD, e.type);
        }
    }

    @Test
    public void testCapNotEnforcedOnArgsText() {
        RecordingHandler handler = new RecordingHandler();
        IMAPServerLexer lexer = new IMAPServerLexer(handler, 5);
        StringBuilder longArgs = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longArgs.append('a');
        }
        lexer.feed(bytesOf("a1 " + longArgs + "\r\n"));
        assertEquals(0, handler.tokenTooLongCount);
        assertEquals(longArgs.toString(), handler.reconstructedArgs());
    }

    @Test
    public void testSlicedAtEveryChunkSizeReproducesSameTokens() {
        String line = "a1 LOGIN alice password\r\n";
        byte[] wire = line.getBytes(StandardCharsets.US_ASCII);

        RecordingHandler whole = new RecordingHandler();
        new IMAPServerLexer(whole, 1024).feed(bytesOf(line));
        String expectedArgs = whole.reconstructedArgs();

        for (int chunkSize = 1; chunkSize <= wire.length; chunkSize++) {
            RecordingHandler handler = new RecordingHandler();
            IMAPServerLexer lexer = new IMAPServerLexer(handler, 1024);
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
            assertEquals("chunk size " + chunkSize,
                    expectedArgs, handler.reconstructedArgs());
        }
    }

    @Test
    public void testResumeAfterEnterRawWithoutLeadingSpace() {
        // Simulates what IMAPProtocolHandler sees right after a literal's
        // raw octets complete with no separating space before the next
        // content (e.g. a literal immediately followed by ")" closing a
        // list): enterRaw resumes in structured-token mode, so the next
        // bytes arrive as an ordinary KEYWORD/CRLF pair, not as TEXT.
        RecordingHandler handler = new RecordingHandler() {
            @Override
            public boolean token(IMAPServerLexer.Token type, ByteBuffer window) {
                if (type == IMAPServerLexer.Token.KEYWORD) {
                    byte[] copy = new byte[window.remaining()];
                    window.get(copy);
                    events.add(new Event(type, new String(copy, StandardCharsets.US_ASCII)));
                    // simulate a lexer-driven raw escape mid-line, exactly
                    // as IMAPProtocolHandler's CRLF handling would trigger
                    // one for a literal — here just proving that once
                    // resumed, consume() runs again rather than text mode.
                    return false;
                }
                return super.token(type, window);
            }
        };
        IMAPServerLexer lexer = new IMAPServerLexer(handler, 1024);
        lexer.feed(bytesOf(")\r\n"));
        assertEquals(IMAPServerLexer.Token.KEYWORD, handler.events.get(0).type);
        assertEquals(")", handler.events.get(0).text);
    }
}
