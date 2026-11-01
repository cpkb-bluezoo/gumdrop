/*
 * MimeParserHeaderStreamingTest.java
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
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests that {@link MimeParser} streams the headers it does not itself
 * understand to the handler as they arrive, without holding them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MimeParserHeaderStreamingTest {

    /** Logs the header events, merging adjacent pieces of text. */
    static class Log implements MimeHandler {
        final List<String> events = new ArrayList<String>();
        final StringBuilder text = new StringBuilder();
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        String rawName;
        int largest;
        long total;

        private void flush() {
            if (text.length() > 0) {
                events.add("text: " + text);
                text.setLength(0);
            }
            if (raw.size() > 0) {
                events.add("raw: " + new String(raw.toByteArray(), StandardCharsets.ISO_8859_1));
                raw.reset();
            }
        }

        @Override
        public HeaderDelivery headerDelivery(String name) {
            return name.equals(rawName) ? HeaderDelivery.RAW : HeaderDelivery.UNSTRUCTURED;
        }

        @Override
        public void startHeader(String name) {
            flush();
            events.add("start: " + name);
        }

        @Override
        public void headerContent(String content) {
            largest = Math.max(largest, content.length());
            total += content.length();
            text.append(content);
        }

        @Override
        public void rawHeaderContent(ByteBuffer bytes) {
            largest = Math.max(largest, bytes.remaining());
            total += bytes.remaining();
            while (bytes.hasRemaining()) {
                raw.write(bytes.get());
            }
        }

        @Override
        public void endHeader() {
            flush();
            events.add("end");
        }

        @Override
        public void setLocator(MimeLocator locator) {
        }

        @Override
        public void startEntity(String boundary) {
        }

        @Override
        public void contentType(ContentType contentType) {
            flush();
            events.add("contentType");
        }

        @Override
        public void contentDisposition(ContentDisposition contentDisposition) {
        }

        @Override
        public void contentTransferEncoding(String encoding) {
            flush();
            events.add("contentTransferEncoding");
        }

        @Override
        public void contentID(ContentID contentID) {
        }

        @Override
        public void contentDescription(String description) {
            flush();
            events.add("contentDescription: " + description);
        }

        @Override
        public void mimeVersion(MimeVersion version) {
        }

        @Override
        public void endHeaders() {
            flush();
            events.add("endHeaders");
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            data.position(data.limit());
        }

        @Override
        public void unexpectedContent(ByteBuffer data) {
            data.position(data.limit());
        }

        @Override
        public void endEntity(String boundary) {
        }
    }

    private static Log parse(String text, int chunk, String rawName) throws MimeParseException {
        Log log = new Log();
        log.rawName = rawName;
        MimeParser parser = new MimeParser();
        parser.setHandler(log);
        byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(4096);
        int size = chunk == 0 ? Math.min(data.length, 2000) : chunk;
        for (int off = 0; off < data.length; off += size) {
            buf.put(data, off, Math.min(size, data.length - off));
            buf.flip();
            parser.receive(buf);
            buf.compact();
        }
        buf.flip();
        parser.receive(buf);
        parser.close();
        return log;
    }

    private static void assertSameAtEveryChunking(List<String> expected, String text, String rawName)
            throws MimeParseException {
        assertEquals("whole", expected, parse(text, 0, rawName).events);
        for (int chunk = 1; chunk <= text.length(); chunk += (chunk < 60 ? 1 : 17)) {
            assertEquals("chunk " + chunk, expected, parse(text, chunk, rawName).events);
        }
    }

    private static List<String> list(String... items) {
        List<String> l = new ArrayList<String>();
        for (String item : items) {
            l.add(item);
        }
        return l;
    }

    @Test
    public void unknownHeadersAreStreamedDecodedAndUnfolded() throws Exception {
        String text = "Subject: =?UTF-8?Q?caf=C3=A9?= is\r\n open\r\nX-Other:   v  \r\n\r\nbody\r\n";
        assertSameAtEveryChunking(list(
            "start: Subject", "text: caf\u00e9 is open", "end",
            "start: X-Other", "text: v", "end",
            "endHeaders"), text, null);
    }

    @Test
    public void headerWithNoValueHasNoContent() throws Exception {
        assertSameAtEveryChunking(list("start: X-Empty", "end", "endHeaders"),
            "X-Empty:\r\n\r\n", null);
    }

    @Test
    public void theHeadersTheParserUnderstandsAreNotStreamed() throws Exception {
        String text = "Content-Type: text/plain\r\nContent-Transfer-Encoding: 7bit\r\n"
            + "MIME-Version: 1.0\r\nContent-ID: <a@b>\r\nContent-Disposition: inline\r\n"
            + "X-Last: z\r\n\r\n";
        assertSameAtEveryChunking(list(
            "contentType", "contentTransferEncoding",
            "start: X-Last", "text: z", "end", "endHeaders"), text, null);
    }

    @Test
    public void contentDescriptionIsStreamedAndStillReported() throws Exception {
        assertSameAtEveryChunking(list(
            "start: Content-Description", "text: a thing", "end",
            "contentDescription: a thing", "endHeaders"),
            "Content-Description: a thing\r\n\r\n", null);
    }

    @Test
    public void aHandlerCanTakeAHeaderRaw() throws Exception {
        String text = "X-Raw: =?UTF-8?Q?a?=\r\n b\r\nX-Cooked: =?UTF-8?Q?a?=\r\n\r\n";
        assertSameAtEveryChunking(list(
            "start: X-Raw", "raw:  =?UTF-8?Q?a?= b", "end",
            "start: X-Cooked", "text: a", "end", "endHeaders"), text, "X-Raw");
    }

    @Test
    public void aHeaderOfAnySizeIsNotHeld() throws Exception {
        StringBuilder line = new StringBuilder(" ");
        for (int i = 0; i < 900; i++) {
            line.append('a');
        }
        StringBuilder text = new StringBuilder("X-Big:");
        for (int i = 0; i < 6000; i++) {
            text.append(line).append("\r\n");
        }
        text.append("\r\n");
        Log log = parse(text.toString(), 0, null);
        // 5.4 MB, far beyond the 32 KB a held header is limited to
        assertTrue(log.total > 5000000L);
        assertTrue("pieces are bounded: " + log.largest, log.largest < 8192);
        assertEquals(4, log.events.size());
        assertEquals("start: X-Big", log.events.get(0));
        assertEquals("end", log.events.get(2));
    }

    @Test
    public void rawHeaderOfAnySizeIsNotHeld() throws Exception {
        StringBuilder text = new StringBuilder("X-Raw:");
        for (int i = 0; i < 2000; i++) {
            text.append(" aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\r\n");
        }
        text.append("\r\n");
        Log log = parse(text.toString(), 0, "X-Raw");
        assertEquals(2000L * 65, log.total);
    }
}
