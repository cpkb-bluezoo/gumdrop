/*
 * HTTPResponseState.java
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

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;

import java.util.List;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;

/**
 * State interface for sending an HTTP response.
 *
 * <p>This interface is provided to {@link HTTPRequestHandler} callbacks and
 * allows the handler to send the response. Methods should be called in order:
 *
 * <pre>
 * sendInformational()    // optional, 1xx (e.g. 103 Early Hints)
 * headers()              // required, includes :status
 * headers()              // continuation headers (if needed)
 * startResponseBody()    // if response has a body
 * responseBodyContent()  // zero or more times
 * endResponseBody()      // if body was started
 * headers()              // trailer headers (optional)
 * complete()             // required
 * </pre>
 *
 * <h2>Response Patterns</h2>
 *
 * <p><b>Simple response with body:</b>
 * <pre>{@code
 * Headers response = new Headers();
 * response.add(":status", "200");
 * response.add("content-type", "application/json");
 * state.headers(response);
 * state.startResponseBody();
 * state.responseBodyContent(ByteBuffer.wrap(jsonBytes));
 * state.endResponseBody();
 * state.complete();
 * }</pre>
 *
 * <p><b>Response without body (204, 304, redirects):</b>
 * <pre>{@code
 * Headers response = new Headers();
 * response.add(":status", "204");
 * state.headers(response);
 * state.complete();
 * }</pre>
 *
 * <p><b>Streaming response:</b>
 * <pre>{@code
 * Headers response = new Headers();
 * response.add(":status", "200");
 * response.add("content-type", "text/event-stream");
 * state.headers(response);
 * state.startResponseBody();
 * // Send chunks as data becomes available
 * state.responseBodyContent(chunk1);
 * state.responseBodyContent(chunk2);
 * // ... more chunks ...
 * state.endResponseBody();
 * state.complete();
 * }</pre>
 *
 * <p><b>Response with trailer headers:</b>
 * <pre>{@code
 * Headers response = new Headers();
 * response.add(":status", "200");
 * state.headers(response);
 * state.startResponseBody();
 * state.responseBodyContent(data);
 * state.endResponseBody();
 * Headers trailers = new Headers();
 * trailers.add("x-checksum", checksum);
 * state.headers(trailers);  // trailers (after endResponseBody)
 * state.complete();
 * }</pre>
 *
 * <h2>HTTP/2 Server Push</h2>
 *
 * <p>For HTTP/2 connections, {@link #pushPromise} can be used to initiate
 * server push. The pushed request will be processed through the normal
 * factory/handler mechanism.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTPRequestHandler
 */
public interface HTTPResponseState {

    // ─────────────────────────────────────────────────────────────────────────
    // Connection Info
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the remote (client) socket address.
     *
     * @return the remote socket address
     */
    SocketAddress getRemoteAddress();

    /**
     * Returns the local (server) socket address.
     *
     * @return the local socket address
     */
    SocketAddress getLocalAddress();

    /**
     * Returns whether the connection is secured by TLS (or QUIC).
     *
     * @return true if the connection is secure
     */
    boolean isSecure();

    /**
     * Returns security metadata for this connection.
     *
     * <p>When {@link #isSecure()} returns true, this provides details about
     * the negotiated cipher suite, protocol version, certificates, and ALPN.
     * When not secure, returns a NullSecurityInfo singleton.
     *
     * @return the security info, never null
     */
    SecurityInfo getSecurityInfo();

    /**
     * Returns the HTTP version being used for this request/response.
     *
     * @return the HTTP version
     */
    HTTPVersion getVersion();

    /**
     * Returns the URL scheme ("http" or "https").
     *
     * @return the scheme
     */
    String getScheme();

    /**
     * Returns the SelectorLoop that owns this connection's I/O.
     *
     * <p>Used by services that dispatch to worker threads (e.g. the servlet
     * container) to marshal response operations back onto the correct I/O
     * thread. Returns {@code null} if no SelectorLoop is associated.
     *
     * @return the owning SelectorLoop, or null
     */
    SelectorLoop getSelectorLoop();

    /**
     * Returns the current trace for distributed tracing, or null if none.
     *
     * <p>When making outbound HTTP calls to other services, pass this trace
     * to {@link org.bluezoo.gumdrop.http.client.HTTPClient#setTrace} so that
     * the traceparent header is automatically propagated and the distributed
     * trace remains connected.
     *
     * @return the current trace, or null
     */
    default Trace getTrace() {
        return null;
    }

