/*
 * QuicConnection.java
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

package org.bluezoo.gumdrop.quic;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.MultiplexedEndpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.TimerHandle;

/**
 * Represents a single QUIC connection containing multiple streams.
 *
 * <p>A QuicConnection wraps a native quiche connection handle and
 * manages the lifecycle of its streams. Each stream is exposed as a
 * {@link QuicStreamEndpoint} that protocol handlers interact with.
 *
 * <p>All quiche calls for this connection happen on the same SelectorLoop
 * thread (thread-safe because quiche connections are single-threaded).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicEngine
 * @see QuicStreamEndpoint
 */
public final class QuicConnection {

    private static final Logger LOGGER =
            Logger.getLogger(QuicConnection.class.getName());

    private final QuicEngine engine;
    private final long connPtr;
    private final long sslPtr;
    private final InetSocketAddress localAddress;
    private final InetSocketAddress remoteAddress;
    private final long handshakeStartTime;

    private final Map<Long, QuicStreamEndpoint> streams =
            new HashMap<Long, QuicStreamEndpoint>();

    private StreamAcceptHandler streamAcceptHandler;
    private ConnectionReadyHandler connectionReadyHandler;
    private QuicEngine.ConnectionAcceptedHandler
            clientConnectionAcceptedHandler;
    private ProtocolHandler clientHandler;
    private SecurityInfo securityInfo;
    private TimerHandle timerHandle;
    private boolean established;
    private boolean closed;

    QuicConnection(QuicEngine engine, long connPtr, long sslPtr,
                   InetSocketAddress localAddress,
                   InetSocketAddress remoteAddress) {
        this.engine = engine;
        this.connPtr = connPtr;
        this.sslPtr = sslPtr;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.handshakeStartTime = System.currentTimeMillis();
    }

    /**
     * Returns the native quiche connection pointer.
     * Used by the HTTP/3 module to create an h3 connection.
     */
    public long getConnPtr() {
        return connPtr;
    }

    /**
     * Returns the owning QuicEngine.
     */
    public QuicEngine getEngine() {
        return engine;
    }

    /**
     * Returns the local address of this connection.
     */
    public SocketAddress getLocalAddress() {
        return localAddress;
    }

    /**
     * Returns the remote address of this connection.
     */
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Returns security info for this connection.
     */
    public SecurityInfo getSecurityInfo() {
        if (securityInfo == null && established) {
            securityInfo = new QuicSecurityInfo(connPtr, sslPtr,
                    handshakeStartTime);
        }
        return securityInfo;
    }

    void setStreamAcceptHandler(StreamAcceptHandler handler) {
        this.streamAcceptHandler = handler;
    }

    /**
     * Sets a handler that is called when the client handshake completes,
     * providing the connection itself. Used by HTTP/3 to install a
     * {@link ConnectionReadyHandler} before any streams are opened.
     */
    void setClientConnectionAcceptedHandler(
            QuicEngine.ConnectionAcceptedHandler handler) {
        this.clientConnectionAcceptedHandler = handler;
    }

    /**
     * Sets the client-mode endpoint handler that is waiting for the
     * handshake to complete.
     *
     * <p>When the QUIC handshake finishes, a bidirectional stream is
     * automatically opened and the handler receives
     * {@link ProtocolHandler#connected(Endpoint)} followed by
     * {@link ProtocolHandler#securityEstablished(SecurityInfo)}.
     *
     * @param handler the handler for the initial client stream
     */
    void setClientHandler(ProtocolHandler handler) {
        this.clientHandler = handler;
    }

    /**
     * Handler interface for connection-level processing.
     *
     * <p>Used by HTTP/3 where the quiche h3 module processes streams
     * internally rather than exposing individual stream I/O.
     */
    public interface ConnectionReadyHandler {

        /**
         * Called after QUIC packets have been received on this connection.
         * The handler should poll for application-level events.
         */
        void onConnectionReady();
    }

    /**
     * Sets a connection-level handler that is called after packets
     * are received. When set, individual stream processing is bypassed
     * (used by HTTP/3).
     */
    public void setConnectionReadyHandler(ConnectionReadyHandler handler) {
        this.connectionReadyHandler = handler;
    }

