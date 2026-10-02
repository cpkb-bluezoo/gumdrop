/*
 * HandlerlessPostConnectionCloseTest.java
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
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.junit.Test;

/**
 * A listener without an application handler answers every request with a
 * default 404 before the request body has arrived. The discarded body must
 * still be counted so the request completes and a {@code Connection: close}
 * request is closed (RFC 9112 section 9.6) instead of hanging.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HandlerlessPostConnectionCloseTest {

    private static String run(BinaryRecordingEndpoint endpoint, String request) {
        Http2Listener listener = new Http2Listener();
        HttpProtocolHandler handler = new HttpProtocolHandler(listener);
        handler.connected(endpoint);
        ByteBuffer in = ByteBuffer.wrap(request.getBytes(StandardCharsets.ISO_8859_1));
        handler.receive(in);
        return new String(endpoint.getAllBytes(), StandardCharsets.ISO_8859_1);
    }

    @Test
    public void postWithBodyAndConnectionCloseIsClosedAfter404() {
        BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        String wire = run(endpoint, "POST /submit HTTP/1.1\r\nHost: h\r\nConnection: close\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: 19\r\n\r\n"
                + "test=data&key=value");
        assertTrue(wire, wire.startsWith("HTTP/1.1 404"));
        assertEquals(wire, 1, endpoint.getCloseCount());
    }

    @Test
    public void getWithConnectionCloseIsClosedAfter404() {
        BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        String wire = run(endpoint, "GET /x HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
        assertTrue(wire, wire.startsWith("HTTP/1.1 404"));
        assertEquals(wire, 1, endpoint.getCloseCount());
    }
}
