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
import java.util.ResourceBundle;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.NullSecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.hpack.HeaderHandler;
import org.bluezoo.gumdrop.websocket.WebSocketConnection;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.websocket.WebSocketListener;
import org.bluezoo.gumdrop.websocket.WebSocketServerMetrics;
import org.bluezoo.gumdrop.websocket.WebSocketSession;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * A stream representing a single HTTP request/response exchange.
 *
 * <p>Although the concept was introduced in HTTP/2, we use the same
 * mechanism in HTTP/1. This class transparently handles
 * Transfer-Encoding: chunked in requests (RFC 9112 section 7).
 * For responses, implementations should set Content-Length
 * (RFC 9110 section 8.6).
 *
 * <p>Handles connection-level headers per RFC 9110/9112:
 * <ul>
 * <li>Connection: close (RFC 9112 section 9.6)</li>
 * <li>Content-Length (RFC 9112 section 6.2)</li>
 * <li>Transfer-Encoding (RFC 9112 section 6.1)</li>
 * <li>Upgrade (RFC 9110 section 7.8)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9112">RFC 9112</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9113#section-5">RFC 9113 section 5</a>
 */
class Stream implements HTTPResponseState {

    private static final Logger LOGGER = Logger.getLogger(Stream.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");

    /** Reusable empty buffer for completing responses without body. */
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /**
     * Date format for HTTP headers.
     */
    private static final HTTPDateFormat dateFormat = new HTTPDateFormat();

    /**
     * Returns true if the given HTTP method does not have a request body.
     * RFC 9110 section 9.3.1: GET has no defined body semantics.
     * RFC 9110 section 9.3.2: HEAD is identical to GET but no response body.
     * RFC 9110 section 9.3.7: OPTIONS body has no defined semantics.
     * RFC 9110 section 9.3.5: DELETE body has no defined semantics.
     * RFC 9110 section 9.3.8: A client MUST NOT send content in TRACE.
     */
    private static boolean isNoBodyMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || 
               "OPTIONS".equals(method) || "DELETE".equals(method) ||
               "TRACE".equals(method);
    }

    // RFC 9113 section 5.1: stream states
    enum State {
        IDLE,                // RFC 9113 section 5.1: initial state
        OPEN,                // RFC 9113 section 5.1: after HEADERS sent/received
        CLOSED,              // RFC 9113 section 5.1: terminal state
        RESERVED_LOCAL,      // RFC 9113 section 5.1: after PUSH_PROMISE sent
        RESERVED_REMOTE,     // RFC 9113 section 5.1: after PUSH_PROMISE received
        HALF_CLOSED_LOCAL,   // RFC 9113 section 5.1: local END_STREAM sent
        HALF_CLOSED_REMOTE;  // RFC 9113 section 5.1: remote END_STREAM received
    }

    final HTTPConnectionLike connection;
    final int streamId;

