/*
 * TCPEndpoint.java
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

import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.text.MessageFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * TCP transport implementation of {@link Endpoint}.
 *
 * <p>This class provides TCP connection management with optional TLS
 * (via JSSE SSLEngine). It delegates all application events to an
 * {@link ProtocolHandler} provided at construction time. Protocol
 * handlers never subclass this class.
 *
 * <p>Transparent TLS support:
 * <ul>
 * <li>{@link SSLState} intercepts inbound/outbound data automatically</li>
 * <li>The protocol handler's {@code receive()} always gets plaintext</li>
 * <li>The protocol handler's {@code send()} always accepts plaintext</li>
 * <li>STARTTLS is available via {@link #startTLS()}</li>
 * </ul>
 *
 * <p>All I/O and SSL processing occurs on the assigned SelectorLoop thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Endpoint
 * @see ProtocolHandler
 */
public class TCPEndpoint implements Endpoint, ChannelHandler, SSLState.Callback {

    private static final Logger LOGGER =
            Logger.getLogger(TCPEndpoint.class.getName());

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    // -- Transport state --

    private final ProtocolHandler handler;
    private TransportFactory factory;
    SocketChannel channel;
    private SelectionKey key;
    private SelectorLoop selectorLoop;

    // -- Network I/O buffers --

    ByteBuffer netIn;
    ByteBuffer netOut;
    final Object netOutLock = new Object();
    boolean closeRequested;

    private int bufferSize;

    // -- SSL state --

    private boolean secure;
    private final SSLEngine engine;
    private SSLState sslState;
    private long handshakeStartTime;

    // -- Lifecycle --

    private volatile boolean initialized;
    private volatile boolean closing;
    private boolean clientMode;

    // -- Telemetry --

    private Trace trace;

    // -- Timestamps --

    private long timestampCreated;
    private long timestampLastActivity;
    private long timestampConnected;

    /**
     * Creates a TCPEndpoint for a plaintext connection.
     *
     * @param handler the protocol handler
     */
    public TCPEndpoint(ProtocolHandler handler) {
        this(handler, null, false);
    }

