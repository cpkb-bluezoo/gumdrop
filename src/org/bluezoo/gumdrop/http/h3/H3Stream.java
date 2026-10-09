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

import org.bluezoo.gumdrop.http.HeaderFields;
import java.io.ByteArrayOutputStream;
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
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.quic.QuicConnectionCloseException;
import org.bluezoo.gumdrop.quic.QuicStreamEndpoint;
import org.bluezoo.gumdrop.websocket.WebSocketConnection;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketMetricsSource;
import org.bluezoo.gumdrop.websocket.WebSocketServerMetrics;
import org.bluezoo.gumdrop.websocket.WebSocketSession;
import org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider;
import org.bluezoo.gumdrop.http.server.HttpPrincipal;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.HttpServerMetrics;
import org.bluezoo.gumdrop.http.ContentEncoding;
import org.bluezoo.gumdrop.http.HttpUtils;
import org.bluezoo.gumdrop.http.HttpVersion;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.FieldSectionAdapter;
import org.bluezoo.gumdrop.http.HeaderCollector;
import org.bluezoo.gumdrop.http.HttpMessageRecorder;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.Capsule;
import org.bluezoo.gumdrop.http.CapsuleParser;
import org.bluezoo.gumdrop.http.PriorityParams;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.qpack.Encoder;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.access.HttpAccessLog;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.EventLogger;

/**
 * A single HTTP/3 request/response exchange on a QUIC stream.
 *
 * <p>This is the HTTP/3 equivalent of the HTTP/2 {@code Stream} class.
 * Each instance manages one request/response lifecycle (RFC 9114
 * section 4.1) and implements {@link HttpResponse} so that
 * {@link HttpRequestHandler} implementations can send responses
 * identically to HTTP/2.
 *
 * <p>This class is itself the QUIC stream's {@link ProtocolHandler} and
 * {@link H3FrameHandler} -- it owns its own {@link H3Parser}, fed directly
 * from {@link #receive}, and decodes/encodes header blocks itself via the
 * connection-shared {@link Decoder}/{@link Encoder} (RFC 9204's full
 * dynamic-table QPACK codec); any resulting encoder/decoder-stream
 * instructions are flushed back through {@link Http3ServerHandler}, which
 * owns the actual QPACK stream endpoints. Response-body flow-control
 * buffering is handled once, generically, by {@link Endpoint#send} itself,
 * the same as every other protocol running over QUIC.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Http3ServerHandler
 * @see HttpRequestHandler
 */
class H3Stream implements ProtocolHandler, H3FrameHandler, HttpResponse {

    private static final Logger LOGGER = Logger.getLogger(H3Stream.class.getName());

    private EventLogger eventsHttp() {
        return connection.getTelemetryConfig().getLogger(H3Stream.class, HTTP_L10N);
    }

    private EventLogger events() {
        return connection.getTelemetryConfig().getLogger(H3Stream.class, L10N);
    }

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

    private final Http3ServerHandler connection;
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
    private HttpRequestHandler handler;
    private boolean applicationHandlerOpened;
    private List<Header> requestHeaders;
    private String method;
    private String requestTarget;
    private String protocol;
    private Principal authenticatedPrincipal;
    private Principal applicationPrincipal;
    private boolean bodyStarted;
    private boolean responseStarted;
    private boolean responseBodyStarted;
    private List<Header> pendingResponseHeaders;
    private List<Header> responseTrailers;
    private boolean pushOpen;

    // RFC 9204 section 4.4.2: whether this stream's request field
    // section was ever successfully decoded -- if not, and the stream
    // ends anyway (reset, or the connection going away), the peer
    // encoder must be told via cancelQpackStream so it releases any
    // table references it made for this stream; otherwise they leak
    // for the rest of the connection.
    private boolean headersDecoded;
    private ByteArrayOutputStream heldBody;

    private boolean capsuleMode;
    private final CapsuleParser capsuleParser = new CapsuleParser();

    /**
     * Declared {@code Content-Length} from the request headers, or
     * {@code -1} if absent. Validated against accumulated DATA bytes
     * (RFC 9114 section 4.1.2).
     */
    private long contentLength = -1L;
    private long bodyBytesReceived;

    private H3WebSocketConnectionAdapter webSocketAdapter;

    private Span span;
    private long timestampStarted;
    private int responseStatusCode;
    private long responseBodyBytes;

    /**
     * The Content-Length the handler declared for a response whose DATA
     * must add up to it, or -1 when there is none to enforce (HEAD, 204,
     * 304, or no declared length).
     */
    private long responseDeclaredLength = -1L;

    private ContentEncoding.Encoder responseContentEncoder;
    private boolean decodeRequestContentCoding;
    private boolean encodeResponseContentCoding;
    private ContentEncoding.Coding requestInboundCoding;
    private ContentEncoding.Decoder requestContentDecoder;
    private long requestDecodedBytesReceived;

