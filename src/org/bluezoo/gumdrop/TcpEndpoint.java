/*
 * TcpEndpoint.java
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
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.TlsProtocolError;
import org.bluezoo.gumdrop.util.DirectByteBufferPool;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.ResourceBundle;
/**
 * TCP transport implementation of {@link Endpoint}.
 *
 * <p>This class provides TCP connection management with optional TLS,
 * either TLS 1.3 (via the in-tree {@link org.bluezoo.gumdrop.tls.TlsRecordEngine})
 * or TLS 1.2 (via {@link org.bluezoo.gumdrop.tls.Tls12RecordEngine}) --
 * the version is a deployment-time choice
 * ({@link org.bluezoo.gumdrop.tls.TlsVersion}), fixed for the life of a
 * given listener/connection, not negotiated at runtime. It delegates all
 * application events to an {@link ProtocolHandler} provided at
 * construction time. Protocol handlers never subclass this class.
 *
 * <p>Transparent TLS support:
 * <ul>
 * <li>{@link TlsRecordState}/{@link Tls12RecordState} intercepts inbound/outbound data automatically</li>
 * <li>The protocol handler's {@code receive()} always gets plaintext</li>
 * <li>The protocol handler's {@code send()} always accepts plaintext</li>
 * <li>STARTTLS is available via {@link #startTLS()}</li>
 * </ul>
 *
 * <p>All I/O and TLS processing occurs on the assigned SelectorLoop thread.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Endpoint
 * @see ProtocolHandler
 */
public class TcpEndpoint implements Endpoint, ChannelHandler, TlsRecordState.Callback {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.L10N");

    private static final Logger LOGGER =
            Logger.getLogger(TcpEndpoint.class.getName());

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    // -- Transport state --

    private final ProtocolHandler handler;
    private TransportFactory factory;
    SocketChannel channel;
    private SelectionKey key;
    private SelectorLoop selectorLoop;

    // -- Admission accounting (server endpoints only) --
    // Set at accept time so the listener's connection counters and per-IP
    // rate limiter are released exactly once when this endpoint closes,
    // regardless of which close path fires.
    private Listener listener;
    private SocketAddress admissionAddress;
    private boolean admissionReleased;
    private final Object admissionLock = new Object();

    // -- Network I/O buffers --

    ByteBuffer netIn;
    /** Decrypted bytes the handler has not yet consumed (TLS only). */
    private ByteBuffer tlsPendingIn;
    ByteBuffer netOut;
    /**
     * Guards {@link #netOut} append, grow, socket write, and release.
     * Intentionally separate from {@link #tlsEngineLock} so
     * {@link SelectorLoop} can drain pending ciphertext to the socket
     * while record-layer decrypt/encrypt runs.
     */
    final Object netOutLock = new Object();

    /**
     * Guards TLS record-engine wrap/unwrap on this connection. The engine
     * is not safe for concurrent access; handshake offload still mutates
     * {@link org.bluezoo.gumdrop.tls.HandshakeEngine} on a crypto thread,
     * coordinated by {@link org.bluezoo.gumdrop.tls.HandshakeAsyncScheduler}.
     */
    final Object tlsEngineLock = new Object();
    boolean closeRequested;

    private boolean disconnectDelivered;

    // Write-completion callback for backpressure support.
    // Invoked on the SelectorLoop thread after netOut has been fully drained.
    private Runnable writeCompleteCallback;

    private boolean readPaused;

    private int bufferSize;

    // -- TLS state --

    private boolean secure;
    private final HandshakeConfig config;
    private final Tls12HandshakeConfig config12;
    private TlsRecordState tlsState;
    private Tls12RecordState tls12State;
    private long handshakeStartTime;

