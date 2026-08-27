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
import org.bluezoo.gumdrop.util.ByteBufferPool;
import org.bluezoo.gumdrop.util.DirectByteBufferPool;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

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

    // Network I/O. Pooled (issue #193) rather than a fresh heap
    // allocation, both to reduce GC pressure and, being a genuinely
    // direct buffer, to avoid the JVM's internal bounce-copy through a
    // temporary direct buffer on every datagram read/write that a heap
    // buffer would otherwise force.
    ByteBuffer netIn;
    final Deque<PendingDatagram> pendingDatagrams =
            new ConcurrentLinkedDeque<PendingDatagram>();

    private boolean secure;
    private volatile boolean closing;

    /**
     * DTLS sessions keyed by peer address (issue #190). A single bound
     * datagram socket serves every peer in server mode, so unlike TCP/TLS
     * (one {@code SSLEngine} per connection) DTLS needs one session per
     * remote address here. Client mode only ever has one entry, keyed by
     * {@link #remoteAddress}. Unused (stays empty) when {@link #secure}
     * is false. Reads and writes all happen on this endpoint's own
     * SelectorLoop thread (as with everything else on {@code Endpoint}),
     * including timer callbacks -- see {@link Endpoint#scheduleTimer} --
     * so a plain {@link HashMap} is sufficient.
     */
    private final Map<InetSocketAddress, DTLSSession> dtlsSessions =
            new HashMap<InetSocketAddress, DTLSSession>();

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
        netIn = DirectByteBufferPool.acquire(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Initiates the DTLS handshake for a secure client-mode endpoint
     * (issue #190). Unlike a server, which waits passively for a
     * {@code ClientHello}, a DTLS client must send one proactively, so
     * this is called explicitly by {@link UDPTransportFactory#connect}
     * right after the endpoint is set up -- lazily creating the session
     * on first receive (as server mode does in {@link #netReceive}) would
     * never actually send anything.
     */
    void startClientDtlsHandshake() {
        if (secure && clientMode && remoteAddress != null) {
            getOrCreateDtlsSession(remoteAddress);
        }
    }

    /**
     * Returns the existing DTLS session for {@code peer}, or creates and
     * begins the handshake for a new one.
     */
    private DTLSSession getOrCreateDtlsSession(InetSocketAddress peer) {
        DTLSSession existing = dtlsSessions.get(peer);
        if (existing != null) {
            return existing;
        }
        SSLContext dtlsContext = ((UDPTransportFactory) factory).getDTLSContext();
        if (dtlsContext == null) {
            throw new IllegalStateException(
                    "Secure UDP endpoint has no DTLS context configured");
        }
        SSLEngine engine = dtlsContext.createSSLEngine(peer.getHostString(), peer.getPort());
        engine.setUseClientMode(clientMode);
        DTLSSession created = new DTLSSession(engine, this, peer);
        dtlsSessions.put(peer, created);
        created.beginHandshake();
        return created;
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
     * <p>For a secure endpoint (issue #190), {@code data} is treated as
     * plaintext and transparently DTLS-encrypted for {@code dest}'s
     * session before being put on the wire -- callers never handle DTLS
     * records directly. If the session for {@code dest} has not finished
     * its handshake yet (or has failed/closed), the data is dropped; the
     * {@code data} buffer is not retained after this call.
     *
     * @param data the datagram payload
     * @param dest the destination address
     */
    public void sendTo(ByteBuffer data, InetSocketAddress dest) {
        if (secure) {
            DTLSSession session = getOrCreateDtlsSession(dest);
            ByteBuffer encrypted = session.wrap(data);
            if (encrypted == null) {
                return;
            }
            try {
                sendRawDatagram(encrypted, dest);
            } finally {
                ByteBufferPool.release(encrypted);
            }
            return;
        }
        sendRawDatagram(data, dest);
    }

    /**
     * Queues a datagram for the wire exactly as given, with no DTLS
     * involvement -- used both for plaintext endpoints and internally by
     * {@link DTLSSession} to send already-encrypted records (handshake
     * flights, application data, {@code close_notify}). Never call this
     * directly with plaintext on a secure endpoint; use {@link #sendTo}.
     */
    void sendRawDatagram(ByteBuffer data, InetSocketAddress dest) {
        ByteBuffer copy = ByteBufferPool.acquire(data.remaining());
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

        if (secure) {
            // Copy first: DTLSSession.close() calls back into
            // removeDtlsSession(), which would otherwise mutate
            // dtlsSessions while this loop is iterating it.
            for (DTLSSession dtlsSession
                    : new ArrayList<DTLSSession>(dtlsSessions.values())) {
                dtlsSession.close();
            }
        }

        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                String message = MessageFormat.format(
                        Gumdrop.L10N.getString("err.close"), "datagram channel");
                LOGGER.log(Level.WARNING, message, e);
            }
        }
        if (key != null) {
            key.cancel();
        }

        Gumdrop gumdrop = Gumdrop.getInstance();
        if (gumdrop != null) {
            gumdrop.removeChannelHandler(this);
        }

        if (netIn != null) {
            DirectByteBufferPool.release(netIn);
            netIn = null;
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
        if (secure && remoteAddress != null) {
            DTLSSession session = dtlsSessions.get(remoteAddress);
            if (session != null) {
                SecurityInfo info = session.getSecurityInfo();
                if (info != null) {
                    return info;
                }
            }
        }
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
    public void execute(Runnable task) {
        selectorLoop.invokeLater(task);
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

    // -- Flow control (not supported for datagrams) --

    @Override
    public void pauseRead() {
        throw new UnsupportedOperationException(
                "Flow control not supported on datagram endpoints");
    }

    @Override
    public void resumeRead() {
        throw new UnsupportedOperationException(
                "Flow control not supported on datagram endpoints");
    }

    @Override
    public void onWriteReady(Runnable callback) {
        throw new UnsupportedOperationException(
                "Flow control not supported on datagram endpoints");
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
        if (!clientMode) {
            // For server mode, set the source so the handler can reply
            remoteAddress = source;
        }

        if (secure) {
            DTLSSession session = getOrCreateDtlsSession(source);
            ByteBuffer plaintext = session.unwrap(data);
            if (plaintext != null) {
                // A DTLS datagram is one complete, self-contained record;
                // unlike a TCP byte stream there is no partial-message
                // carry-over for the handler to leave unconsumed, so this
                // pooled buffer's lifetime ends when receive() returns.
                try {
                    handler.receive(plaintext);
                } finally {
                    ByteBufferPool.release(plaintext);
                }
            }
            // plaintext == null: handshake still in progress (any
            // handshake response has already been sent by DTLSSession
            // itself), or the record carried no application data.
            return;
        }

        handler.receive(data);
    }

    /**
     * Called by {@link DTLSSession} once its handshake completes.
     */
    void notifyDtlsHandshakeComplete(InetSocketAddress peer, SecurityInfo info) {
        handler.securityEstablished(info);
    }

    /**
     * Delivers already-decrypted application data to the handler.
     *
     * <p>Used by {@link DTLSSession#drainPendingIncoming} when app data
     * surfaces while replaying datagrams that queued up behind an
     * in-flight delegated task (issue #274); the normal {@link #netReceive}
     * path delivers directly instead.
     */
    void deliverPlaintext(ByteBuffer plaintext) {
        try {
            handler.receive(plaintext);
        } finally {
            ByteBufferPool.release(plaintext);
        }
    }

    /**
     * Called by {@link DTLSSession} when its handshake fails permanently
     * (e.g. retransmit attempts exhausted). In client mode -- where the
     * endpoint has exactly one peer -- this is fatal to the endpoint and
     * surfaces as {@link ProtocolHandler#error}. In server mode, a single
     * misbehaving/unreachable peer must not take down the listener for
     * every other peer it's serving, so the failure is only logged and
     * that peer's session is dropped.
     */
    void onDtlsSessionFailed(InetSocketAddress peer, Exception cause) {
        dtlsSessions.remove(peer);
        if (clientMode) {
            handler.error(cause);
        } else if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.log(Level.WARNING, cause.getMessage(), cause);
        }
    }

    /**
     * Called by {@link DTLSSession} once it is closed (either a normal
     * {@code close_notify} exchange or after {@link #onDtlsSessionFailed}),
     * so the endpoint stops tracking it.
     */
    void removeDtlsSession(InetSocketAddress peer) {
        dtlsSessions.remove(peer);
    }

    /**
     * Returns the handler for this endpoint.
     */
    ProtocolHandler getHandler() {
        return handler;
    }
}