    H3Stream(Http3ServerHandler connection, Encoder qpackEncoder, Decoder qpackDecoder) {
        this.connection = connection;
        this.qpackEncoder = qpackEncoder;
        this.qpackDecoder = qpackDecoder;
        this.state = State.IDLE;
    }

    /**
     * The events of the request's header section (or trailer section) as the
     * HTTP/3 adapter produced them. The server decides what to do with a
     * request (authentication, limits, which handler to bind) from the whole
     * header section, so the events wait here until that decision has been
     * made and then go to the application handler.
     */
    private final HttpMessageRecorder recordedEvents = new HttpMessageRecorder();

    /**
     * Set once the events of this request have been replayed to the handler;
     * from then on the body and completion events follow, so a handler never
     * sees a body without the start of the message.
     */
    private boolean messageEvents;

    /** Replays the recorded header-section events to the bound handler. */
    private void replayRecordedEvents() {
        if (!recordedEvents.isEmpty()) {
            messageEvents = true;
            recordedEvents.replay(handler);
            recordedEvents.clear();
        }
    }

    void openApplicationHandler() {
        if (applicationHandlerOpened || handler != null || connection == null) {
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

    HttpRequestHandler getHandler() {
        return handler;
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
        if (connection != null) {
            connection.registerRequestStream(this);
        }
    }

    @Override
    public void receive(ByteBuffer data) {
        parser.receive(data);
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
    }

    @Override
    public void readFinished() {
        handlePeerSendFinished();
    }

    @Override
    public void disconnected() {
        // RESET_STREAM or connection teardown -- endpoint is no longer
        // usable; still treat as a finish for request/response handling.
        handlePeerSendFinished();
    }

    private void handlePeerSendFinished() {
        // connection is only ever null when a test constructs this class
        // directly without going through Http3ServerHandler (see
        // H3StreamTest) -- never in production.
        if (!headersDecoded && connection != null) {
            connection.cancelQpackStream(streamId);
        }
        if (isWebSocketUpgraded()) {
            onWebSocketFinished();
            if (connection != null) {
                connection.streamFinished(this);
            }
        } else {
            onFinished();
            // HTTP Datagrams (RFC 9297 section 2.1) are demuxed by
            // quarter-stream-ID after the request FIN; the stream stays
            // registered until complete() or error().
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
            handler.failed(cause);
        }
        state = State.CLOSED;
        handler = null;
        if (connection != null) {
            connection.streamFinished(this);
        }
    }

    // ── H3FrameHandler ──

    /**
     * Called when a complete HEADERS frame is received (RFC 9114
     * section 7.2.2), decoded via QPACK into name/value pairs including
     * pseudo-headers (RFC 9114 section 4.3.1).
     */
    @Override
    public void headersFrameReceived(ByteBuffer encodedFieldSection) {
        // The decoder pushes the fields into an adapter that applies the
        // HTTP/3 rules (RFC 9114 section 4.2 and 4.3) and produces the message
        // events; the same fields, as the exact octets, go to a collector for
        // the server's own use.
        recordedEvents.clear();
        HeaderCollector collected = new HeaderCollector();
        FieldSectionAdapter adapter = new FieldSectionAdapter(recordedEvents, HttpVersion.HTTP_3,
                state == State.IDLE ? FieldSectionAdapter.Kind.REQUEST : FieldSectionAdapter.Kind.TRAILERS,
                collected);
        try {
            qpackDecoder.decode(streamId, encodedFieldSection, adapter);
        } catch (ProtocolException e) {
            // Treated as this stream's own malformed HEADERS -- cancelling
            // just this stream, rather than tearing down the whole
            // connection, is a deliberate simplification: a real peer
            // encoder could in principle desynchronize the shared dynamic
            // table in a way that surfaces here, but gumdrop has no way
            // to distinguish that from an ordinary malformed field
            // section from this exception alone.
            events().warn("warn.qpack_decode_failed").thrown(e).emit();
            cancel();
            return;
        }
        headersDecoded = true;
        if (!adapter.finish() || collected.isMalformed()) {
            // RFC 9114 section 4.1.2: a field that breaks the rules, or is not
            // valid field syntax, makes the request malformed, a stream error.
            // The section was decoded and acknowledged in full, so the QPACK
            // state is intact.
            recordedEvents.clear();
            abortMessageError("malformed header field");
            if (connection != null) {
                connection.flushQpackDecoderInstructions();
            }
            return;
        }
        List<Header> headers = HeaderFields.collected(collected);
        if (H3Qlog.on(endpoint)) {
            H3Qlog.frameParsed(endpoint, streamId, H3Qlog.headersFrame(headers));
        }
        if (H3Writer.fieldSectionSize(headers) > localMaxFieldSectionSize()) {
            // RFC 9114 section 4.2.2 / 10.5.1: refuse oversized field
            // sections with a stream error rather than hanging the peer.
            abortExcessiveLoad();
            if (connection != null) {
                connection.flushQpackDecoderInstructions();
            }
            return;
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

    private void onHeaders(List<Header> headers) {
        if (state == State.IDLE) {
            state = State.OPEN;
            requestHeaders = headers;
            method = HeaderFields.getValue(headers, ":method");
            requestTarget = HeaderFields.getValue(headers, ":path");
            protocol = HeaderFields.getValue(headers, ":protocol");

            // RFC 9114 section 4.1.2 / 4.3.1: validate mandatory
            // pseudo-headers. CONNECT omits :scheme and :path.
            if (method == null
                    || (!"CONNECT".equals(method)
                        && (HeaderFields.getValue(headers, ":scheme") == null
                            || requestTarget == null))) {
                events().warn("warn.malformed_request_missing_pseudo_headers")
                        .attr("remote_address", String.valueOf(connection.getRemoteAddress())).emit();
                sendErrorResponse(400);
                return;
            }

            // RFC 9114 section 4.2: a request carrying a connection-specific
            // header field is malformed (Content-Length is not one).
            for (int i = 0; i < headers.size(); i++) {
                Header h = headers.get(i);
                String hname = h.getName();
                if (!hname.startsWith(":")
                        && !"content-length".equalsIgnoreCase(hname)
                        && HttpVersion.isHttp1FramingHeader(hname, h.getValue())) {
                    abortMessageError("connection-specific header field: " + hname);
                    return;
                }
            }

            // Capture Content-Length before stripHttp1FramingHeaders removes
            // it (same ordering as HTTP/2 in Stream): H3 still validates the
            // declared length against DATA bytes (RFC 9114 section 4.1.2)
            // even though CL is not used for framing.
            if (!captureContentLength(headers)) {
                return;
            }

            HeaderFields.stripHttp1FramingHeaders(headers, false);

            HttpAuthenticationProvider authProvider = connection.getAuthenticationProvider();
            if (authProvider != null) {
                String authHeader = HeaderFields.getValue(headers, "authorization");
                HttpAuthenticationProvider.AuthenticationResult result =
                        authProvider.authenticate(authHeader, method, requestTarget);
                if (result.success) {
                    authenticatedPrincipal = new HttpPrincipal(result.username);
                } else if (authProvider.isAuthenticationRequired()) {
                    sendUnauthorized(authProvider);
                    return;
                }
            }

            openApplicationHandler();
            if (handler == null) {
                sendErrorResponse(404);
                return;
            }

            capsuleMode = Capsule.capsuleProtocolEnabled(
                    HeaderFields.getValue(headers, Capsule.PROTOCOL_HEADER));

            if (connection != null) {
                connection.applyRequestPriority(streamId, PriorityParams.parse(
                        HeaderFields.getValue(headers, PriorityParams.PRIORITY_HEADER)), false);
            }

            initTelemetrySpan();
            if (!prepareRequestContentDecoding(headers)) {
                return;
            }
            replayRecordedEvents();
        } else if (state == State.RECEIVING_BODY || state == State.HALF_CLOSED_REMOTE) {
            if (handler != null) {
                replayRecordedEvents();
            }
        }
    }

    @Override
    public void dataFrameReceived(ByteBuffer data, boolean endOfFrame) {
        if (H3Qlog.on(endpoint)) {
            H3Qlog.frameParsed(endpoint, streamId, H3Qlog.dataFrame(data.remaining()));
        }
        if (isWebSocketUpgraded()) {
            onWebSocketData(data);
            return;
        }
        if (capsuleMode) {
            dispatchCapsules(data);
            return;
        }
        if (state == State.IDLE) {
            // RFC 9114 section 4.1: DATA before the initial HEADERS is
            // a connection error of type H3_FRAME_UNEXPECTED.
            connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                    "DATA received before HEADERS");
            return;
        }
        if (state == State.OPEN) {
            state = State.RECEIVING_BODY;
            if (requestInboundCoding == null && !bodyStarted && handler != null) {
                bodyStarted = true;
            }
        }
        bodyBytesReceived += data.remaining();
        if (contentLength >= 0 && bodyBytesReceived > contentLength) {
            // RFC 9114 section 4.1.2
            abortMessageError("DATA exceeds Content-Length");
            return;
        }
        if (requestInboundCoding != null || requestContentDecoder != null) {
            try {
                ensureRequestContentDecoder();
                if (data.hasRemaining()) {
                    requestContentDecoder.write(data, false);
                }
                if (!drainDecodedRequestBody(false)) {
                    return;
                }
            } catch (ContentEncoding.ContentEncodingException e) {
                sendErrorResponse(400);
                return;
            }
            return;
        }
        if (handler != null) {
            if (messageEvents) {
                handler.bodyContent(data.asReadOnlyBuffer());
            }
        }
    }

    private void dispatchCapsules(ByteBuffer data) {
        List<Capsule> capsules;
        try {
            capsules = capsuleParser.push(data);
        } catch (CapsuleParser.CapsuleException e) {
            abortDatagramError();
            return;
        }
        for (int i = 0; i < capsules.size(); i++) {
            Capsule capsule = capsules.get(i);
            if (H3Qlog.on(endpoint)) {
                H3Qlog.capsuleParsed(endpoint, streamId, capsule.getType(), capsule.getValue().length);
            }
            if (capsule.getType() == Capsule.TYPE_DATAGRAM) {
                if (handler != null && handler.wantsDatagrams()) {
                    handler.datagramReceived(this, ByteBuffer.wrap(capsule.getValue()));
                } else {
                    abortDatagramError();
                    return;
                }
            } else if (handler != null) {
                handler.capsuleReceived(this, capsule.getType(),
                        ByteBuffer.wrap(capsule.getValue()));
            }
        }
    }

    private void onFinished() {
        if (state == State.CLOSED || state == State.HALF_CLOSED_REMOTE) {
            return;
        }
        if (capsuleMode && !capsuleParser.finish()) {
            abortDatagramError();
            return;
        }
        if (contentLength >= 0 && bodyBytesReceived != contentLength) {
            // RFC 9114 section 4.1.2: Content-Length must equal the sum
            // of DATA frame payload lengths.
            abortMessageError("Content-Length does not match DATA frame bytes");
            return;
        }
        if (requestInboundCoding != null || requestContentDecoder != null) {
            // an active decoder (created by the first DATA frame, which
            // clears requestInboundCoding) must be finished too: that
            // flushes buffered output and rejects a truncated stream
            if (!drainDecodedRequestBody(true)) {
                return;
            }
        }
        state = State.HALF_CLOSED_REMOTE;
        if (handler != null) {
            if (messageEvents) {
                handler.endMessage();
            }
        }
    }

    /**
     * Parses and stores {@code Content-Length} from the request headers.
     *
     * @return false if the field was present but malformed (stream aborted)
     */
    private boolean captureContentLength(List<Header> headers) {
        String value = HeaderFields.getCombinedValue(headers, "content-length");
        if (value == null) {
            return true;
        }
        long parsed = HttpUtils.validateContentLength(value);
        if (parsed < 0) {
            abortMessageError("invalid Content-Length");
            return false;
        }
        contentLength = parsed;
        return true;
    }

    /**
     * Aborts this stream with {@link H3ErrorCode#H3_MESSAGE_ERROR}
     * (RFC 9114 section 4.1.2 / 8.1) because the message is malformed.
     */
    private void abortMessageError(String reason) {
        abortStream(reason, H3ErrorCode.H3_MESSAGE_ERROR);
    }

    private void abortStream(String reason, long errorCode) {
        if (span != null && !span.isEnded()) {
            span.recordError(ErrorCategory.PROTOCOL_ERROR, reason);
            span.end();
        }
        state = State.CLOSED;
        handler = null;
        if (endpoint != null) {
            endpoint.resetStream(errorCode);
        }
    }

    /**
     * Abandons a response whose body does not add up to the Content-Length
     * it declared (RFC 9114 section 4.1.2): the stream is reset with
     * {@link H3ErrorCode#H3_INTERNAL_ERROR} rather than finished as if it
     * were sound.
     *
     * @param actual the number of body bytes the response came to
     */
    private void abortResponseLength(long actual) {
        String reason = MessageFormat.format(
                HTTP_L10N.getString("warn.response_length_mismatch"),
                Long.valueOf(streamId), Long.valueOf(responseDeclaredLength),
                Long.valueOf(actual));
        eventsHttp().warn("warn.response_length_mismatch")
                .attr("stream_id", streamId)
                .attr("declared_length", responseDeclaredLength)
                .attr("body_bytes", actual).emit();
        responseDeclaredLength = -1L;
        heldBody = null;
        abortStream(reason, H3ErrorCode.H3_INTERNAL_ERROR);
        if (connection != null) {
            connection.streamFinished(this);
        }
    }

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
    public void priorityUpdateRequestFrameReceived(long streamId, String fieldValue) {
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                "PRIORITY_UPDATE is not valid on a request stream");
    }

    @Override
    public void priorityUpdatePushFrameReceived(long pushId, String fieldValue) {
        connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                "PRIORITY_UPDATE is not valid on a request stream");
    }

    @Override
    public void unknownFrameReceived(long frameType) {
        if (H3FrameHandler.isReservedHttp2FrameType(frameType)) {
            // RFC 9114 section 7.2.8
            connectionError(H3ErrorCode.H3_FRAME_UNEXPECTED,
                    "reserved HTTP/2 frame type: " + frameType);
        }
        // Genuine GREASE / extension frame types are ignored (section 9).
    }

    @Override
    public void frameError(String message) {
        events().warn("warn.frame_error").attr("message", message).emit();
        cancel();
    }

    private void connectionError(long errorCode, String message) {
        events().warn("warn.frame_error").attr("message", message).emit();
        connection.closeWithApplicationError(errorCode, message);
    }

    // ── HttpResponse Implementation ──

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
    public HttpVersion getVersion() {
        return HttpVersion.HTTP_3;
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
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return endpoint.scheduleTimer(delayMs, callback);
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
        if (responseBodyStarted || responseStarted) {
            throw new IllegalStateException(MessageFormat.format(
                    HTTP_L10N.getString("err.response_status_late"), "started"));
        }
        if (pendingResponseHeaders == null) {
            pendingResponseHeaders = new ArrayList<Header>();
        }
        removeHeaders(pendingResponseHeaders, ":status");
        pendingResponseHeaders.add(0, new Header(":status", Integer.toString(code)));
    }

    @Override
    public void header(String name, ByteBuffer value) {
        byte[] octets = new byte[value.remaining()];
        value.duplicate().get(octets);
        addField(name, new String(octets, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    private void addField(String name, String value) {
        HttpUtils.requireAsciiFieldValue(name, value);
        if (pushOpen) {
            return; // push is declined: the promised request is discarded
        }
        if (state == State.CLOSED) {
            throw new IllegalStateException(HTTP_L10N.getString("err.response_complete"));
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (responseBodyStarted) {
            // after the body began this is a trailer field (RFC 9110 section 6.5)
            if (HttpUtils.isForbiddenInTrailers(lower)) {
                throw new IllegalArgumentException(MessageFormat.format(
                        HTTP_L10N.getString("err.trailer_not_allowed"), name));
            }
            if (responseTrailers == null) {
                responseTrailers = new ArrayList<Header>();
            }
            responseTrailers.add(new Header(lower, value));
            return;
        }
        if (pendingResponseHeaders == null) {
            pendingResponseHeaders = new ArrayList<Header>();
        }
        pendingResponseHeaders.add(new Header(lower, value));
    }

    @Override
    public void endHeaders() {
        if (pushOpen) {
            throw new IllegalStateException(HTTP_L10N.getString("err.push_promise_open"));
        }
        if (responseStarted || responseBodyStarted) {
            return; // the header section is already out
        }
        int interim = interimStatus();
        if (interim != 0) {
            List<Header> fields = pendingResponseHeaders;
            pendingResponseHeaders = null;
            sendHeaderFrame(fields, false);
            return;
        }
        ensureStatus();
        flushHeaders(false);
        responseBodyStarted = true;
    }

    /** The pending status if it is 1xx, else 0. */
    private int interimStatus() {
        if (pendingResponseHeaders != null && !pendingResponseHeaders.isEmpty()) {
            Header first = pendingResponseHeaders.get(0);
            if (":status".equals(first.getName()) && first.getValue().startsWith("1")) {
                return Integer.parseInt(first.getValue());
            }
        }
        return 0;
    }

    /** A response with no status is a 200. */
    private void ensureStatus() {
        if (pendingResponseHeaders == null) {
            pendingResponseHeaders = new ArrayList<Header>();
        }
        if (pendingResponseHeaders.isEmpty()
                || !":status".equals(pendingResponseHeaders.get(0).getName())) {
            pendingResponseHeaders.add(0, new Header(":status", "200"));
        }
    }

    @Override
    public void bodyContent(ByteBuffer data) {
        if (pushOpen) {
            throw new IllegalStateException(HTTP_L10N.getString("err.push_promise_open"));
        }
        if (state == State.CLOSED) {
            throw new IllegalStateException(HTTP_L10N.getString("err.response_complete"));
        }
        if (!responseStarted && !responseBodyStarted) {
            endHeaders();
        }
        if (responseTrailers != null) {
            throw new IllegalStateException(MessageFormat.format(
                    HTTP_L10N.getString("err.response_body_late"), "trailers"));
        }
        int len = data.remaining();
        if (responseDeclaredLength >= 0
                && responseBodyBytes + len > responseDeclaredLength) {
            long total = responseBodyBytes + len;
            long declared = responseDeclaredLength;
            abortResponseLength(total);
            throw new IllegalStateException(MessageFormat.format(
                    HTTP_L10N.getString("warn.response_length_mismatch"),
                    Long.valueOf(streamId), Long.valueOf(declared),
                    Long.valueOf(total)));
        }
        responseBodyBytes += len;
        if ("HEAD".equals(method)) {
            return;
        }
        sendBody(data);
    }

    @Override
    public void endMessage() {
        if (pushOpen) {
            throw new IllegalStateException(HTTP_L10N.getString("err.push_promise_open"));
        }
        if (state == State.CLOSED) {
            return; // already complete
        }
        if (!responseStarted && !responseBodyStarted) {
            ensureStatus();
            flushHeaders(true);
            if (state == State.CLOSED) {
                return; // the response was abandoned
            }
        } else if (responseBodyStarted) {
            if (responseDeclaredLength >= 0 && responseBodyBytes != responseDeclaredLength) {
                abortResponseLength(responseBodyBytes);
                return;
            }
            finishResponseContentEncoder();
            if (responseTrailers != null) {
                sendHeaderFrame(responseTrailers, false);
                responseTrailers = null;
            }
            endpoint.close();
        }
        state = State.CLOSED;
        endTelemetrySpan(responseStatusCode);
        if (connection != null) {
            connection.streamFinished(this);
        }
    }

    /**
     * RFC 9114 section 4.6 - server push. HTTP/3 server push uses
     * PUSH_PROMISE frames on the request stream plus a unidirectional
     * push stream. Push is rarely used in practice and many clients
     * disable it. This implementation declines all push requests.
     */
    @Override
    public void startPushPromise(HttpMethod method, String target) {
        if (pushOpen) {
            throw new IllegalStateException(HTTP_L10N.getString("err.push_promise_open"));
        }
        pushOpen = true;
    }

    @Override
    public boolean endPushPromise() {
        if (!pushOpen) {
            throw new IllegalStateException(HTTP_L10N.getString("err.push_promise_not_open"));
        }
        pushOpen = false;
        return false;
    }

    @Override
    public boolean sendDatagram(ByteBuffer data) {
        if (data == null || connection == null) {
            return false;
        }
        byte[] copy = new byte[data.remaining()];
        data.get(copy);
        if (connection.peerH3Datagram()) {
            return connection.sendHttpDatagram(streamId, copy);
        }
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
        if (H3Qlog.on(endpoint)) {
            H3Qlog.capsuleCreated(endpoint, streamId, type, copy.length);
        }
        sendBody(ByteBuffer.wrap(encoded));
        return true;
    }

    void httpDatagramReceived(ByteBuffer data) {
        if (handler != null && handler.wantsDatagrams()) {
            handler.datagramReceived(this, data);
            return;
        }
        abortDatagramError();
    }

    private void abortDatagramError() {
        state = State.CLOSED;
        if (endpoint != null) {
            endpoint.resetStream(H3ErrorCode.H3_DATAGRAM_ERROR);
        }
        if (connection != null) {
            connection.streamFinished(this);
        }
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

        // Resolve WebSocket metrics from the upgrading handler (if it opts
        // in), not the listener/connection -- handler-scoped, matching
        // Stream.java's H2 path.
        WebSocketServerMetrics wsMetrics = null;
        if (this.handler instanceof WebSocketMetricsSource) {
            wsMetrics = ((WebSocketMetricsSource) this.handler).getWebSocketMetrics();
        }

        webSocketAdapter = new H3WebSocketConnectionAdapter(wsHandler, wsMetrics);
        webSocketAdapter.setTransport(new H3WebSocketTransport());
        if (extensions != null && !extensions.isEmpty()) {
            webSocketAdapter.setExtensions(extensions);
        }

        if (connection.getTelemetryConfig() != null) {
            webSocketAdapter.setTelemetryConfig(connection.getTelemetryConfig());
            if (span != null) {
                webSocketAdapter.setParentSpan(span);
            } else {
                webSocketAdapter.createSpan(null);
            }
        }

        webSocketAdapter.notifyConnectionOpen();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HttpResponse.acceptConnectUdp/acceptConnectIp Implementation
    // (RFC 9298, RFC 9484)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Shared implementation behind {@link #acceptConnectUdp} and {@link
     * #acceptConnectIp} -- both RFC 9298 CONNECT-UDP and RFC 9484
     * CONNECT-IP over HTTP/3 via Extended CONNECT, differing only in the
     * {@code :protocol} pseudo-header value. Sends a {@code 2xx} response
     * accepting the tunnel and leaves the stream open in both directions
     * -- unlike {@link #upgradeToWebSocketInternal}, there is nothing to
     * bridge to here: datagrams already reach {@link
     * HttpRequestHandler#datagramReceived} (via native H3 Datagram,
     * {@link #httpDatagramReceived}, or the Capsule Protocol fallback,
     * {@link #dispatchCapsules}) whether this stream is CONNECT-UDP,
     * CONNECT-IP, some other Context ID-aware protocol, or nothing in
     * particular -- the caller (typically {@link
     * org.bluezoo.gumdrop.http.ConnectUdpRequestHandler}/{@link
     * org.bluezoo.gumdrop.http.ConnectIpRequestHandler}) already
     * registered itself as this stream's handler before this runs.
     *
     * @param protocolToken the {@code :protocol} value, e.g. {@code
     *        "connect-udp"} or {@code "connect-ip"}
     */
    private boolean acceptExtendedConnect(String protocolToken) {
        if (!"CONNECT".equals(method) || !protocolToken.equalsIgnoreCase(protocol)) {
            return false;
        }
        if (responseStarted) {
            return false;
        }
        pendingResponseHeaders = new ArrayList<Header>();
        pendingResponseHeaders.add(new Header(":status", "200"));
        flushHeaders(false);
        return true;
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
            events().warn("warn.websocket_frame_error").thrown(e).emit();
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
            final ByteBuffer frame = frameData;
            // Application threads may send via the WebSocketSession; stream
            // state and the QUIC endpoint belong to the connection's loop.
            endpoint.execute(new Runnable() {
                @Override
                public void run() {
                    if (state == State.CLOSED) {
                        return;
                    }
                    responseBodyBytes += frame.remaining();
                    sendBody(frame);
                }
            });
        }

        @Override
        public void close(boolean normalClose) throws IOException {
            endpoint.execute(new Runnable() {
                @Override
                public void run() {
                    if (state != State.CLOSED) {
                        endpoint.close();
                        state = State.CLOSED;
                        endTelemetrySpan(200);
                    }
                }
            });
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

    /**
     * Aborts this stream with {@link H3ErrorCode#H3_EXCESSIVE_LOAD}
     * (RFC 9114 section 4.2.2 / 8.1) because a field section exceeded
     * {@code SETTINGS_MAX_FIELD_SECTION_SIZE}.
     */
    private void abortExcessiveLoad() {
        if (span != null && !span.isEnded()) {
            span.recordError(ErrorCategory.PROTOCOL_ERROR, "Excessive field section size");
            span.end();
        }
        state = State.CLOSED;
        handler = null;
        if (endpoint != null) {
            endpoint.resetStream(H3ErrorCode.H3_EXCESSIVE_LOAD);
        }
    }

    private long localMaxFieldSectionSize() {
        if (connection == null) {
            return H3FrameHandler.DEFAULT_MAX_FIELD_SECTION_SIZE;
        }
        return connection.getLocalMaxFieldSectionSize();
    }

    // ── Telemetry ──

    /**
     * Initialises a telemetry span for this request if tracing is
     * enabled. Called once when the initial HEADERS event arrives.
     */
    private void initTelemetrySpan() {
        timestampStarted = System.currentTimeMillis();

        HttpServerMetrics metrics = connection.getMetrics();
        if (metrics != null) {
            metrics.requestStarted(method != null ? method : "UNKNOWN");
        }

        TelemetryConfig telemetryConfig = connection.getTelemetryConfig();
        if (telemetryConfig == null) {
            return;
        }
        Trace trace = connection.getTrace();

        String traceparent = requestHeaders != null ? HeaderFields.getValue(requestHeaders, "traceparent") : null;

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

            String host = requestHeaders != null ? HeaderFields.getValue(requestHeaders, ":authority") : null;
            if (host != null) {
                span.addAttribute("http.host", host);
            }
            String userAgent = requestHeaders != null ? HeaderFields.getValue(requestHeaders, "user-agent") : null;
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
        HttpServerMetrics metrics = connection.getMetrics();
        if (metrics != null && timestampStarted > 0) {
            double durationMs = System.currentTimeMillis() - timestampStarted;
            metrics.requestCompleted(method != null ? method : "UNKNOWN", statusCode, durationMs, 0, responseBodyBytes);
        }

        TelemetryConfig telemetryConfig = connection.getTelemetryConfig();
        if (telemetryConfig != null && telemetryConfig.accepts(LogLevel.ACCESS)) {
            HttpAccessLog.record(
                    telemetryConfig,
                    span,
                    System.currentTimeMillis(),
                    connection.getRemoteAddress(),
                    method,
                    requestTarget,
                    HttpVersion.HTTP_3.toString(),
                    authenticatedPrincipal,
                    applicationPrincipal,
                    statusCode,
                    responseBodyBytes);
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
    private void sendUnauthorized(HttpAuthenticationProvider authProvider) {
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
        String hsts = connection.getStrictTransportSecurityHeaderValue();
        if (hsts != null
                && !containsHeader(pendingResponseHeaders,
                "Strict-Transport-Security")) {
            pendingResponseHeaders.add(
                    new Header("Strict-Transport-Security", hsts));
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

        if (shouldCompressResponse(fin)) {
            String acceptEncoding = requestHeaders != null
                    ? HeaderFields.getCombinedValue(requestHeaders, "Accept-Encoding") : null;
            ContentEncoding.Coding coding =
                    ContentEncoding.selectFromAcceptEncoding(acceptEncoding);
            if (coding != null) {
                responseContentEncoder = ContentEncoding.createEncoder(coding);
                pendingResponseHeaders.add(new Header("content-encoding", coding.token()));
                removeHeaders(pendingResponseHeaders, "content-length");
            }
        }

        // Strip headers that are illegal in HTTP/3 (RFC 9114 section 4.2).
        // A Content-Length is kept: RFC 9114 section 4.1.2 requires it to
        // equal the DATA bytes, which bodyContent and endMessage enforce
        // (not for HEAD, 204 or 304, which carry no body).
        HeaderFields.stripHttp1FramingHeaders(pendingResponseHeaders, true);
        if (responseStatusCode >= 200 && responseStatusCode != 204
                && responseStatusCode != 304 && !"HEAD".equals(method)) {
            responseDeclaredLength = parseDeclaredLength(
                    HeaderFields.getValue(pendingResponseHeaders, "content-length"));
            if (responseDeclaredLength > 0 && fin) {
                pendingResponseHeaders = null;
                abortResponseLength(0L);
                return;
            }
        }

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
        if (connection != null && connection.exceedsPeerFieldSectionLimit(fields)) {
            abortExcessiveLoad();
            return;
        }
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
        if (H3Qlog.on(endpoint)) {
            H3Qlog.frameCreated(endpoint, streamId, H3Qlog.headersFrame(fields));
        }
        endpoint.send(out);
        if (fin) {
            endpoint.close();
        }
    }

    private void sendBody(ByteBuffer data) {
        if (responseContentEncoder != null) {
            try {
                responseContentEncoder.write(data, false);
            } catch (ContentEncoding.ContentEncodingException e) {
                abortMessageError("response Content-Encoding failed");
                return;
            }
            ByteBuffer encoded;
            while ((encoded = responseContentEncoder.readEncoded()) != null) {
                sendBodyWire(encoded);
            }
            return;
        }
        sendBodyWire(data);
    }

    private void sendBodyWire(ByteBuffer data) {
        int length = data.remaining();
        byte[] bytes = new byte[length];
        data.get(bytes);
        if (connection != null
                && !connection.claimResponseBodySlot(streamId)) {
            if (heldBody == null) {
                heldBody = new ByteArrayOutputStream();
            }
            try {
                heldBody.write(bytes);
            } catch (IOException e) {
                // ByteArrayOutputStream does not throw
            }
            return;
        }
        emitBody(bytes);
    }

    private void finishResponseContentEncoder() {
        if (responseContentEncoder == null) {
            return;
        }
        try {
            responseContentEncoder.write(ByteBuffer.allocate(0), true);
            ByteBuffer encoded;
            while ((encoded = responseContentEncoder.readEncoded()) != null) {
                sendBodyWire(encoded);
            }
        } catch (ContentEncoding.ContentEncodingException e) {
            abortMessageError("response Content-Encoding failed");
        } finally {
            responseContentEncoder.close();
            responseContentEncoder = null;
        }
    }

    private boolean shouldCompressResponse(boolean fin) {
        if (responseContentEncoder != null || fin) {
            return false;
        }
        if (pendingResponseHeaders == null) {
            return false;
        }
        if (containsHeader(pendingResponseHeaders, "content-encoding")
                || containsHeader(pendingResponseHeaders, "content-length")) {
            return false;
        }
        if ("HEAD".equals(method) || responseStatusCode == 204 || responseStatusCode == 304) {
            return false;
        }
        if (responseStatusCode < 200 || responseStatusCode >= 300) {
            return false;
        }
        if (isWebSocketUpgraded()) {
            return false;
        }
        if (!encodeResponseContentCoding || connection == null) {
            return false;
        }
        return connection.getCompressResponses();
    }

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
            sendErrorResponse(415);
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
        requestContentDecoder = ContentEncoding.createDecoder(requestInboundCoding,
                ContentEncoding.DEFAULT_MAX_DECOMPRESSED_SIZE);
        requestInboundCoding = null;
    }

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
                requestDecodedBytesReceived += decoded.remaining();
                bodyStarted = true;
                if (messageEvents) {
                    handler.bodyContent(decoded.asReadOnlyBuffer());
                }
            }
            if (finish) {
                requestContentDecoder.close();
                requestContentDecoder = null;
            }
        } catch (ContentEncoding.ContentEncodingException e) {
            sendErrorResponse(400);
            return false;
        }
        return true;
    }

    private static void removeHeaders(List<Header> headers, String name) {
        for (int i = headers.size() - 1; i >= 0; i--) {
            if (name.equalsIgnoreCase(headers.get(i).getName())) {
                headers.remove(i);
            }
        }
    }

    boolean hasHeldBody() {
        return heldBody != null && heldBody.size() > 0;
    }

    long getStreamId() {
        return streamId;
    }

    void flushHeldBody() {
        if (heldBody == null || heldBody.size() == 0) {
            return;
        }
        if (connection != null && !connection.claimResponseBodySlot(streamId)) {
            return;
        }
        byte[] bytes = heldBody.toByteArray();
        heldBody.reset();
        sendBody(ByteBuffer.wrap(bytes));
    }

    private void emitBody(byte[] bytes) {
        ByteBuffer out = ByteBuffer.allocate(H3Writer.dataLength(bytes.length));
        H3Writer.writeData(out, bytes);
        out.flip();
        if (H3Qlog.on(endpoint)) {
            H3Qlog.frameCreated(endpoint, streamId, H3Qlog.dataFrame(bytes.length));
        }
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
