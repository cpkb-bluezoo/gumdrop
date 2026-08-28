/*
 * StreamWellKnownHeaderValuesTest.java
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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Verifies that {@code Stream.sendResponseHeaders} adds its framework-fixed
 * headers (Server, Connection: close, X-Frame-Options, X-Content-Type-Options,
 * Transfer-Encoding: chunked) using the exact same object references as
 * {@link HTTPProtocolHandler}'s {@code *_VALUE} constants.
 *
 * <p>This is the one thing {@link HTTPProtocolHandlerHeaderWriteTest}'s
 * byte-output checks cannot catch: those construct headers directly from
 * the constants, so they prove the write side is correct but cannot notice
 * if {@code Stream.java} ever drifted back to building an equal-looking but
 * differently-sourced value (a literal in a different class, a
 * concatenation, {@code new String(...)}, etc.). That would not break any
 * response - {@code writeWellKnownLine}'s reference check would just stop
 * matching and every response would silently fall back to the slower
 * generic per-character path - so nothing else would catch the regression.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StreamWellKnownHeaderValuesTest {

    @Test
    public void testFrameworkHeaderValuesAreTheSharedConstants() throws Exception {
        HTTPListener listener = new HTTPListener();
        listener.setAddSecurityHeaders(true);
        listener.setHandlerFactory((state, headers) -> new DefaultHTTPRequestHandler());

        HTTPProtocolHandler connection = new HTTPProtocolHandler(listener);
        connection.version = HTTPVersion.HTTP_1_1;

        Stream stream = new Stream(connection, 1);
        stream.addHeader(new Header(":method", "GET"));
        stream.streamEndHeaders();
        // Forces the Connection: close branch too, alongside Server, Date,
        // the security headers, and (no explicit Content-Length, a body
        // follows) Transfer-Encoding: chunked - all five in one exchange.
        stream.closeConnection = true;

        Headers responseHeaders = new Headers();
        responseHeaders.status(HTTPStatus.OK);
        stream.sendResponseHeaders(200, responseHeaders, false);

        assertSame("Server header value must be the shared constant "
                + "writeWellKnownLine matches against, not just an equal string",
                HTTPProtocolHandler.SERVER_HEADER_VALUE,
                responseHeaders.getValue("Server"));
        assertSame("Connection header value must be the shared constant",
                HTTPProtocolHandler.CONNECTION_CLOSE_VALUE,
                responseHeaders.getValue("Connection"));
        assertSame("X-Frame-Options header value must be the shared constant",
                HTTPProtocolHandler.X_FRAME_OPTIONS_VALUE,
                responseHeaders.getValue("X-Frame-Options"));
        assertSame("X-Content-Type-Options header value must be the shared constant",
                HTTPProtocolHandler.X_CONTENT_TYPE_OPTIONS_VALUE,
                responseHeaders.getValue("X-Content-Type-Options"));
        assertSame("Transfer-Encoding header value must be the shared constant",
                HTTPProtocolHandler.TRANSFER_ENCODING_CHUNKED_VALUE,
                responseHeaders.getValue("Transfer-Encoding"));
        assertSame("Date header value must be HTTPDateCache's cached instance",
                HTTPDateCache.get(),
                responseHeaders.getValue("Date"));
    }
}
