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

import org.bluezoo.gumdrop.tls.Dtls12HandshakeConfig;
import org.bluezoo.gumdrop.tls.Dtls13HandshakeConfig;
import org.bluezoo.gumdrop.tls.DtlsVersion;

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
 * <p>For DTLS, encryption and decryption use the in-tree DTLS engine
 * ({@link Dtls12Session} or {@link Dtls13Session}, selected by
 * {@link UDPTransportFactory#getDtlsVersion()}).
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
    private int pendingDatagramBytes;

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
    private final Map<InetSocketAddress, Dtls12Session> dtlsSessions =
            new HashMap<InetSocketAddress, Dtls12Session>();

    private final Map<InetSocketAddress, Dtls13Session> dtls13Sessions =
            new HashMap<InetSocketAddress, Dtls13Session>();

    private boolean usesDtls13;

    private Trace trace;

    /** Accepting listener for admission control (secure server mode). */
    private Listener listener;

    /**
     * A pending datagram waiting to be sent.
     */
    static final class PendingDatagram {
        final ByteBuffer data;
        final InetSocketAddress destination;
        /** {@link ByteBuffer#remaining()} at enqueue time, for queue accounting. */
        final int queuedBytes;

        PendingDatagram(ByteBuffer data, InetSocketAddress destination) {
            this.data = data;
            this.destination = destination;
            this.queuedBytes = data.remaining();
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
        if (factory instanceof UDPTransportFactory) {
            usesDtls13 = ((UDPTransportFactory) factory).getDtlsVersion()
                    == DtlsVersion.DTLS_1_3;
        }
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

    /**
     * Associates this server-mode endpoint with its accepting listener for
     * CIDR, rate-limit, and connection-cap admission before new DTLS sessions
     * are created.
     */
    void setListener(Listener listener) {
        this.listener = listener;
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
            if (usesDtls13) {
                if (getOrCreateDtls13Session(remoteAddress) == null) {
                    handler.error(new IOException("DTLS session refused"));
                }
            } else {
                if (getOrCreateDtls12Session(remoteAddress) == null) {
                    handler.error(new IOException("DTLS session refused"));
                }
            }
        }
    }

    private Dtls12Session getOrCreateDtls12Session(InetSocketAddress peer) {
        Dtls12Session existing = dtlsSessions.get(peer);
        if (existing != null) {
            return existing;
        }
        if (!admitNewDtlsPeer(peer)) {
            return null;
        }
        UDPTransportFactory udpFactory = (UDPTransportFactory) factory;
        Dtls12HandshakeConfig config;
        if (clientMode) {
            config = udpFactory.buildClientConfig12(
                    TCPTransportFactory.tlsServerNameFor(peer.getAddress(), null));
        } else {
            config = udpFactory.getSharedServerConfig();
            if (config == null) {
                throw new IllegalStateException(
                        "Secure UDP server endpoint has no DTLS configuration");
            }
        }
        Dtls12Session created = new Dtls12Session(config, this, peer);
        dtlsSessions.put(peer, created);
        notifyDtlsSessionOpened(peer);
        created.beginHandshake();
        return created;
    }

    private Dtls13Session getOrCreateDtls13Session(InetSocketAddress peer) {
        Dtls13Session existing = dtls13Sessions.get(peer);
        if (existing != null) {
            return existing;
        }
        if (!admitNewDtlsPeer(peer)) {
            return null;
        }
        UDPTransportFactory udpFactory = (UDPTransportFactory) factory;
        Dtls13HandshakeConfig config;
        if (clientMode) {
            config = udpFactory.buildClientConfig13(
                    TCPTransportFactory.tlsServerNameFor(peer.getAddress(), null));
        } else {
            config = udpFactory.getSharedServerConfig13();
            if (config == null) {
                throw new IllegalStateException(
                        "Secure UDP server endpoint has no DTLS configuration");
            }
        }
        Dtls13Session created = new Dtls13Session(config, this, peer);
        dtls13Sessions.put(peer, created);
        notifyDtlsSessionOpened(peer);
        created.beginHandshake();
        return created;
    }

    private boolean admitNewDtlsPeer(InetSocketAddress peer) {
        if (!canAcceptNewDtlsSession()) {
            return false;
        }
        if (listener != null && !clientMode) {
            if (!listener.acceptConnection(peer)) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("DTLS session rejected for " + peer);
                }
                return false;
            }
        }
        return true;
    }

    private void notifyDtlsSessionOpened(InetSocketAddress peer) {
        if (listener != null && !clientMode) {
            listener.connectionOpened(peer);
        }
    }

    private void notifyDtlsSessionClosed(InetSocketAddress peer) {
        if (listener != null && !clientMode) {
            listener.connectionClosed(peer);
        }
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
            if (usesDtls13) {
                Dtls13Session session = dtls13Sessions.get(dest);
                if (session == null || !session.isHandshakeComplete()) {
                    return;
                }
                if (data.hasArray()) {
                    session.sendApplicationData(data.array(),
                            data.arrayOffset() + data.position(), data.remaining());
                } else {
                    byte[] plaintext = new byte[data.remaining()];
                    data.get(plaintext);
                    session.sendApplicationData(plaintext);
                }
                return;
            }
            Dtls12Session session = dtlsSessions.get(dest);
            if (session == null || !session.isHandshakeComplete()) {
                return;
            }
            if (data.hasArray()) {
                session.sendApplicationData(data.array(),
                        data.arrayOffset() + data.position(), data.remaining());
            } else {
                byte[] plaintext = new byte[data.remaining()];
                data.get(plaintext);
                session.sendApplicationData(plaintext);
            }
            return;
        }
        sendRawDatagram(data, dest);
    }

    /**
     * Queues an already-encrypted datagram for the wire, taking ownership
     * of {@code data}. The buffer is released back to
     * {@link org.bluezoo.gumdrop.util.ByteBufferPool} once fully written.
     * Callers must not use or release {@code data} after this call.
     */
    void sendOwnedRawDatagram(ByteBuffer data, InetSocketAddress dest) {
        if (!enqueuePendingDatagram(data, dest)) {
            return;
        }
        if (selectorLoop != null) {
            selectorLoop.requestDatagramWrite(this);
        }
    }

    /**
     * Queues a datagram for the wire exactly as given, with no DTLS
     * involvement -- used both for plaintext endpoints and internally by
     * {@link Dtls12Session} to send already-encrypted records (handshake
     * flights, application data, {@code close_notify}). Never call this
     * directly with plaintext on a secure endpoint; use {@link #sendTo}.
     *
     * <p>Makes a defensive copy of {@code data} because callers may reuse
     * or retain the source buffer. For a freshly allocated buffer that
     * the caller is handing off, use {@link #sendOwnedRawDatagram}.
     */
    void sendRawDatagram(ByteBuffer data, InetSocketAddress dest) {
        ByteBuffer copy = ByteBufferPool.acquire(data.remaining());
        copy.put(data);
        copy.flip();
        if (!enqueuePendingDatagram(copy, dest)) {
            return;
        }
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
            if (usesDtls13) {
                for (Dtls13Session dtlsSession
                        : new ArrayList<Dtls13Session>(dtls13Sessions.values())) {
                    dtlsSession.close();
                }
            } else {
                // Copy first: Dtls12Session.close() calls back into
                // removeDtlsSession(), which would otherwise mutate
                // dtlsSessions while this loop is iterating it.
                for (Dtls12Session dtlsSession
                        : new ArrayList<Dtls12Session>(dtlsSessions.values())) {
                    dtlsSession.close();
                }
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

        drainPendingDatagrams();

        handler.disconnected();
    }

    /**
     * Called by {@link SelectorLoop} once a queued datagram has been fully
     * written and its buffer can return to {@link ByteBufferPool}.
     */
    void onPendingDatagramFullySent(PendingDatagram pending) {
        pendingDatagramBytes -= pending.queuedBytes;
        ByteBufferPool.release(pending.data);
    }

    private void drainPendingDatagrams() {
        PendingDatagram pending;
        while ((pending = pendingDatagrams.poll()) != null) {
            ByteBufferPool.release(pending.data);
        }
        pendingDatagramBytes = 0;
    }

    boolean enqueuePendingDatagram(ByteBuffer data, InetSocketAddress dest) {
        int bytes = data.remaining();
        int cap = getMaxNetOutSize();
        if (cap > 0 && pendingDatagramBytes + bytes > cap) {
            ByteBufferPool.release(data);
            handlePendingDatagramOverflow();
            return false;
        }
        pendingDatagramBytes += bytes;
        pendingDatagrams.add(new PendingDatagram(data, dest));
        return true;
    }

    private int getMaxNetOutSize() {
        return factory != null ? factory.getMaxNetOutSize() : 0;
    }

    private void handlePendingDatagramOverflow() {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning(MessageFormat.format(
                    Gumdrop.L10N.getString("warn.outbound_buffer_overflow"),
                    Integer.valueOf(getMaxNetOutSize()), getRemoteAddress()));
        }
        close();
    }

    private boolean canAcceptNewDtlsSession() {
        if (clientMode) {
            return true;
        }
        if (!(factory instanceof UDPTransportFactory)) {
            return true;
        }
        int cap = ((UDPTransportFactory) factory).getMaxDtlsPeers();
        if (cap <= 0) {
            return true;
        }
        if (dtlsSessions.size() + dtls13Sessions.size() >= cap) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("DTLS peer limit reached (" + cap
                        + "); refusing new session");
            }
            return false;
        }
        return true;
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
            if (usesDtls13) {
                Dtls13Session session = dtls13Sessions.get(remoteAddress);
                if (session != null) {
                    SecurityInfo info = session.getSecurityInfo();
                    if (info != null) {
                        return info;
                    }
                }
            } else {
                Dtls12Session session = dtlsSessions.get(remoteAddress);
                if (session != null) {
                    SecurityInfo info = session.getSecurityInfo();
                    if (info != null) {
                        return info;
                    }
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
            if (usesDtls13) {
                Dtls13Session session = getOrCreateDtls13Session(source);
                if (session == null) {
                    return;
                }
                if (data.hasArray()) {
                    session.receive(data.array(), data.arrayOffset() + data.position(), data.remaining());
                } else {
                    byte[] datagram = new byte[data.remaining()];
                    data.get(datagram);
                    session.receive(datagram);
                }
            } else {
                Dtls12Session session = getOrCreateDtls12Session(source);
                if (session == null) {
                    return;
                }
                if (data.hasArray()) {
                    session.receive(data.array(), data.arrayOffset() + data.position(), data.remaining());
                } else {
                    byte[] datagram = new byte[data.remaining()];
                    data.get(datagram);
                    session.receive(datagram);
                }
            }
            return;
        }

        handler.receive(data);
    }

    /**
     * Called by {@link Dtls12Session} once its handshake completes.
     */
    void notifyDtlsHandshakeComplete(InetSocketAddress peer, SecurityInfo info) {
        handler.securityEstablished(info);
    }

    /**
     * Delivers already-decrypted application data to the handler.
     *
     * Used by {@link Dtls12Session} when app data surfaces from the record engine.
     */
    void deliverPlaintext(ByteBuffer plaintext) {
        try {
            handler.receive(plaintext);
        } finally {
            ByteBufferPool.release(plaintext);
        }
    }

    /**
     * Called by {@link Dtls12Session} when its handshake fails permanently
     * (e.g. retransmit attempts exhausted). In client mode -- where the
     * endpoint has exactly one peer -- this is fatal to the endpoint and
     * surfaces as {@link ProtocolHandler#error}. In server mode, a single
     * misbehaving/unreachable peer must not take down the listener for
     * every other peer it's serving, so the failure is only logged and
     * that peer's session is dropped.
     */
    void onDtlsSessionFailed(InetSocketAddress peer, Exception cause) {
        removeDtlsSession(peer);
        onDtlsFailure(peer, cause);
    }

    void onDtls13SessionFailed(InetSocketAddress peer, Exception cause) {
        removeDtls13Session(peer);
        onDtlsFailure(peer, cause);
    }

    private void onDtlsFailure(InetSocketAddress peer, Exception cause) {
        if (clientMode) {
            handler.error(cause);
        } else if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.log(Level.WARNING, cause.getMessage(), cause);
        }
    }

    /**
     * Called by {@link Dtls12Session} once it is closed (either a normal
     * {@code close_notify} exchange or after {@link #onDtlsSessionFailed}),
     * so the endpoint stops tracking it.
     */
    void removeDtlsSession(InetSocketAddress peer) {
        if (dtlsSessions.remove(peer) != null) {
            notifyDtlsSessionClosed(peer);
        }
    }

    void removeDtls13Session(InetSocketAddress peer) {
        if (dtls13Sessions.remove(peer) != null) {
            notifyDtlsSessionClosed(peer);
        }
    }

    /**
     * Returns the handler for this endpoint.
     */
    ProtocolHandler getHandler() {
        return handler;
    }
}