    /**
     * Creates a TCPEndpoint with optional TLS.
     *
     * @param handler the protocol handler
     * @param engine the SSL engine, or null for plaintext only
     * @param secure true if TLS should be active immediately
     */
    public TCPEndpoint(ProtocolHandler handler, SSLEngine engine,
                       boolean secure) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        this.engine = engine;
        this.secure = secure;
        this.timestampCreated = System.currentTimeMillis();
        this.timestampLastActivity = this.timestampCreated;
    }

    // -- Initialization (called by TCPTransportFactory) --

    /**
     * Sets the transport factory that created this endpoint.
     */
    void setFactory(TransportFactory factory) {
        this.factory = factory;
    }

    /**
     * Sets the underlying socket channel.
     */
    void setChannel(SocketChannel channel) {
        this.channel = channel;
    }

    /**
     * Sets whether this is a client-initiated endpoint.
     */
    void setClientMode(boolean clientMode) {
        this.clientMode = clientMode;
    }

    /**
     * Initialises the endpoint after the channel has been set.
     */
    void init() throws IOException {
        if (channel == null) {
            bufferSize = DEFAULT_BUFFER_SIZE;
            netIn = ByteBuffer.allocate(bufferSize);
            netOut = ByteBuffer.allocate(bufferSize);
            initialized = true;
            timestampConnected = System.currentTimeMillis();
            return;
        }

        Socket socket = channel.socket();
        socket.setTcpNoDelay(true);

        if (engine == null || !secure) {
            bufferSize = Math.max(DEFAULT_BUFFER_SIZE,
                    socket.getReceiveBufferSize());
            timestampConnected = System.currentTimeMillis();
        }

        if (engine != null && secure) {
            handshakeStartTime = System.currentTimeMillis();
            sslState = new SSLState(engine, this);
            bufferSize = sslState.getBufferSize();
        }

        netIn = ByteBuffer.allocate(bufferSize);
        netOut = ByteBuffer.allocate(bufferSize);

        initialized = true;
        updateLastActivity();
    }

    // -- Endpoint implementation --

    @Override
    public void send(ByteBuffer data) {
        if (data == null) {
            close();
            return;
        }
        if (channel != null && !channel.isOpen()) {
            if (LOGGER.isLoggable(Level.FINE)) {
                String message = Gumdrop.L10N.getString("err.channel_closed");
                message = MessageFormat.format(message, channel);
                LOGGER.fine(message);
            }
            return;
        }
        updateLastActivity();
        if (sslState != null) {
            sslState.wrap(data);
        } else {
            appendToNetOut(data);
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
        closeRequested = true;
        if (sslState != null) {
            sslState.closeOutbound();
        }
        if (selectorLoop != null) {
            selectorLoop.requestWrite(this);
        }
    }

    @Override
    public SocketAddress getLocalAddress() {
        if (channel == null) {
            return new java.net.InetSocketAddress("localhost", 0);
        }
        return channel.socket().getLocalSocketAddress();
    }

    @Override
    public SocketAddress getRemoteAddress() {
        if (channel == null) {
            return new java.net.InetSocketAddress("unknown", 0);
        }
        return channel.socket().getRemoteSocketAddress();
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public SecurityInfo getSecurityInfo() {
        if (!secure || engine == null) {
            return NullSecurityInfo.INSTANCE;
        }
        return new JSSESecurityInfo(engine, handshakeStartTime);
    }

    @Override
    public void startTLS() throws IOException {
        if (engine == null) {
            throw new IOException("No SSL engine available for STARTTLS");
        }
        if (sslState != null) {
            throw new IOException("SSL state already initialised");
        }
        if (secure) {
            throw new IOException("Endpoint is already secure");
        }
        secure = true;
        handshakeStartTime = System.currentTimeMillis();
        sslState = new SSLState(engine, this);
        bufferSize = sslState.getBufferSize();
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
        return Type.TCP;
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
        if (loop != null && netOut != null && netOut.position() > 0) {
            loop.requestWrite(this);
        }
    }

    // -- Package-private methods called by SelectorLoop --

    ByteBuffer getNetOut() {
        return netOut;
    }

    boolean hasPendingWrite() {
        return (netOut != null && netOut.position() > 0) || closeRequested;
    }

    /**
     * Called by SelectorLoop after copying data into netIn.
     */
    final void processInbound() {
        updateLastActivity();
        if (sslState != null) {
            sslState.unwrap();
        } else {
            if (LOGGER.isLoggable(Level.FINEST)) {
                String message = Gumdrop.L10N.getString("info.received_plaintext");
                message = MessageFormat.format(message,
                        netIn.remaining(),
                        channel.socket().getRemoteSocketAddress());
                LOGGER.finest(message);
            }
            handler.receive(netIn);
            netIn.compact();
        }
    }

    /**
     * Appends data to the netIn buffer, growing if necessary.
     */
    final void appendToNetIn(ByteBuffer data) {
        int needed = data.remaining();
        int available = netIn.remaining();

        if (needed > available) {
            int newSize = netIn.position() + needed + DEFAULT_BUFFER_SIZE;
            int maxSize = factory != null ? factory.getMaxNetInSize() : 0;
            if (maxSize > 0 && newSize > maxSize) {
                throw new BufferOverflowException();
            }
            ByteBuffer newBuf = ByteBuffer.allocate(newSize);
            netIn.flip();
            newBuf.put(netIn);
            netIn = newBuf;
        }
        netIn.put(data);
    }

    final void appendToNetOut(ByteBuffer data) {
        synchronized (netOutLock) {
            int needed = data.remaining();
            int available = netOut.remaining();

            if (needed > available) {
                int newSize = netOut.position() + needed + DEFAULT_BUFFER_SIZE;
                ByteBuffer newBuf = ByteBuffer.allocate(newSize);
                netOut.flip();
                newBuf.put(netOut);
                netOut = newBuf;
            }
            netOut.put(data);
        }
        if (selectorLoop != null) {
            selectorLoop.requestWrite(this);
        }
    }

    void handleEOF() {
        try {
            handler.disconnected();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error in disconnected handler", e);
            if (trace != null && trace.getRootSpan() != null) {
                trace.getRootSpan().recordException(e);
            }
        } finally {
            try {
                endTrace();
            } finally {
                doClose();
            }
        }
    }

    void handleReadError(IOException e) {
        try {
            String msg = e.getMessage();
            ErrorCategory category = ErrorCategory.IO_ERROR;
            if (msg != null && (msg.contains("reset") ||
                    msg.contains("Broken pipe"))) {
                category = ErrorCategory.CONNECTION_LOST;
                if (LOGGER.isLoggable(Level.FINE)) {
                    Object sa = channel.socket().getRemoteSocketAddress();
                    LOGGER.fine("Client disconnected: " + sa);
                }
            } else if (LOGGER.isLoggable(Level.WARNING)) {
                Object sa = channel.socket().getRemoteSocketAddress();
                String message = Gumdrop.L10N.getString("err.read");
                message = MessageFormat.format(message, sa);
                LOGGER.log(Level.WARNING, message, e);
            }
            if (trace != null && trace.getRootSpan() != null) {
                trace.getRootSpan().recordException(e, category);
            }
            handler.error(e);
        } finally {
            try {
                endTrace();
            } finally {
                doClose();
            }
        }
    }

    void handleWriteError(IOException e) {
        try {
            String msg = e.getMessage();
            ErrorCategory category = ErrorCategory.IO_ERROR;
            if (msg != null && (msg.contains("Broken pipe") ||
                    msg.contains("reset"))) {
                category = ErrorCategory.CONNECTION_LOST;
                if (LOGGER.isLoggable(Level.FINE)) {
                    Object sa = channel.socket().getRemoteSocketAddress();
                    LOGGER.fine("Client disconnected: " + sa);
                }
            } else if (LOGGER.isLoggable(Level.WARNING)) {
                Object sa = channel.socket().getRemoteSocketAddress();
                String message = Gumdrop.L10N.getString("err.write");
                message = MessageFormat.format(message, sa);
                LOGGER.log(Level.WARNING, message, e);
            }
            if (trace != null && trace.getRootSpan() != null) {
                trace.getRootSpan().recordException(e, category);
            }
            handler.error(e);
        } finally {
            try {
                endTrace();
            } finally {
                doClose();
            }
        }
    }

    void handleConnectError(IOException e) {
        try {
            if (trace != null && trace.getRootSpan() != null) {
                ErrorCategory category = ErrorCategory.fromException(e);
                if (category == ErrorCategory.INTERNAL_ERROR ||
                        category == ErrorCategory.UNKNOWN) {
                    category = ErrorCategory.CONNECTION_ERROR;
                }
                trace.getRootSpan().recordException(e, category);
            }
            handler.error(e);
        } finally {
            try {
                endTrace();
            } finally {
                doClose();
            }
        }
    }

    /**
     * Called by SelectorLoop when OP_CONNECT completes.
     */
    void connected() {
        handler.connected(this);
    }

    /**
     * Initiates the TLS handshake for client connections.
     */
    void initiateClientTLSHandshake() {
        if (sslState != null && clientMode) {
            sslState.startClientHandshake();
        }
    }

    void doClose() {
        endTrace();
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
        }
        if (key != null) {
            key.cancel();
        }
        if (clientMode) {
            Gumdrop gumdrop = Gumdrop.getInstance();
            if (gumdrop != null) {
                gumdrop.removeChannelHandler(this);
            }
        }
    }

    private void endTrace() {
        if (trace != null) {
            trace.end();
        }
    }

    private void updateLastActivity() {
        timestampLastActivity = System.currentTimeMillis();
    }

    // -- SSLState.Callback implementation --

    @Override
    public final void onApplicationData(ByteBuffer data) {
        handler.receive(data);
    }

    @Override
    public final void onHandshakeComplete(String protocol) {
        if (timestampConnected == 0) {
            timestampConnected = System.currentTimeMillis();
        }
        SecurityInfo info = new JSSESecurityInfo(engine, handshakeStartTime);
        handler.securityEstablished(info);
    }

    @Override
    public final void onClosed() {
        handler.disconnected();
        doClose();
    }

    // -- Timestamps --

    /**
     * Returns the creation timestamp.
     *
     * @return milliseconds since epoch
     */
    public long getTimestampCreated() {
        return timestampCreated;
    }

    /**
     * Returns the last activity timestamp.
     *
     * @return milliseconds since epoch
     */
    public long getTimestampLastActivity() {
        return timestampLastActivity;
    }

    /**
     * Returns the idle time in milliseconds.
     *
     * @return milliseconds since last activity
     */
    public long getIdleTimeMs() {
        return System.currentTimeMillis() - timestampLastActivity;
    }
}
