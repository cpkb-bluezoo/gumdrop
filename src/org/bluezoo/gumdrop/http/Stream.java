/*
 * Stream.java
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

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.text.MessageFormat;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.ConnectionInfo;
import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.TLSInfo;
import org.bluezoo.gumdrop.http.hpack.HeaderHandler;
import org.bluezoo.gumdrop.http.websocket.WebSocketConnection;
import org.bluezoo.gumdrop.http.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * A stream.
 * This represents a single request/response.
 * Although the concept was introduced in HTTP/2, we use the same
 * mechanism in HTTP/1.
 * This class transparently handles Transfer-Encoding: chunked in requests.
 * This method is deprecated for responses: implementations should always
 * set the Content-Length.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc7540#section-5
 */
class Stream implements HTTPResponseState {

    private static final Logger LOGGER = Logger.getLogger(Stream.class.getName());

    /** Reusable empty buffer for completing responses without body. */
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /**
     * Date format for HTTP headers.
     */
    private static final HTTPDateFormat dateFormat = new HTTPDateFormat();

    /**
     * Returns true if the given HTTP method does not have a request body.
     */
    private static boolean isNoBodyMethod(String method) {
        // Ordered by frequency for short-circuit optimization
        return "GET".equals(method) || "HEAD".equals(method) || 
               "OPTIONS".equals(method) || "DELETE".equals(method);
    }

    enum State {
        IDLE,
        OPEN,
        CLOSED,
        RESERVED_LOCAL,
        RESERVED_REMOTE,
        HALF_CLOSED_LOCAL,
        HALF_CLOSED_REMOTE;
    }

    final HTTPConnection connection;
    final int streamId;

    Stream(HTTPConnection connection, int streamId) {
        this.connection = connection;
        this.streamId = streamId;
    }

    private State state = State.IDLE;
    private Headers headers; // NB these are the *request* headers
    private Headers trailerHeaders; // Trailer headers in chunked request
    private ByteBuffer headerBlock; // raw HPACK-encoded header block
    private boolean pushPromise;
    private String method;
    private String requestTarget;
    private long contentLength = -1L;
    private long requestBodyBytesReceived = 0L;
    private boolean chunked;
    private long timestampStarted = 0L;

    // Fields accessed by HTTPConnection
    boolean closeConnection;
    Collection<String> upgrade;
    Map<Integer, Integer> h2cSettings;
    long timestampCompleted = 0L;

    // Telemetry span for this request/response (null if telemetry disabled)
    private Span span;
    private int responseStatusCode; // Saved for telemetry when body completes

    // Response body size tracking for metrics
    private long responseBodyBytes = 0L;

    // ─────────────────────────────────────────────────────────────────────────
    // HTTPResponseState implementation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Response state for the event-based API.
     */
    private enum ResponseState {
        INITIAL,        // Before any response headers sent
        HEADERS_SENT,   // Headers sent, may send body or complete
        IN_BODY,        // After startResponseBody, sending body chunks
        BODY_COMPLETE,  // After endResponseBody, may send trailers
        COMPLETE        // After complete(), response finished
    }

    private ResponseState responseState = ResponseState.INITIAL;
    private Headers bufferedResponseHeaders;
    private HTTPRequestHandler handler;
    private Principal authenticatedPrincipal;

    // Request body state tracking for handler dispatch
    private boolean handlerBodyStarted = false;
    private boolean handlerBodyEnded = false;

    /** Reusable header handler for HPACK decoding. */
    private final HeaderHandler hpackHandler = new HeaderHandler() {
        @Override public void header(Header header) {
            headers.add(header);
        }
    };

    /**
     * Returns the URI scheme of the connection.
     */
    public String getScheme() {
        return connection.getScheme();
    }

    /**
     * Returns the version of the connection.
     */
    public HTTPVersion getVersion() {
        return connection.getVersion();
    }

    /**
     * Notify that this stream represents a push promise.
     */
    void setPushPromise() {
        pushPromise = true;
    }
    
