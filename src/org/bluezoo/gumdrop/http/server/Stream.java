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

package org.bluezoo.gumdrop.http.server;

import org.bluezoo.gumdrop.http.HeaderFields;
import java.util.ArrayList;
import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.CapsuleParser;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.ContentEncoding;
import org.bluezoo.gumdrop.http.HttpDateCache;
import org.bluezoo.gumdrop.http.HttpUtils;
import org.bluezoo.gumdrop.http.HttpVersion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.text.MessageFormat;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ResourceBundle;

import org.bluezoo.gumdrop.http.h2.H2FrameHandler;
import org.bluezoo.gumdrop.NullSecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.FieldSectionAdapter;
import org.bluezoo.gumdrop.http.HeaderCollector;
import org.bluezoo.gumdrop.http.HttpMessageRecorder;
import org.bluezoo.gumdrop.util.ByteBufferPool;
import org.bluezoo.gumdrop.websocket.WebSocketConnection;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;
import org.bluezoo.gumdrop.websocket.WebSocketMetricsSource;
import org.bluezoo.gumdrop.websocket.WebSocketServerMetrics;
import org.bluezoo.gumdrop.websocket.WebSocketSession;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.access.HttpAccessLog;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.EventLogger;

/**
 * A stream representing a single HTTP request/response exchange.
 *
 * <p>Although the concept was introduced in HTTP/2, we use the same
 * mechanism in HTTP/1. This class transparently handles
 * Transfer-Encoding: chunked in requests (RFC 9112 section 7).
 * For HTTP/1.1 responses with a body, Transfer-Encoding: chunked is
 * added automatically when Content-Length is not set.
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
class Stream implements HttpResponse {

    private EventLogger events() {
        return connection.getTelemetryConfig().getLogger(Stream.class, L10N);
    }
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");

    /** Reusable empty buffer for completing responses without body. */
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0).asReadOnlyBuffer();

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

    final HttpConnectionLike connection;
    final int streamId;

    Stream(HttpConnectionLike connection, int streamId) {
        this.connection = connection;
        this.streamId = streamId;
    }

    /**
     * Whether an error status line / HEADERS block may still be committed.
     * Includes {@link State#IDLE} because {@link #sendError} promotes IDLE to
     * OPEN before writing (HTTP/1 early parse failures, HTTP/2 before HEADERS).
     */
    boolean canCommitErrorResponse() {
        return state == State.IDLE || state == State.OPEN || state == State.HALF_CLOSED_REMOTE;
    }

    private State state = State.IDLE;
    private List<Header> headers; // NB these are the *request* headers
    private List<Header> trailerHeaders; // Trailer headers in chunked request
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

    /**
     * Set by the HTTP/2 connection once the frame that ends this stream's
     * response has been written, which is when the stream can be let go.
     */
    boolean responseEndWritten;

    // Telemetry span for this request/response (null if telemetry disabled)
    private Span span;
    private int responseStatusCode; // Saved for telemetry when body completes

    // Response body size tracking for metrics
    private long responseBodyBytes = 0L;

    /**
     * The Content-Length the handler declared for an HTTP/2 response whose
     * DATA must add up to it, or -1 when there is none to enforce (HTTP/1,
     * HEAD, 204, 304, or no declared length).
     */
    private long responseDeclaredLength = -1L;

    /** True when HTTP/1.1 response uses Transfer-Encoding: chunked (auto-added) */
    private boolean responseChunked = false;

    /** Non-null when the response body is compressed via {@code Content-Encoding}. */
    private ContentEncoding.Encoder responseContentEncoder;

    private boolean decodeRequestContentCoding;
    private boolean encodeResponseContentCoding;
    private ContentEncoding.Coding requestInboundCoding;
    private ContentEncoding.Decoder requestContentDecoder;
    private long requestDecodedBytesReceived;

    // ─────────────────────────────────────────────────────────────────────────
    // HttpResponse implementation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Response state for the event-based API.
     */
    private enum ResponseState {
        INITIAL,        // Before any response headers sent
        HEADERS_SENT,   // List<Header> sent, may send body or complete
        IN_BODY,        // After startResponseBody, sending body chunks
        BODY_COMPLETE,  // After endResponseBody, may send trailers
        COMPLETE        // After complete(), response finished
    }

    private ResponseState responseState = ResponseState.INITIAL;
    private List<Header> bufferedResponseHeaders;
    private boolean trailersStarted;
    // a push promise being built: the promised request line and fields
    private List<Header> pushHeaders;
    private HttpMethod pushMethod;
    private String pushTarget;
    private HttpRequestHandler handler;
    private boolean applicationHandlerOpened;
    private boolean requestHeadersDispatched;
    private Principal authenticatedPrincipal;
    private Principal applicationPrincipal;

    // Request body state tracking for handler dispatch
    private boolean handlerBodyStarted = false;
    private boolean handlerBodyEnded = false;
    private boolean requestBodyRejected = false;
    /**
     * Set once the framework itself has rejected this request: answered it
     * with an error (or 401 challenge) response, or reset the stream for
     * malformed HTTP/2 headers. The application never saw the request in
     * that case, so it must not be handed the rest of it (body, end of
     * request) either.
     */
    private boolean rejectedByFramework = false;

    /**
     * True once the handler has received its final callback, either
     * {@link HttpRequestHandler#endMessage} or
     * {@link HttpRequestHandler#failed}, so it is never given a second one.
     */
    private boolean handlerFinished = false;

    private boolean capsuleMode;
    private final CapsuleParser capsuleParser = new CapsuleParser();

    /**
     * Binds the application {@link HttpRequestHandler} for this stream via
     * {@link HttpConnectionLike#getStreamHandler()}, if configured.
     */
    void openApplicationHandler() {
        if (applicationHandlerOpened || handler != null) {
            return;
        }
        HttpStreamHandler streamHandler = connection.getStreamHandler();
        if (streamHandler == null) {
            return;
        }
        applicationHandlerOpened = true;
        handler = streamHandler.openStream(this);
        if (handler != null) {
            decodeRequestContentCoding = handler.decodeRequestContentCoding();
            encodeResponseContentCoding = handler.encodeResponseContentCoding();
        }
    }

    /**
     * The events of the request's header section (or trailer section) as the
     * HTTP/2 adapter produced them. The server decides what to do with a
     * request (authentication, limits, upgrade, which handler to bind) from
     * the whole header section, so the events wait here until that decision
     * has been made and then go to the application handler.
     */
    private final HttpMessageRecorder recordedEvents = new HttpMessageRecorder();

    /**
     * Set once the events of this request have been replayed to the handler;
     * from then on the body and completion events follow, so a handler never
     * sees a body without the start of the message.
     */
    private boolean messageEvents;

    /** A trailer field from the HTTP/1.x parser, which sends them as they are read. */
    void trailerField(String name, ByteBuffer value) {
        if (handler != null && messageEvents) {
            handler.header(name, value);
        }
    }

    /** Replays the recorded header-section events to the bound handler. */
    private void replayRecordedEvents() {
        if (!recordedEvents.isEmpty()) {
            messageEvents = true;
            recordedEvents.replay(handler);
            recordedEvents.clear();
        }
    }

    /**
     * Returns the URI scheme of the connection.
     */
    public String getScheme() {
        return connection.getScheme();
    }

    /** The authority of the request being answered, or null. */
    private String requestAuthority() {
        if (headers == null) {
            return null;
        }
        String authority = HeaderFields.getValue(headers, ":authority");
        return authority != null ? authority : HeaderFields.getValue(headers, "host");
    }

    @Override
    public String getConnectionId() {
        return Integer.toHexString(System.identityHashCode(connection));
    }

    @Override
    public String getProtocolConnectionId() {
        if (connection.getVersion() == HttpVersion.HTTP_2_0) {
            return Integer.toString(streamId);
        }
        return "";
    }

    @Override
    public SelectorLoop getSelectorLoop() {
        return connection.getSelectorLoop();
    }

    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return connection.scheduleTimer(delayMs, callback);
    }

    @Override
    public Trace getTrace() {
        return connection.getTrace();
    }

    /**
     * Returns the version of the connection.
     */
    public HttpVersion getVersion() {
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
    private boolean sendServerPush(String method, String uri, List<Header> headers) {
        // Only HTTP/2 connections support server push
        if (connection.getVersion() != HttpVersion.HTTP_2_0) {
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
            events().warn("warn.server_push_failed").attr("uri", uri).thrown(e).emit();
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
            trailerHeaders = new ArrayList<Header>();
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

    /** The recorder the HTTP/1.x server feeds the request's message events to. */
    HttpMessageRecorder eventRecorder() {
        return recordedEvents;
    }

    long getRequestBodyBytesReceived() {
        return requestBodyBytesReceived;
    }

    List<Header> getHeaders() {
        return headers;
    }

    void addHeader(Header header) {
        if (headers == null) {
            headers = new ArrayList<Header>();
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
        int currentSize = headerBlock == null ? 0 : headerBlock.position();
        if (currentSize + hbfLength > connection.getMaxHeaderListSize()) {
            connection.sendGoaway(H2FrameHandler.ERROR_PROTOCOL_ERROR);
            return;
        }
        if (headerBlock == null) {
            headerBlock = ByteBufferPool.acquire(Math.max(HEADER_BLOCK_INITIAL_SIZE, hbfLength));
        } else if (headerBlock.remaining() < hbfLength) {
            // Grow by at least 2x or enough for new data
            int newCapacity = Math.max(headerBlock.capacity() * 2, 
                                       headerBlock.position() + hbfLength);
            ByteBuffer newHeaderBlock = ByteBufferPool.acquire(newCapacity);
            headerBlock.flip();
            newHeaderBlock.put(headerBlock);
            ByteBufferPool.release(headerBlock);
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

    /** RFC 9113 section 8.1.1: answer a malformed request with a stream error. */
    private void rejectMalformedRequest() {
        rejectedByFramework = true;
        connection.sendRstStream(streamId, H2FrameHandler.ERROR_PROTOCOL_ERROR);
        state = State.CLOSED;
        timestampCompleted = System.currentTimeMillis();
    }

    void streamEndHeaders() {
        if (headerBlock != null) {
            headerBlock.flip();
            // RFC 7541: HPACK decompression of the header block. The decoder
            // pushes the fields into an adapter that applies the HTTP/2 rules
            // (RFC 9113 section 8.2 and 8.3) and produces the message events;
            // the same fields, as the exact octets, go to a collector for the
            // server's own use.
            recordedEvents.clear();
            HeaderCollector collected = new HeaderCollector();
            FieldSectionAdapter adapter = new FieldSectionAdapter(recordedEvents,
                    HttpVersion.HTTP_2_0,
                    requestHeadersDispatched ? FieldSectionAdapter.Kind.TRAILERS
                                             : FieldSectionAdapter.Kind.REQUEST,
                    collected);
            headers = new ArrayList<Header>();
            try {
                connection.getHpackDecoder().decode(headerBlock, adapter);
            } catch (IOException e) {
                // RFC 9113 section 4.3: HPACK decompression failure MUST
                // be treated as a connection error of type COMPRESSION_ERROR
                events().warn("warn.hpack_decompression_error").thrown(e).emit();
                ByteBufferPool.release(headerBlock);
                headerBlock = null;
                connection.sendGoaway(H2FrameHandler.ERROR_COMPRESSION_ERROR);
                return;
            }
            ByteBufferPool.release(headerBlock);
            headerBlock = null;
            // RFC 9113 section 8.1.1: a message that breaks the rules for
            // fields, or has a field that is not valid field syntax, is
            // malformed, a stream error, decided only now that the whole
            // block has been decoded so the HPACK state stays in step.
            boolean accepted = adapter.finish();
            for (Header header : HeaderFields.collected(collected)) {
                addHeader(header);
            }
            if (!accepted || collected.isMalformed()) {
                recordedEvents.clear();
                rejectMalformedRequest();
                return;
            }
        }
        if (headers != null) {
            connection.applyRfc9218Priority(streamId, headers);
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
                } else if (connection.getVersion() != HttpVersion.HTTP_2_0) { // HTTP/1
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
                        if (chunked) {
                            rejectContentLengthWithTransferEncoding();
                            return;
                        } else {
                        // RFC 9112 section 6.2 / RFC 9110 section 8.6: a
                        // malformed Content-Length, or a second one that
                        // conflicts with the first, makes the request's
                        // framing ambiguous. Silently dropping the header
                        // and continuing (the old behaviour) can desync a
                        // front-end proxy's view of the body boundary from
                        // gumdrop's, letting the tail of the "body" be
                        // reparsed as the start of a smuggled request. RFC
                        // 9112 section 6.3 requires rejecting the request
                        // instead.
                        long parsed = HttpUtils.validateContentLength(value);
                        if (parsed < 0) {
                            events().warn("warn.reject_invalid_content_length")
                                    .attr("value", value).emit();
                            try {
                                sendError(400);
                            } catch (ProtocolException e) {
                                events().warn("warn.invalid_content_length")
                                        .attr("value", value).emit();
                            }
                            return;
                        } else if (hasExplicitContentLength
                                && parsed != contentLength) {
                            events().warn("warn.reject_conflicting_content_length")
                                    .attr("parsed", parsed)
                                    .attr("content_length", contentLength).emit();
                            try {
                                sendError(400);
                            } catch (ProtocolException e) {
                                events().warn("warn.invalid_content_length")
                                        .attr("value", value).emit();
                            }
                            return;
                        } else {
                            contentLength = parsed;
                            hasExplicitContentLength = true;
                        }
                        }
                    } else if ("Transfer-Encoding".equalsIgnoreCase(name)) {
                        if (HttpUtils.isChunkedTransferEncoding(value)) {
                        // RFC 9112 section 6.3: a server MAY reject a request
                        // carrying both fields; preferring chunked framing
                        // can disagree with a proxy that framed the same
                        // bytes by Content-Length, so reject it.
                        if (hasExplicitContentLength) {
                            rejectContentLengthWithTransferEncoding();
                            return;
                        }
                        contentLength = Integer.MAX_VALUE;
                        chunked = true;
                        i.remove(); // do not pass this on to stream implementation
                        } else {
                            try {
                                sendError(400);
                            } catch (ProtocolException e) {
                                events().warn("warn.invalid_transfer_encoding")
                                        .attr("value", value).emit();
                            }
                            return;
                        }
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
                            events().warn("warn.invalid_http2_settings_base64")
                                    .attr("value", value)
                                    .thrown(e).emit();
                        }
                    }
                }
            }
            if (isUpgrade && upgradeProtocols != null) {
                this.upgrade = upgradeProtocols;
                this.h2cSettings = http2Settings;
            }
            if (connection.getVersion() != HttpVersion.HTTP_2_0
                    && !chunked && !hasExplicitContentLength) {
                // RFC 9112 section 6.3: an HTTP/1 request with neither a
                // Content-Length nor a chunked Transfer-Encoding has no
                // body, whatever its method. (An HTTP/2 request body is
                // delimited by its frames and needs no length.)
                contentLength = 0;
            }
        }
        long maxBody = connection.getMaxRequestBodySize();
        if (maxBody > 0 && connection.getVersion() == HttpVersion.HTTP_2_0 && headers != null) {
            // Check Content-Length before stripHttp1FramingHeaders removes it.
            String cl = HeaderFields.getValue(headers, "content-length");
            if (cl != null) {
                try {
                    long clValue = Long.parseLong(cl.trim());
                    if (clValue > maxBody) {
                        rejectRequestBodyTooLarge();
                        return;
                    }
                } catch (NumberFormatException ignored) {
                    // Malformed Content-Length is handled elsewhere
                }
            }
        }
        if (connection.getVersion() == HttpVersion.HTTP_2_0 && headers != null) {
            HeaderFields.stripHttp1FramingHeaders(headers, false);
        }
        if (maxBody > 0) {
            if (!chunked && contentLength > maxBody) {
                rejectRequestBodyTooLarge();
                return;
            }
        }
        // RFC 9110 section 10.1.1: Expect: 100-continue
        if (connection.getVersion() != HttpVersion.HTTP_2_0
                && headers != null && contentLength != 0) {
            String expect = HeaderFields.getValue(headers, "expect");
            if (expect != null && "100-continue".equalsIgnoreCase(expect.trim())) {
                connection.send(ByteBuffer.wrap(
                        "HTTP/1.1 100 Continue\r\n\r\n".getBytes(
                                StandardCharsets.US_ASCII)));
            }
        }
        // RFC 9110 section 11: HTTP authentication on the first header block
        // for this stream (handler may already be bound via {@link #openApplicationHandler}).
        if (!requestHeadersDispatched) {
            HttpAuthenticationProvider authProvider = connection.getAuthenticationProvider();
            if (authProvider != null) {
                String authHeader = headers != null ? HeaderFields.getValue(headers, "authorization") : null;
                HttpAuthenticationProvider.AuthenticationResult result =
                        authProvider.authenticate(authHeader, method, requestTarget);
                if (result.success) {
                    authenticatedPrincipal = new HttpPrincipal(result.username);
                } else if (authProvider.isAuthenticationRequired()) {
                    try {
                        sendUnauthorized(authProvider);
                    } catch (ProtocolException e) {
                        events().warn("warn.unauthorized_response_failed")
                                .attr("reason", e.getMessage()).emit();
                    }
                    return;
                }
            }
        }
        // Initialize telemetry span if enabled
        initTelemetrySpan();

        // RFC 9297 / RFC 9484 / RFC 9298: Capsule-Protocol on the request
        // must enable capsuleMode even when the handler was bound up front
        // via HttpStreamHandler.openStream() (the late-bind branch below
        // already did this; pre-bound handlers did not).
        if (!requestHeadersDispatched && headers != null) {
            capsuleMode = Capsule.capsuleProtocolEnabled(
                    HeaderFields.getValue(headers, Capsule.PROTOCOL_HEADER));
        }
        
        // Dispatch to handler if present
        requestHeadersDispatched = true;
        if (handler != null) {
            // Handler already set - this is a continuation or trailer headers
            if (handlerBodyStarted && !handlerBodyEnded) {
                // Body was in progress, this must be trailer headers
                handlerBodyEnded = true;
            } else if (!handlerBodyStarted && !prepareRequestContentDecoding(headers)) {
                return;
            }
            replayRecordedEvents();
        } else {
            openApplicationHandler();
            if (handler != null) {
                if (!prepareRequestContentDecoding(headers)) {
                    return;
                }
                replayRecordedEvents();
                capsuleMode = Capsule.capsuleProtocolEnabled(
                    HeaderFields.getValue(headers, Capsule.PROTOCOL_HEADER));
            } else if (responseState == ResponseState.INITIAL) {
                try {
                    sendError(404);
                } catch (ProtocolException e) {
                    events().warn("warn.default_404_failed").attr("reason", e.getMessage()).emit();
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
        HttpServerMetrics metrics = getServerMetrics();
        if (metrics != null) {
            metrics.requestStarted(method != null ? method : "UNKNOWN");
        }

        TelemetryConfig telemetryConfig = connection.getTelemetryConfig();
        if (telemetryConfig == null) {
            return;
        }
        Trace trace = connection.getTrace();

        // Check for incoming traceparent header (distributed tracing)
        String traceparent = headers != null ? HeaderFields.getValue(headers, "traceparent") : null;

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
            String host = headers != null ? HeaderFields.getValue(headers, "host") : null;
            if (host != null) {
                span.addAttribute("http.host", host);
            }

            // Add User-Agent if present
            String userAgent = headers != null ? HeaderFields.getValue(headers, "user-agent") : null;
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
        receiveRequestBody(buf);
    }

    /**
     * Receive request body data from the specified frame data (HTTP/2).
     */
    void appendRequestBody(byte[] bytes) {
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
        if (rejectedByFramework) {
            // Discarded, but still counted: HTTP/1.x framing finishes the
            // request (streamEndRequest, and the deferred Connection: close)
            // only once the declared body length has been consumed.
            requestBodyBytesReceived += buf.remaining();
            buf.position(buf.limit());
            return;
        }
        if (webSocketAdapter != null) {
            processWebSocketInput(buf);
            return;
        }

        if (hasWebSocketUpgrade()) {
            bufferPendingWebSocketData(buf);
            return;
        }

        if (capsuleMode) {
            dispatchCapsules(buf);
            buf.position(buf.limit());
            return;
        }

        if (requestBodyRejected) {
            buf.position(buf.limit());
            return;
        }

        int bytesToConsume = buf.remaining();
        long maxBody = connection.getMaxRequestBodySize();
        if (maxBody > 0 && requestBodyBytesReceived + bytesToConsume > maxBody) {
            buf.position(buf.limit());
            rejectRequestBodyTooLarge();
            return;
        }

        requestBodyBytesReceived += bytesToConsume;

        if (requestInboundCoding != null || requestContentDecoder != null) {
            try {
                ensureRequestContentDecoder();
                if (buf != null && buf.hasRemaining()) {
                    requestContentDecoder.write(buf, false);
                }
                if (!drainDecodedRequestBody(false)) {
                    buf.position(buf.limit());
                    return;
                }
            } catch (ContentEncoding.ContentEncodingException e) {
                buf.position(buf.limit());
                try {
                    sendError(400);
                } catch (ProtocolException pe) {
                    events().warn("warn.request_content_decoding_failed")
                            .attr("reason", e.getMessage()).emit();
                }
                return;
            }
            buf.position(buf.limit());
            return;
        }

        // Dispatch to handler if present
        if (handler != null) {
            handlerBodyStarted = true;
            if (messageEvents) {
                handler.bodyContent(buf.asReadOnlyBuffer());
            }
        }

        // Consume any remaining data (handler may not have consumed it)
        buf.position(buf.limit());
    }

    private void dispatchCapsules(ByteBuffer buf) {
        List<Capsule> capsules;
        try {
            capsules = capsuleParser.push(buf);
        } catch (CapsuleParser.CapsuleException e) {
            try {
                sendError(400);
            } catch (ProtocolException ignored) {
            }
            return;
        }
        for (int i = 0; i < capsules.size(); i++) {
            Capsule capsule = capsules.get(i);
            if (capsule.getType() == Capsule.TYPE_DATAGRAM) {
                if (handler != null && handler.wantsDatagrams()) {
                    handler.datagramReceived(this, ByteBuffer.wrap(capsule.getValue()));
                }
            } else if (handler != null) {
                handler.capsuleReceived(this, capsule.getType(),
                        ByteBuffer.wrap(capsule.getValue()));
            }
        }
    }

    private void rejectContentLengthWithTransferEncoding() {
        events().warn("warn.reject_content_length_and_transfer_encoding").emit();
        try {
            sendError(400);
        } catch (ProtocolException e) {
            events().warn("warn.reject_content_length_and_transfer_encoding").emit();
        }
    }

    private void rejectRequestBodyTooLarge() {
        if (requestBodyRejected) {
            return;
        }
        requestBodyRejected = true;
        try {
            sendError(413);
        } catch (ProtocolException e) {
            events().warn("warn.request_body_too_large").attr("reason", e.getMessage()).emit();
        }
    }

    /**
     * Marks a fully-received, bodyless request as complete for an internally
     * generated response (RFC 9110 section 9.3.7 OPTIONS *, section 9.3.8
     * TRACE). These are answered directly by the protocol handler and bypass
     * the normal {@link #streamEndHeaders}/{@link #streamEndRequest} dispatch,
     * which would otherwise leave the stream in {@link State#IDLE} and cause
     * {@link #sendResponseHeaders} to reject the response. Transitions IDLE to
     * HALF_CLOSED_REMOTE so the response can be committed and, when
     * {@code endStream} is set, {@code Connection: close} honoured.
     */
    void markInternalRequestComplete() {
        if (state == State.IDLE) {
            state = State.HALF_CLOSED_REMOTE;
        }
    }

    void streamEndRequest() {
        // RFC 9113 section 5.1: a stream is closed once both endpoints have
        // sent END_STREAM. The response side reaches HALF_CLOSED_LOCAL when
        // a handler completes the response entirely from within its
        // headers() callback - the common case, since streamEndHeaders()
        // dispatches to the handler before this method (called by the
        // caller only afterwards) applies the request's own end-of-stream.
        // The request side is ending right now, so if the response is
        // already fully sent the stream is fully closed too. Before this
        // fix, the state reassignment below unconditionally overwrote
        // HALF_CLOSED_LOCAL with HALF_CLOSED_REMOTE, so such a stream never
        // reached CLOSED and its activeStreams concurrency slot (see
        // streamResponseCompleted()) leaked for the life of the connection,
        // eventually exhausting SETTINGS_MAX_CONCURRENT_STREAMS.
        boolean responseAlreadySent = state == State.HALF_CLOSED_LOCAL;

        // RFC 9112 section 9.6: when the complete response was already
        // committed while the request body was still outstanding, the stream
        // sits in HALF_CLOSED_LOCAL and sendResponseHeaders/sendResponseBody
        // could not honour Connection: close yet (the request was not fully
        // received). This is the common case for internally generated
        // bodyless responses such as the default 404 for a handler-less
        // listener, which are sent from streamEndHeaders() before this method
        // marks the request complete. Now that the request has finished, close
        // the connection as promised; otherwise a client that sent
        // "Connection: close" and reads until EOF (as it is entitled to) hangs
        // until the idle/drain timeout and the connection slot leaks.
        boolean closeAfterResponse = responseAlreadySent
                && closeConnection
                && connection.getVersion() != HttpVersion.HTTP_2_0;

        if (responseAlreadySent) {
            state = State.CLOSED;
            timestampCompleted = System.currentTimeMillis();
            if (connection instanceof HttpProtocolHandler) {
                ((HttpProtocolHandler) connection).streamResponseCompleted(streamId);
            }
        } else if (state != State.CLOSED) {
            state = State.HALF_CLOSED_REMOTE;
        }

        // Dispatch to handler if present (but not for a request the
        // framework already rejected, e.g. unauthenticated or malformed)
        if (handler != null && !rejectedByFramework) {
            if (capsuleMode && !capsuleParser.finish()) {
                try {
                    sendError(400);
                } catch (ProtocolException e) {
                    events().warn("warn.truncated_capsule").attr("reason", e.getMessage()).emit();
                }
                return;
            }
            if (handlerBodyStarted && !handlerBodyEnded) {
                // Body was started but not ended (no trailers)
                handlerBodyEnded = true;
            } else if (requestInboundCoding != null || requestContentDecoder != null) {
                if (!drainDecodedRequestBody(true)) {
                    return;
                }
            }
            // A handler that fully answered from endHeaders() must not
            // receive a second end; neither must a stream whose response
            // was already committed by an earlier streamEndRequest().
            if (responseState != ResponseState.COMPLETE) {
                handlerFinished = true;
                if (messageEvents) {
                    handler.endMessage();
                }
            }
        }

        if (closeAfterResponse) {
            connection.send(null);
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
    final void sendResponseHeaders(int statusCode, List<Header> headers, boolean endStream) throws ProtocolException {
        if (state != State.HALF_CLOSED_REMOTE && state != State.OPEN) {
            throw new ProtocolException("Invalid state: " + state);
        }

        // RFC 9110 section 15.2: 1xx informational responses are lightweight
        // interim responses -- do not add entity metadata headers
        if (statusCode >= 100 && statusCode < 200) {
            connection.sendResponseHeaders(streamId, statusCode, headers, false);
            return;
        }

        // Snapshot which framework-managed headers the application already
        // set, in one pass over headers as they stand before this method
        // adds anything of its own (issue #278).
        boolean hasXFrameOptions = false;
        boolean hasXContentTypeOptions = false;
        boolean hasContentLength = false;
        boolean hasTransferEncoding = false;
        boolean hasContentEncoding = false;
        for (Header existing : headers) {
            String existingName = existing.getName();
            if ("x-frame-options".equalsIgnoreCase(existingName)) {
                hasXFrameOptions = true;
            } else if ("x-content-type-options".equalsIgnoreCase(existingName)) {
                hasXContentTypeOptions = true;
            } else if ("content-length".equalsIgnoreCase(existingName)) {
                hasContentLength = true;
            } else if ("transfer-encoding".equalsIgnoreCase(existingName)) {
                hasTransferEncoding = true;
            } else if ("content-encoding".equalsIgnoreCase(existingName)) {
                hasContentEncoding = true;
            }
        }

        // RFC 9110 section 10.2.4: Server header field
        //
        // These values are the exact HttpProtocolHandler.*_VALUE constants
        // (not just equal-looking literals) so that writeWellKnownLine's
        // reference-equality fast path there actually applies: it exists
        // specifically to bulk-write these framework-fixed lines instead of
        // encoding their characters again on every single response.
        headers.add(new Header("Server", HttpProtocolHandler.SERVER_HEADER_VALUE));
        // RFC 9110 section 6.6.1: origin server SHOULD send Date in responses
        headers.add(new Header("Date", HttpDateCache.get()));
        // RFC 9112 section 9.6: Connection: close signals end of persistence
        if (closeConnection) {
            headers.add(new Header("Connection", HttpProtocolHandler.CONNECTION_CLOSE_VALUE));
        }

        // Add default security headers if enabled and not already set
        if (connection instanceof HttpProtocolHandler) {
            Http2Listener listener =
                    ((HttpProtocolHandler) connection).getListener();
            if (listener != null && listener.getAddSecurityHeaders()) {
                if (!hasXFrameOptions) {
                    headers.add(new Header("X-Frame-Options", HttpProtocolHandler.X_FRAME_OPTIONS_VALUE));
                }
                if (!hasXContentTypeOptions) {
                    headers.add(new Header("X-Content-Type-Options", HttpProtocolHandler.X_CONTENT_TYPE_OPTIONS_VALUE));
                }
            }
            if (listener != null) {
                String hsts = listener.getStrictTransportSecurityHeaderValue();
                if (hsts != null
                        && !HeaderFields.containsName(headers, "Strict-Transport-Security")) {
                    headers.add(new Header("Strict-Transport-Security", hsts));
                }
            }
        }

        // Add traceparent header to response if telemetry is enabled
        if (span != null) {
            HeaderFields.add(headers, "traceparent", span.getSpanContext().toTraceparent());
        }

        if (shouldCompressResponse(statusCode, endStream, hasContentLength,
                hasTransferEncoding, hasContentEncoding)) {
            String acceptEncoding = this.headers != null
                    ? HeaderFields.getCombinedValue(this.headers, "Accept-Encoding") : null;
            ContentEncoding.Coding coding =
                    ContentEncoding.selectFromAcceptEncoding(acceptEncoding);
            if (coding != null) {
                responseContentEncoder = ContentEncoding.createEncoder(coding);
                HeaderFields.add(headers, "Content-Encoding", coding.token());
                HeaderFields.removeAll(headers, "Content-Length");
                hasContentLength = false;
            }
        }

        // RFC 9112 section 6.3: for HTTP/1.1 responses with a body, use
        // Transfer-Encoding: chunked when Content-Length is not set
        if (connection.getVersion() == HttpVersion.HTTP_1_1
                && statusCode >= 200 && statusCode != 204 && statusCode != 304
                && !"HEAD".equals(method)
                && !hasContentLength
                && !hasTransferEncoding) {
            if (endStream) {
                // The response ends with its headers: there is no body and
                // so no last-chunk will ever follow. Chunked framing here
                // would leave the client waiting for a terminator that
                // never comes, so delimit the (empty) body explicitly.
                HeaderFields.add(headers, "Content-Length", "0");
            } else {
                HeaderFields.add(headers, "Transfer-Encoding", HttpProtocolHandler.TRANSFER_ENCODING_CHUNKED_VALUE);
                responseChunked = true;
            }
        }

        // Save status code for telemetry
        this.responseStatusCode = statusCode;

        // RFC 9113 section 8.1.1: a Content-Length on an HTTP/2 response
        // must equal the DATA bytes. A HEAD, 204 or 304 response carries
        // none, so what it declares is not checked.
        if (hasContentLength && connection.getVersion() == HttpVersion.HTTP_2_0
                && statusCode != 204 && statusCode != 304 && !"HEAD".equals(method)) {
            responseDeclaredLength = parseDeclaredLength(
                    HeaderFields.getValue(headers, "Content-Length"));
            if (responseDeclaredLength >= 0 && endStream && responseDeclaredLength != 0) {
                abortResponseLength();
                return;
            }
        }

        connection.sendResponseHeaders(streamId, statusCode, headers, endStream);
        if (endStream) {
            if (state == State.HALF_CLOSED_REMOTE) {
                state = State.CLOSED; // normal request termination
                timestampCompleted = System.currentTimeMillis();
                if (connection instanceof HttpProtocolHandler) {
                    ((HttpProtocolHandler) connection).streamResponseCompleted(streamId);
                }
                // Close TCP connection if Connection: close was set
                if (closeConnection && connection.getVersion() != HttpVersion.HTTP_2_0) {
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
     * Parses a Content-Length field value.
     *
     * @return the length, or -1 if the value is not a valid one
     */
    private static long parseDeclaredLength(String value) {
        if (value == null) {
            return -1L;
        }
        try {
            long length = Long.parseLong(value.trim());
            return length >= 0 ? length : -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * Abandons an HTTP/2 response whose body does not add up to the
     * Content-Length it declared (RFC 9113 section 8.1.1): the stream is
     * reset with INTERNAL_ERROR rather than ended as if it were sound.
     */
    private void abortResponseLength() {
        events().warn("warn.response_length_mismatch")
                .attr("stream_id", streamId)
                .attr("response_declared_length", responseDeclaredLength)
                .attr("response_body_bytes", responseBodyBytes).emit();
        responseDeclaredLength = -1L;
        connection.sendRstStream(streamId, H2FrameHandler.ERROR_INTERNAL_ERROR);
        responseState = ResponseState.COMPLETE;
        bufferedResponseHeaders = null;
        streamClose(false);
    }

    /**
     * Ends the telemetry span for this request with the given status code.
     *
     * @param statusCode the HTTP response status code
     */
    private void endTelemetrySpan(int statusCode) {
        // Record metrics for request completion
        HttpServerMetrics metrics = getServerMetrics();
        if (metrics != null && timestampStarted > 0) {
            double durationMs = System.currentTimeMillis() - timestampStarted;
            metrics.requestCompleted(
                    method != null ? method : "UNKNOWN",
                    statusCode,
                    durationMs,
                    requestBodyBytesReceived,
                    responseBodyBytes);
        }

        long completedAt = timestampCompleted > 0
                ? timestampCompleted : System.currentTimeMillis();
        TelemetryConfig telemetryConfig = connection.getTelemetryConfig();
        if (telemetryConfig != null && telemetryConfig.accepts(LogLevel.ACCESS)) {
            HttpAccessLog.record(
                    telemetryConfig,
                    span,
                    completedAt,
                    connection.getRemoteSocketAddress(),
                    method,
                    requestTarget,
                    connection.getVersion() != null
                            ? connection.getVersion().toString() : null,
                    authenticatedPrincipal,
                    applicationPrincipal,
                    statusCode,
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

    private HttpServerMetrics getServerMetrics() {
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
        if (responseDeclaredLength >= 0) {
            long total = responseBodyBytes + bytesToAdd;
            if (total > responseDeclaredLength) {
                responseBodyBytes = total;
                abortResponseLength();
                throw new ProtocolException(MessageFormat.format(
                        L10N.getString("warn.response_length_mismatch"),
                        Integer.valueOf(streamId), Long.valueOf(responseDeclaredLength),
                        Long.valueOf(total)));
            }
            if (endStream && total != responseDeclaredLength) {
                responseBodyBytes = total;
                abortResponseLength();
                return;
            }
        }
        boolean closeAfter = sendResponseBodyInternal(bytesToAdd, endStream);
        writeResponseBody(buf, endStream);
        if (closeAfter) {
            // RFC 9112 section 9.6: Connection: close, once the last of the
            // response has been written. Closing first loses it on a TLS
            // connection, whose outbound side shuts with the close.
            connection.send(null);
        }
    }

    private void writeResponseBody(ByteBuffer buf, boolean endStream) throws ProtocolException {
        // RFC 9110 section 9.3.2: suppress body content for HEAD responses
        if ("HEAD".equals(method)) {
            if (endStream && connection.getVersion() == HttpVersion.HTTP_2_0) {
                connection.sendResponseBody(streamId, EMPTY_BUFFER.duplicate(), true);
            }
            return;
        }
        if (responseContentEncoder != null) {
            try {
                responseContentEncoder.write(buf, endStream);
            } catch (ContentEncoding.ContentEncodingException e) {
                throw new ProtocolException(e.getMessage());
            }
            ByteBuffer encoded;
            while ((encoded = responseContentEncoder.readEncoded()) != null) {
                sendResponseBodyWire(encoded, false);
            }
            if (endStream) {
                responseContentEncoder.close();
                responseContentEncoder = null;
                sendResponseBodyWire(EMPTY_BUFFER.duplicate(), true);
            }
            return;
        }
        sendResponseBodyWire(buf, endStream);
    }

    private void sendResponseBodyWire(ByteBuffer buf, boolean endStream) throws ProtocolException {
        if (responseChunked) {
            ByteBuffer toSend = formatChunkedBody(buf, endStream);
            try {
                connection.sendResponseBody(streamId, toSend, endStream);
            } finally {
                ByteBufferPool.release(toSend);
            }
        } else {
            connection.sendResponseBody(streamId, buf, endStream);
        }
    }

    private boolean shouldCompressResponse(int statusCode, boolean endStream,
            boolean hasContentLength, boolean hasTransferEncoding,
            boolean hasContentEncoding) {
        if (responseContentEncoder != null || endStream) {
            return false;
        }
        if (hasContentEncoding || hasTransferEncoding || hasContentLength) {
            return false;
        }
        if ("HEAD".equals(method) || statusCode == 204 || statusCode == 304) {
            return false;
        }
        if (statusCode < 200 || statusCode >= 300) {
            return false;
        }
        if (hasWebSocketUpgrade()) {
            return false;
        }
        if (!(connection instanceof HttpProtocolHandler)) {
            return false;
        }
        if (!encodeResponseContentCoding) {
            return false;
        }
        Http2Listener listener = ((HttpProtocolHandler) connection).getListener();
        return listener != null && listener.getCompressResponses();
    }

    /**
     * Parses {@code Content-Encoding} for inbound request bodies when the
     * handler opts in to transparent decoding.
     *
     * @return false if a response was sent and dispatch must stop
     */
    private boolean prepareRequestContentDecoding(List<Header> headers) {
        if (!decodeRequestContentCoding || headers == null) {
            return true;
        }
        String encoding = HeaderFields.getCombinedValue(headers, "Content-Encoding");
        if (encoding == null || encoding.isEmpty()) {
            return true;
        }
        ContentEncoding.Coding coding = ContentEncoding.parseContentEncoding(encoding);
        if (coding == null) {
            try {
                sendError(415);
            } catch (ProtocolException e) {
                events().warn("warn.unsupported_content_encoding")
                        .attr("encoding", encoding).emit();
            }
            return false;
        }
        requestInboundCoding = coding;
        HeaderFields.removeAll(headers, "Content-Encoding");
        return true;
    }

    private void ensureRequestContentDecoder() throws ContentEncoding.ContentEncodingException {
        if (requestContentDecoder != null || requestInboundCoding == null) {
            return;
        }
        long maxBody = connection.getMaxRequestBodySize();
        int maxDecoded = maxBody > 0 && maxBody <= Integer.MAX_VALUE
                ? (int) maxBody : ContentEncoding.DEFAULT_MAX_DECOMPRESSED_SIZE;
        requestContentDecoder = ContentEncoding.createDecoder(requestInboundCoding, maxDecoded);
        requestInboundCoding = null;
    }

    /**
     * Drains decoded request body bytes to the handler.
     *
     * @param finish true to finish the compressed stream
     * @return false if an error response was sent
     */
    private boolean drainDecodedRequestBody(boolean finish) {
        if (requestContentDecoder == null && requestInboundCoding == null) {
            return true;
        }
        try {
            ensureRequestContentDecoder();
            if (finish) {
                requestContentDecoder.write(ByteBuffer.allocate(0), true);
            }
            ByteBuffer decoded;
            while ((decoded = requestContentDecoder.readDecoded()) != null) {
                if (handler == null) {
                    continue;
                }
                int n = decoded.remaining();
                long maxBody = connection.getMaxRequestBodySize();
                if (maxBody > 0 && requestDecodedBytesReceived + n > maxBody) {
                    rejectRequestBodyTooLarge();
                    return false;
                }
                requestDecodedBytesReceived += n;
                handlerBodyStarted = true;
                if (messageEvents) {
                    handler.bodyContent(decoded.asReadOnlyBuffer());
                }
            }
            if (finish) {
                requestContentDecoder.close();
                requestContentDecoder = null;
                if (handler != null && handlerBodyStarted && !handlerBodyEnded) {
                    handlerBodyEnded = true;
                }
            }
        } catch (ContentEncoding.ContentEncodingException e) {
            try {
                sendError(400);
            } catch (ProtocolException pe) {
                events().warn("warn.request_content_decoding_failed")
                        .attr("reason", e.getMessage()).emit();
            }
            return false;
        }
        return true;
    }

    /**
     * Formats body data as RFC 9112 section 7.1 chunked encoding.
     * Returns a buffer containing chunk-size + chunk-data, and optionally
     * the final 0 chunk when endStream is true.
     */
    private ByteBuffer formatChunkedBody(ByteBuffer buf, boolean endStream) {
        int dataLen = (buf != null) ? buf.remaining() : 0;
        int trailerLen = endStream ? 5 : 0;  // "0\r\n\r\n"
        int totalLen = 0;
        if (dataLen > 0) {
            String sizeHex = Integer.toHexString(dataLen);
            totalLen += sizeHex.length() + 2 + dataLen + 2;  // size CRLF data CRLF
        }
        totalLen += trailerLen;
        ByteBuffer out = ByteBufferPool.acquire(totalLen);
        if (dataLen > 0) {
            String sizeHex = Integer.toHexString(dataLen);
            out.put(sizeHex.getBytes(StandardCharsets.US_ASCII));
            out.put((byte) '\r');
            out.put((byte) '\n');
            out.put(buf);
            out.put((byte) '\r');
            out.put((byte) '\n');
        }
        if (endStream) {
            out.put((byte) '0');
            out.put((byte) '\r');
            out.put((byte) '\n');
            out.put((byte) '\r');
            out.put((byte) '\n');
        }
        out.flip();
        return out;
    }

    /**
     * Common state management for sendResponseBody.
     *
     * @return true if the connection is to be closed once the body data
     *         this call accounts for has been written
     */
    private boolean sendResponseBodyInternal(int bytesToAdd, boolean endStream) throws ProtocolException {
        if (state != State.HALF_CLOSED_REMOTE && state != State.OPEN) {
            throw new ProtocolException("Invalid state: " + state);
        }
        responseBodyBytes += bytesToAdd;
        boolean closeAfter = false;
        if (endStream) {
            if (state == State.HALF_CLOSED_REMOTE) {
                state = State.CLOSED;
                timestampCompleted = System.currentTimeMillis();
                if (connection instanceof HttpProtocolHandler) {
                    ((HttpProtocolHandler) connection).streamResponseCompleted(streamId);
                }
                closeAfter = closeConnection && connection.getVersion() != HttpVersion.HTTP_2_0;
            } else {
                state = State.HALF_CLOSED_LOCAL;
            }
            endTelemetrySpan(responseStatusCode);
        }
        return closeAfter;
    }

    // -- WebSocket Support (Internal) --
    // RFC 9110 section 7.8: Upgrade; RFC 6455: WebSocket Protocol
    
    /**
     * Returns whether this stream's request advertised an RFC 6455 WebSocket
     * upgrade (via {@code Connection: Upgrade} / {@code Upgrade: websocket}).
     * Used to hold pipelined frame bytes while an async upgrade handler
     * (e.g. servlet {@code HttpUpgradeHandler}) runs on a worker thread.
     */
    boolean hasWebSocketUpgrade() {
        if (upgrade == null) {
            return false;
        }
        for (String protocol : upgrade) {
            if ("websocket".equalsIgnoreCase(protocol)) {
                return true;
            }
        }
        return false;
    }

    private void bufferPendingWebSocketData(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            return;
        }
        int n = buf.remaining();
        if (pendingWebSocketData == null) {
            pendingWebSocketData = ByteBuffer.allocate(n);
        } else if (pendingWebSocketData.remaining() < n) {
            ByteBuffer expanded = ByteBuffer.allocate(
                    pendingWebSocketData.position() + pendingWebSocketData.remaining() + n);
            pendingWebSocketData.flip();
            expanded.put(pendingWebSocketData);
            pendingWebSocketData = expanded;
        }
        pendingWebSocketData.put(buf);
        buf.position(buf.limit());
    }

    private void processWebSocketInput(ByteBuffer buf) {
        // Unlike the general body path below, WebSocket frames are
        // push-parsed the same way as H2Parser: processIncomingData()
        // consumes as many complete frames as the buffer holds and
        // leaves the position at the start of any incomplete trailing
        // frame (WebSocketFrame.parse() rewinds on insufficient data).
        // That leftover MUST NOT be force-consumed here — it needs to
        // survive to the next receiveRequestBody() call the same way
        // an incomplete H2 frame or line-lexer token does, relying on
        // the transport to compact and preserve it across reads.
        try {
            webSocketAdapter.processIncomingData(buf);
        } catch (IOException e) {
            events().warn("warn.error_websocket_data").thrown(e).emit();
        }
    }

    private void drainPendingWebSocketData() {
        if (pendingWebSocketData == null || pendingWebSocketData.position() == 0) {
            return;
        }
        pendingWebSocketData.flip();
        processWebSocketInput(pendingWebSocketData);
        pendingWebSocketData = null;
    }

    private boolean isWebSocketUpgradeRequest() {
        if (headers == null) {
            return false;
        }
        // RFC 8441 section 4 -- WebSocket-over-HTTP/2 uses Extended CONNECT
        // (:method CONNECT, :protocol websocket) instead of the RFC 6455
        // Upgrade: handshake, which HTTP/2 forbids as a connection-specific
        // header field.
        if (connection.getVersion() == HttpVersion.HTTP_2_0) {
            return "CONNECT".equals(HeaderFields.getValue(headers, ":method"))
                    && "websocket".equalsIgnoreCase(HeaderFields.getValue(headers, ":protocol"));
        }
        return WebSocketHandshake.isValidWebSocketUpgrade(
                HeaderFields.getCombinedValue(headers, "Upgrade"),
                HeaderFields.getCombinedValue(headers, "Connection"),
                HeaderFields.getValue(headers, "Sec-WebSocket-Key"),
                HeaderFields.getValue(headers, "Sec-WebSocket-Version"));
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // HttpResponse.upgradeToWebSocket Implementation
    // RFC 9110 section 15.2.2: 101 Switching Protocols
    // ─────────────────────────────────────────────────────────────────────────
    
    // The active WebSocket connection adapter (set after upgrade)
    private WebSocketConnectionAdapter webSocketAdapter;

    /** Pipelined WebSocket frame bytes received before the adapter exists. */
    private ByteBuffer pendingWebSocketData;
    
    /** RFC 6455 §9.1 — upgrade with negotiated extensions. */
    @Override
    public void upgradeToWebSocket(String subprotocol,
                                   List<WebSocketExtension> extensions,
                                   WebSocketEventHandler handler) {
        upgradeToWebSocketInternal(subprotocol, extensions, handler);
    }

    @Override
    public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) {
        upgradeToWebSocketInternal(subprotocol, null, handler);
    }

    private void upgradeToWebSocketInternal(String subprotocol,
                                            List<WebSocketExtension> extensions,
                                            WebSocketEventHandler handler) {
        if (!isWebSocketUpgradeRequest()) {
            throw new IllegalStateException(L10N.getString("err.not_websocket_upgrade"));
        }
        if (responseState != ResponseState.INITIAL) {
            throw new IllegalStateException(L10N.getString("err.response_started"));
        }
        
        try {
            boolean h2 = connection.getVersion() == HttpVersion.HTTP_2_0;
            if (h2) {
                // RFC 8441 section 4 -- accept the upgrade with a 200
                // response; there is no Sec-WebSocket-Key/-Accept exchange
                // (HTTP/2 already runs over TLS, unlike RFC 6455's original
                // plaintext-friendly design). Reuses the generic response
                // path (sendResponseHeaders), so this response also carries
                // Server/Date/security headers a normal 200 would -- H3's
                // equivalent 200 does not, since it builds its headers by
                // hand; harmless, just a minor cross-transport divergence.
                List<Header> responseHeaders = new ArrayList<Header>();
                if (subprotocol != null && !subprotocol.isEmpty()) {
                    HeaderFields.add(responseHeaders, "sec-websocket-protocol", subprotocol);
                }
                String extHeader = WebSocketHandshake.formatExtensions(extensions);
                if (extHeader != null && !extHeader.isEmpty()) {
                    HeaderFields.add(responseHeaders, "sec-websocket-extensions", extHeader);
                }
                sendResponseHeaders(200, responseHeaders, false);
            } else {
                String key = HeaderFields.getValue(headers, "sec-websocket-key");
                String extHeader = WebSocketHandshake.formatExtensions(extensions);
                // RFC 6455 section 4.2.2
                List<Header> responseHeaders = new ArrayList<Header>();
                HeaderFields.add(responseHeaders, "Upgrade", "websocket");
                HeaderFields.add(responseHeaders, "Connection", "Upgrade");
                HeaderFields.add(responseHeaders, "Sec-WebSocket-Accept",
                        WebSocketHandshake.calculateAccept(key));
                if (subprotocol != null && !subprotocol.trim().isEmpty()) {
                    HeaderFields.add(responseHeaders, "Sec-WebSocket-Protocol", subprotocol.trim());
                }
                if (extHeader != null && !extHeader.trim().isEmpty()) {
                    HeaderFields.add(responseHeaders, "Sec-WebSocket-Extensions", extHeader.trim());
                }
                sendResponseHeaders(101, responseHeaders, false);
            }

            // Resolve WebSocket metrics from the upgrading handler (if it
            // opts in), not the listener -- handler-scoped, so any
            // HttpStreamHandler can supply WebSocket metrics regardless of
            // which listener type it's composed onto.
            WebSocketServerMetrics wsMetrics = null;
            if (this.handler instanceof WebSocketMetricsSource) {
                wsMetrics = ((WebSocketMetricsSource) this.handler)
                        .getWebSocketMetrics();
            }

            webSocketAdapter = new WebSocketConnectionAdapter(
                    handler, wsMetrics);
            webSocketAdapter.setTransport(new StreamWebSocketTransport());
            if (extensions != null && !extensions.isEmpty()) {
                webSocketAdapter.setExtensions(extensions);
            }
            
            // Configure telemetry if enabled
            if (connection.getTelemetryConfig() != null) {
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

            // Deliver any frame bytes pipelined into the same read as the
            // upgrade request before the adapter existed (async servlet
            // upgrade on a worker thread).
            drainPendingWebSocketData();

        } catch (ProtocolException e) {
            throw new IllegalStateException("Failed to send WebSocket upgrade response", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HttpResponse.acceptConnectUdp/acceptConnectIp Implementation
    // (RFC 9298, RFC 9484)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns whether this stream's request is Extended CONNECT for
     * {@code protocolToken} (RFC 9298 section 3 / RFC 9484 section 4:
     * {@code :method: CONNECT}, {@code :protocol: <protocolToken>} on
     * HTTP/2; a literal {@code Upgrade: <protocolToken>} on HTTP/1.1,
     * which has no {@code :protocol} pseudo-header of its own -- RFC 9110
     * section 7.8 forbids Upgrade over HTTP/2, and neither RFC defines
     * an interim response the way {@code isWebSocketUpgradeRequest()}'s
     * sibling check would need to for RFC 8441).
     *
     * @param protocolToken the {@code :protocol}/{@code Upgrade} value,
     *        e.g. {@code "connect-udp"} or {@code "connect-ip"}
     */
    private boolean isExtendedConnectRequest(String protocolToken) {
        if (headers == null) {
            return false;
        }
        if (connection.getVersion() == HttpVersion.HTTP_2_0) {
            return "CONNECT".equals(HeaderFields.getValue(headers, ":method"))
                    && protocolToken.equalsIgnoreCase(HeaderFields.getValue(headers, ":protocol"));
        }
        return protocolToken.equalsIgnoreCase(HeaderFields.getValue(headers, "upgrade"));
    }

    /**
     * Shared implementation behind {@link #acceptConnectUdp} and {@link
     * #acceptConnectIp}: both accept identically at the HTTP layer,
     * differing only in the {@code :protocol}/{@code Upgrade} token that
     * identifies which one a given request is.
     *
     * @param protocolToken the {@code :protocol}/{@code Upgrade} value,
     *        e.g. {@code "connect-udp"} or {@code "connect-ip"}
     */
    private boolean acceptExtendedConnect(String protocolToken) {
        if (!isExtendedConnectRequest(protocolToken)) {
            return false;
        }
        if (responseState != ResponseState.INITIAL) {
            return false;
        }
        try {
            if (connection.getVersion() == HttpVersion.HTTP_2_0) {
                // RFC 9298 section 3 / RFC 9484 section 4: a 2xx response
                // accepts the tunnel, the same shape RFC 8441 WebSocket uses.
                sendResponseHeaders(200, new ArrayList<Header>(), false);
                // the header section is out: capsules are body from here
                responseState = ResponseState.IN_BODY;
            } else {
                // RFC 9110 section 7.8: HTTP/1.1 accepts via 101
                // Switching Protocols instead.
                List<Header> responseHeaders = new ArrayList<Header>();
                HeaderFields.add(responseHeaders, "connection", "upgrade");
                HeaderFields.add(responseHeaders, "upgrade", protocolToken);
                sendResponseHeaders(101, responseHeaders, false);
                // Hand this connection's remaining raw bytes to this
                // stream -- see switchToStreamTunnelMode's own
                // documentation for why this is safe to share with
                // WebSocket's identical need despite the method's name.
                // HTTP/2 needs no equivalent call: every other stream on
                // the connection is unaffected either way (see that
                // method's own HTTP/2 branch).
                connection.switchToStreamTunnelMode(streamId);
                // 101 is handled as an informational response (no
                // responseState transition in sendResponseHeaders), but
                // RFC 9484/9298 capsule egress on this connection still
                // uses sendCapsule/sendDatagram, which require IN_BODY.
                responseState = ResponseState.IN_BODY;
            }
            return true;
        } catch (ProtocolException e) {
            return false;
        }
    }

    @Override
    public boolean acceptConnectUdp() {
        return acceptExtendedConnect("connect-udp");
    }

    @Override
    public boolean acceptConnectIp() {
        return acceptExtendedConnect("connect-ip");
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

        void notifyTransportClosed(int code, String reason) {
            if (isOpen()) {
                abnormalClose(code, reason);
            }
        }
    }
    
    /**
     * Internal WebSocket transport implementation that uses the Stream for I/O.
     */
    private class StreamWebSocketTransport implements WebSocketConnection.WebSocketTransport {
        
        @Override
        public void sendFrame(ByteBuffer frameData) throws IOException {
            final ByteBuffer frame = frameData;
            // WebSocketSession may be used from application threads; the
            // connection's I/O must run on its own selector loop.
            Stream.this.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        sendResponseBody(frame, false);
                    } catch (ProtocolException e) {
                        events().warn("warn.websocket_frame_send_failed").thrown(e).emit();
                    }
                }
            });
        }
        
        @Override
        public void close(boolean normalClose) throws IOException {
            final boolean normal = normalClose;
            Stream.this.execute(new Runnable() {
                @Override
                public void run() {
                    Stream.this.streamClose(normal);
                }
            });
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
        rejectedByFramework = true;
        List<Header> headers = new ArrayList<Header>();
        // For HTTP/1.x, add Content-Length: 0 so clients know there's no body
        // Also close connection on error to prevent keep-alive issues
        if (connection.getVersion() != HttpVersion.HTTP_2_0) {
            HeaderFields.add(headers, "Content-Length", "0");
            closeConnection = true; // Close connection after error
        }
        sendResponseHeaders(statusCode, headers, true);
    }

    /**
     * Sends a 401 Unauthorized response with a WWW-Authenticate challenge
     * (RFC 9110 section 11.6.1). Unlike {@link #sendError}, the connection
     * is not forced closed for HTTP/1.x: a 401 is a normal part of the
     * authentication handshake and a client may legitimately retry with
     * credentials on the same persistent connection.
     */
    private void sendUnauthorized(HttpAuthenticationProvider authProvider) throws ProtocolException {
        if (state == State.IDLE) {
            state = State.OPEN;
        }
        rejectedByFramework = true;
        List<Header> headers = new ArrayList<Header>();
        String challenge = authProvider.generateChallenge();
        if (challenge != null) {
            HeaderFields.add(headers, "WWW-Authenticate", challenge);
        }
        if (connection.getVersion() != HttpVersion.HTTP_2_0) {
            HeaderFields.add(headers, "Content-Length", "0");
        }
        sendResponseHeaders(401, headers, true);
    }

    /**
     * Closes this stream abnormally (e.g., connection dropped).
     */
    void streamClose() {
        streamClose(false);
    }

    /**
     * Aborts this stream because the transport failed under it (connection
     * closed or errored, or the peer reset the stream): closes it
     * abnormally and gives the application handler its final
     * {@link HttpRequestHandler#failed} callback. The callback is delivered
     * at most once, and not at all for a stream the framework itself
     * rejected, whose handler never saw the request, or one whose handler
     * already received {@code endMessage}.
     *
     * @param cause the reason the stream was aborted
     */
    void streamAbort(Exception cause) {
        streamClose(false);
        if (webSocketAdapter != null) {
            // RFC 6455 section 7.4: 1006 is reserved for a connection
            // closed abnormally without a Close frame, as in the H3 path.
            webSocketAdapter.notifyTransportClosed(1006, cause.getMessage());
        }
        if (handler != null && !rejectedByFramework && !handlerFinished
                && webSocketAdapter == null) {
            handlerFinished = true;
            handler.failed(cause);
        }
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
    // HttpResponse implementation
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public SocketAddress getRemoteAddress() {
        return connection.getRemoteSocketAddress();
    }

    @Override
    public SocketAddress getLocalAddress() {
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
    public Principal getApplicationPrincipal() {
        return applicationPrincipal;
    }

    @Override
    public void setApplicationPrincipal(Principal principal) {
        this.applicationPrincipal = principal;
    }

    @Override
    public void status(int code) {
        if (code < 100 || code > 599) {
            throw new IllegalArgumentException("Invalid status code: " + code);
        }
        if (responseState != ResponseState.INITIAL) {
            throw new IllegalStateException(MessageFormat.format(
                    L10N.getString("err.response_status_late"), responseState));
        }
        if (bufferedResponseHeaders == null) {
            bufferedResponseHeaders = new ArrayList<Header>();
        }
        HeaderFields.removeAll(bufferedResponseHeaders, ":status");
        HeaderFields.add(bufferedResponseHeaders, ":status", Integer.toString(code));
    }

    @Override
    public void header(String name, ByteBuffer value) {
        byte[] octets = new byte[value.remaining()];
        value.duplicate().get(octets);
        addField(name, new String(octets, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    private void addField(String name, String value) {
        if (pushHeaders != null) {
            HttpUtils.requireAsciiFieldValue(name, value);
            HeaderFields.add(pushHeaders, name, value);
            return;
        }
        if (responseState == ResponseState.COMPLETE) {
            throw new IllegalStateException(L10N.getString("err.response_complete"));
        }
        HttpUtils.requireAsciiFieldValue(name, value);
        if (responseState == ResponseState.IN_BODY) {
            // after the body began this is a trailer field (RFC 9110 section 6.5)
            if (HttpUtils.isForbiddenInTrailers(name)) {
                throw new IllegalArgumentException(MessageFormat.format(
                        L10N.getString("err.trailer_not_allowed"), name));
            }
            if (!trailersStarted) {
                trailersStarted = true;
                bufferedResponseHeaders = new ArrayList<Header>();
            }
            HeaderFields.add(bufferedResponseHeaders, name, value);
            return;
        }
        if (responseState != ResponseState.INITIAL) {
            throw new IllegalStateException(MessageFormat.format(
                    L10N.getString("err.response_status_late"), responseState));
        }
        if (bufferedResponseHeaders == null) {
            bufferedResponseHeaders = new ArrayList<Header>();
        }
        HeaderFields.add(bufferedResponseHeaders, name, value);
    }

    @Override
    public void endHeaders() {
        if (pushHeaders != null) {
            throw new IllegalStateException(L10N.getString("err.push_promise_open"));
        }
        if (responseState != ResponseState.INITIAL) {
            return; // the header section is already out
        }
        String statusStr = bufferedResponseHeaders == null
                ? null : HeaderFields.getValue(bufferedResponseHeaders, ":status");
        if (statusStr != null && statusStr.startsWith("1")) {
            sendInterimResponse(Integer.parseInt(statusStr));
            return;
        }
        ensureStatus();
        // Flush buffered headers
        flushResponseHeaders(false);
        responseState = ResponseState.IN_BODY;
    }

    /** A response with no status is a 200. */
    private void ensureStatus() {
        if (bufferedResponseHeaders == null) {
            bufferedResponseHeaders = new ArrayList<Header>();
        }
        if (HeaderFields.getValue(bufferedResponseHeaders, ":status") == null) {
            HeaderFields.add(bufferedResponseHeaders, ":status", "200");
        }
    }

    /** Sends a 1xx response from the buffered fields and starts over. */
    private void sendInterimResponse(int statusCode) {
        List<Header> fields = bufferedResponseHeaders;
        HeaderFields.removeAll(fields, ":status");
        bufferedResponseHeaders = null;
        // RFC 9110 section 15.2: 1xx not defined for HTTP/1.0
        if (connection.getVersion() == HttpVersion.HTTP_1_0) {
            return;
        }
        try {
            sendResponseHeaders(statusCode, fields, false);
        } catch (ProtocolException e) {
            throw new IllegalStateException(
                    "Failed to send informational response", e);
        }
    }

    @Override
    public void bodyContent(ByteBuffer data) {
        if (pushHeaders != null) {
            throw new IllegalStateException(L10N.getString("err.push_promise_open"));
        }
        if (responseState == ResponseState.INITIAL) {
            endHeaders();
        }
        if (responseState != ResponseState.IN_BODY || trailersStarted) {
            throw new IllegalStateException(MessageFormat.format(
                    L10N.getString("err.response_body_late"), responseState));
        }
        try {
            // Send DATA frame without END_STREAM
            sendResponseBody(data, false);
        } catch (ProtocolException e) {
            throw new IllegalStateException("Failed to send response body", e);
        }
    }

    @Override
    public boolean sendDatagram(ByteBuffer data) {
        if (data == null) {
            return false;
        }
        byte[] copy = new byte[data.remaining()];
        data.get(copy);
        if (capsuleMode) {
            return sendCapsule(Capsule.TYPE_DATAGRAM, ByteBuffer.wrap(copy));
        }
        return false;
    }

    @Override
    public boolean sendCapsule(long type, ByteBuffer value) {
        if (value == null) {
            return false;
        }
        byte[] copy = new byte[value.remaining()];
        value.get(copy);
        byte[] encoded = new Capsule(type, copy).encode();
        try {
            if (responseState == ResponseState.INITIAL) {
                endHeaders();
            }
            if (responseState != ResponseState.IN_BODY) {
                return false;
            }
            sendResponseBody(ByteBuffer.wrap(encoded), false);
            return true;
        } catch (ProtocolException e) {
            return false;
        }
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
    public int pendingResponseBytes() {
        return connection.pendingResponseBytes(streamId);
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
    public void endMessage() {
        if (pushHeaders != null) {
            throw new IllegalStateException(L10N.getString("err.push_promise_open"));
        }
        if (responseState == ResponseState.COMPLETE) {
            return; // Already complete, ignore
        }
        if (state == State.CLOSED) {
            // The stream was already closed by something else (e.g. the
            // peer dropped the connection, or a "Connection: close"
            // teardown) before the handler got around to completing the
            // response. The desired end state is already achieved, so
            // treat this the same as an already-complete response rather
            // than failing - there is nothing left to send.
            responseState = ResponseState.COMPLETE;
            return;
        }

        if (responseState == ResponseState.IN_BODY && trailersStarted) {
            if (responseDeclaredLength >= 0 && responseBodyBytes != responseDeclaredLength) {
                abortResponseLength();
                return;
            }
            try {
                sendTrailers();
            } catch (ProtocolException e) {
                throw new IllegalStateException("Failed to complete response", e);
            }
            responseState = ResponseState.COMPLETE;
            return;
        }
        if (responseState == ResponseState.IN_BODY) {
            // the header section went out with the body: nothing is pending
            bufferedResponseHeaders = null;
        } else if (responseState == ResponseState.INITIAL) {
            ensureStatus();
        }
        try {
            if (bufferedResponseHeaders != null && bufferedResponseHeaders.size() > 0) {
                // Has buffered headers (the header section of a response with no body)
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

    /**
     * Ends the response with the trailer fields. HTTP/2 sends them as a final
     * HEADERS frame; chunked HTTP/1.1 as the fields after the last chunk.
     * Where the framing leaves no room (a declared Content-Length, HTTP/1.0)
     * they are dropped, as a recipient may ignore trailer fields.
     */
    private void sendTrailers() throws ProtocolException {
        List<Header> trailers = bufferedResponseHeaders;
        bufferedResponseHeaders = null;
        if (responseContentEncoder != null) {
            // the coding covers the content only, which ends here
            try {
                responseContentEncoder.write(EMPTY_BUFFER.duplicate(), true);
            } catch (ContentEncoding.ContentEncodingException e) {
                throw new ProtocolException(e.getMessage());
            }
            ByteBuffer encoded;
            while ((encoded = responseContentEncoder.readEncoded()) != null) {
                sendResponseBodyWire(encoded, false);
            }
            responseContentEncoder.close();
            responseContentEncoder = null;
        }
        if (connection.getVersion() == HttpVersion.HTTP_2_0) {
            connection.sendResponseTrailers(streamId, trailers);
        } else if (responseChunked) {
            StringBuilder out = new StringBuilder("0\r\n");
            for (Header header : trailers) {
                out.append(header.getName()).append(": ").append(header.getValue()).append("\r\n");
            }
            out.append("\r\n");
            connection.sendResponseBody(streamId, ByteBuffer.wrap(
                    out.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)), true);
        } else {
            connection.sendResponseBody(streamId, EMPTY_BUFFER.duplicate(), true);
        }
    }

    // RFC 9113 section 8.4: server push
    @Override
    public void startPushPromise(HttpMethod method, String target) {
        if (pushHeaders != null) {
            throw new IllegalStateException(L10N.getString("err.push_promise_open"));
        }
        pushMethod = method;
        pushTarget = target;
        pushHeaders = new ArrayList<Header>();
    }

    @Override
    public boolean endPushPromise() {
        if (pushHeaders == null) {
            throw new IllegalStateException(L10N.getString("err.push_promise_not_open"));
        }
        List<Header> promised = pushHeaders;
        String method = pushMethod.toString();
        String path = pushTarget;
        pushHeaders = null;
        pushMethod = null;
        pushTarget = null;
        if (connection.getVersion() != HttpVersion.HTTP_2_0) {
            return false;
        }
        // RFC 9113 section 6.5.2: MUST NOT send PUSH_PROMISE if
        // SETTINGS_ENABLE_PUSH is 0
        if (!connection.isEnablePush()) {
            return false;
        }
        // the promised request's pseudo-header fields (RFC 9113 section 8.4.1)
        List<Header> fields = new ArrayList<Header>();
        HeaderFields.add(fields, ":method", method);
        HeaderFields.add(fields, ":scheme", getScheme());
        String authority = requestAuthority();
        if (authority != null) {
            HeaderFields.add(fields, ":authority", authority);
        }
        HeaderFields.add(fields, ":path", path);
        for (Header header : promised) {
            fields.add(header);
        }
        try {
            return sendServerPush(method, path, fields);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void cancel() {
        try {
            // For HTTP/2, send RST_STREAM
            // For HTTP/1, close connection
            if (connection.getVersion() == HttpVersion.HTTP_2_0) {
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
        String statusStr = HeaderFields.getValue(bufferedResponseHeaders, ":status");
        int statusCode = 200; // Default if no :status header
        if (statusStr != null) {
            try {
                statusCode = Integer.parseInt(statusStr);
            } catch (NumberFormatException e) {
                // Invalid :status value is a server programming error
                statusCode = 500;
            }
            // Remove :status from headers - it's handled separately
            HeaderFields.removeAll(bufferedResponseHeaders, ":status");
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

