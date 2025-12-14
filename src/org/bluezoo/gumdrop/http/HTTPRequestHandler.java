/*
 * HTTPRequestHandler.java
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

import java.nio.ByteBuffer;

/**
 * Handler for HTTP request events on a single stream.
 *
 * <p>This interface provides an event-driven API for handling HTTP requests.
 * Each instance handles exactly one request/response exchange (one stream).
 * Implementations receive request events and use the provided
 * {@link HTTPResponseState} to send the response.
 *
 * <h2>Event Sequence</h2>
 *
 * <p>For a request with body and trailers:
 * <pre>
 * headers()              // initial request headers (:method, :path, etc.)
 * headers()              // continuation headers (if needed)
 * startRequestBody()
 * requestBodyContent()   // first DATA frame
 * requestBodyContent()   // subsequent DATA frames
 * endRequestBody()
 * headers()              // trailer headers
 * requestComplete()      // stream closed from client
 * </pre>
 *
 * <p>For a request without body (GET, HEAD, etc.):
 * <pre>
 * headers()              // request headers with END_STREAM
 * requestComplete()
 * </pre>
 *
 * <p>The {@code headers()} method may be called multiple times:
 * <ul>
 *   <li>Before {@code startRequestBody()} - request headers</li>
 *   <li>After {@code endRequestBody()} - trailer headers</li>
 * </ul>
 *
 * <h2>Response Sending</h2>
 *
 * <p>The handler can send the response at any point using the
 * {@link HTTPResponseState} provided to each callback. Common patterns:
 * <ul>
 *   <li>Respond immediately in {@code headers()} for simple requests</li>
 *   <li>Accumulate body data and respond in {@code endRequestBody()}</li>
 *   <li>Stream response body while receiving request body</li>
 * </ul>
 *
 * <h2>Example Implementation</h2>
 *
 * <pre>{@code
 * public class HelloHandler extends DefaultHTTPRequestHandler {
 *     
 *     @Override
 *     public void headers(Headers headers, HTTPResponseState state) {
 *         if ("GET".equals(headers.getMethod())) {
 *             Headers response = new Headers();
 *             response.status(HTTPStatus.OK);
 *             response.add("content-type", "text/plain");
 *             state.headers(response);
 *             state.startResponseBody();
 *             state.responseBodyContent(ByteBuffer.wrap("Hello, World!".getBytes()));
 *             state.endResponseBody();
 *             state.complete();
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DefaultHTTPRequestHandler
 * @see HTTPResponseState
 * @see HTTPRequestHandlerFactory
 */
public interface HTTPRequestHandler {

    /**
     * Headers received.
     *
     * <p>Called when HTTP headers are received. This may be called multiple
     * times for the same request:
     * <ul>
     *   <li>Initial request headers (always includes :method, :path, :scheme,
     *       :authority pseudo-headers regardless of HTTP version)</li>
     *   <li>Continuation headers (if header block spans multiple frames)</li>
     *   <li>Trailer headers (after {@link #endRequestBody})</li>
     * </ul>
     *
     * <p>The position in the event sequence indicates the header type:
     * headers before {@code startRequestBody()} are request headers;
     * headers after {@code endRequestBody()} are trailers.
     *
     * @param headers the headers (pseudo-headers normalized for all HTTP versions)
     * @param state the response state for sending the response
     */
    void headers(Headers headers, HTTPResponseState state);

    /**
     * Request body is starting.
     *
     * <p>Called before the first {@link #requestBodyContent} if the request
     * has a body. Not called for requests without a body (GET, HEAD, etc.).
     *
     * @param state the response state
     */
    void startRequestBody(HTTPResponseState state);

    /**
     * Request body data received.
     *
     * <p>Called for each chunk of request body data. May be called multiple
     * times. The buffer is only valid during this callback - if the data
     * is needed later, it must be copied.
     *
     * @param data the body data (position and limit define valid range)
     * @param state the response state
     */
    void requestBodyContent(ByteBuffer data, HTTPResponseState state);

    /**
     * Request body complete.
     *
     * <p>Called after the last {@link #requestBodyContent} when all body
     * data has been received. Trailer headers (if any) will follow via
     * {@link #headers} before {@link #requestComplete}.
     *
     * @param state the response state
     */
    void endRequestBody(HTTPResponseState state);

    /**
     * Request stream closed from client side.
     *
     * <p>This is the final callback for this stream. No more events will
     * be delivered. The handler should complete its response if not
     * already done.
     *
     * @param state the response state
     */
    void requestComplete(HTTPResponseState state);

}

