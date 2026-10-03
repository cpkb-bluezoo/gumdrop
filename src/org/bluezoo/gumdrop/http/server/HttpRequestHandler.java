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
 * The server binds instances via {@link HttpStreamHandler#openStream(HttpResponse)}.
 * Implementations receive request events and use the provided
 * {@link HttpResponse} to send the response.
 *
 * <h2>Event Sequence</h2>
 *
 * <p>A request is the events of {@link HttpMessageHandler}, the same whichever
 * protocol carried it:
 * <pre>
 * version() method() target() scheme() authority()   // the start of the request
 * contentType() longHeader() header() ...            // one event per field line
 * endHeaders()
 * bodyContent() ...                                  // zero or more times
 * header() ...                                       // trailer fields, if any
 * endMessage()                                       // the request is complete
 * </pre>
 * A response is the same events with {@code status()} and {@code reason()}
 * in place of {@code method()} and {@code target()}. If the exchange cannot
 * complete, {@code error()} (a malformed message) or {@link #failed} (the
 * transport) is the last event instead of {@code endMessage()}.
 *
 * <h2>Response Sending</h2>
 *
 * <p>The handler can send the response at any point using the
 * {@link HttpResponse} provided to each callback. Common patterns:
 * <ul>
 *   <li>Respond immediately in {@code endHeaders()} for simple requests</li>
 *   <li>Accumulate body data and respond in {@code endMessage()}</li>
 *   <li>Stream response body while receiving request body</li>
 * </ul>
 *
 * <h2>Example Implementation</h2>
 *
 * <pre>{@code
 * public class HelloHandler extends DefaultHttpRequestHandler {
 *
 *     private final HttpResponse response;
 *
 *     public HelloHandler(HttpResponse response) {
 *         this.response = response;
 *     }
 *
 *     @Override
 *     public void endHeaders() {
 *         Headers fields = new Headers();
 *         fields.status(HttpStatus.OK);
 *         fields.add("content-type", "text/plain");
 *         response.headers(fields);
 *         response.startResponseBody();
 *         response.responseBodyContent(ByteBuffer.wrap("Hello, World!".getBytes()));
 *         response.endResponseBody();
 *         response.complete();
 *     }
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DefaultHttpRequestHandler
 * @see HttpResponse
 * @see HttpStreamHandler
 */
public interface HttpRequestHandler extends HttpMessageHandler {

    // ---- HttpMessageHandler events ----
    //
    // They default to doing nothing, so a handler overrides the ones it needs.

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
    @Override default void endMessage() { }
    @Override default void error(HttpError error, String detail) { }
    @Override default void failed(Exception cause) { }

    /**
     * Returns whether this request accepts HTTP Datagrams (RFC 9297).
     * Default {@code false}: an HTTP/3 Datagram with no known semantics
     * aborts the request stream with {@code H3_DATAGRAM_ERROR}.
     *
     * @return true if {@link #datagramReceived} should be called
     */
    /**
     * When {@code true}, the server decodes {@code Content-Encoding} on the
     * request body before {@link #bodyContent} (handlers see plain bytes).
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
    default void datagramReceived(HttpResponse response, ByteBuffer data) {
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
    default void capsuleReceived(HttpResponse response, long type, ByteBuffer value) {
        // Default: do nothing
    }

}

