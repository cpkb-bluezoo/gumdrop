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
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;
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
public abstract class Connection implements ChannelHandler, SSLState.Callback {

    private static final Logger LOGGER = Logger.getLogger(Connection.class.getName());

    /** Default buffer size for network I/O */
    private static final int DEFAULT_BUFFER_SIZE = 8192;

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

    // Network I/O buffers (owned by Connection, used by SelectorLoop)
    ByteBuffer netIn;   // Incoming data (encrypted for TLS, plaintext otherwise)
    ByteBuffer netOut;  // Outgoing data (encrypted for TLS, plaintext otherwise)

    // Close requested flag - SelectorLoop closes after netOut is flushed
    boolean closeRequested;

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
    private long handshakeStartTime;

    // Lifecycle state
    private volatile boolean initialized;
    private volatile boolean closing;
    private boolean clientConnection;

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
            bufferSize = Math.max(DEFAULT_BUFFER_SIZE, socket.getReceiveBufferSize());
            // For plaintext connections, we're connected immediately
            timestampConnected = System.currentTimeMillis();
        }

        // Initialize SSL state if connection starts secure
        if (engine != null && secure) {
            handshakeStartTime = System.currentTimeMillis();
            sslState = new SSLState(engine, this);
            bufferSize = sslState.getBufferSize();
            // timestampConnected will be set in handshakeComplete()
        }

        // Allocate network I/O buffers
        netIn = ByteBuffer.allocate(bufferSize);
        netOut = ByteBuffer.allocate(bufferSize);

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
        handshakeStartTime = System.currentTimeMillis();
        sslState = new SSLState(engine, this);
        bufferSize = sslState.getBufferSize();
    }

    /**
     * Initiates the TLS handshake for client connections.
     *
     * <p>For client TLS connections, the client must send the ClientHello first.
     * This method triggers that initial handshake step. It should be called
     * after TCP connect completes for connections that start secure.
     *
     * <p>Server connections should NOT call this - they wait for the client's
     * ClientHello to arrive via normal data reception.
     *
     * <p>This method is safe to call if SSL is not configured - it does nothing.
     */
    protected final void initiateClientTLSHandshake() {
        if (sslState != null && isClientConnection()) {
            // Start the TLS handshake - this will:
            // 1. Begin the handshake (engine.beginHandshake())
            // 2. See NEED_WRAP status for client
            // 3. Wrap ClientHello into netOut
            // 4. Request write via selectorLoop to send ClientHello
            sslState.startClientHandshake();
        }
    }

    // -- Package-private methods called by SelectorLoop --

    /**
     * Called by SelectorLoop after copying data into netIn.
     * The netIn buffer is in read mode (flipped) with data ready to process.
     * Handles SSL unwrap if secure, then calls receive() with application data.
     * Loops until all data is consumed or receive() stops consuming.
     * After processing, compacts netIn for next append.
     */
    final void processInbound() {
        updateLastActivity();
        if (sslState != null) {
            sslState.unwrap();
        } else {
            // Plaintext: netIn IS the application data
            if (LOGGER.isLoggable(Level.FINEST)) {
                String message = Gumdrop.L10N.getString("info.received_plaintext");
                message = MessageFormat.format(message, netIn.remaining(),
                        channel.socket().getRemoteSocketAddress());
                LOGGER.finest(message);
            }
            
            // Call receive once - it should consume all complete messages
            // and leave only incomplete data (if any)
            receive(netIn);
            
            // Compact to preserve any unconsumed data for next read cycle
            netIn.compact();
        }
    }

    /**
     * Appends data to the netIn buffer, growing if necessary.
     * Called by SelectorLoop after reading from socket.
     *
     * @param data the data to append (in read mode)
     * @throws BufferOverflowException if the buffer would exceed the configured maximum
     */
    final void appendToNetIn(ByteBuffer data) {
        int needed = data.remaining();
        int available = netIn.remaining(); // space available for writing

        if (needed > available) {
            // Need to grow the buffer
            int newSize = netIn.position() + needed + DEFAULT_BUFFER_SIZE;
            
            // Check against configured maximum
            int maxSize = connector != null ? connector.getMaxNetInSize() : 0;
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
        // If data was buffered before registration (e.g., during init()), schedule write now
        if (loop != null && netOut != null && netOut.position() > 0) {
            loop.requestWrite(this);
        }
    }

    @Override
    public SelectorLoop getSelectorLoop() {
        return selectorLoop;
    }

    /**
     * Returns the netOut buffer for SelectorLoop to write from.
     * Buffer should be flipped before writing.
     */
    ByteBuffer getNetOut() {
        return netOut;
    }

    /**
     * Returns true if there is pending data to write or close is requested.
     */
    boolean hasPendingWrite() {
        return (netOut != null && netOut.position() > 0) || closeRequested;
    }

    /**
     * Sets a callback to intercept send operations.
     * Used for testing and integration with streams that need to capture sent data.
     */
    public void setSendCallback(SendCallback callback) {
        this.sendCallback = callback;
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
            close();
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
            appendToNetOut(data);
        }
    }

    /**
     * Appends data to netOut, growing if necessary, and requests write.
     * For plaintext, this is called directly from send().
     * For TLS, SSLState.wrap() calls this with encrypted data.
     *
     * @param data the data to append (in read mode)
     */
    /** Lock for netOut buffer access (shared between worker and selector threads). */
    final Object netOutLock = new Object();

    final void appendToNetOut(ByteBuffer data) {
        // If sendCallback is set (testing mode), deliver to callback instead
        if (sendCallback != null) {
            sendCallback.onSend(this, data);
            return;
        }

        synchronized (netOutLock) {
            int needed = data.remaining();
            int available = netOut.remaining(); // space available for writing

            if (needed > available) {
                // Need to grow the buffer
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

    /**
     * Closes the connection gracefully.
     * For SSL connections, sends close_notify before closing.
     * The actual close happens after netOut is flushed by SelectorLoop.
     */
    public void close() {
        if (closing) {
            return; // Already closing
        }
        closing = true;
        closeRequested = true;
        if (sslState != null) {
            sslState.closeOutbound();
        }
        // Request write so SelectorLoop will flush netOut and close
        if (selectorLoop != null) {
            selectorLoop.requestWrite(this);
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
        // Deregister from Gumdrop if this was a client connection
        if (clientConnection) {
            Gumdrop gumdrop = Gumdrop.getInstance();
            if (gumdrop != null) {
                gumdrop.removeChannelHandler(this);
            }
        }
    }

    /**
     * Sets whether this is a client connection.
     *
     * <p>Client connections are tracked by Gumdrop for automatic lifecycle
     * management. When a client connection closes, it is automatically
     * deregistered from Gumdrop.
     *
     * @param clientConnection true if this is a client connection
     */
    void setClientConnection(boolean clientConnection) {
        this.clientConnection = clientConnection;
    }

    /**
     * Returns whether this is a client connection.
     *
     * @return true if this is a client connection
     */
    boolean isClientConnection() {
        return clientConnection;
    }

    /**
     * Called when the connection is established (for client connections).
     */
    public void connected() {
    }

    /**
     * Called when a connect attempt failed (for client connections).
     *
     * <p>The default implementation deregisters this connection from Gumdrop
     * if it was a client connection.
     *
     * @param connectException the exception that caused the failure
     */
    public void finishConnectFailed(IOException connectException) {
        // Deregister from Gumdrop if this was a client connection
        if (clientConnection) {
            Gumdrop gumdrop = Gumdrop.getInstance();
            if (gumdrop != null) {
                gumdrop.removeChannelHandler(this);
            }
        }
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

    /**
     * Creates a ConnectionInfo for this connection.
     * 
     * <p>This is used by client connections to provide connection details
     * to handlers.
     * 
     * @return the connection info
     */
    protected ConnectionInfo createConnectionInfo() {
        TLSInfo tlsInfo = null;
        if (secure && engine != null) {
            tlsInfo = new DefaultTLSInfo(engine, handshakeStartTime);
        }
        return new DefaultConnectionInfo(
            getLocalSocketAddress(),
            getRemoteSocketAddress(),
            secure,
            tlsInfo
        );
    }

    /**
     * Creates a TLSInfo for this connection.
     * 
     * <p>This is used by client connections to provide TLS details
     * to handlers after a STARTTLS upgrade.
     * 
     * @return the TLS info, or null if not secure
     */
    protected TLSInfo createTLSInfo() {
        if (secure && engine != null) {
            return new DefaultTLSInfo(engine, handshakeStartTime);
        }
        return null;
    }

    // -- SSLState.Callback implementation --

    /**
     * Called by SSLState when decrypted application data is available.
     * SSLState manages its own appIn buffer and handles underflow.
     */
    @Override
    public final void onApplicationData(ByteBuffer data) {
        receive(data);
    }

    /**
     * Called by SSLState when TLS handshake completes.
     */
    @Override
    public final void onHandshakeComplete(String protocol) {
        handshakeComplete(protocol);
    }

    /**
     * Called by SSLState when TLS connection is closed.
     */
    @Override
    public final void onClosed() {
        try {
            disconnected();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error in disconnected handler", e);
        }
        doClose();
    }

    /**
     * Returns the remote socket address for SSLState logging.
     */
    @Override
    public final Object getRemoteAddress() {
        if (channel == null) {
            return "unknown";
        }
        return channel.socket().getRemoteSocketAddress();
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
