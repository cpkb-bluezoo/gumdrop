/*
 * QuicStreamEndpoint.java
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

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * A single QUIC stream, exposed as a transport-agnostic {@link Endpoint}
 * (always secure -- RFC 9001 section 4.1 mandates TLS 1.3 for all of
 * QUIC).
 *
 * <p>All real I/O and flow-control accounting is owned by the
 * connection: {@link #send} just hands data to
 * {@link QuicConnection#queueStreamData}, which buffers it unconditionally
 * (matching how {@code TCPEndpoint} buffers unboundedly, relying on
 * {@link #onWriteReady} as an advisory pacing signal rather than a hard
 * backpressure block) and drains as much as the current flow-control
 * window and congestion window allow on each
 * {@link QuicConnection#flush()}. This is also where the flow-control
 * buffering HTTP/3's old quiche-backed implementation used to duplicate
 * per-protocol now lives instead, shared by every protocol running over
 * QUIC.
 *
 * <p>Received STREAM data is delivered to the handler in arrival order
 * with no reordering/reassembly for out-of-order offsets -- the same
 * accepted simplification {@link org.bluezoo.gumdrop.quic.tls.CryptoStreamBuffer}
 * already has for CRYPTO data.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QuicStreamEndpoint implements Endpoint {

    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0).asReadOnlyBuffer();

    private final QuicConnection connection;
    private final long streamId;
    private final ProtocolHandler handler;

    private volatile boolean open = true;
    private volatile boolean closing;
    private volatile boolean peerFinished;
    private boolean readPaused;
    private Runnable writeReadyCallback;
    private Trace trace;

    QuicStreamEndpoint(QuicConnection connection, long streamId, ProtocolHandler handler) {
        this.connection = connection;
        this.streamId = streamId;
        this.handler = handler;
    }

    /**
     * Returns this stream's QUIC stream ID (RFC 9000 section 2.1).
     */
    public long getStreamId() {
        return streamId;
    }

    ProtocolHandler getHandler() {
        return handler;
    }

    boolean isReadPaused() {
        return readPaused;
    }

    /**
     * Marks this stream closed without sending anything further -- called
     * by {@link QuicConnection} on RESET_STREAM or connection teardown.
     * Unlike {@link #markPeerFinished}, this disables {@link #send}, since
     * both of these cases mean nothing further will ever go out on this
     * stream either.
     */
    void markClosed() {
        open = false;
        closing = true;
    }

    /**
     * Marks the peer's send direction finished (a QUIC STREAM frame with
     * FIN was received) -- called by {@link QuicConnection} before
     * notifying the handler via {@link ProtocolHandler#disconnected()}.
     *
     * <p>Deliberately does <em>not</em> touch {@link #open}: RFC 9000's
     * bidirectional streams have independent send/receive directions, so
     * the peer finishing their side (e.g. a client's request + FIN) must
     * not prevent this side from still sending its own response before
     * closing in turn -- unlike {@link #markClosed}, which is for cases
     * where nothing further will go out either.
     */
    void markPeerFinished() {
        peerFinished = true;
    }

    /**
     * Returns true once both directions of this stream are finished: the
     * peer's FIN has been received and this side has sent its own FIN (or
     * been reset) -- the point at which {@link QuicConnection} can safely
     * forget this stream.
     */
    boolean isFullyClosed() {
        return peerFinished && !open;
    }

    /**
     * Delivers received stream data to the handler, unless
     * {@link #pauseRead} is in effect (in which case it is silently
     * dropped -- QUIC's own transport-level flow control, left
     * unacknowledged while paused, is relied on to hold the peer back).
     *
     * @param data the received data (a slice -- not retained)
     */
    void deliverData(ByteBuffer data) {
        if (readPaused) {
            return;
        }
        handler.receive(data);
    }

    /**
     * Called by {@link QuicConnection} once every byte queued via
     * {@link #send} so far has actually been included in a sent packet
     * -- fires and clears the one-shot {@link #onWriteReady} callback,
     * if one is registered.
     */
    void notifyWriteReady() {
        Runnable callback = writeReadyCallback;
        writeReadyCallback = null;
        if (callback != null) {
            callback.run();
        }
    }

    // ── Endpoint ──

    @Override
    public void send(ByteBuffer data) {
        if (data == null) {
            close();
            return;
        }
        if (!open) {
            return;
        }
        connection.queueStreamData(streamId, data, false);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isClosing() {
        return closing;
    }

    @Override
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        open = false;
        connection.queueStreamData(streamId, EMPTY_BUFFER, true);
        connection.retireStreamIfFullyClosed(streamId, this);
    }

    /**
     * Abruptly terminates this stream's sending part (RESET_STREAM, RFC
     * 9000 section 19.4), e.g. for RFC 9250 section 4.3 DNS-over-QUIC
     * error signalling.
     *
     * @param errorCode the application protocol error code
     */
    public void resetStream(long errorCode) {
        if (closing) {
            return;
        }
        closing = true;
        open = false;
        connection.resetStream(streamId, errorCode);
    }

    @Override
    public SocketAddress getLocalAddress() {
        return connection.getLocalAddress();
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return connection.getRemoteAddress();
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
    public void startTLS() throws IOException {
        throw new UnsupportedOperationException("QUIC streams are always secure");
    }

    @Override
    public void pauseRead() {
        readPaused = true;
    }

    @Override
    public void resumeRead() {
        readPaused = false;
    }

    @Override
    public void onWriteReady(Runnable callback) {
        this.writeReadyCallback = callback;
    }

    @Override
    public SelectorLoop getSelectorLoop() {
        return connection.getEngine().getSelectorLoop();
    }

    @Override
    public void execute(Runnable task) {
        getSelectorLoop().invokeLater(task);
    }

    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return connection.getEngine().scheduleTimer(delayMs, callback);
    }

    @Override
    public Trace getTrace() {
        return trace;
    }

    @Override
    public void setTrace(Trace trace) {
        this.trace = trace;
    }

    @Override
    public boolean isTelemetryEnabled() {
        return connection.getEngine().isTelemetryEnabled();
    }

    @Override
    public TelemetryConfig getTelemetryConfig() {
        return connection.getEngine().getTelemetryConfig();
    }
}
