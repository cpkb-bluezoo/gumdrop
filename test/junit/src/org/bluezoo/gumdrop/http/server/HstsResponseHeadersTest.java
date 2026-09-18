/*
 * HstsResponseHeadersTest.java
 * Copyright (C) 2026 Chris Burdess
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
