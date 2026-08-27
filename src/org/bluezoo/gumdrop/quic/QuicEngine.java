/*
 * QuicEngine.java
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

package org.bluezoo.gumdrop.quic;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;

import tech.kwik.agent15.NewSessionTicket;
import tech.kwik.agent15.engine.TlsServerEngineFactory;

import org.bluezoo.gumdrop.ChannelHandler;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.MultiplexedEndpoint;
import org.bluezoo.gumdrop.NullSecurityInfo;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.ratelimit.RateLimiter;
import org.bluezoo.gumdrop.quic.cid.StatelessResetToken;
import org.bluezoo.gumdrop.quic.packet.LongHeaderCodec;
import org.bluezoo.gumdrop.quic.packet.LongHeaderPrefix;
import org.bluezoo.gumdrop.quic.packet.RetryIntegrityTag;
import org.bluezoo.gumdrop.quic.packet.RetryToken;
import org.bluezoo.gumdrop.quic.packet.StatelessResetPacket;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine;
import org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine;
import org.bluezoo.util.ByteArrays;

/**
 * One UDP socket multiplexing many {@link QuicConnection}s, the
 * pure-Java replacement for the native quiche-backed implementation.
 *
 * <p>Demultiplexes received datagrams by destination connection ID
 * (parsed via {@link LongHeaderCodec}/fixed-length short-header
 * assumption -- see {@link #CONNECTION_ID_LENGTH}) instead of
 * {@code quiche_header_info}, and accepts new server-side connections by
 * constructing a {@link QuicTlsServerEngine} + {@link QuicConnection}
 * pair directly instead of {@code quiche_conn_new_with_tls}.
 *
 * <p>Sending is synchronous and immediate ({@link #requestFlush} calls
 * {@link QuicConnection#flush} directly rather than deferring to
 * {@code OP_WRITE}) -- simple and correct, at the cost of not batching
 * multiple small sends into fewer packets (packet coalescing is
 * deferred anyway, see {@link QuicConnection}'s documentation). A
 * datagram send that the OS declines because its own send buffer is
 * momentarily full is treated exactly like ordinary network loss: this
 * engine does not retry it, relying on {@link org.bluezoo.gumdrop.quic.recovery.LossDetector}'s
 * retransmission to recover it, the same as any other dropped packet.
 *
 * <p>Only QUIC version 1 is supported -- an unrecognised version is
 * silently dropped rather than answered with a Version Negotiation
 * packet (RFC 9000 section 6).
 *
     * <p>When {@link QuicTransportFactory#isRequireRetry} is set (the
     * default on {@code HTTP3Listener} and {@code DoQListener}), a new
     * client Initial with no valid Retry Token is answered with a stateless
     * Retry packet (RFC 9000 section 8.1.2) instead of being accepted --
 * see {@link #sendRetry} and {@link RetryToken}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QuicEngine implements ChannelHandler, MultiplexedEndpoint {

    private static final Logger LOGGER = Logger.getLogger(QuicEngine.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.quic.L10N");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** RFC 9000 section 5.1: the fixed length this implementation uses for every connection ID it generates. */
    static final int CONNECTION_ID_LENGTH = 20;

    /** How long a Retry Token remains valid, bounding replay of a captured token. */
    private static final long RETRY_TOKEN_MAX_AGE_MILLIS = 30_000;

    /** Fallback TTL for stateless reset eligibility when max_idle_timeout is zero. */
    private static final long RESET_ELIGIBLE_FALLBACK_MS = 30_000;

    /** Per-source-address cap on outbound stateless resets (RFC 9000 section 10.3.3). */
    private static final int RESET_RATE_LIMIT_MAX = 10;
    private static final long RESET_RATE_LIMIT_WINDOW_MS = 1_000;

    private final QuicTransportFactory factory;
    private final boolean serverMode;
    private final byte[] connectionIdStaticKey;

    private DatagramChannel channel;
    private SelectionKey selectionKey;
    private SelectorLoop selectorLoop;
    private ByteBuffer recvBuf;

    private final Map<String, QuicConnection> connections = new HashMap<String, QuicConnection>();
    private final Map<String, Long> resetEligibleUntil = new HashMap<String, Long>();
    private final Map<String, RateLimiter> resetRateLimiters = new HashMap<String, RateLimiter>();
    private QuicConnection clientConnection;

    private StreamAcceptHandler streamAcceptHandler;
    private ConnectionAcceptedHandler connectionAcceptedHandler;
    private Trace trace;
    private boolean closing;

    QuicEngine(QuicTransportFactory factory, boolean serverMode) {
        this.factory = factory;
        this.serverMode = serverMode;
        this.connectionIdStaticKey = factory.getConnectionIdStaticKey();
    }

    void init(DatagramChannel channel) {
        this.channel = channel;
        this.recvBuf = ByteBuffer.allocateDirect(65535);
    }

    QuicTransportFactory getFactory() {
        return factory;
    }

    /**
     * Called when a new QUIC connection has been accepted (server) or a
     * client connection's handshake has completed (client).
     */
    public interface ConnectionAcceptedHandler {

        /**
         * @param connection the newly accepted or completed connection
         */
        void connectionAccepted(QuicConnection connection);
    }

    /**
     * Client-only: called once 0-RTT send keys become available for a
     * connection attempt (RFC 9001 section 4.6.1) -- well before the
     * handshake otherwise completes -- so the caller can open a stream
     * and queue eligible data immediately. Only fires when {@link
     * QuicTransportFactory#isEarlyDataEnabled()} is set and a cached
     * session ticket was found for the destination (see {@link
     * SessionTicketCache}); never fires otherwise.
     */
    public interface EarlyDataHandler {

        /**
         * @param connection the connection now able to send 0-RTT data
         */
        void earlyDataReady(QuicConnection connection);
    }

    @Override
    public void setStreamAcceptHandler(StreamAcceptHandler handler) {
        this.streamAcceptHandler = handler;
        for (QuicConnection conn : connections.values()) {
            conn.setStreamAcceptHandler(handler);
        }
    }

    /**
     * Registers a handler to be notified when a new connection is
     * accepted (server) or a client connection's handshake completes.
     *
     * @param handler the handler
     */
    public void setConnectionAcceptedHandler(ConnectionAcceptedHandler handler) {
        this.connectionAcceptedHandler = handler;
    }

    // ── ChannelHandler ──

    @Override
    public Type getChannelType() {
        return Type.QUIC;
    }

    @Override
    public SelectionKey getSelectionKey() {
        return selectionKey;
    }

    @Override
    public void setSelectionKey(SelectionKey key) {
        this.selectionKey = key;
    }

    @Override
    public SelectorLoop getSelectorLoop() {
        return selectorLoop;
    }

    @Override
    public void setSelectorLoop(SelectorLoop loop) {
        this.selectorLoop = loop;
    }

    // ── Datagram I/O ──

    /**
     * Called by {@link SelectorLoop} when the underlying socket has a
     * datagram ready to read.
     */
    public void onReadable() {
        recvBuf.clear();
        InetSocketAddress source;
        try {
            source = (InetSocketAddress) channel.receive(recvBuf);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.recv_error"), e);
            return;
        }
        if (source == null) {
            return;
        }
        recvBuf.flip();
        if (!recvBuf.hasRemaining()) {
            return;
        }
        byte[] bytes = new byte[recvBuf.remaining()];
        recvBuf.get(bytes);

        boolean longHeader = (bytes[0] & 0x80) != 0;
        byte[] dcid;
        LongHeaderPrefix prefix = null;
        if (longHeader) {
            try {
                prefix = LongHeaderCodec.parsePrefix(bytes);
            } catch (IllegalArgumentException e) {
                return;
            }
            if (prefix.getVersion() != 1) {
                return; // only QUIC v1 is supported; see the class documentation
            }
            dcid = prefix.getDestinationConnectionId();
        } else {
            if (bytes.length < 1 + CONNECTION_ID_LENGTH) {
                return;
            }
            dcid = java.util.Arrays.copyOfRange(bytes, 1, 1 + CONNECTION_ID_LENGTH);
        }

        String key = ByteArrays.toHexString(dcid);
        QuicConnection conn = connections.get(key);
        if (conn == null) {
            if (serverMode && prefix != null && prefix.getPacketType() == LongHeaderCodec.TYPE_INITIAL) {
                if (factory.isRequireRetry()) {
                    byte[] originalDcid = null;
                    byte[] token = prefix.getToken();
                    if (token.length > 0) {
                        originalDcid = RetryToken.unseal(factory.getRetryTokenKey(), token, source.getAddress(),
                                RETRY_TOKEN_MAX_AGE_MILLIS);
                    }
                    if (originalDcid == null) {
                        sendRetry(prefix.getSourceConnectionId(), dcid, source);
                        return;
                    }
                    conn = acceptConnection(dcid, prefix.getSourceConnectionId(), source, originalDcid, dcid, true);
                } else {
                    conn = acceptConnection(dcid, prefix.getSourceConnectionId(), source, dcid, null, false);
                }
                if (conn == null) {
                    return;
                }
            } else {
                if (tryHandleStatelessResetForPeer(bytes, source)) {
                    return;
                }
                trySendStatelessReset(dcid, bytes, source);
                return;
            }
        }
        conn.receive(ByteBuffer.wrap(bytes), source);
        if (conn.isClosed() && clientConnection == conn) {
            clientConnection = null;
        }
    }

    /**
     * Called by {@link SelectorLoop} when the socket is writable again
     * -- a no-op, since sends happen synchronously and immediately (see
     * the class documentation); nothing is ever left queued waiting for
     * this.
     */
    public void onWritable() {
    }

    /**
     * Accepts a new server-side connection from a client's Initial packet.
     *
     * @param clientDcid the Destination Connection ID of the Initial
     *        packet that triggered this accept -- for a post-Retry
     *        Initial, this is the connection ID the server chose as the
     *        Retry packet's Source Connection ID
     * @param clientScid the client's Source Connection ID
     * @param source the client's address
     * @param originalDcidForParams the value to advertise as
     *        {@code original_destination_connection_id}: the client's
     *        very first (pre-Retry) Initial packet's Destination
     *        Connection ID, or {@code clientDcid} itself when no Retry
     *        occurred
     * @param retrySourceConnectionId the value to advertise as
     *        {@code retry_source_connection_id}, or {@code null} if this
     *        connection did not follow a Retry
     * @param addressValidated whether the peer's address is already
     *        considered validated (a validated Retry token proves it
     *        without a Handshake round trip)
     * @return the new connection, or {@code null} if no server
     *         certificate is configured
     */
    private QuicConnection acceptConnection(byte[] clientDcid, byte[] clientScid, InetSocketAddress source,
            byte[] originalDcidForParams, byte[] retrySourceConnectionId, boolean addressValidated) {
        // Following a Retry, this connection's own connection ID must be
        // the one already committed to in the Retry packet's Source
        // Connection ID field (== clientDcid, since the client echoes it
        // back as its post-Retry Initial's own DCID) -- the client
        // learned that ID as its peer's connection ID from the Retry
        // itself and, being stateless, won't be told a different one
        // now, so minting a fresh random ID here would leave the client
        // addressing every future packet to an ID this connection was
        // never actually registered under.
        byte[] serverScid = retrySourceConnectionId != null ? retrySourceConnectionId : generateConnectionId();
        InetSocketAddress local = getLocalSocketAddress();
        TransportParameters localParams = factory.buildTransportParameters(serverScid, true);
        localParams.setOriginalDestinationConnectionId(originalDcidForParams);
        if (retrySourceConnectionId != null) {
            localParams.setRetrySourceConnectionId(retrySourceConnectionId);
        }

        QuicConnection conn = new QuicConnection(this, true, local, source, serverScid, clientScid, clientDcid,
                localParams, connectionIdStaticKey);
        if (addressValidated) {
            conn.markAddressValidated();
        }
        TlsServerEngineFactory engineFactory = factory.getServerEngineFactory();
        if (engineFactory == null) {
            LOGGER.warning(L10N.getString("warn.no_server_cert"));
            return null;
        }
        QuicTlsServerEngine tlsEngine = new QuicTlsServerEngine(engineFactory, localParams, conn,
                factory.isEarlyDataEnabled(), factory.getApplicationProtocols(), factory.getCipherSuites());
        conn.setTlsEngine(tlsEngine);

        if (connectionAcceptedHandler != null) {
            connectionAcceptedHandler.connectionAccepted(conn);
        } else if (streamAcceptHandler != null) {
            conn.setStreamAcceptHandler(streamAcceptHandler);
        }
        registerConnectionId(serverScid, conn);
        return conn;
    }

    void registerConnectionId(byte[] connectionId, QuicConnection connection) {
        connections.put(ByteArrays.toHexString(connectionId), connection);
    }

    void unregisterConnectionId(byte[] connectionId) {
        connections.remove(ByteArrays.toHexString(connectionId));
    }

    void markResetEligible(byte[] connectionId) {
        long ttl = factory.getMaxIdleTimeout();
        if (ttl <= 0) {
            ttl = RESET_ELIGIBLE_FALLBACK_MS;
        }
        markResetEligible(connectionId, System.currentTimeMillis() + ttl);
    }

    void markResetEligible(byte[] connectionId, long expiryMillis) {
        resetEligibleUntil.put(ByteArrays.toHexString(connectionId), Long.valueOf(expiryMillis));
    }

    void onConnectionClosed(QuicConnection connection) {
        if (!connections.containsValue(connection)) {
            return;
        }
        for (byte[] connectionId : connection.getOurConnectionIds()) {
            markResetEligible(connectionId);
        }
        removeConnection(connection);
    }

    private void removeConnection(QuicConnection connection) {
        Iterator<Map.Entry<String, QuicConnection>> it = connections.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() == connection) {
                it.remove();
            }
        }
    }

    private boolean isResetEligible(byte[] connectionId) {
        Long expiry = resetEligibleUntil.get(ByteArrays.toHexString(connectionId));
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiry.longValue()) {
            resetEligibleUntil.remove(ByteArrays.toHexString(connectionId));
            return false;
        }
        return true;
    }

    private boolean tryHandleStatelessResetForPeer(byte[] datagram, InetSocketAddress source) {
        if (datagram.length < StatelessResetPacket.MIN_DATAGRAM_LENGTH) {
            return false;
        }
        for (QuicConnection conn : new ArrayList<QuicConnection>(connections.values())) {
            if (conn.isClosed() || !source.equals(conn.getRemoteAddress())) {
                continue;
            }
            if (conn.handleIncomingStatelessResetDatagram(datagram)) {
                if (clientConnection == conn) {
                    clientConnection = null;
                }
                return true;
            }
        }
        return false;
    }

    private void trySendStatelessReset(byte[] dcid, byte[] received, InetSocketAddress source) {
        if (received.length < StatelessResetPacket.MIN_DATAGRAM_LENGTH || !isResetEligible(dcid)) {
            return;
        }
        String sourceKey = source.getAddress().getHostAddress();
        RateLimiter limiter = resetRateLimiters.get(sourceKey);
        if (limiter == null) {
            limiter = new RateLimiter(RESET_RATE_LIMIT_MAX, RESET_RATE_LIMIT_WINDOW_MS);
            resetRateLimiters.put(sourceKey, limiter);
        }
        if (!limiter.tryAcquire()) {
            return;
        }
        byte[] token = StatelessResetToken.generate(connectionIdStaticKey, dcid);
        byte[] reset = StatelessResetPacket.build(received.length, token, RANDOM);
        if (reset == null) {
            return;
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("fine.stateless_reset_sent"));
        }
        sendTo(source, reset);
    }

    /**
     * Sends a stateless Retry packet (RFC 9000 section 8.1.2) in response
     * to a client Initial that arrived with no (valid) Retry Token, when
     * {@link QuicTransportFactory#isRequireRetry} is set. No connection
     * state is created -- the token carries everything needed to validate
     * the client's follow-up Initial statelessly.
     *
     * @param clientScid the Source Connection ID of the client's Initial
     *        packet -- becomes this Retry packet's own Destination
     *        Connection ID (RFC 9000 section 17.2.5.1)
     * @param originalClientDcid the Destination Connection ID of the
     *        client's Initial packet (its own chosen, pre-Retry DCID) --
     *        sealed into the token and used as the integrity tag's
     *        associated data (RFC 9001 section 5.8), but not itself a
     *        field of the Retry packet
     * @param source the client's address
     */
    private void sendRetry(byte[] clientScid, byte[] originalClientDcid, InetSocketAddress source) {
        byte[] retryScid = generateConnectionId();
        byte[] token = RetryToken.seal(factory.getRetryTokenKey(), originalClientDcid, source.getAddress(),
                System.currentTimeMillis());
        byte[] withoutTag = LongHeaderCodec.buildRetryWithoutTag(clientScid, retryScid, token);
        byte[] tag = RetryIntegrityTag.compute(originalClientDcid, withoutTag);
        byte[] packet = new byte[withoutTag.length + tag.length];
        System.arraycopy(withoutTag, 0, packet, 0, withoutTag.length);
        System.arraycopy(tag, 0, packet, withoutTag.length, tag.length);
        sendTo(source, packet);
    }

    /**
     * Sends a raw packet to an address with no associated
     * {@link QuicConnection} -- used for stateless Retry, where no
     * connection exists yet to hang a normal {@link #sendPacket} call off of.
     *
     * @param address the destination address
     * @param packet the raw packet bytes
     */
    void sendTo(SocketAddress address, byte[] packet) {
        try {
            channel.send(ByteBuffer.wrap(packet), address);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.send_retry_failed"), e);
        }
    }

    /**
     * Opens a client connection with a {@link ProtocolHandler} that will
     * receive an auto-opened first stream once the handshake completes.
     *
     * @param remote the server address
     * @param handler the handler for the auto-opened first stream, may be null
     * @param serverName the SNI server name
     */
    void connectTo(InetSocketAddress remote, ProtocolHandler handler, String serverName) {
        connectTo(remote, handler, null, serverName);
    }

    /**
     * Opens a client connection, notifying {@code connHandler} once the
     * handshake completes instead of auto-opening a stream.
     *
     * @param remote the server address
     * @param handler the handler for the auto-opened first stream, may be null
     * @param connHandler notified once the handshake completes, may be null
     * @param serverName the SNI server name
     */
    void connectTo(InetSocketAddress remote, ProtocolHandler handler, ConnectionAcceptedHandler connHandler,
            String serverName) {
        connectTo(remote, handler, connHandler, null, serverName);
    }

    /**
     * Opens a client connection, notifying {@code connHandler} once the
     * handshake completes instead of auto-opening a stream, and {@code
     * earlyDataHandler} if 0-RTT (RFC 9001 section 4.6.1) becomes
     * available first.
     *
     * <p>If {@link QuicTransportFactory#isEarlyDataEnabled()} is set,
     * looks up {@link SessionTicketCache} for {@code serverName} (or
     * {@code remote}'s address if {@code serverName} is null, e.g. DoQ)
     * before starting the handshake, and presents any cached ticket --
     * this is the one and only place session tickets are consulted for
     * an attempted 0-RTT connection; no ticket cached means an ordinary
     * handshake, same as today.
     *
     * @param remote the server address
     * @param handler the handler for the auto-opened first stream, may be null
     * @param connHandler notified once the handshake completes, may be null
     * @param earlyDataHandler notified once 0-RTT send keys are ready, may be null
     * @param serverName the SNI server name, or null (e.g. DoQ)
     */
    void connectTo(InetSocketAddress remote, ProtocolHandler handler, ConnectionAcceptedHandler connHandler,
            EarlyDataHandler earlyDataHandler, String serverName) {
        byte[] clientScid = generateConnectionId();
        byte[] clientInitialDcid = generateConnectionId();
        InetSocketAddress local = getLocalSocketAddress();
        TransportParameters localParams = factory.buildTransportParameters(clientScid);

        QuicConnection conn = new QuicConnection(this, false, local, remote, clientScid, clientInitialDcid,
                clientInitialDcid, localParams, connectionIdStaticKey);
        QuicTlsClientEngine tlsEngine = new QuicTlsClientEngine(localParams, conn,
                factory.getApplicationProtocols(), factory.getNamedGroups(), factory.getCipherSuites());
        X509TrustManager trustManager = factory.getTrustManager();
        if (trustManager != null) {
            tlsEngine.setTrustManager(trustManager);
        }
        if (!factory.isVerifyHostnameEnabled()) {
            tlsEngine.setVerifyHostname(false);
        }
        conn.setTlsEngine(tlsEngine);
        if (connHandler != null) {
            conn.setClientConnectionAcceptedHandler(connHandler);
        }
        if (handler != null) {
            conn.setClientHandler(handler);
        }
        if (factory.isEarlyDataEnabled()) {
            String host = serverName != null ? serverName : remote.getAddress().getHostAddress();
            SessionTicketCache.Entry cached = SessionTicketCache.get(host, remote.getPort());
            if (cached != null) {
                tlsEngine.presentSessionTicket(cached.toTicket());
                conn.seedRememberedTransportParameters(cached.toTransportParameters());
            }
        }
        if (earlyDataHandler != null) {
            conn.setEarlyDataHandler(earlyDataHandler);
        }
        clientConnection = conn;
        registerConnectionId(clientScid, conn);

        try {
            conn.startHandshake(serverName);
        } catch (IOException e) {
            if (handler != null) {
                handler.error(e);
            }
            return;
        }
        conn.flush();
    }

    void requestFlush(QuicConnection connection) {
        connection.flush();
    }

    void sendPacket(QuicConnection connection, byte[] packet) {
        try {
            int sent = channel.send(ByteBuffer.wrap(packet), connection.getRemoteAddress());
            if (sent == 0) {
                LOGGER.fine(L10N.getString("fine.datagram_send_would_block"));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.send_packet_failed"), e);
        }
    }

    private InetSocketAddress getLocalSocketAddress() {
        try {
            return (InetSocketAddress) channel.getLocalAddress();
        } catch (IOException e) {
            return new InetSocketAddress("localhost", 0);
        }
    }

    private static byte[] generateConnectionId() {
        byte[] id = new byte[CONNECTION_ID_LENGTH];
        RANDOM.nextBytes(id);
        return id;
    }

    // ── MultiplexedEndpoint ──

    @Override
    public Endpoint openStream(ProtocolHandler handler) {
        if (clientConnection == null) {
            throw new IllegalStateException("No active QUIC connection (client mode)");
        }
        return clientConnection.openStream(handler);
    }

    @Override
    public Endpoint openUnidirectionalStream(ProtocolHandler handler) {
        if (clientConnection == null) {
            throw new IllegalStateException("No active QUIC connection (client mode)");
        }
        return clientConnection.openUnidirectionalStream(handler);
    }

    // ── Endpoint ──

    @Override
    public void send(ByteBuffer data) {
        if (clientConnection == null) {
            throw new IllegalStateException("No active QUIC connection (client mode)");
        }
        clientConnection.queueStreamData(0, data, false);
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
        // Snapshot rather than iterate connections directly: a concurrent
        // onReadable() on this engine's own SelectorLoop thread (e.g. a
        // caller closing from an admin/shutdown thread while I/O is still
        // in flight) can add/remove map entries at the same time.
        for (QuicConnection conn : new ArrayList<QuicConnection>(connections.values())) {
            conn.close();
        }
        connections.clear();
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, L10N.getString("warn.close_channel_failed"), e);
            }
        }
        if (selectionKey != null) {
            selectionKey.cancel();
        }
    }

    @Override
    public SocketAddress getLocalAddress() {
        return getLocalSocketAddress();
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return clientConnection != null ? clientConnection.getRemoteAddress() : new InetSocketAddress("unknown", 0);
    }

    @Override
    public boolean isSecure() {
        return true;
    }

    @Override
    public SecurityInfo getSecurityInfo() {
        return clientConnection != null ? clientConnection.getSecurityInfo() : NullSecurityInfo.INSTANCE;
    }

    @Override
    public void startTLS() throws IOException {
        throw new UnsupportedOperationException("QUIC connections are always secure");
    }

    @Override
    public void pauseRead() {
        // Flow control is per-stream in QUIC; there is no connection-wide read to pause.
    }

    @Override
    public void resumeRead() {
    }

    @Override
    public void onWriteReady(Runnable callback) {
        // Write-ready notification is per-stream in QUIC (QuicStreamEndpoint.onWriteReady).
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
        return factory.isTelemetryEnabled();
    }

    @Override
    public TelemetryConfig getTelemetryConfig() {
        return factory.getTelemetryConfig();
    }

    @Override
    public void execute(Runnable task) {
        selectorLoop.invokeLater(task);
    }

    // ChannelHandler and Endpoint both declare scheduleTimer -- one as a
    // default, one as abstract -- so an explicit override is required to
    // resolve which wins (ChannelHandler's, which is what every other
    // caller in this class already relies on).
    @Override
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return ChannelHandler.super.scheduleTimer(delayMs, callback);
    }
}
