/*
 * Connection.java
 * Copyright (C) 2005, 2013, 2025 Chris Burdess
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
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Abstract base class for TCP protocol handlers.
 * All I/O and SSL processing occurs on the assigned SelectorLoop thread.
 *
 * <p>Connections are created by {@link Connector} subclasses (either
 * {@link Server} for inbound connections or {@link Client} for outbound
 * connections) and are registered with a {@link SelectorLoop} for
 * event-driven I/O processing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class Connection implements ChannelHandler {

    private static final Logger LOGGER = Logger.getLogger(Connection.class.getName());

    /**
     * Sentinel buffer indicating the connection should be closed after
     * all pending writes complete.
     */
    static final ByteBuffer CLOSE_SENTINEL = ByteBuffer.allocate(0);

    // Key size parsing from cipher suites
    private static final Map<String,Integer> KNOWN_KEY_SIZES = new HashMap<String,Integer>();
    static {
        KNOWN_KEY_SIZES.put("3DES", Integer.valueOf(168));
        KNOWN_KEY_SIZES.put("CHACHA20", Integer.valueOf(256));
        KNOWN_KEY_SIZES.put("IDEA", Integer.valueOf(128));
    }

    protected Connector connector;
    protected SocketChannel channel;
    private SelectionKey key;
    private SelectorLoop selectorLoop;

    // Outbound data queue
    private final Deque<ByteBuffer> outboundQueue;

    protected int bufferSize;

    // SSL state
    protected boolean secure;
    protected X509Certificate[] certificates;
    protected String cipherSuite;
    protected int keySize = -1;
    protected final SSLEngine engine;
    private SSLState sslState;

    // Callback for testing and stream integration
    private SendCallback sendCallback;

    // Telemetry trace context (null if telemetry disabled)
    protected Trace trace;

    // Connection lifecycle timestamps (milliseconds since epoch)
    private long timestampCreated;
    private long timestampLastActivity;
    private long timestampConnected;

    // Lifecycle state
    private volatile boolean initialized;
    private volatile boolean closing;

    /**
     * Constructor for an unencrypted connection.
     */
    protected Connection() {
        this(null, false);
    }

    /**
     * Constructor for a connection with optional SSL.
     * If secure is true and engine is not null, SSL will be active immediately.
     * If secure is false but engine is not null, STARTTLS can be used later.
     *
     * @param engine the SSL engine, or null for plaintext only
     * @param secure true if SSL should be active immediately
     */
    protected Connection(SSLEngine engine, boolean secure) {
        this.engine = engine;
        this.secure = secure;
        this.outboundQueue = new ArrayDeque<ByteBuffer>();
        this.timestampCreated = System.currentTimeMillis();
        this.timestampLastActivity = this.timestampCreated;
    }

    /**
     * Indicates whether this connection is secure (TLS active).
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * Indicates whether this connection is open.
     *
     * @return true if the underlying channel is open, false otherwise
     */
    public boolean isOpen() {
        return channel != null && channel.isOpen() && !closing;
    }

    /**
     * Indicates whether this connection is currently closing.
     *
     * @return true if close has been initiated
     */
    public boolean isClosing() {
        return closing;
    }

    // -- Lifecycle Timestamps --

    /**
     * Returns the timestamp when this connection was created.
     *
     * @return creation timestamp in milliseconds since epoch
     */
    public long getTimestampCreated() {
        return timestampCreated;
    }

    /**
     * Returns the timestamp of the last activity on this connection.
     * Activity includes data received or sent.
     *
     * @return last activity timestamp in milliseconds since epoch
     */
    public long getTimestampLastActivity() {
        return timestampLastActivity;
    }

    /**
     * Returns the timestamp when this connection was fully established.
     * For secure connections, this is after TLS handshake completion.
     *
     * @return connected timestamp in milliseconds since epoch, or 0 if not yet connected
     */
    public long getTimestampConnected() {
        return timestampConnected;
    }

    /**
     * Returns the idle time in milliseconds.
     *
     * @return milliseconds since last activity
     */
    public long getIdleTimeMs() {
        return System.currentTimeMillis() - timestampLastActivity;
    }

    /**
     * Updates the last activity timestamp.
     * Called automatically on data receive/send.
     */
    protected void updateLastActivity() {
        timestampLastActivity = System.currentTimeMillis();
    }

    /**
     * Initializes the connection after the channel is set.
     */
    public void init() throws IOException {
        if (channel == null) {
            bufferSize = 4096;
            initialized = true;
            timestampConnected = System.currentTimeMillis();
            return;
        }

        Socket socket = channel.socket();
        socket.setTcpNoDelay(true);

        if (engine == null || !secure) {
            bufferSize = Math.max(4096, socket.getReceiveBufferSize());
            // For plaintext connections, we're connected immediately
            timestampConnected = System.currentTimeMillis();
        }

        // Initialize SSL state if connection starts secure
        if (engine != null && secure) {
            SSLSession session = engine.getSession();
            sslState = new SSLState(session);
            // timestampConnected will be set in handshakeComplete()
        }

        initialized = true;
        updateLastActivity();
    }

    /**
     * Initializes SSL state for STARTTLS upgrade.
     * The connection must have been created with an SSLEngine but secure=false.
     *
     * @throws IOException if SSL initialization fails
     */
    protected final void initializeSSLState() throws IOException {
        if (engine == null) {
            throw new IOException("Cannot initialize SSL state: no SSL engine available");
        }
        if (sslState != null) {
            throw new IOException("SSL state already initialized");
        }
        if (secure) {
            throw new IOException("Connection is already secure");
        }

        secure = true;
        SSLSession session = engine.getSession();
        sslState = new SSLState(session);
        bufferSize = Math.max(4096, channel.socket().getReceiveBufferSize());
    }

    // -- Package-private methods called by SelectorLoop --

    /**
     * Called by SelectorLoop when network data arrives.
     * Handles SSL unwrap if secure, then calls receive() with application data.
     */
    final void netReceive(ByteBuffer data) {
        updateLastActivity();
        if (sslState != null) {
            sslState.unwrap(data);
        } else {
            if (LOGGER.isLoggable(Level.FINEST)) {
                String message = Gumdrop.L10N.getString("info.received_plaintext");
                message = MessageFormat.format(message, data.remaining(),
                        channel.socket().getRemoteSocketAddress());
                LOGGER.finest(message);
            }
            receive(data);
        }
    }

    /**
     * Called by SelectorLoop when EOF is received (peer closed connection).
     * Uses try-finally to guarantee telemetry is recorded even if exceptions occur.
     */
    void handleEOF() {
        try {
            disconnected();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error in disconnected handler", e);
            // Record exception in telemetry if available
            if (trace != null && trace.getRootSpan() != null) {
                trace.getRootSpan().recordException(e);
            }
        } finally {
            // Guarantee telemetry is ended regardless of disconnected() outcome
            try {
                endTrace();
            } finally {
                doClose();
            }
        }
    }

    /**
     * Called by SelectorLoop when a read error occurs.
     * Records error in telemetry before closing the connection.
     */
    void handleReadError(IOException e) {
        try {
            // "Connection reset by peer" is normal when clients disconnect abruptly
            String msg = e.getMessage();
            ErrorCategory category = ErrorCategory.IO_ERROR;
            if (msg != null && (msg.contains("reset") || msg.contains("Broken pipe"))) {
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
            // Record read error in telemetry with category
            if (trace != null && trace.getRootSpan() != null) {
                trace.getRootSpan().recordException(e, category);
            }
        } finally {
            try {
                endTrace();
            } finally {
                doClose();
            }
        }
    }

    /**
     * Called by SelectorLoop when a write error occurs.
     * Records error in telemetry before closing the connection.
     */
    void handleWriteError(IOException e) {
        try {
            // "Broken pipe" is normal when clients disconnect before we finish writing
            String msg = e.getMessage();
            ErrorCategory category = ErrorCategory.IO_ERROR;
            if (msg != null && (msg.contains("Broken pipe") || msg.contains("reset"))) {
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
            // Record write error in telemetry with category
            if (trace != null && trace.getRootSpan() != null) {
                trace.getRootSpan().recordException(e, category);
            }
        } finally {
            try {
                endTrace();
            } finally {
                doClose();
            }
        }
    }

    /**
     * Called by SelectorLoop when a connect error occurs (client connections).
     * Records error in telemetry before closing the connection.
     */
    void handleConnectError(IOException e) {
        try {
            // Record connect error in telemetry with category
            if (trace != null && trace.getRootSpan() != null) {
                ErrorCategory category = ErrorCategory.fromException(e);
                if (category == ErrorCategory.INTERNAL_ERROR || 
                    category == ErrorCategory.UNKNOWN) {
                    category = ErrorCategory.CONNECTION_ERROR;
                }
                trace.getRootSpan().recordException(e, category);
            }
            finishConnectFailed(e);
        } finally {
            try {
                endTrace();
            } finally {
                doClose();
            }
        }
    }

    // -- ChannelHandler implementation --

    @Override
    public final Type getChannelType() {
        return Type.TCP;
    }

    @Override
    public void setSelectionKey(SelectionKey key) {
        this.key = key;
    }

    @Override
    public SelectionKey getSelectionKey() {
        return key;
    }

    @Override
    public void setSelectorLoop(SelectorLoop loop) {
        this.selectorLoop = loop;
        // If data was queued before registration (e.g., during init()), schedule write now
        if (loop != null && !outboundQueue.isEmpty()) {
            loop.requestWrite(this);
        }
    }

    @Override
    public SelectorLoop getSelectorLoop() {
        return selectorLoop;
    }

    Deque<ByteBuffer> getOutboundQueue() {
        return outboundQueue;
    }

    /**
     * Sets a callback to intercept send operations.
     * Used for testing and integration with streams that need to capture sent data.
     */
    public void setSendCallback(SendCallback callback) {
        this.sendCallback = callback;
    }

    /**
     * Returns the current send callback.
     */
    public SendCallback getSendCallback() {
        return sendCallback;
    }

    // -- Telemetry --

    /**
     * Returns the trace context for this connection.
     *
     * @return the trace, or null if telemetry is disabled
     */
    public Trace getTrace() {
        return trace;
    }

    /**
     * Sets the trace context for this connection.
     * This is typically called during connection initialization when
     * continuing a trace from a remote context.
     *
     * @param trace the trace context
     */
    public void setTrace(Trace trace) {
        this.trace = trace;
    }

    /**
     * Returns true if telemetry is enabled for this connection.
     *
     * @return true if a trace context is available
     */
    public boolean hasTelemetry() {
        return trace != null;
    }

    /**
     * Returns true if telemetry is enabled for this connection's connector.
     * This indicates whether new traces should be created for this connection.
     *
     * @return true if telemetry is enabled on the connector
     */
    public boolean isTelemetryEnabled() {
        return connector != null && connector.isTelemetryEnabled();
    }

    /**
     * Returns the telemetry configuration for this connection.
     *
     * @return the telemetry config, or null if not configured
     */
    public TelemetryConfig getTelemetryConfig() {
        return connector != null ? connector.getTelemetryConfig() : null;
    }

    /**
     * Creates and sets a trace context for this connection.
     * Called automatically during initialization if telemetry is enabled.
     *
     * @param spanName the name for the root span
     */
    protected void initTrace(String spanName) {
        if (connector != null && connector.isTelemetryEnabled()) {
            trace = connector.getTelemetryConfig().createTrace(spanName);
        }
    }

    /**
     * Creates a trace context from a remote traceparent header.
     * Use this to continue a distributed trace.
     *
     * @param traceparent the W3C traceparent header value
     * @param spanName the name for the local root span
     */
    protected void initTraceFromTraceparent(String traceparent, String spanName) {
        if (connector != null && connector.isTelemetryEnabled()) {
            trace = connector.getTelemetryConfig().createTraceFromTraceparent(
                    traceparent, spanName, org.bluezoo.gumdrop.telemetry.SpanKind.SERVER);
        }
    }

    /**
     * Ends the trace and exports telemetry data.
     * Called automatically when the connection is closed.
     */
    protected void endTrace() {
        if (trace != null) {
            trace.end();
        }
    }

    // -- Protected/public API --

    /**
     * Called when application data is received from the peer.
     * SSL decryption is handled automatically if this is a secure connection.
     * Public to allow direct testing without reflection.
     *
     * @param data the application data received
     */
    public abstract void receive(ByteBuffer data);

    /**
     * Sends application data to the remote peer.
     * SSL encryption is handled automatically if this is a secure connection.
     *
     * @param data the application data to send
     */
    public void send(ByteBuffer data) {
        if (data == null) {
            // Null means close after current pending writes
            netSend(CLOSE_SENTINEL);
            return;
        }
        // Check if channel is closed (skip if channel is null - testing mode)
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
            netSend(data);
        }
    }

    /**
     * Queues raw (possibly encrypted) data for network transmission.
     * If a sendCallback is set (testing/stream mode), delivers to callback instead.
     */
    final void netSend(ByteBuffer data) {
        // If sendCallback is set, deliver to callback instead of queueing
        if (sendCallback != null) {
            sendCallback.onSend(this, data);
            return;
        }
        outboundQueue.offer(data);
        if (selectorLoop != null) {
            selectorLoop.requestWrite(this);
        }
    }

    /**
     * Closes the connection gracefully.
     * For SSL connections, sends close_notify before closing.
     */
    public void close() {
        if (closing) {
            return; // Already closing
        }
        closing = true;
        if (sslState != null) {
            sslState.closeOutbound();
        } else {
            netSend(CLOSE_SENTINEL);
        }
    }

    /**
     * Closes the underlying socket connection immediately.
     */
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
    }

    /**
     * Called when the connection is established (for client connections).
     */
    public void connected() {
    }

    /**
     * Called when a connect attempt failed (for client connections).
     */
    public void finishConnectFailed(IOException connectException) {
    }

    /**
     * Called when the peer closed the connection.
     */
    protected abstract void disconnected() throws IOException;

    /**
     * Called when SSL handshaking has completed.
     *
     * @param protocol the application protocol negotiated (e.g., "h2", "http/1.1")
     */
    protected void handshakeComplete(String protocol) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("SSL handshake complete. Negotiated protocol: " + protocol);
        }
        // Mark connection as fully established after TLS handshake
        if (timestampConnected == 0) {
            timestampConnected = System.currentTimeMillis();
        }
    }

    // -- SSL information --

    /**
     * Returns the cipher suite used for this secure connection.
     */
    public String getCipherSuite() {
        return (sslState == null) ? null : sslState.session.getCipherSuite();
    }

    /**
     * Returns the peer certificates from the TLS handshake.
     */
    public Certificate[] getPeerCertificates() {
        try {
            return (sslState == null) ? null : sslState.session.getPeerCertificates();
        } catch (SSLPeerUnverifiedException e) {
            return null;
        }
    }

    /**
     * Returns the key size of the cipher suite.
     */
    public int getKeySize() {
        String cipherSuite = getCipherSuite();
        if (cipherSuite == null) {
            return -1;
        }
        String[] comps = cipherSuite.split("_");
        for (String comp : comps) {
            try {
                return Integer.parseInt(comp);
            } catch (NumberFormatException e) {
            }
            Integer keySize = KNOWN_KEY_SIZES.get(comp);
            if (keySize != null) {
                return keySize.intValue();
            }
        }
        return -1;
    }

    // -- Socket address methods --

    /**
     * Returns the local address of this connection.
     */
    public SocketAddress getLocalSocketAddress() {
        if (channel == null) {
            return new java.net.InetSocketAddress("localhost", 0);
        }
        return channel.socket().getLocalSocketAddress();
    }

    /**
     * Returns the remote address of this connection.
     */
    public SocketAddress getRemoteSocketAddress() {
        if (channel == null) {
            return new java.net.InetSocketAddress("unknown", 0);
        }
        return channel.socket().getRemoteSocketAddress();
    }

    // -- SSL State inner class --

    /**
     * Manages SSL wrap/unwrap operations.
     * All methods run on the SelectorLoop thread, no synchronization needed.
     */
    private class SSLState {

        final SSLSession session;

        ByteBuffer netIn;   // Network input (encrypted from peer)
        ByteBuffer appIn;   // Application input (decrypted)
        ByteBuffer netOut;  // Network output (encrypted to peer)

        boolean handshakeStarted;
        boolean closed;

        // Track if netIn is in read mode (after flip) or write mode (after clear/compact)
        boolean netInReadMode;

        SSLState(SSLSession session) {
            this.session = session;
            int netSize = Math.max(32768, session.getPacketBufferSize());
            int appSize = Math.max(32768, session.getApplicationBufferSize());

            netIn = ByteBuffer.allocate(netSize);
            netOut = ByteBuffer.allocate(netSize);
            appIn = ByteBuffer.allocate(appSize);

            bufferSize = appSize;
            netInReadMode = false; // Starts in write mode
        }

        void unwrap(ByteBuffer data) {
            if (closed) {
                return;
            }

            try {
                // Ensure netIn is in write mode before appending
                if (netInReadMode) {
                    if (netIn.hasRemaining()) {
                        netIn.compact();
                    } else {
                        netIn.clear();
                    }
                    netInReadMode = false;
                }

                // Expand buffer if needed
                if (netIn.remaining() < data.remaining()) {
                    int newSize = netIn.position() + data.remaining() + 4096;
                    ByteBuffer tmp = ByteBuffer.allocate(newSize);
                    netIn.flip();
                    tmp.put(netIn);
                    netIn = tmp;
                }

                // Append incoming data and switch to read mode
                netIn.put(data);
                netIn.flip();
                netInReadMode = true;

                processSSLEvents();
            } catch (SSLException e) {
                LOGGER.log(Level.SEVERE, "SSL unwrap error", e);
                handleClosed("unwrap");
            }
        }

        void wrap(ByteBuffer data) {
            if (closed || engine.isOutboundDone()) {
                return;
            }

            try {
                // Wrap application data into encrypted output
                boolean done = false;
                while (!done && data.hasRemaining()) {
                    netOut.clear();
                    SSLEngineResult result = engine.wrap(data, netOut);

                    switch (result.getStatus()) {
                        case OK:
                            if (netOut.position() > 0) {
                                netOut.flip();
                                sendNetOut();
                            }
                            break;
                        case BUFFER_OVERFLOW:
                            netOut = ByteBuffer.allocate(netOut.capacity() + 4096);
                            break;
                        case CLOSED:
                            done = true;
                            break;
                        default:
                            done = true;
                            break;
                    }

                    // Handle any handshake events
                    SSLEngineResult.HandshakeStatus hs = result.getHandshakeStatus();
                    if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                        runDelegatedTasks();
                    } else if (hs == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                        done = true;
                    }
                }
            } catch (SSLException e) {
                LOGGER.log(Level.SEVERE, "SSL wrap error", e);
            }
        }

        void closeOutbound() {
            if (closed) {
                netSend(CLOSE_SENTINEL);
                return;
            }
            closed = true;

            try {
                engine.closeOutbound();

                // Generate close_notify
                ByteBuffer empty = ByteBuffer.allocate(0);
                netOut.clear();
                engine.wrap(empty, netOut);

                if (netOut.position() > 0) {
                    netOut.flip();
                    sendNetOut();
                }

                // Queue close sentinel
                netSend(CLOSE_SENTINEL);
            } catch (SSLException e) {
                LOGGER.log(Level.WARNING, "Error sending close_notify", e);
                netSend(CLOSE_SENTINEL);
            }
        }

        private void processSSLEvents() throws SSLException {
            if (!handshakeStarted) {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    Object sa = channel.socket().getRemoteSocketAddress();
                    String message = Gumdrop.L10N.getString("info.ssl_begin_handshake");
                    message = MessageFormat.format(message, sa);
                    LOGGER.finest(message);
                }
                engine.beginHandshake();
                handshakeStarted = true;
            }

            SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();

            if (hs == SSLEngineResult.HandshakeStatus.FINISHED ||
                hs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                // Process application data
                processApplicationData();
            } else {
                // Still handshaking
                processHandshake(hs);
            }
        }

        private void processHandshake(SSLEngineResult.HandshakeStatus hs) throws SSLException {
            boolean loop;
            SSLEngineResult result;

            do {
                loop = false;

                switch (hs) {
                    case NEED_WRAP:
                        netOut.clear();
                        ByteBuffer empty = ByteBuffer.allocate(0);
                        result = engine.wrap(empty, netOut);

                        switch (result.getStatus()) {
                            case OK:
                                if (netOut.position() > 0) {
                                    netOut.flip();
                                    sendNetOut();
                                }
                                break;
                            case BUFFER_OVERFLOW:
                                netOut = ByteBuffer.allocate(netOut.capacity() + 4096);
                                loop = true;
                                break;
                            case CLOSED:
                                handleClosed("handshake-wrap");
                                return;
                            default:
                                break;
                        }
                        break;

                    case NEED_UNWRAP:
                        result = engine.unwrap(netIn, appIn);

                        switch (result.getStatus()) {
                            case OK:
                                break;
                            case BUFFER_UNDERFLOW:
                                // Need more data from network
                                netIn.compact();
                                netInReadMode = false;
                                return;
                            case BUFFER_OVERFLOW:
                                appIn = ByteBuffer.allocate(appIn.capacity() + 4096);
                                loop = true;
                                break;
                            case CLOSED:
                                handleClosed("handshake-unwrap");
                                return;
                        }
                        break;

                    case NEED_TASK:
                        runDelegatedTasks();
                        break;

                    default:
                        break;
                }

                hs = engine.getHandshakeStatus();

                // Continue if still handshaking
                if (hs != SSLEngineResult.HandshakeStatus.FINISHED &&
                    hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                    loop = true;
                }

            } while (loop);

            // Handshake complete
            if (hs == SSLEngineResult.HandshakeStatus.FINISHED ||
                hs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    Object sa = channel.socket().getRemoteSocketAddress();
                    String message = Gumdrop.L10N.getString("info.ssl_handshake_finished");
                    message = MessageFormat.format(message, sa);
                    LOGGER.finest(message);
                }
                handshakeComplete(engine.getApplicationProtocol());

                // Process any remaining data, or prepare for next read
                if (netIn.hasRemaining()) {
                    processApplicationData();
                } else {
                    // No remaining data - switch to write mode for next read
                    netIn.clear();
                    netInReadMode = false;
                }
            }
        }

        private void processApplicationData() throws SSLException {
            SSLEngineResult result;
            boolean loop = netIn.hasRemaining();

            while (loop) {
                loop = false;
                result = engine.unwrap(netIn, appIn);

                switch (result.getStatus()) {
                    case OK:
                        if (appIn.position() > 0) {
                            appIn.flip();
                            ByteBuffer data = ByteBuffer.allocate(appIn.remaining());
                            data.put(appIn);
                            data.flip();
                            appIn.clear();

                            if (LOGGER.isLoggable(Level.FINEST)) {
                                Object sa = channel.socket().getRemoteSocketAddress();
                                String message = Gumdrop.L10N.getString("info.received_decrypted");
                                message = MessageFormat.format(message, data.remaining(), sa);
                                LOGGER.finest(message);
                            }

                            // Deliver to application
                            receive(data);
                        }
                        if (netIn.hasRemaining()) {
                            loop = true;
                        }
                        break;

                    case BUFFER_UNDERFLOW:
                        // Need more data
                        netIn.compact();
                        netInReadMode = false;
                        return;

                    case BUFFER_OVERFLOW:
                        appIn = ByteBuffer.allocate(appIn.capacity() + 4096);
                        loop = true;
                        break;

                    case CLOSED:
                        handleClosed("application-unwrap");
                        return;
                }

                // Handle handshake events during data transfer
                SSLEngineResult.HandshakeStatus hs = result.getHandshakeStatus();
                if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    runDelegatedTasks();
                } else if (hs == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    // Need to send data (e.g., close_notify response)
                    netOut.clear();
                    ByteBuffer empty = ByteBuffer.allocate(0);
                    engine.wrap(empty, netOut);
                    if (netOut.position() > 0) {
                        netOut.flip();
                        sendNetOut();
                    }
                }
            }

            netIn.compact();
            netInReadMode = false;
        }

        private void runDelegatedTasks() {
            Runnable task;
            while ((task = engine.getDelegatedTask()) != null) {
                task.run();
            }
        }

        private void sendNetOut() {
            ByteBuffer data = ByteBuffer.allocate(netOut.remaining());
            data.put(netOut);
            data.flip();
            netSend(data);
        }

        void handleClosed(String context) {
            if (closed) {
                return;
            }
            closed = true;

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("SSL closed during " + context);
            }

            try {
                disconnected();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error in disconnected handler", e);
            }
            doClose();
        }
    }

    // -- Debug utilities --

    protected static String toString(ByteBuffer buf) {
        return buf.getClass().getName() + "[pos=" + buf.position() +
                ",limit=" + buf.limit() + ",capacity=" + buf.capacity() + "]";
    }

    protected static String toASCIIString(ByteBuffer data) {
        int pos = data.position();
        StringBuilder s = new StringBuilder();
        while (data.hasRemaining()) {
            int c = data.get() & 0xff;
            if (c == '\r') {
                s.append("<CR>");
            } else if (c == '\n') {
                s.append("<LF>");
            } else if (c == '\t') {
                s.append("<HT>");
            } else if (c >= 32 && c < 127) {
                s.append((char) c);
            } else {
                s.append('.');
            }
        }
        data.position(pos);
        return s.toString();
    }
}