    /**
     * Sends an HTTP/2 server push for the specified resource.
     * This method creates a PUSH_PROMISE frame and establishes a promised stream.
     * 
     * <p>Server push is only supported on HTTP/2 connections. On HTTP/1.x connections,
     * this method returns false.
     * 
     * @param method the HTTP method for the push request (typically GET or HEAD)
     * @param uri the URI path for the pushed resource
     * @param headers the headers for the push request
     * @return true if push was initiated successfully, false otherwise
     */
    private boolean sendServerPush(String method, String uri, Headers headers) {
        // Only HTTP/2 connections support server push
        if (connection.getVersion() != HTTPVersion.HTTP_2_0) {
            return false;
        }
        
        try {
            HTTPConnection httpConnection = (HTTPConnection) connection;
            
            // Get next available server stream ID (must be even for server-initiated streams)
            int promisedStreamId = httpConnection.getNextServerStreamId();
            
            // Create and send PUSH_PROMISE frame with the headers
            byte[] headerBlock = httpConnection.encodeHeaders(headers);
            httpConnection.sendPushPromise(this.streamId, promisedStreamId, 
                ByteBuffer.wrap(headerBlock), true);
            
            // Create the promised stream for handling the pushed response
            Stream promisedStream = httpConnection.createPushedStream(promisedStreamId, method, uri, headers);
            
            if (promisedStream != null) {
                // Mark as push promise stream
                promisedStream.setPushPromise();
                
                // The pushed response will be handled by the server when 
                // the application generates content for this URI
                return true;
            }
            
        } catch (Exception e) {
            // Log error but don't throw - server push failures should not break main response
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "Failed to execute server push for " + uri, e);
            }
        }
        
        return false;
    }

    /**
     * 5.1.2 Stream Concurrency
     */
    boolean isActive() {
        return state == State.OPEN ||
            state == State.HALF_CLOSED_LOCAL ||
            state == State.HALF_CLOSED_REMOTE;
    }

    /**
     * Indicates whether this stream will cause the connection to the client
     * to be closed after its response is sent.
     * This is the default behaviour for HTTP/1.0, and can be set by using
     * the Connection: close header in HTTP/1.1.
     */
    boolean isCloseConnection() {
        return closeConnection;
    }

    /**
     * Returns the Content-Length of the request, or -1 if not known: this
     * indicates chunked encoding.
     */
    long getContentLength() {
        return contentLength;
    }

    /**
     * Returns true if this request uses chunked transfer encoding.
     */
    boolean isChunked() {
        return chunked;
    }

    /**
     * Adds a trailer header to this stream.
     * Called by HTTPConnection when processing trailer headers in chunked encoding.
     */
    void addTrailerHeader(Header header) {
        if (trailerHeaders == null) {
            trailerHeaders = new Headers();
        }
        trailerHeaders.add(header);
    }

    /**
     * Indicates whether this stream has been closed.
     */
    boolean isClosed() {
        return state == State.CLOSED;
    }

    long getRequestBodyBytesNeeded() {
        return contentLength - requestBodyBytesReceived;
    }

    Headers getHeaders() {
        return headers;
    }

    void addHeader(Header header) {
        if (headers == null) {
            headers = new Headers();
        }
        headers.add(header);
        if (":method".equals(header.getName())) {
            method = header.getValue();
        } else if (":path".equals(header.getName())) {
            requestTarget = header.getValue();
        }
    }

    private static final int HEADER_BLOCK_INITIAL_SIZE = 4096;

    void appendHeaderBlockFragment(ByteBuffer hbf) {
        int hbfLength = hbf.remaining();
        if (headerBlock == null) {
            headerBlock = ByteBuffer.allocate(Math.max(HEADER_BLOCK_INITIAL_SIZE, hbfLength));
        } else if (headerBlock.remaining() < hbfLength) {
            // Grow by at least 2x or enough for new data
            int newCapacity = Math.max(headerBlock.capacity() * 2, 
                                       headerBlock.position() + hbfLength);
            ByteBuffer newHeaderBlock = ByteBuffer.allocate(newCapacity);
            headerBlock.flip();
            newHeaderBlock.put(headerBlock);
            headerBlock = newHeaderBlock;
        }
        headerBlock.put(hbf);
    }

    /**
     * Parses HTTP2-Settings header value into a settings map.
     */
    private static Map<Integer, Integer> parseH2cSettings(ByteBuffer payload) {
        Map<Integer, Integer> settings = new LinkedHashMap<Integer, Integer>();
        while (payload.remaining() >= 6) {
            int identifier = ((payload.get() & 0xff) << 8) | (payload.get() & 0xff);
            int value = ((payload.get() & 0xff) << 24)
                | ((payload.get() & 0xff) << 16)
                | ((payload.get() & 0xff) << 8)
                | (payload.get() & 0xff);
            settings.put(identifier, value);
        }
        return settings.isEmpty() ? null : settings;
    }

    void streamEndHeaders() {
        if (headerBlock != null) {
            headerBlock.flip();
            headers = new Headers();
            try {
                connection.hpackDecoder.decode(headerBlock, hpackHandler);
            } catch (IOException e) {
                // HPACK decompression failure is a connection-level error
                HTTPConnection.LOGGER.log(Level.WARNING, 
                    "HPACK decompression error", e);
                connection.sendGoaway(H2FrameHandler.ERROR_COMPRESSION_ERROR);
                return;
            }
            headerBlock = null;
        }
        if (state == State.IDLE) {
            if (pushPromise) {
                state = State.RESERVED_REMOTE;
            } else {
                state = State.OPEN;
            }
        } else if (state == State.RESERVED_REMOTE) {
            state = State.HALF_CLOSED_LOCAL;
        }
        if (headers != null) {
            boolean isUpgrade = false;
            Collection<String> upgradeProtocols = null;
            Map<Integer, Integer> http2Settings = null;
            for (Iterator<Header> i = headers.iterator(); i.hasNext(); ) {
                Header header = i.next();
                String name = header.getName();
                String value = header.getValue();
                if (":method".equals(name)) {
                    if (isNoBodyMethod(value)) {
                        contentLength = 0;
                    }
                } else if (connection.version != HTTPVersion.HTTP_2_0) { // HTTP/1
                    if ("Connection".equalsIgnoreCase(name)) {
                        if ("close".equalsIgnoreCase(value)) {
                            closeConnection = true;
                        } else {
                            for (String v : value.split(",")) {
                                if ("Upgrade".equalsIgnoreCase(v.trim())) {
                                    isUpgrade = true;
                                    break;
                                }
                            } 
                        }
                    } else if ("Content-Length".equalsIgnoreCase(name)) {
                        try {
                            contentLength = Long.parseLong(value);
                        } catch (NumberFormatException e) {
                            contentLength = -1L;
                        }
                    } else if ("Transfer-Encoding".equalsIgnoreCase(name) && "chunked".equals(value)) {
                        contentLength = Integer.MAX_VALUE;
                        chunked = true;
                        i.remove(); // do not pass this on to stream implementation
                    } else if ("Upgrade".equalsIgnoreCase(name)) {
                        if (upgradeProtocols == null) {
                            upgradeProtocols = new LinkedHashSet<>();
                        }
                        for (String v : value.split(",")) {
                            upgradeProtocols.add(v.trim());
                        }
                    } else if ("HTTP2-Settings".equalsIgnoreCase(name)) {
                        try {
                            byte[] settings = Base64.getUrlDecoder().decode(value);
                            http2Settings = parseH2cSettings(ByteBuffer.wrap(settings));
                        } catch (IllegalArgumentException e) {
                            // Invalid base64 in HTTP2-Settings header - ignore it
                            HTTPConnection.LOGGER.log(Level.WARNING, 
                                "Invalid base64 in HTTP2-Settings header: " + value, e);
                        }
                    }
                }
            }
            if (isUpgrade && upgradeProtocols != null) {
                this.upgrade = upgradeProtocols;
                this.h2cSettings = http2Settings;
            }
        }
        // Initialize telemetry span if enabled
        initTelemetrySpan();
        
        // Dispatch to handler if present
        if (handler != null) {
            // Handler already set - this is a continuation or trailer headers
            if (handlerBodyStarted && !handlerBodyEnded) {
                // Body was in progress, this must be trailer headers
                handlerBodyEnded = true;
                handler.endRequestBody(this);
            }
            handler.headers(headers, this);
        } else {
            // No handler yet - try to create one via factory
            // Note: We create the handler even for h2c upgrade requests, because
            // the request body (if any) arrives before the protocol switch.
            HTTPRequestHandlerFactory factory = connection.getHandlerFactory();
            if (factory != null) {
                handler = factory.createHandler(headers, this);
                if (handler != null) {
                    handler.headers(headers, this);
                }
                // If handler is null, factory may have sent a response (401, 404, etc.)
            } else {
                // No factory configured - send 404 Not Found
                try {
                    sendError(404);
                } catch (ProtocolException e) {
                    HTTPConnection.LOGGER.warning(MessageFormat.format(
                        HTTPConnection.L10N.getString("warn.default_404_failed"), e.getMessage()));
                }
            }
        }
    }

    /**
     * Initializes a telemetry span for this request if telemetry is enabled.
     * The span is created as a child of the connection's trace, or a new
     * trace is started if traceparent header is present.
     */
    private void initTelemetrySpan() {
        // Record request start time
        timestampStarted = System.currentTimeMillis();

        // Record metrics for request start
        HTTPServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.requestStarted(method != null ? method : "UNKNOWN");
        }

        if (!connection.isTelemetryEnabled()) {
            return;
        }

        TelemetryConfig telemetryConfig = connection.getTelemetryConfig();
        Trace trace = connection.getTrace();

        // Check for incoming traceparent header (distributed tracing)
        String traceparent = headers != null ? headers.getValue("traceparent") : null;

        // Build span name following OpenTelemetry semantic conventions: "HTTP {method}"
        String methodName = method != null ? method : "UNKNOWN";
        String spanName = MessageFormat.format(
                HTTPConnection.L10N.getString("telemetry.http_request"), methodName);

        if (traceparent != null) {
            // Continue distributed trace from upstream service
            trace = telemetryConfig.createTraceFromTraceparent(traceparent, spanName, SpanKind.SERVER);
            connection.setTrace(trace);
        } else if (trace == null) {
            // Create a new trace for this request
            trace = telemetryConfig.createTrace(spanName, SpanKind.SERVER);
            connection.setTrace(trace);
        }

        if (trace != null) {
            // Create a child span for this stream/request
            span = trace.startSpan(spanName, SpanKind.SERVER);

            // Add standard HTTP semantic convention attributes
            if (method != null) {
                span.addAttribute("http.method", method);
            }
            if (requestTarget != null) {
                span.addAttribute("http.target", requestTarget);
            }
            span.addAttribute("http.scheme", connection.getScheme());
            span.addAttribute("http.flavor", connection.getVersion().toString());

            // Add network attributes
            span.addAttribute("net.peer.ip", connection.getRemoteSocketAddress().toString());

            // Add Host header if present
            String host = headers != null ? headers.getValue("Host") : null;
            if (host != null) {
                span.addAttribute("http.host", host);
            }

            // Add User-Agent if present
            String userAgent = headers != null ? headers.getValue("User-Agent") : null;
            if (userAgent != null) {
                span.addAttribute("http.user_agent", userAgent);
            }
        }
    }

    /**
     * Receive request body data from the specified input buffer.
     * Used by WebSocket mode which passes data directly.
     * Note: HTTP/1 chunked encoding is now handled at the connection level.
     */
    void appendRequestBody(ByteBuffer buf) {
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        requestBodyBytesReceived += bytes.length;
        receiveRequestBody(ByteBuffer.wrap(bytes));
    }

    /**
     * Receive request body data from the specified frame data (HTTP/2).
     */
    void appendRequestBody(byte[] bytes) {
        requestBodyBytesReceived += bytes.length;
        receiveRequestBody(ByteBuffer.wrap(bytes));
    }

    /**
     * Receive request body data. This method may be called more than once
     * for a single stream (request).
     * Called by HTTPConnection for direct body data (non-chunked or after
     * chunked decoding at the connection level).
     * 
     * <p>This method consumes all data in the buffer (advances position to limit).
     */
    void receiveRequestBody(ByteBuffer buf) {
        // Track bytes received
        int bytesToConsume = buf.remaining();
        requestBodyBytesReceived += bytesToConsume;
        
        // If WebSocket mode, delegate to WebSocket processing
        if (webSocketAdapter != null) {
            try {
                webSocketAdapter.processIncomingData(buf);
            } catch (IOException e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, "Error processing WebSocket data", e);
                }
            }
            // Consume any remaining data
            buf.position(buf.limit());
            return;
        }
        
        // Dispatch to handler if present
        if (handler != null) {
            if (!handlerBodyStarted) {
                handlerBodyStarted = true;
                handler.startRequestBody(this);
            }
            handler.requestBodyContent(buf, this);
        }
        
        // Consume any remaining data (handler may not have consumed it)
        buf.position(buf.limit());
    }

    void streamEndRequest() {
        state = State.HALF_CLOSED_REMOTE;
        
        // Dispatch to handler if present
        if (handler != null) {
            if (handlerBodyStarted && !handlerBodyEnded) {
                // Body was started but not ended (no trailers)
                handlerBodyEnded = true;
                handler.endRequestBody(this);
            }
            handler.requestComplete(this);
        }
    }

    /**
     * Sends response headers for this stream.
     * This will include a Status-Line for HTTP/1 streams.
     * For HTTP/2 streams, the status will be added to the headers
     * automatically.
     * This method should only be called once. It corresponds to
     * "committing" the response.
     * @param statusCode the status code of the response
     * @param headers the headers to send in the response
     * @param endStream if no response data will be sent and this is a
     * complete response
     */
    final void sendResponseHeaders(int statusCode, Headers headers, boolean endStream) throws ProtocolException {
        if (state != State.HALF_CLOSED_REMOTE && state != State.OPEN) {
            throw new ProtocolException("Invalid state: " + state);
        }

        // Standard HTTP headers
        headers.add(new Header("Server", "gumdrop/" + Gumdrop.VERSION));
        headers.add(new Header("Date", dateFormat.format(new Date())));
        if (closeConnection) {
            headers.add(new Header("Connection", "close"));
        }

        // Add traceparent header to response if telemetry is enabled
        if (span != null) {
            headers.add("traceparent", span.getSpanContext().toTraceparent());
        }

        // Save status code for telemetry
        this.responseStatusCode = statusCode;

        connection.sendResponseHeaders(streamId, statusCode, headers, endStream);
        if (endStream) {
            if (state == State.HALF_CLOSED_REMOTE) {
                state = State.CLOSED; // normal request termination
                timestampCompleted = System.currentTimeMillis();
                // Close TCP connection if Connection: close was set
                if (closeConnection && connection.getVersion() != HTTPVersion.HTTP_2_0) {
                    connection.send(null);
                }
            } else {
                state = State.HALF_CLOSED_LOCAL;
            }
            // End telemetry span with response status
            endTelemetrySpan(statusCode);
        }
    }

    /**
     * Ends the telemetry span for this request with the given status code.
     *
     * @param statusCode the HTTP response status code
     */
    private void endTelemetrySpan(int statusCode) {
        // Record metrics for request completion
        HTTPServerMetrics metrics = getServerMetrics();
        if (metrics != null && timestampStarted > 0) {
            double durationMs = System.currentTimeMillis() - timestampStarted;
            metrics.requestCompleted(
                    method != null ? method : "UNKNOWN",
                    statusCode,
                    durationMs,
                    requestBodyBytesReceived,
                    responseBodyBytes);
        }

        if (span == null) {
            return;
        }

        // Add response status code
        span.addAttribute("http.status_code", statusCode);

        // Set span status based on HTTP status code
        if (statusCode >= 400) {
            // Add error category for HTTP errors
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

    private HTTPServerMetrics getServerMetrics() {
        return connection != null ? connection.getServerMetrics() : null;
    }

    /**
     * Sends response body data for this stream.
     * This must be called after sendResponseHeaders.
     * This method may be called multiple times. The caller should ensure
     * that the total number of bytes supplied via this method add up to
     * the number specified for the Content-Length.
     * @param buf response body contents
     * @param endStream if this is the last response body data that will be
     * sent
     */
    final void sendResponseBody(ByteBuffer buf, boolean endStream) throws ProtocolException {
        int bytesToAdd = (buf != null) ? buf.remaining() : 0;
        sendResponseBodyInternal(bytesToAdd, endStream);
        connection.sendResponseBody(streamId, buf, endStream);
    }

    /**
     * Common state management for sendResponseBody.
     */
    private void sendResponseBodyInternal(int bytesToAdd, boolean endStream) throws ProtocolException {
        if (state != State.HALF_CLOSED_REMOTE && state != State.OPEN) {
            throw new ProtocolException("Invalid state: " + state);
        }
        responseBodyBytes += bytesToAdd;
        if (endStream) {
            if (state == State.HALF_CLOSED_REMOTE) {
                state = State.CLOSED;
                timestampCompleted = System.currentTimeMillis();
                // Close TCP connection if Connection: close was set
                if (closeConnection && connection.getVersion() != HTTPVersion.HTTP_2_0) {
                    connection.send(null);
                }
            } else {
                state = State.HALF_CLOSED_LOCAL;
            }
            endTelemetrySpan(responseStatusCode);
        }
    }

    // -- WebSocket Support (Internal) --
    
    private boolean isWebSocketUpgradeRequest() {
        return headers != null && WebSocketHandshake.isValidWebSocketUpgrade(headers);
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // HTTPResponseState.upgradeToWebSocket Implementation
    // ─────────────────────────────────────────────────────────────────────────
    
    // The active WebSocket connection adapter (set after upgrade)
    private WebSocketConnectionAdapter webSocketAdapter;
    
    @Override
    public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) {
        if (!isWebSocketUpgradeRequest()) {
            throw new IllegalStateException(HTTPConnection.L10N.getString("err.not_websocket_upgrade"));
        }
        if (responseState != ResponseState.INITIAL) {
            throw new IllegalStateException(HTTPConnection.L10N.getString("err.response_started"));
        }
        
        try {
            // Send 101 Switching Protocols response
            String key = headers.getValue("Sec-WebSocket-Key");
            Headers responseHeaders = WebSocketHandshake.createWebSocketResponse(key, subprotocol);
            sendResponseHeaders(101, responseHeaders, false);
            
            // Create adapter that bridges handler to WebSocketConnection
            webSocketAdapter = new WebSocketConnectionAdapter(handler);
            webSocketAdapter.setTransport(new StreamWebSocketTransport());
            
            // Configure telemetry if enabled
            if (connection.isTelemetryEnabled()) {
                webSocketAdapter.setTelemetryConfig(connection.getTelemetryConfig());
                if (span != null) {
                    webSocketAdapter.setParentSpan(span);
                } else {
                    webSocketAdapter.createSpan(null);
                }
            }
            
            // Switch connection to WebSocket mode
            connection.switchToWebSocketMode(streamId);
            
            // Notify handler that connection is open
            webSocketAdapter.notifyConnectionOpen();
            
        } catch (ProtocolException e) {
            throw new IllegalStateException("Failed to send WebSocket upgrade response", e);
        }
    }
    
    /**
     * Adapter that bridges WebSocketEventHandler to WebSocketConnection.
     */
    private class WebSocketConnectionAdapter extends WebSocketConnection 
            implements WebSocketSession {
        
        private final WebSocketEventHandler handler;
        
        WebSocketConnectionAdapter(WebSocketEventHandler handler) {
            this.handler = handler;
        }
        
        // ─────────── WebSocketConnection abstract methods ───────────
        
        @Override
        protected void opened() {
            handler.opened(this);
        }
        
        @Override
        protected void textMessageReceived(String message) {
            handler.textMessageReceived(message);
        }
        
        @Override
        protected void binaryMessageReceived(ByteBuffer data) {
            handler.binaryMessageReceived(data);
        }
        
        @Override
        protected void closed(int code, String reason) {
            handler.closed(code, reason);
        }
        
        @Override
        protected void error(Throwable cause) {
            handler.error(cause);
        }
        
        // ─────────── WebSocketSession interface (delegates to parent) ───────────
        // Note: sendText, sendBinary, sendPing, close, isOpen are already 
        // implemented in WebSocketConnection - we just expose them via the interface
    }
    
    /**
     * Internal WebSocket transport implementation that uses the Stream for I/O.
     */
    private class StreamWebSocketTransport implements WebSocketConnection.WebSocketTransport {
        
        @Override
        public void sendFrame(ByteBuffer frameData) throws IOException {
            try {
                sendResponseBody(frameData, false);
            } catch (ProtocolException e) {
                throw new IOException("Failed to send WebSocket frame", e);
            }
        }
        
        @Override
        public void close(boolean normalClose) throws IOException {
            Stream.this.streamClose(normalClose);
        }
    }
    
    /**
     * Sends an error response with the specified status code.
     */
    void sendError(int statusCode) throws ProtocolException {
        if (state == State.IDLE) {
            state = State.OPEN;
        }
        Headers headers = new Headers();
        // For HTTP/1.x, add Content-Length: 0 so clients know there's no body
        // Also close connection on error to prevent keep-alive issues
        if (connection.getVersion() != HTTPVersion.HTTP_2_0) {
            headers.add("Content-Length", "0");
            closeConnection = true; // Close connection after error
        }
        sendResponseHeaders(statusCode, headers, true);
    }

    /**
     * Closes this stream abnormally (e.g., connection dropped).
     */
    void streamClose() {
        streamClose(false);
    }

    /**
     * Closes this stream.
     *
     * @param normalClose true if this is a normal close (e.g., Connection: close
     *                    header or normal WebSocket close), false if abnormal
     */
    void streamClose(boolean normalClose) {
        state = State.CLOSED;
        timestampCompleted = System.currentTimeMillis();
        // End telemetry span if still open
        if (span != null && !span.isEnded()) {
            if (normalClose) {
                span.setStatusOk();
            } else {
                span.recordError(ErrorCategory.CONNECTION_LOST, 
                    HTTPConnection.L10N.getString("telemetry.stream_closed_abnormally"));
            }
            span.end();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTPResponseState implementation
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public ConnectionInfo getConnectionInfo() {
        return connection.getConnectionInfoForStream();
    }

    @Override
    public TLSInfo getTLSInfo() {
        return connection.isSecure() ? connection.getTLSInfoForStream() : null;
    }

    @Override
    public Principal getPrincipal() {
        return authenticatedPrincipal;
    }

    @Override
    public void headers(Headers headers) {
        if (responseState == ResponseState.COMPLETE) {
            throw new IllegalStateException(HTTPConnection.L10N.getString("err.response_complete"));
        }
        // Buffer headers - they will be flushed on startResponseBody() or complete()
        if (bufferedResponseHeaders == null) {
            bufferedResponseHeaders = new Headers();
        }
        // Merge incoming headers into buffer
        for (Header header : headers) {
            bufferedResponseHeaders.add(header);
        }
    }

    @Override
    public void startResponseBody() {
        if (responseState != ResponseState.INITIAL) {
            throw new IllegalStateException("startResponseBody() called in invalid state: " + responseState);
        }
        // Flush buffered headers
        flushResponseHeaders(false);
        responseState = ResponseState.IN_BODY;
    }

    @Override
    public void responseBodyContent(ByteBuffer data) {
        if (responseState != ResponseState.IN_BODY) {
            throw new IllegalStateException("responseBodyContent() called in invalid state: " + responseState);
        }
        try {
            // Send DATA frame without END_STREAM
            sendResponseBody(data, false);
        } catch (ProtocolException e) {
            throw new IllegalStateException("Failed to send response body", e);
        }
    }

    @Override
    public void endResponseBody() {
        if (responseState != ResponseState.IN_BODY) {
            throw new IllegalStateException("endResponseBody() called in invalid state: " + responseState);
        }
        responseState = ResponseState.BODY_COMPLETE;
        // Clear buffered headers for potential trailers
        bufferedResponseHeaders = null;
    }

    @Override
    public void complete() {
        if (responseState == ResponseState.COMPLETE) {
            return; // Already complete, ignore
        }

        try {
            if (bufferedResponseHeaders != null && bufferedResponseHeaders.size() > 0) {
                // Has buffered headers (either initial headers for no-body response, or trailers)
                flushResponseHeaders(true); // END_STREAM
            } else {
                // No buffered headers - send empty DATA with END_STREAM
                sendResponseBody(EMPTY_BUFFER.duplicate(), true);
            }
        } catch (ProtocolException e) {
            throw new IllegalStateException("Failed to complete response", e);
        }

        responseState = ResponseState.COMPLETE;
    }

    @Override
    public boolean pushPromise(Headers headers) {
        if (connection.getVersion() != HTTPVersion.HTTP_2_0) {
            return false; // Server push only for HTTP/2
        }
        if (!connection.enablePush) {
            return false; // Client disabled push
        }
        
        // Extract method and path from headers
        String method = headers.getValue(":method");
        String path = headers.getValue(":path");
        if (method == null || path == null) {
            return false; // Required pseudo-headers missing
        }
        
        try {
            return sendServerPush(method, path, headers);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void cancel() {
        try {
            // For HTTP/2, send RST_STREAM
            // For HTTP/1, close connection
            if (connection.getVersion() == HTTPVersion.HTTP_2_0) {
                connection.sendRstStream(streamId, 0x8); // CANCEL error code
            } else {
                closeConnection = true;
                connection.send(null); // Trigger close
            }
        } catch (Exception e) {
            // Best effort
        }
        responseState = ResponseState.COMPLETE;
    }

    /**
     * Flushes buffered response headers.
     *
     * @param endStream true to set END_STREAM (for no-body responses or trailers)
     */
    private void flushResponseHeaders(boolean endStream) {
        if (bufferedResponseHeaders == null || bufferedResponseHeaders.size() == 0) {
            return;
        }

        // Extract status code from :status pseudo-header
        String statusStr = bufferedResponseHeaders.getValue(":status");
        int statusCode = 200; // Default if no :status header
        if (statusStr != null) {
            try {
                statusCode = Integer.parseInt(statusStr);
            } catch (NumberFormatException e) {
                // Invalid :status value is a server programming error
                statusCode = 500;
            }
            // Remove :status from headers - it's handled separately for HTTP/1
            bufferedResponseHeaders.remove(":status");
        }

        try {
            sendResponseHeaders(statusCode, bufferedResponseHeaders, endStream);
        } catch (ProtocolException e) {
            throw new IllegalStateException("Failed to send response headers", e);
        }

        if (responseState == ResponseState.INITIAL) {
            responseState = ResponseState.HEADERS_SENT;
        }
    }

}

