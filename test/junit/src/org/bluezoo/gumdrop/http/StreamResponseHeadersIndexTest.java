/*
 * StreamResponseHeadersIndexTest.java
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
 * Regression test for issue #278: {@code Stream.sendResponseHeaders}
 * interleaves {@code Headers.add()} calls with {@code containsName()}
 * lookups - add Server/Date, then (when the listener has security headers
 * enabled, the default) check-and-maybe-add X-Frame-Options, then
 * check-and-maybe-add X-Content-Type-Options, then check
 * Content-Length/Transfer-Encoding. Every {@code add()} bumps {@code
 * Headers}' inherited {@code ArrayList} {@code modCount}, which invalidates
 * {@link Headers}' lazily-built name index (see its javadoc) right before
 * the next lookup needs it - so in the common case a single response
 * rebuilds that index's {@code HashMap} (plus a fresh {@code ArrayList} per
 * distinct header name) three times instead of the once its own caching is
 * designed for.
 *
 * <p>This drives a real {@link HTTPProtocolHandler} (not just a minimal
 * {@link HTTPConnectionLike} stub, as in {@code
 * StreamContentLengthValidationTest}) because the security-headers checks
 * that cause the repeated rebuilds are gated on {@code instanceof
 * HTTPProtocolHandler} plus the listener's {@code getAddSecurityHeaders()} -
 * a lighter stub would skip exactly the code path under test. Asserts the
 * rebuild count directly via {@link Headers#indexBuildCountForTesting}, since
 * the interleaving is a pure efficiency defect: the response headers sent
 * are identical either way, so nothing externally observable (status code,
 * header values) would catch a regression here.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StreamResponseHeadersIndexTest {

    @Test
    public void testHeadersIndexNotRebuiltPerContainsNameCall() throws Exception {
        HTTPListener listener = new HTTPListener();
        // Default true, but explicit so this test keeps exercising the
        // X-Frame-Options/X-Content-Type-Options branch even if that
        // default ever changes.
        listener.setAddSecurityHeaders(true);
        listener.setHandlerFactory((state, headers) -> new DefaultHTTPRequestHandler());

        HTTPProtocolHandler connection = new HTTPProtocolHandler(listener);
        connection.version = HTTPVersion.HTTP_1_1;

        Stream stream = new Stream(connection, 1);
        stream.addHeader(new Header(":method", "GET"));
        // No handler.headers() call happens for a no-op handler, so
        // sendResponseHeaders is not auto-invoked here - the test drives
        // it directly below to isolate exactly one call's behaviour.
        stream.streamEndHeaders();

        Headers responseHeaders = new Headers();
        responseHeaders.status(HTTPStatus.OK);
        responseHeaders.add("content-type", "text/plain");

        stream.sendResponseHeaders(200, responseHeaders, true);

        assertEquals("Headers.index() should be built once for a whole "
                + "sendResponseHeaders call, not rebuilt before every "
                + "containsName() check",
                1, responseHeaders.indexBuildCountForTesting);
    }
}