    /**
     * Returns the authenticated principal, or null if not authenticated.
     *
     * <p>This is only populated if the server's {@link org.bluezoo.gumdrop.auth.Realm}
     * performed authentication. If no Realm is configured on the server,
     * authentication is the handler's responsibility.
     *
     * @return the authenticated principal, or null
     */
    Principal getPrincipal();

    // ─────────────────────────────────────────────────────────────────────────
    // Response Events
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends response headers.
     *
     * <p>For the initial response headers, must include the {@code :status}
     * pseudo-header with the HTTP status code. Can be called multiple times:
     * <ul>
     *   <li>Before {@code startResponseBody()} - response headers</li>
     *   <li>After {@code endResponseBody()} - trailer headers</li>
     * </ul>
     *
     * <p>Headers are buffered until the next event determines how to send them:
     * <ul>
     *   <li>{@code startResponseBody()} - flush as HEADERS frame</li>
     *   <li>{@code complete()} - flush as HEADERS frame with END_STREAM</li>
     * </ul>
     *
     * @param headers the headers to send
     */
    void headers(Headers headers);

    /**
     * Signals the start of the response body.
     *
     * <p>Must be called before {@link #responseBodyContent} if the response
     * has a body. Triggers flushing of buffered headers.
     */
    void startResponseBody();

    /**
     * Sends response body data.
     *
     * <p>Can be called multiple times for streaming responses. The buffer
     * contents are sent; the buffer itself is not retained after this call.
     *
     * @param data the body data to send
     */
    void responseBodyContent(ByteBuffer data);

    /**
     * Signals the end of the response body.
     *
     * <p>Must be called after all {@link #responseBodyContent} calls.
     * After this, {@link #headers} calls are interpreted as trailer headers.
     */
    void endResponseBody();

    /**
     * Completes the response.
     *
     * <p>This must be called to signal the end of the response. For HTTP/2,
     * this sends the END_STREAM flag. For HTTP/1.1 with chunked encoding,
     * this sends the final chunk marker.
     *
     * <p>Behaviour depends on buffered state:
     * <ul>
     *   <li>If headers buffered (no body or trailers pending): flush with END_STREAM</li>
     *   <li>If no headers buffered: send empty frame with END_STREAM</li>
     * </ul>
     */
    void complete();

    // ─────────────────────────────────────────────────────────────────────────
    // Thread Dispatch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Dispatches a task to run on this stream's SelectorLoop thread.
     * If the caller is already on the SelectorLoop thread, the task
     * runs immediately.  Otherwise it is enqueued and will run on
     * the next selector iteration.
     *
     * <p>This is intended for use from external threads (e.g. an
     * {@link java.nio.channels.AsynchronousFileChannel} completion
     * handler) that need to call back into the response API.
     *
     * @param task the task to execute
     */
    void execute(Runnable task);

