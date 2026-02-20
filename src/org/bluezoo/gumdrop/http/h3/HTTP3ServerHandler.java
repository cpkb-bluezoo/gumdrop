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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.HTTPRequestHandler;
import org.bluezoo.gumdrop.http.HTTPRequestHandlerFactory;
import org.bluezoo.gumdrop.http.HTTPServerMetrics;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicheNative;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * Server-side HTTP/3 handler built on top of quiche's h3 module.
 *
 * <p>This class implements
 * {@link QuicConnection.ConnectionReadyHandler} to receive
 * notifications when QUIC packets arrive. It then polls the quiche h3
 * module for HTTP/3 events (HEADERS, DATA, FINISHED, GOAWAY, RESET)
 * and dispatches them to per-stream {@link H3Stream} instances.
 *
 * <p>The quiche h3 module handles all HTTP/3 framing and QPACK header
 * compression internally. This class simply bridges between the h3
 * event model and the {@link HTTPRequestHandler} API used by the
 * existing gumdrop HTTP stack.
 *
 * <p>Architecture:
 * <pre>
 *   UDP packets
 *       |
 *   QuicEngine  (datagram I/O, connection demux)
 *       |
 *   QuicConnection  (QUIC connection lifecycle)
 *       |
 *   HTTP3ServerHandler  (h3 event polling, stream dispatch)
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
public class HTTP3ServerHandler
        implements QuicConnection.ConnectionReadyHandler {

    private static final Logger LOGGER =
            Logger.getLogger(HTTP3ServerHandler.class.getName());

    /** H3 event type: HEADERS frame received. */
    static final int H3_EVENT_HEADERS = 0;
    /** H3 event type: DATA frame received. */
    static final int H3_EVENT_DATA = 1;
    /** H3 event type: Stream finished (FIN). */
    static final int H3_EVENT_FINISHED = 2;
    /** H3 event type: GOAWAY frame received. */
    static final int H3_EVENT_GOAWAY = 3;
    /** H3 event type: Stream reset. */
    static final int H3_EVENT_RESET = 4;

    /** Default QPACK max dynamic table capacity. */
    private static final long DEFAULT_QPACK_MAX_TABLE_CAPACITY = 4096;

    /** Buffer size for receiving h3 body data. */
    private static final int BODY_BUFFER_SIZE = 65536;

    private final QuicConnection quicConnection;
    private final HTTPRequestHandlerFactory handlerFactory;
    private final HTTPServerMetrics metrics;
    private final TelemetryConfig telemetryConfig;

    private long h3Config;
    private long h3Conn;
    private final ByteBuffer bodyBuffer;

    private final Map<Long, H3Stream> streams =
            new HashMap<Long, H3Stream>();
    private final List<H3Stream> pendingWriteStreams =
            new ArrayList<H3Stream>();

    private Trace trace;
    private boolean goaway;

    /**
     * Creates a new HTTP/3 server handler on top of an existing
     * QUIC connection.
     *
     * @param quicConnection the underlying QUIC connection
     * @param handlerFactory factory for creating request handlers
     * @param metrics server metrics (may be null)
     * @param telemetryConfig telemetry configuration (may be null)
     */
    public HTTP3ServerHandler(QuicConnection quicConnection,
                              HTTPRequestHandlerFactory handlerFactory,
                              HTTPServerMetrics metrics,
                              TelemetryConfig telemetryConfig) {
        this.quicConnection = quicConnection;
        this.handlerFactory = handlerFactory;
        this.metrics = metrics;
        this.telemetryConfig = telemetryConfig;
        this.bodyBuffer = ByteBuffer.allocateDirect(BODY_BUFFER_SIZE);

        if (metrics != null) {
            metrics.connectionOpened();
        }

        quicConnection.setConnectionReadyHandler(this);
    }

    /**
     * Initialises the quiche h3 config and creates the h3 connection.
     */
    private void initH3() {
        h3Config = QuicheNative.quiche_h3_config_new();
        QuicheNative.quiche_h3_config_set_max_dynamic_table_capacity(
                h3Config, DEFAULT_QPACK_MAX_TABLE_CAPACITY);

        long quicheConn = quicConnection.getConnPtr();
        h3Conn = QuicheNative.quiche_h3_conn_new_with_transport(
                quicheConn, h3Config);

        if (h3Conn == 0) {
            LOGGER.severe("Failed to create h3 connection");
        }
    }

    // ── QuicConnection.ConnectionReadyHandler ──

    @Override
    public void onConnectionReady() {
        if (h3Conn == 0) {
            initH3();
        }
        if (h3Conn != 0) {
            pollEvents();
            resumePendingWrites();
        }
    }

    // ── Event Polling ──

    /**
     * Polls the h3 connection for pending events and dispatches them
     * to the appropriate stream handlers.
     */
    private void pollEvents() {
        long quicheConn = quicConnection.getConnPtr();

        while (true) {
            long[] event = QuicheNative.quiche_h3_conn_poll(
                    h3Conn, quicheConn);
            if (event == null) {
                break;
            }

            long streamId = event[0];
            int eventType = (int) event[1];

            switch (eventType) {
                case H3_EVENT_HEADERS:
                    onHeaders(streamId);
                    break;
                case H3_EVENT_DATA:
                    onData(streamId);
                    break;
                case H3_EVENT_FINISHED:
                    onFinished(streamId);
                    break;
                case H3_EVENT_GOAWAY:
                    onGoaway(streamId);
                    break;
                case H3_EVENT_RESET:
                    onReset(streamId);
                    break;
                default:
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.fine("Unknown h3 event type: " +
                                eventType + " on stream " + streamId);
                    }
                    break;
            }
        }
    }

    private void onHeaders(long streamId) {
        String[] headerPairs =
                QuicheNative.quiche_h3_event_headers(h3Conn);
        if (headerPairs == null) {
            return;
        }

        H3Stream stream = getOrCreateStream(streamId);
        stream.onHeaders(headerPairs);
    }

    private void onData(long streamId) {
        H3Stream stream = streams.get(Long.valueOf(streamId));
        if (stream == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("DATA for unknown stream " + streamId);
            }
            return;
        }

        long quicheConn = quicConnection.getConnPtr();

        while (true) {
            bodyBuffer.clear();
            int len = QuicheNative.quiche_h3_recv_body(
                    h3Conn, quicheConn, streamId,
                    bodyBuffer, bodyBuffer.capacity());
            if (len <= 0) {
                break;
            }
            bodyBuffer.limit(len);
            stream.onData(bodyBuffer);
        }
    }

    private void onFinished(long streamId) {
        H3Stream stream = streams.get(Long.valueOf(streamId));
        if (stream != null) {
            stream.onFinished();
            streams.remove(Long.valueOf(streamId));
        }
    }

    private void onGoaway(long streamId) {
        goaway = true;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("GOAWAY received, last stream: " + streamId);
        }
    }

    private void onReset(long streamId) {
        H3Stream stream = streams.get(Long.valueOf(streamId));
        if (stream != null) {
            stream.onReset();
            streams.remove(Long.valueOf(streamId));
        }
    }

    /**
     * Returns the existing stream or creates a new one.
     */
    private H3Stream getOrCreateStream(long streamId) {
        Long key = Long.valueOf(streamId);
        H3Stream stream = streams.get(key);
        if (stream == null) {
            stream = new H3Stream(this, streamId);
            streams.put(key, stream);
        }
        return stream;
    }

    // ── Accessors for H3Stream ──

    /**
     * Returns the native h3 connection handle.
     */
    long getH3Conn() {
        return h3Conn;
    }

    /**
     * Returns the native quiche connection handle.
     */
    long getQuicheConn() {
        return quicConnection.getConnPtr();
    }

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
     * Flushes outgoing QUIC packets immediately.
     * Called by {@link H3Stream} after sending response data.
     * Performs a direct flush rather than scheduling OP_WRITE,
     * since we may be in a send loop that needs the wire flush
     * to make progress (free quiche's internal send buffer).
     */
    void flushQuic() {
        quicConnection.getEngine().flushConnection(quicConnection);
    }

    /**
     * Registers a stream that has buffered data waiting for the
     * congestion window to open.  Called by {@link H3Stream} when
     * {@code quiche_h3_send_body} returns {@code QUICHE_ERR_DONE}.
     */
    void registerPendingWrite(H3Stream stream) {
        if (!pendingWriteStreams.contains(stream)) {
            pendingWriteStreams.add(stream);
        }
    }

    /**
     * Drains buffered data on all streams that were blocked by flow
     * control.  Called from {@link #onConnectionReady()} after
     * incoming packets (which may carry ACKs) have been processed.
     */
    private void resumePendingWrites() {
        int i = 0;
        while (i < pendingWriteStreams.size()) {
            H3Stream stream = pendingWriteStreams.get(i);
            if (stream.resumeWrite()) {
                pendingWriteStreams.remove(i);
            } else {
                i++;
            }
        }
    }

    /**
     * Returns the remote (client) address for this HTTP/3 connection.
     */
    java.net.SocketAddress getRemoteAddress() {
        return quicConnection.getRemoteAddress();
    }

    /**
     * Returns the local (server) address for this HTTP/3 connection.
     */
    java.net.SocketAddress getLocalAddress() {
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
        return quicConnection.getEngine().getSelectorLoop();
    }

    // ── Telemetry ──

    /**
     * Returns the telemetry configuration, or null if telemetry is
     * not enabled.
     */
    TelemetryConfig getTelemetryConfig() {
        return telemetryConfig;
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
        return telemetryConfig != null
                && telemetryConfig.isTracesEnabled();
    }

    /**
     * Returns the HTTP server metrics, or null.
     */
    HTTPServerMetrics getMetrics() {
        return metrics;
    }

    /**
     * Closes this HTTP/3 handler and frees native resources.
     */
    public void close() {
        for (H3Stream stream : streams.values()) {
            stream.onReset();
        }
        streams.clear();

        if (metrics != null) {
            metrics.connectionClosed();
        }

        if (h3Conn != 0) {
            QuicheNative.quiche_h3_conn_free(h3Conn);
            h3Conn = 0;
        }
        if (h3Config != 0) {
            QuicheNative.quiche_h3_config_free(h3Config);
            h3Config = 0;
        }
    }

}
