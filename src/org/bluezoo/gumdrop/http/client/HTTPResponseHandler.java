/*
 * HTTPResponseHandler.java
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

import java.nio.ByteBuffer;

/**
 * Handler interface for receiving HTTP response events.
 *
 * <p>This interface uses an event-driven pattern where response components are
 * delivered incrementally as they arrive. This enables streaming processing of
 * large responses and proper handling of HTTP trailer headers.
 *
 * <h3>Event Flow</h3>
 *
 * <p>For a successful response with a body:
 * <ol>
 *   <li>{@link #ok(HTTPResponse)} - status received</li>
 *   <li>{@link #header(String, String)} - called for each response header</li>
 *   <li>{@link #startResponseBody()} - body begins</li>
 *   <li>{@link #responseBodyContent(ByteBuffer)} - called for each body chunk</li>
 *   <li>{@link #endResponseBody()} - body complete</li>
 *   <li>{@link #header(String, String)} - called for each trailer header (if any)</li>
 *   <li>{@link #close()} - response complete</li>
 * </ol>
 *
 * <p>For a bodyless response (e.g., 204 No Content):
 * <ol>
 *   <li>{@link #ok(HTTPResponse)} - status received</li>
 *   <li>{@link #header(String, String)} - called for each response header</li>
 *   <li>{@link #close()} - response complete</li>
 * </ol>
 *
 * <p>For an error response:
 * <ol>
 *   <li>{@link #error(HTTPResponse)} - error status received</li>
 *   <li>{@link #header(String, String)} - called for each response header</li>
 *   <li>(body events if the error response has a body)</li>
 *   <li>{@link #close()} - response complete</li>
 * </ol>
 *
 * <p>For a connection failure:
 * <ol>
 *   <li>{@link #failed(Exception)} - connection or protocol error</li>
 * </ol>
 *
 * <h3>HTTP/2 Server Push</h3>
 *
 * <p>When an HTTP/2 server sends a PUSH_PROMISE, the {@link #pushPromise(PushPromise)}
 * callback is invoked. The handler can accept or reject the push. If accepted,
 * a separate handler receives the pushed response.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPRequest
 * @see DefaultHTTPResponseHandler
 */
public interface HTTPResponseHandler {

    /**
     * Called when a successful response (2xx) status line is received.
     *
     * <p>After this callback, {@link #header(String, String)} will be called
     * for each response header, followed by body events (if applicable),
     * and finally {@link #close()}.
     *
     * @param response the response status
     */
    void ok(HTTPResponse response);

    /**
     * Called when an error response (4xx, 5xx, or client-side pseudo-status) is received.
     *
     * <p>This is called for HTTP error responses and client-detected conditions
     * like {@link HTTPStatus#REDIRECT_LOOP}. The response may still have headers
     * and a body (e.g., an HTML error page), which will be delivered via subsequent
     * callbacks before {@link #close()}.
     *
     * @param response the error response
     */
    void error(HTTPResponse response);

    /**
     * Called for each HTTP header received.
     *
     * <p>Headers are delivered in the order they are received. This method may be called:
     * <ul>
     *   <li>After {@link #ok(HTTPResponse)} or {@link #error(HTTPResponse)} for response headers</li>
     *   <li>After {@link #endResponseBody()} for trailer headers (HTTP/2 or chunked encoding)</li>
     * </ul>
     *
     * <p>The same header name may appear multiple times for multi-value headers.
     *
     * @param name the header name (case may vary, compare case-insensitively)
     * @param value the header value
     */
    void header(String name, String value);

    /**
     * Called when the response body begins.
     *
     * <p>This is not called for bodyless responses (e.g., 204 No Content, 304 Not Modified).
     * After this callback, {@link #responseBodyContent(ByteBuffer)} will be called for
     * each chunk of body data, followed by {@link #endResponseBody()}.
     */
    void startResponseBody();

    /**
     * Called for each chunk of response body data.
     *
     * <p>The buffer is only valid during this callback. If the data is needed later,
     * it must be copied. The buffer's position and limit define the valid data range.
     *
     * @param data the body data chunk
     */
    void responseBodyContent(ByteBuffer data);

    /**
     * Called when the response body is complete.
     *
     * <p>After this callback, trailer headers (if any) may be delivered via
     * {@link #header(String, String)}, followed by {@link #close()}.
     */
    void endResponseBody();

    /**
     * Called when an HTTP/2 server push promise is received.
     *
     * <p>The handler must either call {@link PushPromise#accept(HTTPResponseHandler)}
     * to receive the pushed response, or {@link PushPromise#reject()} to cancel it.
     * If neither is called, the push is rejected by default.
     *
     * <p>This callback is only invoked for HTTP/2 connections.
     *
     * @param promise the push promise
     */
    void pushPromise(PushPromise promise);

    /**
     * Called when the response is fully complete.
     *
     * <p>This is always the final callback for a successful response, called after
     * all headers (including trailers) and body data have been delivered. After this
     * callback, no further callbacks will be invoked for this response.
     *
     * <p>This method is analogous to closing a stream or connection from the
     * response handler's perspective.
     */
    void close();

    /**
     * Called when the request fails due to a connection error, protocol error,
     * cancellation, or server shutdown (GOAWAY).
     *
     * <p>This is the only callback invoked on failure. After this callback,
     * no further callbacks will be invoked for this response.
     *
     * <p>Common exceptions include:
     * <ul>
     *   <li>{@link java.net.ConnectException} - connection refused</li>
     *   <li>{@link java.net.UnknownHostException} - DNS failure</li>
     *   <li>{@link javax.net.ssl.SSLException} - TLS handshake failure</li>
     *   <li>{@link java.net.SocketTimeoutException} - timeout</li>
     *   <li>{@link java.io.IOException} - connection dropped</li>
     *   <li>{@link java.util.concurrent.CancellationException} - request cancelled</li>
     * </ul>
     *
     * @param ex the exception describing the failure
     */
    void failed(Exception ex);
}

