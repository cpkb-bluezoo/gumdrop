/*
 * HttpProtocolHandlerHeaderValueTest.java
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

package org.bluezoo.gumdrop.http.server;

import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.http.HeaderFields;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.CollectingRequestHandler;
import org.junit.Before;
import org.junit.Test;

/**
 * HTTP/1.1 request header values must reach handlers exactly as sent, apart
 * from surrounding whitespace (RFC 9112 section 5.1). In particular the
 * double quotes of an entity-tag or a quoted-string parameter are part of
 * the value: dropping them breaks {@code If-None-Match} / {@code If-Match}
 * comparisons and changes the meaning of parameters such as a
 * {@code Content-Disposition} filename.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpProtocolHandlerHeaderValueTest {

    private List<Header> received;
    private HttpProtocolHandler connection;

    @Before
    public void setUp() {
        received = null;
        Http2Listener listener = new Http2Listener();
        listener.streamHandler(new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponse state) {
                return new CollectingRequestHandler(state) {
                    @Override
                    public void headers(HttpResponse s, List<Header> headers) {
                        received = headers;
                    }
                };
            }
        });
        connection = new HttpProtocolHandler(listener);
        connection.connected(new BinaryRecordingEndpoint());
    }

    private List<Header> requestWith(String headerLine) {
        String request = "GET / HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + headerLine + "\r\n"
                + "\r\n";
        connection.receive(ByteBuffer.wrap(
                request.getBytes(StandardCharsets.ISO_8859_1)));
        assertNotNull("handler should have received headers", received);
        return received;
    }

    @Test
    public void entityTagKeepsItsQuotes() {
        assertEquals("\"0-1ab\"",
                HeaderFields.getValue(requestWith("If-None-Match: \"0-1ab\""), "If-None-Match"));
    }

    @Test
    public void weakEntityTagKeepsItsQuotes() {
        assertEquals("W/\"v1\"",
                HeaderFields.getValue(requestWith("If-None-Match: W/\"v1\""), "If-None-Match"));
    }

    @Test
    public void entityTagListKeepsQuotesAndSeparators() {
        assertEquals("\"a\", \"b\"",
                HeaderFields.getValue(requestWith("If-Match: \"a\", \"b\""), "If-Match"));
    }

    @Test
    public void quotedParameterKeepsQuotesAndInnerSpaces() {
        assertEquals("attachment; filename=\"my  file.txt\"",
                HeaderFields.getValue(requestWith("Content-Disposition: attachment; filename=\"my  file.txt\""), "Content-Disposition"));
    }

    @Test
    public void escapedQuoteInsideQuotedStringDoesNotEndIt() {
        assertEquals("form-data; name=\"a\\\"b c\"",
                HeaderFields.getValue(requestWith("Content-Disposition: form-data; name=\"a\\\"b c\""), "Content-Disposition"));
    }

    @Test
    public void unquotedValueIsUnchanged() {
        assertEquals("text/html; charset=utf-8",
                HeaderFields.getValue(requestWith("Accept: text/html; charset=utf-8"), "Accept"));
    }

    @Test
    public void surroundingWhitespaceIsTrimmed() {
        assertEquals("\"x\"",
                HeaderFields.getValue(requestWith("If-Match:   \"x\"  "), "If-Match"));
    }
}
