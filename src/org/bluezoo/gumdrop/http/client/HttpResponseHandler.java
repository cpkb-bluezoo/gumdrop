/*
 * HttpResponseHandler.java
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

import org.bluezoo.gumdrop.http.HttpError;
import org.bluezoo.gumdrop.http.HttpMessageHandler;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentType;

/**
 * Handler interface for receiving HTTP response events.
 *
 * <p>A response arrives as the events of {@link HttpMessageHandler}, the same
 * whichever protocol carried it, and the same events a server handler receives
 * for a request but for how the message starts: a response begins with
 * {@code status()} and {@code reason()} where a request begins with
 * {@code method()} and {@code target()}. They are delivered incrementally as
 * they arrive, so large responses can be processed as a stream, and trailer
 * fields reach the handler too (RFC 9112 section 7.1.2).
 *
 * <h3>Event Flow</h3>
 *
 * <p>For a response with a body:
 * <ol>
 *   <li>{@code version()}, {@code status()}, {@code reason()}</li>
 *   <li>the field events: {@code contentType()}, {@code longHeader()},
 *       {@code header()} ..., one per field line</li>
 *   <li>{@code endHeaders()}</li>
 *   <li>{@code bodyContent()} - zero or more times</li>
 *   <li>{@code header()} - for each trailer field, if any</li>
 *   <li>{@code endMessage()} - the response is complete</li>
 * </ol>
 *
 * <p>A bodyless response (for example 204 No Content) has no
 * {@code bodyContent()}. Interim ({@code 1xx}) responses are not delivered. A
 * malformed response ends with {@code error()}, and a response cut short by
 * the connection or by the server ends with {@link #failed(Exception)}
 * instead of {@code endMessage()}; nothing follows either. The status of a
 * redirect the client does not follow, or of an error, is an ordinary
 * {@code status()}: a handler that cares about success looks at the code.
 *
 * <h3>HTTP/2 Server Push</h3>
 *
 * <p>When an HTTP/2 server sends a PUSH_PROMISE, {@link #pushPromise()} is
 * called. It returns a {@link PushPromiseHandler} that receives the promised
 * request as the events of a request and then supplies the handler that
 * receives the pushed response, or refuses the push.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HttpRequest
 * @see DefaultHttpResponseHandler
 */
public interface HttpResponseHandler extends HttpMessageHandler {

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
    @Override default void dateHeader(String name, java.time.Instant value) { }
    @Override default void header(String name, ByteBuffer value) { }
    @Override default void endHeaders() { }
    @Override default void bodyContent(ByteBuffer data) { }
    @Override default void endMessage() { }
    @Override default void error(HttpError error, String detail) { }

    /**
     * Called when an HTTP/2 server sends a push promise (PUSH_PROMISE, RFC 9113
     * section 8.4). Returns the handler to receive the promised request as the
     * events of a request, or {@code null} to refuse the push, which is what
     * the default does. See {@link PushPromiseHandler}.
     *
     * <p>This is only called for HTTP/2 connections.
     *
     * @return the handler for the promised request, or null to refuse
     */
    default PushPromiseHandler pushPromise() {
        return null;
    }

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

    /**
     * Returns whether this response accepts HTTP Datagrams (RFC 9297).
     * Default {@code false}: an HTTP/3 Datagram with no known semantics
     * aborts the request stream with {@code H3_DATAGRAM_ERROR}.
     *
     * @return true if {@link #datagramReceived} should be called
     */
    default boolean wantsDatagrams() {
        return false;
    }

    /**
     * An HTTP Datagram associated with this request (QUIC DATAGRAM
     * demuxed by quarter-stream-ID, or a DATAGRAM capsule). Only called
     * when {@link #wantsDatagrams()} is true.
     *
     * @param data the datagram payload; valid only during this call
     */
    default void datagramReceived(ByteBuffer data) {
        // Default: do nothing
    }

    /**
     * A Capsule Protocol capsule other than DATAGRAM (RFC 9297
     * section 3.2). Unknown types should usually be ignored.
     *
     * @param type the Capsule Type
     * @param value the Capsule Value; valid only during this call
     */
    default void capsuleReceived(long type, ByteBuffer value) {
        // Default: do nothing
    }
}

