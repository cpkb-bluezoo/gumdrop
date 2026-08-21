/*
 * HTTP3ServerHandler.java
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

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.http.HTTPAuthenticationProvider;
import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPServerMetrics;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.PriorityParams;
import org.bluezoo.gumdrop.http.Rfc9218NonIncrementalSlots;
import org.bluezoo.gumdrop.http.qpack.Decoder;
import org.bluezoo.gumdrop.http.qpack.Encoder;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicStreamEndpoint;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.websocket.WebSocketServerMetrics;

/**
 * Server-side HTTP/3 handler for one QUIC connection.
 *
 * <p>HTTP/3 (RFC 9114) maps HTTP semantics onto QUIC (RFC 9000) transport.
 * Unlike HTTP/2, HTTP/3 does not use TCP; instead each request/response
 * exchange occupies a dedicated QUIC stream, with QPACK (RFC 9204) for
 * header compression.
 *
 * <p>This class registers itself as the {@link QuicConnection}'s
 * {@link StreamAcceptHandler} for new peer-initiated bidirectional
 * streams (RFC 9114 section 4.1 request streams) and, via
 * {@link H3ControlStream}, as the unidirectional-stream accept handler
 * for the peer's control stream (RFC 9114 section 6.2.1) -- opening its
 * own control stream and sending SETTINGS (RFC 9114 section 7.2.4)
 * immediately. Each accepted request stream becomes a new {@link H3Stream},
 * which owns its own {@link H3Parser} and handles its request/response
 * lifecycle; this class additionally tracks RFC 9218 stream priority so
 * response DATA can be scheduled by urgency.
 *
 * <p>Architecture:
 * <pre>
 *   UDP packets
 *       |
 *   QuicEngine  (datagram I/O, connection demux)
 *       |
 *   QuicConnection  (QUIC connection lifecycle, per-stream dispatch)
 *       |
 *   HTTP3ServerHandler  (per-connection setup: control stream, SETTINGS)
 *       |
 *   H3Stream  (per-request HTTPResponseState)
 *       |
 *   HTTPRequestHandler  (application logic, same as HTTP/2)
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see H3Stream
 * @see QuicConnection
 */
public final class HTTP3ServerHandler implements StreamAcceptHandler, H3ControlStream.Listener {

