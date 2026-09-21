/*
 * HttpClientProtocolHandlerHttp1ResponseTest.java
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

package org.bluezoo.gumdrop.http.client;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * HTTP/1.1 response parsing on {@link HttpClientProtocolHandler} over an
 * in-memory {@link BinaryRecordingEndpoint}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpClientProtocolHandlerHttp1ResponseTest {

    private static final class RecordingHandler extends DefaultHttpResponseHandler {
        HttpResponse response;
        boolean ok;
        boolean error;
        boolean startBody;
        boolean endBody;
        boolean closed;
        Exception failure;
        final List<String[]> headers = new ArrayList<String[]>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        @Override
        public void ok(HttpResponse response) {
            ok = true;
            this.response = response;
        }

        @Override
        public void error(HttpResponse response) {
            error = true;
            this.response = response;
        }

        @Override
        public void header(String name, String value) {
            headers.add(new String[] { name, value });
        }

        @Override
        public void startResponseBody() {
            startBody = true;
        }

        @Override
        public void responseBodyContent(ByteBuffer data) {
            if (data.hasRemaining()) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                body.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void endResponseBody() {
            endBody = true;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public void failed(Exception ex) {
            failure = ex;
        }

        @Override
        public void pushPromise(PushPromise promise) {
            promise.reject();
        }
    }

    private HttpClientProtocolHandler handler;
    private BinaryRecordingEndpoint endpoint;

    @Before
    public void setUp() {
        handler = new HttpClientProtocolHandler(null, "example.com", 80, false);
        endpoint = new BinaryRecordingEndpoint();
        handler.connected(endpoint);
    }

    private RecordingHandler sendGet() {
        RecordingHandler rh = new RecordingHandler();
        handler.get("/path").send(rh);
        return rh;
    }

    private RecordingHandler sendHead() {
        RecordingHandler rh = new RecordingHandler();
        handler.head("/path").send(rh);
        return rh;
    }

    private void feed(String raw) {
        handler.receive(ByteBuffer.wrap(raw.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void contentLengthBodyIsDelivered() {
        RecordingHandler rh = sendGet();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello");

        assertTrue(rh.ok);
        assertEquals(HttpStatus.OK, rh.response.getStatus());
        assertTrue(rh.startBody);
        assertTrue(rh.endBody);
        assertTrue(rh.closed);
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), rh.body.toByteArray());
    }

    @Test
    public void chunkedBodyIsDelivered() {
        RecordingHandler rh = sendGet();
        feed("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n0\r\n\r\n");

        assertTrue(rh.ok);
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), rh.body.toByteArray());
        assertTrue(rh.closed);
    }

    @Test
    public void headResponseHasNoBodyDespiteContentLength() {
        RecordingHandler rh = sendHead();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshould-not-read");

        assertTrue(rh.ok);
        assertFalse(rh.startBody);
        assertEquals(0, rh.body.size());
        assertTrue(rh.closed);
    }

    @Test
    public void noContentSkipsBody() {
        RecordingHandler rh = sendGet();
        feed("HTTP/1.1 204 No Content\r\n\r\n");

        assertTrue(rh.ok);
        assertFalse(rh.startBody);
        assertTrue(rh.closed);
    }

    @Test
    public void clientErrorInvokesErrorCallback() {
        RecordingHandler rh = sendGet();
        feed("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");

        assertTrue(rh.error);
        assertEquals(HttpStatus.NOT_FOUND, rh.response.getStatus());
        assertTrue(rh.closed);
    }

    @Test
    public void invalidContentLengthFailsRequest() {
        RecordingHandler rh = sendGet();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 5, 9\r\n\r\n");

        assertNotNull(rh.failure);
        assertTrue(rh.closed);
    }

    @Test
    public void missingLengthAndChunkedFailsRequest() {
        RecordingHandler rh = sendGet();
        feed("HTTP/1.1 200 OK\r\nX-Test: yes\r\n\r\nbody");

        assertNotNull(rh.failure);
        assertTrue(rh.failure.getMessage().contains("Content-Length"));
        assertTrue(rh.closed);
    }

    @Test
    public void obsFoldCombinesHeaderValue() {
        RecordingHandler rh = sendGet();
        feed("HTTP/1.1 200 OK\r\n"
                + "X-Multi: line-one\r\n"
                + " second-line\r\n"
                + "Content-Length: 0\r\n\r\n");

        assertTrue(rh.ok);
        boolean found = false;
        for (String[] pair : rh.headers) {
            if ("X-Multi".equalsIgnoreCase(pair[0])) {
                assertTrue(pair[1].contains("line-one"));
                assertTrue(pair[1].contains("second-line"));
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    public void altSvcListenerReceivesValueOnce() {
        final AtomicReference<String> seen = new AtomicReference<String>();
        handler.setAltSvcListener(new AltSvcListener() {
            @Override
            public void altSvcReceived(String value) {
                seen.set(value);
            }
        });
        RecordingHandler rh = sendGet();
        feed("HTTP/1.1 200 OK\r\n"
                + "Alt-Svc: h3=\":443\"; ma=3600\r\n"
                + "Content-Length: 0\r\n\r\n");

        assertTrue(rh.ok);
        assertEquals("h3=\":443\"; ma=3600", seen.get());
    }

    @Test
    public void connectionCloseClosesEndpoint() {
        RecordingHandler rh = sendGet();
        feed("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");

        assertTrue(rh.closed);
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void ipv6HostHeaderIsBracketedInRequest() {
        HttpClientProtocolHandler v6 =
                new HttpClientProtocolHandler(null, "::1", 8080, false);
        BinaryRecordingEndpoint ep = new BinaryRecordingEndpoint();
        v6.connected(ep);
        v6.get("/").send(new DefaultHttpResponseHandler());

        String request = new String(ep.getAllBytes(), StandardCharsets.US_ASCII);
        assertTrue(request.contains("Host: [::1]:8080"));
    }

    @Test
    public void disconnectedFailsActiveStream() {
        RecordingHandler rh = sendGet();
        handler.disconnected();
        assertNotNull(rh.failure);
        assertFalse(handler.isOpen());
    }

    @Test
    public void transportErrorFailsActiveStream() {
        RecordingHandler rh = sendGet();
        handler.error(new java.io.IOException("reset"));
        assertNotNull(rh.failure);
        assertNull(rh.response);
    }

    @Test
    public void contentLengthBodyMayArriveInMultipleFeeds() {
        RecordingHandler rh = sendGet();
        feed("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhel");
        feed("lo");

        assertTrue(rh.ok);
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), rh.body.toByteArray());
        assertTrue(rh.closed);
    }

    @Test
    public void notModifiedHasNoBody() {
        RecordingHandler rh = sendGet();
        feed("HTTP/1.1 304 Not Modified\r\nETag: \"v1\"\r\n\r\n");

        assertTrue(rh.error);
        assertEquals(HttpStatus.NOT_MODIFIED, rh.response.getStatus());
        assertFalse(rh.startBody);
        assertTrue(rh.closed);
    }

    @Test
    public void responseHeaderTooLargeFailsStream() {
        handler.setMaxResponseHeaderSize(64);
        RecordingHandler rh = sendGet();
        feed("HTTP/1.1 200 OK\r\n"
                + "X-Padding: " + "abcdefghijklmnopqrstuvwxyz0123456789\r\n"
                + "Content-Length: 0\r\n\r\n");

        assertNotNull(rh.failure);
        assertTrue(rh.failure.getMessage().contains("header"));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void chunkedPrecedenceWhenBothLengthAndTransferEncodingPresent() {
        RecordingHandler rh = sendGet();
        feed("HTTP/1.1 200 OK\r\n"
                + "Content-Length: 100\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n"
                + "3\r\n"
                + "abc\r\n"
                + "0\r\n\r\n");

        assertTrue(rh.ok);
        assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), rh.body.toByteArray());
    }
}
