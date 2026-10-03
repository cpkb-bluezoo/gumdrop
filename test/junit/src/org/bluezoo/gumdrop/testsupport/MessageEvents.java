/*
 * MessageEvents.java
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

package org.bluezoo.gumdrop.testsupport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.http.FieldSectionAdapter;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpError;
import org.bluezoo.gumdrop.http.HttpMessageHandler;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.h1.Http1Parser;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.http.HttpVersion;

/**
 * Delivers a request, given as {@link Headers} with pseudo-headers, to an
 * {@link HttpMessageHandler} as the events the server sends: the same path
 * HTTP/2 and HTTP/3 requests take.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MessageEvents {

    private MessageEvents() {
    }

    /**
     * Delivers the header section as events, ending with {@code endHeaders}.
     * A request that names {@code :scheme} or {@code :protocol} arrives as
     * HTTP/3 would send it; any other is sent as an HTTP/1.1 request.
     */
    public static void headers(HttpMessageHandler handler, Headers headers) {
        if (headers.getValue(":scheme") != null || headers.getValue(":protocol") != null) {
            headers(handler, HttpVersion.HTTP_3, headers);
        } else {
            http1(handler, headers);
        }
    }

    /** As {@link #headers(HttpMessageHandler, Headers)} over HTTP/2 or HTTP/3. */
    public static void headers(HttpMessageHandler handler, HttpVersion version, Headers headers) {
        FieldSectionAdapter adapter = new FieldSectionAdapter(handler, version,
                FieldSectionAdapter.Kind.REQUEST);
        // a test request need not spell out every pseudo-header a real one
        // carries: supply the ones the protocol requires and it left out
        boolean extendedOrOrdinary = !"CONNECT".equals(headers.getValue(":method"))
                || headers.getValue(":protocol") != null;
        if (extendedOrOrdinary) {
            for (Header header : headers) {
                if (header.getName().startsWith(":")) {
                    adapter.field(octets(header.getName()), octets(header.getValue()));
                }
            }
            if (headers.getValue(":scheme") == null) {
                adapter.field(octets(":scheme"), octets("https"));
            }
            if (headers.getValue(":path") == null) {
                adapter.field(octets(":path"), octets("/"));
            }
            if (headers.getValue(":authority") == null) {
                adapter.field(octets(":authority"), octets("test.invalid"));
            }
            for (Header header : headers) {
                if (!header.getName().startsWith(":")) {
                    adapter.field(octets(header.getName()), octets(header.getValue()));
                }
            }
        } else {
            for (Header header : headers) {
                adapter.field(octets(header.getName()), octets(header.getValue()));
            }
        }
        adapter.finish();
    }

    /** Delivers the request as the bytes of an HTTP/1.x request, up to its header section. */
    private static void http1(HttpMessageHandler handler, Headers headers) {
        String method = headers.getValue(":method");
        String path = headers.getValue(":path");
        StringBuilder sb = new StringBuilder();
        sb.append(method == null ? "GET" : method).append(' ')
          .append(path == null ? "/" : path);
        String authority = headers.getValue(":authority");
        boolean hasHost = headers.getValue("host") != null;
        // HTTP/1.1 requires a Host field; a request that names no host is
        // sent as HTTP/1.0, which does not
        if (authority == null && !hasHost) {
            sb.append(" HTTP/1.0\r\n");
        } else {
            sb.append(" HTTP/1.1\r\n");
            if (!hasHost) {
                sb.append("Host: ").append(authority).append("\r\n");
            }
        }
        for (Header header : headers) {
            if (!header.getName().startsWith(":")) {
                sb.append(header.getName()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        sb.append("\r\n");
        ByteBuffer wire = octets(sb.toString());
        Http1Parser.forRequests(new WithoutEnd(handler)).receive(wire);
    }

    /** Passes everything on but the end of the message, which a bodyless request would otherwise send. */
    private static final class WithoutEnd implements HttpMessageHandler {
        private final HttpMessageHandler h;
        WithoutEnd(HttpMessageHandler h) { this.h = h; }
        @Override public void method(HttpMethod m) { h.method(m); }
        @Override public void target(ByteBuffer t) { h.target(t); }
        @Override public void scheme(ByteBuffer v) { h.scheme(v); }
        @Override public void authority(ByteBuffer v) { h.authority(v); }
        @Override public void protocol(ByteBuffer v) { h.protocol(v); }
        @Override public void version(HttpVersion v) { h.version(v); }
        @Override public void status(int c) { h.status(c); }
        @Override public void reason(ByteBuffer v) { h.reason(v); }
        @Override public void contentType(ContentType v) { h.contentType(v); }
        @Override public void contentDisposition(ContentDisposition v) { h.contentDisposition(v); }
        @Override public void longHeader(String n, long v) { h.longHeader(n, v); }
        @Override public void dateHeader(String n, java.time.Instant v) { h.dateHeader(n, v); }
        @Override public void header(String n, ByteBuffer v) { h.header(n, v); }
        @Override public void endHeaders() { h.endHeaders(); }
        @Override public void bodyContent(ByteBuffer d) { h.bodyContent(d); }
        @Override public void endMessage() { }
        @Override public void error(HttpError e, String d) { h.error(e, d); }
        @Override public void failed(Exception c) { h.failed(c); }
    }

    /** Delivers a whole request: header section, the body if any, and the end. */
    public static void request(HttpMessageHandler handler, Headers headers, byte[] body) {
        headers(handler, headers);
        if (body != null && body.length > 0) {
            handler.bodyContent(ByteBuffer.wrap(body));
        }
        handler.endMessage();
    }

    public static ByteBuffer octets(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
