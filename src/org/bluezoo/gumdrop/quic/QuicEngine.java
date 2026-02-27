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

import org.bluezoo.gumdrop.GumdropNative;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.ChannelHandler;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.MultiplexedEndpoint;
import org.bluezoo.gumdrop.NullSecurityInfo;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * QUIC engine managing connections over a single DatagramChannel.
 *
 * <p>QuicEngine is a {@link ChannelHandler} registered with a
 * {@link SelectorLoop} on a {@link DatagramChannel}. When the channel
 * is readable, the engine receives UDP datagrams, parses QUIC headers
 * using {@code quiche_header_info}, and dispatches them to the correct
 * {@link QuicConnection}. For server mode, it also accepts new incoming
 * connections.
 *
 * <p>Each QuicEngine has one underlying DatagramChannel (bound to a local
 * port for servers, or connected to a single remote for clients). Multiple
 * QUIC connections can be multiplexed over this single UDP socket.
 *
 * <p>QuicEngine also implements {@link MultiplexedEndpoint} by delegating
 * to a client-mode QuicConnection, allowing protocol handlers to open
 * streams via the standard Endpoint API.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see QuicConnection
 * @see QuicStreamEndpoint
 * @see QuicTransportFactory
 */
public class QuicEngine implements ChannelHandler, MultiplexedEndpoint {

    private static final Logger LOGGER =
            Logger.getLogger(QuicEngine.class.getName());

    /** Maximum QUIC connection ID length per RFC 9000. */
    private static final int MAX_CONN_ID_LEN = 20;

    private final QuicTransportFactory factory;
    private final boolean serverMode;

    private DatagramChannel channel;
    private SelectionKey selectionKey;
    private SelectorLoop selectorLoop;

    // Reusable direct buffers for zero-copy JNI interaction
    private ByteBuffer recvBuf;
    private ByteBuffer sendBuf;
    private ByteBuffer streamBuf;

    // Connection map: connection ID (as hex string) -> QuicConnection
    private final Map<String, QuicConnection> connections =
            new HashMap<String, QuicConnection>();

    // For client mode: the single outbound connection
    private QuicConnection clientConnection;

    // Server-side: handler factory for new connections
    private StreamAcceptHandler streamAcceptHandler;

    // Connection-level accept handler (used by HTTP/3)
    private ConnectionAcceptedHandler connectionAcceptedHandler;

    private Trace trace;
    private boolean closing;

    /**
     * Creates a QuicEngine.
     *
     * @param factory the QuicTransportFactory that created this engine
     * @param serverMode true for server mode, false for client mode
     */
    QuicEngine(QuicTransportFactory factory, boolean serverMode) {
        this.factory = factory;
        this.serverMode = serverMode;
    }

    /**
     * Initialises the engine with its DatagramChannel and buffers.
     * Called by QuicTransportFactory after channel setup.
     *
     * @param channel the datagram channel
     */
    void init(DatagramChannel channel) {
        this.channel = channel;
        int maxPayload = 1350;
        this.recvBuf = ByteBuffer.allocateDirect(65535);
        this.sendBuf = ByteBuffer.allocateDirect(maxPayload);
        this.streamBuf = ByteBuffer.allocateDirect(65535);
    }

    // ── ChannelHandler implementation ──

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

    // ── Packet receive (called by SelectorLoop on OP_READ) ──

    /**
     * Receives a UDP datagram and feeds it to quiche.
     * Called by the SelectorLoop's QUIC dispatch path.
     */
    public void onReadable() {
        recvBuf.clear();

        InetSocketAddress source;
        try {
            source = (InetSocketAddress) channel.receive(recvBuf);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error receiving QUIC packet", e);
            return;
        }

        if (source == null) {
            return;
        }

        recvBuf.flip();
        int len = recvBuf.remaining();

        if (len == 0) {
            return;
        }

        boolean isLongHeader = (recvBuf.get(0) & 0x80) != 0;

        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("Received " + len + " bytes from " + source
                    + " (" + (isLongHeader ? "Long" : "Short") + " header)");
        }

