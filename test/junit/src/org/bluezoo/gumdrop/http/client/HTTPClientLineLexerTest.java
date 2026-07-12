/*
 * HTTPClientLineLexerTest.java
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

package org.bluezoo.gumdrop.http.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.ByteStreamLexer;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HTTPClientLineLexer}. Structurally identical to
 * the server-side {@code org.bluezoo.gumdrop.http.HTTPLineLexer}: a
 * single token type ({@link HTTPClientLineLexer.Token#LINE}) spanning a
 * whole line <em>including its CRLF</em>, matching the pre-conversion
 * bespoke {@code findCRLF}/{@code parseBuffer} path's own line-extraction
 * shape.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPClientLineLexerTest {

    static class Event {
        final HTTPClientLineLexer.Token type;
        final String text;
        Event(HTTPClientLineLexer.Token type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    static class RecordingHandler implements ByteStreamLexer.Handler<HTTPClientLineLexer.Token> {
        final List<Event> events = new ArrayList<Event>();
        int tokenTooLongCount;

        @Override
        public boolean token(HTTPClientLineLexer.Token type, ByteBuffer window) {
            byte[] copy = new byte[window.remaining()];
            window.get(copy);
            events.add(new Event(type, new String(copy, StandardCharsets.US_ASCII)));
            return false; // never latches text mode
        }

        @Override
        public void rawBytes(ByteBuffer slice) {
            fail("HTTPClientLineLexer should never enter raw mode itself");
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
    public void testStatusLineIncludesCrlf() {
        RecordingHandler handler = new RecordingHandler();
        HTTPClientLineLexer lexer = new HTTPClientLineLexer(handler, 1024);
        lexer.feed(bytesOf("HTTP/1.1 200 OK\r\n"));
        assertEquals(1, handler.events.size());
        assertEquals(HTTPClientLineLexer.Token.LINE, handler.events.get(0).type);
        assertEquals("HTTP/1.1 200 OK\r\n", handler.events.get(0).text);
    }

    @Test
    public void testMultipleLinesEachIncludeCrlf() {
        RecordingHandler handler = new RecordingHandler();
        HTTPClientLineLexer lexer = new HTTPClientLineLexer(handler, 1024);
        lexer.feed(bytesOf("Content-Type: text/plain\r\nContent-Length: 5\r\n\r\n"));
        assertEquals(3, handler.events.size());
        assertEquals("Content-Type: text/plain\r\n", handler.events.get(0).text);
        assertEquals("Content-Length: 5\r\n", handler.events.get(1).text);
        assertEquals("\r\n", handler.events.get(2).text);
    }

    @Test
    public void testBareEmptyLineEmittedUnconditionally() {
        RecordingHandler handler = new RecordingHandler();
        HTTPClientLineLexer lexer = new HTTPClientLineLexer(handler, 1024);
        lexer.feed(bytesOf("\r\n"));
        assertEquals(1, handler.events.size());
        assertEquals("\r\n", handler.events.get(0).text);
    }

    @Test
    public void testCapEnforcedOnWholeLine() {
        RecordingHandler handler = new RecordingHandler();
        HTTPClientLineLexer lexer = new HTTPClientLineLexer(handler, 10);
        StringBuilder longHeader = new StringBuilder("X-Long: ");
        for (int i = 0; i < 20; i++) {
            longHeader.append('a');
        }
        lexer.feed(bytesOf(longHeader + "\r\n"));
        assertEquals(1, handler.tokenTooLongCount);
        assertEquals(0, handler.events.size());
    }

    @Test
    public void testSlicedAtEveryChunkSizeReproducesSameLines() {
        String data = "HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n";
        byte[] wire = data.getBytes(StandardCharsets.US_ASCII);

        RecordingHandler whole = new RecordingHandler();
        new HTTPClientLineLexer(whole, 1024).feed(bytesOf(data));

        for (int chunkSize = 1; chunkSize <= wire.length; chunkSize++) {
            RecordingHandler handler = new RecordingHandler();
            HTTPClientLineLexer lexer = new HTTPClientLineLexer(handler, 1024);
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
        final HTTPClientLineLexer[] lexerHolder = new HTTPClientLineLexer[1];
        ByteStreamLexer.Handler<HTTPClientLineLexer.Token> handler =
                new ByteStreamLexer.Handler<HTTPClientLineLexer.Token>() {
            boolean enteredRaw;
            final List<Event> events = new ArrayList<Event>();

            @Override
            public boolean token(HTTPClientLineLexer.Token type, ByteBuffer window) {
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
        lexerHolder[0] = new HTTPClientLineLexer(handler, 1024);
        // "hello" (5 raw bytes) immediately followed by another line.
        lexerHolder[0].feed(bytesOf("X-Len: 5\r\nhelloNEXT: line\r\n"));
        assertEquals(1, rawChunks.size());
        assertEquals("hello", rawChunks.get(0));
    }
}
