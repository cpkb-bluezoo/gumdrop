/*
 * HstsResponseHeadersTest.java
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

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RFC 6797: HSTS is emitted on TLS responses only when configured.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HstsResponseHeadersTest {

    @Test
    public void testHstsOnSecureListenerWhenEnabled() throws Exception {
        Http2Listener listener = new Http2Listener().secure(true);
        listener.setHstsPolicy(HstsPolicy.enabled(3600).includeSubDomains(true));
        listener.setAddSecurityHeaders(false);
        listener.setStreamHandler(new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponseState state) {
                return new DefaultHttpRequestHandler();
            }
        });

        HttpProtocolHandler connection = new HttpProtocolHandler(listener);
        connection.version = HttpVersion.HTTP_1_1;

        Stream stream = new Stream(connection, 1);
        stream.addHeader(new Header(":method", "GET"));
        stream.streamEndHeaders();

        Headers responseHeaders = new Headers();
        responseHeaders.status(HttpStatus.OK);
        stream.sendResponseHeaders(200, responseHeaders, true);

        assertEquals("max-age=3600; includeSubDomains",
                responseHeaders.getValue("Strict-Transport-Security"));
    }

    @Test
    public void testNoHstsOnPlaintextEvenWhenConfigured() throws Exception {
        Http2Listener listener = new Http2Listener().secure(false);
        listener.setHstsPolicy(HstsPolicy.enabled(3600));
        listener.setAddSecurityHeaders(false);
        listener.setStreamHandler(new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponseState state) {
                return new DefaultHttpRequestHandler();
            }
        });

        HttpProtocolHandler connection = new HttpProtocolHandler(listener);
        connection.version = HttpVersion.HTTP_1_1;

        Stream stream = new Stream(connection, 1);
        stream.addHeader(new Header(":method", "GET"));
        stream.streamEndHeaders();

        Headers responseHeaders = new Headers();
        responseHeaders.status(HttpStatus.OK);
        stream.sendResponseHeaders(200, responseHeaders, true);

        assertNull(responseHeaders.getValue("Strict-Transport-Security"));
    }
}
