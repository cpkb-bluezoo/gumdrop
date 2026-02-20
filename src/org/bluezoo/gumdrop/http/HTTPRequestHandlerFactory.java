/*
 * HTTPRequestHandlerFactory.java
 * Copyright (C) 2025 Chris Burdess
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

import java.util.Set;

/**
 * Factory for creating {@link HTTPRequestHandler} instances.
 *
 * <p>Provided by an {@link HTTPService} and wired to its listeners.
 * The factory is called once per stream (request) when the initial headers
 * are received.
 *
 * <h2>Routing</h2>
 *
 * <p>The factory receives the request headers, allowing routing decisions
 * based on {@code :method}, {@code :path}, {@code :authority}, etc.:
 *
 * <pre>{@code
 * public class MyFactory implements HTTPRequestHandlerFactory {
 *     
 *     public HTTPRequestHandler createHandler(HTTPResponseState state, Headers headers) {
 *         String path = headers.getValue(":path");
 *         
 *         if (path.startsWith("/api/")) {
 *             return new ApiHandler();
 *         } else if (path.startsWith("/static/")) {
 *             return new StaticFileHandler(documentRoot);
 *         } else {
 *             return new DefaultHandler();
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>Authentication</h2>
 *
 * <p>If a {@link org.bluezoo.gumdrop.auth.Realm} is configured on the server,
 * authentication is performed automatically before the factory is called.
 * The authenticated principal is available via
 * {@link HTTPResponseState#getPrincipal()}.
 *
 * <p>If no Realm is configured, the factory or handler is responsible for
 * authentication. The factory can reject unauthenticated requests:
 *
 * <pre>{@code
 * public HTTPRequestHandler createHandler(HTTPResponseState state, Headers headers) {
 *     String auth = headers.getValue("authorization");
 *     if (!isValidAuth(auth)) {
 *         // Send 401 and return null
 *         Headers response = new Headers();
 *         response.add(":status", "401");
 *         response.add("www-authenticate", "Bearer");
 *         state.headers(response);
 *         state.complete();
 *         return null;
 *     }
 *     return new MyHandler();
 * }
 * }</pre>
 *
 * <h2>Returning null</h2>
 *
 * <p>If the factory returns null:
 * <ul>
 *   <li>If a response was sent via the state, that response is used</li>
 *   <li>If no response was sent, a 404 Not Found is sent automatically</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPRequestHandler
 * @see HTTPListener#setHandlerFactory
 */
public interface HTTPRequestHandlerFactory {

    /**
     * Creates a handler for a new request.
     *
     * <p>Called when the initial headers for a stream are received. The factory
     * can examine the headers to decide which handler implementation to return.
     *
     * <p>The returned handler will receive a {@link HTTPRequestHandler#headers}
     * callback with the same headers - the factory is for routing/creation,
     * the handler performs the actual request processing.
     *
     * @param state the response state for this stream (can be used to send
     *              early responses like 401)
     * @param headers the initial request headers (includes :method, :path,
     *                :scheme, :authority pseudo-headers)
     * @return a handler for this request, or null to reject (sends 404 if
     *         no response was sent via state)
     */
    HTTPRequestHandler createHandler(HTTPResponseState state, Headers headers);

    /**
     * Returns the set of HTTP methods supported by this factory.
     *
     * <p>Override this method to add custom methods (e.g., WebDAV methods like
     * PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK) or to restrict
     * the methods that are accepted.
     *
     * <p>If this method returns null (the default), the connection uses its
     * default set of known methods:
     * <ul>
     *   <li>Standard HTTP: GET, HEAD, POST, PUT, DELETE, CONNECT, OPTIONS, TRACE, PATCH</li>
     *   <li>HTTP/2: PRI</li>
     *   <li>WebDAV: PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK</li>
     * </ul>
     *
     * <p>Unknown methods result in a 501 Not Implemented response.
     *
     * @return set of supported method names (uppercase), or null to use defaults
     */
    default Set<String> getSupportedMethods() {
        return null;
    }

}
