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
import org.bluezoo.gumdrop.NullSecurityInfo;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * Endpoint implementation for a single QUIC stream.
 *
 * <p>Protocol handlers interact with a QuicStreamEndpoint identically
 * to a TCPEndpoint: send/receive plaintext ByteBuffers. QUIC encryption
 * is handled transparently by quiche.
 *
 * <p>A QuicStreamEndpoint is always secure ({@link #isSecure()} returns
 * true) because QUIC mandates TLS 1.3.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Endpoint
 * @see QuicConnection
 */
final class QuicStreamEndpoint implements Endpoint {

    private final QuicConnection connection;
    private final long streamId;
    private final ProtocolHandler handler;
    private volatile boolean open = true;
    private volatile boolean closing;
    private Trace trace;

    QuicStreamEndpoint(QuicConnection connection, long streamId,
                       ProtocolHandler handler) {
        this.connection = connection;
        this.streamId = streamId;
        this.handler = handler;
    }

    /**
     * Returns the QUIC stream ID.
     */
    long getStreamId() {
        return streamId;
    }

    /**
     * Returns the handler for this stream.
     */
    ProtocolHandler getHandler() {
        return handler;
    }

    @Override
    public void send(ByteBuffer data) {
        if (data == null) {
            close();
            return;
        }
        if (!open) {
            return;
        }
        connection.streamSend(streamId, data, false);
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
        connection.streamClose(streamId);
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
        throw new UnsupportedOperationException(
                "QUIC streams are always secure");
    }

    @Override
    public SelectorLoop getSelectorLoop() {
        return connection.getEngine().getSelectorLoop();
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

    /**
     * Marks this stream as closed (called by QuicConnection).
     */
    void markClosed() {
        open = false;
        closing = true;
    }

    /**
     * Delivers received data to the handler.
     */
    void deliverData(ByteBuffer data) {
        handler.receive(data);
    }
}
