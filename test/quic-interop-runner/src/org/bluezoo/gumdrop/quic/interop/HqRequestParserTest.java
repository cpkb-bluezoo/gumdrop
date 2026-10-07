/*
 * HqRequestParserTest.java
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

package org.bluezoo.gumdrop.quic.interop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * {@link HqRequestParser} must reach the same result however the request
 * bytes are split across {@code receive} calls.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HqRequestParserTest {

    private static final class Recording implements HqRequestParser.Listener {

        String target;
        String malformed;

        @Override
        public void requestTarget(String target) {
            this.target = target;
        }

        @Override
        public void malformed(String reason) {
            this.malformed = reason;
        }

    }

    private static Recording feed(String request, int chunkSize, boolean fin) {
        Recording recording = new Recording();
        HqRequestParser parser = new HqRequestParser(recording);
        byte[] bytes = request.getBytes(StandardCharsets.US_ASCII);
        for (int offset = 0; offset < bytes.length; offset += chunkSize) {
            int end = Math.min(bytes.length, offset + chunkSize);
            parser.receive(ByteBuffer.wrap(bytes, offset, end - offset));
        }
        if (fin) {
            parser.readFinished();
        }
        return recording;
    }

    @Test
    public void wholeRequestInOneChunk() {
        Recording r = feed("GET /abcdef\r\n", 64, false);
        assertEquals("/abcdef", r.target);
        assertNull(r.malformed);
    }

    @Test
    public void oneByteAtATime() {
        Recording r = feed("GET /abcdef\r\n", 1, false);
        assertEquals("/abcdef", r.target);
        assertNull(r.malformed);
    }

    @Test
    public void splitInsideTerminator() {
        Recording r = feed("GET /abcdef\r\n", 12, false);
        assertEquals("/abcdef", r.target);
    }

    @Test
    public void bareLineFeedTerminator() {
        Recording r = feed("GET /x\n", 3, false);
        assertEquals("/x", r.target);
    }

    @Test
    public void unterminatedLineCompletedByFin() {
        Recording r = feed("GET /trailing", 5, true);
        assertEquals("/trailing", r.target);
    }

    @Test
    public void trailingVersionTokenIgnored() {
        Recording r = feed("GET /file HTTP/0.9\r\n", 7, false);
        assertEquals("/file", r.target);
    }

    @Test
    public void bytesAfterTheLineAreIgnored() {
        Recording recording = new Recording();
        HqRequestParser parser = new HqRequestParser(recording);
        ByteBuffer data = ByteBuffer.wrap("GET /a\r\nGET /b\r\n".getBytes(StandardCharsets.US_ASCII));
        parser.receive(data);
        assertEquals("/a", recording.target);
        assertTrue(parser.isDone());
        assertEquals("the whole chunk is consumed", 0, data.remaining());
    }

    @Test
    public void otherMethodIsMalformed() {
        Recording r = feed("POST /a\r\n", 2, false);
        assertNull(r.target);
        assertTrue(r.malformed != null);
    }

    @Test
    public void emptyRequestWithFinIsMalformed() {
        Recording r = feed("", 1, true);
        assertNull(r.target);
        assertTrue(r.malformed != null);
    }

    @Test
    public void overlongLineIsMalformed() {
        StringBuilder sb = new StringBuilder("GET /");
        for (int i = 0; i < HqRequestParser.MAX_LINE_LENGTH; i++) {
            sb.append('a');
        }
        sb.append("\r\n");
        Recording r = feed(sb.toString(), 100, false);
        assertNull(r.target);
        assertTrue(r.malformed != null);
    }

}
