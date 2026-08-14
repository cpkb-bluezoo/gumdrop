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
import org.bluezoo.gumdrop.http.qpack.SimpleDecoder;
import org.bluezoo.gumdrop.http.qpack.SimpleEncoder;
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
 * lifecycle directly; unlike the previous quiche-backed implementation,
 * this class does not poll for events or track per-stream state itself.
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

    private final QuicConnection quicConnection;
    private final HTTPRequestHandlerFactory handlerFactory;
    private final HTTPAuthenticationProvider authenticationProvider;
    private final HTTPServerMetrics metrics;
    private final TelemetryConfig telemetryConfig;
    private final boolean addSecurityHeaders;

    private final SimpleEncoder qpackEncoder = new SimpleEncoder();
    private final SimpleDecoder qpackDecoder = new SimpleDecoder();

    private long highestClientStreamId = -1;
    private Trace trace;
    private boolean goaway;
    private WebSocketServerMetrics wsMetrics;
    // RFC 9114 section 5.2: client-indicated last stream ID from GOAWAY
    private long goawayStreamId = Long.MAX_VALUE;

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
                return new H3ControlStream(HTTP3ServerHandler.this);
            }
        });

        openControlStream();
    }

    // RFC 9114 section 6.2.1 / 7.2.4: open our own control stream and
    // send SETTINGS as its first frame.
    private void openControlStream() {
        Endpoint controlStream = quicConnection.openUnidirectionalStream(new NullProtocolHandler());
        long[] settings = {
            H3FrameHandler.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1
        };
        int length = H3Writer.streamTypeLength(0x00) + H3Writer.settingsLength(settings);
        ByteBuffer out = ByteBuffer.allocate(length);
        H3Writer.writeStreamType(out, 0x00);
        H3Writer.writeSettings(out, settings);
        out.flip();
        controlStream.send(out);
    }

    // ── StreamAcceptHandler (RFC 9114 section 4.1: new request streams) ──

    // RFC 9114 section 5.2: after receiving GOAWAY, reject new
    // client-initiated streams beyond the indicated last-stream-ID
    @Override
    public ProtocolHandler acceptStream(Endpoint stream) {
        long streamId = ((QuicStreamEndpoint) stream).getStreamId();
        if (goaway && streamId > goawayStreamId) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Rejecting stream " + streamId + " beyond GOAWAY limit " + goawayStreamId);
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
        // Nothing to react to yet: SETTINGS_QPACK_MAX_TABLE_CAPACITY
        // stays 0 on both sides this stage (static-table-only QPACK).
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
            LOGGER.fine("GOAWAY received, last stream: " + streamOrPushId);
        }
        if (highestClientStreamId >= 0) {
            sendGoaway(highestClientStreamId);
        }
    }

    private void sendGoaway(long streamId) {
        Endpoint controlStream = quicConnection.openUnidirectionalStream(new NullProtocolHandler());
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
