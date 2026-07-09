/*
 * SMTPServerLexerTest.java
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

package org.bluezoo.gumdrop.smtp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.ByteStreamLexer;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SMTPServerLexer}, verifying exact token content
 * independent of {@link SMTPProtocolHandler}'s business logic.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPServerLexerTest {

    static class Event {
        final SMTPServerLexer.Token type;
        final String text;
        Event(SMTPServerLexer.Token type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    static class RecordingHandler implements ByteStreamLexer.Handler<SMTPServerLexer.Token> {
        final List<Event> events = new ArrayList<Event>();
        int tokenTooLongCount;

        @Override
        public boolean token(SMTPServerLexer.Token type, ByteBuffer window) {
            byte[] copy = new byte[window.remaining()];
            window.get(copy);
            events.add(new Event(type, new String(copy, StandardCharsets.US_ASCII)));
            return type == SMTPServerLexer.Token.SP;
        }

        @Override
        public void rawBytes(ByteBuffer slice) {
            fail("SMTP server lexer should never enter raw mode");
        }

        @Override
        public void tokenTooLong() {
            tokenTooLongCount++;
        }

        String reconstructedArgs() {
            StringBuilder sb = new StringBuilder();
            for (Event e : events) {
                if (e.type == SMTPServerLexer.Token.TEXT) {
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
    public void testBareCommandNoArgs() {
        RecordingHandler handler = new RecordingHandler();
        SMTPServerLexer lexer = new SMTPServerLexer(handler, 1024);
        lexer.feed(bytesOf("NOOP\r\n"));
        assertEquals(2, handler.events.size());
        assertEquals(SMTPServerLexer.Token.KEYWORD, handler.events.get(0).type);
        assertEquals("NOOP", handler.events.get(0).text);
        assertEquals(SMTPServerLexer.Token.CRLF, handler.events.get(1).type);
    }

    @Test
    public void testCommandWithArgs() {
        RecordingHandler handler = new RecordingHandler();
        SMTPServerLexer lexer = new SMTPServerLexer(handler, 1024);
        lexer.feed(bytesOf("MAIL FROM:<alice@example.com>\r\n"));
        assertEquals(SMTPServerLexer.Token.KEYWORD, handler.events.get(0).type);
        assertEquals("MAIL", handler.events.get(0).text);
        assertEquals(SMTPServerLexer.Token.SP, handler.events.get(1).type);
        assertEquals("FROM:<alice@example.com>", handler.reconstructedArgs());
        assertEquals(SMTPServerLexer.Token.CRLF,
                handler.events.get(handler.events.size() - 1).type);
    }

    @Test
    public void testEmptyLineEmitsNoKeyword() {
        RecordingHandler handler = new RecordingHandler();
        SMTPServerLexer lexer = new SMTPServerLexer(handler, 1024);
        lexer.feed(bytesOf("\r\n"));
        assertEquals(1, handler.events.size());
        assertEquals(SMTPServerLexer.Token.CRLF, handler.events.get(0).type);
    }

    @Test
    public void testCapEnforcedOnKeyword() {
        RecordingHandler handler = new RecordingHandler();
        SMTPServerLexer lexer = new SMTPServerLexer(handler, 5);
        StringBuilder longWord = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longWord.append('X');
        }
        lexer.feed(bytesOf(longWord + "\r\n"));
        assertEquals(1, handler.tokenTooLongCount);
        for (Event e : handler.events) {
            assertNotEquals(SMTPServerLexer.Token.KEYWORD, e.type);
        }
    }

    @Test
    public void testCapNotEnforcedOnArgsText() {
        RecordingHandler handler = new RecordingHandler();
        SMTPServerLexer lexer = new SMTPServerLexer(handler, 5);
        StringBuilder longArgs = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longArgs.append('a');
        }
        lexer.feed(bytesOf("MAIL " + longArgs + "\r\n"));
        assertEquals(0, handler.tokenTooLongCount);
        assertEquals(longArgs.toString(), handler.reconstructedArgs());
    }

    @Test
    public void testSlicedAtEveryChunkSizeReproducesSameTokens() {
        String line = "RCPT TO:<bob@example.com> NOTIFY=SUCCESS,FAILURE\r\n";
        byte[] wire = line.getBytes(StandardCharsets.US_ASCII);

        RecordingHandler whole = new RecordingHandler();
        new SMTPServerLexer(whole, 1024).feed(bytesOf(line));
        String expectedArgs = whole.reconstructedArgs();

        for (int chunkSize = 1; chunkSize <= wire.length; chunkSize++) {
            RecordingHandler handler = new RecordingHandler();
            SMTPServerLexer lexer = new SMTPServerLexer(handler, 1024);
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
}