    // ─────────────────────────────────────────────────────────────────────────
    // Backpressure / Flow Control
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Registers a one-shot callback to be invoked when the transport is
     * ready to accept more response body data (write buffer drained).
     *
     * <p>The callback runs on the SelectorLoop thread, so it is safe to
     * perform further I/O operations (e.g. call
     * {@link #responseBodyContent(ByteBuffer)} again) from within it.
     *
     * <p>Only one callback may be pending at a time.  Calling this method
     * replaces any previously registered callback.  Pass {@code null} to
     * clear an existing callback without registering a new one.
     *
     * <p>Typical use: send a chunk of response body data, then register a
     * callback to send the next chunk when the transport has flushed.
     *
     * @param callback the callback, or null to clear
     */
    void onWritable(Runnable callback);

    /**
     * Pauses delivery of request body events
     * ({@link HTTPRequestHandler#requestBodyContent}).
     *
     * <p>When paused, the transport stops reading data from the network
     * for this stream.  Backpressure propagates to the client, causing
     * it to slow or stop sending.
     *
     * <p>For HTTP/1.1, this removes {@code OP_READ} from the
     * connection's {@code SelectionKey}, causing TCP backpressure.
     *
     * <p>For HTTP/2, this withholds WINDOW_UPDATE frames for this
     * stream.  Other streams on the same connection are unaffected.
     *
     * <p>For HTTP/3, this stops consuming body data from the QUIC
     * stream, causing the peer's flow control window to fill.
     *
     * <p>Call {@link #resumeRequestBody()} to resume delivery.
     */
    void pauseRequestBody();

    /**
     * Resumes delivery of request body events after a previous call to
     * {@link #pauseRequestBody()}.
     *
     * <p>The transport re-enables reading from the network for this
     * stream.  Any data that has already arrived will be delivered
     * promptly, followed by further data as it arrives.
     */
    void resumeRequestBody();

    // ─────────────────────────────────────────────────────────────────────────
    // Informational Responses (1xx)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends an informational (1xx) response before the final response.
     *
     * <p>RFC 9110 section 15.2 allows a server to send one or more
     * intermediate 1xx responses before the final response. The primary
     * use case is 103 Early Hints (RFC 8297), which allows the server to
     * send {@code Link} headers so the client can begin preloading
     * resources while the final response is being prepared.
     *
     * <p>This method may be called zero or more times before
     * {@link #headers(Headers)}. The response state is not transitioned
     * -- the handler must still send the final response via
     * {@code headers()} and {@code complete()}.
     *
     * <p>Example:
     * <pre>{@code
     * Headers hints = new Headers();
     * hints.add("Link", "</style.css>; rel=preload; as=style");
     * hints.add("Link", "</app.js>; rel=preload; as=script");
     * state.sendInformational(103, hints);
     *
     * Headers response = new Headers();
     * response.status(HTTPStatus.OK);
     * response.add("content-type", "text/html");
     * state.headers(response);
     * // ... body and complete() ...
     * }</pre>
     *
     * <p>For HTTP/1.0 connections, this method is a no-op (1xx responses
     * are not defined for HTTP/1.0). For HTTP/1.1, HTTP/2, and HTTP/3,
     * the informational response is sent immediately on the wire.
     *
     * @param statusCode the informational status code (100-199)
     * @param headers the headers to include (e.g. Link headers)
     * @throws IllegalArgumentException if statusCode is not 1xx
     * @throws IllegalStateException if the final response has already started
     * @see <a href="https://www.rfc-editor.org/rfc/rfc8297">RFC 8297: Early Hints</a>
     * @see <a href="https://www.rfc-editor.org/rfc/rfc9110#section-15.2">RFC 9110 Section 15.2</a>
     */
    default void sendInformational(int statusCode, Headers headers) {
        // Default: no-op for implementations that do not support 1xx
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Server Push
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initiates an HTTP/2 server push.
     *
     * <p>The headers must include the pseudo-headers for the promised request:
     * {@code :method}, {@code :path}, {@code :scheme}, {@code :authority}.
     *
     * <p>The pushed request will be processed through the normal
     * {@link HTTPRequestHandlerFactory} mechanism, creating a new handler
     * for the pushed stream.
     *
     * <p>For HTTP/1.x connections, this method returns false and has no effect.
     *
     * @param headers the headers for the promised request
     * @return true if the push was initiated, false if not supported or
     *         disabled by the client
     */
    boolean pushPromise(Headers headers);

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocket Upgrade
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Upgrades this HTTP connection to WebSocket protocol.
     *
     * <p>This method validates the request, sends the 101 Switching Protocols
     * response, and switches the connection to WebSocket mode. Once complete,
     * the handler's {@link WebSocketEventHandler#opened} method is called.
     *
     * <p>Example usage:
     * <pre>{@code
     * public void headers(HTTPResponseState state, Headers headers) {
     *     if (WebSocketHandshake.isValidWebSocketUpgrade(headers)) {
     *         String protocol = headers.getValue("Sec-WebSocket-Protocol");
     *         state.upgradeToWebSocket(protocol, new DefaultWebSocketEventHandler() {
     *             
     *             public void textMessageReceived(WebSocketSession session,
     *                                             String message) {
     *                 session.sendText("Echo: " + message);
     *             }
     *         });
     *     }
     * }
     * }</pre>
     *
     * @param subprotocol optional negotiated subprotocol (may be null)
     * @param handler receives WebSocket lifecycle events
     * @throws IllegalStateException if not a valid WebSocket upgrade request
     *         or if the response has already started
     */
    void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler);

    /**
     * RFC 6455 §4.2.2 / §9.1 — upgrades to WebSocket with negotiated
     * extensions. The extensions header is included in the 101 response
     * and the extension pipeline is configured on the connection.
     *
     * @param subprotocol optional negotiated subprotocol (may be null)
     * @param extensions negotiated extensions (may be null or empty)
     * @param handler receives WebSocket lifecycle events
     * @throws IllegalStateException if not a valid WebSocket upgrade request
     */
    default void upgradeToWebSocket(String subprotocol,
                                    List<WebSocketExtension> extensions,
                                    WebSocketEventHandler handler) {
        upgradeToWebSocket(subprotocol, handler);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cancel
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cancels the stream.
     *
     * <p>For HTTP/2, this sends an RST_STREAM frame. For HTTP/1.x, this
     * closes the connection.
     *
     * <p>Use this for error conditions where the normal response flow
     * cannot be completed.
     */
    void cancel();

}

