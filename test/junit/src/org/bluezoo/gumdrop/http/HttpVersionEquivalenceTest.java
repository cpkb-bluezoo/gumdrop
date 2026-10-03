/*
 * HttpVersionEquivalenceTest.java
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


package org.bluezoo.gumdrop.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.http.h1.Http1Parser;
import org.bluezoo.gumdrop.testsupport.RecordingMessageHandler;
import org.junit.Test;

/**
 * The point of {@link HttpMessageHandler}: the same message, however it was
 * carried, reaches the handler as the same events. Here one request and one
 * response are sent as HTTP/1.x text, as an HPACK header block (HTTP/2) and as
 * a QPACK field section (HTTP/3), and the handler must see the same thing each
 * time, apart from what only one protocol has (the version, the HTTP/1.x
 * reason phrase, the scheme that HTTP/1.x leaves to the connection).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpVersionEquivalenceTest {

    private static final String[] NOT_COMPARED = {"version ", "scheme ", "reason "};

    /** The events minus those only some protocols have, up to and including endHeaders. */
    private static List<String> comparable(List<String> events) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < events.size(); i++) {
            String e = events.get(i);
            boolean skip = false;
            for (int j = 0; j < NOT_COMPARED.length; j++) {
                if (e.startsWith(NOT_COMPARED[j])) {
                    skip = true;
                }
            }
            if (!skip) {
                out.add(e);
            }
            if (e.equals("endHeaders")) {
                break;
            }
        }
        return out;
    }

    private static List<String> http1(boolean request, String wire) {
        RecordingMessageHandler r = new RecordingMessageHandler();
        Http1Parser p = request ? Http1Parser.forRequests(r) : Http1Parser.forResponses(r);
        p.receive(ByteBuffer.wrap(wire.getBytes(StandardCharsets.ISO_8859_1)));
        return comparable(r.events);
    }

    private static List<String> hpack(FieldSectionAdapter.Kind kind, List<Header> fields) throws Exception {
        ByteBuffer block = ByteBuffer.allocate(4096);
        new org.bluezoo.gumdrop.http.hpack.Encoder(4096, Integer.MAX_VALUE).encode(block, fields);
        block.flip();
        RecordingMessageHandler r = new RecordingMessageHandler();
        FieldSectionAdapter adapter = new FieldSectionAdapter(r, HttpVersion.HTTP_2_0, kind);
        new org.bluezoo.gumdrop.http.hpack.Decoder(4096).decode(block, adapter);
        assertTrue(adapter.finish());
        return comparable(r.events);
    }

    private static List<String> qpack(FieldSectionAdapter.Kind kind, List<Header> fields) throws Exception {
        ByteBuffer section = ByteBuffer.allocate(4096);
        new org.bluezoo.gumdrop.http.qpack.SimpleEncoder().encode(section, fields);
        section.flip();
        RecordingMessageHandler r = new RecordingMessageHandler();
        FieldSectionAdapter adapter = new FieldSectionAdapter(r, HttpVersion.HTTP_3, kind);
        new org.bluezoo.gumdrop.http.qpack.Decoder(4096).decode(0, section, adapter);
        assertTrue(adapter.finish());
        return comparable(r.events);
    }

    private static Header h(String name, String value) {
        return new Header(name, value);
    }

    @Test
    public void aRequestIsTheSameOverHttp1Http2AndHttp3() throws Exception {
        List<String> viaHttp1 = http1(true, "POST /upload?x=1 HTTP/1.1\r\nHost: example.test\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\nContent-Disposition: attachment; filename=\"a.txt\"\r\n"
                + "Content-Length: 5\r\nX-Custom: value\r\n\r\nhello");
        List<Header> fields = Arrays.asList(
                h(":method", "POST"), h(":path", "/upload?x=1"), h(":authority", "example.test"),
                h(":scheme", "https"),
                h("content-type", "text/plain; charset=utf-8"),
                h("content-disposition", "attachment; filename=\"a.txt\""),
                h("content-length", "5"), h("x-custom", "value"));

        List<String> expected = Arrays.asList("method POST", "target /upload?x=1", "authority example.test",
                "contentType text/plain charset=utf-8", "contentDisposition attachment filename=a.txt",
                "long content-length 5", "header x-custom value", "endHeaders");
        assertEquals(expected, viaHttp1);
        assertEquals(expected, hpack(FieldSectionAdapter.Kind.REQUEST, fields));
        assertEquals(expected, qpack(FieldSectionAdapter.Kind.REQUEST, fields));
    }

    @Test
    public void aResponseIsTheSameOverHttp1Http2AndHttp3() throws Exception {
        List<String> viaHttp1 = http1(false, "HTTP/1.1 404 Not Found\r\nContent-Type: text/html\r\n"
                + "Content-Length: 9\r\nX-Request-Id: abc\r\n\r\nnot found");
        List<Header> fields = Arrays.asList(
                h(":status", "404"), h("content-type", "text/html"),
                h("content-length", "9"), h("x-request-id", "abc"));

        List<String> expected = Arrays.asList("status 404", "contentType text/html charset=null",
                "long content-length 9", "header x-request-id abc", "endHeaders");
        assertEquals(expected, viaHttp1);
        assertEquals(expected, hpack(FieldSectionAdapter.Kind.RESPONSE, fields));
        assertEquals(expected, qpack(FieldSectionAdapter.Kind.RESPONSE, fields));
    }

    @Test
    public void anUnparseableTypedFieldFallsBackToHeaderInEveryProtocol() throws Exception {
        List<String> viaHttp1 = http1(true, "GET / HTTP/1.1\r\nHost: h\r\nContent-Type: (comment) text/plain\r\n\r\n");
        List<Header> fields = Arrays.asList(
                h(":method", "GET"), h(":path", "/"), h(":authority", "h"), h(":scheme", "https"),
                h("content-type", "(comment) text/plain"));

        List<String> expected = Arrays.asList("method GET", "target /", "authority h",
                "header content-type (comment) text/plain", "endHeaders");
        assertEquals(expected, viaHttp1);
        assertEquals(expected, hpack(FieldSectionAdapter.Kind.REQUEST, fields));
        assertEquals(expected, qpack(FieldSectionAdapter.Kind.REQUEST, fields));
    }
}
