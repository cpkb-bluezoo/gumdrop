/*
 * MimeParserCloseTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * What the parser does with the data the caller still holds at the end of
 * the input: it is read if it means anything, ignored if it does not, and
 * always consumed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MimeParserCloseTest {

    static class Log implements MimeHandler {
        final List<String> events = new ArrayList<String>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public boolean wantsLegacyEvents() {
            return false;
        }

        @Override
        public void setLocator(MimeLocator locator) {
        }

        @Override
        public void startEntity(String boundary) {
            events.add("startEntity " + boundary);
        }

        @Override
        public void startHeader(String name) {
            text.setLength(0);
            events.add("header " + name);
        }

        @Override
        public void headerContent(String content) {
            text.append(content);
        }

        @Override
        public void endHeader() {
            events.add("value " + text.toString().trim());
        }

        @Override
        public void contentTransferEncoding(String encoding) {
        }

        @Override
        public void contentDescription(String description) {
        }

        @Override
        public void endHeaders() {
            events.add("endHeaders");
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            events.add("body " + new String(b, StandardCharsets.ISO_8859_1).replace("\r", "\\r").replace("\n", "\\n"));
        }

        @Override
        public void unexpectedContent(ByteBuffer data) {
            events.add("unexpected");
            data.position(data.limit());
        }

        @Override
        public void endEntity(String boundary) {
            events.add("endEntity " + boundary);
        }
    }

    /** Feeds the text as the caller should: what is left stays for the next read. */
    private static Log run(String text, int step) throws MimeParseException {
        Log log = new Log();
        MimeParser parser = new MimeParser();
        parser.setHandler(log);
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(bytes.length + 16);
        for (int at = 0; at < bytes.length; at += step) {
            buf.put(bytes, at, Math.min(step, bytes.length - at));
            buf.flip();
            parser.receive(buf);
            buf.compact();
        }
        buf.flip();
        parser.close(buf);
        assertFalse("the buffer is consumed", buf.hasRemaining());
        return log;
    }

    @Test
    public void theLastHeaderLineWithoutALineEndingIsRead() throws MimeParseException {
        for (int step = 1; step <= 5; step++) {
            Log log = run("Subject: one\r\nX-Last: two", step);
            assertTrue(log.events.toString(), log.events.contains("header X-Last"));
            assertTrue(log.events.toString(), log.events.contains("value two"));
        }
    }

    @Test
    public void whiteSpaceLeftOverIsIgnored() throws MimeParseException {
        Log log = run("Subject: one\r\n  \t", 3);
        assertEquals("value one", log.events.get(log.events.indexOf("header Subject") + 1));
    }

    @Test
    public void aHeaderLineThatIsNotGivenIsStillAnError() throws MimeParseException {
        MimeParser parser = new MimeParser();
        parser.setHandler(new Log());
        ByteBuffer buf = ByteBuffer.wrap("Subject: one".getBytes(StandardCharsets.ISO_8859_1));
        parser.receive(buf);
        try {
            parser.close();
            fail("data was lost");
        } catch (MimeParseException expected) {
            // the line was left in the caller's buffer and not given back
        }
    }

    private static final String MULTIPART = "Content-Type: multipart/mixed; boundary=BB\r\n\r\n"
        + "--BB\r\nContent-Type: text/plain\r\n\r\nhello\r\n--BB";

    @Test
    public void aClosingDelimiterWithoutALineEndingIsFound() throws MimeParseException {
        for (int step : new int[] { 1, 2, 7, 1000 }) {
            Log with = run(MULTIPART + "--\r\n", step);
            Log without = run(MULTIPART + "--", step);
            assertEquals("step " + step, with.events, without.events);
            assertEquals("step " + step, "endEntity null", without.events.get(without.events.size() - 1));
        }
    }

    @Test
    public void partialTextAfterTheLastDelimiterIsIgnored() throws MimeParseException {
        for (int step : new int[] { 1, 3, 1000 }) {
            Log log = run(MULTIPART + "--\r\nepilogue with no end", step);
            assertEquals("step " + step, "endEntity null", log.events.get(log.events.size() - 1));
        }
    }

    @Test
    public void aMultipartThatIsNotClosedIsStillAnError() throws MimeParseException {
        try {
            run("Content-Type: multipart/mixed; boundary=BB\r\n\r\n--BB\r\n\r\nhello", 1000);
            fail("unclosed");
        } catch (MimeParseException expected) {
            // the closing delimiter never came
        }
    }

}
