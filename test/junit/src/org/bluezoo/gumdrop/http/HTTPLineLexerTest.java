/*
 * HTTPLineLexerTest.java
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.ByteStreamLexer;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HTTPLineLexer}. Unlike every other lexer in this
 * conversion, this one emits a single token type ({@link
 * HTTPLineLexer.Token#LINE}) spanning a whole line <em>including its
 * CRLF</em> — deliberately matching {@code LineParser}'s buffer contract
 * so {@code HTTPProtocolHandler}'s existing whole-line decode methods
 * needed no changes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPLineLexerTest {

    static class Event {
        final HTTPLineLexer.Token type;
        final String text;
        Event(HTTPLineLexer.Token type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    static class RecordingHandler implements ByteStreamLexer.Handler<HTTPLineLexer.Token> {
        final List<Event> events = new ArrayList<Event>();
        int tokenTooLongCount;

        @Override
        public boolean token(HTTPLineLexer.Token type, ByteBuffer window) {
            byte[] copy = new byte[window.remaining()];
            window.get(copy);
            events.add(new Event(type, new String(copy, StandardCharsets.US_ASCII)));
            return false; // never latches text mode
        }

        @Override
        public void rawBytes(ByteBuffer slice) {
            fail("HTTPLineLexer should never enter raw mode itself");
        }

        @Override
        public void tokenTooLong() {
            tokenTooLongCount++;
        }
    }

    private static ByteBuffer bytesOf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void testRequestLineIncludesCrlf() {
        RecordingHandler handler = new RecordingHandler();
        HTTPLineLexer lexer = new HTTPLineLexer(handler, 1024);
        lexer.feed(bytesOf("GET / HTTP/1.1\r\n"));
        assertEquals(1, handler.events.size());
        assertEquals(HTTPLineLexer.Token.LINE, handler.events.get(0).type);
        assertEquals("GET / HTTP/1.1\r\n", handler.events.get(0).text);
    }

    @Test
    public void testMultipleLinesEachIncludeCrlf() {
        RecordingHandler handler = new RecordingHandler();
        HTTPLineLexer lexer = new HTTPLineLexer(handler, 1024);
        lexer.feed(bytesOf("Host: example.com\r\nAccept: */*\r\n\r\n"));
        assertEquals(3, handler.events.size());
        assertEquals("Host: example.com\r\n", handler.events.get(0).text);
        assertEquals("Accept: */*\r\n", handler.events.get(1).text);
        assertEquals("\r\n", handler.events.get(2).text);
    }

    @Test
    public void testBareEmptyLineEmittedUnconditionally() {
        // Unlike every other lexer here, an empty line is NOT suppressed —
        // LineParser delivered every CRLF-terminated span the same way,
        // including the blank line that terminates a header section, and
        // processHeaderLine()/processTrailerLine() already handle a
        // zero-length LINE window (just "\r\n") themselves.
        RecordingHandler handler = new RecordingHandler();
        HTTPLineLexer lexer = new HTTPLineLexer(handler, 1024);
        lexer.feed(bytesOf("\r\n"));
        assertEquals(1, handler.events.size());
        assertEquals("\r\n", handler.events.get(0).text);
    }

    @Test
    public void testCapEnforcedOnWholeLine() {
        RecordingHandler handler = new RecordingHandler();
        HTTPLineLexer lexer = new HTTPLineLexer(handler, 10);
        StringBuilder longUri = new StringBuilder("GET /");
        for (int i = 0; i < 20; i++) {
            longUri.append('a');
        }
        longUri.append(" HTTP/1.1");
        lexer.feed(bytesOf(longUri + "\r\n"));
        assertEquals(1, handler.tokenTooLongCount);
        assertEquals(0, handler.events.size());
    }

    @Test
    public void testSlicedAtEveryChunkSizeReproducesSameLines() {
        String data = "GET /path HTTP/1.1\r\nHost: example.com\r\n\r\n";
        byte[] wire = data.getBytes(StandardCharsets.US_ASCII);

        RecordingHandler whole = new RecordingHandler();
        new HTTPLineLexer(whole, 1024).feed(bytesOf(data));

        for (int chunkSize = 1; chunkSize <= wire.length; chunkSize++) {
            RecordingHandler handler = new RecordingHandler();
            HTTPLineLexer lexer = new HTTPLineLexer(handler, 1024);
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
            assertEquals("chunk size " + chunkSize, whole.events.size(), handler.events.size());
            for (int i = 0; i < whole.events.size(); i++) {
                assertEquals("chunk size " + chunkSize + " line " + i,
                        whole.events.get(i).text, handler.events.get(i).text);
            }
        }
    }

    @Test
    public void testEnterRawBodyThenResumeLineScanning() {
        final List<String> rawChunks = new ArrayList<String>();
        final HTTPLineLexer[] lexerHolder = new HTTPLineLexer[1];
        ByteStreamLexer.Handler<HTTPLineLexer.Token> handler =
                new ByteStreamLexer.Handler<HTTPLineLexer.Token>() {
            boolean enteredRaw;
            final List<Event> events = new ArrayList<Event>();

            @Override
            public boolean token(HTTPLineLexer.Token type, ByteBuffer window) {
                byte[] copy = new byte[window.remaining()];
                window.get(copy);
                events.add(new Event(type, new String(copy, StandardCharsets.US_ASCII)));
                if (!enteredRaw && "X-Len: 5\r\n".equals(events.get(events.size() - 1).text)) {
                    enteredRaw = true;
                    lexerHolder[0].enterRawBody(5);
                }
                return false;
            }

            @Override
            public void rawBytes(ByteBuffer slice) {
                byte[] copy = new byte[slice.remaining()];
                slice.get(copy);
                rawChunks.add(new String(copy, StandardCharsets.US_ASCII));
            }

            @Override
            public void tokenTooLong() {
                fail("unexpected tokenTooLong");
            }
        };
        lexerHolder[0] = new HTTPLineLexer(handler, 1024);
        // "hello" (5 raw bytes) immediately followed by another line.
        lexerHolder[0].feed(bytesOf("X-Len: 5\r\nhelloNEXT: line\r\n"));
        assertEquals(1, rawChunks.size());
        assertEquals("hello", rawChunks.get(0));
    }
}
