/*
 * TlsRecordState.java
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
import java.text.MessageFormat;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.tls.HandshakeAsyncOffload;
import org.bluezoo.gumdrop.tls.HandshakeConfig;
import org.bluezoo.gumdrop.tls.TlsProtocolError;
import org.bluezoo.gumdrop.tls.TlsRecordEngine;
import org.bluezoo.gumdrop.tls.TlsRecordSink;
import org.bluezoo.gumdrop.util.DirectByteBufferPool;

/**
 * Manages TLS 1.3 record-layer wrap/unwrap operations for a TCP
 * connection, driving an in-tree {@link TlsRecordEngine} -- the direct
 * replacement for the former JSSE-backed {@code SSLState}, playing the
 * exact same role relative to {@link TcpEndpoint}: same {@link Callback}
 * shape, same {@code netIn}/{@code netOut} buffer ownership (still
 * {@link TcpEndpoint}'s, accessed here the same way {@code SSLState}
 * accessed them). {@link TcpEndpoint#tlsEngineLock} serializes engine
 * access; {@link TcpEndpoint#netOutLock} guards {@code netOut} alone so
 * the selector loop can write ciphertext to the socket while decrypt runs.
 *
 * <p>Record-layer AEAD (wrap/unwrap) runs on the {@code SelectorLoop}
 * thread, but {@link TlsRecordEngine} offloads handshake start and message
 * processing to {@link CryptoExecutor} when {@code Gumdrop} is started --
 * the same model as QUIC and DTLS. Because the underlying engine is not
 * safe for concurrent access from more than one thread at a time, every
 * method that touches it -- {@link #wrap}, {@link #unwrap},
 * {@link #startClientHandshake}, {@link #closeOutbound} -- synchronizes on
 * {@link TcpEndpoint#tlsEngineLock}. Outbound buffer writes from
 * {@link #ciphertextReady} take {@link TcpEndpoint#netOutLock} only.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class TlsRecordState implements TlsRecordSink {

    private static final Logger LOGGER = Logger.getLogger(TlsRecordState.class.getName());

    private static final int DEFAULT_BUFFER_SIZE = 32768;

    /** Callback interface for TlsRecordState to communicate with TcpEndpoint -- mirrors the former SSLState.Callback exactly. */
    interface Callback {
        /** Called when decrypted application data is available. */
        void onApplicationData(ByteBuffer data);

        /**
         * Called when the TLS handshake completes.
         *
         * @param protocol the negotiated application protocol (e.g., "h2")
         */
        void onHandshakeComplete(String protocol);

        /** Called when the TLS connection is closed (e.g., close_notify received). */
        void onClosed();

        /** Called when the TLS stack detects a fatal protocol error. */
        void onProtocolError(TlsProtocolError error);

        /** Returns the remote socket address for logging. */
        Object getRemoteAddress();
    }

    private final TlsRecordEngine engine;
    private final TcpEndpoint tcpEndpoint;
    private final Callback callback;

    private boolean handshakeStarted;
    private boolean closed;

    // Mirrors SSLState's own pendingAppData field: an RFC 4217-style
    // "client speaks first" protocol can call wrap() with real
    // application data before the handshake has finished --
    // sendApplicationData() during a handshake does not consume
    // application bytes, so without buffering here that data would
    // simply vanish. Held until the handshake completes, then flushed
    // once via flushPendingAppData().
    private ByteBuffer pendingAppData;

    TlsRecordState(HandshakeConfig config, TcpEndpoint tcpEndpoint, Callback callback) {
        this.engine = new TlsRecordEngine(config, handshakeOffload(tcpEndpoint));
        this.tcpEndpoint = tcpEndpoint;
        this.callback = callback;
    }

    /**
     * Returns the wrapped engine, for reading negotiated state (cipher
     * suite, ALPN, peer certificate chain, resumption) once the
     * handshake completes.
     */
    TlsRecordEngine getEngine() {
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
        synchronized (tcpEndpoint.tlsEngineLock) {
            if (!handshakeStarted) {
                handshakeStarted = true;
                engine.start(this);
            }
        }
    }

    /**
     * Processes incoming encrypted data from {@code netIn}. Called by
     * {@link TcpEndpoint#processInbound} after data is appended. The
     * {@code netIn} buffer is in read mode (flipped).
     */
    void unwrap() {
        if (closed) {
            return;
        }
        try {
            synchronized (tcpEndpoint.tlsEngineLock) {
                ByteBuffer in = netIn();
                if (in == null) {
                    return;
                }
                feedCiphertextFromBuffer(in);
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
        synchronized (tcpEndpoint.tlsEngineLock) {
            synchronized (tcpEndpoint.netOutLock) {
                if (netOut() == null) {
                    return;
                }
            }
            // See the field comment on handshakeStarted -- mirrors
            // SSLState.wrap()'s own same guard, for the same
            // client-speaks-first reason.
            if (!handshakeStarted) {
                handshakeStarted = true;
                engine.start(this);
            }
            if (!engine.isComplete()) {
                bufferPendingAppData(data);
                return;
            }
            sendApplicationDataFromBuffer(data);
        }
    }

    private void feedCiphertextFromBuffer(ByteBuffer in) {
        if (in.hasArray()) {
            int offset = in.arrayOffset() + in.position();
            int length = in.remaining();
            engine.feedCiphertext(in.array(), offset, length, this);
            in.position(in.limit());
        } else {
            byte[] data = new byte[in.remaining()];
            in.get(data);
            engine.feedCiphertext(data, this);
        }
    }

    private void sendApplicationDataFromBuffer(ByteBuffer data) {
        if (data.hasArray()) {
            int offset = data.arrayOffset() + data.position();
            int length = data.remaining();
            engine.sendApplicationData(data.array(), offset, length, this);
            data.position(data.limit());
        } else {
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
        synchronized (tcpEndpoint.tlsEngineLock) {
            synchronized (tcpEndpoint.netOutLock) {
                if (netOut() == null) {
                    return;
                }
            }
            engine.sendCloseNotify(this);
        }
    }

    private void bufferPendingAppData(ByteBuffer data) {
        int needed = data.remaining();
        int cap = tcpEndpoint.getMaxNetOutSize();
        int current = pendingAppData != null ? pendingAppData.position() : 0;
        if (cap > 0 && current + needed > cap) {
            handleOverflow();
            return;
        }
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
        boolean overflow = false;
        synchronized (tcpEndpoint.netOutLock) {
            if (netOut() == null) {
                return;
            }
            if (!ensureNetOutCapacityOrOverflow(data.length)) {
                overflow = true;
            } else {
                netOut().put(data);
            }
        }
        if (overflow) {
            handleOverflow();
            return;
        }
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
            LOGGER.fine(MessageFormat.format(
                    Gumdrop.L10N.getString("log.tls_protocol_error"),
                    callback.getRemoteAddress(), error));
        }
        callback.onProtocolError(error);
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

    /**
     * Grows {@code netOut} to fit {@code needed} more bytes, the same
     * ceiling-enforced, pooled-buffer pattern {@code SSLState.growNetOut()}
     * used. Returns false (does not grow) if the peer is not draining and
     * the buffer would have to exceed {@link TcpEndpoint#getMaxNetOutSize()}.
     */
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
            LOGGER.warning(MessageFormat.format(
                    Gumdrop.L10N.getString("warn.tls_outbound_buffer_overflow"),
                    Integer.valueOf(tcpEndpoint.getMaxNetOutSize()),
                    callback.getRemoteAddress()));
        }
        handleClosed("outbound-overflow");
    }

    void handleClosed(String context) {
        if (closed) {
            return;
        }
        closed = true;
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    Gumdrop.L10N.getString("log.tls_closed_during"), context));
        }
        callback.onClosed();
    }

    private static HandshakeAsyncOffload handshakeOffload(final TcpEndpoint endpoint) {
        SelectorLoop loop = endpoint.getSelectorLoop();
        Gumdrop gumdrop = (loop != null) ? loop.getGumdrop() : null;
        return new TlsHandshakeAsyncOffload(loopExecutor(endpoint), gumdrop);
    }

    private static Executor loopExecutor(final TcpEndpoint endpoint) {
        return new Executor() {
            @Override
            public void execute(Runnable task) {
                endpoint.execute(task);
            }
        };
    }

}
