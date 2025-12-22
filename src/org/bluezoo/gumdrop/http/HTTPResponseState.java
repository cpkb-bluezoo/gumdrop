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

import java.nio.ByteBuffer;
import java.security.Principal;

import org.bluezoo.gumdrop.ConnectionInfo;
import org.bluezoo.gumdrop.TLSInfo;

/**
 * State interface for sending an HTTP response.
 *
 * <p>This interface is provided to {@link HTTPRequestHandler} callbacks and
 * allows the handler to send the response. Methods should be called in order:
 *
 * <pre>
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
     * Returns connection information (addresses, etc.).
     *
     * @return the connection info
     */
    ConnectionInfo getConnectionInfo();

    /**
     * Returns TLS information, or null if the connection is not secure.
     *
     * @return TLS info, or null
     */
    TLSInfo getTLSInfo();

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
     * public void headers(Headers headers, HTTPResponseState state) {
     *     if (WebSocketHandshake.isValidWebSocketUpgrade(headers)) {
     *         String protocol = headers.getValue("Sec-WebSocket-Protocol");
     *         state.upgradeToWebSocket(protocol, new DefaultWebSocketEventHandler() {
     *             private WebSocketSession session;
     *             
     *             public void opened(WebSocketSession session) {
     *                 this.session = session;
     *             }
     *             
     *             public void textMessageReceived(String message) {
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

