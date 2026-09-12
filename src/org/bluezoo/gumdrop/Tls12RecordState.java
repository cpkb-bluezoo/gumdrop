/*
 * Tls12RecordState.java
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

import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.tls.Tls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.Tls12RecordEngine;
import org.bluezoo.gumdrop.tls.TlsProtocolError;
import org.bluezoo.gumdrop.tls.TlsRecordSink;
import org.bluezoo.gumdrop.util.DirectByteBufferPool;

/**
 * Manages TLS 1.2 record-layer wrap/unwrap operations for a TCP
 * connection, driving an in-tree {@link Tls12RecordEngine} -- the TLS 1.2
 * sibling of {@link TlsRecordState}, playing the exact same role relative
 * to {@link TCPEndpoint}: same {@link TlsRecordState.Callback} shape
 * (reused unchanged -- it has no TLS-1.3-specific coupling), same
 * {@code netIn}/{@code netOut} buffer ownership, same {@code netOutLock}
 * discipline.
 *
 * <p>Implements the same {@link TlsRecordSink} interface
 * {@link TlsRecordState} does, unchanged -- {@link Tls12RecordEngine}
 * produces the identical event shape {@link org.bluezoo.gumdrop.tls.TlsRecordEngine}
 * does.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class Tls12RecordState implements TlsRecordSink {

    private static final Logger LOGGER = Logger.getLogger(Tls12RecordState.class.getName());

    private static final int DEFAULT_BUFFER_SIZE = 32768;

    private final Tls12RecordEngine engine;
    private final TCPEndpoint tcpEndpoint;
    private final TlsRecordState.Callback callback;

    private boolean handshakeStarted;
    private boolean closed;

    // See TlsRecordState.pendingAppData's doc comment -- identical reasoning.
    private ByteBuffer pendingAppData;

    Tls12RecordState(Tls12HandshakeConfig config, TCPEndpoint tcpEndpoint, TlsRecordState.Callback callback) {
        this.engine = new Tls12RecordEngine(config);
        this.tcpEndpoint = tcpEndpoint;
        this.callback = callback;
    }

    /**
     * Returns the wrapped engine, for reading negotiated state (cipher
     * suite, ALPN, peer certificate chain, resumption) once the
     * handshake completes.
     */
    Tls12RecordEngine getEngine() {
        return engine;
    }

    /**
     * Returns the application buffer size for connection configuration.
     */
    int getBufferSize() {
        return DEFAULT_BUFFER_SIZE;
    }

    /**
     * Initiates the TLS handshake for client connections. Only call this
     * for client connections -- server connections wait for the
     * ClientHello to arrive via normal data flow.
     */
    void startClientHandshake() {
        if (closed) {
            return;
        }
        synchronized (tcpEndpoint.netOutLock) {
            if (!handshakeStarted) {
                handshakeStarted = true;
                engine.start(this);
            }
        }
    }

    /**
     * Processes incoming encrypted data from {@code netIn}. Called by
     * {@link TCPEndpoint#processInbound} after data is appended. The
     * {@code netIn} buffer is in read mode (flipped).
     */
    void unwrap() {
        if (closed) {
            return;
        }
        try {
            synchronized (tcpEndpoint.netOutLock) {
                ByteBuffer in = netIn();
                if (in == null) {
                    return;
                }
                byte[] data = new byte[in.remaining()];
                in.get(data);
                engine.feedCiphertext(data, this);
            }
        } finally {
            ByteBuffer in = netIn();
            if (in != null) {
                in.compact();
            }
        }
    }

    /**
     * Encrypts and sends application data. Writes encrypted output
     * directly to {@code tcpEndpoint.netOut}.
     */
    void wrap(ByteBuffer data) {
        if (closed) {
            return;
        }
        synchronized (tcpEndpoint.netOutLock) {
            if (netOut() == null) {
                return;
            }
            if (!handshakeStarted) {
                handshakeStarted = true;
                engine.start(this);
            }
            if (!engine.isComplete()) {
                bufferPendingAppData(data);
                return;
            }
            byte[] plaintext = new byte[data.remaining()];
            data.get(plaintext);
            engine.sendApplicationData(plaintext, this);
        }
    }

    /**
     * Initiates a graceful close with {@code close_notify}. Writes it to
     * {@code tcpEndpoint.netOut}.
     */
    void closeOutbound() {
        if (closed) {
            return;
        }
        closed = true;
        synchronized (tcpEndpoint.netOutLock) {
            if (netOut() == null) {
                return;
            }
            engine.sendCloseNotify(this);
        }
    }

    private void bufferPendingAppData(ByteBuffer data) {
        int needed = data.remaining();
        if (pendingAppData == null) {
            pendingAppData = ByteBuffer.allocate(Math.max(needed, DEFAULT_BUFFER_SIZE));
        } else if (pendingAppData.remaining() < needed) {
            ByteBuffer grown = ByteBuffer.allocate(pendingAppData.position() + needed);
            pendingAppData.flip();
            grown.put(pendingAppData);
            pendingAppData = grown;
        }
        pendingAppData.put(data);
    }

    private void flushPendingAppData() {
        if (pendingAppData == null || pendingAppData.position() == 0) {
            return;
        }
        ByteBuffer toSend = pendingAppData;
        pendingAppData = null;
        toSend.flip();
        wrap(toSend);
    }

    // ---- TlsRecordSink implementation ----

    @Override
    public void ciphertextReady(byte[] data) {
        if (netOut() == null) {
            return;
        }
        if (!ensureNetOutCapacityOrOverflow(data.length)) {
            handleOverflow();
            return;
        }
        netOut().put(data);
        requestWrite();
    }

    @Override
    public void applicationDataReady(byte[] plaintext) {
        callback.onApplicationData(ByteBuffer.wrap(plaintext));
    }

    @Override
    public void handshakeComplete() {
        flushPendingAppData();
        callback.onHandshakeComplete(engine.getNegotiatedApplicationProtocol());
    }

    @Override
    public void protocolError(TlsProtocolError error) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("TLS protocol error from " + callback.getRemoteAddress() + ": " + error);
        }
        handleClosed("protocol-error");
    }

    @Override
    public void peerClosed() {
        handleClosed("peer-closed");
    }

    // ---- helpers ----

    private ByteBuffer netIn() {
        return tcpEndpoint.netIn;
    }

    private ByteBuffer netOut() {
        return tcpEndpoint.netOut;
    }

    private void setNetOut(ByteBuffer buf) {
        tcpEndpoint.netOut = buf;
    }

    private void requestWrite() {
        SelectorLoop loop = tcpEndpoint.getSelectorLoop();
        if (loop != null) {
            loop.requestWrite(tcpEndpoint);
        }
    }

    private boolean ensureNetOutCapacityOrOverflow(int needed) {
        ByteBuffer out = netOut();
        if (out.remaining() >= needed) {
            return true;
        }
        int required = out.position() + needed;
        int cap = tcpEndpoint.getMaxNetOutSize();
        if (cap > 0 && required > cap) {
            return false;
        }
        int desired = Math.max(out.capacity() * 2, required);
        if (cap > 0 && desired > cap) {
            desired = cap;
        }
        ByteBuffer newBuf = DirectByteBufferPool.acquire(desired);
        out.flip();
        newBuf.put(out);
        DirectByteBufferPool.release(out);
        setNetOut(newBuf);
        return true;
    }

    private void handleOverflow() {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning("Outbound TLS buffer exceeded maximum size ("
                    + tcpEndpoint.getMaxNetOutSize() + " bytes); peer not reading: " + callback.getRemoteAddress());
        }
        handleClosed("outbound-overflow");
    }

    void handleClosed(String context) {
        if (closed) {
            return;
        }
        closed = true;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("TLS closed during " + context);
        }
        callback.onClosed();
    }

}
