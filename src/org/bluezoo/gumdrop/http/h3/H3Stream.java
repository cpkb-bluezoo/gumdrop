/*
 * H3Stream.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.http.h3;

import java.nio.ByteBuffer;
import java.security.Principal;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.io.IOException;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.websocket.WebSocketConnection;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketServerMetrics;
import org.bluezoo.gumdrop.websocket.WebSocketSession;
import org.bluezoo.gumdrop.http.HTTPAuthenticationProvider;
import org.bluezoo.gumdrop.http.HTTPPrincipal;
import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPResponseState;
import org.bluezoo.gumdrop.http.HTTPServerMetrics;
import org.bluezoo.gumdrop.http.HTTPVersion;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.GumdropNative;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.util.ByteBufferPool;

/**
 * A single HTTP/3 request/response exchange on a QUIC stream.
 *
 * <p>This is the HTTP/3 equivalent of the HTTP/2 {@code Stream} class.
 * Each instance manages one request/response lifecycle (RFC 9114
 * section 4.1) and implements {@link HTTPResponseState} so that
 * {@link HTTPRequestHandler} implementations can send responses
 * identically to HTTP/2.
 *
 * <p>HTTP/3 framing (HEADERS, DATA, GOAWAY) is handled by the quiche
 * h3 module via JNI. This class translates h3 events into the
 * {@link HTTPRequestHandler} event sequence and converts
 * {@code HTTPResponseState} calls into h3 send operations.
 * Connection-specific headers are stripped per RFC 9114 section 4.2.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTP3ServerHandler
 * @see HTTPRequestHandler
 */
class H3Stream implements HTTPResponseState {

    private static final Logger LOGGER =
            Logger.getLogger(H3Stream.class.getName());

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");

    /** Reusable empty buffer for FIN-only sends. */
    private static final ByteBuffer EMPTY_BUFFER =
            ByteBuffer.allocate(0).asReadOnlyBuffer();

    /**
     * Stream lifecycle states. Maps to the HTTP/3 request/response
     * lifecycle in RFC 9114 section 4.1: a client sends HEADERS
     * (optionally followed by DATA), then FIN; the server sends
     * HEADERS (optionally followed by DATA), then FIN.
     */
    enum State {
        /** Waiting for initial HEADERS event. */
        IDLE,
        /** Request headers received, awaiting body or completion. */
        OPEN,
        /** Request body is being received. */
        RECEIVING_BODY,
        /** Request complete (FIN received). */
        HALF_CLOSED_REMOTE,
        /** Response complete (FIN sent). */
        CLOSED
    }

    private final HTTP3ServerHandler connection;
    private final long streamId;

    private State state;
    private HTTPRequestHandler handler;
    private Headers requestHeaders;
    private String method;
    private String requestTarget;
    private String protocol;
    private Principal authenticatedPrincipal;
    private boolean bodyStarted;
    private boolean responseStarted;
    private boolean responseBodyStarted;
    private List<Header> pendingResponseHeaders;

    private H3WebSocketConnectionAdapter webSocketAdapter;

    private boolean readPaused;

    private List<ByteBuffer> pendingWriteQueue;
    private boolean pendingFin;

    private Span span;
    private long timestampStarted;
    private int responseStatusCode;
    private long responseBodyBytes;

    H3Stream(HTTP3ServerHandler connection, long streamId) {
        this.connection = connection;
        this.streamId = streamId;
        this.state = State.IDLE;
    }

    /**
     * Returns the QUIC stream ID for this HTTP/3 stream.
     */
    long getStreamId() {
        return streamId;
    }

    // ── Event Dispatch (called by HTTP3ServerHandler) ──