    private static final Logger LOGGER = Logger.getLogger(HTTP3ServerHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.http.h3.L10N");

    private final QuicConnection quicConnection;
    private final HTTPRequestHandlerFactory handlerFactory;
    private final HTTPAuthenticationProvider authenticationProvider;
    private final HTTPServerMetrics metrics;
    private final TelemetryConfig telemetryConfig;
    private final boolean addSecurityHeaders;

    // RFC 9204 section 3.2.1: matches HPACK's own well-known
    // SETTINGS_HEADER_TABLE_SIZE default (RFC 7541 section 6.5.2) --
    // generous for real header sets, still bounded per connection.
    private static final int DEFAULT_QPACK_TABLE_CAPACITY = 4096;

    // Our own declared receive-side ceiling (SETTINGS_QPACK_MAX_TABLE_CAPACITY),
    // fixed for the connection's lifetime (Decoder enforces this itself,
    // RFC 9204 section 3.2.3). Our send-side Encoder starts at capacity 0
    // (falls back to literal-only encoding) until the peer's own SETTINGS
    // arrives and tells us the ceiling it's willing to accept -- see
    // settingsReceived.
    private final Encoder qpackEncoder = new Encoder(0);
    private final Decoder qpackDecoder = new Decoder(DEFAULT_QPACK_TABLE_CAPACITY);
    private Endpoint qpackEncoderStream;
    private Endpoint qpackDecoderStream;

    private long highestClientStreamId = -1;
    private Trace trace;
    private boolean goaway;
    private WebSocketServerMetrics wsMetrics;
    // RFC 9114 section 5.2: client-indicated last stream ID from GOAWAY
    private long goawayStreamId = Long.MAX_VALUE;

    private final Map<Long, PriorityParams> streamPriority = new HashMap<Long, PriorityParams>();
    private final Map<Long, H3Stream> requestStreams = new HashMap<Long, H3Stream>();
    private final Rfc9218NonIncrementalSlots nonIncSlots = new Rfc9218NonIncrementalSlots();

    /**
     * Creates a new HTTP/3 server handler on top of an existing
     * QUIC connection.
     *
     * @param quicConnection the underlying QUIC connection
     * @param handlerFactory factory for creating request handlers
     * @param authProvider authentication provider (may be null)
     * @param metrics server metrics (may be null)
     * @param telemetryConfig telemetry configuration (may be null)
     * @param addSecurityHeaders whether to add default security headers
     */
    public HTTP3ServerHandler(QuicConnection quicConnection,
                              HTTPRequestHandlerFactory handlerFactory,
                              HTTPAuthenticationProvider authProvider,
                              HTTPServerMetrics metrics,
                              TelemetryConfig telemetryConfig,
                              boolean addSecurityHeaders) {
        this.quicConnection = quicConnection;
        this.handlerFactory = handlerFactory;
        this.authenticationProvider = authProvider;
        this.metrics = metrics;
        this.telemetryConfig = telemetryConfig;
        this.addSecurityHeaders = addSecurityHeaders;

        if (metrics != null) {
            metrics.connectionOpened();
        }

        quicConnection.setStreamAcceptHandler(this);
        quicConnection.setUnidirectionalStreamAcceptHandler(new StreamAcceptHandler() {
            @Override
            public ProtocolHandler acceptStream(Endpoint stream) {
                return new H3ControlStream(quicConnection, HTTP3ServerHandler.this, qpackEncoder, qpackDecoder,
                        false);
            }
        });

        openControlStream();
        openQpackStreams();
    }

    // RFC 9114 section 6.2.1 / 7.2.4: open our own control stream and
    // send SETTINGS as its first frame. The send lives in connected()
    // so a queued open (no uni-stream credit yet; RFC 9000 section 4.6)
    // still emits SETTINGS once the peer grants the stream.
    private void openControlStream() {
        final long[] settings = {
            H3FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1,
            H3FrameHandler.SETTINGS_QPACK_MAX_TABLE_CAPACITY, DEFAULT_QPACK_TABLE_CAPACITY
        };
        quicConnection.openUnidirectionalStream(new ProtocolHandler() {
            @Override
            public void connected(Endpoint endpoint) {
                int length = H3Writer.streamTypeLength(0x00) + H3Writer.settingsLength(settings);
                ByteBuffer out = ByteBuffer.allocate(length);
                H3Writer.writeStreamType(out, 0x00);
                H3Writer.writeSettings(out, settings);
                out.flip();
                endpoint.send(out);
            }

            @Override
            public void receive(ByteBuffer data) {
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
            }

            @Override
            public void disconnected() {
            }

            @Override
            public void error(Exception cause) {
            }
        });
    }

    // RFC 9204 section 4.2: open our own QPACK encoder and decoder
    // streams, kept open for the connection's lifetime -- neither is
    // ever closed, matching the control stream.
    private void openQpackStreams() {
        quicConnection.openUnidirectionalStream(new ProtocolHandler() {
            @Override
            public void connected(Endpoint endpoint) {
                qpackEncoderStream = endpoint;
                ByteBuffer out = ByteBuffer.allocate(H3Writer.streamTypeLength(0x02));
                H3Writer.writeStreamType(out, 0x02);
                out.flip();
                endpoint.send(out);
            }

            @Override
            public void receive(ByteBuffer data) {
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
            }

            @Override
            public void disconnected() {
            }

            @Override
            public void error(Exception cause) {
            }
        });
        quicConnection.openUnidirectionalStream(new ProtocolHandler() {
            @Override
            public void connected(Endpoint endpoint) {
                qpackDecoderStream = endpoint;
                ByteBuffer out = ByteBuffer.allocate(H3Writer.streamTypeLength(0x03));
                H3Writer.writeStreamType(out, 0x03);
                out.flip();
                endpoint.send(out);
            }

            @Override
            public void receive(ByteBuffer data) {
            }

            @Override
            public void securityEstablished(SecurityInfo info) {
            }

            @Override
            public void disconnected() {
            }

            @Override
            public void error(Exception cause) {
            }
        });
    }

    /**
     * Sends any QPACK encoder-stream instructions {@code
     * Encoder#encode} wrote to {@code instructions} (often nothing) on
     * this connection's own QPACK encoder stream.
     *
     * @param instructions the (possibly empty) instructions buffer,
     *                     positioned for reading
     */
    void flushQpackEncoderInstructions(ByteBuffer instructions) {
        if (instructions.hasRemaining() && qpackEncoderStream != null) {
            qpackEncoderStream.send(instructions);
        }
    }

    /**
     * Sends any QPACK decoder-stream instructions queued by {@link
     * Decoder#decode} (Section Acknowledgment) or by mirroring an
     * encoder-stream insertion (Insert Count Increment) on this
     * connection's own QPACK decoder stream.
     */
    void flushQpackDecoderInstructions() {
        byte[] bytes = qpackDecoder.takePendingInstructions();
        if (bytes.length > 0 && qpackDecoderStream != null) {
            qpackDecoderStream.send(ByteBuffer.wrap(bytes));
        }
    }

    /**
     * RFC 9204 section 4.4.2: notifies the peer encoder that {@code
     * streamId} was abandoned before its (possibly still in-flight)
     * field section was decoded, so it can release its table
     * references for it -- otherwise they leak for the rest of the
     * connection (a reference-counted entry can never be evicted).
     *
     * @param streamId the stream that was abandoned
     */
    void cancelQpackStream(long streamId) {
        qpackDecoder.cancelStream(streamId);
        flushQpackDecoderInstructions();
    }

    // ── StreamAcceptHandler (RFC 9114 section 4.1: new request streams) ──

    // RFC 9114 section 5.2: after receiving GOAWAY, reject new
    // client-initiated streams beyond the indicated last-stream-ID
    @Override
    public ProtocolHandler acceptStream(Endpoint stream) {
        long streamId = ((QuicStreamEndpoint) stream).getStreamId();
        if (goaway && streamId > goawayStreamId) {
            if (LOGGER.isLoggable(Level.FINE)) {
                String formatted = MessageFormat.format(
                        L10N.getString("fine.reject_stream_beyond_goaway"),
                        Long.valueOf(streamId), Long.valueOf(goawayStreamId));
                LOGGER.fine(formatted);
            }
            return null;
        }
        if (streamId > highestClientStreamId) {
            highestClientStreamId = streamId;
        }
        return new H3Stream(this, qpackEncoder, qpackDecoder);
    }

    // ── H3ControlStream.Listener (peer's control stream) ──

    @Override
    public void settingsReceived(long[] settings) {
        // RFC 9204 section 3.2.1/4.3.1: our own Encoder may not use more
        // dynamic-table capacity than the peer's declared receive-side
        // ceiling permits, whichever is smaller against our own default.
        for (int i = 0; i + 1 < settings.length; i += 2) {
            if (settings[i] == H3FrameHandler.SETTINGS_QPACK_MAX_TABLE_CAPACITY) {
                int capacity = (int) Math.min(DEFAULT_QPACK_TABLE_CAPACITY, settings[i + 1]);
                ByteBuffer instructions = ByteBuffer.allocate(16);
                qpackEncoder.setCapacity(instructions, capacity);
                instructions.flip();
                flushQpackEncoderInstructions(instructions);
                break;
            }
        }
    }

    // RFC 9114 section 5.2: GOAWAY for graceful shutdown. Record the
    // last stream ID the client will process so we stop accepting new
    // streams beyond it, and send a server GOAWAY in response
    // indicating which client requests we will honour.
    @Override
    public void goawayReceived(long streamOrPushId) {
        goaway = true;
        goawayStreamId = streamOrPushId;
        if (LOGGER.isLoggable(Level.FINE)) {
            String formatted = MessageFormat.format(
                    L10N.getString("fine.goaway_received"), Long.valueOf(streamOrPushId));
            LOGGER.fine(formatted);
        }
        if (highestClientStreamId >= 0) {
            sendGoaway(highestClientStreamId);
        }
    }

    @Override
    public void priorityUpdateReceived(long streamId, String fieldValue) {
        applyRequestPriority(streamId, PriorityParams.parse(fieldValue), true);
    }

    void registerRequestStream(H3Stream stream) {
        requestStreams.put(Long.valueOf(stream.getStreamId()), stream);
    }

    /**
     * Applies RFC 9218 priority. A PRIORITY_UPDATE always overwrites;
     * a {@code Priority} header is ignored if an update already landed.
     *
     * @param streamId the request stream
     * @param params the parsed parameters
     * @param fromUpdate true if this came from PRIORITY_UPDATE
     */
    void applyRequestPriority(long streamId, PriorityParams params, boolean fromUpdate) {
        Long key = Long.valueOf(streamId);
        if (!fromUpdate && streamPriority.containsKey(key)) {
            return;
        }
        streamPriority.put(key, params);
        quicConnection.setStreamSendPriority(streamId, params.quicSendPriority());
    }

    PriorityParams getStreamPriority(long streamId) {
        PriorityParams params = streamPriority.get(Long.valueOf(streamId));
        return params != null ? params : PriorityParams.DEFAULT;
    }

    boolean claimResponseBodySlot(long streamId) {
        return nonIncSlots.claim(streamId, getStreamPriority(streamId));
    }

    void streamFinished(H3Stream stream) {
        long streamId = stream.getStreamId();
        Long key = Long.valueOf(streamId);
        if (requestStreams.remove(key) == null) {
            return;
        }
        PriorityParams params = getStreamPriority(streamId);
        nonIncSlots.release(streamId, params);
        streamPriority.remove(key);
        flushHeldBodies(params.getUrgency());
    }

    private void flushHeldBodies(int urgency) {
        H3Stream next = null;
        for (H3Stream stream : requestStreams.values()) {
            if (!stream.hasHeldBody()) {
                continue;
            }
            PriorityParams params = getStreamPriority(stream.getStreamId());
            if (params.isIncremental() || params.getUrgency() != urgency) {
                continue;
            }
            if (next == null || stream.getStreamId() < next.getStreamId()) {
                next = stream;
            }
        }
        if (next != null) {
            next.flushHeldBody();
        }
    }

    private void sendGoaway(long streamId) {
        Endpoint controlStream = quicConnection.openUnidirectionalStream(new NullProtocolHandler());
        if (controlStream == null) {
            return;
        }
        int length = H3Writer.goawayLength(streamId);
        ByteBuffer out = ByteBuffer.allocate(length);
        H3Writer.writeGoaway(out, streamId);
        out.flip();
        controlStream.send(out);
        controlStream.close();
    }

    // ── Accessors for H3Stream ──

    /**
     * Creates an {@link HTTPRequestHandler} for a new stream.
     *
     * @param stream the H3Stream acting as HTTPResponseState
     * @param headers the initial request headers
     * @return the created handler, or null
     */
    HTTPRequestHandler createHandler(H3Stream stream, Headers headers) {
        if (handlerFactory == null) {
            return null;
        }
        return handlerFactory.createHandler(stream, headers);
    }

    /**
     * Returns the remote (client) address for this HTTP/3 connection.
     */
    SocketAddress getRemoteAddress() {
        return quicConnection.getRemoteAddress();
    }

    /**
     * Returns the local (server) address for this HTTP/3 connection.
     */
    SocketAddress getLocalAddress() {
        return quicConnection.getLocalAddress();
    }

    /**
     * Returns security metadata for this HTTP/3 connection.
     * Always non-null because QUIC mandates TLS 1.3.
     */
    SecurityInfo getSecurityInfo() {
        return quicConnection.getSecurityInfo();
    }

    /**
     * Returns the SelectorLoop that owns this connection's I/O.
     */
    SelectorLoop getSelectorLoop() {
        return quicConnection.getSelectorLoop();
    }

    /**
     * Closes the underlying QUIC connection with an HTTP/3 application
     * error (RFC 9114 section 8.1).
     *
     * @param errorCode an {@link H3ErrorCode} value
     * @param reason a human-readable reason phrase
     */
    void closeWithApplicationError(long errorCode, String reason) {
        quicConnection.closeWithApplicationError(errorCode, reason);
    }

    // ── Telemetry ──

    /**
     * Returns the authentication provider, or null if authentication
     * is not configured.
     */
    HTTPAuthenticationProvider getAuthenticationProvider() {
        return authenticationProvider;
    }

    /**
     * Returns the telemetry configuration, or null if telemetry is
     * not enabled.
     */
    TelemetryConfig getTelemetryConfig() {
        return telemetryConfig;
    }

    boolean getAddSecurityHeaders() {
        return addSecurityHeaders;
    }

    /**
     * Returns the current trace for this connection, or null.
     */
    Trace getTrace() {
        return trace;
    }

    /**
     * Sets the current trace for this connection.
     *
     * @param trace the trace
     */
    void setTrace(Trace trace) {
        this.trace = trace;
    }

    /**
     * Returns true if telemetry tracing is enabled.
     */
    boolean isTelemetryEnabled() {
        return telemetryConfig != null && telemetryConfig.isTracesEnabled();
    }

    /**
     * Returns the HTTP server metrics, or null.
     */
    HTTPServerMetrics getMetrics() {
        return metrics;
    }

    /**
     * Sets the WebSocket server metrics for streams upgraded via
     * RFC 9220 Extended CONNECT.
     *
     * @param wsMetrics the WebSocket metrics, or null
     */
    public void setWebSocketMetrics(WebSocketServerMetrics wsMetrics) {
        this.wsMetrics = wsMetrics;
    }

    /**
     * Returns the WebSocket server metrics, or null.
     */
    WebSocketServerMetrics getWebSocketMetrics() {
        return wsMetrics;
    }

    /**
     * Closes this HTTP/3 handler.
     * RFC 9114 section 5.2: sends GOAWAY with the highest client-initiated
     * stream ID processed so far as a courtesy; actual QUIC connection
     * teardown (which tears down open request streams) is the caller's
     * responsibility.
     */
    public void close() {
        if (highestClientStreamId >= 0) {
            sendGoaway(highestClientStreamId);
        }
        if (metrics != null) {
            metrics.connectionClosed();
        }
    }

    /** A {@link ProtocolHandler} for our own send-only unidirectional streams, which never receive data. */
    private static final class NullProtocolHandler implements ProtocolHandler {

        @Override
        public void connected(Endpoint endpoint) {
        }

        @Override
        public void receive(ByteBuffer data) {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void error(Exception cause) {
        }
    }
}
