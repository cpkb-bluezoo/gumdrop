/*
 * UDPEndpoint.java
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

package org.bluezoo.gumdrop;

import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * UDP transport implementation of {@link Endpoint}.
 *
 * <p>Handles both server-side and client-side UDP communication in a
 * single class, delegating all application events to an
 * {@link ProtocolHandler}.
 *
 * <p>A UDPEndpoint can operate in two modes:
 * <ul>
 * <li><strong>Server mode</strong> -- bound to a local port, receives
 *     datagrams from any source. Each datagram is delivered to the
 *     handler via {@link ProtocolHandler#receive(ByteBuffer)}.</li>
 * <li><strong>Client mode</strong> -- connected to a specific remote
 *     address. All sends go to that address and receives come only
 *     from that address.</li>
 * </ul>
 *
 * <p>For DTLS, encryption and decryption are handled transparently
 * using JSSE SSLEngine, just as TLS is handled transparently for TCP.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Endpoint
 * @see UDPTransportFactory
 */
public class UDPEndpoint implements Endpoint, ChannelHandler {

    private static final Logger LOGGER =
            Logger.getLogger(UDPEndpoint.class.getName());

    private static final int DEFAULT_BUFFER_SIZE = 65535;

    private final ProtocolHandler handler;
    private TransportFactory factory;

    private DatagramChannel channel;
    private SelectionKey key;
    private SelectorLoop selectorLoop;
    private boolean clientMode;

    // Remote address (for client mode)
    private InetSocketAddress remoteAddress;

    // Network I/O
    ByteBuffer netIn;
    final Deque<PendingDatagram> pendingDatagrams =
            new ConcurrentLinkedDeque<PendingDatagram>();

    private boolean secure;
    private volatile boolean closing;

    private Trace trace;

    /**
     * A pending datagram waiting to be sent.
     */
    static final class PendingDatagram {
        final ByteBuffer data;
        final InetSocketAddress destination;

        PendingDatagram(ByteBuffer data, InetSocketAddress destination) {
            this.data = data;
            this.destination = destination;
        }
    }

    /**
     * Creates a UDPEndpoint.
     *
     * @param handler the protocol handler
     */
    public UDPEndpoint(ProtocolHandler handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
    }

    // -- Setup (called by UDPTransportFactory) --

    void setFactory(TransportFactory factory) {
        this.factory = factory;
    }

    void setChannel(DatagramChannel channel) {
        this.channel = channel;
    }

    void setSecure(boolean secure) {
        this.secure = secure;
    }

    void setClientMode(boolean clientMode) {
        this.clientMode = clientMode;
    }

    void setRemoteAddress(InetSocketAddress address) {
        this.remoteAddress = address;
    }

    void init() {
        netIn = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
    }

    // -- Endpoint implementation --

    @Override
    public void send(ByteBuffer data) {
        if (data == null) {
            close();
            return;
        }
        InetSocketAddress dest = remoteAddress;
        if (dest == null && !clientMode) {
            throw new IllegalStateException(
                    "Server-mode datagram requires explicit destination");
        }
        sendTo(data, dest);
    }

    /**
     * Sends a datagram to a specific destination (server mode).
     *
     * @param data the datagram payload
     * @param dest the destination address
     */
    public void sendTo(ByteBuffer data, InetSocketAddress dest) {
        ByteBuffer copy = ByteBuffer.allocate(data.remaining());
        copy.put(data);
        copy.flip();
        pendingDatagrams.add(new PendingDatagram(copy, dest));

        if (selectorLoop != null) {
            selectorLoop.requestDatagramWrite(this);
        }
    }

    @Override
    public boolean isOpen() {
        return channel != null && channel.isOpen() && !closing;
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

        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Error closing datagram channel", e);
            }
        }
        if (key != null) {
            key.cancel();
        }

        Gumdrop gumdrop = Gumdrop.getInstance();
        if (gumdrop != null) {
            gumdrop.removeChannelHandler(this);
        }

        handler.disconnected();
    }

    @Override
    public SocketAddress getLocalAddress() {
        if (channel == null) {
            return new InetSocketAddress("localhost", 0);
        }
        try {
            return channel.getLocalAddress();
        } catch (IOException e) {
            return new InetSocketAddress("localhost", 0);
        }
    }

    @Override
    public SocketAddress getRemoteAddress() {
        if (remoteAddress != null) {
            return remoteAddress;
        }
        return new InetSocketAddress("unknown", 0);
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public SecurityInfo getSecurityInfo() {
        // DTLS security info would go here when DTLS is implemented
        return NullSecurityInfo.INSTANCE;
    }

    @Override
    public void startTLS() throws IOException {
        throw new UnsupportedOperationException(
                "STARTTLS not supported on datagram endpoints");
    }

    @Override
    public SelectorLoop getSelectorLoop() {
        return selectorLoop;
    }

    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return Gumdrop.getInstance().scheduleTimer(this, delayMs, callback);
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
        return factory != null && factory.isTelemetryEnabled();
    }

    @Override
    public TelemetryConfig getTelemetryConfig() {
        return factory != null ? factory.getTelemetryConfig() : null;
    }

    // -- ChannelHandler implementation --

    @Override
    public Type getChannelType() {
        return Type.DATAGRAM_SERVER;
    }

    @Override
    public SelectionKey getSelectionKey() {
        return key;
    }

    @Override
    public void setSelectionKey(SelectionKey key) {
        this.key = key;
    }

    @Override
    public void setSelectorLoop(SelectorLoop loop) {
        this.selectorLoop = loop;
    }

    // -- Package-private methods called by SelectorLoop --

    /**
     * Called by the SelectorLoop when a datagram is received.
     */
    void netReceive(ByteBuffer data, InetSocketAddress source) {
        if (clientMode) {
            handler.receive(data);
        } else {
            // For server mode, set the source so the handler can reply
            remoteAddress = source;
            handler.receive(data);
        }
    }

    /**
     * Returns the handler for this endpoint.
     */
    ProtocolHandler getHandler() {
        return handler;
    }
}
