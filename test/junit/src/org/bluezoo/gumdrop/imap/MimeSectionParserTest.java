/*
 * MimeSectionParserTest.java
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Push-parser tests for {@link MimeSectionParser}: the same section must be
 * produced however the input is split into chunks (down to single bytes),
 * for CRLF and bare LF line endings, long lines and unterminated final
 * lines. Also covers {@link MimeSectionSpec} and
 * {@link MimeTransferDecoder}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MimeSectionParserTest {

    private static final String MESSAGE =
            "Subject: s\r\n"
            + "Content-Type: multipart/mixed; boundary=B\r\n"
            + "\r\n"
            + "--B\r\n"
            + "Content-Type: text/plain\r\n"
            + "\r\n"
            + "one\r\n"
            + "two\r\n"
            + "--B\r\n"
            + "Content-Type: message/rfc822\r\n"
            + "\r\n"
            + "Subject: inner\r\n"
            + "\r\n"
            + "inner body\r\n"
            + "--B--\r\n";

    /** Collects section data. */
    private static final class Collect implements MimeSectionParser.Sink {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean begun;
        boolean composite;

        @Override
        public void begin(String mediaType, String charset, String encoding,
                boolean composite) {
            begun = true;
            this.composite = composite;
        }

        @Override
        public void data(byte[] buf, int off, int len) {
            out.write(buf, off, len);
        }

        String text() {
            return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    private static String run(String section, String message, int chunk) {
        MimeSectionSpec spec = MimeSectionSpec.parse(section, false);
        assertNotNull(section, spec);
        Collect sink = new Collect();
        MimeSectionParser parser = new MimeSectionParser(spec, sink);
        byte[] data = message.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(MimeSectionParser.MAX_LINE * 4);
        int pos = 0;
        while (pos < data.length && !parser.isDone()) {
            int n = Math.min(Math.min(chunk, data.length - pos), buf.remaining());
            buf.put(data, pos, n);
            pos += n;
            buf.flip();
            parser.receive(buf);
            buf.compact();
        }
        buf.flip();
        parser.finish(buf);
        return sink.text();
    }

    private static void assertAllSplits(String expected, String section,
            String message) {
        int[] chunks = new int[] {1, 2, 3, 7, 64, 100000};
        for (int i = 0; i < chunks.length; i++) {
            assertEquals("chunk " + chunks[i], expected,
                    run(section, message, chunks[i]));
        }
    }

    @Test
    public void wholeMessageAndTopLevelSections() {
        assertAllSplits(MESSAGE, "", MESSAGE);
        assertAllSplits("Subject: s\r\n"
                + "Content-Type: multipart/mixed; boundary=B\r\n\r\n",
                "HEADER", MESSAGE);
        assertAllSplits("Subject: s\r\n\r\n", "HEADER.FIELDS (subject)", MESSAGE);
        assertAllSplits("Content-Type: multipart/mixed; boundary=B\r\n\r\n",
                "HEADER.FIELDS.NOT (Subject)", MESSAGE);
        assertAllSplits(MESSAGE.substring(MESSAGE.indexOf("--B")), "TEXT",
                MESSAGE);
    }

    @Test
    public void numberedSections() {
        assertAllSplits("one\r\ntwo", "1", MESSAGE);
        assertAllSplits("Content-Type: text/plain\r\n\r\n", "1.MIME", MESSAGE);
        assertAllSplits("Subject: inner\r\n\r\ninner body", "2", MESSAGE);
        assertAllSplits("Subject: inner\r\n\r\n", "2.HEADER", MESSAGE);
        assertAllSplits("inner body", "2.TEXT", MESSAGE);
        assertAllSplits("inner body", "2.1", MESSAGE);
        assertAllSplits("", "3", MESSAGE);
    }

    @Test
    public void bareLineFeedsAndMissingFinalNewline() {
        String lf = MESSAGE.replace("\r\n", "\n");
        assertAllSplits("one\ntwo", "1", lf);
        String unterminated = "Subject: x\r\n\r\nno newline at end";
        assertAllSplits("no newline at end", "1", unterminated);
        assertAllSplits("no newline at end", "TEXT", unterminated);
    }

    @Test
    public void lineLongerThanTheLineLimitIsPassedThrough() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MimeSectionParser.MAX_LINE * 2 + 10; i++) {
            sb.append((char) ('a' + i % 26));
        }
        String body = sb.toString();
        String message = "Subject: x\r\n\r\n" + body + "\r\nlast\r\n";
        assertAllSplits(body + "\r\nlast\r\n", "1", message);
    }

    @Test
    public void nestedBoundaryClosesInnerParts() {
        String message = "Content-Type: multipart/mixed; boundary=O\r\n\r\n"
                + "--O\r\n"
                + "Content-Type: multipart/alternative; boundary=I\r\n\r\n"
                + "--I\r\n\r\nin-one\r\n"
                + "--O\r\n"
                + "\r\nsecond\r\n"
                + "--O--\r\n";
        assertAllSplits("in-one", "1.1", message);
        assertAllSplits("second", "2", message);
    }

    @Test
    public void unknownPartsAreNotFound() {
        MimeSectionSpec spec = MimeSectionSpec.parse("7", false);
        Collect sink = new Collect();
        MimeSectionParser parser = new MimeSectionParser(spec, sink);
        ByteBuffer buf = ByteBuffer.wrap(
                MESSAGE.getBytes(StandardCharsets.ISO_8859_1));
        parser.receive(buf);
        parser.finish(buf);
        assertFalse(parser.isFound());
        assertFalse(sink.begun);
    }

    @Test
    public void extractorStopsEarlyForPartialWindows() throws Exception {
        MimeSectionSpec spec = MimeSectionSpec.parse("1", false);
        byte[] data = MESSAGE.getBytes(StandardCharsets.ISO_8859_1);
        MimeSectionExtractor.Result result = MimeSectionExtractor.extract(
                Channels.newChannel(new java.io.ByteArrayInputStream(data)),
                spec, MimeSectionExtractor.Form.BODY, 1, 3);
        assertTrue(result.available);
        assertEquals("ne\r", new String(result.data,
                StandardCharsets.ISO_8859_1));
        assertEquals(1, result.origin);
    }

    @Test
    public void specParsing() {
        assertNull(MimeSectionSpec.parse("1.", false));
        assertNull(MimeSectionSpec.parse("0", false));
        assertNull(MimeSectionSpec.parse("1x", false));
        assertNull(MimeSectionSpec.parse("HEADER.FIELDS a", false));
        assertNull(MimeSectionSpec.parse("MIME", false));
        assertNull(MimeSectionSpec.parse("1.HEADER", true));
        assertNull(MimeSectionSpec.parse("bogus", false));
        assertNull(MimeSectionSpec.parse("99999999999", false));
        assertNotNull(MimeSectionSpec.parse("1.2.3.MIME", false));
        MimeSectionSpec fields = MimeSectionSpec.parse(
                "HEADER.FIELDS (\"Subject\" To)", false);
        assertTrue(fields.fields.contains("subject"));
        assertTrue(fields.fields.contains("to"));
    }

    @Test
    public void transferDecoderIsChunkIndependent() {
        String base64 = "SGVsbG8g\r\nd29y bGQ=\r\nignored";
        String qp = "a=3Db =\r\nc=4";
        for (int chunk = 1; chunk <= 5; chunk++) {
            assertEquals("Hello world", decode("base64", base64, chunk));
            assertEquals("a=b c=4", decode("quoted-printable", qp, chunk));
            assertEquals("plain", decode("7bit", "plain", chunk));
        }
        assertNull(MimeTransferDecoder.create("x-weird", null));
    }

    private static String decode(String encoding, String text, int chunk) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        MimeTransferDecoder decoder = MimeTransferDecoder.create(encoding,
                new MimeTransferDecoder.Output() {
                    @Override
                    public void write(byte[] buf, int off, int len) {
                        out.write(buf, off, len);
                    }
                });
        byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < data.length; i += chunk) {
            decoder.write(data, i, Math.min(chunk, data.length - i));
        }
        decoder.finish();
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}
