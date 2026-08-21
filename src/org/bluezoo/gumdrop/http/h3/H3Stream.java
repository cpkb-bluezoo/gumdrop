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

import java.io.IOException;
import java.net.ProtocolException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.quic.QuicConnectionCloseException;
import org.bluezoo.gumdrop.quic.QuicStreamEndpoint;
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
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.qpack.Encoder;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * A single HTTP/3 request/response exchange on a QUIC stream.
 *
 * <p>This is the HTTP/3 equivalent of the HTTP/2 {@code Stream} class.
 * Each instance manages one request/response lifecycle (RFC 9114
 * section 4.1) and implements {@link HTTPResponseState} so that
 * {@link HTTPRequestHandler} implementations can send responses
 * identically to HTTP/2.
 *
 * <p>Unlike the previous quiche-backed implementation, this class is
 * itself the QUIC stream's {@link ProtocolHandler} and {@link H3FrameHandler}
 * -- it owns its own {@link H3Parser}, fed directly from {@link #receive},
 * and decodes/encodes header blocks itself via the connection-shared
 * {@link Decoder}/{@link Encoder} (RFC 9204's full dynamic-table QPACK
 * codec); any resulting encoder/decoder-stream instructions are flushed
 * back through {@link HTTP3ServerHandler}, which owns the actual QPACK
 * stream endpoints. Response-body flow-control buffering, which the quiche-backed version
 * duplicated per stream ({@code pendingWriteQueue}/{@code resumeWrite}),
 * is gone entirely -- {@link Endpoint#send} now buffers and paces that
 * itself, the same as every other protocol running over QUIC.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see HTTP3ServerHandler
 * @see HTTPRequestHandler
 */
class H3Stream implements ProtocolHandler, H3FrameHandler, HTTPResponseState {

    private static final Logger LOGGER = Logger.getLogger(H3Stream.class.getName());

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.h3.L10N");
    private static final ResourceBundle HTTP_L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");

    /** Reusable empty buffer for FIN-only sends. */
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0).asReadOnlyBuffer();

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
    private final H3Parser parser = new H3Parser(this);
    private final Encoder qpackEncoder;
    private final Decoder qpackDecoder;
    private QuicStreamEndpoint endpoint;
    // Mirrors endpoint.getStreamId(), captured once in connected() so
    // QPACK bookkeeping doesn't depend on endpoint being non-null (unit
    // tests construct this class directly without ever calling
    // connected() -- see H3StreamTest).
    private long streamId;

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

    // RFC 9204 section 4.4.2: whether this stream's request field
    // section was ever successfully decoded -- if not, and the stream
    // ends anyway (reset, or the connection going away), the peer
    // encoder must be told via cancelQpackStream so it releases any
    // table references it made for this stream; otherwise they leak
    // for the rest of the connection.
    private boolean headersDecoded;

    private H3WebSocketConnectionAdapter webSocketAdapter;

    private Span span;
    private long timestampStarted;
    private int responseStatusCode;
    private long responseBodyBytes;

    H3Stream(HTTP3ServerHandler connection, Encoder qpackEncoder, Decoder qpackDecoder) {
        this.connection = connection;
        this.qpackEncoder = qpackEncoder;
        this.qpackDecoder = qpackDecoder;
        this.state = State.IDLE;
    }

    private static boolean containsHeader(List<Header> headers, String name) {
        for (Header h : headers) {
            if (name.equalsIgnoreCase(h.getName())) {
                return true;
            }
        }
        return false;
    }

    // ── ProtocolHandler ──

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = (QuicStreamEndpoint) endpoint;
        this.streamId = this.endpoint.getStreamId();
    }

    @Override
    public void receive(ByteBuffer data) {
        parser.receive(data);
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
    }

    @Override
    public void disconnected() {
        // connection is only ever null when a test constructs this class
        // directly without going through HTTP3ServerHandler (see
        // H3StreamTest) -- never in production.
        if (!headersDecoded && connection != null) {
            connection.cancelQpackStream(streamId);
        }
        // The QUIC layer delivers both a clean FIN and a peer
        // RESET_STREAM through this same callback (see QuicConnection);
        // there is no way from here to tell which one this was, so both
        // are treated as a normal finish -- a known simplification.
        if (isWebSocketUpgraded()) {
            onWebSocketFinished();
        } else {
            onFinished();
        }
    }

    @Override
    public void error(Exception cause) {
        if (!headersDecoded && connection != null) {
            connection.cancelQpackStream(streamId);
        }
        if (span != null && !span.isEnded()) {
            span.recordError(ErrorCategory.CONNECTION_LOST,
                    HTTP_L10N.getString("telemetry.stream_closed_abnormally"));
            span.end();
        }
        if (isWebSocketUpgraded()) {
            if (cause instanceof QuicConnectionCloseException) {
                // RFC 6455 section 7.4: 1006 is reserved for "the connection
                // was closed abnormally, e.g. without a Close frame being
                // sent" -- exactly this case. The real QUIC/H3 error code
                // and reason go into the reason string.
                webSocketAdapter.notifyTransportClosed(1006, cause.getMessage());
            } else {
                webSocketAdapter.notifyError(cause);
            }
        } else if (handler != null) {
            handler.failed(this, cause);
        }
        state = State.CLOSED;
        handler = null;
    }

    // ── H3FrameHandler ──

    /**
     * Called when a complete HEADERS frame is received (RFC 9114
     * section 7.2.2), decoded via QPACK into name/value pairs including
     * pseudo-headers (RFC 9114 section 4.3.1).
     */
    @Override
    public void headersFrameReceived(ByteBuffer encodedFieldSection) {
        List<Header> fields;
        try {
            fields = qpackDecoder.decode(streamId, encodedFieldSection);
        } catch (ProtocolException e) {
            // Treated as this stream's own malformed HEADERS -- cancelling
            // just this stream, rather than tearing down the whole
            // connection, is a deliberate simplification: a real peer
            // encoder could in principle desynchronize the shared dynamic
            // table in a way that surfaces here, but gumdrop has no way
            // to distinguish that from an ordinary malformed field
            // section from this exception alone.
            LOGGER.log(Level.WARNING, L10N.getString("warn.qpack_decode_failed"), e);
            cancel();
            return;
        }
        headersDecoded = true;
        Headers headers = new Headers();
        for (Header field : fields) {
            headers.add(field);
        }
        onHeaders(headers);
        // connection is only ever null in a test that constructs this
        // class directly (see H3StreamTest); deferred until after
        // onHeaders() so it never runs ahead of the pre-existing
        // request-validation logic there.
        if (connection != null) {
            connection.flushQpackDecoderInstructions();
        }
    }

    private void onHeaders(Headers headers) {
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
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.malformed_request_missing_pseudo_headers"), connection.getRemoteAddress()));
                sendErrorResponse(400);
                return;
            }

            HTTPVersion.stripHttp1FramingHeaders(headers);

            HTTPAuthenticationProvider authProvider = connection.getAuthenticationProvider();
            if (authProvider != null) {
                String authHeader = headers.getValue("authorization");
                HTTPAuthenticationProvider.AuthenticationResult result =
                        authProvider.authenticate(authHeader, method, requestTarget);
                if (result.success) {
                    authenticatedPrincipal = new HTTPPrincipal(result.username);
                } else if (authProvider.isAuthenticationRequired()) {
                    sendUnauthorized(authProvider);
                    return;
                }
            }

            handler = connection.createHandler(this, headers);
            if (handler == null) {
                cancel();
                return;
            }

            initTelemetrySpan();
            handler.headers(this, headers);
        } else if (state == State.RECEIVING_BODY || state == State.HALF_CLOSED_REMOTE) {
            if (handler != null) {
                handler.headers(this, headers);
            }
        }
    }

    @Override
    public void dataFrameReceived(ByteBuffer data, boolean endOfFrame) {
        if (isWebSocketUpgraded()) {
            onWebSocketData(data);
            return;
        }
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

    private void onFinished() {
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

    @Override
    public void cancelPushFrameReceived(long pushId) {
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                "CANCEL_PUSH is not valid on a request stream");
    }

    @Override
    public void settingsFrameReceived(long[] settings) {
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                "SETTINGS is not valid on a request stream");
    }

    @Override
    public void pushPromiseFrameReceived(long pushId, ByteBuffer encodedFieldSection) {
        // RFC 9114 section 7.2.5: a client MUST NOT send PUSH_PROMISE.
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                "PUSH_PROMISE is not valid from a client");
    }

    @Override
    public void goawayFrameReceived(long streamOrPushId) {
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                "GOAWAY is not valid on a request stream");
    }

    @Override
    public void maxPushIdFrameReceived(long maxPushId) {
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                "MAX_PUSH_ID is not valid on a request stream");
    }

    @Override
    public void frameError(String message) {
        String formatted = MessageFormat.format(L10N.getString("warn.frame_error"), message);
        LOGGER.warning(formatted);
        cancel();
    }

    private void connectionError(long errorCode, String message) {
        String formatted = MessageFormat.format(L10N.getString("warn.frame_error"), message);
        LOGGER.warning(formatted);
        connection.closeWithApplicationError(errorCode, message);
    }

    // ── HTTPResponseState Implementation ──

    @Override
    public SocketAddress getRemoteAddress() {
        return connection.getRemoteAddress();
    }

    @Override
    public SocketAddress getLocalAddress() {
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
    public Trace getTrace() {
        return connection.getTrace();
    }

    @Override
    public Principal getPrincipal() {
        return authenticatedPrincipal;
    }

    @Override
    public void sendInformational(int statusCode, Headers headers) {
        if (statusCode < 100 || statusCode > 199) {
            throw new IllegalArgumentException("Status code must be 1xx: " + statusCode);
        }
        if (responseBodyStarted) {
            throw new IllegalStateException("Cannot send informational response after body started");
        }

        List<Header> infoHeaders = new ArrayList<Header>();
        infoHeaders.add(new Header(":status", String.valueOf(statusCode)));
        for (int i = 0; i < headers.size(); i++) {
            Header h = headers.get(i);
            String name = h.getName();
            if ("Connection".equalsIgnoreCase(name) || "Keep-Alive".equalsIgnoreCase(name)
                    || "Transfer-Encoding".equalsIgnoreCase(name)) {
                continue;
            }
            infoHeaders.add(h);
        }

        sendHeaderFrame(infoHeaders, false);
        responseStarted = true;
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
        sendBody(data);
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
            endpoint.close();
        }
        state = State.CLOSED;
        endTelemetrySpan(responseStatusCode);
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
    public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler wsHandler) {
        upgradeToWebSocketInternal(subprotocol, null, wsHandler);
    }

    @Override
    public void upgradeToWebSocket(String subprotocol, List<WebSocketExtension> extensions,
            WebSocketEventHandler wsHandler) {
        upgradeToWebSocketInternal(subprotocol, extensions, wsHandler);
    }

    private void upgradeToWebSocketInternal(String subprotocol, List<WebSocketExtension> extensions,
            WebSocketEventHandler wsHandler) {
        if (!"CONNECT".equals(method) || !"websocket".equalsIgnoreCase(protocol)) {
            throw new IllegalStateException("Not an Extended CONNECT with :protocol websocket");
        }
        if (responseStarted) {
            throw new IllegalStateException("Response already started");
        }

        // RFC 9220 section 4 — send 200 OK to accept the upgrade.
        // Include negotiated subprotocol and extensions as regular
        // headers (RFC 9220 section 3).
        pendingResponseHeaders = new ArrayList<Header>();
        pendingResponseHeaders.add(new Header(":status", "200"));
        if (subprotocol != null && !subprotocol.isEmpty()) {
            pendingResponseHeaders.add(new Header("sec-websocket-protocol", subprotocol));
        }
        if (extensions != null && !extensions.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < extensions.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(extensions.get(i).getName());
                Map<String, String> extParams = extensions.get(i).generateOffer();
                if (extParams != null) {
                    for (Map.Entry<String, String> ep : extParams.entrySet()) {
                        sb.append("; ").append(ep.getKey());
                        if (ep.getValue() != null) {
                            sb.append("=").append(ep.getValue());
                        }
                    }
                }
            }
            pendingResponseHeaders.add(new Header("sec-websocket-extensions", sb.toString()));
        }
        flushHeaders(false);

        WebSocketServerMetrics wsMetrics = connection.getWebSocketMetrics();

        webSocketAdapter = new H3WebSocketConnectionAdapter(wsHandler, wsMetrics);
        webSocketAdapter.setTransport(new H3WebSocketTransport());
        if (extensions != null && !extensions.isEmpty()) {
            webSocketAdapter.setExtensions(extensions);
        }

        if (connection.isTelemetryEnabled()) {
            webSocketAdapter.setTelemetryConfig(connection.getTelemetryConfig());
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
    private void onWebSocketData(ByteBuffer data) {
        try {
            webSocketAdapter.processIncomingData(data);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.websocket_frame_error"), e);
            webSocketAdapter.notifyError(e);
        }
    }

    /**
     * Called when the peer's stream ends while in WebSocket mode,
     * indicating that the transport has been closed.
     */
    private void onWebSocketFinished() {
        try {
            webSocketAdapter.processIncomingData(EMPTY_BUFFER.duplicate());
        } catch (IOException ignored) {
            // FIN with empty data
        }
        webSocketAdapter.notifyTransportClosed(1001, "Transport closed");
        state = State.CLOSED;
    }

    // ── WebSocket adapter inner classes ──

    /**
     * Bridges {@link WebSocketEventHandler} to the {@link WebSocketConnection}
     * abstract class. Modelled on {@code Stream.WebSocketConnectionAdapter}.
     */
    private class H3WebSocketConnectionAdapter extends WebSocketConnection implements WebSocketSession {

        private final WebSocketEventHandler wsHandler;
        private final WebSocketServerMetrics wsMetrics;
        private long openedAtNanos;

        H3WebSocketConnectionAdapter(WebSocketEventHandler wsHandler, WebSocketServerMetrics wsMetrics) {
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
                double durationMs = (System.nanoTime() - openedAtNanos) / 1_000_000.0;
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

        void notifyTransportClosed(int code, String reason) {
            if (isOpen()) {
                abnormalClose(code, reason);
            }
        }
    }

    /**
     * {@link WebSocketConnection.WebSocketTransport} that sends WebSocket
     * frames as HTTP/3 DATA frames on this stream.
     */
    private class H3WebSocketTransport implements WebSocketConnection.WebSocketTransport {

        @Override
        public void sendFrame(ByteBuffer frameData) throws IOException {
            if (state == State.CLOSED) {
                throw new IOException("Stream closed");
            }
            responseBodyBytes += frameData.remaining();
            sendBody(frameData);
        }

        @Override
        public void close(boolean normalClose) throws IOException {
            if (state != State.CLOSED) {
                endpoint.close();
                state = State.CLOSED;
                endTelemetrySpan(200);
            }
        }
    }

    // ── Backpressure / flow control ──
    //
    // Both delegate straight to the QUIC layer -- QuicStreamEndpoint
    // already buffers/paces sends and drops received data while paused,
    // so there is nothing left for this class to track itself (see the
    // class documentation).

    @Override
    public void execute(Runnable task) {
        endpoint.execute(task);
    }

    @Override
    public void onWritable(Runnable callback) {
        endpoint.onWriteReady(callback);
    }

    @Override
    public void pauseRequestBody() {
        endpoint.pauseRead();
    }

    @Override
    public void resumeRequestBody() {
        endpoint.resumeRead();
    }

    /**
     * Cancels this stream (RFC 9114 section 8: H3_REQUEST_CANCELLED)
     * because the request is no longer needed (e.g. no handler was
     * found for it).
     */
    @Override
    public void cancel() {
        if (span != null && !span.isEnded()) {
            span.recordError(ErrorCategory.INTERNAL_ERROR, "Request cancelled");
            span.end();
        }
        state = State.CLOSED;
        handler = null;
        endpoint.resetStream(H3ErrorCode.H3_REQUEST_CANCELLED);
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
            metrics.requestStarted(method != null ? method : "UNKNOWN");
        }

        if (!connection.isTelemetryEnabled()) {
            return;
        }

        TelemetryConfig telemetryConfig = connection.getTelemetryConfig();
        Trace trace = connection.getTrace();

        String traceparent = requestHeaders != null ? requestHeaders.getValue("traceparent") : null;

        String methodName = method != null ? method : "UNKNOWN";
        String spanName = MessageFormat.format(
                HTTP_L10N.getString("telemetry.http_request"), methodName);

        if (traceparent != null) {
            trace = telemetryConfig.createTraceFromTraceparent(traceparent, spanName, SpanKind.SERVER);
            connection.setTrace(trace);
        } else if (trace == null) {
            trace = telemetryConfig.createTrace(spanName, SpanKind.SERVER);
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
            span.addAttribute("net.peer.ip", connection.getRemoteAddress().toString());

            String host = requestHeaders != null ? requestHeaders.getValue(":authority") : null;
            if (host != null) {
                span.addAttribute("http.host", host);
            }
            String userAgent = requestHeaders != null ? requestHeaders.getValue("user-agent") : null;
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
            double durationMs = System.currentTimeMillis() - timestampStarted;
            metrics.requestCompleted(method != null ? method : "UNKNOWN", statusCode, durationMs, 0, responseBodyBytes);
        }

        if (span == null || span.isEnded()) {
            return;
        }

        span.addAttribute("http.status_code", statusCode);

        if (statusCode >= 400) {
            ErrorCategory category = ErrorCategory.fromHttpStatus(statusCode);
            if (category != null) {
                span.recordError(category, statusCode, "HTTP " + statusCode);
            } else {
                span.setStatusError("HTTP " + statusCode);
            }
        } else {
            span.setStatusOk();
        }

        span.end();
    }

    // ── Internal ──

    private void sendErrorResponse(int statusCode) {
        pendingResponseHeaders = new ArrayList<Header>();
        pendingResponseHeaders.add(new Header(":status", Integer.toString(statusCode)));
        flushHeaders(true);
        state = State.CLOSED;
        handler = null;
    }

    /**
     * Sends a 401 Unauthorized response with a WWW-Authenticate challenge
     * (RFC 9110 section 11.6.1).
     */
    private void sendUnauthorized(HTTPAuthenticationProvider authProvider) {
        pendingResponseHeaders = new ArrayList<Header>();
        pendingResponseHeaders.add(new Header(":status", "401"));
        String challenge = authProvider.generateChallenge();
        if (challenge != null) {
            pendingResponseHeaders.add(new Header("www-authenticate", challenge));
        }
        flushHeaders(true);
        state = State.CLOSED;
        handler = null;
    }

    /**
     * Flushes pending response headers. Strips connection-specific
     * headers per RFC 9114 section 4.2 before sending.
     *
     * @param fin true to also close the stream (no body will follow)
     */
    private void flushHeaders(boolean fin) {
        if (pendingResponseHeaders == null || pendingResponseHeaders.isEmpty()) {
            return;
        }

        // Add default security headers if enabled and not already set
        if (connection.getAddSecurityHeaders()) {
            if (!containsHeader(pendingResponseHeaders, "X-Frame-Options")) {
                pendingResponseHeaders.add(new Header("X-Frame-Options", "SAMEORIGIN"));
            }
            if (!containsHeader(pendingResponseHeaders, "X-Content-Type-Options")) {
                pendingResponseHeaders.add(new Header("X-Content-Type-Options", "nosniff"));
            }
        }

        // Capture response status code from :status pseudo-header
        for (int i = 0; i < pendingResponseHeaders.size(); i++) {
            Header h = pendingResponseHeaders.get(i);
            if (":status".equals(h.getName())) {
                try {
                    responseStatusCode = Integer.parseInt(h.getValue());
                } catch (NumberFormatException ignored) {
                    // leave as 0
                }
                break;
            }
        }

        // Strip headers that are illegal in HTTP/3 (RFC 9114 section 4.2)
        HTTPVersion.stripHttp1FramingHeaders(pendingResponseHeaders);

        // Inject traceparent for distributed trace propagation
        if (span != null) {
            pendingResponseHeaders.add(new Header("traceparent", span.getSpanContext().toTraceparent()));
        }

        List<Header> toSend = pendingResponseHeaders;
        pendingResponseHeaders = null;
        sendHeaderFrame(toSend, fin);
        responseStarted = true;
    }

    // Encodes and sends one HEADERS frame; RFC 9114 doesn't distinguish
    // "additional headers" (informational/trailers) from the initial
    // response at the frame level -- it's just another HEADERS frame.
    private void sendHeaderFrame(List<Header> fields, boolean fin) {
        ByteBuffer fieldSection = ByteBuffer.allocate(estimateFieldSectionCapacity(fields));
        ByteBuffer encoderInstructions = ByteBuffer.allocate(estimateFieldSectionCapacity(fields));
        qpackEncoder.encode(fieldSection, encoderInstructions, streamId, fields);
        fieldSection.flip();
        byte[] encoded = new byte[fieldSection.remaining()];
        fieldSection.get(encoded);
        encoderInstructions.flip();
        connection.flushQpackEncoderInstructions(encoderInstructions);

        ByteBuffer out = ByteBuffer.allocate(H3Writer.headersLength(encoded.length));
        H3Writer.writeHeaders(out, encoded);
        out.flip();
        endpoint.send(out);
        if (fin) {
            endpoint.close();
        }
    }

    private void sendBody(ByteBuffer data) {
        int length = data.remaining();
        ByteBuffer out = ByteBuffer.allocate(H3Writer.dataLength(length));
        byte[] bytes = new byte[length];
        data.get(bytes);
        H3Writer.writeData(out, bytes);
        out.flip();
        endpoint.send(out);
    }

    private static int estimateFieldSectionCapacity(List<Header> fields) {
        int estimate = 16;
        for (Header field : fields) {
            estimate += 8 + 2 * (field.getName().length() + field.getValue().length());
        }
        return estimate;
    }
}
