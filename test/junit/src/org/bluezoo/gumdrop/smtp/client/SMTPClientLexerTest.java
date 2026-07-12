/*
 * SMTPClientLexerTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.ByteStreamLexer;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SMTPClientLexer}, verifying exact token content
 * for the {@code CODE [SEP TEXT] CRLF} reply grammar, independent of
 * {@link SMTPClientProtocolHandler}'s business logic — in particular the
 * per-line {@code sawCode} tracking reset (see
 * {@link SMTPClientLexer#resetForNextLine()}), which a fixed-width-prefix
 * grammar needs but the command lexers' scan-for-space grammars do not.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPClientLexerTest {

    static class Event {
        final SMTPClientLexer.Token type;
        final String text;
        Event(SMTPClientLexer.Token type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    static class RecordingHandler implements ByteStreamLexer.Handler<SMTPClientLexer.Token> {
        final List<Event> events = new ArrayList<Event>();
        int tokenTooLongCount;

        @Override
        public boolean token(SMTPClientLexer.Token type, ByteBuffer window) {
            byte[] copy = new byte[window.remaining()];
            window.get(copy);
            events.add(new Event(type, new String(copy, StandardCharsets.US_ASCII)));
            if (type == SMTPClientLexer.Token.CRLF) {
                ((SMTPClientLexer) lexerRef).resetForNextLine();
            }
            return type == SMTPClientLexer.Token.DASH || type == SMTPClientLexer.Token.SP;
        }

        @Override
        public void rawBytes(ByteBuffer slice) {
            fail("SMTP client lexer should never enter raw mode");
        }

        @Override
        public void tokenTooLong() {
            tokenTooLongCount++;
        }

        SMTPClientLexer lexerRef;

        String reconstructedText() {
            StringBuilder sb = new StringBuilder();
            for (Event e : events) {
                if (e.type == SMTPClientLexer.Token.TEXT) {
                    sb.append(e.text);
                }
            }
            return sb.toString();
        }

        List<String> codes() {
            List<String> result = new ArrayList<String>();
            for (Event e : events) {
                if (e.type == SMTPClientLexer.Token.CODE) {
                    result.add(e.text);
                }
            }
            return result;
        }
    }

    private static SMTPClientLexer newLexer(RecordingHandler handler) {
        SMTPClientLexer lexer = new SMTPClientLexer(handler, Integer.MAX_VALUE);
        handler.lexerRef = lexer;
        return lexer;
    }

    private static ByteBuffer bytesOf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void testSingleLineReplyWithText() {
        RecordingHandler handler = new RecordingHandler();
        SMTPClientLexer lexer = newLexer(handler);
        lexer.feed(bytesOf("250 OK\r\n"));
        assertEquals(SMTPClientLexer.Token.CODE, handler.events.get(0).type);
        assertEquals("250", handler.events.get(0).text);
        assertEquals(SMTPClientLexer.Token.SP, handler.events.get(1).type);
        assertEquals("OK", handler.reconstructedText());
        assertEquals(SMTPClientLexer.Token.CRLF,
                handler.events.get(handler.events.size() - 1).type);
    }

    @Test
    public void testBareCodeNoSeparatorNoText() {
        RecordingHandler handler = new RecordingHandler();
        SMTPClientLexer lexer = newLexer(handler);
        lexer.feed(bytesOf("250\r\n"));
        assertEquals(2, handler.events.size());
        assertEquals("250", handler.events.get(0).text);
        assertEquals(SMTPClientLexer.Token.CRLF, handler.events.get(1).type);
    }

    @Test
    public void testContinuationDash() {
        RecordingHandler handler = new RecordingHandler();
        SMTPClientLexer lexer = newLexer(handler);
        lexer.feed(bytesOf("250-mail.example.com\r\n"));
        assertEquals(SMTPClientLexer.Token.DASH, handler.events.get(1).type);
        assertEquals("mail.example.com", handler.reconstructedText());
    }

    @Test
    public void testMultilineEhloReplySequence() {
        RecordingHandler handler = new RecordingHandler();
        SMTPClientLexer lexer = newLexer(handler);
        String wire = "250-mail.example.com\r\n"
                + "250-PIPELINING\r\n"
                + "250-SIZE 52428800\r\n"
                + "250 CHUNKING\r\n";
        lexer.feed(bytesOf(wire));
        // The regression this test guards: without resetForNextLine(),
        // sawCode leaks across lines once text mode has latched (which it
        // does for essentially every real SMTP reply), and every line
        // after the first is misparsed.
        assertEquals(java.util.Arrays.asList("250", "250", "250", "250"), handler.codes());
        assertEquals("mail.example.comPIPELININGSIZE 52428800CHUNKING",
                handler.reconstructedText());
    }

    @Test
    public void testBlankLineEmitsNoCodeToken() {
        RecordingHandler handler = new RecordingHandler();
        SMTPClientLexer lexer = newLexer(handler);
        lexer.feed(bytesOf("\r\n"));
        assertEquals(1, handler.events.size());
        assertEquals(SMTPClientLexer.Token.CRLF, handler.events.get(0).type);
    }

    @Test
    public void testShortLineEmitsPartialCode() {
        RecordingHandler handler = new RecordingHandler();
        SMTPClientLexer lexer = newLexer(handler);
        lexer.feed(bytesOf("25\r\n"));
        assertEquals(SMTPClientLexer.Token.CODE, handler.events.get(0).type);
        assertEquals("25", handler.events.get(0).text);
    }

    @Test
    public void testSlicedAtEveryChunkSizeReproducesSameTokens() {
        String wire = "250-mail.example.com\r\n250-PIPELINING\r\n250 CHUNKING\r\n";
        byte[] bytes = wire.getBytes(StandardCharsets.US_ASCII);

        RecordingHandler whole = new RecordingHandler();
        newLexer(whole).feed(bytesOf(wire));
        String expectedText = whole.reconstructedText();
        List<String> expectedCodes = whole.codes();

        for (int chunkSize = 1; chunkSize <= bytes.length; chunkSize++) {
            RecordingHandler handler = new RecordingHandler();
            SMTPClientLexer lexer = newLexer(handler);
            ByteBuffer netIn = ByteBuffer.allocate(256);
            int offset = 0;
            while (offset < bytes.length) {
                int len = Math.min(chunkSize, bytes.length - offset);
                netIn.put(bytes, offset, len);
                offset += len;
                netIn.flip();
                lexer.feed(netIn);
                netIn.compact();
            }
            assertEquals("chunk size " + chunkSize, expectedText, handler.reconstructedText());
            assertEquals("chunk size " + chunkSize, expectedCodes, handler.codes());
        }
    }
}