    /**
     * Processes readable streams after a packet is fed to quiche.
     * Called by QuicEngine on the SelectorLoop thread.
     */
    /**
     * Checks whether the QUIC handshake has completed and, if so,
     * transitions the connection to the established state. Called
     * after flushing outgoing packets, since quiche may mark the
     * connection as established only after HANDSHAKE_DONE is queued.
     */
    void checkEstablished() {
        if (!established) {
            boolean nowEstablished =
                    QuicheNative.quiche_conn_is_established(connPtr);
            if (nowEstablished) {
                established = true;
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("QUIC connection established: "
                            + remoteAddress);
                }
                scheduleTimeout();
                notifyClientHandshakeComplete();
                if (connectionReadyHandler != null) {
                    connectionReadyHandler.onConnectionReady();
                    engine.flushConnection(this);
                }
            }
        }
    }

    void processReadableStreams(ByteBuffer streamBuf) {
        if (!established) {
            boolean nowEstablished =
                    QuicheNative.quiche_conn_is_established(connPtr);
            if (nowEstablished) {
                established = true;
                scheduleTimeout();
                notifyClientHandshakeComplete();
            }
        }

        // HTTP/3: delegate to the h3 connection handler if set
        if (connectionReadyHandler != null) {
            if (established) {
                connectionReadyHandler.onConnectionReady();
            }
            return;
        }

        long[] readableIds = QuicheNative.quiche_conn_readable(connPtr);
        for (int i = 0; i < readableIds.length; i++) {
            long streamId = readableIds[i];
            QuicStreamEndpoint stream = streams.get(Long.valueOf(streamId));

            if (stream == null) {
                stream = acceptStream(streamId);
                if (stream == null) {
                    continue;
                }
            }

            boolean[] fin = new boolean[1];
            streamBuf.clear();
            int len = QuicheNative.quiche_conn_stream_recv(
                    connPtr, streamId, streamBuf,
                    streamBuf.capacity(), fin);

            if (len > 0) {
                streamBuf.flip();
                stream.deliverData(streamBuf);
            }

            if (fin[0]) {
                stream.markClosed();
                stream.getHandler().disconnected();
                streams.remove(Long.valueOf(streamId));
            }
        }
    }

    /**
     * Called when the QUIC handshake completes in client mode.
     *
     * <p>If a {@code clientConnectionAcceptedHandler} is set (HTTP/3
     * mode), it is called with this connection so that the h3 handler
     * can be installed. Otherwise, a bidirectional stream is auto-opened
     * and the waiting {@code clientHandler} is notified.
     */
    private void notifyClientHandshakeComplete() {
        if (clientConnectionAcceptedHandler != null) {
            QuicEngine.ConnectionAcceptedHandler ch =
                    clientConnectionAcceptedHandler;
            clientConnectionAcceptedHandler = null;
            ch.connectionAccepted(this);
            return;
        }
        if (clientHandler == null) {
            return;
        }
        ProtocolHandler handler = clientHandler;
        clientHandler = null;
        openStream(handler);
    }

    /**
     * Accepts a new incoming stream from the peer.
     */
    private QuicStreamEndpoint acceptStream(long streamId) {
        if (streamAcceptHandler == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("No stream accept handler for stream "
                        + streamId);
            }
            return null;
        }

        QuicStreamEndpoint stream = new QuicStreamEndpoint(
                this, streamId, null);
        ProtocolHandler handler =
                streamAcceptHandler.acceptStream(stream);
        if (handler == null) {
            return null;
        }

        QuicStreamEndpoint realStream = new QuicStreamEndpoint(
                this, streamId, handler);
        streams.put(Long.valueOf(streamId), realStream);
        handler.connected(realStream);
        handler.securityEstablished(getSecurityInfo());
        return realStream;
    }

    /**
     * Opens a new outgoing stream.
     */
    Endpoint openStream(ProtocolHandler handler) {
        // Client-initiated bidi streams use IDs 0, 4, 8, ...
        // Server-initiated bidi streams use IDs 1, 5, 9, ...
        // For simplicity, use the next available even ID
        long streamId = findNextStreamId();
        QuicStreamEndpoint stream = new QuicStreamEndpoint(
                this, streamId, handler);
        streams.put(Long.valueOf(streamId), stream);
        handler.connected(stream);
        handler.securityEstablished(getSecurityInfo());
        return stream;
    }

    private long findNextStreamId() {
        long maxId = -1;
        for (Long id : streams.keySet()) {
            if (id.longValue() > maxId) {
                maxId = id.longValue();
            }
        }
        return maxId < 0 ? 0 : maxId + 4;
    }

    /**
     * Sends data on a stream.
     */
    void streamSend(long streamId, ByteBuffer data, boolean fin) {
        QuicheNative.quiche_conn_stream_send(connPtr, streamId,
                data, data.remaining(), fin);
        engine.requestFlush();
    }

    /**
     * Closes a stream.
     */
    void streamClose(long streamId) {
        ByteBuffer empty = ByteBuffer.allocate(0);
        QuicheNative.quiche_conn_stream_send(connPtr, streamId,
                empty, 0, true);
        streams.remove(Long.valueOf(streamId));
        engine.requestFlush();
    }

    /**
     * Schedules the quiche timeout timer.
     */
    void scheduleTimeout() {
        if (timerHandle != null) {
            timerHandle.cancel();
        }
        long timeoutMs =
                QuicheNative.quiche_conn_timeout_as_millis(connPtr);
        if (timeoutMs > 0) {
            timerHandle = engine.scheduleTimer(timeoutMs,
                    new Runnable() {
                        @Override
                        public void run() {
                            onTimeout();
                        }
                    });
        }
    }

    private void onTimeout() {
        if (closed) {
            return;
        }
        QuicheNative.quiche_conn_on_timeout(connPtr);
        engine.requestFlush();
        if (QuicheNative.quiche_conn_is_closed(connPtr)) {
            close();
        } else {
            scheduleTimeout();
        }
    }

    /**
     * Closes this connection and all its streams.
     */
    void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (timerHandle != null) {
            timerHandle.cancel();
        }

        for (QuicStreamEndpoint stream : streams.values()) {
            stream.markClosed();
            stream.getHandler().disconnected();
        }
        streams.clear();

        QuicheNative.quiche_conn_free(connPtr);
    }

    boolean isClosed() {
        return closed;
    }
}
