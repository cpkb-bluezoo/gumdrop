/*
 * MimeParserParseStreamTest.java
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * {@link MimeParser#parse(InputStream)} gives the same events as feeding the
 * push API, whatever the stream gives at a time.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MimeParserParseStreamTest {

    private static final String MESSAGE = "Content-Type: multipart/mixed; boundary=BB\r\n"
        + "Subject: s\r\n\r\n--BB\r\nContent-Type: text/plain\r\n\r\nhello\r\n--BB--";

    private static InputStream trickle(final byte[] data, final int step) {
        return new InputStream() {
            private int at;

            @Override
            public int read() {
                return at < data.length ? data[at++] & 0xff : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (at >= data.length) {
                    return -1;
                }
                int n = Math.min(Math.min(step, len), data.length - at);
                System.arraycopy(data, at, b, off, n);
                at += n;
                return n;
            }
        };
    }

    private static List<String> parse(InputStream in) throws Exception {
        MimeParserCloseTest.Log log = new MimeParserCloseTest.Log();
        MimeParser parser = new MimeParser();
        parser.setHandler(log);
        parser.parse(in);
        // body content arrives in pieces of no particular size
        List<String> merged = new ArrayList<String>();
        for (String e : log.events) {
            int last = merged.size() - 1;
            if (e.startsWith("body ") && last >= 0 && merged.get(last).startsWith("body ")) {
                merged.set(last, merged.get(last) + e.substring(5));
            } else {
                merged.add(e);
            }
        }
        return merged;
    }

    @Test
    public void sameEventsWhateverTheStreamGivesAtATime() throws Exception {
        byte[] bytes = MESSAGE.getBytes(StandardCharsets.ISO_8859_1);
        List<String> whole = parse(new ByteArrayInputStream(bytes));
        for (int step = 1; step <= 9; step++) {
            assertEquals("step " + step, whole, parse(trickle(bytes, step)));
        }
        assertEquals("endEntity null", whole.get(whole.size() - 1));
    }

    @Test
    public void aHeaderWithoutALineEndingAtTheEndIsRead() throws Exception {
        List<String> events = parse(trickle("Subject: last".getBytes(StandardCharsets.ISO_8859_1), 2));
        assertEquals(true, events.contains("value last"));
    }

    @Test
    public void aHeaderLineLongerThanTheBufferIsRefused() throws Exception {
        StringBuilder sb = new StringBuilder("X: ");
        for (int i = 0; i < 20000; i++) {
            sb.append('a');
        }
        sb.append("\r\n\r\n");
        MimeParser parser = new MimeParser();
        parser.setLimits(ParserLimits.unlimited());
        parser.setHandler(new MimeParserCloseTest.Log());
        try {
            parser.parse(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.ISO_8859_1)));
            fail("line too long");
        } catch (IOException | MimeParseException expected) {
            // the line cannot be held
        }
    }

}