    Stream(HTTPConnectionLike connection, int streamId) {
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

    // RFC 9112 section 9.6: Connection: close flag
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

    @Override
    public SelectorLoop getSelectorLoop() {
        return connection.getSelectorLoop();
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
            // Get next available server stream ID (must be even for server-initiated streams)
            int promisedStreamId = connection.getNextServerStreamId();
            
            // Create and send PUSH_PROMISE frame with the headers
            byte[] headerBlock = connection.encodeHeaders(headers);
            connection.sendPushPromise(this.streamId, promisedStreamId,
                ByteBuffer.wrap(headerBlock), true);
            
            // Create the promised stream for handling the pushed response
            Stream promisedStream = connection.createPushedStream(promisedStreamId, method, uri, headers);
            
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
     * RFC 9112 section 9.3: HTTP/1.0 defaults to close.
     * RFC 9112 section 9.6: Connection: close ends persistence in HTTP/1.1.
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
                // RFC 7541: HPACK decompression of the header block
                connection.getHpackDecoder().decode(headerBlock, hpackHandler);
            } catch (IOException e) {
                // RFC 9113 section 4.3: HPACK decompression failure MUST
                // be treated as a connection error of type COMPRESSION_ERROR
                LOGGER.log(Level.WARNING, 
                    "HPACK decompression error", e);
                connection.sendGoaway(H2FrameHandler.ERROR_COMPRESSION_ERROR);
                return;
            }
            headerBlock = null;
        }
        // RFC 9113 section 8.2: validate pseudo-headers and
        // connection-specific header constraints
        if (connection.getVersion() == HTTPVersion.HTTP_2_0
                && headers != null && !validateH2Headers()) {
            connection.sendRstStream(streamId, H2FrameHandler.ERROR_PROTOCOL_ERROR);
            state = State.CLOSED;
            timestampCompleted = System.currentTimeMillis();
            return;
        }
        // RFC 9113 section 5.1: stream state transitions on HEADERS receipt
        if (state == State.IDLE) {
            if (pushPromise) {
                state = State.RESERVED_REMOTE;
            } else {
                state = State.OPEN;
            }
        } else if (state == State.RESERVED_REMOTE) {
            state = State.HALF_CLOSED_LOCAL;
        }
        boolean hasExplicitContentLength = false;
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
                } else if (connection.getVersion() != HTTPVersion.HTTP_2_0) { // HTTP/1
                    // RFC 9112 section 9.6: Connection header field
                    if ("Connection".equalsIgnoreCase(name)) {
                        if ("close".equalsIgnoreCase(value)) {
                            closeConnection = true;
                        } else {
                            // Parse comma-separated connection tokens
                            int vStart = 0;
                            int vLen = value.length();
                            while (vStart <= vLen) {
                                int vEnd = value.indexOf(',', vStart);
                                if (vEnd < 0) {
                                    vEnd = vLen;
                                }
                                String v = value.substring(vStart, vEnd).trim();
                                if ("Upgrade".equalsIgnoreCase(v)) {
                                    isUpgrade = true;
                                    break;
                                }
                                vStart = vEnd + 1;
                            }
                        }
                    } else if ("Content-Length".equalsIgnoreCase(name)) {
                        // RFC 9112 section 6.2: Content-Length
                        try {
                            contentLength = Long.parseLong(value);
                            hasExplicitContentLength = true;
                        } catch (NumberFormatException e) {
                            contentLength = -1L;
                        }
                    } else if ("Transfer-Encoding".equalsIgnoreCase(name) && "chunked".equals(value)) {
                        // RFC 9112 section 6.3: Transfer-Encoding overrides Content-Length
                        contentLength = Integer.MAX_VALUE;
                        chunked = true;
                        i.remove(); // do not pass this on to stream implementation
                    } else if ("Upgrade".equalsIgnoreCase(name)) {
                        // RFC 9110 section 7.8: Upgrade header field
                        if (upgradeProtocols == null) {
                            upgradeProtocols = new LinkedHashSet<String>();
                        }
                        // Parse comma-separated upgrade protocols
                        int vStart = 0;
                        int vLen = value.length();
                        while (vStart <= vLen) {
                            int vEnd = value.indexOf(',', vStart);
                            if (vEnd < 0) {
                                vEnd = vLen;
                            }
                            String v = value.substring(vStart, vEnd).trim();
                            if (!v.isEmpty()) {
                                upgradeProtocols.add(v);
                            }
                            vStart = vEnd + 1;
                        }
                    } else if ("HTTP2-Settings".equalsIgnoreCase(name)) {
                        try {
                            byte[] settings = Base64.getUrlDecoder().decode(value);
                            http2Settings = parseH2cSettings(ByteBuffer.wrap(settings));
                        } catch (IllegalArgumentException e) {
                            // Invalid base64 in HTTP2-Settings header - ignore it
                            LOGGER.log(Level.WARNING, 
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
        // RFC 9112 section 6.3: a message with both Transfer-Encoding and
        // Content-Length indicates a possible request smuggling attempt.
        if (chunked && hasExplicitContentLength) {
            try {
                sendError(400);
            } catch (ProtocolException e) {
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.te_cl_conflict"), e.getMessage()));
            }
            return;
        }
        // RFC 9110 section 10.1.1: Expect: 100-continue
        if (connection.getVersion() != HTTPVersion.HTTP_2_0
                && headers != null && contentLength != 0) {
            String expect = headers.getValue("Expect");
            if (expect != null && "100-continue".equalsIgnoreCase(expect.trim())) {
                connection.send(ByteBuffer.wrap(
                        "HTTP/1.1 100 Continue\r\n\r\n".getBytes(
                                java.nio.charset.StandardCharsets.US_ASCII)));
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
            handler.headers(this, headers);
        } else {
            // No handler yet - try to create one via factory
            // Note: We create the handler even for h2c upgrade requests, because
            // the request body (if any) arrives before the protocol switch.
            HTTPRequestHandlerFactory factory = connection.getHandlerFactory();
            if (factory != null) {
                handler = factory.createHandler(this, headers);
                if (handler != null) {
                    handler.headers(this, headers);
                }
                // If handler is null, factory may have sent a response (401, 404, etc.)
            } else {
                // No factory configured - send 404 Not Found
                try {
                    sendError(404);
                } catch (ProtocolException e) {
                    LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.default_404_failed"), e.getMessage()));
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
                L10N.getString("telemetry.http_request"), methodName);

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
     * Validates HTTP/2 request headers per RFC 9113 section 8.2 and 8.3.
     * Returns false if the headers are malformed.
     */
    private boolean validateH2Headers() {
        boolean pastPseudo = false;
        boolean hasMethod = false;
        boolean hasScheme = false;
        boolean hasPath = false;
        String methodValue = null;
        java.util.Set<String> seenPseudo = new java.util.HashSet<String>();

        for (Header header : headers) {
            String name = header.getName();
            if (name.startsWith(":")) {
                // RFC 9113 section 8.3: pseudo-headers MUST appear
                // before regular headers
                if (pastPseudo) {
                    LOGGER.warning("Pseudo-header after regular header: " + name);
                    return false;
                }
                // RFC 9113 section 8.3: each pseudo-header MUST appear
                // at most once
                if (!seenPseudo.add(name)) {
                    LOGGER.warning("Duplicate pseudo-header: " + name);
                    return false;
                }
                if (":method".equals(name)) {
                    hasMethod = true;
                    methodValue = header.getValue();
                } else if (":scheme".equals(name)) {
                    hasScheme = true;
                } else if (":path".equals(name)) {
                    hasPath = true;
                }
            } else {
                pastPseudo = true;
                String lower = name.toLowerCase();
                // RFC 9113 section 8.2.2: connection-specific headers
                // MUST NOT appear in HTTP/2
                if ("connection".equals(lower) || "keep-alive".equals(lower)
                        || "proxy-connection".equals(lower)
                        || "upgrade".equals(lower)) {
                    LOGGER.warning("Connection-specific header in HTTP/2: " + name);
                    return false;
                }
                // RFC 9113 section 8.2.2: Transfer-Encoding MUST NOT
                // appear in HTTP/2
                if ("transfer-encoding".equals(lower)) {
                    LOGGER.warning("Transfer-Encoding in HTTP/2 request");
                    return false;
                }
                // RFC 9113 section 8.2.2: TE header is allowed only
                // with value "trailers"
                if ("te".equals(lower) && !"trailers".equals(header.getValue())) {
                    LOGGER.warning("TE header with value other than 'trailers'");
                    return false;
                }
            }
        }

        // RFC 9113 section 8.3.1: CONNECT requests need only :method
        if ("CONNECT".equals(methodValue)) {
            return hasMethod;
        }
        // RFC 9113 section 8.3.1: all other requests MUST include
        // :method, :scheme, and :path
        if (!hasMethod || !hasScheme || !hasPath) {
            LOGGER.warning("Missing required pseudo-header(s): method="
                    + hasMethod + " scheme=" + hasScheme + " path=" + hasPath);
            return false;
        }
        return true;
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
            handler.requestBodyContent(this, buf);
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

        // RFC 9110 section 15.2: 1xx informational responses are lightweight
        // interim responses -- do not add entity metadata headers
        if (statusCode >= 100 && statusCode < 200) {
            connection.sendResponseHeaders(streamId, statusCode, headers, false);
            return;
        }

        // RFC 9110 section 10.2.4: Server header field
        headers.add(new Header("Server", "gumdrop/" + Gumdrop.VERSION));
        // RFC 9110 section 6.6.1: origin server SHOULD send Date in responses
        headers.add(new Header("Date", dateFormat.format(new Date())));
        // RFC 9112 section 9.6: Connection: close signals end of persistence
        if (closeConnection) {
            headers.add(new Header("Connection", "close"));
        }

        // Add traceparent header to response if telemetry is enabled
        if (span != null) {
            headers.add("traceparent", span.getSpanContext().toTraceparent());
        }

        // RFC 9110 section 8.6: warn if Content-Length is missing for responses
        // that could carry a body (not 1xx, 204, 304, and not HEAD responses)
        if (statusCode >= 200 && statusCode != 204 && statusCode != 304
                && !"HEAD".equals(method)
                && !headers.containsName("Content-Length")
                && !headers.containsName("Transfer-Encoding")) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Response " + statusCode
                        + " lacks Content-Length and Transfer-Encoding"
                        + " for " + method + " " + requestTarget);
            }
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
        // RFC 9110 section 9.3.2: suppress body content for HEAD responses
        if ("HEAD".equals(method)) {
            if (endStream && connection.getVersion() == HTTPVersion.HTTP_2_0) {
                connection.sendResponseBody(streamId, EMPTY_BUFFER.duplicate(), true);
            }
            return;
        }
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
    // RFC 9110 section 7.8: Upgrade; RFC 6455: WebSocket Protocol
    
    private boolean isWebSocketUpgradeRequest() {
        return headers != null && WebSocketHandshake.isValidWebSocketUpgrade(headers);
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // HTTPResponseState.upgradeToWebSocket Implementation
    // RFC 9110 section 15.2.2: 101 Switching Protocols
    // ─────────────────────────────────────────────────────────────────────────
    
    // The active WebSocket connection adapter (set after upgrade)
    private WebSocketConnectionAdapter webSocketAdapter;
    
    /** RFC 6455 §9.1 — upgrade with negotiated extensions. */
    @Override
    public void upgradeToWebSocket(String subprotocol,
                                   java.util.List<WebSocketExtension> extensions,
                                   WebSocketEventHandler handler) {
        upgradeToWebSocketInternal(subprotocol, extensions, handler);
    }

    @Override
    public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) {
        upgradeToWebSocketInternal(subprotocol, null, handler);
    }

    private void upgradeToWebSocketInternal(String subprotocol,
                                            java.util.List<WebSocketExtension> extensions,
                                            WebSocketEventHandler handler) {
        if (!isWebSocketUpgradeRequest()) {
            throw new IllegalStateException(L10N.getString("err.not_websocket_upgrade"));
        }
        if (responseState != ResponseState.INITIAL) {
            throw new IllegalStateException(L10N.getString("err.response_started"));
        }
        
        try {
            String key = headers.getValue("Sec-WebSocket-Key");
            String extHeader = WebSocketHandshake.formatExtensions(extensions);
            Headers responseHeaders = WebSocketHandshake.createWebSocketResponse(
                    key, subprotocol, extHeader);
            sendResponseHeaders(101, responseHeaders, false);
            
            // Resolve WebSocket metrics from the listener (if available)
            WebSocketServerMetrics wsMetrics = null;
            if (connection instanceof HTTPProtocolHandler) {
                HTTPListener listener =
                        ((HTTPProtocolHandler) connection).getListener();
                if (listener instanceof WebSocketListener) {
                    wsMetrics = ((WebSocketListener) listener)
                            .getWebSocketMetrics();
                }
            }

            webSocketAdapter = new WebSocketConnectionAdapter(
                    handler, wsMetrics);
            webSocketAdapter.setTransport(new StreamWebSocketTransport());
            if (extensions != null && !extensions.isEmpty()) {
                webSocketAdapter.setExtensions(extensions);
            }
            
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
        private final WebSocketServerMetrics wsMetrics;
        private long openedAtNanos;
        
        WebSocketConnectionAdapter(WebSocketEventHandler handler,
                                   WebSocketServerMetrics wsMetrics) {
            this.handler = handler;
            this.wsMetrics = wsMetrics;
            setServerMetrics(wsMetrics);
        }
        
        // ─────────── WebSocketConnection abstract methods ───────────
        
        @Override
        protected void opened() {
            openedAtNanos = System.nanoTime();
            if (wsMetrics != null) { wsMetrics.connectionOpened(); }
            handler.opened(this);
        }
        
        @Override
        protected void textMessageReceived(String message) {
            if (wsMetrics != null) { wsMetrics.textMessageReceived(); }
            handler.textMessageReceived(this, message);
        }
        
        @Override
        protected void binaryMessageReceived(ByteBuffer data) {
            if (wsMetrics != null) { wsMetrics.binaryMessageReceived(); }
            handler.binaryMessageReceived(this, data);
        }
        
        @Override
        protected void closed(int code, String reason) {
            if (wsMetrics != null) {
                double durationMs =
                        (System.nanoTime() - openedAtNanos) / 1_000_000.0;
                wsMetrics.connectionClosed(durationMs, code);
            }
            handler.closed(code, reason);
        }
        
        @Override
        protected void error(Throwable cause) {
            if (wsMetrics != null) { wsMetrics.error(); }
            handler.error(cause);
        }
        
        // ─────────── WebSocketSession interface (delegates to parent) ───────────
        // Note: sendText, sendBinary, sendPing, close, isOpen are already 
        // implemented in WebSocketConnection - we just expose them via the interface
        
        @Override
        public Principal getPrincipal() {
            return authenticatedPrincipal;
        }
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
    // RFC 9110 section 15: error responses
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
                    L10N.getString("telemetry.stream_closed_abnormally"));
            }
            span.end();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTPResponseState implementation
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public java.net.SocketAddress getRemoteAddress() {
        return connection.getRemoteSocketAddress();
    }

    @Override
    public java.net.SocketAddress getLocalAddress() {
        return connection.getLocalSocketAddress();
    }

    @Override
    public boolean isSecure() {
        return connection.isSecure();
    }

    @Override
    public SecurityInfo getSecurityInfo() {
        if (connection.isSecure()) {
            return connection.getSecurityInfoForStream();
        }
        return NullSecurityInfo.INSTANCE;
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
        if (responseState != ResponseState.INITIAL) {
            throw new IllegalStateException(
                    "Cannot send informational response in state: " + responseState);
        }
        // RFC 9110 section 15.2: 1xx not defined for HTTP/1.0
        if (connection.getVersion() == HTTPVersion.HTTP_1_0) {
            return;
        }
        try {
            sendResponseHeaders(statusCode, headers, false);
        } catch (ProtocolException e) {
            throw new IllegalStateException(
                    "Failed to send informational response", e);
        }
    }

    @Override
    public void headers(Headers headers) {
        if (responseState == ResponseState.COMPLETE) {
            throw new IllegalStateException(L10N.getString("err.response_complete"));
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

    // ── Backpressure / flow control ──

    private Runnable writableCallback;

    /**
     * Named callback that dispatches a one-shot write-readiness
     * notification from the connection up to the handler.
     */
    private class WriteReadyDispatcher implements Runnable {
        @Override
        public void run() {
            Runnable cb = writableCallback;
            writableCallback = null;
            connection.onWritable(streamId, null);
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
        if (callback != null) {
            connection.onWritable(streamId, writeReadyDispatcher);
        } else {
            connection.onWritable(streamId, null);
        }
    }

    @Override
    public void pauseRequestBody() {
        connection.pauseRead(streamId);
    }

    @Override
    public void resumeRequestBody() {
        connection.resumeRead(streamId);
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

    // RFC 9113 section 8.4: server push
    @Override
    public boolean pushPromise(Headers headers) {
        if (connection.getVersion() != HTTPVersion.HTTP_2_0) {
            return false;
        }
        // RFC 9113 section 6.5.2: MUST NOT send PUSH_PROMISE if
        // SETTINGS_ENABLE_PUSH is 0
        if (!connection.isEnablePush()) {
            return false;
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
            // Remove :status from headers - it's handled separately
            bufferedResponseHeaders.removeAll(":status");
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

