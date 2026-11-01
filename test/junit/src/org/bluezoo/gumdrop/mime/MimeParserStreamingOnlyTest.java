/*
 * MimeParserStreamingOnlyTest.java
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
import static org.junit.Assert.assertTrue;

/**
 * A handler that does not want the older whole-header events gets the
 * streaming ones, and the parser holds no Content-Type or
 * Content-Disposition for it, though it still finds the multipart boundary.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@SuppressWarnings("removal") // exercises the older events, which are deprecated for removal
public class MimeParserStreamingOnlyTest {

    static class Log implements MimeHandler {
        final List<String> events = new ArrayList<String>();
        boolean legacy;

        @Override
        public boolean wantsLegacyEvents() {
            return legacy;
        }

        @Override
        public void setLocator(MimeLocator locator) {
        }

        @Override
        public void startEntity(String boundary) {
            events.add("startEntity " + boundary);
        }

        @Override
        public void startContentType(String primary, String sub) {
            events.add("startContentType " + primary + "/" + sub);
        }

        @Override
        public void startParameter(String name, String language) {
            events.add("parameter " + name);
        }

        @Override
        public void contentType(ContentType contentType) {
            events.add("LEGACY contentType");
        }

        @Override
        public void contentDisposition(ContentDisposition contentDisposition) {
            events.add("LEGACY contentDisposition");
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
            events.add("body");
        }

        @Override
        public void unexpectedContent(ByteBuffer data) {
        }

        @Override
        public void endEntity(String boundary) {
            events.add("endEntity " + boundary);
        }
    }

    private static Log run(String message, boolean legacy) throws MimeParseException {
        Log log = new Log();
        log.legacy = legacy;
        MimeParser parser = new MimeParser();
        parser.setHandler(log);
        ByteBuffer buffer = ByteBuffer.allocate(message.length() + 16);
        // a byte at a time
        byte[] bytes = message.getBytes(StandardCharsets.ISO_8859_1);
        for (byte b : bytes) {
            buffer.put(b);
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
        parser.close();
        return log;
    }

    private static final String MULTIPART = "Content-Type: multipart/mixed;\r\n boundary=\"BB\"\r\n\r\n"
        + "--BB\r\nContent-Type: text/plain\r\n\r\nhello\r\n--BB--\r\n";

    @Test
    public void theBoundaryIsFoundWithoutTheObject() throws MimeParseException {
        Log log = run(MULTIPART, false);
        assertTrue(log.events.toString(), log.events.contains("startEntity BB"));
        assertTrue(log.events.toString(), log.events.contains("body"));
        assertTrue(log.events.toString(), log.events.contains("startContentType multipart/mixed"));
        assertTrue(log.events.toString(), !log.events.contains("LEGACY contentType"));
    }

    @Test
    public void theOlderEventsStillComeWhenWanted() throws MimeParseException {
        Log log = run(MULTIPART, true);
        assertTrue(log.events.toString(), log.events.contains("startEntity BB"));
        assertTrue(log.events.toString(), log.events.contains("LEGACY contentType"));
    }

    @Test
    public void anExtendedBoundaryReplacesAPlainOne() throws MimeParseException {
        String message = "Content-Type: multipart/mixed; boundary=\"plain\"; boundary*=us-ascii''ext\r\n\r\n"
            + "--ext\r\n\r\nx\r\n--ext--\r\n";
        assertEquals(run(message, true).events.contains("startEntity ext"),
            run(message, false).events.contains("startEntity ext"));
        assertTrue(run(message, false).events.contains("startEntity ext"));
    }

    @Test
    public void aParameterOverTheHeaderValueLimitIsNotHeldSoNeedNotBeRefused() throws MimeParseException {
        StringBuilder message = new StringBuilder("Content-Type: text/plain; x=\"");
        for (int i = 0; i < 100; i++) {
            message.append('a');
        }
        message.append("\"\r\n\r\nbody\r\n");
        Log log = new Log();
        MimeParser parser = new MimeParser();
        parser.setMaxHeaderValueSize(50);
        parser.setHandler(log);
        parser.receive(ByteBuffer.wrap(message.toString().getBytes(StandardCharsets.ISO_8859_1)));
        parser.close();
        assertTrue(log.events.toString(), log.events.contains("parameter x"));
    }

}
