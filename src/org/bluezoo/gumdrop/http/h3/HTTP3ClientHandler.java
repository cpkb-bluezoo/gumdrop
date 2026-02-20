/*
 * HTTP3ClientHandler.java
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
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicheNative;

/**
 * Client-side HTTP/3 handler built on top of quiche's h3 module.
 *
 * <p>This class implements
 * {@link QuicConnection.ConnectionReadyHandler} to receive
 * notifications when QUIC packets arrive. It then polls the quiche h3
 * module for HTTP/3 response events (HEADERS, DATA, FINISHED, RESET)
 * and dispatches them to per-stream {@link H3ClientStream} instances.
 *
 * <p>The quiche h3 module handles all HTTP/3 framing and QPACK header
 * compression internally. This class provides
 * {@link #sendRequest(Headers, HTTPResponseHandler)} to initiate
 * HTTP/3 requests and translates h3 response events into
 * {@link HTTPResponseHandler} callbacks.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see H3ClientStream
 * @see QuicConnection
 */
public class HTTP3ClientHandler
        implements QuicConnection.ConnectionReadyHandler {

    private static final Logger LOGGER =
            Logger.getLogger(HTTP3ClientHandler.class.getName());

    /** Default QPACK max dynamic table capacity. */
    private static final long DEFAULT_QPACK_MAX_TABLE_CAPACITY = 4096;

    /** Buffer size for receiving h3 body data. */
    private static final int BODY_BUFFER_SIZE = 65536;

    private final QuicConnection quicConnection;

    private long h3Config;
    private long h3Conn;
    private final ByteBuffer bodyBuffer;

    private final Map<Long, H3ClientStream> streams =
            new HashMap<Long, H3ClientStream>();

    private boolean goaway;

    /** Callback invoked when the h3 connection is ready for requests. */
    private Runnable readyCallback;

    /**
     * Creates a new HTTP/3 client handler on top of an existing
     * QUIC connection.
     *
     * @param quicConnection the underlying QUIC connection
     */
    public HTTP3ClientHandler(QuicConnection quicConnection) {
        this.quicConnection = quicConnection;
        this.bodyBuffer = ByteBuffer.allocateDirect(BODY_BUFFER_SIZE);

        initH3();
        quicConnection.setConnectionReadyHandler(this);
    }

    /**
     * Sets a callback that is invoked once the h3 connection is ready
     * to send requests.
     *
     * @param callback the ready callback
     */
    public void setReadyCallback(Runnable callback) {
        this.readyCallback = callback;
    }

    /**
     * Returns whether this handler has received a GOAWAY frame.
     *
     * @return true if GOAWAY was received
     */
    public boolean isGoaway() {
        return goaway;
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
            LOGGER.severe("Failed to create h3 client connection");
        }
    }

    // ── Request sending ──

    /**
     * Sends an HTTP/3 request on a new stream.
     *
     * <p>The headers must include the HTTP/3 pseudo-headers:
     * {@code :method}, {@code :scheme}, {@code :authority}, {@code :path}.
     *
     * @param headers the request headers
     * @param handler the handler to receive response events
     * @return the stream ID, or -1 on failure
     */
    public long sendRequest(Headers headers, HTTPResponseHandler handler) {
        return sendRequest(headers, handler, true);
    }

    /**
     * Sends an HTTP/3 request on a new stream.
     *
     * <p>If {@code fin} is false, the caller must send the request body
     * via {@link #sendRequestBody(long, ByteBuffer, boolean)}.
     *
     * @param headers the request headers
     * @param handler the handler to receive response events
     * @param fin true if no request body will follow
     * @return the stream ID, or -1 on failure
     */
    public long sendRequest(Headers headers, HTTPResponseHandler handler,
                            boolean fin) {
        if (goaway) {
            handler.failed(new java.io.IOException(
                    "Connection received GOAWAY"));
            return -1;
        }

        String[] headerArray = new String[headers.size() * 2];
        for (int i = 0; i < headers.size(); i++) {
            Header h = headers.get(i);
            headerArray[i * 2] = h.getName();
            headerArray[i * 2 + 1] = h.getValue();
        }

        long quicheConn = quicConnection.getConnPtr();
        long streamId = QuicheNative.quiche_h3_send_request(
                h3Conn, quicheConn, headerArray, fin);

        if (streamId < 0) {
            handler.failed(new java.io.IOException(
                    "h3 send_request failed: " + streamId));
            return -1;
        }

        H3ClientStream stream = new H3ClientStream(this, streamId,
                                                     handler);
        streams.put(Long.valueOf(streamId), stream);

        flushQuic();
        return streamId;
    }

    /**
     * Sends request body data on the specified stream.
     *
     * @param streamId the stream ID returned by
     *                 {@link #sendRequest(Headers, HTTPResponseHandler, boolean)}
     * @param data the body data
     * @param fin true if this is the last body data
     */
    public void sendRequestBody(long streamId, ByteBuffer data,
                                boolean fin) {
        long quicheConn = quicConnection.getConnPtr();
        int result = QuicheNative.quiche_h3_send_body(
                h3Conn, quicheConn, streamId,
                data, data.remaining(), fin);
        if (result < 0) {
            LOGGER.log(Level.WARNING,
                    "h3 send_body failed: " + result +
                    " stream=" + streamId);
        }
        flushQuic();
    }

    // ── QuicConnection.ConnectionReadyHandler ──

    @Override
    public void onConnectionReady() {
        if (readyCallback != null) {
            Runnable cb = readyCallback;
            readyCallback = null;
            cb.run();
        }
        pollEvents();
    }

    // ── Event Polling ──

    /**
     * Polls the h3 connection for pending events and dispatches them
     * to the appropriate client stream handlers.
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
                case HTTP3ServerHandler.H3_EVENT_HEADERS:
                    onHeaders(streamId);
                    break;
                case HTTP3ServerHandler.H3_EVENT_DATA:
                    onData(streamId);
                    break;
                case HTTP3ServerHandler.H3_EVENT_FINISHED:
                    onFinished(streamId);
                    break;
                case HTTP3ServerHandler.H3_EVENT_GOAWAY:
                    onGoaway(streamId);
                    break;
                case HTTP3ServerHandler.H3_EVENT_RESET:
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

        H3ClientStream stream = streams.get(Long.valueOf(streamId));
        if (stream != null) {
            stream.onHeaders(headerPairs);
        }
    }

    private void onData(long streamId) {
        H3ClientStream stream = streams.get(Long.valueOf(streamId));
        if (stream == null) {
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
        H3ClientStream stream = streams.get(Long.valueOf(streamId));
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
        H3ClientStream stream = streams.get(Long.valueOf(streamId));
        if (stream != null) {
            stream.onReset();
            streams.remove(Long.valueOf(streamId));
        }
    }

    // ── Accessors ──

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
     * Requests that outgoing QUIC packets be flushed.
     */
    void flushQuic() {
        quicConnection.getEngine().requestFlush();
    }

    /**
     * Closes this HTTP/3 client handler and frees native resources.
     */
    public void close() {
        for (H3ClientStream stream : streams.values()) {
            stream.onReset();
        }
        streams.clear();

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
