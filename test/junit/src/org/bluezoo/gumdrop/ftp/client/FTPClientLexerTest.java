/*
 * FTPClientLexerTest.java
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

package org.bluezoo.gumdrop.ftp.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.ByteStreamLexer;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link FtpClientLexer}, verifying exact token content for
 * the {@code CODE [SEP TEXT] CRLF} reply grammar (RFC 959 §4.2), which is
 * identical to SMTP's — see {@code SMTPClientLexerTest} for the pattern
 * this mirrors.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPClientLexerTest {

    static class Event {
        final FtpClientLexer.Token type;
        final String text;
        Event(FtpClientLexer.Token type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    static class RecordingHandler implements ByteStreamLexer.Handler<FtpClientLexer.Token> {
        final List<Event> events = new ArrayList<Event>();
        int tokenTooLongCount;
        FtpClientLexer lexerRef;

        @Override
        public boolean token(FtpClientLexer.Token type, ByteBuffer window) {
            byte[] copy = new byte[window.remaining()];
            window.get(copy);
            events.add(new Event(type, new String(copy, StandardCharsets.US_ASCII)));
            if (type == FtpClientLexer.Token.CRLF) {
                lexerRef.resetForNextLine();
            }
            return type == FtpClientLexer.Token.DASH || type == FtpClientLexer.Token.SP;
        }

        @Override
        public void rawBytes(ByteBuffer slice) {
            fail("FTP client lexer should never enter raw mode");
        }

        @Override
        public void tokenTooLong() {
            tokenTooLongCount++;
        }

        String reconstructedText() {
            StringBuilder sb = new StringBuilder();
            for (Event e : events) {
                if (e.type == FtpClientLexer.Token.TEXT) {
                    sb.append(e.text);
                }
            }
            return sb.toString();
        }

        List<String> codes() {
            List<String> result = new ArrayList<String>();
            for (Event e : events) {
                if (e.type == FtpClientLexer.Token.CODE) {
                    result.add(e.text);
                }
            }
            return result;
        }
    }

    private static FtpClientLexer newLexer(RecordingHandler handler) {
        FtpClientLexer lexer = new FtpClientLexer(handler, Integer.MAX_VALUE);
        handler.lexerRef = lexer;
        return lexer;
    }

    private static ByteBuffer bytesOf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void testSingleLineReplyWithText() {
        RecordingHandler handler = new RecordingHandler();
        newLexer(handler).feed(bytesOf("220 Service ready\r\n"));
        assertEquals(FtpClientLexer.Token.CODE, handler.events.get(0).type);
        assertEquals("220", handler.events.get(0).text);
        assertEquals(FtpClientLexer.Token.SP, handler.events.get(1).type);
        assertEquals("Service ready", handler.reconstructedText());
        assertEquals(FtpClientLexer.Token.CRLF,
                handler.events.get(handler.events.size() - 1).type);
    }

    @Test
    public void testBareCodeNoSeparatorNoText() {
        RecordingHandler handler = new RecordingHandler();
        newLexer(handler).feed(bytesOf("220\r\n"));
        assertEquals(2, handler.events.size());
        assertEquals("220", handler.events.get(0).text);
        assertEquals(FtpClientLexer.Token.CRLF, handler.events.get(1).type);
    }

    @Test
    public void testContinuationDash() {
        RecordingHandler handler = new RecordingHandler();
        newLexer(handler).feed(bytesOf("150-About to open data connection\r\n"));
        assertEquals(FtpClientLexer.Token.DASH, handler.events.get(1).type);
        assertEquals("About to open data connection", handler.reconstructedText());
    }

    @Test
    public void testMultilineReplySequence() {
        RecordingHandler handler = new RecordingHandler();
        String wire = "214-The following commands are recognized\r\n"
                + "214-USER PASS ACCT\r\n"
                + "214-CWD CDUP PWD\r\n"
                + "214 HELP\r\n";
        newLexer(handler).feed(bytesOf(wire));
        // The regression this test guards: without resetForNextLine(),
        // sawCode leaks across lines once text mode has latched, and every
        // line after the first is misparsed.
        assertEquals(java.util.Arrays.asList("214", "214", "214", "214"), handler.codes());
        assertEquals("The following commands are recognizedUSER PASS ACCTCWD CDUP PWDHELP",
                handler.reconstructedText());
    }

    @Test
    public void testBlankLineEmitsNoCodeToken() {
        RecordingHandler handler = new RecordingHandler();
        newLexer(handler).feed(bytesOf("\r\n"));
        assertEquals(1, handler.events.size());
        assertEquals(FtpClientLexer.Token.CRLF, handler.events.get(0).type);
    }

    @Test
    public void testShortLineEmitsPartialCode() {
        RecordingHandler handler = new RecordingHandler();
        newLexer(handler).feed(bytesOf("22\r\n"));
        assertEquals(FtpClientLexer.Token.CODE, handler.events.get(0).type);
        assertEquals("22", handler.events.get(0).text);
    }

    @Test
    public void testPwdReplyWithQuotedPathname() {
        RecordingHandler handler = new RecordingHandler();
        newLexer(handler).feed(bytesOf("257 \"/home/user\" is current directory\r\n"));
        assertEquals("257", handler.events.get(0).text);
        assertEquals("\"/home/user\" is current directory", handler.reconstructedText());
    }

    @Test
    public void testSlicedAtEveryChunkSizeReproducesSameTokens() {
        String wire = "214-line one\r\n214-line two\r\n214 line three\r\n";
        byte[] bytes = wire.getBytes(StandardCharsets.US_ASCII);

        RecordingHandler whole = new RecordingHandler();
        newLexer(whole).feed(bytesOf(wire));
        String expectedText = whole.reconstructedText();
        List<String> expectedCodes = whole.codes();

        for (int chunkSize = 1; chunkSize <= bytes.length; chunkSize++) {
            RecordingHandler handler = new RecordingHandler();
            FtpClientLexer lexer = newLexer(handler);
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
