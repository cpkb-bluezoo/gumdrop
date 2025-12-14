/*
 * PushPromise.java
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

package org.bluezoo.gumdrop.http.client;

import org.bluezoo.gumdrop.http.Headers;

/**
 * Represents an HTTP/2 server push promise (PUSH_PROMISE frame).
 *
 * <p>When an HTTP/2 server pushes a resource, the client receives a push promise
 * containing the headers that would have been sent in a request for that resource.
 * The client can choose to accept or reject the push.
 *
 * <p>This interface is delivered to the {@link HTTPResponseHandler#pushPromise(PushPromise)}
 * callback. The handler must either call {@link #accept(HTTPResponseHandler)} to receive
 * the pushed response, or {@link #reject()} to cancel it.
 *
 * <p><strong>Example:</strong>
 * <pre>
 * public void pushPromise(PushPromise promise) {
 *     if (promise.getPath().endsWith(".css")) {
 *         // Accept CSS pushes
 *         promise.accept(new CSSResponseHandler());
 *     } else {
 *         // Reject other pushes
 *         promise.reject();
 *     }
 * }
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPResponseHandler#pushPromise(PushPromise)
 */
public interface PushPromise {

    /**
     * Returns the HTTP method for the promised request.
     *
     * <p>Server push is typically only used for GET requests, but the protocol
     * allows other safe methods.
     *
     * @return the HTTP method (usually "GET")
     */
    String getMethod();

    /**
     * Returns the path (including query string) for the promised request.
     *
     * @return the request path
     */
    String getPath();

    /**
     * Returns the authority (host and port) for the promised request.
     *
     * @return the authority (e.g., "example.com:443")
     */
    String getAuthority();

    /**
     * Returns the scheme for the promised request.
     *
     * @return the scheme (typically "https" for HTTP/2)
     */
    String getScheme();

    /**
     * Returns all headers for the promised request.
     *
     * <p>This includes the pseudo-headers (:method, :path, :authority, :scheme)
     * as well as any regular headers.
     *
     * @return the headers (never null)
     */
    Headers getHeaders();

    /**
     * Accepts the push promise and provides a handler for the pushed response.
     *
     * <p>The provided handler will receive the same callbacks as a normal response:
     * {@link HTTPResponseHandler#ok(HTTPResponse)}, {@link HTTPResponseHandler#header(String, String)},
     * body content callbacks, and {@link HTTPResponseHandler#close()}.
     *
     * <p>This method must be called at most once. After calling this method,
     * {@link #reject()} must not be called.
     *
     * @param handler the handler to receive the pushed response
     */
    void accept(HTTPResponseHandler handler);

    /**
     * Rejects the push promise.
     *
     * <p>This sends an RST_STREAM frame to cancel the promised stream.
     * No further callbacks will be received for this push.
     *
     * <p>This method must be called at most once. After calling this method,
     * {@link #accept(HTTPResponseHandler)} must not be called.
     */
    void reject();
}

