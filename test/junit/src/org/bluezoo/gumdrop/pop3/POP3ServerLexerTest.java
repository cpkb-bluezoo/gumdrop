/*
 * POP3ServerLexerTest.java
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

package org.bluezoo.gumdrop.pop3;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.ByteStreamLexer;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link POP3ServerLexer}, verifying exact token content
 * (including the free-form TEXT chunking properties relied on by {@link
 * POP3ProtocolHandler} to reconstruct args and SASL continuation lines
 * with embedded whitespace preserved verbatim) independent of the full
 * protocol handler's business logic.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3ServerLexerTest {

    static class Event {
        final POP3ServerLexer.Token type;
        final String text;
        Event(POP3ServerLexer.Token type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    static class RecordingHandler implements ByteStreamLexer.Handler<POP3ServerLexer.Token> {
        final List<Event> events = new ArrayList<Event>();
        int tokenTooLongCount;
        boolean latchTextAfterSp = true;

        @Override
        public boolean token(POP3ServerLexer.Token type, ByteBuffer window) {
            byte[] copy = new byte[window.remaining()];
            window.get(copy);
            events.add(new Event(type, new String(copy, StandardCharsets.US_ASCII)));
            return type == POP3ServerLexer.Token.SP && latchTextAfterSp;
        }

        @Override
        public void rawBytes(ByteBuffer slice) {
            fail("POP3 server lexer should never enter raw mode");
        }

        @Override
        public void tokenTooLong() {
            tokenTooLongCount++;
        }

        String reconstructedArgs() {
            StringBuilder sb = new StringBuilder();
            for (Event e : events) {
                if (e.type == POP3ServerLexer.Token.TEXT) {
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
        POP3ServerLexer lexer = new POP3ServerLexer(handler, 512);
        lexer.feed(bytesOf("NOOP\r\n"));
        assertEquals(2, handler.events.size());
        assertEquals(POP3ServerLexer.Token.KEYWORD, handler.events.get(0).type);
        assertEquals("NOOP", handler.events.get(0).text);
        assertEquals(POP3ServerLexer.Token.CRLF, handler.events.get(1).type);
    }

    @Test
    public void testCommandWithArgs() {
        RecordingHandler handler = new RecordingHandler();
        POP3ServerLexer lexer = new POP3ServerLexer(handler, 512);
        lexer.feed(bytesOf("USER alice\r\n"));
        assertEquals("KEYWORD", handler.events.get(0).type.toString());
        assertEquals("USER", handler.events.get(0).text);
        assertEquals(POP3ServerLexer.Token.SP, handler.events.get(1).type);
        assertEquals("alice", handler.reconstructedArgs());
        assertEquals(POP3ServerLexer.Token.CRLF,
                handler.events.get(handler.events.size() - 1).type);
    }

    @Test
    public void testArgsWithEmbeddedDoubleSpacePreservedVerbatim() {
        RecordingHandler handler = new RecordingHandler();
        POP3ServerLexer lexer = new POP3ServerLexer(handler, 512);
        lexer.feed(bytesOf("APOP someuser  extraspace\r\n"));
        // Only the FIRST space is the KEYWORD/args separator (consumed as
        // the SP token); every subsequent byte, including the second
        // space, is part of the TEXT-mode args verbatim.
        assertEquals("someuser  extraspace", handler.reconstructedArgs());
    }

    @Test
    public void testEmptyLineEmitsNoKeyword() {
        RecordingHandler handler = new RecordingHandler();
        POP3ServerLexer lexer = new POP3ServerLexer(handler, 512);
        lexer.feed(bytesOf("\r\n"));
        assertEquals(1, handler.events.size());
        assertEquals(POP3ServerLexer.Token.CRLF, handler.events.get(0).type);
    }

    @Test
    public void testSaslContinuationSingleTokenNoSpace() {
        RecordingHandler handler = new RecordingHandler();
        POP3ServerLexer lexer = new POP3ServerLexer(handler, 512);
        // A base64 SASL response contains no spaces, so it lexes as a
        // single KEYWORD token spanning the whole line — the parser
        // (POP3ProtocolHandler), tracking its own authState, treats this
        // as the raw continuation data rather than a command verb.
        lexer.feed(bytesOf("QUJDRA==\r\n"));
        assertEquals(2, handler.events.size());
        assertEquals(POP3ServerLexer.Token.KEYWORD, handler.events.get(0).type);
        assertEquals("QUJDRA==", handler.events.get(0).text);
    }

    @Test
    public void testCapEnforcedOnKeyword() {
        RecordingHandler handler = new RecordingHandler();
        POP3ServerLexer lexer = new POP3ServerLexer(handler, 5);
        StringBuilder longWord = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longWord.append('X');
        }
        lexer.feed(bytesOf(longWord + "\r\n"));
        assertEquals(1, handler.tokenTooLongCount);
        for (Event e : handler.events) {
            assertNotEquals(POP3ServerLexer.Token.KEYWORD, e.type);
        }
    }

    @Test
    public void testCapNotEnforcedOnArgsText() {
        RecordingHandler handler = new RecordingHandler();
        POP3ServerLexer lexer = new POP3ServerLexer(handler, 5);
        StringBuilder longArgs = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            longArgs.append('a');
        }
        lexer.feed(bytesOf("CMD " + longArgs + "\r\n"));
        assertEquals(0, handler.tokenTooLongCount);
        assertEquals(longArgs.toString(), handler.reconstructedArgs());
    }

    @Test
    public void testSlicedAtEveryChunkSizeReproducesSameTokens() {
        String line = "PASS s3cr3t p@ss with spaces\r\n";
        byte[] wire = line.getBytes(StandardCharsets.US_ASCII);

        RecordingHandler whole = new RecordingHandler();
        new POP3ServerLexer(whole, 512).feed(bytesOf(line));
        String expectedArgs = whole.reconstructedArgs();

        for (int chunkSize = 1; chunkSize <= wire.length; chunkSize++) {
            RecordingHandler handler = new RecordingHandler();
            POP3ServerLexer lexer = new POP3ServerLexer(handler, 512);
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