    /**
     * Called when an h3 HEADERS event is received for this stream.
     * The headers are provided as a flat array of alternating name/value
     * pairs including pseudo-headers (RFC 9114 section 4.3.1).
     *
     * <p>For requests, RFC 9114 section 4.3.1 requires the pseudo-headers
     * {@code :method}, {@code :scheme}, and {@code :path} (or
     * {@code :authority} for CONNECT). Quiche validates pseudo-header
     * presence at the h3 layer.
     */
    void onHeaders(String[] headerPairs) {
        Headers headers = new Headers();
        for (int i = 0; i < headerPairs.length; i += 2) {
            headers.add(new Header(headerPairs[i], headerPairs[i + 1]));
        }

        if (state == State.IDLE) {
            state = State.OPEN;
            requestHeaders = headers;
            method = headers.getValue(":method");
            requestTarget = headers.getValue(":path");
            protocol = headers.getValue(":protocol");

            // RFC 9114 section 4.1.2 / 4.3.1: validate mandatory
            // pseudo-headers. CONNECT omits :scheme and :path.
            if (method == null
                    || (!"CONNECT".equals(method)
                        && (headers.getValue(":scheme") == null
                            || requestTarget == null))) {
                LOGGER.warning("Malformed request: missing required "
                        + "pseudo-headers on stream " + streamId);
                sendErrorResponse(400);
                return;
            }

            HTTPAuthenticationProvider authProvider =
                    connection.getAuthenticationProvider();
            if (authProvider != null) {
                String authHeader =
                        headers.getValue("authorization");
                if (authHeader != null) {
                    HTTPAuthenticationProvider.AuthenticationResult
                            result = authProvider.authenticate(
                                    authHeader);
                    if (result.success) {
                        authenticatedPrincipal =
                                new HTTPPrincipal(result.username);
                    }
                }
            }

            handler = connection.createHandler(this, headers);
            if (handler == null) {
                cancel();
                return;
            }

            initTelemetrySpan();
            handler.headers(this, headers);
        } else if (state == State.RECEIVING_BODY ||
                   state == State.HALF_CLOSED_REMOTE) {
            if (handler != null) {
                handler.headers(this, headers);
            }
        }
    }

    /**
     * Called when an h3 DATA event is received for this stream.
     * The actual body data must be read via {@code quiche_h3_recv_body}.
     */
    void onData(ByteBuffer data) {
        if (state == State.OPEN) {
            state = State.RECEIVING_BODY;
            if (!bodyStarted && handler != null) {
                bodyStarted = true;
                handler.startRequestBody(this);
            }
        }
        if (handler != null) {
            handler.requestBodyContent(this, data);
        }
    }

    /**
     * Called when an h3 FINISHED event is received (stream closed by peer).
     */
    void onFinished() {
        if (state == State.RECEIVING_BODY) {
            if (handler != null) {
                handler.endRequestBody(this);
            }
        }
        state = State.HALF_CLOSED_REMOTE;
        if (handler != null) {
            handler.requestComplete(this);
        }
    }

    /**
     * Called when an h3 RESET event is received (stream aborted by peer).
     * Per RFC 9114 section 8, stream errors are signalled via QUIC
     * RESET_STREAM with an HTTP/3 error code.
     */
    void onReset() {
        if (span != null && !span.isEnded()) {
            span.recordError(ErrorCategory.CONNECTION_LOST,
                    L10N.getString("telemetry.stream_closed_abnormally"));
            span.end();
        }
        state = State.CLOSED;
        handler = null;
    }

    // ── HTTPResponseState Implementation ──

    @Override
    public java.net.SocketAddress getRemoteAddress() {
        return connection.getRemoteAddress();
    }

    @Override
    public java.net.SocketAddress getLocalAddress() {
        return connection.getLocalAddress();
    }

    @Override
    public boolean isSecure() {
        return true;
    }

    @Override
    public SecurityInfo getSecurityInfo() {
        return connection.getSecurityInfo();
    }

    @Override
    public HTTPVersion getVersion() {
        return HTTPVersion.HTTP_3;
    }

    @Override
    public String getScheme() {
        return "https";
    }

    @Override
    public SelectorLoop getSelectorLoop() {
        return connection.getSelectorLoop();
    }

    @Override
    public Principal getPrincipal() {
        return authenticatedPrincipal;
    }

