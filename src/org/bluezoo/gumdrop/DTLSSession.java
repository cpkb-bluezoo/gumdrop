/*
 * DTLSSession.java
 * Copyright (C) 2025 Chris Burdess
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

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages DTLS wrap/unwrap operations for a datagram connection.
 *
 * <p>Unlike TLS over TCP, DTLS operates on individual datagrams rather than
 * a continuous byte stream. Each datagram is independently encrypted/decrypted.
 *
 * <p>Key differences from TCP TLS:
 * <ul>
 * <li>No stream reassembly - each datagram is self-contained</li>
 * <li>DTLS handles retransmission of handshake messages internally</li>
 * <li>No guaranteed ordering - datagrams may arrive out of order</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class DTLSSession {

    private static final Logger LOGGER = Logger.getLogger(DTLSSession.class.getName());

    private final SSLEngine engine;
    private final SSLSession session;
    private final InetSocketAddress remoteAddress;

    // Reference to parent for sending handshake messages
    private final DatagramServer server;
    private final DatagramClient client;

    // Buffers for DTLS operations
    private ByteBuffer netIn;
    private ByteBuffer netOut;
    private ByteBuffer appIn;

    private boolean handshakeComplete;
    private boolean closed;

    /**
     * Creates a DTLS session for a server.
     */
    DTLSSession(SSLEngine engine, DatagramServer server, InetSocketAddress remoteAddress) {
        this.engine = engine;
        this.session = engine.getSession();
        this.server = server;
        this.client = null;
        this.remoteAddress = remoteAddress;
        initBuffers();
    }

    /**
     * Creates a DTLS session for a client.
     */
    DTLSSession(SSLEngine engine, DatagramClient client, InetSocketAddress remoteAddress) {
        this.engine = engine;
        this.session = engine.getSession();
        this.server = null;
        this.client = client;
        this.remoteAddress = remoteAddress;
        initBuffers();
    }

    private void initBuffers() {
        int netSize = Math.max(32768, session.getPacketBufferSize());
        int appSize = Math.max(32768, session.getApplicationBufferSize());

        netIn = ByteBuffer.allocate(netSize);
        netOut = ByteBuffer.allocate(netSize);
        appIn = ByteBuffer.allocate(appSize);
    }

    /**
     * Initiates the DTLS handshake (for client-side).
     */
    void beginHandshake() {
        if (handshakeComplete || closed) {
            return;
        }

        try {
            engine.beginHandshake();
            processHandshakeStatus(engine.getHandshakeStatus());
        } catch (SSLException e) {
            LOGGER.log(Level.SEVERE, "DTLS handshake initiation failed", e);
        }
    }

    /**
     * Unwraps (decrypts) an incoming DTLS record.
     *
     * @param datagram the incoming encrypted datagram
     * @return the decrypted application data, or null if handshake in progress
     */
    ByteBuffer unwrap(ByteBuffer datagram) {
        if (closed) {
            return null;
        }

        try {
            // DTLS datagrams are self-contained, so we process each one individually
            appIn.clear();

            SSLEngineResult result = engine.unwrap(datagram, appIn);

            switch (result.getStatus()) {
                case OK:
                    processHandshakeStatus(result.getHandshakeStatus());
                    if (appIn.position() > 0) {
                        appIn.flip();
                        ByteBuffer data = ByteBuffer.allocate(appIn.remaining());
                        data.put(appIn);
                        data.flip();
                        return data;
                    }
                    return null;

                case BUFFER_OVERFLOW:
                    // Application buffer too small
                    appIn = ByteBuffer.allocate(appIn.capacity() * 2);
                    return unwrap(datagram);

                case BUFFER_UNDERFLOW:
                    // Incomplete datagram - shouldn't happen with UDP
                    LOGGER.warning("DTLS buffer underflow - incomplete datagram?");
                    return null;

                case CLOSED:
                    closed = true;
                    return null;
            }
        } catch (SSLException e) {
            LOGGER.log(Level.SEVERE, "DTLS unwrap error", e);
        }

        return null;
    }

    /**
     * Wraps (encrypts) outgoing application data.
     *
     * @param data the application data to encrypt
     * @return the encrypted DTLS record, or null if encryption failed
     */
    ByteBuffer wrap(ByteBuffer data) {
        if (closed || engine.isOutboundDone()) {
            return null;
        }

        try {
            netOut.clear();

            SSLEngineResult result = engine.wrap(data, netOut);

            switch (result.getStatus()) {
                case OK:
                    processHandshakeStatus(result.getHandshakeStatus());
                    if (netOut.position() > 0) {
                        netOut.flip();
                        ByteBuffer encrypted = ByteBuffer.allocate(netOut.remaining());
                        encrypted.put(netOut);
                        encrypted.flip();
                        return encrypted;
                    }
                    return null;

                case BUFFER_OVERFLOW:
                    // Network buffer too small
                    netOut = ByteBuffer.allocate(netOut.capacity() * 2);
                    return wrap(data);

                case CLOSED:
                    closed = true;
                    return null;

                default:
                    return null;
            }
        } catch (SSLException e) {
            LOGGER.log(Level.SEVERE, "DTLS wrap error", e);
            return null;
        }
    }

    /**
     * Processes the handshake status and performs any required actions.
     */
    private void processHandshakeStatus(SSLEngineResult.HandshakeStatus hs) throws SSLException {
        while (hs != SSLEngineResult.HandshakeStatus.FINISHED &&
               hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {

            switch (hs) {
                case NEED_TASK:
                    // Run delegated tasks
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    break;

                case NEED_WRAP:
                    // Need to send handshake data
                    netOut.clear();
                    ByteBuffer empty = ByteBuffer.allocate(0);
                    SSLEngineResult result = engine.wrap(empty, netOut);

                    if (netOut.position() > 0) {
                        netOut.flip();
                        ByteBuffer handshakeData = ByteBuffer.allocate(netOut.remaining());
                        handshakeData.put(netOut);
                        handshakeData.flip();
                        sendHandshakeData(handshakeData);
                    }
                    break;

                case NEED_UNWRAP:
                    // Need more data from remote - return and wait
                    // Also handles NEED_UNWRAP_AGAIN (Java 9+) for DTLS buffered data
                    return;

                default:
                    // Handle any unknown status (including future additions)
                    return;
            }

            hs = engine.getHandshakeStatus();
        }

        if (hs == SSLEngineResult.HandshakeStatus.FINISHED && !handshakeComplete) {
            handshakeComplete = true;
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("DTLS handshake complete with " + remoteAddress +
                           ", protocol=" + engine.getApplicationProtocol());
            }
        }
    }

    /**
     * Sends handshake data to the remote peer.
     */
    private void sendHandshakeData(ByteBuffer data) {
        if (server != null) {
            server.netSend(data, remoteAddress);
        } else if (client != null) {
            client.netSend(data);
        }
    }

    /**
     * Returns whether the handshake has completed.
     */
    boolean isHandshakeComplete() {
        return handshakeComplete;
    }

    /**
     * Closes the DTLS session.
     */
    void close() {
        if (closed) {
            return;
        }
        closed = true;

        try {
            engine.closeOutbound();

            // Send close_notify
            netOut.clear();
            ByteBuffer empty = ByteBuffer.allocate(0);
            engine.wrap(empty, netOut);

            if (netOut.position() > 0) {
                netOut.flip();
                ByteBuffer closeNotify = ByteBuffer.allocate(netOut.remaining());
                closeNotify.put(netOut);
                closeNotify.flip();
                sendHandshakeData(closeNotify);
            }
        } catch (SSLException e) {
            LOGGER.log(Level.WARNING, "Error sending DTLS close_notify", e);
        }
    }

    /**
     * Returns the cipher suite in use after handshake.
     */
    String getCipherSuite() {
        return handshakeComplete ? session.getCipherSuite() : null;
    }

}

