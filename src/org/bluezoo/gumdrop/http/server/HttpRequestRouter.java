/*
 * HttpRequestRouter.java
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

import org.bluezoo.gumdrop.http.Headers;

import java.util.Set;

/**
 * Selects an {@link HttpRequestHandler} for each incoming HTTP request.
 *
 * <p>This is the Gumdrop 3 composition entry point for HTTP application
 * logic. An {@link org.bluezoo.gumdrop.http.HttpServer} built via
 * {@link org.bluezoo.gumdrop.http.HttpServer#builder()} accepts a router
 * (or a single handler wrapped by {@link HttpRequestHandlers}).
 *
 * <p>Routers replace {@link HttpRequestHandlerFactory} in application code.
 * Listeners wire {@link HttpRequestRouter} directly; legacy factories bridge
 * via {@link HttpRequestHandlers#fromFactory(HttpRequestHandlerFactory)} only
 * during migration.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HttpRequestHandlers
 * @see HttpRequestHandlerFactory
 */
public interface HttpRequestRouter {

    /**
     * Returns the handler for a new request stream.
     *
     * <p>Called when the initial headers for a stream are received. The
     * router may examine {@code :method}, {@code :path}, {@code :authority},
     * and other pseudo-headers to choose an implementation.
     *
     * <p>Returning {@code null} rejects the request: if no response was
     * sent via {@code state}, the server sends 404 Not Found.
     *
     * @param state the response state for this stream (may be used for early
     *              responses such as 401)
     * @param headers the initial request headers
     * @return a handler for this request, or {@code null} to reject
     */
    HttpRequestHandler route(HttpResponseState state, Headers headers);

    /**
     * Returns the set of HTTP methods supported by this router.
     *
     * <p>When {@code null} (the default), the connection uses the built-in
     * set of known methods (standard HTTP, HTTP/2 PRI, and WebDAV).
     *
     * @return supported method names (uppercase), or {@code null} for defaults
     */
    default Set<String> getSupportedMethods() {
        return null;
    }

}
