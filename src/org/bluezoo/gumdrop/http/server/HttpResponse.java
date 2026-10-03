/*
 * HttpResponse.java
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

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;

import java.util.List;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpDatagramContext;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;

/**
 * Interface for sending an HTTP response.
 *
 * <p>This interface is provided to the {@link HttpRequestHandler} when the
 * stream is opened and allows the handler to send the response. It mirrors
 * the events a received response is made of ({@link
 * org.bluezoo.gumdrop.http.HttpMessageHandler}), and the client's
 * {@link org.bluezoo.gumdrop.http.client.HttpRequest}: the same events, in
 * the same order, go the other way.
 *
 * <pre>
 * status()               // 1xx, then endHeaders(), any number of times (optional)
 * status()               // the final status
 * header() ...           // any number of fields: header, longHeader,
 *                        // dateHeader, contentType, contentDisposition
 * endHeaders()           // optional; the header section is otherwise ended by
 *                        // the first bodyContent() or by endMessage()
 * bodyContent()          // zero or more times
 * header() ...           // trailer fields, after the body began (optional)
 * endMessage()           // required
 * </pre>
 *
 * <p>Nothing is sent until the header section is ended, so a response with no
 * body goes out as a single header section that also ends the stream.
 *
 * <h2>Response Patterns</h2>
 *
 * <p><b>Simple response with body:</b>
 * <pre>{@code
 * response.status(200);
 * response.contentType(new ContentType("application", "json", null));
 * response.bodyContent(ByteBuffer.wrap(jsonBytes));
 * response.endMessage();
 * }</pre>
 *
 * <p><b>Response without body (204, 304, redirects):</b>
 * <pre>{@code
 * response.status(204);
 * response.endMessage();
 * }</pre>
 *
 * <p><b>Streaming response:</b>
 * <pre>{@code
 * response.status(200);
 * response.header("content-type", "text/event-stream");
 * response.endHeaders();   // send the header section now, not with the first chunk
 * // Send chunks as data becomes available
 * response.bodyContent(chunk1);
 * response.bodyContent(chunk2);
 * response.endMessage();
 * }</pre>
 *
 * <p><b>Response with trailer fields:</b>
 * <pre>{@code
 * response.status(200);
 * response.bodyContent(data);
 * response.header("x-checksum", checksum);  // after the body began: a trailer
 * response.endMessage();
 * }</pre>
 *
 * <p>Fields that must be known before the content (framing, routing,
 * authentication; RFC 9110 section 6.5.1) cannot be trailers.
 *
 * <h2>HTTP/2 Server Push</h2>
 *
 * <p>For HTTP/2 connections, {@link #startPushPromise} can be used to initiate
 * server push. The pushed request will be processed through the normal
 * factory/handler mechanism.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HttpRequestHandler
 */
public interface HttpResponse {

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
    HttpVersion getVersion();

    /**
     * Returns the URL scheme ("http" or "https").
     *
     * @return the scheme
     */
    String getScheme();

    /**
     * Returns a container-scoped identifier for the underlying connection.
     *
     * @return the connection identifier
     */
    default String getConnectionId() {
        return Integer.toHexString(System.identityHashCode(this));
    }

