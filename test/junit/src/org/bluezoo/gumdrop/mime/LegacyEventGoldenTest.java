/*
 * LegacyEventGoldenTest.java
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.GroupEmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.MessageHandler;
import org.bluezoo.gumdrop.mime.rfc5322.MessageParser;
import org.bluezoo.gumdrop.mime.rfc5322.ObsoleteStructureType;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Pins the events {@link MessageParser} delivers today, message by message,
 * so that the streaming rewrite of the parser can be shown to reproduce them
 * through its compatibility bridge.
 * <p>
 * Each message in {@code test/junit/resources/mime-golden/NAME.eml} is parsed
 * and every event is logged, with adjacent body content merged since the size
 * of the runs it arrives in is not part of the contract. The log is compared
 * with {@code NAME.golden} and must be the same however the input is divided
 * into chunks.
 * <p>
 * To regenerate the golden files after a deliberate change in behaviour, run
 * with {@code -Dmime.golden.update=test/junit/resources/mime-golden} and
 * review the diff.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LegacyEventGoldenTest {

    private static final String[] CORPUS = {
        "simple",
        "multipart",
        "encodings",
        "parameters",
        "invalid-structured",
        "obsolete",
        "no-final-newline",
        "headers-only",
        "long-lines",
        "unclosed-multipart",
        "no-boundary-param",
        "duplicate-content-type",
        "8bit-headers",
    };

    /** 0 is the whole message in one chunk. */
    private static final int[] CHUNK_SIZES = { 0, 1, 2, 3, 7, 64, 1000 };

    /** Logs every event, merging adjacent body content. */
    static final class Log implements MessageHandler {

        final List<String> lines = new ArrayList<String>();
        private final StringBuilder content = new StringBuilder();
        private String contentKind;
        private MimeLocator locator;

        private void flush() {
            if (contentKind != null) {
                lines.add(contentKind + ": " + escape(content.toString()));
                content.setLength(0);
                contentKind = null;
            }
        }

        private void add(String line) {
            flush();
            lines.add(line);
        }

        private void content(String kind, ByteBuffer data) {
            if (contentKind != null && !contentKind.equals(kind)) {
                flush();
            }
            contentKind = kind;
            byte[] b = new byte[data.remaining()];
            data.get(b);
            content.append(new String(b, StandardCharsets.ISO_8859_1));
        }

        @Override
        public void setLocator(MimeLocator locator) {
            this.locator = locator;
            add("locator");
        }

        @Override
        public void startEntity(String boundary) {
            add("startEntity: " + boundary);
        }

        @Override
        public void contentType(ContentType contentType) {
            add("contentType: " + contentType.toHeaderValue());
        }

        @Override
        public void contentDisposition(ContentDisposition contentDisposition) {
            add("contentDisposition: " + contentDisposition.toHeaderValue());
        }

        @Override
        public void contentTransferEncoding(String encoding) {
            add("contentTransferEncoding: " + encoding);
        }

        @Override
        public void contentID(ContentID contentID) {
            add("contentID: " + contentID);
        }

        @Override
        public void contentDescription(String description) {
            add("contentDescription: " + escape(description));
        }

        @Override
        public void mimeVersion(MimeVersion version) {
            add("mimeVersion: " + version);
        }

        @Override
        public void endHeaders() {
            add("endHeaders: offset=" + locator.getOffset() + " line=" + locator.getLineNumber());
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            content("body", data);
        }

        @Override
        public void unexpectedContent(ByteBuffer data) {
            content("unexpectedContent", data);
        }

        @Override
        public void endEntity(String boundary) {
            add("endEntity: " + boundary);
        }

        @Override
        public void header(String name, String value) {
            add("header: " + name + " = " + escape(value));
        }

        @Override
        public void unexpectedHeader(String name, String value) {
            add("unexpectedHeader: " + name + " = " + escape(value));
        }

        @Override
        public void dateHeader(String name, OffsetDateTime date) {
            add("dateHeader: " + name + " = " + date);
        }

        @Override
        public void addressHeader(String name, List<EmailAddress> addresses) {
            StringBuilder sb = new StringBuilder("addressHeader: " + name);
            for (EmailAddress a : addresses) {
                sb.append(" | ");
                describe(a, sb);
            }
            add(sb.toString());
        }

        private static void describe(EmailAddress a, StringBuilder sb) {
            if (a instanceof GroupEmailAddress) {
                GroupEmailAddress g = (GroupEmailAddress) a;
                sb.append("group ").append(escape(g.getGroupName())).append(" [");
                boolean first = true;
                for (EmailAddress m : g.getMembers()) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    describe(m, sb);
                }
                sb.append("]");
            } else {
                sb.append(escape(String.valueOf(a.getDisplayName())))
                    .append(" <").append(a.getLocalPart()).append("@")
                    .append(a.getDomain()).append(">")
                    .append(" comments=").append(a.getComments());
            }
        }

        @Override
        public void messageIDHeader(String name, List<ContentID> contentIDs) {
            StringBuilder sb = new StringBuilder("messageIDHeader: " + name);
            for (ContentID id : contentIDs) {
                sb.append(" | ").append(id);
            }
            add(sb.toString());
        }

        @Override
        public void obsoleteStructure(ObsoleteStructureType type) {
            add("obsoleteStructure: " + type);
        }

        String text() {
            flush();
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    static String escape(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 32 || c > 126) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static byte[] resource(String name) throws IOException {
        InputStream in = LegacyEventGoldenTest.class.getResourceAsStream("/mime-golden/" + name);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Parses data in chunks of the given size; an exception ends the log. */
    static String parse(byte[] data, int chunk) throws MimeParseException {
        Log log = new Log();
        MessageParser parser = new MessageParser();
        parser.setHandler(log);
        ByteBuffer buf = ByteBuffer.allocate(data.length + 8);
        int size = (chunk == 0) ? data.length : chunk;
        int off = 0;
        try {
            while (off < data.length) {
                int n = Math.min(size, data.length - off);
                buf.put(data, off, n);
                off += n;
                buf.flip();
                parser.receive(buf);
                buf.compact();
            }
            buf.flip();
            parser.receive(buf);
            parser.close();
        } catch (MimeParseException e) {
            log.add("exception: " + e.getClass().getSimpleName());
        }
        return log.text();
    }

    private static void write(String dir, String name, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(new File(dir, name));
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    @Test
    public void eventsMatchGoldensForEveryChunking() throws Exception {
        String update = System.getProperty("mime.golden.update");
        for (String name : CORPUS) {
            byte[] message = resource(name + ".eml");
            assertNotNull("missing corpus message " + name, message);
            String whole = parse(message, 0);
            if (update != null) {
                write(update, name + ".golden", whole);
                continue;
            }
            byte[] golden = resource(name + ".golden");
            assertNotNull("missing golden for " + name, golden);
            String expected = new String(golden, StandardCharsets.UTF_8);
            assertEquals(name + " (whole message)", expected, whole);
            for (int chunk : CHUNK_SIZES) {
                assertEquals(name + " (chunk " + chunk + ")", expected, parse(message, chunk));
            }
        }
    }
}