    @Override
    public void sendInformational(int statusCode, Headers headers) {
        if (statusCode < 100 || statusCode > 199) {
            throw new IllegalArgumentException(
                    "Status code must be 1xx: " + statusCode);
        }
        if (responseBodyStarted) {
            throw new IllegalStateException(
                    "Cannot send informational response after body started");
        }

        List<Header> infoHeaders = new ArrayList<>();
        infoHeaders.add(new Header(":status", String.valueOf(statusCode)));
        for (int i = 0; i < headers.size(); i++) {
            Header h = headers.get(i);
            String name = h.getName();
            if ("Connection".equalsIgnoreCase(name)
                    || "Keep-Alive".equalsIgnoreCase(name)
                    || "Transfer-Encoding".equalsIgnoreCase(name)) {
                continue;
            }
            infoHeaders.add(h);
        }

        String[] headerArray = new String[infoHeaders.size() * 2];
        for (int i = 0; i < infoHeaders.size(); i++) {
            Header h = infoHeaders.get(i);
            headerArray[i * 2] = h.getName();
            headerArray[i * 2 + 1] = h.getValue();
        }

        long h3Conn = connection.getH3Conn();
        long quicheConn = connection.getQuicheConn();
        int result;
        if (!responseStarted) {
            result = GumdropNative.quiche_h3_send_response(
                    h3Conn, quicheConn, streamId, headerArray, false);
        } else {
            result = GumdropNative.quiche_h3_send_additional_headers(
                    h3Conn, quicheConn, streamId, headerArray, false, false);
        }
        if (result < 0) {
            LOGGER.log(Level.WARNING,
                    "h3 send informational failed: " + result
                            + " stream=" + streamId);
        }
        responseStarted = true;
        connection.flushQuic();
    }

    @Override
    public void headers(Headers headers) {
        if (pendingResponseHeaders == null) {
            pendingResponseHeaders = new ArrayList<Header>();
        }
        for (int i = 0; i < headers.size(); i++) {
            pendingResponseHeaders.add(headers.get(i));
        }
    }

    @Override
    public void startResponseBody() {
        flushHeaders(false);
        responseBodyStarted = true;
    }

    @Override
    public void responseBodyContent(ByteBuffer data) {
        if (!responseStarted) {
            flushHeaders(false);
            responseBodyStarted = true;
        }
        responseBodyBytes += data.remaining();
        sendBody(data, false);
    }

    @Override
    public void endResponseBody() {
        // Nothing to send here; FIN is sent with complete()
    }

    @Override
    public void complete() {
        if (!responseStarted) {
            flushHeaders(true);
        } else if (responseBodyStarted) {
            sendBody(EMPTY_BUFFER.duplicate(), true);
        }
        state = State.CLOSED;
        if (!hasPendingWrites()) {
            endTelemetrySpan(responseStatusCode);
        }
    }

    /**
     * RFC 9114 section 4.6 — server push. HTTP/3 server push uses
     * PUSH_PROMISE frames on the request stream plus a unidirectional
     * push stream. Push is rarely used in practice and many clients
     * disable it. This implementation declines all push requests.
     */
    @Override
    public boolean pushPromise(Headers headers) {
        return false;
    }

    /**
     * RFC 9220 section 3 — WebSocket over HTTP/3 via Extended CONNECT.
     * Validates the request, sends a 200 OK response (no
     * {@code Sec-WebSocket-Key} exchange — HTTP/3 integrity is provided
     * by TLS), and bridges the H3 stream to a {@link WebSocketConnection}.
     */
    @Override
    public void upgradeToWebSocket(String subprotocol,
            WebSocketEventHandler wsHandler) {
        upgradeToWebSocketInternal(subprotocol, null, wsHandler);
    }

    @Override
    public void upgradeToWebSocket(String subprotocol,
            List<WebSocketExtension> extensions,
            WebSocketEventHandler wsHandler) {
        upgradeToWebSocketInternal(subprotocol, extensions, wsHandler);
    }

    private void upgradeToWebSocketInternal(String subprotocol,
            List<WebSocketExtension> extensions,
            WebSocketEventHandler wsHandler) {
        if (!"CONNECT".equals(method)
                || !"websocket".equalsIgnoreCase(protocol)) {
            throw new IllegalStateException(
                    "Not an Extended CONNECT with :protocol websocket");
        }
        if (responseStarted) {
            throw new IllegalStateException(
                    "Response already started");
        }

        // RFC 9220 section 4 — send 200 OK to accept the upgrade.
        // Include negotiated subprotocol and extensions as regular
        // headers (RFC 9220 section 3).
        pendingResponseHeaders = new ArrayList<Header>();
        pendingResponseHeaders.add(new Header(":status", "200"));
        if (subprotocol != null && !subprotocol.isEmpty()) {
            pendingResponseHeaders.add(
                    new Header("sec-websocket-protocol", subprotocol));
        }
        if (extensions != null && !extensions.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < extensions.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(extensions.get(i).getName());
                java.util.Map<String, String> extParams =
                        extensions.get(i).generateOffer();
                if (extParams != null) {
                    for (java.util.Map.Entry<String, String> ep
                            : extParams.entrySet()) {
                        sb.append("; ").append(ep.getKey());
                        if (ep.getValue() != null) {
                            sb.append("=").append(ep.getValue());
                        }
                    }
                }
            }
            pendingResponseHeaders.add(
                    new Header("sec-websocket-extensions", sb.toString()));
        }
        flushHeaders(false);

