/*
 * QuicConnection.java
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import tech.kwik.agent15.TlsConstants;
import tech.kwik.agent15.TlsProtocolException;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StreamAcceptHandler;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.quic.cid.ConnectionIdEntry;
import org.bluezoo.gumdrop.quic.cid.ConnectionIdManager;
import org.bluezoo.gumdrop.quic.frame.QuicFrameHandler;
import org.bluezoo.gumdrop.quic.frame.QuicFrameParser;
import org.bluezoo.gumdrop.quic.frame.QuicFrameWriter;
import org.bluezoo.gumdrop.quic.packet.LongHeaderCodec;
import org.bluezoo.gumdrop.quic.packet.LongHeaderPrefix;
import org.bluezoo.gumdrop.quic.packet.PacketNumberCodec;
import org.bluezoo.gumdrop.quic.packet.PacketProtection;
import org.bluezoo.gumdrop.quic.packet.PacketProtectionException;
import org.bluezoo.gumdrop.quic.packet.PacketProtectionKeys;
import org.bluezoo.gumdrop.quic.packet.QuicAeadAlgorithm;
import org.bluezoo.gumdrop.quic.packet.RetryIntegrityTag;
import org.bluezoo.gumdrop.quic.packet.RetryPacket;
import org.bluezoo.gumdrop.quic.packet.ShortHeaderCodec;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.quic.recovery.LossDetector;
import org.bluezoo.gumdrop.quic.recovery.SentPacket;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;
import org.bluezoo.gumdrop.quic.tls.Hkdf;
import org.bluezoo.gumdrop.quic.tls.InitialSecrets;
import org.bluezoo.gumdrop.quic.tls.QuicTlsEngine;
import org.bluezoo.gumdrop.quic.tls.QuicTlsEngineListener;

/**
 * One QUIC connection: owns the TLS 1.3 handshake, packet protection
 * keys, connection ID lifecycle, loss detection/congestion control, and
 * every stream on the connection.
 *
 * <p>This is the pure-Java replacement for the native quiche-backed
 * implementation, composing the already-built toolkit packages
 * ({@code quic.tls}, {@code quic.packet}, {@code quic.frame},
 * {@code quic.cid}, {@code quic.recovery}) the same way
 * {@code test/.../quic/QuicTestPeer} demonstrated, but driven by real
 * socket I/O via {@link QuicEngine} instead of hand-called from a test.
 *
 * <p>{@link #flush} coalesces every encryption level with pending data
 * into a single UDP datagram (RFC 9000 section 12.2) rather than sending
 * one packet per level. Deliberately still out of scope for now (see the
 * QUIC migration plan document): Retry address validation (the
 * anti-amplification byte-limit half of RFC 9000 section 8.1 -- the
 * primary DoS protection -- is implemented; the optional Retry-packet
 * mechanism for validating a client's address without a Handshake round
 * trip is not), connection migration (this connection always addresses
 * the peer using the connection ID learned during the handshake, even
 * though {@link ConnectionIdManager} correctly tracks further connection
 * IDs the peer issues), and out-of-order STREAM data reassembly
 * (delivered in arrival order only, matching
 * {@link org.bluezoo.gumdrop.quic.tls.CryptoStreamBuffer}'s own accepted
 * limitation for CRYPTO data).
 *
 * <p>Flow control is now bidirectional: the peer's advertised MAX_DATA/
 * MAX_STREAM_DATA is honoured on the send side (see {@code canSendOnStream}),
 * and this endpoint also grows and enforces its own advertised receive-side
 * limits -- as data arrives, {@code streamFrameReceived} tracks each
 * stream's highest received offset (RFC 9000 section 4.1's model, not
 * literal bytes delivered to the app, so a duplicate/retransmitted STREAM
 * frame doesn't double-count), rejects a peer that exceeds the currently
 * advertised limit (RFC 9000 section 11: FLOW_CONTROL_ERROR), and, once
 * consumption passes half of the current window, grows the limit and
 * queues a MAX_DATA/MAX_STREAM_DATA update -- a fixed-size window, not
 * RTT-tuned (matches how {@code quic.recovery}'s congestion control is
 * NewReno-only for now: correctness first, performance tuning later).
 * DATA_BLOCKED/STREAM_DATA_BLOCKED trigger the same growth proactively.
 * One known, deliberately unaddressed gap: unlike CRYPTO/STREAM chunks,
 * a lost MAX_DATA/MAX_STREAM_DATA frame is not explicitly retransmitted --
 * recovery relies on the peer re-sending a BLOCKED frame while still
 * blocked (RFC 9000 section 4.1), a bounded delay rather than a deadlock.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QuicConnection implements QuicTlsEngineListener {

    private static final Logger LOGGER = Logger.getLogger(QuicConnection.class.getName());

    /** RFC 9000 section 14.1: every implementation must support at least this size. */
    static final int MIN_DATAGRAM_SIZE = 1200;

    private static final byte[] EMPTY_TOKEN = new byte[0];

    private final QuicEngine engine;
    private final boolean isServer;
    private final InetSocketAddress localAddress;
    private final InetSocketAddress remoteAddress;
    private QuicTlsEngine tlsEngine;
    private final TransportParameters localTransportParameters;
    private final byte[] connectionIdStaticKey;
    private final long handshakeStartTime = System.currentTimeMillis();

    private final byte[] ourConnectionId;

    /**
     * The connection ID currently used to address the peer. Learned once
     * (from the peer's first long-header response's Source Connection ID
     * for a client; known immediately at accept time for a server) and
     * used for the connection's whole lifetime -- see the class
     * documentation on why this never rotates.
     */
    private byte[] peerConnectionId;
    private boolean peerConnectionIdLearned;

    private ConnectionIdManager connectionIdManager;
    private TransportParameters peerTransportParameters;

    private final EnumMap<EncryptionLevel, PacketProtectionKeys> sendKeys = new EnumMap<EncryptionLevel, PacketProtectionKeys>(
            EncryptionLevel.class);
    private final EnumMap<EncryptionLevel, PacketProtectionKeys> recvKeys = new EnumMap<EncryptionLevel, PacketProtectionKeys>(
            EncryptionLevel.class);
    private final EnumMap<EncryptionLevel, List<PendingChunk>> pendingCrypto = new EnumMap<EncryptionLevel, List<PendingChunk>>(
            EncryptionLevel.class);
    private final EnumMap<EncryptionLevel, Map<Long, List<PendingChunk>>> sentCrypto = new EnumMap<EncryptionLevel, Map<Long, List<PendingChunk>>>(
            EncryptionLevel.class);
    private final long[] sendPacketNumber = new long[EncryptionLevel.values().length];
    private final long[] largestReceived = { -1, -1, -1 };
    private final boolean[] ackOwed = new boolean[EncryptionLevel.values().length];
    private final boolean[] discarded = new boolean[EncryptionLevel.values().length];
    // Set on a Probe Timeout when nothing else was queued to naturally retransmit (RFC 9002 Appendix A.9).
    private final boolean[] pendingPing = new boolean[EncryptionLevel.values().length];

    // STREAM data pending send, keyed by stream ID; only ever flushed at ONE_RTT.
    private final Map<Long, List<PendingChunk>> pendingStream = new HashMap<Long, List<PendingChunk>>();
    private final Map<Long, Long> streamSendOffset = new HashMap<Long, Long>();
    // packetNumber (ONE_RTT) -> streamId -> chunks sent in that packet, for retransmission on loss.
    private final Map<Long, Map<Long, List<PendingChunk>>> sentStream = new HashMap<Long, Map<Long, List<PendingChunk>>>();
    // Each entry: {streamId, applicationErrorCode, finalSize}, owed a RESET_STREAM frame.
    private final List<long[]> pendingResetStreams = new ArrayList<long[]>();

    private final LossDetector lossDetector;
    private Hkdf hkdf = Hkdf.sha256();
    private QuicAeadAlgorithm aead = QuicAeadAlgorithm.AES_128_GCM;

    // RFC 9000 section 2.1: four independent counters by (initiator, directionality).
    private long nextLocalBidiStreamId;
    private long nextLocalUniStreamId;
    private long nextPeerBidiStreamId;
    private long nextPeerUniStreamId;

    private final Map<Long, QuicStreamEndpoint> streams = new HashMap<Long, QuicStreamEndpoint>();

    private StreamAcceptHandler streamAcceptHandler;
    private StreamAcceptHandler unidirectionalStreamAcceptHandler;
    private QuicEngine.ConnectionAcceptedHandler clientConnectionAcceptedHandler;
    private ProtocolHandler clientHandler;

    // Connection- and stream-level send budgets, learned from the peer's
    // transport parameters and grown by received MAX_DATA/MAX_STREAM_DATA
    // frames.
    private long peerMaxData;
    private final Map<Long, Long> peerMaxStreamData = new HashMap<Long, Long>();
    private long connectionBytesSent;
    private final Map<Long, Long> streamBytesSent = new HashMap<Long, Long>();

    // Connection- and stream-level receive budgets: this endpoint's own
    // currently advertised limits (grown over time, see the class
    // documentation) and what has actually been received against them.
    // streamBytesReceived tracks each stream's highest received offset
    // (offset + length), not a running total, so a duplicate/retransmitted
    // STREAM frame covering already-counted bytes doesn't double-count.
    private long localMaxData;
    private final Map<Long, Long> localMaxStreamData = new HashMap<Long, Long>();
    private long connectionBytesReceived;
    private final Map<Long, Long> streamBytesReceived = new HashMap<Long, Long>();

    // MAX_DATA/MAX_STREAM_DATA owed to the peer, drained at the next
    // ONE_RTT flush -- see buildProtectedPacket.
    private boolean maxDataOwed;
    private final Map<Long, Long> maxStreamDataOwed = new HashMap<Long, Long>();

    // DATA_BLOCKED/STREAM_DATA_BLOCKED owed to the peer (this side is
    // blocked sending by the PEER's advertised limit -- see
    // checkSendBlocked/buildProtectedPacket), plus which limit value has
    // already been signalled so it isn't repeated until that limit grows
    // (cleared in maxDataFrameReceived/maxStreamDataFrameReceived).
    private boolean dataBlockedOwed;
    private boolean dataBlockedSignalled;
    private final Map<Long, Long> streamDataBlockedOwed = new HashMap<Long, Long>();
    private final java.util.Set<Long> streamDataBlockedSignalled = new java.util.HashSet<Long>();

    // RFC 9000 section 8.1: anti-amplification limit. Server-side only --
    // a server MUST NOT send more than 3x what it has received from a
    // peer whose address isn't yet validated, to bound how much this
    // connection can be used to reflect/amplify traffic at a spoofed
    // victim address. addressValidated is set true the first time a
    // Handshake-level packet from the peer is successfully decrypted
    // (which requires the peer to have actually received and processed
    // this server's Initial response -- proving the address isn't
    // spoofed, since an off-path attacker cannot have the Handshake
    // keys), and never re-checked past that point.
    private long amplificationBytesReceived;
    private long amplificationBytesSent;
    private boolean addressValidated;

    // RFC 9000 section 8.1.2/17.2.5: client-only Retry state. originalDcid
    // is the Destination Connection ID this client used in its very first
    // Initial packet -- needed both as the Retry Integrity Tag's
    // associated data (RFC 9001 section 5.8) and, once a Retry has been
    // processed, as the value advertised back by the server in
    // original_destination_connection_id, which this endpoint doesn't
    // itself validate but keeps for symmetry/future use. retryToken is
    // echoed back in every subsequent Initial packet's Token field until
    // the handshake completes. expectedRetrySourceConnectionId is checked
    // against the peer's eventual retry_source_connection_id transport
    // parameter (RFC 9000 section 17.2.5.2's anti-tampering check).
    private final byte[] originalDcid;
    private boolean retryProcessed;
    private byte[] retryToken = EMPTY_TOKEN;
    private byte[] expectedRetrySourceConnectionId;

    private SecurityInfo securityInfo;
    private TimerHandle timerHandle;
    private boolean established;
    private boolean handshakeConfirmed;
    private boolean handshakeDoneOwed;
    private boolean closed;
    private String deferredCloseReason;
    private long deferredCloseErrorCode;

    /**
     * Creates a QUIC connection.
     *
     * @param engine the owning engine
     * @param isServer true if this endpoint is the server
     * @param localAddress the local socket address
     * @param remoteAddress the peer's socket address
     * @param ourConnectionId this endpoint's connection ID (server: freshly
     *                        generated; client: its own chosen SCID)
     * @param peerConnectionId the connection ID to address the peer with
     *                         initially (server: the client's Initial
     *                         packet SCID, known immediately; client: its
     *                         own bootstrap {@code clientInitialDcid},
     *                         corrected once the server's real SCID is learned)
     * @param initialSecretDcid the Destination Connection ID used to
     *                          derive Initial secrets (RFC 9001 section
     *                          5.2) -- the client's Initial packet DCID,
     *                          whether or not it equals {@code peerConnectionId}
     * @param localTransportParameters this endpoint's own transport parameters
     * @param connectionIdStaticKey this engine's static key for
     *                              {@link org.bluezoo.gumdrop.quic.cid.StatelessResetToken} derivation
     */
    QuicConnection(QuicEngine engine, boolean isServer, InetSocketAddress localAddress, InetSocketAddress remoteAddress,
            byte[] ourConnectionId, byte[] peerConnectionId, byte[] initialSecretDcid,
            TransportParameters localTransportParameters, byte[] connectionIdStaticKey) {
        this.engine = engine;
        this.isServer = isServer;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.ourConnectionId = ourConnectionId;
        this.peerConnectionId = peerConnectionId;
        this.localTransportParameters = localTransportParameters;
        this.connectionIdStaticKey = connectionIdStaticKey;
        this.originalDcid = initialSecretDcid;

        for (EncryptionLevel level : EncryptionLevel.values()) {
            pendingCrypto.put(level, new ArrayList<PendingChunk>());
            sentCrypto.put(level, new HashMap<Long, List<PendingChunk>>());
        }

        byte[] clientSecret = InitialSecrets.clientSecretV1(initialSecretDcid);
        byte[] serverSecret = InitialSecrets.serverSecretV1(initialSecretDcid);
        Hkdf initialHkdf = Hkdf.sha256();
        PacketProtectionKeys clientKeys = PacketProtectionKeys.derive(initialHkdf, clientSecret, QuicAeadAlgorithm.AES_128_GCM);
        PacketProtectionKeys serverKeys = PacketProtectionKeys.derive(initialHkdf, serverSecret, QuicAeadAlgorithm.AES_128_GCM);
        if (isServer) {
            sendKeys.put(EncryptionLevel.INITIAL, serverKeys);
            recvKeys.put(EncryptionLevel.INITIAL, clientKeys);
            nextLocalBidiStreamId = 1;
            nextLocalUniStreamId = 3;
            nextPeerBidiStreamId = 0;
            nextPeerUniStreamId = 2;
        } else {
            sendKeys.put(EncryptionLevel.INITIAL, clientKeys);
            recvKeys.put(EncryptionLevel.INITIAL, serverKeys);
            nextLocalBidiStreamId = 0;
            nextLocalUniStreamId = 2;
            nextPeerBidiStreamId = 1;
            nextPeerUniStreamId = 3;
        }

        this.lossDetector = new LossDetector(MIN_DATAGRAM_SIZE);
        this.connectionIdManager = new ConnectionIdManager(ourConnectionId, peerConnectionId, connectionIdStaticKey);
        this.localMaxData = localTransportParameters.getInitialMaxData();
    }

    /**
     * Sets the TLS engine driving this connection's handshake. Not
     * passed to the constructor because the TLS engine's own
     * constructor needs this connection as its {@link QuicTlsEngineListener}
     * -- construction is necessarily two-phase.
     *
     * @param tlsEngine the TLS engine
     */
    void setTlsEngine(QuicTlsEngine tlsEngine) {
        this.tlsEngine = tlsEngine;
    }

    /**
     * Marks the peer's address as already validated -- called by
     * {@link QuicEngine} when accepting a connection whose client Initial
     * carried a valid Retry Token, which itself proves the address
     * without needing a Handshake-level round trip (RFC 9000 section 8.1).
     */
    void markAddressValidated() {
        this.addressValidated = true;
    }

    // ── Identity / accessors ──

    QuicEngine getEngine() {
        return engine;
    }

    /**
     * Returns the SelectorLoop that owns this connection's I/O.
     */
    public SelectorLoop getSelectorLoop() {
        return engine.getSelectorLoop();
    }

    byte[] getOurConnectionId() {
        return ourConnectionId;
    }

    public SocketAddress getLocalAddress() {
        return localAddress;
    }

    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public SecurityInfo getSecurityInfo() {
        if (!established) {
            return null;
        }
        if (securityInfo == null) {
            securityInfo = new QuicSecurityInfo(tlsEngine, isServer, handshakeStartTime);
        }
        return securityInfo;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Registers a handler to accept incoming bidirectional streams (RFC
     * 9000 section 2.1) from the peer -- new requests, for HTTP/3, or
     * DoQ's/a generic client's queries.
     *
     * @param handler the handler
     */
    public void setStreamAcceptHandler(StreamAcceptHandler handler) {
        this.streamAcceptHandler = handler;
    }

    /**
     * Registers a handler to accept incoming unidirectional streams (RFC
     * 9000 section 2.1) from the peer -- HTTP/3's control stream, not
     * used by {@code StreamAcceptHandler}'s bidi-only consumers (DoQ,
     * generic clients).
     *
     * @param handler the handler
     */
    public void setUnidirectionalStreamAcceptHandler(StreamAcceptHandler handler) {
        this.unidirectionalStreamAcceptHandler = handler;
    }

    void setClientConnectionAcceptedHandler(QuicEngine.ConnectionAcceptedHandler handler) {
        this.clientConnectionAcceptedHandler = handler;
    }

    void setClientHandler(ProtocolHandler handler) {
        this.clientHandler = handler;
    }

    /**
     * Starts the client-side TLS handshake, producing an Initial packet
     * on the next {@link #flush}.
     *
     * @param serverName the SNI server name
     * @throws IOException if the handshake cannot be started
     */
    void startHandshake(String serverName) throws IOException {
        ((org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine) tlsEngine).startHandshake(serverName);
    }

    // ── Stream lifecycle ──

    /**
     * Opens a new locally-initiated bidirectional stream.
     *
     * @param handler the handler for the new stream
     * @return the new stream's endpoint
     */
    public Endpoint openStream(ProtocolHandler handler) {
        long streamId = nextLocalBidiStreamId;
        nextLocalBidiStreamId += 4;
        return createStream(streamId, handler);
    }

    /**
     * Opens a new locally-initiated unidirectional stream.
     *
     * @param handler the handler for the new stream
     * @return the new stream's endpoint
     */
    public Endpoint openUnidirectionalStream(ProtocolHandler handler) {
        long streamId = nextLocalUniStreamId;
        nextLocalUniStreamId += 4;
        return createStream(streamId, handler);
    }

    private Endpoint createStream(long streamId, ProtocolHandler handler) {
        QuicStreamEndpoint stream = new QuicStreamEndpoint(this, streamId, handler);
        streams.put(Long.valueOf(streamId), stream);
        handler.connected(stream);
        handler.securityEstablished(getSecurityInfo());
        return stream;
    }

    // RFC 9000 section 2.1: low bit 0x02 set means unidirectional.
    private static boolean isUnidirectional(long streamId) {
        return (streamId & 0x02) != 0;
    }

    // Peer-initiated if the low bit (0x01, client/server origin) disagrees with our own role.
    private boolean isPeerInitiated(long streamId) {
        boolean clientInitiated = (streamId & 0x01) == 0;
        return isServer == clientInitiated;
    }

    private QuicStreamEndpoint acceptStream(long streamId) {
        boolean unidirectional = isUnidirectional(streamId);
        StreamAcceptHandler handler = unidirectional ? unidirectionalStreamAcceptHandler : streamAcceptHandler;
        if (handler == null) {
            return null;
        }
        QuicStreamEndpoint probe = new QuicStreamEndpoint(this, streamId, null);
        ProtocolHandler protocolHandler = handler.acceptStream(probe);
        if (protocolHandler == null) {
            return null;
        }
        QuicStreamEndpoint stream = new QuicStreamEndpoint(this, streamId, protocolHandler);
        streams.put(Long.valueOf(streamId), stream);
        protocolHandler.connected(stream);
        protocolHandler.securityEstablished(getSecurityInfo());
        return stream;
    }

    /**
     * Queues data to be sent on a stream at the next {@link #flush}
     * (RFC 9000 section 19.8 STREAM frames only travel at 1-RTT).
     *
     * @param streamId the stream
     * @param data the data (copied -- the caller's buffer is not retained)
     * @param fin true if this is the last chunk of the stream
     */
    void queueStreamData(long streamId, ByteBuffer data, boolean fin) {
        byte[] copy = new byte[data.remaining()];
        data.get(copy);
        long offset = getAndAdvanceStreamOffset(streamId, copy.length);
        List<PendingChunk> chunks = pendingStream.get(Long.valueOf(streamId));
        if (chunks == null) {
            chunks = new ArrayList<PendingChunk>();
            pendingStream.put(Long.valueOf(streamId), chunks);
        }
        chunks.add(new PendingChunk(offset, copy, fin));
        requestFlush();
    }

    private long getAndAdvanceStreamOffset(long streamId, int length) {
        Long key = Long.valueOf(streamId);
        long offset = streamSendOffset.containsKey(key) ? streamSendOffset.get(key).longValue() : 0;
        streamSendOffset.put(key, Long.valueOf(offset + length));
        return offset;
    }

    /**
     * Abruptly terminates a stream's sending part (RESET_STREAM, RFC
     * 9000 section 19.4).
     *
     * @param streamId the stream
     * @param errorCode the application protocol error code
     */
    void resetStream(long streamId, long errorCode) {
        long finalSize = streamSendOffset.containsKey(Long.valueOf(streamId))
                ? streamSendOffset.get(Long.valueOf(streamId)).longValue() : 0;
        pendingResetStreams.add(new long[] { streamId, errorCode, finalSize });
        pendingStream.remove(Long.valueOf(streamId));
        requestFlush();
    }

    // Set for the duration of receive() so every side effect of
    // processing one incoming datagram (potentially several coalesced
    // packets, each with several frames, each of which can itself
    // trigger further synchronous callbacks -- e.g. Agent15 delivering a
    // server's whole certificate flight as several back-to-back
    // cryptoDataReady calls, or an application handler responding to a
    // request synchronously from within a frame callback) accumulates
    // into pendingCrypto/pendingStream/etc. rather than each one
    // triggering its own premature flush -- otherwise every such
    // mid-processing requestFlush() call would send whatever was queued
    // so far as its own separate datagram, defeating coalescing (RFC
    // 9000 section 12.2) even though buildProtectedPacket/flush
    // themselves are perfectly willing to combine everything pending
    // into one.
    private boolean suppressFlush;

    void requestFlush() {
        if (suppressFlush) {
            return;
        }
        engine.requestFlush(this);
    }

    /**
     * Forgets a stream once both its directions are finished (see
     * {@link QuicStreamEndpoint#isFullyClosed}) -- called after either
     * direction finishes, since either order is possible (a fire-and-
     * forget local {@link QuicStreamEndpoint#close} before the peer's
     * FIN arrives, or vice versa).
     */
    void retireStreamIfFullyClosed(long streamId, QuicStreamEndpoint stream) {
        if (stream.isFullyClosed()) {
            streams.remove(Long.valueOf(streamId));
        }
    }

    // ── Receive path ──

    /**
     * Processes a received datagram: one or more coalesced QUIC packets
     * (RFC 9000 section 12.2), each unprotected, decrypted, and its
     * frames dispatched.
     *
     * @param datagram the received datagram
     */
    void receive(ByteBuffer datagram) {
        byte[] bytes = new byte[datagram.remaining()];
        datagram.get(bytes);
        if (isServer && !addressValidated) {
            amplificationBytesReceived += bytes.length;
        }
        int offset = 0;
        suppressFlush = true;
        try {
            while (offset < bytes.length) {
                int consumed = receiveOnePacket(bytes, offset);
                if (consumed <= 0) {
                    break;
                }
                offset += consumed;
            }
        } finally {
            suppressFlush = false;
        }
        requestFlush();
    }

    // Returns the number of bytes this one packet occupied within
    // `bytes`, or -1 if it could not be parsed (the rest of the datagram
    // is then abandoned, matching RFC 9000 section 12.2's allowance to
    // stop processing a datagram once it can no longer be parsed).
    private int receiveOnePacket(byte[] bytes, int offset) {
        boolean longHeader = (bytes[offset] & 0x80) != 0;
        EncryptionLevel level;
        int pnOffset;
        int packetLength;
        if (longHeader) {
            byte[] fromOffset = offset == 0 ? bytes : java.util.Arrays.copyOfRange(bytes, offset, bytes.length);
            // A Retry packet has no Length field (RFC 9000 section
            // 17.2.5) -- a genuinely different shape from
            // Initial/Handshake/0-RTT -- so its type must be checked
            // before calling parsePrefix, which assumes that field exists.
            int packetType = (fromOffset[0] >>> 4) & 0x03;
            if (packetType == LongHeaderCodec.TYPE_RETRY) {
                handleRetryPacket(fromOffset);
                return bytes.length - offset; // a Retry packet always spans the rest of its datagram
            }
            LongHeaderPrefix prefix;
            try {
                prefix = LongHeaderCodec.parsePrefix(fromOffset);
            } catch (RuntimeException e) {
                return -1;
            }
            if (prefix.getPacketType() == LongHeaderCodec.TYPE_0RTT) {
                return -1; // 0-RTT is not implemented
            }
            level = prefix.getPacketType() == LongHeaderCodec.TYPE_INITIAL ? EncryptionLevel.INITIAL : EncryptionLevel.HANDSHAKE;
            pnOffset = prefix.getPacketNumberOffset();
            packetLength = pnOffset + (int) prefix.getRemainingLength();
            if (!peerConnectionIdLearned) {
                learnPeerConnectionId(prefix.getSourceConnectionId());
            }
        } else {
            level = EncryptionLevel.ONE_RTT;
            pnOffset = ShortHeaderCodec.packetNumberOffset(ourConnectionId.length);
            packetLength = bytes.length - offset;
        }
        if (packetLength <= pnOffset || offset + packetLength > bytes.length) {
            return -1;
        }
        byte[] packet = new byte[packetLength];
        System.arraycopy(bytes, offset, packet, 0, packetLength);
        processPacket(level, packet, pnOffset);
        return packetLength;
    }

    // Client-only: the server's connection ID isn't known until its
    // first long-header response arrives (see the class documentation
    // on why this never changes again after that).
    private void learnPeerConnectionId(byte[] scid) {
        peerConnectionId = scid;
        peerConnectionIdLearned = true;
    }

    // Client-only: handles a received Retry packet (RFC 9000 section
    // 8.1.2/17.2.5). Ignored outright on a server (a server never
    // receives a Retry -- it only sends them), once already processed
    // (a second Retry is either a duplicate or an attack), or once the
    // handshake has progressed past the point a Retry can still apply.
    private void handleRetryPacket(byte[] packet) {
        if (isServer || retryProcessed || established) {
            return;
        }
        RetryPacket retry;
        try {
            retry = LongHeaderCodec.parseRetry(packet);
        } catch (RuntimeException e) {
            return;
        }
        // RFC 9000 section 17.2.5.1: the Retry's own Destination
        // Connection ID must echo the Source Connection ID this client
        // used in the Initial packet that triggered it.
        if (!java.util.Arrays.equals(retry.getDestinationConnectionId(), ourConnectionId)) {
            return;
        }
        if (!RetryIntegrityTag.verify(originalDcid, retry.getPacketWithoutTag(), retry.getTag())) {
            return; // corrupted, or forged by an off-path attacker without the fixed key
        }

        retryProcessed = true;
        retryToken = retry.getRetryToken();
        expectedRetrySourceConnectionId = retry.getSourceConnectionId();
        peerConnectionId = retry.getSourceConnectionId();
        peerConnectionIdLearned = true;

        // RFC 9001 section 5.2: Initial secrets are derived from the
        // Destination Connection ID the client addresses the server with.
        // After a Retry, that DCID changes to the Retry packet's own
        // Source Connection ID, so Initial keys must be re-derived to match.
        byte[] clientSecret = InitialSecrets.clientSecretV1(peerConnectionId);
        byte[] serverSecret = InitialSecrets.serverSecretV1(peerConnectionId);
        Hkdf initialHkdf = Hkdf.sha256();
        sendKeys.put(EncryptionLevel.INITIAL,
                PacketProtectionKeys.derive(initialHkdf, clientSecret, QuicAeadAlgorithm.AES_128_GCM));
        recvKeys.put(EncryptionLevel.INITIAL,
                PacketProtectionKeys.derive(initialHkdf, serverSecret, QuicAeadAlgorithm.AES_128_GCM));

        // The TLS transcript itself is untouched (RFC 9000 section
        // 17.2.5.2) -- only the QUIC-level Initial packet(s) carrying it
        // are resent, now with the token attached and under the new keys.
        requeueAllSentCrypto(EncryptionLevel.INITIAL);
        requestFlush();
    }

    // Moves every previously sent chunk at a level back into the pending
    // queue for resending, e.g. after a Retry invalidates everything sent
    // so far at INITIAL. PendingChunk carries its own explicit stream/CRYPTO
    // offset, so re-queuing order doesn't matter for correctness, only
    // which packet numbers end up retransmitting which bytes.
    private void requeueAllSentCrypto(EncryptionLevel level) {
        Map<Long, List<PendingChunk>> sent = sentCrypto.get(level);
        List<PendingChunk> pending = pendingCrypto.get(level);
        for (List<PendingChunk> chunks : sent.values()) {
            pending.addAll(0, chunks);
        }
        sent.clear();
    }

    private void processPacket(EncryptionLevel level, byte[] packet, int pnOffset) {
        PacketProtectionKeys keys = recvKeys.get(level);
        if (keys == null) {
            return; // keys not derived yet at this level; drop
        }
        boolean longHeader = level != EncryptionLevel.ONE_RTT;
        try {
            byte[] sample = new byte[QuicAeadAlgorithm.SAMPLE_LENGTH];
            System.arraycopy(packet, pnOffset + 4, sample, 0, QuicAeadAlgorithm.SAMPLE_LENGTH);
            byte[] mask = PacketProtection.headerProtectionMask(keys, sample);
            PacketProtection.xorFirstByte(packet, mask, longHeader);
            int pnLength = (packet[0] & 0x03) + 1;
            PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);

            long truncatedPn = 0;
            for (int i = 0; i < pnLength; i++) {
                truncatedPn = (truncatedPn << 8) | (packet[pnOffset + i] & 0xff);
            }
            long fullPacketNumber = PacketNumberCodec.decode(largestReceived[level.ordinal()], truncatedPn, pnLength);

            int headerLength = pnOffset + pnLength;
            byte[] aad = java.util.Arrays.copyOfRange(packet, 0, headerLength);
            byte[] ciphertext = java.util.Arrays.copyOfRange(packet, headerLength, packet.length);
            byte[] plaintext = PacketProtection.open(keys, fullPacketNumber, aad, ciphertext);

            largestReceived[level.ordinal()] = Math.max(largestReceived[level.ordinal()], fullPacketNumber);
            ackOwed[level.ordinal()] = true;
            if (level == EncryptionLevel.HANDSHAKE) {
                // RFC 9000 section 8.1: a successfully decrypted Handshake
                // packet proves the peer holds the Handshake keys, which
                // requires it to have actually received and processed our
                // Initial response -- an off-path attacker spoofing the
                // client's address could not have produced this.
                addressValidated = true;
            }

            new QuicFrameParser(new FrameDispatcher(level)).receive(ByteBuffer.wrap(plaintext));
        } catch (PacketProtectionException e) {
            LOGGER.log(Level.FINE, "Packet protection failure at " + level + "; dropping", e);
        }
    }


    /** Per-packet frame dispatcher, one instance per {@link #processPacket} call. */
    private final class FrameDispatcher implements QuicFrameHandler {

        private final EncryptionLevel level;

        FrameDispatcher(EncryptionLevel level) {
            this.level = level;
        }

        @Override
        public void paddingFrameReceived(int length) {
        }

        @Override
        public void pingFrameReceived() {
        }

        @Override
        public void ackFrameReceived(long largestAcknowledged, long ackDelay, long[][] ranges) {
            if (level == EncryptionLevel.HANDSHAKE) {
                receivedHandshakeAck = true;
            }
            LossDetector.AckResult result = lossDetector.onAckReceived(level, largestAcknowledged, ackDelay,
                    ranges, peerMaxAckDelay(), System.currentTimeMillis(), peerAddressValidated());
            for (SentPacket lost : result.getNewlyLost()) {
                requeueLostPacket(level, lost.getPacketNumber());
            }
        }

        @Override
        public void resetStreamFrameReceived(long streamId, long applicationErrorCode, long finalSize) {
            QuicStreamEndpoint stream = streams.remove(Long.valueOf(streamId));
            if (stream != null) {
                stream.markClosed();
                stream.getHandler().disconnected();
            }
        }

        @Override
        public void stopSendingFrameReceived(long streamId, long applicationErrorCode) {
            resetStream(streamId, applicationErrorCode);
        }

        @Override
        public void cryptoFrameReceived(long offset, ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            try {
                tlsEngine.receiveCryptoData(level, offset, ByteBuffer.wrap(copy));
            } catch (TlsProtocolException | IOException e) {
                LOGGER.log(Level.WARNING, "TLS error processing CRYPTO data at " + level, e);
            }
        }

        @Override
        public void newTokenFrameReceived(ByteBuffer token) {
        }

        @Override
        public void streamFrameReceived(long streamId, long offset, boolean fin, ByteBuffer data) {
            QuicStreamEndpoint stream = streams.get(Long.valueOf(streamId));
            if (stream == null) {
                stream = acceptStream(streamId);
                if (stream == null) {
                    return;
                }
            }
            if (!checkAndRecordFlowControl(streamId, offset, data.remaining())) {
                return;
            }
            if (data.hasRemaining()) {
                stream.deliverData(data);
            }
            if (fin) {
                // The peer finishing their send direction must not stop
                // this side from still sending its own response on the
                // same (bidirectional) stream -- see QuicStreamEndpoint's
                // markPeerFinished javadoc.
                stream.markPeerFinished();
                stream.getHandler().disconnected();
                retireStreamIfFullyClosed(streamId, stream);
            }
        }

        @Override
        public void maxDataFrameReceived(long maximumData) {
            if (maximumData > peerMaxData) {
                peerMaxData = maximumData;
                dataBlockedSignalled = false;
            }
        }

        @Override
        public void maxStreamDataFrameReceived(long streamId, long maximumStreamData) {
            Long key = Long.valueOf(streamId);
            Long current = peerMaxStreamData.get(key);
            if (current == null || maximumStreamData > current.longValue()) {
                peerMaxStreamData.put(key, Long.valueOf(maximumStreamData));
                streamDataBlockedSignalled.remove(key);
            }
        }

        @Override
        public void maxStreamsFrameReceived(boolean bidirectional, long maximumStreams) {
        }

        // RFC 9000 section 4.1: the peer is blocked sending -- grow our
        // advertised limit right away rather than waiting for more data
        // to arrive and cross the usual half-window threshold. The peer
        // being fully blocked already means it cannot send anything more
        // to advance that passive check, so the unconditional
        // xxxOnBlocked growth is used instead -- see its javadoc.
        @Override
        public void dataBlockedFrameReceived(long maximumData) {
            growConnectionLimitOnBlocked();
        }

        @Override
        public void streamDataBlockedFrameReceived(long streamId, long maximumStreamData) {
            growStreamLimitOnBlocked(streamId);
        }

        @Override
        public void streamsBlockedFrameReceived(boolean bidirectional, long maximumStreams) {
        }

        @Override
        public void newConnectionIdFrameReceived(long sequenceNumber, long retirePriorTo,
                ByteBuffer connectionId, ByteBuffer statelessResetToken) {
            byte[] cid = new byte[connectionId.remaining()];
            connectionId.get(cid);
            byte[] token = new byte[statelessResetToken.remaining()];
            statelessResetToken.get(token);
            connectionIdManager.addPeerConnectionId(sequenceNumber, retirePriorTo, cid, token);
        }

        @Override
        public void retireConnectionIdFrameReceived(long sequenceNumber) {
            connectionIdManager.retireOurs(sequenceNumber);
        }

        @Override
        public void pathChallengeFrameReceived(ByteBuffer data) {
            // Connection migration/path validation is not implemented.
        }

        @Override
        public void pathResponseFrameReceived(ByteBuffer data) {
        }

        @Override
        public void connectionCloseFrameReceived(boolean applicationError, long errorCode,
                long frameType, String reason) {
            deferredCloseReason = reason;
            close();
        }

        @Override
        public void handshakeDoneFrameReceived() {
            handshakeConfirmed = true;
            notifyClientHandshakeComplete();
        }

        @Override
        public void frameError(String message) {
            LOGGER.warning("QUIC frame error on connection to " + remoteAddress + ": " + message);
        }
    }

    // RFC 9000 section 18.2's default: max_ack_delay/ack_delay_exponent
    // aren't implemented as transport parameters yet (TransportParameters
    // doesn't carry them), so the default always applies.
    private static long peerMaxAckDelay() {
        return 25;
    }

    private void requeueLostPacket(EncryptionLevel level, long packetNumber) {
        List<PendingChunk> lostCrypto = sentCrypto.get(level).remove(Long.valueOf(packetNumber));
        if (lostCrypto != null) {
            pendingCrypto.get(level).addAll(0, lostCrypto);
        }
        if (level == EncryptionLevel.ONE_RTT) {
            Map<Long, List<PendingChunk>> lostStreams = sentStream.remove(Long.valueOf(packetNumber));
            if (lostStreams != null) {
                for (Map.Entry<Long, List<PendingChunk>> entry : lostStreams.entrySet()) {
                    List<PendingChunk> chunks = pendingStream.get(entry.getKey());
                    if (chunks == null) {
                        chunks = new ArrayList<PendingChunk>();
                        pendingStream.put(entry.getKey(), chunks);
                    }
                    chunks.addAll(0, entry.getValue());
                }
            }
        }
    }

    // ── Send path ──

    /**
     * Builds one packet per encryption level with pending data and sends
     * them coalesced into a single UDP datagram (RFC 9000 section 12.2),
     * in the order the spec requires when more than one is present:
     * Initial, then Handshake, then 1-RTT.
     *
     * <p>Handshake and 1-RTT are built first even though Initial is
     * placed first in the datagram -- their sizes don't depend on
     * Initial's padding, but a client Initial's padding target (RFC 9000
     * section 14.1's 1200-byte minimum) does depend on theirs, once they
     * share a datagram: padding only needs to make up whatever the other
     * levels aren't already contributing.
     */
    void flush() {
        byte[] handshakeBytes = buildLevelPacketOrNull(EncryptionLevel.HANDSHAKE, 0);
        byte[] oneRttBytes = buildLevelPacketOrNull(EncryptionLevel.ONE_RTT, 0);

        int handshakeAndOneRttBytes = (handshakeBytes != null ? handshakeBytes.length : 0)
                + (oneRttBytes != null ? oneRttBytes.length : 0);
        int initialMinDatagramSize = !isServer ? Math.max(0, MIN_DATAGRAM_SIZE - handshakeAndOneRttBytes) : 0;
        byte[] initialBytes = buildLevelPacketOrNull(EncryptionLevel.INITIAL, initialMinDatagramSize);

        if (initialBytes == null && handshakeBytes == null && oneRttBytes == null) {
            return;
        }

        int totalLength = (initialBytes != null ? initialBytes.length : 0) + handshakeAndOneRttBytes;
        byte[] datagram = new byte[totalLength];
        int pos = 0;
        if (initialBytes != null) {
            System.arraycopy(initialBytes, 0, datagram, pos, initialBytes.length);
            pos += initialBytes.length;
        }
        if (handshakeBytes != null) {
            System.arraycopy(handshakeBytes, 0, datagram, pos, handshakeBytes.length);
            pos += handshakeBytes.length;
        }
        if (oneRttBytes != null) {
            System.arraycopy(oneRttBytes, 0, datagram, pos, oneRttBytes.length);
            pos += oneRttBytes.length;
        }

        // RFC 9000 section 8.1: don't send past the anti-amplification
        // limit while this peer's address isn't yet validated, checked
        // against the coalesced datagram as a whole now that multiple
        // levels' packets may share one. A datagram withheld for this
        // reason is indistinguishable, from this connection's
        // perspective, from one lost in flight -- loss-detection
        // bookkeeping for every packet built into it has already
        // happened regardless (see buildProtectedPacket), and the
        // existing "a blocked send is treated like ordinary packet loss"
        // handling (see QuicEngine.sendPacket) recovers it once more
        // receive-side credit arrives.
        if (!isServer || addressValidated || amplificationBytesSent + datagram.length <= 3 * amplificationBytesReceived) {
            engine.sendPacket(this, datagram);
            if (isServer && !addressValidated) {
                amplificationBytesSent += datagram.length;
            }
        } else if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Anti-amplification limit reached (sent=" + amplificationBytesSent
                    + ", received=" + amplificationBytesReceived + "); withholding coalesced datagram"
                    + " until the peer's address is validated");
        }

        scheduleLossDetectionTimer();
    }

    private byte[] buildLevelPacketOrNull(EncryptionLevel level, int minDatagramSize) {
        if (discarded[level.ordinal()] || sendKeys.get(level) == null) {
            return null;
        }
        try {
            return buildProtectedPacket(level, minDatagramSize);
        } catch (PacketProtectionException e) {
            LOGGER.log(Level.WARNING, "Failed to protect outgoing packet at " + level, e);
            return null;
        }
    }

    // Stream-level and connection-level send budget, per RFC 9000
    // section 18.2's parameter semantics (verified against the RFC text):
    // our send limit on a stream is always PEER-declared -- their
    // bidi_remote if we opened the stream (we're "the endpoint that
    // receives the parameter" from their point of view), their bidi_local
    // if they opened it, or their uni limit for a uni stream we opened.
    private static final int SEND_NOT_BLOCKED = 0;
    private static final int SEND_BLOCKED_BY_STREAM_LIMIT = 1;
    private static final int SEND_BLOCKED_BY_CONNECTION_LIMIT = 2;

    private long currentPeerStreamLimit(long streamId) {
        Long limit = peerMaxStreamData.get(Long.valueOf(streamId));
        return limit != null ? limit.longValue() : initialPeerStreamLimit(streamId);
    }

    private int checkSendBlocked(long streamId, int length) {
        if (connectionBytesSent + length > peerMaxData) {
            return SEND_BLOCKED_BY_CONNECTION_LIMIT;
        }
        long sent = streamBytesSent.containsKey(Long.valueOf(streamId)) ? streamBytesSent.get(Long.valueOf(streamId)).longValue() : 0;
        if (sent + length > currentPeerStreamLimit(streamId)) {
            return SEND_BLOCKED_BY_STREAM_LIMIT;
        }
        return SEND_NOT_BLOCKED;
    }

    private long initialPeerStreamLimit(long streamId) {
        if (peerTransportParameters == null) {
            return 0;
        }
        if (isUnidirectional(streamId)) {
            return peerTransportParameters.getInitialMaxStreamDataUni();
        }
        boolean weOpened = !isPeerInitiated(streamId);
        return weOpened
                ? peerTransportParameters.getInitialMaxStreamDataBidiRemote()
                : peerTransportParameters.getInitialMaxStreamDataBidiLocal();
    }

    private void recordBytesSent(long streamId, int length) {
        connectionBytesSent += length;
        Long key = Long.valueOf(streamId);
        long sent = streamBytesSent.containsKey(key) ? streamBytesSent.get(key).longValue() : 0;
        streamBytesSent.put(key, Long.valueOf(sent + length));
    }

    // The mirror image of initialPeerStreamLimit: OUR OWN declared
    // parameter, interpreted as OUR OWN receive limit, so the
    // weOpened/bidiLocal/bidiRemote mapping swaps relative to that
    // method -- our bidi_local is the limit we declared for streams we
    // ourselves initiate.
    private long initialLocalStreamLimit(long streamId) {
        if (isUnidirectional(streamId)) {
            return localTransportParameters.getInitialMaxStreamDataUni();
        }
        boolean weOpened = !isPeerInitiated(streamId);
        return weOpened
                ? localTransportParameters.getInitialMaxStreamDataBidiLocal()
                : localTransportParameters.getInitialMaxStreamDataBidiRemote();
    }

    private long currentLocalStreamLimit(long streamId) {
        Long limit = localMaxStreamData.get(Long.valueOf(streamId));
        return limit != null ? limit.longValue() : initialLocalStreamLimit(streamId);
    }

    /**
     * Raises a stream's advertised receive limit to {@code newLimit} (a
     * no-op if it is not actually higher than the current one) and
     * queues a MAX_STREAM_DATA update.
     */
    private void growStreamLimit(long streamId, long newLimit) {
        Long key = Long.valueOf(streamId);
        if (newLimit > currentLocalStreamLimit(streamId)) {
            localMaxStreamData.put(key, Long.valueOf(newLimit));
            maxStreamDataOwed.put(key, Long.valueOf(newLimit));
        }
    }

    /**
     * Grows a stream's advertised receive limit and queues a
     * MAX_STREAM_DATA update, once consumption has passed half of the
     * current window -- see the class documentation for why this is a
     * fixed-size window rather than RTT-tuned. Called as data is
     * received; see {@link #growStreamLimitOnBlocked} for the
     * complementary trigger used when the peer reports it is blocked.
     *
     * @param streamId the stream
     * @param highestOffset the highest offset+length seen on this stream so far
     */
    private void maybeGrowStreamLimit(long streamId, long highestOffset) {
        long windowSize = initialLocalStreamLimit(streamId);
        if (windowSize <= 0) {
            return;
        }
        long currentLimit = currentLocalStreamLimit(streamId);
        if (highestOffset > currentLimit - windowSize / 2) {
            growStreamLimit(streamId, highestOffset + windowSize / 2);
        }
    }

    /**
     * Unconditionally extends a stream's advertised receive limit by a
     * full window, in response to the peer reporting it is blocked
     * (STREAM_DATA_BLOCKED, RFC 9000 section 19.13).
     *
     * <p>Reusing {@link #maybeGrowStreamLimit}'s passive, receipt-driven
     * check here would not help: it is keyed off the highest offset
     * actually <em>received</em>, which by definition cannot have moved
     * since the peer stopped being able to send -- the peer being fully
     * blocked is itself the signal that growth is needed now, not a
     * data point to re-run the same threshold check against.
     */
    private void growStreamLimitOnBlocked(long streamId) {
        long windowSize = initialLocalStreamLimit(streamId);
        if (windowSize <= 0) {
            return;
        }
        growStreamLimit(streamId, currentLocalStreamLimit(streamId) + windowSize);
    }

    /**
     * Grows the connection-level advertised receive limit and queues a
     * MAX_DATA update, using the same fixed-window heuristic as
     * {@link #maybeGrowStreamLimit}.
     */
    private void maybeGrowConnectionLimit() {
        long windowSize = localTransportParameters.getInitialMaxData();
        if (windowSize <= 0) {
            return;
        }
        if (connectionBytesReceived > localMaxData - windowSize / 2) {
            localMaxData = connectionBytesReceived + windowSize / 2;
            maxDataOwed = true;
        }
    }

    /**
     * Unconditionally extends the connection-level advertised receive
     * limit by a full window -- the connection-level counterpart of
     * {@link #growStreamLimitOnBlocked}, for DATA_BLOCKED (RFC 9000
     * section 19.12).
     */
    private void growConnectionLimitOnBlocked() {
        long windowSize = localTransportParameters.getInitialMaxData();
        if (windowSize <= 0) {
            return;
        }
        localMaxData += windowSize;
        maxDataOwed = true;
    }

    /**
     * Enforces this endpoint's own advertised receive-side limits (RFC
     * 9000 section 11) against an incoming STREAM frame, updates the
     * "highest received offset" accounting the limits are grown from,
     * and triggers that growth when appropriate.
     *
     * @return true if the frame is within limits and should be
     *         delivered; false if it violated a limit -- the connection
     *         has already been closed with FLOW_CONTROL_ERROR and the
     *         caller must not deliver the data
     */
    private boolean checkAndRecordFlowControl(long streamId, long offset, int length) {
        long highestOffset = offset + length;
        Long key = Long.valueOf(streamId);
        Long previous = streamBytesReceived.get(key);
        long previousHighest = previous != null ? previous.longValue() : 0;
        if (highestOffset <= previousHighest) {
            // No new bytes implied by this frame (duplicate/retransmission
            // of already-accounted-for data) -- nothing to check or record.
            return true;
        }
        long streamLimit = currentLocalStreamLimit(streamId);
        if (highestOffset > streamLimit) {
            closeWithError(TRANSPORT_ERROR_FLOW_CONTROL_ERROR,
                    "Stream " + streamId + " exceeded advertised MAX_STREAM_DATA " + streamLimit);
            return false;
        }
        long delta = highestOffset - previousHighest;
        if (connectionBytesReceived + delta > localMaxData) {
            closeWithError(TRANSPORT_ERROR_FLOW_CONTROL_ERROR,
                    "Connection exceeded advertised MAX_DATA " + localMaxData);
            return false;
        }
        streamBytesReceived.put(key, Long.valueOf(highestOffset));
        connectionBytesReceived += delta;
        maybeGrowStreamLimit(streamId, highestOffset);
        maybeGrowConnectionLimit();
        return true;
    }

    /**
     * Builds and protects one packet at {@code level} containing every
     * currently pending frame for it, or returns {@code null} if there
     * is nothing to send at that level. Does not send it -- {@link #flush}
     * concatenates whatever levels have pending data into one coalesced
     * datagram (RFC 9000 section 12.2) and sends that as a single unit.
     *
     * @param level the encryption level to build a packet for
     * @param minDatagramSize the minimum size this packet's own padding
     *                        should pad up to, e.g. to satisfy RFC 9000
     *                        section 14.1's 1200-byte client Initial
     *                        minimum after accounting for whatever other
     *                        levels' packets {@link #flush} will
     *                        concatenate into the same datagram
     * @return the protected packet bytes, or {@code null} if there was
     *         nothing pending to send at this level
     */
    private byte[] buildProtectedPacket(EncryptionLevel level, int minDatagramSize) throws PacketProtectionException {
        boolean oneRtt = level == EncryptionLevel.ONE_RTT;
        List<PendingChunk> cryptoChunks = pendingCrypto.get(level);

        Map<Long, List<PendingChunk>> streamChunksToSend = new HashMap<Long, List<PendingChunk>>();
        if (oneRtt) {
            for (Map.Entry<Long, List<PendingChunk>> entry : pendingStream.entrySet()) {
                long streamId = entry.getKey().longValue();
                List<PendingChunk> queued = entry.getValue();
                List<PendingChunk> toSend = new ArrayList<PendingChunk>();
                for (PendingChunk chunk : queued) {
                    int blocked = checkSendBlocked(streamId, chunk.data.length);
                    if (blocked == SEND_NOT_BLOCKED) {
                        toSend.add(chunk);
                        recordBytesSent(streamId, chunk.data.length);
                    } else {
                        // RFC 9000 section 4.1: tell the peer we're blocked
                        // so it has a reason to grow its advertised limit
                        // even though (being blocked) we can't send it any
                        // more data to trigger that growth passively --
                        // without this, once a chunk doesn't fit in the
                        // remaining window, nothing would ever unblock it.
                        // Only signalled once per limit value (RFC 9000
                        // section 4.1's "SHOULD NOT send more than once for
                        // a given limit"); cleared when that limit grows.
                        if (blocked == SEND_BLOCKED_BY_STREAM_LIMIT) {
                            Long key = Long.valueOf(streamId);
                            if (streamDataBlockedSignalled.add(key)) {
                                streamDataBlockedOwed.put(key, Long.valueOf(currentPeerStreamLimit(streamId)));
                            }
                        } else if (!dataBlockedSignalled) {
                            dataBlockedSignalled = true;
                            dataBlockedOwed = true;
                        }
                        break; // preserve order: don't skip ahead of a blocked chunk
                    }
                }
                if (!toSend.isEmpty()) {
                    streamChunksToSend.put(entry.getKey(), toSend);
                }
            }
        }

        boolean includeAck = ackOwed[level.ordinal()];
        boolean includeHandshakeDone = oneRtt && handshakeDoneOwed;
        boolean includePing = pendingPing[level.ordinal()];
        List<long[]> resetsToSend = oneRtt ? new ArrayList<long[]>(pendingResetStreams) : java.util.Collections.<long[]>emptyList();
        List<ConnectionIdEntry> newCidsToSend = oneRtt
                ? connectionIdManager.drainPendingIssuance() : java.util.Collections.<ConnectionIdEntry>emptyList();
        long[] retiresToSend = oneRtt ? connectionIdManager.drainPendingRetirement() : new long[0];
        boolean includeMaxData = oneRtt && maxDataOwed;
        Map<Long, Long> maxStreamDataToSend = oneRtt
                ? new HashMap<Long, Long>(maxStreamDataOwed) : java.util.Collections.<Long, Long>emptyMap();
        boolean includeDataBlocked = oneRtt && dataBlockedOwed;
        Map<Long, Long> streamDataBlockedToSend = oneRtt
                ? new HashMap<Long, Long>(streamDataBlockedOwed) : java.util.Collections.<Long, Long>emptyMap();

        boolean nothingToSend = cryptoChunks.isEmpty() && streamChunksToSend.isEmpty() && !includeAck
                && !includeHandshakeDone && !includePing && resetsToSend.isEmpty() && newCidsToSend.isEmpty()
                && retiresToSend.length == 0 && !includeMaxData && maxStreamDataToSend.isEmpty()
                && !includeDataBlocked && streamDataBlockedToSend.isEmpty();
        if (nothingToSend) {
            return null;
        }

        int frameBytes = 0;
        for (PendingChunk chunk : cryptoChunks) {
            frameBytes += QuicFrameWriter.cryptoLength(chunk.offset, chunk.data.length);
        }
        for (Map.Entry<Long, List<PendingChunk>> entry : streamChunksToSend.entrySet()) {
            for (PendingChunk chunk : entry.getValue()) {
                frameBytes += QuicFrameWriter.streamLength(entry.getKey().longValue(), chunk.offset, chunk.data.length);
            }
        }
        long[][] ackRanges = { { largestReceived[level.ordinal()], largestReceived[level.ordinal()] } };
        if (includeAck) {
            frameBytes += QuicFrameWriter.ackLength(ackRanges, 0);
        }
        if (includeHandshakeDone) {
            frameBytes += QuicFrameWriter.handshakeDoneLength();
        }
        if (includePing) {
            frameBytes += QuicFrameWriter.pingLength();
        }
        for (long[] reset : resetsToSend) {
            frameBytes += QuicFrameWriter.resetStreamLength(reset[0], reset[1], reset[2]);
        }
        for (ConnectionIdEntry entry : newCidsToSend) {
            frameBytes += QuicFrameWriter.newConnectionIdLength(entry.getSequenceNumber(), 0,
                    entry.getConnectionId(), entry.getStatelessResetToken());
        }
        for (long sequenceNumber : retiresToSend) {
            frameBytes += QuicFrameWriter.retireConnectionIdLength(sequenceNumber);
        }
        if (includeMaxData) {
            frameBytes += QuicFrameWriter.maxDataLength(localMaxData);
        }
        for (Map.Entry<Long, Long> entry : maxStreamDataToSend.entrySet()) {
            frameBytes += QuicFrameWriter.maxStreamDataLength(entry.getKey().longValue(), entry.getValue().longValue());
        }
        if (includeDataBlocked) {
            frameBytes += QuicFrameWriter.dataBlockedLength(peerMaxData);
        }
        for (Map.Entry<Long, Long> entry : streamDataBlockedToSend.entrySet()) {
            frameBytes += QuicFrameWriter.streamDataBlockedLength(entry.getKey().longValue(), entry.getValue().longValue());
        }

        boolean longHeader = !oneRtt;
        long packetNumber = sendPacketNumber[level.ordinal()]++;
        int pnLength = PacketNumberCodec.encodedLength(packetNumber, -1);
        int packetType = level == EncryptionLevel.INITIAL ? LongHeaderCodec.TYPE_INITIAL : LongHeaderCodec.TYPE_HANDSHAKE;

        // RFC 9001 section 5.4.2: the header-protection sample is taken
        // starting 4 bytes after the (assumed 4-byte) packet number field
        // and is QuicAeadAlgorithm.SAMPLE_LENGTH bytes long -- every
        // packet, not just an Initial-carrying datagram, must carry
        // enough ciphertext for that sample to exist, or applying the
        // header-protection mask reads past the end of the packet.
        int hpSamplePadding = Math.max(0,
                4 + QuicAeadAlgorithm.SAMPLE_LENGTH - pnLength - QuicAeadAlgorithm.TAG_LENGTH - frameBytes);

        int paddingBytes = 0;
        byte[] header;
        while (true) {
            header = longHeader
                    ? LongHeaderCodec.build(packetType, 1, peerConnectionId, ourConnectionId,
                            packetType == LongHeaderCodec.TYPE_INITIAL ? retryToken : EMPTY_TOKEN,
                            packetNumber, pnLength, frameBytes + paddingBytes + QuicAeadAlgorithm.TAG_LENGTH)
                    : ShortHeaderCodec.build(peerConnectionId, false, packetNumber, pnLength);
            int required = minDatagramSize - (header.length + frameBytes + paddingBytes + QuicAeadAlgorithm.TAG_LENGTH);
            int nextPadding = Math.max(hpSamplePadding, Math.max(0, paddingBytes + required));
            if (nextPadding == paddingBytes) {
                break;
            }
            paddingBytes = nextPadding;
        }
        int totalFrameBytes = frameBytes + paddingBytes;

        ByteBuffer payload = ByteBuffer.allocate(totalFrameBytes);
        List<PendingChunk> sentCryptoThisPacket = new ArrayList<PendingChunk>();
        for (PendingChunk chunk : cryptoChunks) {
            QuicFrameWriter.writeCrypto(payload, chunk.offset, chunk.data);
            sentCryptoThisPacket.add(chunk);
        }
        cryptoChunks.clear();
        if (!sentCryptoThisPacket.isEmpty()) {
            sentCrypto.get(level).put(Long.valueOf(packetNumber), sentCryptoThisPacket);
        }

        Map<Long, List<PendingChunk>> sentStreamThisPacket = new HashMap<Long, List<PendingChunk>>();
        for (Map.Entry<Long, List<PendingChunk>> entry : streamChunksToSend.entrySet()) {
            long streamId = entry.getKey().longValue();
            for (PendingChunk chunk : entry.getValue()) {
                QuicFrameWriter.writeStream(payload, streamId, chunk.offset, chunk.data, chunk.fin);
            }
            List<PendingChunk> queued = pendingStream.get(entry.getKey());
            queued.removeAll(entry.getValue());
            if (queued.isEmpty()) {
                pendingStream.remove(entry.getKey());
                QuicStreamEndpoint stream = streams.get(entry.getKey());
                if (stream != null) {
                    stream.notifyWriteReady();
                }
            }
            sentStreamThisPacket.put(entry.getKey(), entry.getValue());
        }
        if (!sentStreamThisPacket.isEmpty()) {
            sentStream.put(Long.valueOf(packetNumber), sentStreamThisPacket);
        }

        if (includeAck) {
            QuicFrameWriter.writeAck(payload, ackRanges, 0);
            ackOwed[level.ordinal()] = false;
        }
        if (includeHandshakeDone) {
            QuicFrameWriter.writeHandshakeDone(payload);
            handshakeDoneOwed = false;
        }
        if (includePing) {
            QuicFrameWriter.writePing(payload);
            pendingPing[level.ordinal()] = false;
        }
        for (long[] reset : resetsToSend) {
            QuicFrameWriter.writeResetStream(payload, reset[0], reset[1], reset[2]);
        }
        if (!resetsToSend.isEmpty()) {
            pendingResetStreams.removeAll(resetsToSend);
        }
        for (ConnectionIdEntry entry : newCidsToSend) {
            QuicFrameWriter.writeNewConnectionId(payload, entry.getSequenceNumber(), 0,
                    entry.getConnectionId(), entry.getStatelessResetToken());
        }
        for (long sequenceNumber : retiresToSend) {
            QuicFrameWriter.writeRetireConnectionId(payload, sequenceNumber);
        }
        if (includeMaxData) {
            QuicFrameWriter.writeMaxData(payload, localMaxData);
            maxDataOwed = false;
        }
        for (Map.Entry<Long, Long> entry : maxStreamDataToSend.entrySet()) {
            QuicFrameWriter.writeMaxStreamData(payload, entry.getKey().longValue(), entry.getValue().longValue());
        }
        maxStreamDataOwed.keySet().removeAll(maxStreamDataToSend.keySet());
        if (includeDataBlocked) {
            QuicFrameWriter.writeDataBlocked(payload, peerMaxData);
            dataBlockedOwed = false;
        }
        for (Map.Entry<Long, Long> entry : streamDataBlockedToSend.entrySet()) {
            QuicFrameWriter.writeStreamDataBlocked(payload, entry.getKey().longValue(), entry.getValue().longValue());
        }
        streamDataBlockedOwed.keySet().removeAll(streamDataBlockedToSend.keySet());
        if (paddingBytes > 0) {
            QuicFrameWriter.writePadding(payload, paddingBytes);
        }
        payload.flip();
        byte[] plaintext = new byte[payload.remaining()];
        payload.get(plaintext);

        PacketProtectionKeys keys = sendKeys.get(level);
        byte[] ciphertext = PacketProtection.seal(keys, packetNumber, header, plaintext);
        byte[] packet = new byte[header.length + ciphertext.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(ciphertext, 0, packet, header.length, ciphertext.length);

        int pnOffset = header.length - pnLength;
        byte[] sample = new byte[QuicAeadAlgorithm.SAMPLE_LENGTH];
        System.arraycopy(packet, pnOffset + 4, sample, 0, QuicAeadAlgorithm.SAMPLE_LENGTH);
        byte[] mask = PacketProtection.headerProtectionMask(keys, sample);
        PacketProtection.xorFirstByte(packet, mask, longHeader);
        PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);

        boolean ackEliciting = !sentCryptoThisPacket.isEmpty() || !sentStreamThisPacket.isEmpty()
                || includeHandshakeDone || includePing || !resetsToSend.isEmpty() || !newCidsToSend.isEmpty()
                || retiresToSend.length > 0 || includeMaxData || !maxStreamDataToSend.isEmpty()
                || includeDataBlocked || !streamDataBlockedToSend.isEmpty();
        lossDetector.onPacketSent(level, packetNumber, System.currentTimeMillis(), ackEliciting, true, packet.length);
        return packet;
    }

    // ── Timers ──

    private boolean receivedHandshakeAck;

    private boolean peerAddressValidated() {
        return isServer || receivedHandshakeAck || handshakeConfirmed;
    }

    private void scheduleLossDetectionTimer() {
        if (closed) {
            return;
        }
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
        }
        long now = System.currentTimeMillis();
        boolean hasHandshakeKeys = sendKeys.get(EncryptionLevel.HANDSHAKE) != null;
        long deadline = lossDetector.getLossDetectionTimeout(false, peerAddressValidated(), hasHandshakeKeys,
                peerMaxAckDelay(), now);
        if (deadline == LossDetector.NO_TIMEOUT) {
            return;
        }
        long delay = Math.max(0, deadline - now);
        timerHandle = engine.scheduleTimer(delay, new Runnable() {
            @Override
            public void run() {
                onLossDetectionTimeout();
            }
        });
    }

    private void onLossDetectionTimeout() {
        if (closed) {
            return;
        }
        boolean hasHandshakeKeys = sendKeys.get(EncryptionLevel.HANDSHAKE) != null;
        LossDetector.TimeoutResult result = lossDetector.onLossDetectionTimeout(peerAddressValidated(), hasHandshakeKeys,
                peerMaxAckDelay(), System.currentTimeMillis());
        EncryptionLevel lossSpace = result.getLossSpace();
        if (lossSpace != null) {
            for (SentPacket lost : result.getNewlyLost()) {
                requeueLostPacket(lossSpace, lost.getPacketNumber());
            }
        } else {
            // Probe Timeout: nothing was naturally queued to retransmit,
            // so send a bare PING to elicit an ACK and keep the
            // connection alive (RFC 9002 Appendix A.9).
            EncryptionLevel probeSpace = result.getProbeSpace();
            if (probeSpace != null && sendKeys.get(probeSpace) != null) {
                pendingPing[probeSpace.ordinal()] = true;
            }
        }
        flush();
        scheduleLossDetectionTimer();
    }

    // ── Close ──

    /** RFC 9000 section 20.1: an endpoint received more data than the flow control limits it advertised permit. */
    private static final long TRANSPORT_ERROR_FLOW_CONTROL_ERROR = 0x3;
    /** RFC 9000 section 20.1: a transport parameter was received with a value not permitted for its type, e.g. a mismatched retry_source_connection_id. */
    private static final long TRANSPORT_ERROR_TRANSPORT_PARAMETER_ERROR = 0x8;

    /**
     * Closes the connection with a specific transport error code (RFC
     * 9000 section 11), e.g. a flow-control violation.
     *
     * @param errorCode the RFC 9000 section 20.1 transport error code
     * @param reason a human-readable reason phrase
     */
    private void closeWithError(long errorCode, String reason) {
        deferredCloseErrorCode = errorCode;
        deferredCloseReason = reason;
        close();
    }

    /**
     * Closes the connection, sending CONNECTION_CLOSE if the handshake
     * had progressed far enough to have usable keys.
     */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
        }
        for (EncryptionLevel level : EncryptionLevel.values()) {
            PacketProtectionKeys keys = sendKeys.get(level);
            if (keys == null) {
                continue;
            }
            try {
                sendConnectionClose(level, keys);
                break; // RFC 9000 section 10.2.1: send at only the highest available level
            } catch (PacketProtectionException e) {
                LOGGER.log(Level.FINE, "Failed to send CONNECTION_CLOSE", e);
            }
        }
        for (QuicStreamEndpoint stream : streams.values()) {
            stream.markClosed();
            stream.getHandler().disconnected();
        }
        streams.clear();
    }

    private void sendConnectionClose(EncryptionLevel level, PacketProtectionKeys keys) throws PacketProtectionException {
        String reason = deferredCloseReason != null ? deferredCloseReason : "";
        long errorCode = deferredCloseErrorCode;
        int frameBytes = QuicFrameWriter.connectionCloseLength(false, errorCode, reason);
        boolean longHeader = level != EncryptionLevel.ONE_RTT;
        long packetNumber = sendPacketNumber[level.ordinal()]++;
        int pnLength = PacketNumberCodec.encodedLength(packetNumber, -1);
        int packetType = level == EncryptionLevel.INITIAL ? LongHeaderCodec.TYPE_INITIAL : LongHeaderCodec.TYPE_HANDSHAKE;
        byte[] header = longHeader
                ? LongHeaderCodec.build(packetType, 1, peerConnectionId, ourConnectionId,
                        packetType == LongHeaderCodec.TYPE_INITIAL ? retryToken : EMPTY_TOKEN,
                        packetNumber, pnLength, frameBytes + QuicAeadAlgorithm.TAG_LENGTH)
                : ShortHeaderCodec.build(peerConnectionId, false, packetNumber, pnLength);

        ByteBuffer payload = ByteBuffer.allocate(frameBytes);
        QuicFrameWriter.writeConnectionClose(payload, false, errorCode, 0, reason);
        payload.flip();
        byte[] plaintext = new byte[payload.remaining()];
        payload.get(plaintext);

        byte[] ciphertext = PacketProtection.seal(keys, packetNumber, header, plaintext);
        byte[] packet = new byte[header.length + ciphertext.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(ciphertext, 0, packet, header.length, ciphertext.length);

        int pnOffset = header.length - pnLength;
        byte[] sample = new byte[QuicAeadAlgorithm.SAMPLE_LENGTH];
        System.arraycopy(packet, pnOffset + 4, sample, 0, QuicAeadAlgorithm.SAMPLE_LENGTH);
        byte[] mask = PacketProtection.headerProtectionMask(keys, sample);
        PacketProtection.xorFirstByte(packet, mask, longHeader);
        PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);

        engine.sendPacket(this, packet);
    }

    // ── QuicTlsEngineListener ──

    @Override
    public void cryptoDataReady(EncryptionLevel level, long offset, byte[] data) {
        pendingCrypto.get(level).add(new PendingChunk(offset, data));
        requestFlush();
    }

    @Override
    public void handshakeSecretsAvailable() {
        TlsConstants.CipherSuite cipher = selectedCipher();
        // A proper mapping, not a two-way ternary: an unrecognised cipher
        // must fail loudly rather than silently be treated as AES-128-GCM
        // (which would derive keys of the wrong length/interpretation and
        // fail decryption in a confusing way instead of here).
        switch (cipher) {
            case TLS_AES_128_GCM_SHA256:
                hkdf = Hkdf.sha256();
                aead = QuicAeadAlgorithm.AES_128_GCM;
                break;
            case TLS_AES_256_GCM_SHA384:
                hkdf = Hkdf.sha384();
                aead = QuicAeadAlgorithm.AES_256_GCM;
                break;
            case TLS_CHACHA20_POLY1305_SHA256:
                hkdf = Hkdf.sha256();
                aead = QuicAeadAlgorithm.CHACHA20_POLY1305;
                break;
            default:
                throw new IllegalStateException("Unsupported QUIC cipher suite: " + cipher);
        }
        byte[] clientSecret = tlsEngine.getClientHandshakeTrafficSecret();
        byte[] serverSecret = tlsEngine.getServerHandshakeTrafficSecret();
        deriveDirectionalKeys(EncryptionLevel.HANDSHAKE, clientSecret, serverSecret);
    }

    @Override
    public void handshakeFinished() {
        byte[] clientSecret = tlsEngine.getClientApplicationTrafficSecret();
        byte[] serverSecret = tlsEngine.getServerApplicationTrafficSecret();
        deriveDirectionalKeys(EncryptionLevel.ONE_RTT, clientSecret, serverSecret);
        established = true;
        if (isServer) {
            handshakeDoneOwed = true;
            handshakeConfirmed = true; // RFC 9001 section 4.1.2: sending HANDSHAKE_DONE is the server's own confirmation
            requestFlush();
        } else {
            notifyClientHandshakeComplete();
        }
    }

    @Override
    public void transportParametersReceived(TransportParameters transportParameters) {
        if (!isServer) {
            // RFC 9000 section 17.2.5.2: an off-path attacker that
            // spoofed an earlier Retry can be detected because it can't
            // also control the eventual (encrypted) transport parameters
            // -- so the real server's retry_source_connection_id must
            // match the Retry this client actually processed, and must
            // be absent if no Retry occurred at all.
            byte[] retryScid = transportParameters.getRetrySourceConnectionId();
            boolean mismatch = expectedRetrySourceConnectionId != null
                    ? !java.util.Arrays.equals(retryScid, expectedRetrySourceConnectionId)
                    : retryScid != null;
            if (mismatch) {
                closeWithError(TRANSPORT_ERROR_TRANSPORT_PARAMETER_ERROR, "retry_source_connection_id mismatch");
                return;
            }
        }
        this.peerTransportParameters = transportParameters;
        peerMaxData = transportParameters.getInitialMaxData();
    }

    private TlsConstants.CipherSuite selectedCipher() {
        return isServer
                ? ((org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine) tlsEngine).getSelectedCipher()
                : ((org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine) tlsEngine).getSelectedCipher();
    }

    private void deriveDirectionalKeys(EncryptionLevel level, byte[] clientSecret, byte[] serverSecret) {
        PacketProtectionKeys clientKeys = PacketProtectionKeys.derive(hkdf, clientSecret, aead);
        PacketProtectionKeys serverKeys = PacketProtectionKeys.derive(hkdf, serverSecret, aead);
        if (isServer) {
            sendKeys.put(level, serverKeys);
            recvKeys.put(level, clientKeys);
        } else {
            sendKeys.put(level, clientKeys);
            recvKeys.put(level, serverKeys);
        }
    }

    // Client-only: fires once the client's own handshake has finished,
    // handing off the now-usable connection to whichever caller is
    // waiting for it.
    private void notifyClientHandshakeComplete() {
        if (clientConnectionAcceptedHandler != null) {
            QuicEngine.ConnectionAcceptedHandler handlerToNotify = clientConnectionAcceptedHandler;
            clientConnectionAcceptedHandler = null;
            handlerToNotify.connectionAccepted(this);
        } else if (clientHandler != null) {
            ProtocolHandler handlerToNotify = clientHandler;
            clientHandler = null;
            openStream(handlerToNotify);
        }
    }

    /** One chunk of CRYPTO or STREAM data queued for sending. */
    private static final class PendingChunk {

        final long offset;
        final byte[] data;
        final boolean fin;

        PendingChunk(long offset, byte[] data) {
            this(offset, data, false);
        }

        PendingChunk(long offset, byte[] data, boolean fin) {
            this.offset = offset;
            this.data = data;
            this.fin = fin;
        }
    }
}
