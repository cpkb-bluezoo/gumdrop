/*
 * MimeParserTransferEncodingStreamingTest.java
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
package org.bluezoo.gumdrop.mime;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests that base64 and quoted-printable bodies are decoded as they arrive,
 * whatever the chunking and however long their lines, with only a few bytes
 * of the encoded text held between chunks.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MimeParserTransferEncodingStreamingTest {

    /** Collects the decoded body and the size of the largest piece. */
    static final class Sink implements MimeHandler {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int largest;

        @Override
        public void setLocator(MimeLocator locator) {
        }

        @Override
        public void startEntity(String boundary) {
        }

        @Override
        public void contentType(ContentType contentType) {
        }

        @Override
        public void contentDisposition(ContentDisposition contentDisposition) {
        }

        @Override
        public void contentTransferEncoding(String encoding) {
        }

        @Override
        public void contentID(ContentID contentID) {
        }

        @Override
        public void contentDescription(String description) {
        }

        @Override
        public void mimeVersion(MimeVersion version) {
        }

        @Override
        public void endHeaders() {
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            largest = Math.max(largest, data.remaining());
            while (data.hasRemaining()) {
                body.write(data.get());
            }
        }

        @Override
        public void unexpectedContent(ByteBuffer data) {
            data.position(data.limit());
        }

        @Override
        public void endEntity(String boundary) {
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** Parses data in chunks, with a buffer of the size of a chunk. */
    private static Sink parse(byte[] data, int chunk, boolean lenient) throws MimeParseException {
        Sink sink = new Sink();
        MimeParser parser = new MimeParser();
        parser.setAllowMalformed(lenient);
        parser.setHandler(sink);
        ByteBuffer buf = ByteBuffer.allocate(Math.max(chunk, 2048));
        int off = 0;
        while (off < data.length) {
            int n = Math.min(chunk, data.length - off);
            buf.put(data, off, n);
            off += n;
            buf.flip();
            parser.receive(buf);
            buf.compact();
        }
        buf.flip();
        parser.receive(buf);
        parser.close();
        return sink;
    }

    private static byte[] quotedPrintable(byte[] raw, int softBreakEvery) {
        StringBuilder sb = new StringBuilder();
        int column = 0;
        for (int i = 0; i < raw.length; i++) {
            int b = raw[i] & 0xff;
            String piece;
            if (b >= 33 && b <= 126 && b != '=') {
                piece = String.valueOf((char) b);
            } else {
                piece = String.format("=%02X", b);
            }
            if (softBreakEvery > 0 && column + piece.length() > softBreakEvery) {
                sb.append("=\r\n");
                column = 0;
            }
            sb.append(piece);
            column += piece.length();
        }
        return bytes(sb.toString());
    }

    private static byte[] random(int n, long seed) {
        byte[] raw = new byte[n];
        new Random(seed).nextBytes(raw);
        return raw;
    }

    @Test
    public void base64IsDecodedWhateverTheChunking() throws Exception {
        byte[] raw = random(1000, 1L);
        byte[] encoded = Base64.getMimeEncoder(76, bytes("\r\n")).encode(raw);
        byte[] message = concat(bytes("Content-Transfer-Encoding: base64\r\n\r\n"), encoded);
        for (int chunk = 1; chunk <= 200; chunk++) {
            assertArrayEquals("chunk " + chunk, raw, parse(message, chunk, false).body.toByteArray());
        }
    }

    @Test
    public void base64InAMultipartPartIsDecodedWhateverTheChunking() throws Exception {
        byte[] raw = random(400, 2L);
        byte[] encoded = Base64.getMimeEncoder(76, bytes("\r\n")).encode(raw);
        byte[] message = concat(concat(
            bytes("Content-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n"), encoded),
            bytes("\r\n--b--\r\n"));
        for (int chunk = 1; chunk <= 120; chunk++) {
            assertArrayEquals("chunk " + chunk, raw, parse(message, chunk, false).body.toByteArray());
        }
    }

    @Test
    public void quotedPrintableIsDecodedWhateverTheChunking() throws Exception {
        byte[] raw = random(600, 3L);
        byte[] message = concat(bytes("Content-Transfer-Encoding: quoted-printable\r\n\r\n"),
            quotedPrintable(raw, 70));
        for (int chunk = 1; chunk <= 150; chunk++) {
            assertArrayEquals("chunk " + chunk, raw, parse(message, chunk, false).body.toByteArray());
        }
    }

    @Test
    public void quotedPrintableInAMultipartPartIsDecodedWhateverTheChunking() throws Exception {
        byte[] raw = random(300, 4L);
        byte[] message = concat(concat(
            bytes("Content-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n\r\n"),
            quotedPrintable(raw, 70)), bytes("\r\n--b--\r\n"));
        for (int chunk = 1; chunk <= 100; chunk++) {
            assertArrayEquals("chunk " + chunk, raw, parse(message, chunk, false).body.toByteArray());
        }
    }

    @Test
    public void quotedPrintableEscapesSplitAtEveryPosition() throws Exception {
        String text = "Content-Transfer-Encoding: quoted-printable\r\n\r\n"
            + "a=3Db=\r\nc=C3=A9 =zz=4 d=\r\ne==3D=\n=";
        // =3D is '=', "=\r\n" and "=\n" are soft breaks, the invalid escapes
        // are literal, and so is the final lone '='
        String expected = "a=bc\u00c3\u00a9 =zz=4 de===";
        for (int chunk = 1; chunk <= 20; chunk++) {
            assertEquals("chunk " + chunk, expected,
                new String(parse(bytes(text), chunk, false).body.toByteArray(),
                    StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    public void base64SegmentsEachEndedByPaddingAreAllDecoded() throws Exception {
        byte[] message = bytes("Content-Transfer-Encoding: base64\r\n\r\nQQ==\r\nQg==\r\nQw==\r\n");
        for (int chunk = 1; chunk <= 12; chunk++) {
            assertEquals("chunk " + chunk, "ABC",
                new String(parse(message, chunk, false).body.toByteArray(), StandardCharsets.ISO_8859_1));
        }
        assertEquals("ABC", new String(parse(message, 1000, false).body.toByteArray(),
            StandardCharsets.ISO_8859_1));
    }

    @Test
    public void base64OverlongLineIsRejectedUnlessLenient() throws Exception {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            line.append('A');
        }
        byte[] message = bytes("Content-Transfer-Encoding: base64\r\n\r\n" + line + "\r\n");
        for (int chunk : new int[] { 1, 7, 1000 }) {
            try {
                parse(message, chunk, false);
                assertTrue("chunk " + chunk + ": a line over 76 characters must be rejected", false);
            } catch (IllegalArgumentException expected) {
                // RFC 2045 section 6.8
            }
        }
        assertEquals(75, parse(message, 7, true).body.size());
    }

    @Test
    public void enormousBase64LineIsDecodedInBoundedPieces() throws Exception {
        byte[] raw = random(3000000, 5L);
        byte[] encoded = Base64.getEncoder().encode(raw); // one line of 4 MB
        byte[] message = concat(bytes("Content-Transfer-Encoding: base64\r\n\r\n"), encoded);
        Sink sink = parse(message, 4096, true);
        assertArrayEquals(raw, sink.body.toByteArray());
        assertTrue("pieces are bounded: " + sink.largest, sink.largest <= 4096);
    }

    @Test
    public void enormousQuotedPrintableLineIsDecodedInBoundedPieces() throws Exception {
        byte[] raw = random(500000, 6L);
        byte[] message = concat(bytes("Content-Transfer-Encoding: quoted-printable\r\n\r\n"),
            quotedPrintable(raw, 0)); // no soft line breaks: one line
        Sink sink = parse(message, 4096, false);
        assertArrayEquals(raw, sink.body.toByteArray());
        assertTrue("pieces are bounded: " + sink.largest, sink.largest <= 4096);
        assertFalse(sink.body.size() == 0);
    }

    @Test
    public void unpaddedBase64TailIsFlushedAtTheEndOfTheBody() throws Exception {
        byte[] message = bytes("Content-Transfer-Encoding: base64\r\n\r\nQUJD\r\nRA");
        for (int chunk = 1; chunk <= 8; chunk++) {
            assertEquals("chunk " + chunk, "ABCD",
                new String(parse(message, chunk, false).body.toByteArray(), StandardCharsets.ISO_8859_1));
        }
    }
}