        WebSocketServerMetrics wsMetrics =
                connection.getWebSocketMetrics();

        webSocketAdapter = new H3WebSocketConnectionAdapter(
                wsHandler, wsMetrics);
        webSocketAdapter.setTransport(new H3WebSocketTransport());
        if (extensions != null && !extensions.isEmpty()) {
            webSocketAdapter.setExtensions(extensions);
        }

        if (connection.isTelemetryEnabled()) {
            webSocketAdapter.setTelemetryConfig(
                    connection.getTelemetryConfig());
            if (span != null) {
                webSocketAdapter.setParentSpan(span);
            } else {
                webSocketAdapter.createSpan(null);
            }
        }

        webSocketAdapter.notifyConnectionOpen();
    }

    /**
     * Returns true if this stream has been upgraded to WebSocket mode.
     */
    boolean isWebSocketUpgraded() {
        return webSocketAdapter != null;
    }

    /**
     * Routes incoming body data to the WebSocket frame parser when the
     * stream is in WebSocket mode.
     */
    void onWebSocketData(ByteBuffer data) {
        if (webSocketAdapter == null) {
            return;
        }
        try {
            webSocketAdapter.processIncomingData(data);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "WebSocket frame processing error on stream "
                            + streamId, e);
            webSocketAdapter.notifyError(e);
        }
    }

    /**
     * Called when the peer sends FIN on a WebSocket-upgraded stream,
     * indicating that the transport has been closed.
     */
    void onWebSocketFinished() {
        if (webSocketAdapter != null) {
            try {
                webSocketAdapter.processIncomingData(EMPTY_BUFFER.duplicate());
            } catch (IOException ignored) {
                // FIN with empty data
            }
            webSocketAdapter.notifyTransportClosed();
        }
        state = State.CLOSED;
    }

    // ── WebSocket adapter inner classes ──

    /**
     * Bridges {@link WebSocketEventHandler} to the {@link WebSocketConnection}
     * abstract class. Modelled on {@code Stream.WebSocketConnectionAdapter}.
     */
    private class H3WebSocketConnectionAdapter extends WebSocketConnection
            implements WebSocketSession {

        private final WebSocketEventHandler wsHandler;
        private final WebSocketServerMetrics wsMetrics;
        private long openedAtNanos;

        H3WebSocketConnectionAdapter(WebSocketEventHandler wsHandler,
                WebSocketServerMetrics wsMetrics) {
            this.wsHandler = wsHandler;
            this.wsMetrics = wsMetrics;
            setServerMetrics(wsMetrics);
        }

        @Override
        protected void opened() {
            openedAtNanos = System.nanoTime();
            if (wsMetrics != null) {
                wsMetrics.connectionOpened();
            }
            wsHandler.opened(this);
        }

        @Override
        protected void textMessageReceived(String message) {
            if (wsMetrics != null) {
                wsMetrics.textMessageReceived();
            }
            wsHandler.textMessageReceived(this, message);
        }

        @Override
        protected void binaryMessageReceived(ByteBuffer data) {
            if (wsMetrics != null) {
                wsMetrics.binaryMessageReceived();
            }
            wsHandler.binaryMessageReceived(this, data);
        }

        @Override
        protected void closed(int code, String reason) {
            if (wsMetrics != null) {
                double durationMs =
                        (System.nanoTime() - openedAtNanos) / 1_000_000.0;
                wsMetrics.connectionClosed(durationMs, code);
            }
            wsHandler.closed(code, reason);
        }

        @Override
        protected void error(Throwable cause) {
            if (wsMetrics != null) {
                wsMetrics.error();
            }
            wsHandler.error(cause);
        }

        @Override
        public Principal getPrincipal() {
            return authenticatedPrincipal;
        }

        void notifyError(Throwable cause) {
            error(cause);
        }

        void notifyTransportClosed() {
            if (isOpen()) {
                try {
                    close(1001, "Transport closed");
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /**
     * {@link WebSocketConnection.WebSocketTransport} that sends WebSocket
     * frames as HTTP/3 DATA frames on this stream.
     */
    private class H3WebSocketTransport
            implements WebSocketConnection.WebSocketTransport {

        @Override
        public void sendFrame(ByteBuffer frameData) throws IOException {
            if (state == State.CLOSED) {
                throw new IOException("Stream closed");
            }
            responseBodyBytes += frameData.remaining();
            sendBody(frameData, false);
        }

        @Override
        public void close(boolean normalClose) throws IOException {
            if (state != State.CLOSED) {
                sendBody(EMPTY_BUFFER.duplicate(), true);
                state = State.CLOSED;
                endTelemetrySpan(200);
            }
        }
    }

    // ── Backpressure / flow control ──

    private Runnable writableCallback;

    /**
     * Named callback that dispatches a one-shot write-readiness
     * notification from the QUIC layer up to the handler.
     * Invoked by {@link #resumeWrite()} when buffered data has
     * been fully drained after a congestion window opening.
     */
    private class WriteReadyDispatcher implements Runnable {
        @Override
        public void run() {
            Runnable cb = writableCallback;
            writableCallback = null;
            if (cb != null) {
                cb.run();
            }
        }
    }

    private final WriteReadyDispatcher writeReadyDispatcher =
            new WriteReadyDispatcher();

    @Override
    public void execute(Runnable task) {
        connection.getSelectorLoop().invokeLater(task);
    }

    @Override
    public void onWritable(Runnable callback) {
        this.writableCallback = callback;
    }

    @Override
    public void pauseRequestBody() {
        readPaused = true;
    }

    @Override
    public void resumeRequestBody() {
        if (readPaused) {
            readPaused = false;
            connection.resumeStreamRead(this);
        }
    }

    /**
     * Returns true if request body consumption is paused.
     * When paused, the connection should not call
     * {@code quiche_h3_recv_body} for this stream, allowing
     * the peer's flow control window to fill naturally.
     */
    boolean isReadPaused() {
        return readPaused;
    }

    /**
     * Cancels this stream. Per RFC 9114 section 8, the server may
     * reset a stream with H3_REQUEST_CANCELLED (0x10c) if the request
     * is no longer needed.
     */
    @Override
    public void cancel() {
        if (span != null && !span.isEnded()) {
            span.recordError(ErrorCategory.INTERNAL_ERROR, "Request cancelled");
            span.end();
        }
        state = State.CLOSED;
        handler = null;
    }

    // ── Telemetry ──

    /**
     * Initialises a telemetry span for this request if tracing is
     * enabled. Called once when the initial HEADERS event arrives.
     */
    private void initTelemetrySpan() {
        timestampStarted = System.currentTimeMillis();

        HTTPServerMetrics metrics = connection.getMetrics();
        if (metrics != null) {
            metrics.requestStarted(
                    method != null ? method : "UNKNOWN");
        }

        if (!connection.isTelemetryEnabled()) {
            return;
        }

        TelemetryConfig telemetryConfig =
                connection.getTelemetryConfig();
        Trace trace = connection.getTrace();

        String traceparent = requestHeaders != null
                ? requestHeaders.getValue("traceparent") : null;

        String methodName = method != null ? method : "UNKNOWN";
        String spanName = MessageFormat.format(
                L10N.getString("telemetry.http_request"), methodName);

        if (traceparent != null) {
            trace = telemetryConfig.createTraceFromTraceparent(
                    traceparent, spanName, SpanKind.SERVER);
            connection.setTrace(trace);
        } else if (trace == null) {
            trace = telemetryConfig.createTrace(
                    spanName, SpanKind.SERVER);
            connection.setTrace(trace);
        }

        if (trace != null) {
            span = trace.startSpan(spanName, SpanKind.SERVER);

            if (method != null) {
                span.addAttribute("http.method", method);
            }
            if (requestTarget != null) {
                span.addAttribute("http.target", requestTarget);
            }
            span.addAttribute("http.scheme", "https");
            span.addAttribute("http.flavor", "3");
            span.addAttribute("net.transport", "quic");
            span.addAttribute("net.peer.ip",
                    connection.getRemoteAddress().toString());

            String host = requestHeaders != null
                    ? requestHeaders.getValue(":authority") : null;
            if (host != null) {
                span.addAttribute("http.host", host);
            }
            String userAgent = requestHeaders != null
                    ? requestHeaders.getValue("user-agent") : null;
            if (userAgent != null) {
                span.addAttribute("http.user_agent", userAgent);
            }
        }
    }

    /**
     * Ends the telemetry span for this request with the given status
     * code. Also records metrics for request completion.
     *
     * @param statusCode the HTTP response status code
     */
    private void endTelemetrySpan(int statusCode) {
        HTTPServerMetrics metrics = connection.getMetrics();
        if (metrics != null && timestampStarted > 0) {
            double durationMs =
                    System.currentTimeMillis() - timestampStarted;
            metrics.requestCompleted(
                    method != null ? method : "UNKNOWN",
                    statusCode, durationMs, 0, responseBodyBytes);
        }

        if (span == null) {
            return;
        }

        span.addAttribute("http.status_code", statusCode);

        if (statusCode >= 400) {
            ErrorCategory category =
                    ErrorCategory.fromHttpStatus(statusCode);
            if (category != null) {
                span.recordError(category, statusCode,
                        "HTTP " + statusCode);
            } else {
                span.setStatusError("HTTP " + statusCode);
            }
        } else {
            span.setStatusOk();
        }

        span.end();
    }

    // ── Internal ──

    /**
     * Sends a minimal error response (e.g. 400) and closes the stream.
     * Used when the request is malformed before a handler is created.
     */
    private void sendErrorResponse(int statusCode) {
        pendingResponseHeaders = new ArrayList<Header>();
        pendingResponseHeaders.add(
                new Header(":status", Integer.toString(statusCode)));
        pendingResponseHeaders.add(
                new Header("content-length", "0"));
        flushHeaders(true);
        state = State.CLOSED;
        handler = null;
    }

    /**
     * Flushes pending response headers to the h3 connection.
     * Strips connection-specific headers per RFC 9114 section 4.2
     * before sending.
     *
     * @param fin true to include FIN (no body will follow)
     */
    private void flushHeaders(boolean fin) {
        if (pendingResponseHeaders == null
                || pendingResponseHeaders.isEmpty()) {
            return;
        }

        // Capture response status code from :status pseudo-header
        for (int i = 0; i < pendingResponseHeaders.size(); i++) {
            Header h = pendingResponseHeaders.get(i);
            if (":status".equals(h.getName())) {
                try {
                    responseStatusCode =
                            Integer.parseInt(h.getValue());
                } catch (NumberFormatException ignored) {
                    // leave as 0
                }
                break;
            }
        }

        // Strip headers that are illegal in HTTP/3
        // (RFC 9114 Section 4.2)
        Iterator<Header> it = pendingResponseHeaders.iterator();
        while (it.hasNext()) {
            String name = it.next().getName();
            if ("Connection".equalsIgnoreCase(name)
                    || "Keep-Alive".equalsIgnoreCase(name)
                    || "Proxy-Connection".equalsIgnoreCase(name)
                    || "Transfer-Encoding".equalsIgnoreCase(name)
                    || "Upgrade".equalsIgnoreCase(name)) {
                it.remove();
            }
        }

        // Inject traceparent for distributed trace propagation
        if (span != null) {
            pendingResponseHeaders.add(new Header("traceparent",
                    span.getSpanContext().toTraceparent()));
        }

        String[] headerArray =
                new String[pendingResponseHeaders.size() * 2];
        for (int i = 0; i < pendingResponseHeaders.size(); i++) {
            Header h = pendingResponseHeaders.get(i);
            headerArray[i * 2] = h.getName();
            headerArray[i * 2 + 1] = h.getValue();
        }
        pendingResponseHeaders.clear();

        long h3Conn = connection.getH3Conn();
        long quicheConn = connection.getQuicheConn();
        int result;
        if (!responseStarted) {
            result = GumdropNative.quiche_h3_send_response(
                    h3Conn, quicheConn, streamId, headerArray, fin);
        } else {
            boolean isTrailer = responseBodyStarted;
            result = GumdropNative.quiche_h3_send_additional_headers(
                    h3Conn, quicheConn, streamId, headerArray,
                    isTrailer, fin);
        }
        if (result < 0) {
            LOGGER.log(Level.WARNING,
                    "h3 send headers failed: " + result
                            + " stream=" + streamId);
        }

        responseStarted = true;
        connection.flushQuic();
    }

    /**
     * Returns true if this stream has buffered data waiting for the
     * congestion window to open.
     */
    boolean hasPendingWrites() {
        return (pendingWriteQueue != null && !pendingWriteQueue.isEmpty())
                || pendingFin;
    }

    /**
     * Resumes sending buffered data after the congestion window has
     * opened (ACKs received).  Called by {@link HTTP3ServerHandler}
     * after processing incoming packets.
     *
     * @return true if all pending data has been drained
     */
    boolean resumeWrite() {
        long h3Conn = connection.getH3Conn();
        long quicheConn = connection.getQuicheConn();

        while (pendingWriteQueue != null
                && !pendingWriteQueue.isEmpty()) {
            ByteBuffer data = pendingWriteQueue.get(0);
            while (data.hasRemaining()) {
                int result = GumdropNative.quiche_h3_send_body(
                        h3Conn, quicheConn, streamId,
                        data, data.remaining(), false);
                if (result > 0) {
                    data.position(data.position() + result);
                    connection.flushQuic();
                } else if (result == GumdropNative.QUICHE_ERR_DONE) {
                    connection.flushQuic();
                    return false;
                } else {
                    LOGGER.warning("h3 send_body error: " + result
                            + " stream=" + streamId);
                    for (ByteBuffer b : pendingWriteQueue) {
                        ByteBufferPool.release(b);
                    }
                    pendingWriteQueue.clear();
                    pendingFin = false;
                    return true;
                }
            }
            ByteBufferPool.release(pendingWriteQueue.remove(0));
        }

        if (pendingFin) {
            int result = GumdropNative.quiche_h3_send_body(
                    h3Conn, quicheConn, streamId,
                    EMPTY_BUFFER.duplicate(), 0, true);
            if (result < 0 && result != GumdropNative.QUICHE_ERR_DONE) {
                LOGGER.warning("h3 send_body FIN error: " + result
                        + " stream=" + streamId);
            }
            pendingFin = false;
            connection.flushQuic();
            endTelemetrySpan(responseStatusCode);
        }

        // Notify write-readiness callback now that all pending data
        // has been drained.
        if (writableCallback != null) {
            writeReadyDispatcher.run();
        }

        return true;
    }

    /**
     * Sends response body data on this stream.  Sends as much as the
     * QUIC congestion window allows; any remainder is buffered and
     * will be drained by {@link #resumeWrite()} when ACKs arrive.
     */
    private void sendBody(ByteBuffer data, boolean fin) {
        if (hasPendingWrites()) {
            enqueue(data, fin);
            return;
        }

        long h3Conn = connection.getH3Conn();
        long quicheConn = connection.getQuicheConn();

        while (data.hasRemaining()) {
            int result = GumdropNative.quiche_h3_send_body(
                    h3Conn, quicheConn, streamId,
                    data, data.remaining(), false);
            if (result > 0) {
                data.position(data.position() + result);
                connection.flushQuic();
            } else if (result == GumdropNative.QUICHE_ERR_DONE) {
                connection.flushQuic();
                enqueue(data, fin);
                return;
            } else {
                LOGGER.warning("h3 send_body error: " + result
                        + " stream=" + streamId);
                return;
            }
        }

        if (fin) {
            int result = GumdropNative.quiche_h3_send_body(
                    h3Conn, quicheConn, streamId, data, 0, true);
            if (result < 0 && result != GumdropNative.QUICHE_ERR_DONE) {
                LOGGER.warning("h3 send_body FIN error: " + result
                        + " stream=" + streamId);
            }
        }
        connection.flushQuic();
    }

    /**
     * Buffers remaining data for deferred sending and registers this
     * stream for write resumption.
     */
    private void enqueue(ByteBuffer data, boolean fin) {
        if (pendingWriteQueue == null) {
            pendingWriteQueue = new ArrayList<ByteBuffer>();
        }
        if (data.hasRemaining()) {
            ByteBuffer copy = ByteBufferPool.acquire(data.remaining());
            copy.put(data);
            copy.flip();
            pendingWriteQueue.add(copy);
        }
        if (fin) {
            pendingFin = true;
        }
        connection.registerPendingWrite(this);
    }
}
