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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.util.ArrayList;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.http.Header;
import org.bluezoo.gumdrop.http.Headers;
import org.bluezoo.gumdrop.http.client.HTTPMethodSafety;
import org.bluezoo.gumdrop.http.client.HTTPResponseHandler;
import org.bluezoo.gumdrop.http.qpack.SimpleDecoder;
import org.bluezoo.gumdrop.http.qpack.SimpleEncoder;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicStreamEndpoint;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;
import org.bluezoo.gumdrop.websocket.WebSocketExtension;
import org.bluezoo.gumdrop.websocket.WebSocketHandshake;

/**
 * Client-side HTTP/3 handler for one QUIC connection.
 *
 * <p>HTTP/3 (RFC 9114) maps HTTP semantics onto QUIC (RFC 9000)
 * transport. The client negotiates "h3" via ALPN (RFC 9114 section 3.1)
 * during the QUIC handshake, then exchanges SETTINGS frames
 * (RFC 9114 section 7.2.4). This class opens the connection's control
 * stream and sends SETTINGS in its constructor, and registers via
 * {@link H3ControlStream} to receive the peer's control stream events.
 *
 * <p>This class provides
 * {@link #sendRequest(Headers, HTTPResponseHandler)} to initiate
 * HTTP/3 requests: each request opens a new bidirectional stream
 * handled by its own {@link H3ClientStream}, which owns its own
 * {@link H3Parser} and translates response frames into
 * {@link HTTPResponseHandler} callbacks directly -- unlike the previous
 * quiche-backed implementation, this class does not poll for events.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see H3ClientStream
 * @see QuicConnection
 */
public final class HTTP3ClientHandler implements H3ControlStream.Listener {

    private static final Logger LOGGER = Logger.getLogger(HTTP3ClientHandler.class.getName());

    private final QuicConnection quicConnection;
    private final SimpleEncoder qpackEncoder = new SimpleEncoder();
    private final SimpleDecoder qpackDecoder = new SimpleDecoder();

    private final Map<Long, H3ClientStream> streams = new HashMap<Long, H3ClientStream>();

    private boolean goaway;

    /** Callback invoked when the h3 connection is ready for requests. */
    private Runnable readyCallback;

    // Requests deferred because their method isn't 0-RTT-eligible and the
    // connection isn't yet established -- see isSafeToSendNow/deferUntilEstablished.
    // Only ever touched from the QuicConnection's own SelectorLoop thread
    // (both deferUntilEstablished and runDeferredRequests are always called
    // from within a h3Handler.execute(...) task), so no synchronization needed.
    private final List<Runnable> deferredRequests = new ArrayList<Runnable>();

