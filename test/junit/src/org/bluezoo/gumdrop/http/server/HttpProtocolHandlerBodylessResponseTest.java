/*
 * HttpProtocolHandlerBodylessResponseTest.java
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

import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.junit.Test;

/**
 * An HTTP/1.1 response that ends without a body must still delimit itself:
 * with neither Content-Length nor Transfer-Encoding a client can only treat
 * everything up to connection close as the body (RFC 9112 section 6.3), so a
 * keep-alive connection would hang until timeout.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpProtocolHandlerBodylessResponseTest {

    private String exchange(final String status, final boolean startBody) {
        Http2Listener listener = new Http2Listener();
        listener.setStreamHandler(new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponseState state) {
                return new DefaultHttpRequestHandler() {
                    @Override
                    public void headers(HttpResponseState s, Headers headers) {
                        Headers response = new Headers();
                        response.add(":status", status);
                        s.headers(response);
                        if (startBody) {
                            s.startResponseBody();
                            s.endResponseBody();
                        }
                        s.complete();
                    }
                };
            }
        });
        HttpProtocolHandler connection = new HttpProtocolHandler(listener);
        BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        connection.connected(endpoint);
        String request = "GET /api HTTP/1.1\r\nHost: example.test\r\n\r\n";
        connection.receive(ByteBuffer.wrap(request.getBytes(StandardCharsets.ISO_8859_1)));
        String wire = new String(endpoint.getAllBytes(), StandardCharsets.ISO_8859_1);
        return wire;
    }

    private static void assertDelimited(String wire) {
        String lower = wire.toLowerCase();
        if (lower.contains("transfer-encoding: chunked")) {
            assertTrue("chunked response must end with the last-chunk: " + wire,
                    wire.endsWith("0\r\n\r\n"));
        } else {
            assertTrue("response must be length-delimited: " + wire,
                    lower.contains("content-length:") || lower.contains("connection: close"));
        }
    }

    @Test
    public void okWithoutBodyIsDelimited() {
        String wire = exchange("200", false);
        assertTrue(wire, wire.startsWith("HTTP/1.1 200"));
        assertDelimited(wire);
    }

    @Test
    public void okWithEmptyBodyIsDelimited() {
        assertDelimited(exchange("200", true));
    }

    @Test
    public void notFoundWithoutBodyIsDelimited() {
        assertDelimited(exchange("404", false));
    }

    @Test
    public void secondBodylessResponseOnKeepAliveConnectionIsDelimited() {
        Http2Listener listener = new Http2Listener();
        listener.setStreamHandler(new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponseState state) {
                return new DefaultHttpRequestHandler() {
                    @Override
                    public void headers(HttpResponseState s, Headers headers) {
                        Headers response = new Headers();
                        response.add(":status", "200");
                        s.headers(response);
                        s.complete();
                    }
                };
            }
        });
        HttpProtocolHandler connection = new HttpProtocolHandler(listener);
        BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        connection.connected(endpoint);
        String request = "GET /a HTTP/1.1\r\nHost: example.test\r\n\r\n";
        connection.receive(ByteBuffer.wrap(request.getBytes(StandardCharsets.ISO_8859_1)));
        endpoint.clearWrites();
        request = "GET /b HTTP/1.1\r\nHost: example.test\r\n\r\n";
        connection.receive(ByteBuffer.wrap(request.getBytes(StandardCharsets.ISO_8859_1)));
        String wire = new String(endpoint.getAllBytes(), StandardCharsets.ISO_8859_1);
        System.err.println("SECOND:\n" + wire.replace("\r", "<CR>"));
        assertTrue(wire, wire.startsWith("HTTP/1.1 200"));
        assertDelimited(wire);
    }
}
