/*
 * HttpRequestHandler.java
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

package org.bluezoo.gumdrop.http.server;

import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.HttpError;
import org.bluezoo.gumdrop.http.HttpMessageHandler;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;

import java.nio.ByteBuffer;

/**
 * Handler for HTTP request events on a single stream.
 *
 * <p>This interface provides an event-driven API for handling HTTP requests.
 * Each instance handles exactly one request/response exchange (one stream).
 * The server binds instances via {@link HttpStreamHandler#openStream(HttpResponseState)}.
 * Implementations receive request events and use the provided
 * {@link HttpResponseState} to send the response.
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
 * {@link HttpResponseState} provided to each callback. Common patterns:
 * <ul>
 *   <li>Respond immediately in {@code headers()} for simple requests</li>
 *   <li>Accumulate body data and respond in {@code endRequestBody()}</li>
 *   <li>Stream response body while receiving request body</li>
 * </ul>
 *
 * <h2>Example Implementation</h2>
 *
 * <pre>{@code
 * public class HelloHandler extends DefaultHttpRequestHandler {
 *     
 *     @Override
 *     public void headers(HttpResponseState response, Headers headers) {
 *         if ("GET".equals(headers.getMethod())) {
 *             Headers fields = new Headers();
 *             fields.status(HttpStatus.OK);
 *             fields.add("content-type", "text/plain");
 *             response.headers(fields);
 *             response.startResponseBody();
 *             response.responseBodyContent(ByteBuffer.wrap("Hello, World!".getBytes()));
 *             response.endResponseBody();
 *             response.complete();
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DefaultHttpRequestHandler
 * @see HttpResponseState
 * @see HttpStreamHandler
 */
public interface HttpRequestHandler extends HttpMessageHandler {

    // ---- HttpMessageHandler events ----
    //
    // A request arrives as the events of HttpMessageHandler (method, target,
    // fields, endHeaders, body, endMessage), the same whichever protocol
    // carried it. They default to doing nothing so that a handler written for
    // the older headers/startRequestBody/requestBodyContent/requestComplete
    // methods below keeps working while handlers move over to the events; those
    // methods will then be removed.

    @Override default void method(HttpMethod method) { }
    @Override default void target(ByteBuffer target) { }
    @Override default void scheme(ByteBuffer scheme) { }
    @Override default void authority(ByteBuffer authority) { }
    @Override default void protocol(ByteBuffer protocol) { }
    @Override default void version(HttpVersion version) { }
    @Override default void status(int code) { }
    @Override default void reason(ByteBuffer phrase) { }
    @Override default void contentType(ContentType contentType) { }
    @Override default void contentDisposition(ContentDisposition contentDisposition) { }
    @Override default void longHeader(String name, long value) { }
    @Override default void header(String name, ByteBuffer value) { }
    @Override default void endHeaders() { }
    @Override default void bodyContent(ByteBuffer data) { }
    @Override default void trailer(String name, ByteBuffer value) { }
    @Override default void endMessage() { }
    @Override default void error(HttpError error, String detail) { }

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
     * @param response for sending the response
     * @param headers the headers (pseudo-headers normalized for all HTTP versions)
     */
    void headers(HttpResponseState response, Headers headers);

    /**
     * Request body is starting.
     *
     * <p>Called before the first {@link #requestBodyContent} if the request
     * has a body. Not called for requests without a body (GET, HEAD, etc.).
     *
     * @param response the response
     */
    void startRequestBody(HttpResponseState response);

    /**
     * Request body data received.
     *
     * <p>Called for each chunk of request body data. May be called multiple
     * times. The buffer is only valid during this callback - if the data
     * is needed later, it must be copied.
     *
     * @param response the response
     * @param data the body data (position and limit define valid range)
     */
    void requestBodyContent(HttpResponseState response, ByteBuffer data);

    /**
     * Request body complete.
     *
     * <p>Called after the last {@link #requestBodyContent} when all body
     * data has been received. Trailer headers (if any) will follow via
     * {@link #headers} before {@link #requestComplete}.
     *
     * @param response the response
     */
    void endRequestBody(HttpResponseState response);

    /**
     * Request stream closed from client side.
     *
     * <p>This is the final callback for this stream. No more events will
     * be delivered. The handler should complete its response if not
     * already done.
     *
     * @param response the response
     */
    void requestComplete(HttpResponseState response);

    /**
     * The request failed due to a transport or protocol-level error
     * before {@link #requestComplete} could be delivered normally --
     * e.g. the underlying connection was closed or errored mid-request
     * (HTTP/1.1, HTTP/2 and HTTP/3), the peer reset the stream with
     * RST_STREAM (HTTP/2), sent GOAWAY and closed the connection
     * (HTTP/2), or closed the connection with an error (see {@code
     * QuicConnectionCloseException} for the HTTP/3 case). This is the
     * final callback for this stream: it is delivered at most once, never
     * after {@link #requestComplete}, and never for a request the server
     * itself rejected before it reached this handler. No more events will
     * be delivered, and any response already sent through {@code response}
     * is final.
     *
     * <p>Default implementation does nothing, so existing implementations
     * are unaffected by this method's addition; override to react to
     * abnormal termination the way {@link
     * org.bluezoo.gumdrop.http.client.HttpResponseHandler#failed} already
     * lets client code do for the client side.
     *
     * @param response the response
     * @param cause the error
     */
    default void failed(HttpResponseState response, Exception cause) {
        // Default: do nothing
    }

    /**
     * Returns whether this request accepts HTTP Datagrams (RFC 9297).
     * Default {@code false}: an HTTP/3 Datagram with no known semantics
     * aborts the request stream with {@code H3_DATAGRAM_ERROR}.
     *
     * @return true if {@link #datagramReceived} should be called
     */
    /**
     * When {@code true}, the server decodes {@code Content-Encoding} on the
     * request body before {@link #requestBodyContent} (handlers see plain bytes).
     * Default {@code false} so servlet and similar stacks receive the on-the-wire
     * representation.
     */
    default boolean decodeRequestContentCoding() {
        return false;
    }

    /**
     * When {@code true}, the server may compress the response body with
     * {@code Content-Encoding} when the client sends {@code Accept-Encoding}
     * and the listener allows compression. Default {@code false}.
     */
    default boolean encodeResponseContentCoding() {
        return false;
    }

    default boolean wantsDatagrams() {
        return false;
    }

    /**
     * An HTTP Datagram associated with this request (QUIC DATAGRAM
     * demuxed by quarter-stream-ID, or a DATAGRAM capsule). Only called
     * when {@link #wantsDatagrams()} is true.
     *
     * @param response the response
     * @param data the datagram payload; valid only during this call
     */
    default void datagramReceived(HttpResponseState response, ByteBuffer data) {
        // Default: do nothing
    }

    /**
     * A Capsule Protocol capsule other than DATAGRAM (RFC 9297
     * section 3.2). Unknown types should usually be ignored.
     *
     * @param response the response
     * @param type the Capsule Type
     * @param value the Capsule Value; valid only during this call
     */
    default void capsuleReceived(HttpResponseState response, long type, ByteBuffer value) {
        // Default: do nothing
    }

}

