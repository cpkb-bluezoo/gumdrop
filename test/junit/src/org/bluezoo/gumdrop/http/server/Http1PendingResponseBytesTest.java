/*
 * Http1PendingResponseBytesTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.CollectingRequestHandler;
import org.junit.Test;

/**
 * On HTTP/1.1 there is no flow control window: response body data that
 * has not been sent sits in the connection's outbound buffer. {@link
 * HttpResponse#pendingResponseBytes()} has to report it, or a handler
 * that honours the method's contract can never tell that the peer is
 * slow and fills the buffer until the connection is closed.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Http1PendingResponseBytesTest {

    @Test
    public void reportsUnsentBytesHeldByTheConnection() {
        final AtomicReference<HttpResponse> opened = new AtomicReference<HttpResponse>();
        Http2Listener listener = new Http2Listener();
        listener.setStreamHandler(new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponse state) {
                opened.set(state);
                return new CollectingRequestHandler(state);
            }
        });
        HttpProtocolHandler connection = new HttpProtocolHandler(listener);
        BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        connection.connected(endpoint);
        String request = "GET /api HTTP/1.1\r\nHost: example.test\r\n\r\n";
        byte[] octets = request.getBytes(StandardCharsets.ISO_8859_1);
        connection.receive(ByteBuffer.wrap(octets));
        HttpResponse response = opened.get();
        assertNotNull(response);

        assertEquals(0, response.pendingResponseBytes());
        endpoint.setPendingWriteBytes(123456);
        assertEquals(123456, response.pendingResponseBytes());
        endpoint.setPendingWriteBytes(0);
        assertEquals(0, response.pendingResponseBytes());
    }
}
