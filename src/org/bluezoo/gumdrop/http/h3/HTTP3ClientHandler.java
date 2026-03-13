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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.GumdropNative;

/**
 * Client-side HTTP/3 handler built on top of quiche's h3 module.
 *
 * <p>HTTP/3 (RFC 9114) maps HTTP semantics onto QUIC (RFC 9000)
 * transport. The client negotiates "h3" via ALPN (RFC 9114 section 3.1)
 * during the QUIC handshake, then exchanges SETTINGS frames
 * (RFC 9114 section 7.2.4). QPACK header compression (RFC 9204) and
 * HTTP/3 framing (RFC 9114 section 7) are handled by the quiche h3
 * module internally.
 *
 * <p>This class implements
 * {@link QuicConnection.ConnectionReadyHandler} to receive
 * notifications when QUIC packets arrive. It then polls the quiche h3
 * module for HTTP/3 response events (HEADERS, DATA, FINISHED, RESET)
 * and dispatches them to per-stream {@link H3ClientStream} instances.
 *
 * <p>This class provides
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
    private final Map<Long, PendingWrite> pendingWrites =
            new LinkedHashMap<Long, PendingWrite>();

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
        h3Config = GumdropNative.quiche_h3_config_new();
        GumdropNative.quiche_h3_config_set_max_dynamic_table_capacity(
                h3Config, DEFAULT_QPACK_MAX_TABLE_CAPACITY);

        long quicheConn = quicConnection.getConnPtr();
        h3Conn = GumdropNative.quiche_h3_conn_new_with_transport(
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
     * Sends an HTTP/3 request on a new stream (RFC 9114 section 4.1).
     *
     * <p>The headers must include the pseudo-headers defined in
     * RFC 9114 section 4.3.1: {@code :method}, {@code :scheme},
     * {@code :authority}, and {@code :path}.
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
        long streamId = GumdropNative.quiche_h3_send_request(
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
     * Sends request body data on the specified stream (RFC 9114
     * section 4.1 — DATA frames carry the message body).
     *
     * <p>If the QUIC congestion window is full, remaining data is
     * buffered and drained when ACKs arrive (mirroring the server-side
     * {@code H3Stream.enqueue()} pattern).
     *
     * @param streamId the stream ID returned by
     *                 {@link #sendRequest(Headers, HTTPResponseHandler, boolean)}
     * @param data the body data
     * @param fin true if this is the last body data
     */
    public void sendRequestBody(long streamId, ByteBuffer data,
                                boolean fin) {
        PendingWrite pending = pendingWrites.get(Long.valueOf(streamId));
        if (pending != null) {
            pending.enqueue(data, fin);
            return;
        }

        long quicheConn = quicConnection.getConnPtr();

        while (data.hasRemaining()) {
            int result = GumdropNative.quiche_h3_send_body(
                    h3Conn, quicheConn, streamId,
                    data, data.remaining(), false);
            if (result > 0) {
                data.position(data.position() + result);
                flushQuic();
            } else if (result == GumdropNative.QUICHE_ERR_DONE) {
                flushQuic();
                PendingWrite pw = new PendingWrite();
                pw.enqueue(data, fin);
                pendingWrites.put(Long.valueOf(streamId), pw);
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
            if (result < 0
                    && result != GumdropNative.QUICHE_ERR_DONE) {
                LOGGER.warning("h3 send_body FIN error: " + result
                        + " stream=" + streamId);
            }
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
        resumePendingWrites();
    }

    // ── Event Polling ──

    /**
     * Polls the h3 connection for pending events and dispatches them
     * to the appropriate client stream handlers. Events correspond to
     * HTTP/3 frame types (RFC 9114 section 7.2) and connection-level
     * signals (GOAWAY per section 5.2, stream reset per section 8).
     */
    private void pollEvents() {
        long quicheConn = quicConnection.getConnPtr();

        while (true) {
            long[] event = GumdropNative.quiche_h3_conn_poll(
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
                GumdropNative.quiche_h3_event_headers(h3Conn);
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
            int len = GumdropNative.quiche_h3_recv_body(
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

    // RFC 9114 section 5.2: GOAWAY for graceful shutdown. The server
    // sends GOAWAY with the stream ID of the last request it will
    // process; the client should not send new requests and may retry
    // unprocessed requests on a new connection.
    private void onGoaway(long lastStreamId) {
        goaway = true;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("GOAWAY received, last stream: " + lastStreamId);
        }

        // RFC 9114 section 5.2: fail all streams with IDs above
        // the server's last-stream-ID so the caller can retry them
        // on a new connection
        java.io.IOException retryable = new java.io.IOException(
                "Server GOAWAY: stream not processed (retryable)");
        for (java.util.Iterator<java.util.Map.Entry<Long, H3ClientStream>> it =
                     streams.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<Long, H3ClientStream> entry = it.next();
            if (entry.getKey().longValue() > lastStreamId) {
                entry.getValue().onGoawayFailed(retryable);
                it.remove();
            }
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
     * Drains buffered request body data on all streams that were
     * blocked by QUIC flow control. Called from
     * {@link #onConnectionReady()} after processing incoming packets
     * (which may carry ACKs that open the congestion window).
     */
    private void resumePendingWrites() {
        long quicheConn = quicConnection.getConnPtr();

        for (Iterator<Map.Entry<Long, PendingWrite>> it =
                     pendingWrites.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<Long, PendingWrite> entry = it.next();
            long streamId = entry.getKey().longValue();
            PendingWrite pw = entry.getValue();

            while (!pw.buffers.isEmpty()) {
                ByteBuffer buf = pw.buffers.get(0);
                while (buf.hasRemaining()) {
                    int result = GumdropNative.quiche_h3_send_body(
                            h3Conn, quicheConn, streamId,
                            buf, buf.remaining(), false);
                    if (result > 0) {
                        buf.position(buf.position() + result);
                        flushQuic();
                    } else if (result == GumdropNative.QUICHE_ERR_DONE) {
                        flushQuic();
                        return;
                    } else {
                        LOGGER.warning("h3 send_body error: " + result
                                + " stream=" + streamId);
                        pw.buffers.clear();
                        pw.fin = false;
                        it.remove();
                        return;
                    }
                }
                pw.buffers.remove(0);
            }

            if (pw.fin) {
                int result = GumdropNative.quiche_h3_send_body(
                        h3Conn, quicheConn, streamId,
                        ByteBuffer.allocate(0), 0, true);
                if (result < 0
                        && result != GumdropNative.QUICHE_ERR_DONE) {
                    LOGGER.warning("h3 send_body FIN error: " + result
                            + " stream=" + streamId);
                }
                flushQuic();
            }
            it.remove();
        }
    }

    /**
     * Tracks buffered request body data for a stream that is blocked
     * by QUIC flow control.
     */
    private static class PendingWrite {
        final List<ByteBuffer> buffers = new ArrayList<ByteBuffer>();
        boolean fin;

        void enqueue(ByteBuffer data, boolean fin) {
            if (data.hasRemaining()) {
                ByteBuffer copy = ByteBuffer.allocate(data.remaining());
                copy.put(data);
                copy.flip();
                buffers.add(copy);
            }
            if (fin) {
                this.fin = true;
            }
        }
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
            GumdropNative.quiche_h3_conn_free(h3Conn);
            h3Conn = 0;
        }
        if (h3Config != 0) {
            GumdropNative.quiche_h3_config_free(h3Config);
            h3Config = 0;
        }
    }

}