    // -- Transport-level establishment timeouts (server endpoints only) --
    // handshakeTimeout bounds TLS handshake completion; firstByteTimeout
    // bounds how long an established connection may stay silent before the
    // first inbound application data. Both defend every protocol against
    // slowloris / connect-and-stall file-descriptor exhaustion without the
    // protocol handler having to opt in.
    private TimerHandle handshakeTimeoutHandle;
    private TimerHandle firstByteTimeoutHandle;
    private final Runnable handshakeTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            onHandshakeTimeout();
        }
    };
    private final Runnable firstByteTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            onFirstByteTimeout();
        }
    };

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
     * Creates a TcpEndpoint for a plaintext connection.
     *
     * @param handler the protocol handler
     */
    public TcpEndpoint(ProtocolHandler handler) {
        this(handler, (HandshakeConfig) null, false);
    }

    /**
     * Creates a TcpEndpoint with optional TLS 1.3.
     *
     * @param handler the protocol handler
     * @param config this endpoint's TLS configuration, or null for
     *               plaintext only
     * @param secure true if TLS should be active immediately
     */
    public TcpEndpoint(ProtocolHandler handler, HandshakeConfig config,
                       boolean secure) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        this.config = config;
        this.config12 = null;
        this.secure = secure;
        this.timestampCreated = System.currentTimeMillis();
        this.timestampLastActivity = this.timestampCreated;
    }

    /**
     * Creates a TcpEndpoint with TLS 1.2.
     *
     * @param handler the protocol handler
     * @param config12 this endpoint's TLS 1.2 configuration
     * @param secure true if TLS should be active immediately
     */
    public TcpEndpoint(ProtocolHandler handler, Tls12HandshakeConfig config12,
                       boolean secure) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        this.config = null;
        this.config12 = config12;
        this.secure = secure;
        this.timestampCreated = System.currentTimeMillis();
        this.timestampLastActivity = this.timestampCreated;
    }

    // -- Initialization (called by TcpTransportFactory) --

    /**
     * Sets the transport factory that created this endpoint.
     */
    void setFactory(TransportFactory factory) {
        this.factory = factory;
    }

    /**
     * Returns the transport factory that created this endpoint, if any.
     *
     * @return the factory, or null
     */
    public TransportFactory getTransportFactory() {
        return factory;
    }

    /**
     * Sets the underlying socket channel.
     *
     * @param channel the socket channel
     */
    public void setChannel(SocketChannel channel) {
        this.channel = channel;
    }

    /**
     * Transfers the open socket channel to another {@link SelectorLoop}
     * registration without closing it. Used when a short-lived outbound
     * connect endpoint only establishes TCP (e.g. FTP active-mode data).
     *
     * @return the channel, or null if none was attached
     */
    public SocketChannel takeSocketChannelForHandoff() {
        SocketChannel ch = channel;
        if (ch == null) {
            return null;
        }
        channel = null;
        closing = true;
        if (key != null) {
            key.cancel();
            key = null;
        }
        cancelHandshakeTimeout();
        cancelFirstByteTimeout();
        releaseBuffers();
        if (clientMode && selectorLoop != null) {
            Gumdrop gumdrop = selectorLoop.getGumdrop();
            if (gumdrop != null) {
                gumdrop.removeChannelHandler(this);
            }
        }
        return ch;
    }

    /**
     * Sets whether this is a client-initiated endpoint.
     */
    void setClientMode(boolean clientMode) {
        this.clientMode = clientMode;
    }

    /**
     * Returns whether this endpoint initiated the TCP connection (client).
     */
    public boolean isClientMode() {
        return clientMode;
    }

    /**
     * When {@code gumdrop.integration.log.level} is {@code FINE} or finer,
     * client TLS close/send paths log extra detail for integration debugging.
     */
    public static boolean integrationTlsTraceEnabled() {
        String prop = System.getProperty("gumdrop.integration.log.level");
        if (prop == null || prop.isEmpty()) {
            return false;
        }
        try {
            return Level.parse(prop.trim().toUpperCase(Locale.ROOT)).intValue()
                    <= Level.FINE.intValue();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Logs a formatted TLS trace line for client connections during integration runs.
     */
    void logIntegrationClientTls(Level level, String pattern, Object... args) {
        if (!clientMode || !integrationTlsTraceEnabled()) {
            return;
        }
        if (LOGGER.isLoggable(level)) {
            LOGGER.log(level, MessageFormat.format(pattern, args));
        }
    }

    /**
     * Associates this server endpoint with the listener that accepted it, so
     * that {@link Listener#connectionClosed(SocketAddress)} is invoked exactly
     * once when the connection closes. The remote address is captured here
     * because it is no longer retrievable once the channel has been closed.
     *
     * @param listener the accepting listener
     * @param remoteAddress the remote address at accept time
     */
    void setListener(Listener listener, SocketAddress remoteAddress) {
        this.listener = listener;
        this.admissionAddress = remoteAddress;
    }

    /**
     * Initialises the endpoint after the channel has been set.
     *
     * @throws IOException if initialisation fails
     */
    public void init() throws IOException {
        if (channel == null) {
            bufferSize = DEFAULT_BUFFER_SIZE;
            netIn = DirectByteBufferPool.acquire(bufferSize);
            netOut = DirectByteBufferPool.acquire(bufferSize);
            initialized = true;
            timestampConnected = System.currentTimeMillis();
            return;
        }

        // channel.socket() has no legacy java.net.Socket view for a UNIX
        // domain socket channel (StandardProtocolFamily.UNIX) -- the JDK
        // throws UnsupportedOperationException rather than returning one,
        // since java.net.Socket is inherently an AF_INET/AF_INET6
        // abstraction. TCP_NODELAY (Nagle's algorithm) and a TCP receive
        // buffer size are both meaningless for a UNIX domain socket, so
        // skip this tuning entirely rather than trying to detect the
        // address family in advance -- the family isn't reliably knowable
        // here anyway for a not-yet-connected client-mode channel.
        Socket socket;
        try {
            socket = channel.socket();
            socket.setTcpNoDelay(true);
        } catch (UnsupportedOperationException e) {
            socket = null;
        }

        if ((config == null && config12 == null) || !secure) {
            bufferSize = (socket != null)
                    ? Math.max(DEFAULT_BUFFER_SIZE, socket.getReceiveBufferSize())
                    : DEFAULT_BUFFER_SIZE;
            timestampConnected = System.currentTimeMillis();
        }

        if (secure && config != null) {
            handshakeStartTime = System.currentTimeMillis();
            tlsState = new TlsRecordState(config, this, this);
            bufferSize = tlsState.getBufferSize();
        } else if (secure && config12 != null) {
            handshakeStartTime = System.currentTimeMillis();
            tls12State = new Tls12RecordState(config12, this, this);
            bufferSize = tls12State.getBufferSize();
        }

        netIn = DirectByteBufferPool.acquire(bufferSize);
        netOut = DirectByteBufferPool.acquire(bufferSize);

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
        if (tlsState != null) {
            tlsState.wrap(data);
        } else if (tls12State != null) {
            tls12State.wrap(data);
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
        logIntegrationClientTls(Level.INFO,
                "client TcpEndpoint.close netOutPending={0} remote={1}",
                pendingNetOutBytes(), getRemoteAddress());
        closing = true;
        closeRequested = true;
        if (tlsState != null) {
            tlsState.closeOutbound();
        } else if (tls12State != null) {
            tls12State.closeOutbound();
        }
        if (selectorLoop != null) {
            selectorLoop.requestWrite(this);
        }
    }

    @Override
    public void closeWhenOutboundIdle() {
        if (closing) {
            return;
        }
        logIntegrationClientTls(Level.INFO,
                "client TcpEndpoint.closeWhenOutboundIdle netOutPending={0} remote={1}",
                pendingNetOutBytes(), getRemoteAddress());
        onWriteReady(new Runnable() {
            @Override
            public void run() {
                if (!closing) {
                    close();
                }
            }
        });
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
        if (!secure) {
            return NullSecurityInfo.INSTANCE;
        }
        if (config != null) {
            return new HandshakeSecurityInfo(tlsState.getEngine(), config, handshakeStartTime);
        }
        if (config12 != null) {
            return new Tls12SecurityInfo(tls12State.getEngine(), config12, handshakeStartTime);
        }
        return NullSecurityInfo.INSTANCE;
    }

    @Override
    public void startTLS() throws IOException {
        if (config == null && config12 == null) {
            throw new IOException("No TLS configuration available for STARTTLS");
        }
        if (tlsState != null || tls12State != null) {
            throw new IOException("TLS state already initialised");
        }
        if (secure) {
            throw new IOException("Endpoint is already secure");
        }
        secure = true;
        handshakeStartTime = System.currentTimeMillis();
        if (config != null) {
            tlsState = new TlsRecordState(config, this, this);
            bufferSize = tlsState.getBufferSize();
        } else {
            tls12State = new Tls12RecordState(config12, this, this);
            bufferSize = tls12State.getBufferSize();
        }
        // For an in-band upgrade (STARTTLS/STLS) the client must drive the
        // handshake by emitting the ClientHello. At OP_CONNECT time
        // tlsState/tls12State did not yet exist, so SelectorLoop's
        // initiateClientTLSHandshake() was a no-op; kick it off now.
        // Servers wait for the ClientHello to arrive via the normal read path.
        if (clientMode) {
            if (tlsState != null) {
                tlsState.startClientHandshake();
            } else {
                tls12State.startClientHandshake();
            }
        }
        // Bound the in-band upgrade handshake the same way as implicit TLS.
        armHandshakeTimeout();
    }

    @Override
    public SelectorLoop getSelectorLoop() {
        return selectorLoop;
    }

    @Override
    public void execute(Runnable task) {
        selectorLoop.invokeLater(task);
    }

    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return selectorLoop.getTimer().schedule(this, delayMs, callback);
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

    // -- Flow control --

    @Override
    public void pauseRead() {
        if (readPaused) {
            return;
        }
        readPaused = true;
        if (selectorLoop != null) {
            selectorLoop.cancelRead(this);
        }
    }

    @Override
    public void resumeRead() {
        if (!readPaused) {
            return;
        }
        readPaused = false;
        if (selectorLoop != null) {
            selectorLoop.requestRead(this);
        }
    }

    @Override
    public void onWriteReady(Runnable callback) {
        this.writeCompleteCallback = callback;
    }

    /**
     * Returns whether reading is currently paused on this endpoint.
     *
     * @return true if pauseRead has been called without a matching resumeRead
     */
    public boolean isReadPaused() {
        return readPaused;
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

    private int pendingNetOutBytes() {
        synchronized (netOutLock) {
            return netOut != null ? netOut.position() : 0;
        }
    }

    /**
     * Sets the callback to be invoked when the write buffer has been
     * fully drained by the SelectorLoop.
     *
     * <p>The callback runs on the SelectorLoop thread, so it is safe to
     * perform further I/O operations from within it.  Only one callback
     * may be registered at a time; setting a new one replaces the
     * previous one.
     *
     * @param callback the callback, or null to clear
     */
    public void setWriteCompleteCallback(Runnable callback) {
        this.writeCompleteCallback = callback;
    }

    /**
     * Returns the current write-complete callback, or null if none is set.
     */
    public Runnable getWriteCompleteCallback() {
        return writeCompleteCallback;
    }

    /**
     * Called by SelectorLoop after copying data into netIn.
     */
    final void processInbound() {
        updateLastActivity();
        if (tlsState != null) {
            tlsState.unwrap();
        } else if (tls12State != null) {
            tls12State.unwrap();
        } else {
            // First plaintext bytes have arrived: the connection is no longer
            // silent, so release the first-byte establishment timeout.
            cancelFirstByteTimeout();
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
     * Prepares {@link #netIn} for a direct read from the socket channel,
     * avoiding an intermediate scratch buffer and the extra copy it implies.
     *
     * <p>The buffer is returned in write mode. When it is nearly full because
     * the handler has not yet consumed everything, it is grown (doubling,
     * bounded by the configured {@code maxNetInSize}) so a single read can
     * still pull a useful chunk. If the buffer is full and cannot grow within
     * that limit, an {@link IOException} is thrown so the connection is closed
     * gracefully instead of spinning on zero-length reads.
     *
     * @return {@link #netIn}, in write mode, with room to read into
     * @throws IOException if the input buffer would exceed its maximum size
     */
    final ByteBuffer prepareNetInForRead() throws IOException {
        if (netIn.remaining() >= DEFAULT_BUFFER_SIZE) {
            return netIn;
        }
        int maxSize = factory != null ? factory.getMaxNetInSize() : 0;
        int desiredCapacity = netIn.capacity() * 2;
        if (maxSize > 0 && desiredCapacity > maxSize) {
            desiredCapacity = maxSize;
        }
        if (desiredCapacity > netIn.capacity()) {
            ByteBuffer newBuf = DirectByteBufferPool.acquire(desiredCapacity);
            netIn.flip();
            newBuf.put(netIn);
            DirectByteBufferPool.release(netIn);
            netIn = newBuf;
        }
        if (netIn.remaining() == 0) {
            throw new IOException("Network input buffer exceeded maximum size ("
                    + maxSize + " bytes)");
        }
        return netIn;
    }

    final void appendToNetOut(ByteBuffer data) {
        boolean overflow = false;
        synchronized (netOutLock) {
            if (netOut == null) {
                // Connection is closing/closed; drop outbound data. The
                // buffer has already been released to the pool by doClose().
                return;
            }
            int needed = data.remaining();
            int available = netOut.remaining();

            if (needed > available) {
                int required = netOut.position() + needed;
                int cap = getMaxNetOutSize();
                if (cap > 0 && required > cap) {
                    // The peer is not draining and we would have to buffer more
                    // than the configured ceiling off-heap. Refuse and close
                    // rather than growing without bound toward OOM.
                    overflow = true;
                } else {
                    int desired = Math.max(netOut.capacity() * 2, required);
                    if (cap > 0 && desired > cap) {
                        desired = cap;
                    }
                    ByteBuffer newBuf = DirectByteBufferPool.acquire(desired);
                    netOut.flip();
                    newBuf.put(netOut);
                    DirectByteBufferPool.release(netOut);
                    netOut = newBuf;
                }
            }
            if (!overflow) {
                netOut.put(data);
            }
        }
        if (overflow) {
            handleNetOutOverflow();
            return;
        }
        if (selectorLoop != null) {
            selectorLoop.requestWrite(this);
        }
    }

    /**
     * Returns the configured maximum outbound buffer size, or 0 if unlimited.
     * Package-private so {@link TlsRecordState} can enforce the same
     * ceiling on the encrypted output path.
     */
    int getMaxNetOutSize() {
        return factory != null ? factory.getMaxNetOutSize() : 0;
    }

    /**
     * Handles an outbound-buffer overflow: the peer is not reading fast enough
     * to keep the pending write buffer under its ceiling. Logs and closes the
     * connection. Called outside {@link #netOutLock}.
     */
    private void handleNetOutOverflow() {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning(MessageFormat.format(
                    Gumdrop.L10N.getString("warn.outbound_buffer_overflow"),
                    getMaxNetOutSize(), getRemoteAddress()));
        }
        close();
    }

    void handleEOF() {
        try {
            deliverDisconnected();
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
                    LOGGER.fine(MessageFormat.format(L10N.getString("log.client_disconnected_0"), sa));
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
                    LOGGER.fine(MessageFormat.format(L10N.getString("log.client_disconnected_0_1"), sa));
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
     * Called by {@link SelectorLoop} when dispatching this endpoint's I/O
     * throws an unchecked exception. Surfaces the failure to the protocol
     * handler and tears the connection down so the worker loop can continue
     * serving other registrations.
     */
    void handleDispatchError(Exception e) {
        try {
            if (LOGGER.isLoggable(Level.WARNING)) {
                Object sa = channel != null
                        ? channel.socket().getRemoteSocketAddress() : null;
                LOGGER.log(Level.WARNING, MessageFormat.format(
                        L10N.getString("log.tcp_dispatch_error"), sa), e);
            }
            if (trace != null && trace.getRootSpan() != null) {
                trace.getRootSpan().recordException(e,
                        ErrorCategory.INTERNAL_ERROR);
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
        // Arm establishment timeouts for server endpoints. Secure endpoints
        // first bound the handshake; plaintext endpoints bound the wait for
        // the first inbound bytes. Client endpoints (listener == null) manage
        // their own timeouts.
        if (secure) {
            armHandshakeTimeout();
        } else {
            armFirstByteTimeout();
        }
        handler.connected(this);
    }

    private void armHandshakeTimeout() {
        if (listener == null) {
            return;
        }
        long t = listener.getConnectionTimeoutMs();
        if (t > 0 && handshakeTimeoutHandle == null) {
            handshakeTimeoutHandle = scheduleTimer(t, handshakeTimeoutRunnable);
        }
    }

    private void armFirstByteTimeout() {
        if (listener == null) {
            return;
        }
        long t = listener.getReadTimeoutMs();
        if (t > 0 && firstByteTimeoutHandle == null) {
            firstByteTimeoutHandle = scheduleTimer(t, firstByteTimeoutRunnable);
        }
    }

    private void cancelHandshakeTimeout() {
        TimerHandle h = handshakeTimeoutHandle;
        if (h != null) {
            handshakeTimeoutHandle = null;
            h.cancel();
        }
    }

    private void cancelFirstByteTimeout() {
        TimerHandle h = firstByteTimeoutHandle;
        if (h != null) {
            firstByteTimeoutHandle = null;
            h.cancel();
        }
    }

    private void onHandshakeTimeout() {
        if (closing) {
            return;
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("log.tls_handshake_timeout_after_0_ms_closing_1"), (listener != null ? listener.getConnectionTimeoutMs() : 0), getRemoteAddress()));
        }
        close();
    }

    private void onFirstByteTimeout() {
        if (closing) {
            return;
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("log.read_timeout_waiting_for_first_data_after_0_ms_c"), (listener != null ? listener.getReadTimeoutMs() : 0), getRemoteAddress()));
        }
        close();
    }

    /**
     * Initiates the TLS handshake for client connections.
     */
    void initiateClientTLSHandshake() {
        if (!clientMode) {
            return;
        }
        if (tlsState != null) {
            tlsState.startClientHandshake();
        } else if (tls12State != null) {
            tls12State.startClientHandshake();
        }
    }

    void deliverDisconnected() {
        if (disconnectDelivered) {
            return;
        }
        disconnectDelivered = true;
        try {
            handler.disconnected();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, L10N.getString("log.error_in_disconnected_handler"), e);
            if (trace != null && trace.getRootSpan() != null) {
                trace.getRootSpan().recordException(e);
            }
        }
    }

    void doClose() {
        deliverDisconnected();
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
        cancelHandshakeTimeout();
        cancelFirstByteTimeout();
        releaseBuffers();
        releaseAdmission();
        if (clientMode && selectorLoop != null) {
            Gumdrop gumdrop = selectorLoop.getGumdrop();
            if (gumdrop != null) {
                gumdrop.removeChannelHandler(this);
            }
        }
    }

    /**
     * Releases this endpoint's admission accounting on the accepting listener.
     * Idempotent and thread-safe: only the first call notifies the listener,
     * so the connection counters stay balanced even though {@link #doClose}
     * may run more than once via distinct error paths.
     */
    private void releaseAdmission() {
        Listener l;
        SocketAddress addr;
        synchronized (admissionLock) {
            if (admissionReleased || listener == null) {
                return;
            }
            admissionReleased = true;
            l = listener;
            addr = admissionAddress;
        }
        l.connectionClosed(addr);
    }

    /**
     * Returns the pooled direct network buffers. Idempotent: the fields are
     * nulled so a second close (which can happen via multiple error paths)
     * does not release a buffer twice. netOut is released under netOutLock so
     * it cannot race with a concurrent {@link #appendToNetOut} or
     * {@link TlsRecordState#wrap} on another thread.
     */
    private void releaseBuffers() {
        ByteBuffer in = netIn;
        if (in != null) {
            netIn = null;
            DirectByteBufferPool.release(in);
        }
        synchronized (netOutLock) {
            ByteBuffer out = netOut;
            if (out != null) {
                netOut = null;
                DirectByteBufferPool.release(out);
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

    // -- TlsRecordState.Callback implementation --

    @Override
    public final void onApplicationData(ByteBuffer data) {
        // First decrypted application data: release the post-handshake
        // first-byte establishment timeout.
        cancelFirstByteTimeout();
        ByteBuffer input = data;
        if (tlsPendingIn != null) {
            // Bytes the handler left unconsumed from the previous record
            // come first, as with plaintext reads (ProtocolHandler.receive).
            input = ByteBuffer.allocate(tlsPendingIn.remaining() + data.remaining());
            input.put(tlsPendingIn);
            input.put(data);
            input.flip();
            tlsPendingIn = null;
        }
        handler.receive(input);
        if (input.hasRemaining()) {
            int maxSize = factory != null ? factory.getMaxNetInSize() : 0;
            if (maxSize > 0 && input.remaining() > maxSize) {
                handler.error(new IOException("Application input buffer exceeded maximum size ("
                        + maxSize + " bytes)"));
                return;
            }
            tlsPendingIn = ByteBuffer.allocate(input.remaining());
            tlsPendingIn.put(input);
            tlsPendingIn.flip();
        }
    }

    @Override
    public final void onHandshakeComplete(String protocol) {
        if (timestampConnected == 0) {
            timestampConnected = System.currentTimeMillis();
        }
        // Handshake finished within its bound; now bound the wait for the
        // first application data over the established secure channel.
        cancelHandshakeTimeout();
        armFirstByteTimeout();
        SecurityInfo info = (tlsState != null)
                ? new HandshakeSecurityInfo(tlsState.getEngine(), config, handshakeStartTime)
                : new Tls12SecurityInfo(tls12State.getEngine(), config12, handshakeStartTime);
        handler.securityEstablished(info);
    }

    @Override
    public final void onClosed() {
        doClose();
    }

    @Override
    public final void onProtocolError(TlsProtocolError error) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    Gumdrop.L10N.getString("log.tls_protocol_error"),
                    getRemoteAddress(), error));
        }
        handler.error(new javax.net.ssl.SSLException(error.toString()));
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
