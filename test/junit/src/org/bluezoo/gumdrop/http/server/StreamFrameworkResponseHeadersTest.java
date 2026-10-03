/*
 * StreamFrameworkResponseHeadersTest.java
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

import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HeaderFields;
import org.bluezoo.gumdrop.http.HttpVersion;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Regression test for issue #278: {@code Stream.sendResponseHeaders} takes
 * a one-pass snapshot of which framework-managed headers the application
 * already set (X-Frame-Options, X-Content-Type-Options, Content-Length,
 * Transfer-Encoding) before adding its own Server, Date and security
 * headers. This pins the observable behaviour of that snapshot: a header
 * the application set is neither duplicated nor overridden, and a header it
 * did not set is added exactly once.
 *
 * <p>This drives a real {@link HttpProtocolHandler} (not just a minimal
 * {@link HttpConnectionLike} stub) because the security-headers checks are
 * gated on {@code instanceof HttpProtocolHandler} plus the listener's
 * {@code getAddSecurityHeaders()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StreamFrameworkResponseHeadersTest {

    private static Stream openStream() throws Exception {
        Http2Listener listener = new Http2Listener();
        // Default true, but explicit so this test keeps exercising the
        // X-Frame-Options/X-Content-Type-Options branch even if that
        // default ever changes.
        listener.setAddSecurityHeaders(true);
        listener.setStreamHandler(new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponse state) {
                return new DefaultHttpRequestHandler();
            }
        });

        HttpProtocolHandler connection = new HttpProtocolHandler(listener);
        connection.version = HttpVersion.HTTP_1_1;

        Stream stream = new Stream(connection, 1);
        stream.addHeader(new Header(":method", "GET"));
        // No handler.headers() call happens for a no-op handler, so
        // sendResponseHeaders is not auto-invoked here - the tests drive
        // it directly to isolate exactly one call's behaviour.
        stream.streamEndHeaders();
        return stream;
    }

    @Test
    public void testApplicationSecurityHeadersAreKept() throws Exception {
        Stream stream = openStream();
        List<Header> responseHeaders = new ArrayList<Header>();
        responseHeaders.add(new Header(":status", "200"));
        responseHeaders.add(new Header("content-type", "text/plain"));
        responseHeaders.add(new Header("x-frame-options", "SAMEORIGIN"));
        responseHeaders.add(new Header("X-Content-Type-Options", "custom"));

        stream.sendResponseHeaders(200, responseHeaders, true);

        List<String> frame = HeaderFields.getValues(responseHeaders, "X-Frame-Options");
        assertEquals("application X-Frame-Options must not be duplicated", 1, frame.size());
        assertEquals("SAMEORIGIN", frame.get(0));
        List<String> sniff = HeaderFields.getValues(responseHeaders, "X-Content-Type-Options");
        assertEquals("application X-Content-Type-Options must not be duplicated", 1, sniff.size());
        assertEquals("custom", sniff.get(0));
    }

    @Test
    public void testDefaultSecurityHeadersAddedOnce() throws Exception {
        Stream stream = openStream();
        List<Header> responseHeaders = new ArrayList<Header>();
        responseHeaders.add(new Header(":status", "200"));
        responseHeaders.add(new Header("content-type", "text/plain"));

        stream.sendResponseHeaders(200, responseHeaders, true);

        assertEquals(1, HeaderFields.getValues(responseHeaders, "X-Frame-Options").size());
        assertEquals(1, HeaderFields.getValues(responseHeaders, "X-Content-Type-Options").size());
        assertEquals(1, HeaderFields.getValues(responseHeaders, "Server").size());
        assertEquals(1, HeaderFields.getValues(responseHeaders, "Date").size());
    }

    @Test
    public void testApplicationContentLengthIsKept() throws Exception {
        Stream stream = openStream();
        List<Header> responseHeaders = new ArrayList<Header>();
        responseHeaders.add(new Header(":status", "200"));
        responseHeaders.add(new Header("Content-Length", "5"));

        stream.sendResponseHeaders(200, responseHeaders, false);

        assertEquals("5", HeaderFields.getValue(responseHeaders, "content-length"));
        assertEquals(1, HeaderFields.getValues(responseHeaders, "Content-Length").size());
        assertFalse("no chunked framing when Content-Length is set",
                HeaderFields.containsName(responseHeaders, "Transfer-Encoding"));
    }
}