        // Parse QUIC header to extract version, DCID, SCID, and token
        byte[] headerInfo = GumdropNative.quiche_header_info(recvBuf, len);
        if (headerInfo == null) {
            LOGGER.severe("Failed to parse QUIC header from " + source
                    + " (" + len + " bytes, "
                    + (isLongHeader ? "Long" : "Short") + " header)");
            return;
        }

        int off = 0;

        int version = ((headerInfo[off] & 0xFF) << 24)
                | ((headerInfo[off + 1] & 0xFF) << 16)
                | ((headerInfo[off + 2] & 0xFF) << 8)
                | (headerInfo[off + 3] & 0xFF);
        off += 4;

        // skip type byte
        off += 1;

        int dcidLen = headerInfo[off] & 0xFF;
        off += 1;
        byte[] dcid = new byte[dcidLen];
        System.arraycopy(headerInfo, off, dcid, 0, dcidLen);
        off += dcidLen;

        int scidLen = headerInfo[off] & 0xFF;
        off += 1;
        byte[] peerScid = new byte[scidLen];
        System.arraycopy(headerInfo, off, peerScid, 0, scidLen);
        off += scidLen;

        // token (not used yet, but parsed for completeness)
        // int tokenLen = headerInfo[off] & 0xFF;

        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("Parsed header: version=0x"
                    + Integer.toHexString(version)
                    + " dcid=" + bytesToHex(dcid)
                    + " scid=" + bytesToHex(peerScid));
        }

        String connKey = bytesToHex(dcid);
        QuicConnection conn = connections.get(connKey);

        if (conn == null && serverMode) {
            if (!factory.isVersionSupported(version)) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Unsupported QUIC version 0x"
                            + Integer.toHexString(version)
                            + " from " + source
                            + ", sending Version Negotiation");
                }
                sendVersionNegotiation(peerScid, dcid, source);
                return;
            }

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Received QUIC Initial packet from " + source
                        + ", version=0x" + Integer.toHexString(version)
                        + ", attempting to accept connection");
            }
            conn = acceptConnection(dcid, version, source);
            if (conn == null) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning("Failed to accept QUIC connection from "
                            + source + ", version=0x"
                            + Integer.toHexString(version));
                }
                return;
            }
        }

        if (conn == null) {
            LOGGER.severe("No connection for DCID " + connKey);
            return;
        }

        // Feed the packet to quiche
        InetSocketAddress local = getLocalSocketAddress();
        byte[] fromAddr = encodeAddress(source);
        byte[] toAddr = encodeAddress(local);

        recvBuf.rewind();
        int rc = GumdropNative.quiche_conn_recv(
                conn.getConnPtr(), recvBuf, len, fromAddr, toAddr);

        if (rc < 0) {
            if (rc != GumdropNative.QUICHE_ERR_DONE) {
                LOGGER.severe("QUIC recv error: "
                        + GumdropNative.errorString(rc));
            }
            return;
        }

        boolean estAfterRecv =
                GumdropNative.quiche_conn_is_established(conn.getConnPtr());
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("quiche_conn_recv consumed " + rc
                    + " bytes, established=" + estAfterRecv
                    + ", " + (isLongHeader ? "Long" : "Short")
                    + " header, dcid=" + connKey);
        }

        // Process readable streams
        conn.processReadableStreams(streamBuf);

        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("Flushing after recv of " + len
                    + " bytes from " + source);
        }

        // Flush outgoing packets and re-check established state
        flushAndCheck(conn);

        // Reschedule timeout
        conn.scheduleTimeout();

        // Check if connection is closed
        if (GumdropNative.quiche_conn_is_closed(conn.getConnPtr())) {
            removeConnection(conn, connKey);
        }
    }

    /**
     * Sends a stateless QUIC Version Negotiation packet.
     * Called when an Initial packet arrives with an unsupported version.
     */
    private void sendVersionNegotiation(byte[] peerScid, byte[] dcid,
                                        InetSocketAddress dest) {
        sendBuf.clear();
        int written = GumdropNative.quiche_negotiate_version(
                peerScid, dcid, sendBuf, sendBuf.capacity());
        if (written < 0) {
            LOGGER.warning("Failed to write Version Negotiation packet: "
                    + written);
            return;
        }
        sendBuf.limit(written);
        sendBuf.position(0);
        try {
            channel.send(sendBuf, dest);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Error sending Version Negotiation to " + dest, e);
        }
    }

    /**
     * Accepts a new incoming QUIC connection.
     *
     * @param dcid    the DCID from the client's Initial packet
     * @param version the QUIC version from the packet header
     * @param source  the peer's socket address
     */
    private QuicConnection acceptConnection(byte[] dcid, int version,
                                             InetSocketAddress source) {
        byte[] scid = generateConnectionId();

        InetSocketAddress local = getLocalSocketAddress();
        byte[] localAddr = encodeAddress(local);
        byte[] peerAddr = encodeAddress(source);

        long ssl = GumdropNative.ssl_new(factory.getSslCtx());
        if (ssl == 0) {
            LOGGER.warning("Failed to create SSL for new QUIC connection");
            return null;
        }

        long connPtr = GumdropNative.quiche_conn_new_with_tls(
                scid, null, localAddr, peerAddr,
                factory.getQuicheConfig(version), ssl, true);

        if (connPtr == 0) {
            LOGGER.warning(
                    "Failed to create quiche connection for " + source);
            return null;
        }

        QuicConnection conn = new QuicConnection(
                this, connPtr, ssl, local, source);

        if (connectionAcceptedHandler != null) {
            connectionAcceptedHandler.connectionAccepted(conn);
        } else if (streamAcceptHandler != null) {
            conn.setStreamAcceptHandler(streamAcceptHandler);
        }

        String connKey = bytesToHex(scid);
        connections.put(connKey, conn);

        // Also map the original client DCID so that the first
        // packet (which created this connection) can be looked up
        // after quiche responds with the server SCID.
        String dcidKey = bytesToHex(dcid);
        if (!dcidKey.equals(connKey)) {
            connections.put(dcidKey, conn);
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Accepted QUIC connection from " + source
                    + " [" + connKey + "]");
        }

        return conn;
    }

    /**
     * Flushes outgoing QUIC packets for a connection.
     */
    public void flushConnection(QuicConnection conn) {
        int packetCount = 0;
        int totalBytes = 0;
        while (true) {
            sendBuf.clear();
            int written = GumdropNative.quiche_conn_send(
                    conn.getConnPtr(), sendBuf, sendBuf.capacity());

            if (written == GumdropNative.QUICHE_ERR_DONE) {
                break;
            }

            if (written < 0) {
                LOGGER.warning("quiche_conn_send error: "
                        + GumdropNative.errorString(written));
                break;
            }

            sendBuf.limit(written);
            sendBuf.position(0);

            if (LOGGER.isLoggable(Level.FINEST)) {
                int firstByte = sendBuf.get(0) & 0xFF;
                boolean longHeader = (firstByte & 0x80) != 0;
                String hdrType;
                if (longHeader) {
                    int pktType = (firstByte & 0x30) >> 4;
                    switch (pktType) {
                        case 0: hdrType = "Initial"; break;
                        case 1: hdrType = "0-RTT"; break;
                        case 2: hdrType = "Handshake"; break;
                        case 3: hdrType = "Retry"; break;
                        default: hdrType = "Long(" + pktType + ")"; break;
                    }
                } else {
                    hdrType = "Short(1-RTT)";
                }
                LOGGER.finest("quiche_conn_send: " + written
                        + " bytes, " + hdrType
                        + " [0x" + Integer.toHexString(firstByte) + "]");
            }

            InetSocketAddress dest =
                    (InetSocketAddress) conn.getRemoteAddress();
            try {
                int sent = channel.send(sendBuf, dest);
                if (sent != written) {
                    LOGGER.warning("channel.send returned " + sent
                            + " (expected " + written + ") to " + dest);
                }
                packetCount++;
                totalBytes += written;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Error sending QUIC packet to " + dest, e);
                break;
            }
        }
        if (packetCount > 0 && LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("Flushed " + packetCount + " QUIC packets ("
                    + totalBytes + " bytes) to "
                    + conn.getRemoteAddress());
        }
    }

    /**
     * Flushes a connection and then re-checks the established state.
     * After flushing, quiche may mark the connection as established
     * (e.g. after sending HANDSHAKE_DONE), so we need to notify
     * the connection to trigger application-level setup (HTTP/3 init).
     */
    private void flushAndCheck(QuicConnection conn) {
        flushConnection(conn);
        conn.checkEstablished();
    }

    /**
     * Called by QuicConnection when it has data to flush.
     */
    public void requestFlush() {
        if (selectorLoop != null) {
            selectorLoop.requestDatagramWrite(this);
        }
    }

    /**
     * Called by the SelectorLoop on OP_WRITE.
     * Flushes all connections that may have pending data.
     */
    public void onWritable() {
        for (QuicConnection conn : connections.values()) {
            if (!conn.isClosed()) {
                flushAndCheck(conn);
            }
        }

        // Clear OP_WRITE if nothing more to send
        if (selectionKey != null && selectionKey.isValid()) {
            selectionKey.interestOps(
                    selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    // ── MultiplexedEndpoint implementation ──

    @Override
    public Endpoint openStream(ProtocolHandler handler) {
        if (clientConnection == null) {
            throw new IllegalStateException(
                    "No active QUIC connection (client mode)");
        }
        return clientConnection.openStream(handler);
    }

    @Override
    public void setStreamAcceptHandler(StreamAcceptHandler handler) {
        this.streamAcceptHandler = handler;
        for (QuicConnection conn : connections.values()) {
            conn.setStreamAcceptHandler(handler);
        }
    }

    /**
     * Callback interface for connection-level accept events.
     *
     * <p>Used by protocols like HTTP/3 that need to install a
     * connection-level handler (e.g.,
     * {@link QuicConnection.ConnectionReadyHandler}) as soon as a new
     * QUIC connection is accepted, before any streams are opened.
     */
    public interface ConnectionAcceptedHandler {

        /**
         * Called when a new QUIC connection has been accepted.
         *
         * @param connection the newly created connection
         */
        void connectionAccepted(QuicConnection connection);
    }

    /**
     * Sets the connection-level accept handler.
     *
     * <p>When set, this handler is called for each new server-side
     * connection. The handler typically installs a
     * {@link QuicConnection.ConnectionReadyHandler} on the connection.
     *
     * @param handler the connection accept handler
     */
    public void setConnectionAcceptedHandler(
            ConnectionAcceptedHandler handler) {
        this.connectionAcceptedHandler = handler;
    }

    @Override
    public void send(ByteBuffer data) {
        if (clientConnection == null) {
            throw new IllegalStateException(
                    "No active QUIC connection (client mode)");
        }
        // For MultiplexedEndpoint, send on the default stream (stream 0)
        clientConnection.streamSend(0, data, false);
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

        // Close all connections
        Iterator<Map.Entry<String, QuicConnection>> it =
                connections.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, QuicConnection> entry = it.next();
            entry.getValue().close();
            it.remove();
        }

        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Error closing QUIC DatagramChannel", e);
            }
        }

        if (selectionKey != null) {
            selectionKey.cancel();
        }
    }

    @Override
    public java.net.SocketAddress getLocalAddress() {
        return getLocalSocketAddress();
    }

    @Override
    public java.net.SocketAddress getRemoteAddress() {
        if (clientConnection != null) {
            return clientConnection.getRemoteAddress();
        }
        return new InetSocketAddress("unknown", 0);
    }

    @Override
    public boolean isSecure() {
        return true;
    }

    @Override
    public SecurityInfo getSecurityInfo() {
        if (clientConnection != null) {
            return clientConnection.getSecurityInfo();
        }
        return NullSecurityInfo.INSTANCE;
    }

    @Override
    public void startTLS() throws IOException {
        throw new UnsupportedOperationException(
                "QUIC is always secure");
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
    public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
        return Gumdrop.getInstance().scheduleTimer(this, delayMs, callback);
    }

    // ── Client connection setup ──

    /**
     * Initiates a QUIC client connection to the specified remote address.
     * Called by QuicTransportFactory.
     *
     * @param remote the remote address
     * @param handler the handler for the initial stream
     * @param serverName the TLS SNI hostname, or null
     */
    void connectTo(InetSocketAddress remote,
                   ProtocolHandler handler, String serverName) {
        connectTo(remote, handler, null, serverName);
    }

    /**
     * Initiates a QUIC client connection with a connection-level
     * accepted handler. Used by HTTP/3 where the h3 handler needs the
     * {@link QuicConnection} rather than an individual stream.
     *
     * @param remote the remote address
     * @param handler the handler for stream-level fallback (may be null)
     * @param connHandler the connection-level handler
     * @param serverName the TLS SNI hostname, or null
     */
    void connectTo(InetSocketAddress remote,
                   ProtocolHandler handler,
                   ConnectionAcceptedHandler connHandler,
                   String serverName) {
        byte[] scid = generateConnectionId();

        InetSocketAddress local = getLocalSocketAddress();
        byte[] localAddr = encodeAddress(local);
        byte[] peerAddr = encodeAddress(remote);

        long ssl = GumdropNative.ssl_new(factory.getSslCtx());
        if (ssl == 0) {
            handler.error(new IOException(
                    "Failed to create SSL for QUIC connection"));
            return;
        }

        if (serverName != null) {
            GumdropNative.ssl_set_hostname(ssl, serverName);
        }

        long connPtr = GumdropNative.quiche_conn_new_with_tls(
                scid, null, localAddr, peerAddr,
                factory.getQuicheConfig(), ssl, false);

        if (connPtr == 0) {
            handler.error(new IOException(
                    "Failed to create quiche connection to " + remote));
            return;
        }

        QuicConnection conn = new QuicConnection(
                this, connPtr, ssl, local, remote);
        if (connHandler != null) {
            conn.setClientConnectionAcceptedHandler(connHandler);
        }
        if (handler != null) {
            conn.setClientHandler(handler);
        }
        clientConnection = conn;

        String connKey = bytesToHex(scid);
        connections.put(connKey, conn);

        // Send initial QUIC handshake packet
        flushConnection(conn);
        conn.scheduleTimeout();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Initiating QUIC connection to " + remote
                    + " [" + connKey + "]");
        }
    }

    // ── Internal helpers ──

    private void removeConnection(QuicConnection conn, String connKey) {
        conn.close();
        connections.remove(connKey);

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Removed QUIC connection [" + connKey + "]");
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
        byte[] id = new byte[MAX_CONN_ID_LEN];
        java.security.SecureRandom random = new java.security.SecureRandom();
        random.nextBytes(id);
        return id;
    }

    /**
     * Encodes an InetSocketAddress as bytes for the JNI layer.
     * Format: [family (1)][port (2)][addr (4 or 16)]
     */
    private static byte[] encodeAddress(InetSocketAddress addr) {
        byte[] ipBytes = addr.getAddress().getAddress();
        int port = addr.getPort();
        boolean ipv6 = ipBytes.length == 16;

        byte[] result = new byte[1 + 2 + ipBytes.length];
        result[0] = (byte) (ipv6 ? 6 : 4);
        result[1] = (byte) ((port >> 8) & 0xFF);
        result[2] = (byte) (port & 0xFF);
        System.arraycopy(ipBytes, 0, result, 3, ipBytes.length);
        return result;
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private static String bytesToHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = HEX_CHARS[v >>> 4];
            hex[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hex);
    }
}