    /**
     * Returns the protocol-specific stream or request identifier for this
     * exchange, or an empty string when not applicable (e.g. HTTP/1.1).
     *
     * @return the protocol connection identifier, or {@code ""}
     */
    default String getProtocolConnectionId() {
        return "";
    }

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
     * Schedules a one-shot timer callback on this request's own {@link
     * SelectorLoop} thread.
     *
     * @param delayMs the delay in milliseconds
     * @param callback the callback to run after the delay
     * @return a handle that can cancel the timer, or {@code null} if
     *         timers are not supported by this implementation
     */
    default TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return null;
    }

    /**
     * Returns the current trace for distributed tracing, or null if none.
     *
     * <p>When making outbound HTTP calls to other services, pass this trace
     * to {@link org.bluezoo.gumdrop.http.HttpClient#setTrace} so that
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
     * Sets the status of the response, starting a response message. A 1xx
     * status starts an interim response, which is sent by the next
     * {@link #endHeaders()}; the final status follows it. If no final status
     * is given, 200 is used.
     *
     * @param code the status code
     * @throws IllegalStateException if the response has been completed, or
     *     its header section has been sent
     */
    void status(int code);

    /**
     * Adds a field to the response. May be called more than once for a field
     * name; names are case-insensitive. After the body has begun, a field is a
     * trailer field.
     *
     * @param name the field name
     * @param value the field value; ASCII only
     * @throws IllegalStateException if the response has been completed
     * @throws IllegalArgumentException if the value is not ASCII, or this
     *     would be a trailer that may not be one
     */
    void header(String name, String value);

    /**
     * Adds a field whose value is raw octets (ISO-8859-1).
     *
     * @param name the field name
     * @param value the field value
     */
    default void header(String name, ByteBuffer value) {
        byte[] b = new byte[value.remaining()];
        value.duplicate().get(b);
        header(name, new String(b, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    /**
     * Adds a field whose value is an integer, such as {@code Content-Length}.
     *
     * @param name the field name
     * @param value the value
     */
    default void longHeader(String name, long value) {
        header(name, Long.toString(value));
    }

    /**
     * Adds a field whose value is an HTTP-date, such as {@code Last-Modified}.
     *
     * @param name the field name
     * @param value the instant (HTTP-dates are always GMT)
     */
    default void dateHeader(String name, java.time.Instant value) {
        header(name, new org.bluezoo.gumdrop.http.HttpDateFormat().format(value.toEpochMilli()));
    }

    /**
     * Adds a {@code Content-Type} field.
     *
     * @param contentType the content type
     */
    default void contentType(org.bluezoo.gumdrop.mime.ContentType contentType) {
        header("Content-Type", contentType.toHeaderValue());
    }

    /**
     * Adds a {@code Content-Disposition} field.
     *
     * @param contentDisposition the disposition
     */
    default void contentDisposition(org.bluezoo.gumdrop.mime.ContentDisposition contentDisposition) {
        header("Content-Disposition", contentDisposition.toHeaderValue());
    }

    /**
     * Ends the header section. For an interim (1xx) response this sends it
     * and allows the next {@link #status(int)}; otherwise it sends the header
     * section now rather than with the first body chunk or the end of the
     * message, which a streaming response may want. Optional: the first
     * {@link #bodyContent} or {@link #endMessage} ends the section itself.
     *
     * @throws IllegalStateException if the response has been completed
     */
    void endHeaders();

    /**
     * Sends response body data.
     *
     * <p>Can be called multiple times for streaming responses. The buffer
     * contents are sent; the buffer itself is not retained after this call.
     * The first call ends the header section if that has not been done.
     *
     * @param data the body data to send
     * @throws IllegalStateException if the response has been completed
     */
    void bodyContent(ByteBuffer data);

    /**
     * Ends the response. Sends the header section if nothing has been sent
     * yet, then any trailer fields, and ends the stream: END_STREAM for HTTP/2
     * and HTTP/3, the final chunk for chunked HTTP/1.1. Calling it again is
     * a no-op.
     */
    void endMessage();

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
     * {@link #bodyContent(ByteBuffer)} again) from within it.
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
     * Returns the number of response body bytes currently buffered
     * because the transport can't accept more right now (e.g. the
     * HTTP/2 flow-control send window is closed).
     *
     * <p>Producers that write response body faster than the transport
     * can drain it (e.g. a servlet writing a large streaming response)
     * should check this before writing more and, if it's high, wait for
     * {@link #onWritable(Runnable)} — otherwise buffered data can grow
     * without bound while the peer is slow or unresponsive.
     *
     * @return the number of buffered, unsent response body bytes; 0 if
     *      none or not applicable to this transport
     */
    default int pendingResponseBytes() {
        return 0;
    }

    /**
     * Pauses delivery of request body events
     * ({@link HttpRequestHandler#bodyContent}).
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
    // Server Push
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts an HTTP/2 server push: the promised request. Follow it with
     * {@link #header} events (and the other field events) for the promised
     * request's fields, then {@link #endPushPromise()}. The scheme and
     * authority are those of the current request. Nothing else may be called
     * on this response in between.
     *
     * <p>The pushed request will be processed through the normal
     * factory/handler mechanism, creating a new handler for the pushed stream.
     *
     * @param method the promised request method (typically GET or HEAD)
     * @param target the promised request target (path and query)
     */
    void startPushPromise(HttpMethod method, String target);

    /**
     * Ends the promised request and sends the push promise.
     *
     * <p>For HTTP/1.x connections, and where the client has disabled push,
     * nothing is sent and this returns false.
     *
     * @return true if the push was initiated, false if not supported or
     *         disabled by the client
     * @throws IllegalStateException if {@link #startPushPromise} was not called
     */
    boolean endPushPromise();

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP Datagrams (RFC 9297)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends an HTTP Datagram associated with this request (RFC 9297).
     * Over HTTP/3 this is a QUIC DATAGRAM with a Quarter Stream ID
     * prefix when the peer advertised {@code SETTINGS_H3_DATAGRAM=1};
     * over HTTP/1.1 and HTTP/2 (and HTTP/3 Capsule-Protocol streams) it
     * is a DATAGRAM capsule on the data stream.
     *
     * @param data the datagram payload; copied
     * @return true if the datagram was queued
     */
    default boolean sendDatagram(ByteBuffer data) {
        return false;
    }

    /**
     * Sends an HTTP Datagram prefixed with a Context ID (RFC 9298
     * section 5), for MASQUE-style protocols (CONNECT-UDP, CONNECT-IP)
     * that multiplex more than one flow over one request's datagrams --
     * a convenience over calling {@link HttpDatagramContext#encode} and
     * {@link #sendDatagram(ByteBuffer)} directly.
     *
     * @param contextId the Context ID (RFC 9298 section 5); {@link
     *        HttpDatagramContext#REGISTERED_CONTEXT_ID} for the payload
     *        registered to this request itself
     * @param payload the flow's protocol data; copied
     * @return true if the datagram was queued
     */
    default boolean sendDatagram(long contextId, ByteBuffer payload) {
        return sendDatagram(HttpDatagramContext.encode(contextId, payload));
    }

    /**
     * Accepts this request as an RFC 9298 CONNECT-UDP tunnel: sends the
     * success response (a {@code 2xx} for HTTP/2 or HTTP/3 Extended
     * CONNECT, {@code 101 Switching Protocols} for HTTP/1.1 Upgrade) and
     * leaves the request open in both directions rather than completing
     * it, the same shape {@link #upgradeToWebSocket} uses for WebSocket.
     *
     * <p>The caller (typically {@link ConnectUdpRequestHandler}) is
     * responsible for validating the request as CONNECT-UDP (RFC 9298
     * section 3: {@code :method: CONNECT}, {@code :protocol: connect-udp},
     * {@code Capsule-Protocol: ?1}, a path matching the URI Template) and
     * for having a UDP relay ready to receive datagrams via {@link
     * HttpRequestHandler#datagramReceived} before calling this -- unlike
     * {@link #upgradeToWebSocket}, this method does not itself bridge to
     * anything; it only performs the HTTP-level accept.
     *
     * @return true if the request was accepted; false if it was not a
     *         valid CONNECT-UDP request or the response had already started
     */
    default boolean acceptConnectUdp() {
        return false;
    }

    /**
     * Accepts this request as an RFC 9484 CONNECT-IP tunnel: sends the
     * success response (a {@code 2xx} for HTTP/2 or HTTP/3 Extended
     * CONNECT, {@code 101 Switching Protocols} for HTTP/1.1 Upgrade) and
     * leaves the request open in both directions rather than completing
     * it -- the exact same shape {@link #acceptConnectUdp} uses for RFC
     * 9298.
     *
     * <p>The caller (typically {@link ConnectIpRequestHandler}) is
     * responsible for validating the request as CONNECT-IP (RFC 9484
     * section 4: {@code :method: CONNECT}, {@code :protocol: connect-ip},
     * {@code Capsule-Protocol: ?1}, a path matching the URI Template) and
     * for having an {@link IpPacketHandler} ready to receive packets via
     * {@link HttpRequestHandler#datagramReceived} before calling this --
     * like {@link #acceptConnectUdp}, this method does not itself bridge
     * to anything; it only performs the HTTP-level accept.
     *
     * @return true if the request was accepted; false if it was not a
     *         valid CONNECT-IP request or the response had already started
     */
    default boolean acceptConnectIp() {
        return false;
    }

    /**
     * Sends a Capsule Protocol capsule on this stream's data (RFC 9297
     * section 3.2). Use {@link #sendDatagram} for DATAGRAM capsules
     * unless a different type is required.
     *
     * @param type the Capsule Type
     * @param value the Capsule Value; copied
     * @return true if the capsule was queued
     */
    default boolean sendCapsule(long type, ByteBuffer value) {
        return false;
    }

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
     * public void headers(HttpResponse state, Headers headers) {
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