    /**
     * Creates a new HTTP/3 client handler on top of an existing
     * QUIC connection.
     *
     * @param quicConnection the underlying QUIC connection
     */
    public HTTP3ClientHandler(QuicConnection quicConnection) {
        this.quicConnection = quicConnection;

        quicConnection.setUnidirectionalStreamAcceptHandler(new StreamAcceptHandler() {
            @Override
            public ProtocolHandler acceptStream(Endpoint stream) {
                return new H3ControlStream(quicConnection, HTTP3ClientHandler.this);
            }
        });
        openControlStream();

        if (readyCallback != null) {
            Runnable cb = readyCallback;
            readyCallback = null;
            cb.run();
        }
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
     * Dispatches a task to run on the underlying {@link QuicConnection}'s
     * own {@code SelectorLoop} thread -- the only thread that may safely
     * touch its state (see {@code QuicConnection}'s class documentation).
     * {@link H3Request} uses this so that application callers of
     * {@link org.bluezoo.gumdrop.http.client.HTTPRequest} on an arbitrary
     * thread don't race the connection's own I/O thread.
     *
     * @param task the task to run
     */
    void execute(Runnable task) {
        quicConnection.getSelectorLoop().invokeLater(task);
    }

    /**
     * Returns whether a request using the given method may be sent right
     * now -- either because the connection is already fully established
     * (RFC 9001 section 4.6.1: only established connections may send
     * arbitrary application data), or because the method is safe and
     * idempotent (RFC 9110 section 9.3.1/9.3.2/9.3.7/9.3.8) and so may
     * ride 0-RTT early data if it's available.
     *
     * <p>Must be called from the underlying {@code QuicConnection}'s own
     * {@code SelectorLoop} thread -- see {@link #execute}.
     *
     * @param method the HTTP method of the pending request
     * @return true if the request may be sent immediately
     */
    boolean isSafeToSendNow(String method) {
        return quicConnection.isEstablished() || HTTPMethodSafety.isEarlyDataEligible(method);
    }

    /**
     * Queues a task to run once the connection is fully established,
     * for a request whose method is not 0-RTT-eligible and that was not
     * safe to send immediately (see {@link #isSafeToSendNow}).
     *
     * <p>Must be called from the underlying {@code QuicConnection}'s own
     * {@code SelectorLoop} thread -- see {@link #execute}.
     *
     * @param task the deferred send task
     */
    void deferUntilEstablished(Runnable task) {
        deferredRequests.add(task);
    }

    /**
     * Runs and clears every task queued via {@link #deferUntilEstablished},
     * called once the connection is established.
     */
    public void runDeferredRequests() {
        List<Runnable> pending = new ArrayList<Runnable>(deferredRequests);
        deferredRequests.clear();
        for (Runnable task : pending) {
            task.run();
        }
    }

    /**
     * Returns whether this handler has received a GOAWAY frame.
     *
     * @return true if GOAWAY was received
     */
    public boolean isGoaway() {
        return goaway;
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
    public long sendRequest(Headers headers, HTTPResponseHandler handler, boolean fin) {
        if (goaway) {
            handler.failed(new IOException("Connection received GOAWAY"));
            return -1;
        }

        H3ClientStream clientStream = new H3ClientStream(qpackDecoder, handler);
        Endpoint endpoint = quicConnection.openStream(clientStream);
        long streamId = ((QuicStreamEndpoint) endpoint).getStreamId();
        streams.put(Long.valueOf(streamId), clientStream);

        ByteBuffer fieldSection = ByteBuffer.allocate(estimateFieldSectionCapacity(headers));
        qpackEncoder.encode(fieldSection, headers);
        fieldSection.flip();
        byte[] encoded = new byte[fieldSection.remaining()];
        fieldSection.get(encoded);

        ByteBuffer out = ByteBuffer.allocate(H3Writer.headersLength(encoded.length));
        H3Writer.writeHeaders(out, encoded);
        out.flip();
        endpoint.send(out);
        if (fin) {
            endpoint.close();
        }

        return streamId;
    }

    /**
     * Sends request body data on the specified stream (RFC 9114
     * section 4.1 -- DATA frames carry the message body).
     *
     * @param streamId the stream ID returned by
     *                 {@link #sendRequest(Headers, HTTPResponseHandler, boolean)}
     * @param data the body data
     * @param fin true if this is the last body data
     */
    public void sendRequestBody(long streamId, ByteBuffer data, boolean fin) {
        H3ClientStream clientStream = streams.get(Long.valueOf(streamId));
        if (clientStream == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Request body for unknown stream " + streamId);
            }
            return;
        }
        Endpoint endpoint = clientStream.getEndpoint();
        if (data.hasRemaining()) {
            ByteBuffer out = ByteBuffer.allocate(H3Writer.dataLength(data.remaining()));
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            H3Writer.writeData(out, bytes);
            out.flip();
            endpoint.send(out);
        }
        if (fin) {
            endpoint.close();
        }
    }

    /**
     * Initiates a WebSocket-over-H3 connection via Extended CONNECT
     * (RFC 9220 section 3): opens a new bidirectional stream, sends a
     * {@code CONNECT} request with {@code :protocol: websocket} and the
     * requested subprotocol/extensions, and leaves the stream open in
     * both directions for the WebSocket tunnel -- unlike
     * {@link #sendRequest}, this never closes the stream after sending
     * headers. Once the server responds with {@code 200}, {@code wsHandler}
     * starts receiving {@link org.bluezoo.gumdrop.websocket.WebSocketEventHandler}
     * callbacks; any other response, or a connection-level failure before
     * then, is reported via {@link org.bluezoo.gumdrop.websocket.WebSocketEventHandler#error}.
     *
     * @param authority the {@code :authority} pseudo-header value
     * @param path the {@code :path} pseudo-header value
     * @param subprotocol the WebSocket subprotocol to request, or null
     * @param extensions the extensions to offer, or null/empty for none
     * @param wsHandler the handler to receive WebSocket events
     * @return the stream ID, or -1 on failure
     */
    public long connectWebSocket(String authority, String path, String subprotocol,
            List<WebSocketExtension> extensions, WebSocketEventHandler wsHandler) {
        if (goaway) {
            wsHandler.error(new IOException("Connection received GOAWAY"));
            return -1;
        }

        H3ClientStream clientStream = H3ClientStream.forWebSocket(qpackDecoder, wsHandler, extensions);
        Endpoint endpoint = quicConnection.openStream(clientStream);
        long streamId = ((QuicStreamEndpoint) endpoint).getStreamId();
        streams.put(Long.valueOf(streamId), clientStream);

        Headers headers = new Headers();
        headers.add(new Header(":method", "CONNECT"));
        headers.add(new Header(":protocol", "websocket"));
        headers.add(new Header(":scheme", "https"));
        headers.add(new Header(":authority", authority));
        headers.add(new Header(":path", path));
        if (subprotocol != null && !subprotocol.isEmpty()) {
            headers.add(new Header("sec-websocket-protocol", subprotocol));
        }
        if (extensions != null && !extensions.isEmpty()) {
            headers.add(new Header("sec-websocket-extensions", WebSocketHandshake.formatOffers(extensions)));
        }

        ByteBuffer fieldSection = ByteBuffer.allocate(estimateFieldSectionCapacity(headers));
        qpackEncoder.encode(fieldSection, headers);
        fieldSection.flip();
        byte[] encoded = new byte[fieldSection.remaining()];
        fieldSection.get(encoded);

        ByteBuffer out = ByteBuffer.allocate(H3Writer.headersLength(encoded.length));
        H3Writer.writeHeaders(out, encoded);
        out.flip();
        endpoint.send(out);

        return streamId;
    }

    // ── H3ControlStream.Listener (peer's control stream) ──

    @Override
    public void settingsReceived(long[] settings) {
        // Nothing to react to yet: SETTINGS_QPACK_MAX_TABLE_CAPACITY
        // stays 0 on both sides this stage (static-table-only QPACK).
    }

    // RFC 9114 section 5.2: GOAWAY for graceful shutdown. The server
    // sends GOAWAY with the stream ID of the last request it will
    // process; the client should not send new requests and may retry
    // unprocessed requests on a new connection.
    @Override
    public void goawayReceived(long lastStreamId) {
        goaway = true;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("GOAWAY received, last stream: " + lastStreamId);
        }

        // RFC 9114 section 5.2: fail all streams with IDs above
        // the server's last-stream-ID so the caller can retry them
        // on a new connection
        IOException retryable = new IOException("Server GOAWAY: stream not processed (retryable)");
        for (Iterator<Map.Entry<Long, H3ClientStream>> it = streams.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, H3ClientStream> entry = it.next();
            if (entry.getKey().longValue() > lastStreamId) {
                entry.getValue().onGoawayFailed(retryable);
                it.remove();
            }
        }
    }

    private static int estimateFieldSectionCapacity(List<Header> fields) {
        int estimate = 16;
        for (Header field : fields) {
            estimate += 8 + 2 * (field.getName().length() + field.getValue().length());
        }
        return estimate;
    }

    /**
     * Closes this HTTP/3 client handler, failing any outstanding
     * requests. Actual QUIC connection teardown is the caller's
     * responsibility.
     */
    public void close() {
        IOException closed = new IOException("Connection closed");
        for (H3ClientStream stream : streams.values()) {
            stream.error(closed);
        }
        streams.clear();
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
