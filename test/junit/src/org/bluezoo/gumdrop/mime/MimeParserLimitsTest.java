/*
 * MimeParserLimitsTest.java
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
import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.Locale;
import java.util.ResourceBundle;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the limits that bound what {@link MimeParser} holds while it waits
 * for the rest of a header line.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MimeParserLimitsTest {

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static ByteBuffer buffer(String text, int capacity) {
        byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(capacity);
        buf.put(data);
        buf.flip();
        return buf;
    }

    private static MimeParser parser() {
        MimeParser parser = new MimeParser();
        parser.setHandler(new MimeParserBranchesTest.Rec());
        return parser;
    }

    @Test
    public void defaultLimits() {
        ParserLimits limits = new ParserLimits();
        assertEquals(998, limits.getMaxHeaderLineLength());
        assertEquals(1024, limits.getMaxHeaderNameLength());
    }

    @Test
    public void unlimitedHasNoLimits() {
        ParserLimits limits = ParserLimits.unlimited();
        assertTrue(limits.getMaxHeaderLineLength() <= 0);
        assertTrue(limits.getMaxHeaderNameLength() <= 0);
    }

    @Test
    public void limitsAreLive() {
        MimeParser parser = parser();
        parser.getLimits().setMaxHeaderLineLength(10);
        assertEquals(10, parser.getLimits().getMaxHeaderLineLength());
        ParserLimits replacement = new ParserLimits();
        parser.setLimits(replacement);
        assertEquals(998, parser.getLimits().getMaxHeaderLineLength());
    }

    @Test
    public void unterminatedHeaderLineOverTheLimitFailsBeforeItEnds() throws Exception {
        MimeParser parser = parser();
        ByteBuffer buf = buffer("Subject: " + repeat('x', 1100), 4096);
        try {
            parser.receive(buf);
            fail("a header line already longer than the limit must not be awaited");
        } catch (HeaderLineTooLongException expected) {
            // the whole line need never be held
        }
    }

    @Test
    public void unterminatedHeaderLineAtTheLimitIsAwaited() throws Exception {
        MimeParser parser = parser();
        // 998 characters, then the CR of a line ending: still legal
        ByteBuffer buf = buffer("Subject: " + repeat('x', 989) + "\r", 4096);
        parser.receive(buf);
        assertTrue(parser.isUnderflow());
        assertEquals(0, buf.position());
    }

    @Test
    public void headerLineLimitIsConfigurable() throws Exception {
        MimeParser parser = parser();
        parser.getLimits().setMaxHeaderLineLength(20);
        try {
            parser.receive(buffer("Subject: " + repeat('x', 30) + "\r\n\r\n", 4096));
            fail("a line over the configured limit must be rejected");
        } catch (HeaderLineTooLongException expected) {
            // ok
        }
    }

    @Test
    public void longerHeaderLineAcceptedWhenLimitRaised() throws Exception {
        MimeParser parser = parser();
        parser.getLimits().setMaxHeaderLineLength(5000);
        parser.receive(buffer("Subject: " + repeat('x', 2000) + "\r\n\r\n", 4096));
        parser.close();
    }

    @Test
    public void unlimitedAcceptsALongHeaderLine() throws Exception {
        MimeParser parser = parser();
        parser.setLimits(ParserLimits.unlimited());
        parser.receive(buffer("Subject: " + repeat('x', 3000) + "\r\n\r\n", 8192));
        parser.close();
    }

    @Test
    public void headerNameOverTheLimitIsRejected() throws Exception {
        MimeParser parser = parser();
        parser.getLimits().setMaxHeaderLineLength(0);
        try {
            parser.receive(buffer(repeat('N', 1025) + ": v\r\n\r\n", 4096));
            fail("a header name over the limit must be rejected");
        } catch (MimeParseException expected) {
            assertFalse(expected instanceof HeaderLineTooLongException);
        }
    }

    @Test
    public void headerNameAtTheLimitIsAccepted() throws Exception {
        MimeParser parser = parser();
        parser.getLimits().setMaxHeaderLineLength(0);
        parser.receive(buffer(repeat('N', 1024) + ": v\r\n\r\n", 4096));
        parser.close();
    }

    @Test
    public void incompleteHeaderLineIsAwaitedWhateverTheBufferSize() throws Exception {
        MimeParser parser = parser();
        ByteBuffer buf = buffer("Subject: abc", 64);
        parser.receive(buf);
        assertTrue(parser.isUnderflow());
        assertEquals(0, buf.position());
    }

    @Test
    public void headerLineSplitAtEveryPositionStillParses() throws Exception {
        String text = "Subject: " + repeat('x', 900) + "\r\nTo: a@example.com\r\n\r\nbody\r\n";
        byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
        for (int chunk = 1; chunk <= 1100; chunk += 37) {
            MimeParser parser = new MimeParser();
            MimeParserBranchesTest.Rec rec = new MimeParserBranchesTest.Rec();
            parser.setHandler(rec);
            ByteBuffer buf = ByteBuffer.allocate(2048);
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
            assertEquals("chunk " + chunk, "body\r\n", rec.body.toString());
        }
    }

    @Test
    public void frenchMessagesWithArgumentsAreFormatted() {
        // A single apostrophe in a MessageFormat pattern quotes the text
        // after it, including any {0}, so it has to be doubled
        ResourceBundle fr = ResourceBundle.getBundle(
            "org.bluezoo.gumdrop.mime.L10N", Locale.FRENCH);
        Enumeration<String> keys = fr.getKeys();
        while (keys.hasMoreElements()) {
            String key = keys.nextElement();
            String pattern = fr.getString(key);
            if (pattern.contains("{0}")) {
                String text = MessageFormat.format(pattern, "ARG");
                assertTrue(key + ": " + text, text.contains("ARG"));
            }
        }
    }
}
