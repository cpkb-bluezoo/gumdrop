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
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;

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
import org.bluezoo.gumdrop.quic.packet.LongHeaderCodec;
import org.bluezoo.gumdrop.quic.packet.LongHeaderPrefix;
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
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QuicEngine implements ChannelHandler, MultiplexedEndpoint {

    private static final Logger LOGGER = Logger.getLogger(QuicEngine.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();

    /** RFC 9000 section 5.1: the fixed length this implementation uses for every connection ID it generates. */
    static final int CONNECTION_ID_LENGTH = 20;

    private final QuicTransportFactory factory;
    private final boolean serverMode;
    private final byte[] connectionIdStaticKey;

    private DatagramChannel channel;
    private SelectionKey selectionKey;
    private SelectorLoop selectorLoop;
    private ByteBuffer recvBuf;

    private final Map<String, QuicConnection> connections = new HashMap<String, QuicConnection>();
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
            LOGGER.log(Level.WARNING, "Failed to receive QUIC datagram", e);
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
            } catch (RuntimeException e) {
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
            if (!serverMode || prefix == null || prefix.getPacketType() != LongHeaderCodec.TYPE_INITIAL) {
                return; // unknown connection, and not a new client Initial we can accept
            }
            conn = acceptConnection(dcid, prefix.getSourceConnectionId(), source);
            if (conn == null) {
                return;
            }
        }
        conn.receive(ByteBuffer.wrap(bytes));
        if (conn.isClosed()) {
            connections.remove(key);
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

    private QuicConnection acceptConnection(byte[] clientDcid, byte[] clientScid, InetSocketAddress source) {
        byte[] serverScid = generateConnectionId();
        InetSocketAddress local = getLocalSocketAddress();
        TransportParameters localParams = factory.buildTransportParameters(serverScid);
        localParams.setOriginalDestinationConnectionId(clientDcid);

        QuicConnection conn = new QuicConnection(this, true, local, source, serverScid, clientScid, clientDcid,
                localParams, connectionIdStaticKey);
        TlsServerEngineFactory engineFactory = factory.getServerEngineFactory();
        if (engineFactory == null) {
            LOGGER.warning("No server certificate configured; rejecting new QUIC connection");
            return null;
        }
        QuicTlsServerEngine tlsEngine = new QuicTlsServerEngine(engineFactory, localParams, conn);
        conn.setTlsEngine(tlsEngine);

        if (connectionAcceptedHandler != null) {
            connectionAcceptedHandler.connectionAccepted(conn);
        } else if (streamAcceptHandler != null) {
            conn.setStreamAcceptHandler(streamAcceptHandler);
        }
        connections.put(ByteArrays.toHexString(serverScid), conn);
        return conn;
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
        byte[] clientScid = generateConnectionId();
        byte[] clientInitialDcid = generateConnectionId();
        InetSocketAddress local = getLocalSocketAddress();
        TransportParameters localParams = factory.buildTransportParameters(clientScid);

        QuicConnection conn = new QuicConnection(this, false, local, remote, clientScid, clientInitialDcid,
                clientInitialDcid, localParams, connectionIdStaticKey);
        QuicTlsClientEngine tlsEngine = new QuicTlsClientEngine(localParams, conn);
        X509TrustManager trustManager = factory.getTrustManager();
        if (trustManager != null) {
            tlsEngine.setTrustManager(trustManager);
        }
        conn.setTlsEngine(tlsEngine);
        if (connHandler != null) {
            conn.setClientConnectionAcceptedHandler(connHandler);
        }
        if (handler != null) {
            conn.setClientHandler(handler);
        }
        clientConnection = conn;
        connections.put(ByteArrays.toHexString(clientScid), conn);

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
                LOGGER.fine("QUIC datagram send would block; dropping (loss detection will retransmit)");
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send QUIC packet", e);
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
                LOGGER.log(Level.WARNING, "Failed to close QUIC datagram channel", e);
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
