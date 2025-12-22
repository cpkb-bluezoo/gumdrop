/*
 * SSLState.java
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

import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

/**
 * Manages SSL wrap/unwrap operations for a TCP connection.
 * All methods run on the SelectorLoop thread, no synchronization needed.
 *
 * <p>This class is tightly integrated with {@link Connection} and handles
 * all TLS-specific processing including handshaking, encryption/decryption,
 * and close notification.
 *
 * <p>Buffer ownership:
 * <ul>
 * <li>{@code connection.netIn} - encrypted bytes from socket (owned by Connection)</li>
 * <li>{@code connection.netOut} - encrypted bytes to socket (owned by Connection)</li>
 * <li>{@code appIn} - decrypted bytes for receive() (owned by SSLState)</li>
 * <li>{@code appOut} - plaintext bytes waiting to wrap (owned by SSLState)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class SSLState {

    private static final Logger LOGGER = Logger.getLogger(SSLState.class.getName());

    /** Default buffer size */
    private static final int DEFAULT_BUFFER_SIZE = 8192;

    /**
     * Callback interface for SSLState to communicate with Connection.
     */
    interface Callback {
        /**
         * Called when decrypted application data is available.
         */
        void onApplicationData(ByteBuffer data);

        /**
         * Called when TLS handshake completes.
         * @param protocol the negotiated application protocol (e.g., "h2")
         */
        void onHandshakeComplete(String protocol);

        /**
         * Called when TLS connection is closed (e.g., close_notify received).
         */
        void onClosed();

        /**
         * Returns the remote socket address for logging.
         */
        Object getRemoteAddress();
    }

    private final SSLEngine engine;
    private final Connection connection;
    final SSLSession session;

    // Application data buffers (owned by SSLState)
    ByteBuffer appIn;   // Decrypted input for receive()
    ByteBuffer appOut;  // Plaintext waiting to be wrapped

    boolean handshakeStarted;
    boolean closed;

    /**
     * Creates a new SSLState for the given engine and connection.
     *
     * @param engine the SSL engine
     * @param connection the connection that owns the network buffers
     */
    SSLState(SSLEngine engine, Connection connection) {
        this.engine = engine;
        this.connection = connection;
        this.session = engine.getSession();

        int appSize = Math.max(DEFAULT_BUFFER_SIZE, session.getApplicationBufferSize());

        // Application data buffers - owned by SSLState
        appIn = ByteBuffer.allocate(appSize);
        appOut = ByteBuffer.allocate(appSize);
    }

    /**
     * Returns the application buffer size for connection configuration.
     */
    int getBufferSize() {
        return Math.max(32768, session.getApplicationBufferSize());
    }

    /**
     * Initiates the TLS handshake for client connections.
     *
     * <p>This method starts the handshake process by calling beginHandshake()
     * and processing the initial NEED_WRAP state to generate the ClientHello.
     * The ClientHello is written to connection.netOut and a write is requested.
     *
     * <p>Only call this for client connections - server connections wait for
     * the client's ClientHello to arrive via normal data flow.
     */
    void startClientHandshake() {
        if (closed) {
            return;
        }

        try {
            if (!handshakeStarted) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    Object sa = connection.getRemoteAddress();
                    String message = Gumdrop.L10N.getString("info.ssl_begin_handshake");
                    message = MessageFormat.format(message, sa);
                    LOGGER.fine(message);
                }
                engine.beginHandshake();
                handshakeStarted = true;
            }

            // Process the initial handshake - for clients, this will NEED_WRAP
            SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
            if (hs != SSLEngineResult.HandshakeStatus.FINISHED &&
                hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                processHandshake(hs);
            }

        } catch (SSLException e) {
            LOGGER.log(Level.SEVERE, "Failed to initiate client TLS handshake", e);
            handleClosed("client-handshake-init");
        }
    }

    /**
     * Processes incoming encrypted data from connection.netIn.
     * Called by Connection.processInbound() after data is appended to netIn.
     * The connection.netIn buffer is in read mode (flipped).
     */
    void unwrap() {
        if (closed) {
            return;
        }

        try {
            processSSLEvents();
        } catch (SSLException e) {
            LOGGER.log(Level.SEVERE, "SSL unwrap error", e);
            handleClosed("unwrap");
        } finally {
            // Compact netIn for next append (preserves any partial TLS records)
            connection.netIn.compact();
        }
    }

    /**
     * Encrypts and sends application data.
     * Writes encrypted output directly to connection.netOut.
     */
    void wrap(ByteBuffer data) {
        if (closed || engine.isOutboundDone()) {
            return;
        }

        try {
            synchronized (connection.netOutLock) {
                // Wrap application data into encrypted output
                boolean done = false;
                while (!done && data.hasRemaining()) {
                    // Ensure netOut has space - wrap directly into it
                    ensureNetOutCapacity(session.getPacketBufferSize());
                    
                    SSLEngineResult result = engine.wrap(data, connection.netOut);

                    switch (result.getStatus()) {
                        case OK:
                            // Data was wrapped successfully into connection.netOut
                            break;
                        case BUFFER_OVERFLOW:
                            // Need more space in netOut
                            growNetOut(session.getPacketBufferSize());
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
                
                // Request write if we produced output
                if (connection.netOut.position() > 0 && connection.getSelectorLoop() != null) {
                    connection.getSelectorLoop().requestWrite(connection);
                }
            }
        } catch (SSLException e) {
            LOGGER.log(Level.SEVERE, "SSL wrap error", e);
        }
    }
    
    /**
     * Ensures netOut has at least the specified capacity remaining.
     */
    private void ensureNetOutCapacity(int needed) {
        if (connection.netOut.remaining() < needed) {
            growNetOut(needed);
        }
    }
    
    /**
     * Grows the netOut buffer to accommodate more data.
     */
    private void growNetOut(int additional) {
        int newSize = connection.netOut.position() + additional + DEFAULT_BUFFER_SIZE;
        ByteBuffer newBuf = ByteBuffer.allocate(newSize);
        connection.netOut.flip();
        newBuf.put(connection.netOut);
        connection.netOut = newBuf;
    }

    /**
     * Initiates a graceful close with close_notify.
     * Writes close_notify to connection.netOut.
     */
    void closeOutbound() {
        if (closed) {
            return;
        }
        closed = true;

        try {
            engine.closeOutbound();

            // Generate close_notify into connection.netOut
            // Synchronize to prevent race with application thread calling wrap()
            synchronized (connection.netOutLock) {
                ensureNetOutCapacity(session.getPacketBufferSize());
                ByteBuffer empty = ByteBuffer.allocate(0);
                engine.wrap(empty, connection.netOut);
            }

        } catch (SSLException e) {
            LOGGER.log(Level.WARNING, "Error sending close_notify", e);
        }
    }

    private void processSSLEvents() throws SSLException {
        if (!handshakeStarted) {
            if (LOGGER.isLoggable(Level.FINEST)) {
                Object sa = connection.getRemoteAddress();
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
                    // Synchronize on netOutLock since we're writing to connection.netOut
                    // This prevents race with application thread calling wrap()
                    synchronized (connection.netOutLock) {
                        ensureNetOutCapacity(session.getPacketBufferSize());
                        ByteBuffer empty = ByteBuffer.allocate(0);
                        result = engine.wrap(empty, connection.netOut);

                        switch (result.getStatus()) {
                            case OK:
                                // Handshake data written to connection.netOut
                                if (connection.netOut.position() > 0 && 
                                    connection.getSelectorLoop() != null) {
                                    connection.getSelectorLoop().requestWrite(connection);
                                }
                                break;
                            case BUFFER_OVERFLOW:
                                growNetOut(session.getPacketBufferSize());
                                loop = true;
                                break;
                            case CLOSED:
                                handleClosed("handshake-wrap");
                                return;
                            default:
                                break;
                        }
                    }
                    break;

                case NEED_UNWRAP:
                    // Check if netIn is in read mode and has data
                    // If no data available, return and wait for more from network
                    if (connection.netIn.position() == 0 && connection.netIn.limit() == connection.netIn.capacity()) {
                        // Buffer is in write mode (never flipped), no data to read
                        return;
                    }
                    result = engine.unwrap(connection.netIn, appIn);

                    switch (result.getStatus()) {
                        case OK:
                            break;
                        case BUFFER_UNDERFLOW:
                            // Need more data from network - return and wait
                            return;
                        case BUFFER_OVERFLOW:
                            appIn = ByteBuffer.allocate(appIn.capacity() + DEFAULT_BUFFER_SIZE);
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
                Object sa = connection.getRemoteAddress();
                String message = Gumdrop.L10N.getString("info.ssl_handshake_finished");
                message = MessageFormat.format(message, sa);
                LOGGER.finest(message);
            }
            connection.onHandshakeComplete(engine.getApplicationProtocol());

            // Process any remaining data
            if (connection.netIn.hasRemaining()) {
                processApplicationData();
            }
        }
    }

    private void processApplicationData() throws SSLException {
        SSLEngineResult result;
        boolean loop = connection.netIn.hasRemaining();

        while (loop) {
            loop = false;
            result = engine.unwrap(connection.netIn, appIn);

            switch (result.getStatus()) {
                case OK:
                    // Deliver any decrypted data to the application
                    if (appIn.position() > 0) {
                        appIn.flip();

                        if (LOGGER.isLoggable(Level.FINEST)) {
                            Object sa = connection.getRemoteAddress();
                            String message = Gumdrop.L10N.getString("info.received_decrypted");
                            message = MessageFormat.format(message, appIn.remaining(), sa);
                            LOGGER.finest(message);
                        }

                        // Deliver to application - it should consume all complete messages
                        connection.onApplicationData(appIn);

                        // Compact to preserve any unconsumed data
                        appIn.compact();
                    }
                    
                    if (connection.netIn.hasRemaining()) {
                        loop = true;
                    }
                    break;

                case BUFFER_UNDERFLOW:
                    // Need more encrypted data - return and wait
                    return;

                case BUFFER_OVERFLOW:
                    // appIn too small - grow it
                    int newSize = appIn.capacity() + DEFAULT_BUFFER_SIZE;
                    ByteBuffer newAppIn = ByteBuffer.allocate(newSize);
                    appIn.flip();
                    newAppIn.put(appIn);
                    appIn = newAppIn;
                    loop = true;
                    break;

                case CLOSED:
                    handleClosed("application-unwrap");
                    return;
            }

            // Handle handshake events during data transfer (e.g., renegotiation)
            SSLEngineResult.HandshakeStatus hs = result.getHandshakeStatus();
            if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                runDelegatedTasks();
            } else if (hs == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                // Need to send data (e.g., close_notify response)
                // Synchronize to prevent race with application thread calling wrap()
                synchronized (connection.netOutLock) {
                    ensureNetOutCapacity(session.getPacketBufferSize());
                    ByteBuffer empty = ByteBuffer.allocate(0);
                    engine.wrap(empty, connection.netOut);
                    if (connection.netOut.position() > 0 && 
                        connection.getSelectorLoop() != null) {
                        connection.getSelectorLoop().requestWrite(connection);
                    }
                }
            }
        }
    }

    private void runDelegatedTasks() {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
            task.run();
        }
    }

    void handleClosed(String context) {
        if (closed) {
            return;
        }
        closed = true;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("SSL closed during " + context);
        }

        connection.onClosed();
    }
}

