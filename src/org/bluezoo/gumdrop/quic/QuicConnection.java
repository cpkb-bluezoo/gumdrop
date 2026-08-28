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
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import tech.kwik.agent15.NewSessionTicket;
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
import org.bluezoo.gumdrop.quic.packet.StatelessResetPacket;
import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.quic.recovery.LossDetector;
import org.bluezoo.gumdrop.quic.recovery.RttEstimator;
import org.bluezoo.gumdrop.quic.recovery.SentPacket;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;
import org.bluezoo.gumdrop.quic.tls.Hkdf;
import org.bluezoo.gumdrop.quic.tls.InitialSecrets;
import org.bluezoo.gumdrop.quic.tls.QuicTlsEngine;
import org.bluezoo.gumdrop.quic.tls.QuicTlsEngineListener;
import org.bluezoo.gumdrop.quic.tls.StreamReassembler;

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
 * one packet per level. RFC 9000 section 8.1's address validation is
     * implemented both ways: the anti-amplification byte limit, and the
     * Retry-packet mechanism (see {@link QuicTransportFactory#setRequireRetry};
     * QUIC listeners enable Retry by default).
 *
 * <p>Connection migration (RFC 9000 section 9) is implemented in a
 * deliberately narrowed, passive/reactive form: {@link #receive} detects
 * the peer's address changing (e.g. NAT rebinding) once a packet from the
 * new address decrypts successfully with the existing 1-RTT keys (proof
 * it isn't spoofed), validates the new path via PATH_CHALLENGE/
 * PATH_RESPONSE before switching {@code remoteAddress} to it, and rotates
 * to a fresh peer connection ID from {@link ConnectionIdManager} if one is
 * available. Deliberately out of scope: actively probing additional paths
 * or otherwise initiating migration on this endpoint's own accord,
 * concurrent multi-path use, {@code preferred_address}, a separate
 * anti-amplification budget for the new path before it validates (the
 * existing budget is reused as-is), and stateless-reset detection when
 * a datagram cannot be decrypted or parsed as a valid short-header
 * packet but its tail matches a known peer reset token (RFC 9000
 * section 10.3).
 *
 * <p>Out-of-order and overlapping STREAM data is reassembled into stream
 * order before delivery to the handler, via a per-stream {@link
 * org.bluezoo.gumdrop.quic.tls.StreamReassembler} -- the same class
 * {@link org.bluezoo.gumdrop.quic.tls.CryptoStreamBuffer} uses for CRYPTO
 * data, since both are the same reassembly problem (RFC 9000 section
 * 2.2). Unlike CRYPTO data, STREAM reassembly needs no independent
 * buffering cap: {@code checkAndRecordFlowControl} already bounds how far
 * ahead of the delivered cursor a peer can push data at all.
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
 * <p>Stream concurrency (RFC 9000 section 4.6) is honoured the same way:
 * {@link #openStream} / {@link #openUnidirectionalStream} never mint a
 * stream ID the peer has not granted via {@code initial_max_streams_*}
 * and subsequent {@code MAX_STREAMS} frames. Opens that would exceed the
 * current credit are queued and completed from
 * {@link ProtocolHandler#connected} once the limit lifts;
 * {@code STREAMS_BLOCKED} is sent while waiting. A STREAM/RESET_STREAM/
 * STREAM_DATA_BLOCKED frame that would open a peer-initiated stream
 * beyond this endpoint's own advertised limit is a
 * {@code STREAM_LIMIT_ERROR}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QuicConnection implements QuicTlsEngineListener {

    private static final Logger LOGGER = Logger.getLogger(QuicConnection.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.quic.L10N");

    /** RFC 9000 section 14.1: every implementation must support at least this size. */
    static final int MIN_DATAGRAM_SIZE = 1200;

    // RFC 9000 section 9.3: bounds how much amplification an attacker
    // spoofing many distinct source addresses can extract (each
    // concurrently-validated candidate = one padded PATH_CHALLENGE per
    // retry to an unproven address), while comfortably covering benign
    // races such as a NAT re-numbering twice in quick succession.
    private static final int MAX_CONCURRENT_PATH_VALIDATIONS = 3;

    // How long an address stays "cooled down" after this connection
    // deliberately migrates away from it, before traffic from it is
    // eligible to be treated as a fresh migration candidate again --
    // see recentlyMigratedFromAddresses below. Generous relative to
    // path validation's own timing (bounded by max(3xPTO, 6xkInitialRtt),
    // typically a couple of seconds) so it comfortably outlasts any
    // immediate straggling traffic on the old path, without being a
    // permanent block on an address that might legitimately reappear
    // later (e.g. a NAT mapping flapping back).
    private static final long MIGRATION_COOLDOWN_MILLIS = 10_000L;

    // Bounds recentlyMigratedFromAddresses the same way
    // MAX_CONCURRENT_PATH_VALIDATIONS bounds pathValidationAttempts --
    // an attacker forcing many migrations shouldn't grow this without
    // limit either.
    private static final int MAX_RECENTLY_MIGRATED_FROM = 8;

    private static final byte[] EMPTY_TOKEN = new byte[0];

    // RFC 9000 section 18.2's default, used unconditionally here since
    // this endpoint never sends a non-default ack_delay_exponent
    // transport parameter -- both the encoding of this endpoint's own
    // outgoing ACK Delay field and the peer's interpretation of it rely
    // on this same default applying on both sides.
    private static final int DEFAULT_ACK_DELAY_EXPONENT = 3;

    private final QuicEngine engine;
    private final boolean isServer;
    private final InetSocketAddress localAddress;
    // volatile: unlike every other piece of connection state (touched
    // only on the connection's own SelectorLoop thread), getRemoteAddress()
    // is a public accessor callable from any thread, and this field is no
    // longer final now that a validated migration (see completeMigration)
    // can change it after construction -- a final field's value is
    // guaranteed visible across threads once safely published, but a
    // plain mutable field isn't, and callers of getRemoteAddress() must
    // see the update promptly rather than a stale pre-migration value.
    private volatile InetSocketAddress remoteAddress;
    private QuicTlsEngine tlsEngine;
    // Client-only: the SNI name passed to startHandshake, kept around so
    // newSessionTicketReceived can key the session-ticket cache by it
    // (falling back to remoteAddress's host if unset, e.g. DoQ, which
    // doesn't use SNI).
    private String serverName;
    private final TransportParameters localTransportParameters;
    private final byte[] connectionIdStaticKey;
    private final long handshakeStartTime = System.currentTimeMillis();

    private final byte[] ourConnectionId;

    /**
     * The connection ID currently used to address the peer. Learned once
     * during the handshake (from the peer's first long-header response's
     * Source Connection ID for a client; known immediately at accept time
     * for a server), and thereafter changed only on a validated
     * connection migration (see {@link #completeMigration}), which
     * rotates it to a fresh entry from the connection ID manager's peer
     * pool so the two paths aren't linkable by connection ID alone
     * (RFC 9000 section 9.5).
     */
    private byte[] peerConnectionId;
    private boolean peerConnectionIdLearned;
    // The sequence number (within connectionIdManager's peer pool) that
    // peerConnectionId currently corresponds to -- needed to retire it via
    // connectionIdManager.retirePeerConnectionId when rotating to a new one.
    private long activePeerConnectionIdSequence;

    private ConnectionIdManager connectionIdManager;
    private TransportParameters peerTransportParameters;

    private final EnumMap<EncryptionLevel, PacketProtectionKeys> sendKeys = new EnumMap<EncryptionLevel, PacketProtectionKeys>(
            EncryptionLevel.class);
    private final EnumMap<EncryptionLevel, PacketProtectionKeys> recvKeys = new EnumMap<EncryptionLevel, PacketProtectionKeys>(
            EncryptionLevel.class);
    // 0-RTT (RFC 9001 section 4.6.1) keys, derived from the single client
    // early traffic secret -- deliberately not a third EncryptionLevel
    // value (see that enum's own class documentation): 0-RTT is
    // client-to-server only, so only one direction ever needs keys per
    // role -- the client only ever sends with these, the server only
    // ever receives with them. Packet-number/loss-detection space is
    // still shared with ONE_RTT (RFC 9000 section 12.3), so these exist
    // purely as an extra axis of key material, not a fourth packet-number
    // space.
    private PacketProtectionKeys zeroRttSendKeys;
    private PacketProtectionKeys zeroRttRecvKeys;

    // Client-only: tracks this connection's own 0-RTT attempt, if any.
    // NONE until a ticket is presented and keys are derived; OFFERED
    // from then until the server's EncryptedExtensions arrives and says
    // which way it went (see earlyDataOutcomeKnown).
    private enum ZeroRttState { NONE, OFFERED, ACCEPTED, REJECTED }
    private ZeroRttState zeroRttState = ZeroRttState.NONE;

    // Client-only: fired once, right after 0-RTT send keys become
    // available (before the handshake otherwise completes), so a caller
    // can open a stream and queue 0-RTT-eligible data immediately. See
    // QuicEngine.EarlyDataHandler.
    private QuicEngine.EarlyDataHandler earlyDataHandler;
    private final EnumMap<EncryptionLevel, List<PendingChunk>> pendingCrypto = new EnumMap<EncryptionLevel, List<PendingChunk>>(
            EncryptionLevel.class);
    private final EnumMap<EncryptionLevel, Map<Long, List<PendingChunk>>> sentCrypto = new EnumMap<EncryptionLevel, Map<Long, List<PendingChunk>>>(
            EncryptionLevel.class);
    private final long[] sendPacketNumber = new long[EncryptionLevel.values().length];
    private final long[] largestReceived = { -1, -1, -1 };
    // Wall-clock time this endpoint actually received the packet numbered
    // largestReceived[level], updated only when a new largest arrives --
    // RFC 9000 section 13.2.5's ACK Delay field measures elapsed time
    // since *that* receipt, not since some other packet in the range.
    private final long[] largestReceivedTime = { -1, -1, -1 };
    private final boolean[] ackOwed = new boolean[EncryptionLevel.values().length];
    // Packet numbers received (ack-eliciting) but not yet *confirmed
    // received by the peer* -- RFC 9000 section 13.2.1 requires an ACK
    // frame to acknowledge every received packet number, not just the
    // largest, since any of them may not have been acked before a later
    // one arrived (e.g. a 0-RTT packet followed shortly after by a 1-RTT
    // one, before this endpoint got a chance to ACK the first). An entry
    // is retired only once this endpoint learns the peer actually got an
    // ACK frame covering it (see sentAckCoverage/retireAcknowledgedRanges)
    // -- RFC 9000 section 13.2.4 -- not merely once an ACK frame covering
    // it has been *written*, so a lost/withheld/failed-to-send ACK
    // datagram doesn't permanently forget the packet numbers it covered.
    private final EnumMap<EncryptionLevel, TreeSet<Long>> receivedUnacked =
            new EnumMap<EncryptionLevel, TreeSet<Long>>(EncryptionLevel.class);
    // RFC 9000 section 13.2.4: for each of this endpoint's own sent
    // packets that carried an ACK frame, the peer-originated packet
    // numbers that ACK frame covered -- keyed by this endpoint's own
    // packet number, per level. Consulted once that own packet is itself
    // newly acked (the covered entries can finally be retired from
    // receivedUnacked, see retireAcknowledgedRanges) or newly lost (the
    // tracking entry is simply discarded; receivedUnacked was never
    // touched for it, so those packet numbers are already guaranteed to
    // be included in the next ACK this endpoint sends).
    private final EnumMap<EncryptionLevel, Map<Long, long[]>> sentAckCoverage =
            new EnumMap<EncryptionLevel, Map<Long, long[]>>(EncryptionLevel.class);
    private final boolean[] discarded = new boolean[EncryptionLevel.values().length];
    // Set on a Probe Timeout when nothing else was queued to naturally retransmit (RFC 9002 Appendix A.9).
    private final boolean[] pendingPing = new boolean[EncryptionLevel.values().length];

    // STREAM data pending send, keyed by stream ID; only ever flushed at ONE_RTT.
    private final Map<Long, List<PendingChunk>> pendingStream = new HashMap<Long, List<PendingChunk>>();
    // RFC 9218: higher values are sent sooner when multiplexing STREAM frames.
    private final Map<Long, Integer> streamSendPriority = new HashMap<Long, Integer>();
    private final Map<Long, Long> streamSendOffset = new HashMap<Long, Long>();
    // Per-stream reassembler for received data -- distinct from
    // checkAndRecordFlowControl's streamBytesReceived (which tracks the
    // highest offset+length ever SEEN, purely for byte-budget accounting,
    // regardless of gaps). A reassembler's getNextOffset() is the highest
    // offset actually delivered to the application in contiguous order,
    // used to decide when a FIN is safe to act on -- see
    // streamFrameReceived.
    private final Map<Long, StreamReassembler> streamReassemblers = new HashMap<Long, StreamReassembler>();
    // A FIN seen at an offset beyond what's been contiguously delivered so
    // far (see streamFrameReceived) -- even with reassembly, the frame
    // carrying FIN can itself be out of order (its offset ahead of the
    // reassembler's current cursor), so it can't be acted on immediately:
    // doing so would retire the stream and let the peer send an empty
    // close in response to the handler's disconnected() before its real
    // content -- still buffered in the reassembler, waiting on an earlier
    // gap to close -- has even been delivered, orphaning that content
    // when it does arrive (observed as a spurious re-accept of the same
    // stream ID). Held here until a later delivery catches up to this
    // offset.
    private final Map<Long, Long> pendingFinOffset = new HashMap<Long, Long>();
    // packetNumber (ONE_RTT) -> streamId -> chunks sent in that packet, for retransmission on loss.
    private final Map<Long, Map<Long, List<PendingChunk>>> sentStream = new HashMap<Long, Map<Long, List<PendingChunk>>>();
    // Client-only: same shape as sentStream, but for chunks sent as 0-RTT
    // (see buildZeroRttPacketOrNull) -- kept deliberately separate from
    // sentStream so a since-rejected 0-RTT attempt's chunks can be
    // unambiguously identified and moved back to pendingStream for a
    // clean resend at 1-RTT (see discardZeroRttDataAndKeys).
    private final Map<Long, Map<Long, List<PendingChunk>>> sentZeroRttStream = new HashMap<Long, Map<Long, List<PendingChunk>>>();
    // Each entry: {streamId, applicationErrorCode, finalSize}, owed a RESET_STREAM frame.
    private final List<long[]> pendingResetStreams = new ArrayList<long[]>();
    // RFC 9221 DATAGRAM payloads queued for the next 1-RTT packet. Not
    // retransmitted if the packet is lost -- unreliable by design.
    private final List<byte[]> pendingDatagrams = new ArrayList<byte[]>();
    private long peerMaxDatagramFrameSize;
    private ProtocolHandler datagramHandler;

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
    private final Set<Long> streamDataBlockedSignalled = new HashSet<Long>();

    // RFC 9000 section 4.6 / 19.11: the peer's current stream-concurrency
    // credit (initial_max_streams_* from transport parameters, then grown
    // by MAX_STREAMS). Zero until those parameters arrive, so locally
    // initiated opens before the handshake cannot emit STREAM frames for
    // IDs the peer has not granted. localMaxStreams* is this endpoint's
    // own advertised receive-side ceiling.
    private long peerMaxStreamsBidi;
    private long peerMaxStreamsUni;
    private long localMaxStreamsBidi;
    private long localMaxStreamsUni;
    private final List<ProtocolHandler> pendingOpenBidi = new ArrayList<ProtocolHandler>();
    private final List<ProtocolHandler> pendingOpenUni = new ArrayList<ProtocolHandler>();
    private boolean streamsBlockedBidiOwed;
    private boolean streamsBlockedBidiSignalled;
    private boolean streamsBlockedUniOwed;
    private boolean streamsBlockedUniSignalled;
    private boolean maxStreamsBidiOwed;
    private boolean maxStreamsUniOwed;

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
    // RFC 9001 section 4.9.1: distinct from addressValidated above --
    // this is the server-side trigger for discarding Initial keys ("a
    // server MUST discard Initial keys when it first successfully
    // processes a Handshake packet"), tracked separately even though
    // both happen to be set by the same event, so the two RFC-distinct
    // concepts don't become accidentally coupled if either's trigger
    // condition ever changes independently. sentHandshakePacket is the
    // client-side mirror ("a client MUST discard Initial keys when it
    // first sends a Handshake packet"). Both are persistent flags, not
    // recomputed per flush -- see the two discardEncryptionLevel call
    // sites in flush(), which must keep retrying every flush even on a
    // cycle that builds nothing new at HANDSHAKE, since
    // discardEncryptionLevel itself can defer past its first attempt
    // (see its own javadoc).
    private boolean receivedHandshakePacket;
    private boolean sentHandshakePacket;

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

    // RFC 9000 section 9: passive/reactive connection migration only --
    // detecting the peer's own address changing (e.g. NAT rebinding) and
    // validating the new path before switching to it. There is no
    // support here for deliberately probing additional paths or active
    // multi-path use, or preferred_address -- but unlike a single
    // deliberately-probed path, a *passively detected* candidate can't
    // be limited to one at a time: RFC 9000 section 9.3's own security
    // discussion anticipates multiple addresses producing valid-looking
    // traffic (e.g. an off-path attacker duplicating packets to several
    // addresses), so each candidate is validated independently, with
    // its own nonce and retry/abandon timer (RFC 9000 section 8.2.4;
    // deliberately not registered with lossDetector -- see
    // beginMigrationValidation's comment), bounded by
    // MAX_CONCURRENT_PATH_VALIDATIONS.
    //
    // recentlyMigratedFromAddresses guards against a related but
    // distinct problem: once this connection deliberately migrates
    // *away* from an address, that address doesn't stop being capable
    // of producing valid-looking traffic -- e.g. a straggling ACK, or
    // (in a real NAT-rebind, impossible, but in any scenario where the
    // "old" address is still independently live) ordinary continued
    // activity. Without this, such traffic looks exactly like a fresh
    // migration candidate, gets challenged, and -- since it's genuinely
    // the same peer holding the same keys -- gets a valid PATH_RESPONSE,
    // flip-flopping the connection straight back to the address it just
    // deliberately left. Recording a short cooldown per address we've
    // migrated away from (see MIGRATION_COOLDOWN_MILLIS) closes that
    // without permanently blacklisting an address that might
    // legitimately reappear later.
    //
    // currentDatagramSource/sawValidOneRttThisReceive are scratch state
    // valid only for the duration of one receive() call, letting the
    // frame callbacks below (which don't otherwise see the datagram's
    // source address) know where a PATH_CHALLENGE/PATH_RESPONSE
    // actually arrived from.
    private final Map<InetSocketAddress, PathValidationAttempt> pathValidationAttempts = new HashMap<>();
    private final Map<InetSocketAddress, Long> recentlyMigratedFromAddresses =
            new LinkedHashMap<InetSocketAddress, Long>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<InetSocketAddress, Long> eldest) {
                    return size() > MAX_RECENTLY_MIGRATED_FROM;
                }
            };
    private InetSocketAddress currentDatagramSource;
    private boolean sawValidOneRttThisReceive;
    private boolean decryptFailedOrUnparseableThisDatagram;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecurityInfo securityInfo;
    private TimerHandle timerHandle;
    private boolean established;
    private boolean handshakeConfirmed;
    private boolean handshakeDoneOwed;
    private boolean closed;
    private String deferredCloseReason;
    private long deferredCloseErrorCode;
    // RFC 9000 section 19.19: false for a transport-level (0x1c) close,
    // true for an application-level (0x1d) close.
    private boolean deferredCloseApplicationError;
    // Distinguishes a clean, app-initiated close() (e.g. QuicEngine.close())
    // from one triggered by a peer's CONNECTION_CLOSE or a local transport
    // error -- decides whether streams' ProtocolHandler.disconnected() or
    // .error(Exception) is called on teardown (peer FIN uses readFinished()
    // instead; see completeStreamFin).
    private boolean deferredCloseIsError;

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
        this.localMaxStreamsBidi = localTransportParameters.getInitialMaxStreamsBidi();
        this.localMaxStreamsUni = localTransportParameters.getInitialMaxStreamsUni();
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
            boolean earlyDataAccepted = isServer
                    ? ((org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine) tlsEngine).wasEarlyDataAccepted()
                    : zeroRttState == ZeroRttState.ACCEPTED;
            securityInfo = new QuicSecurityInfo(tlsEngine, isServer, handshakeStartTime, earlyDataAccepted);
        }
        return securityInfo;
    }

    public boolean isClosed() {
        return closed;
    }

    List<byte[]> getOurConnectionIds() {
        return connectionIdManager.collectOurConnectionIds();
    }

    /**
     * Returns whether this connection's TLS handshake has finished (RFC
     * 9001 section 4.1.2's "handshake complete", not necessarily yet
     * "handshake confirmed"). A stream opened before this point can
     * still send data -- e.g. as 0-RTT (RFC 9001 section 4.6.1), from
     * {@link QuicEngine.EarlyDataHandler#earlyDataReady} -- but only a
     * client presenting an accepted session ticket can actually get
     * that data out before this flips true.
     *
     * @return whether the handshake has finished
     */
    public boolean isEstablished() {
        return established;
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

    /**
     * Registers the handler that receives unreliable DATAGRAM payloads
     * (RFC 9221) on this connection. Only {@link ProtocolHandler#datagramReceived}
     * is invoked; the other callbacks are unused. Pass {@code null} to
     * drop received datagrams silently.
     *
     * @param handler the handler, or {@code null}
     */
    public void setDatagramHandler(ProtocolHandler handler) {
        this.datagramHandler = handler;
    }

    /**
     * Returns the peer's {@code max_datagram_frame_size} (RFC 9221
     * section 3), or 0 if the peer omitted the parameter / sent 0
     * (DATAGRAM frames must not be sent).
     *
     * @return the peer's receive ceiling in bytes, including type and
     *         Length fields
     */
    public long getPeerMaxDatagramFrameSize() {
        return peerMaxDatagramFrameSize;
    }

    /**
     * Queues an unreliable DATAGRAM frame (RFC 9221) for the next 1-RTT
     * packet. Not retransmitted if lost. Dropped (and returns
     * {@code false}) when the peer has not advertised a non-zero
     * {@code max_datagram_frame_size}, or when the encoded frame would
     * exceed that limit.
     *
     * @param data the payload; copied, the caller's buffer is not retained
     * @return true if queued, false if it cannot be sent
     */
    public boolean sendDatagram(ByteBuffer data) {
        if (data == null || closed) {
            return false;
        }
        byte[] copy = new byte[data.remaining()];
        data.get(copy);
        int encoded = QuicFrameWriter.datagramLength(copy.length);
        if (peerTransportParameters != null) {
            if (peerMaxDatagramFrameSize <= 0 || encoded > peerMaxDatagramFrameSize) {
                return false;
            }
        }
        pendingDatagrams.add(copy);
        requestFlush();
        return true;
    }

    void setClientConnectionAcceptedHandler(QuicEngine.ConnectionAcceptedHandler handler) {
        this.clientConnectionAcceptedHandler = handler;
    }

    void setClientHandler(ProtocolHandler handler) {
        this.clientHandler = handler;
    }

    /**
     * Client-only: registers a callback fired once, right after 0-RTT
     * send keys become available -- well before the handshake otherwise
     * completes -- so the caller can open a stream and queue eligible
     * data immediately. No-op if this connection never ends up
     * attempting 0-RTT (no ticket presented, or the presented ticket
     * doesn't support early data).
     *
     * @param handler the callback
     */
    void setEarlyDataHandler(QuicEngine.EarlyDataHandler handler) {
        this.earlyDataHandler = handler;
    }

    /**
     * Client-only: seeds this connection's peer-side send limits from a
     * previous connection's remembered transport parameters (RFC 9000
     * section 7.4.1), so 0-RTT stream sends aren't blocked outright by
     * the complete absence of any peer transport parameters before the
     * real ones arrive. Must be called before {@link #startHandshake} --
     * specifically before the client's ClientHello is built, so 0-RTT
     * data queued from {@link QuicEngine.EarlyDataHandler#earlyDataReady}
     * has a budget to send against immediately.
     *
     * <p>Overwritten once the real transport parameters arrive via
     * {@link #transportParametersReceived}, which also checks there that
     * the real values are not more restrictive than these remembered
     * ones for the RFC 9000 section 7.4.1 fields that could invalidate
     * already-sent 0-RTT data, closing the connection if so.
     *
     * @param remembered the peer's transport parameters from the
     *                   connection the presented session ticket came from
     */
    void seedRememberedTransportParameters(TransportParameters remembered) {
        this.peerTransportParameters = remembered;
        this.peerMaxData = remembered.getInitialMaxData();
        this.peerMaxStreamsBidi = remembered.getInitialMaxStreamsBidi();
        this.peerMaxStreamsUni = remembered.getInitialMaxStreamsUni();
        this.peerMaxDatagramFrameSize = remembered.getMaxDatagramFrameSize();
    }

    /**
     * Starts the client-side TLS handshake, producing an Initial packet
     * on the next {@link #flush}.
     *
     * @param serverName the SNI server name
     * @throws IOException if the handshake cannot be started
     */
    void startHandshake(String serverName) throws IOException {
        this.serverName = serverName;
        // If a session ticket was presented, earlySecretsKnown() fires
        // synchronously from inside this call, before the ClientHello
        // itself has even been sent (see QuicTlsClientEngine) -- which
        // in turn synchronously invokes earlyDataHandler.earlyDataReady,
        // whose queued stream data would otherwise trigger its own
        // premature, Initial-less flush() via requestFlush() (see
        // suppressFlush's own documentation at receive() for the same
        // class of problem on the receive side). Suppressed here so the
        // caller's own conn.flush() (QuicEngine.connectTo, right after
        // this returns) is the one that actually coalesces Initial and
        // 0-RTT together.
        suppressFlush = true;
        try {
            ((org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine) tlsEngine).startHandshake(serverName);
        } finally {
            suppressFlush = false;
        }
    }

    // ── Stream lifecycle ──

    /**
     * Opens a new locally-initiated bidirectional stream.
     *
     * <p>If the peer has not yet granted enough stream credit (RFC 9000
     * section 4.6), the open is queued and {@code handler.connected} fires
     * once a later {@code MAX_STREAMS} (or the handshake transport
     * parameters) lifts the limit. Callers that send immediately after
     * this returns must tolerate a {@code null} result and send from
     * {@link ProtocolHandler#connected} instead.
     *
     * @param handler the handler for the new stream
     * @return the new stream's endpoint, or {@code null} if the open was
     *         queued until the peer grants credit
     */
    public Endpoint openStream(ProtocolHandler handler) {
        return openLocalStream(handler, true);
    }

    /**
     * Opens a new locally-initiated unidirectional stream.
     *
     * <p>See {@link #openStream} for the credit-queuing contract.
     *
     * @param handler the handler for the new stream
     * @return the new stream's endpoint, or {@code null} if the open was
     *         queued until the peer grants credit
     */
    public Endpoint openUnidirectionalStream(ProtocolHandler handler) {
        return openLocalStream(handler, false);
    }

    private Endpoint openLocalStream(ProtocolHandler handler, boolean bidirectional) {
        if (closed) {
            handler.error(new IOException("Connection is closed"));
            return null;
        }
        if (canOpenLocalStream(bidirectional)) {
            return createLocalStream(handler, bidirectional);
        }
        if (bidirectional) {
            pendingOpenBidi.add(handler);
        } else {
            pendingOpenUni.add(handler);
        }
        signalStreamsBlocked(bidirectional);
        return null;
    }

    private boolean canOpenLocalStream(boolean bidirectional) {
        if (bidirectional) {
            return openedLocalBidi() < peerMaxStreamsBidi;
        }
        return openedLocalUni() < peerMaxStreamsUni;
    }

    // RFC 9000 section 2.1: stream IDs of one type are 4 apart, so the
    // next-to-assign ID divided by 4 is the count already opened.
    private long openedLocalBidi() {
        return nextLocalBidiStreamId / 4;
    }

    private long openedLocalUni() {
        return nextLocalUniStreamId / 4;
    }

    private Endpoint createLocalStream(ProtocolHandler handler, boolean bidirectional) {
        long streamId;
        if (bidirectional) {
            streamId = nextLocalBidiStreamId;
            nextLocalBidiStreamId += 4;
        } else {
            streamId = nextLocalUniStreamId;
            nextLocalUniStreamId += 4;
        }
        return createStream(streamId, handler);
    }

    private void drainPendingOpens() {
        while (!pendingOpenBidi.isEmpty() && canOpenLocalStream(true)) {
            ProtocolHandler handler = pendingOpenBidi.remove(0);
            createLocalStream(handler, true);
        }
        while (!pendingOpenUni.isEmpty() && canOpenLocalStream(false)) {
            ProtocolHandler handler = pendingOpenUni.remove(0);
            createLocalStream(handler, false);
        }
        if (!pendingOpenBidi.isEmpty()) {
            signalStreamsBlocked(true);
        }
        if (!pendingOpenUni.isEmpty()) {
            signalStreamsBlocked(false);
        }
    }

    private void signalStreamsBlocked(boolean bidirectional) {
        // STREAMS_BLOCKED carries the limit that blocked us (RFC 9000
        // section 19.14). Until transport parameters arrive that limit
        // is unknown, so there is nothing useful to signal yet.
        if (peerTransportParameters == null) {
            return;
        }
        if (bidirectional) {
            if (!streamsBlockedBidiSignalled) {
                streamsBlockedBidiSignalled = true;
                streamsBlockedBidiOwed = true;
                requestFlush();
            }
        } else {
            if (!streamsBlockedUniSignalled) {
                streamsBlockedUniSignalled = true;
                streamsBlockedUniOwed = true;
                requestFlush();
            }
        }
    }

    /**
     * Raises this endpoint's advertised stream-concurrency limit and
     * queues a MAX_STREAMS frame (RFC 9000 section 19.11). Values that
     * do not increase the current limit are ignored, matching the
     * receive-side rule for the same frame.
     *
     * @param bidirectional true to raise the bidirectional limit
     * @param maximumStreams the new limit; must not exceed 2^60
     */
    /**
     * Releases one stream's worth of concurrency credit back to the peer
     * (RFC 9000 section 19.11), raising the advertised MAX_STREAMS limit
     * by one. The HTTP mapping running over this connection calls this
     * once a stream it accepted has fully finished, so the slot it held
     * in the peer's stream budget becomes available for a new stream -
     * without this, {@link #localMaxStreamsBidi}/{@link
     * #localMaxStreamsUni} would stay fixed at their initial transport-
     * parameter value for the connection's entire lifetime, and the peer
     * would be unable to open more than that many streams in total, ever,
     * regardless of how many earlier ones have long since closed.
     *
     * @param bidirectional true if the finished stream was bidirectional
     */
    public void releaseStreamCredit(boolean bidirectional) {
        if (bidirectional) {
            grantMaxStreams(true, localMaxStreamsBidi + 1);
        } else {
            grantMaxStreams(false, localMaxStreamsUni + 1);
        }
    }

    void grantMaxStreams(boolean bidirectional, long maximumStreams) {
        if (maximumStreams > MAX_STREAMS_COUNT) {
            throw new IllegalArgumentException("MAX_STREAMS exceeds 2^60");
        }
        if (bidirectional) {
            if (maximumStreams > localMaxStreamsBidi) {
                localMaxStreamsBidi = maximumStreams;
                maxStreamsBidiOwed = true;
                requestFlush();
            }
        } else {
            if (maximumStreams > localMaxStreamsUni) {
                localMaxStreamsUni = maximumStreams;
                maxStreamsUniOwed = true;
                requestFlush();
            }
        }
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

    // RFC 9000 section 4.6: the stream count implied by streamId is
    // (streamId / 4) + 1. A peer-initiated stream whose count exceeds
    // the limit this endpoint advertised is a STREAM_LIMIT_ERROR.
    private boolean peerInitiatedStreamExceedsLimit(long streamId) {
        if (!isPeerInitiated(streamId)) {
            return false;
        }
        long count = (streamId / 4) + 1;
        long limit = isUnidirectional(streamId) ? localMaxStreamsUni : localMaxStreamsBidi;
        return count > limit;
    }

    private QuicStreamEndpoint acceptStream(long streamId) {
        if (peerInitiatedStreamExceedsLimit(streamId)) {
            closeWithError(TRANSPORT_ERROR_STREAM_LIMIT_ERROR,
                    "peer opened a stream beyond the advertised MAX_STREAMS limit");
            return null;
        }
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

    /**
     * Sets the send priority for a stream when multiplexing STREAM frames
     * (RFC 9218: higher values are sent sooner). Default is 0.
     *
     * @param streamId the stream
     * @param priority higher means sooner
     */
    public void setStreamSendPriority(long streamId, int priority) {
        streamSendPriority.put(Long.valueOf(streamId), Integer.valueOf(priority));
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
            Long key = Long.valueOf(streamId);
            streams.remove(key);
            streamReassemblers.remove(key);
            pendingFinOffset.remove(key);
            streamSendPriority.remove(key);
        }
    }

    // The peer finishing their send direction must not stop this side
    // from still sending its own response on the same (bidirectional)
    // stream -- see QuicStreamEndpoint's markPeerFinished javadoc.
    private void completeStreamFin(long streamId, QuicStreamEndpoint stream) {
        stream.markPeerFinished();
        stream.getHandler().readFinished();
        retireStreamIfFullyClosed(streamId, stream);
    }

    // ── Receive path ──

    /**
     * Processes a received datagram: one or more coalesced QUIC packets
     * (RFC 9000 section 12.2), each unprotected, decrypted, and its
     * frames dispatched.
     *
     * @param datagram the received datagram
     * @param source the address the datagram actually arrived from --
     *        used only for connection migration detection (RFC 9000
     *        section 9); everything else keeps addressing the peer via
     *        {@link #remoteAddress} until a candidate path validates
     */
    void receive(ByteBuffer datagram, InetSocketAddress source) {
        byte[] bytes = new byte[datagram.remaining()];
        datagram.get(bytes);
        if (isServer && !addressValidated) {
            amplificationBytesReceived += bytes.length;
        }
        int offset = 0;
        suppressFlush = true;
        currentDatagramSource = source;
        sawValidOneRttThisReceive = false;
        decryptFailedOrUnparseableThisDatagram = false;
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
        maybeCloseOnStatelessReset(bytes, 0, bytes.length);
        if (closed) {
            return;
        }
        // A successfully decrypted 1-RTT packet proves the peer holds the
        // 1-RTT keys -- an off-path attacker can't forge that, so a
        // source address mismatch at this point is a genuine candidate
        // migration (RFC 9000 section 9.3), not spoofing. Ignored while
        // this same candidate is already being validated
        // (beginMigrationValidation is a no-op for a repeat), ignored
        // while it's still cooling down from a deliberate migration away
        // from it (see recentlyMigratedFromAddresses), and ignored
        // before the handshake completes (no 1-RTT keys yet to prove
        // anything).
        if (sawValidOneRttThisReceive && established && !source.equals(remoteAddress)
                && !pathValidationAttempts.containsKey(source)
                && !isRecentlyMigratedFrom(source)) {
            beginMigrationValidation(source);
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
        // RFC 9000 section 12.3: 0-RTT shares the ONE_RTT packet number/
        // loss-detection space despite using different keys -- routed to
        // that level below, same as the short-header (1-RTT) branch;
        // processPacket tells the two apart via isZeroRtt to pick the
        // right key material and to keep a 0-RTT packet from counting as
        // address validation (see there).
        boolean isZeroRtt = false;
        if (longHeader) {
            byte[] fromOffset = offset == 0 ? bytes : Arrays.copyOfRange(bytes, offset, bytes.length);
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
            } catch (IllegalArgumentException e) {
                decryptFailedOrUnparseableThisDatagram = true;
                return -1;
            }
            isZeroRtt = prefix.getPacketType() == LongHeaderCodec.TYPE_0RTT;
            level = isZeroRtt ? EncryptionLevel.ONE_RTT
                    : prefix.getPacketType() == LongHeaderCodec.TYPE_INITIAL ? EncryptionLevel.INITIAL
                    : EncryptionLevel.HANDSHAKE;
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
            decryptFailedOrUnparseableThisDatagram = true;
            return -1;
        }
        byte[] packet = new byte[packetLength];
        System.arraycopy(bytes, offset, packet, 0, packetLength);
        processPacket(level, packet, pnOffset, isZeroRtt);
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
        if (!Arrays.equals(retry.getDestinationConnectionId(), ourConnectionId)) {
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

    // RFC 9001 section 4.9: once Initial or Handshake keys are no longer
    // needed (see the two call sites in flush(), plus the Initial-only
    // one in processPacket), discard all state for that packet number
    // space -- no packet is ever built, sent, or accepted at this level
    // again. Guarded on pendingCrypto being empty so a chunk that was
    // queued but never actually sent even once is never silently thrown
    // away; per RFC 9001 section 4.9.1's own "ignoring any outstanding
    // Initial packets" language, anything already sent-but-unacknowledged
    // (sentCrypto) is fine to abandon here, along with the space's
    // loss-recovery bookkeeping (RFC 9002 Appendix A.11) -- closing a gap
    // where LossDetector.discardPacketNumberSpace was never called at
    // all, leaving bytes-in-flight for long-abandoned Initial/Handshake
    // packets permanently charged against the congestion window and
    // sentPackets for those levels growing without bound for the life of
    // the connection. Never called for EncryptionLevel.ONE_RTT.
    private void discardEncryptionLevel(EncryptionLevel level) {
        if (discarded[level.ordinal()] || !pendingCrypto.get(level).isEmpty()) {
            return;
        }
        discarded[level.ordinal()] = true;
        sendKeys.remove(level);
        recvKeys.remove(level);
        sentCrypto.get(level).clear();
        ackOwed[level.ordinal()] = false;
        receivedUnacked.remove(level);
        sentAckCoverage.remove(level);
        pendingPing[level.ordinal()] = false;
        lossDetector.discardPacketNumberSpace(level);
    }

    private void processPacket(EncryptionLevel level, byte[] packet, int pnOffset, boolean isZeroRtt) {
        PacketProtectionKeys keys = isZeroRtt ? zeroRttRecvKeys : recvKeys.get(level);
        if (keys == null) {
            return; // keys not derived yet (or not accepted) at this level; drop
        }
        // 0-RTT is wire-long-header despite sharing ONE_RTT's packet
        // number/loss-detection space (see receiveOnePacket).
        boolean longHeader = level != EncryptionLevel.ONE_RTT || isZeroRtt;
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
            byte[] aad = Arrays.copyOfRange(packet, 0, headerLength);
            byte[] ciphertext = Arrays.copyOfRange(packet, headerLength, packet.length);
            byte[] plaintext = PacketProtection.open(keys, fullPacketNumber, aad, ciphertext);

            if (fullPacketNumber > largestReceived[level.ordinal()]) {
                largestReceived[level.ordinal()] = fullPacketNumber;
                largestReceivedTime[level.ordinal()] = System.currentTimeMillis();
            }
            if (level == EncryptionLevel.HANDSHAKE) {
                // RFC 9000 section 8.1: a successfully decrypted Handshake
                // packet proves the peer holds the Handshake keys, which
                // requires it to have actually received and processed our
                // Initial response -- an off-path attacker spoofing the
                // client's address could not have produced this.
                addressValidated = true;
                receivedHandshakePacket = true;
            } else if (level == EncryptionLevel.ONE_RTT && !isZeroRtt) {
                // Likewise proves the peer holds the 1-RTT keys -- the
                // signal receive() uses to tell a genuine candidate
                // migration (RFC 9000 section 9.3) apart from spoofed
                // garbage arriving from a random new address. A 0-RTT
                // packet must NOT count here: unlike a Handshake-level
                // decryption success, 0-RTT keys are derivable from an
                // observed session ticket in some replay scenarios, so
                // successfully decrypting one doesn't by itself prove
                // this is a live round trip with the real peer.
                sawValidOneRttThisReceive = true;
            }

            FrameDispatcher dispatcher = new FrameDispatcher(level, isZeroRtt);
            new QuicFrameParser(dispatcher).receive(ByteBuffer.wrap(plaintext));
            // RFC 9000 section 13.2: only owe an ACK if this packet
            // carried at least one ack-eliciting frame -- acknowledging a
            // packet that itself contained nothing but ACK/PADDING/
            // CONNECTION_CLOSE would let two endpoints that just did that
            // to each other keep acking one another's ACKs forever.
            if (dispatcher.ackEliciting) {
                ackOwed[level.ordinal()] = true;
                TreeSet<Long> unacked = receivedUnacked.get(level);
                if (unacked == null) {
                    unacked = new TreeSet<Long>();
                    receivedUnacked.put(level, unacked);
                }
                unacked.add(Long.valueOf(fullPacketNumber));
            }
        } catch (PacketProtectionException e) {
            LOGGER.log(Level.FINE, "Packet protection failure at " + level + "; dropping", e);
            if (level == EncryptionLevel.ONE_RTT && !isZeroRtt) {
                decryptFailedOrUnparseableThisDatagram = true;
            }
        }
    }

    private void maybeCloseOnStatelessReset(byte[] datagram, int offset, int length) {
        if (closed || !decryptFailedOrUnparseableThisDatagram || sawValidOneRttThisReceive) {
            return;
        }
        if (!StatelessResetPacket.matchesAnyKnownToken(datagram, offset, length,
                connectionIdManager.collectPeerResetTokens())) {
            return;
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("fine.stateless_reset_detected"));
        }
        closeFromStatelessReset();
    }

    /**
     * Handles a datagram that could not be demultiplexed by connection ID
     * but whose tail matches a known peer reset token (RFC 9000 section
     * 10.3 -- stateless reset packets do not carry a valid DCID).
     *
     * @param datagram the received datagram
     * @return {@code true} if this connection accepted the reset
     */
    boolean handleIncomingStatelessResetDatagram(byte[] datagram) {
        if (closed || datagram.length < StatelessResetPacket.MIN_DATAGRAM_LENGTH) {
            return false;
        }
        if (!StatelessResetPacket.matchesAnyKnownToken(datagram, 0, datagram.length,
                connectionIdManager.collectPeerResetTokens())) {
            return false;
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("fine.stateless_reset_detected"));
        }
        closeFromStatelessReset();
        return true;
    }


    /** Per-packet frame dispatcher, one instance per {@link #processPacket} call. */
    private final class FrameDispatcher implements QuicFrameHandler {

        private final EncryptionLevel level;
        private final boolean zeroRtt;

        // RFC 9000 section 13.2: every frame type is ack-eliciting except
        // ACK, PADDING, and CONNECTION_CLOSE -- an incoming packet whose
        // only frames are among those three must not itself be
        // acknowledged, or two endpoints that both received nothing but
        // an ACK from each other would keep acking one another's ACKs
        // forever. Starts false per packet (one FrameDispatcher per
        // processPacket call) and is set true by every other frame type
        // received; processPacket only sets ackOwed when this ends up true.
        boolean ackEliciting;

        FrameDispatcher(EncryptionLevel level, boolean zeroRtt) {
            this.level = level;
            this.zeroRtt = zeroRtt;
        }

        @Override
        public void paddingFrameReceived(int length) {
        }

        @Override
        public void pingFrameReceived() {
            ackEliciting = true;
        }

        @Override
        public void ackFrameReceived(long largestAcknowledged, long ackDelay, long[][] ranges) {
            if (level == EncryptionLevel.HANDSHAKE) {
                receivedHandshakeAck = true;
            }
            LossDetector.AckResult result = lossDetector.onAckReceived(level, largestAcknowledged, ackDelay,
                    ranges, peerMaxAckDelay(), System.currentTimeMillis(), peerAddressValidated());
            retireAcknowledgedRanges(level, result.getNewlyAcked());
            Map<Long, long[]> coverage = sentAckCoverage.get(level);
            for (SentPacket lost : result.getNewlyLost()) {
                requeueLostPacket(level, lost.getPacketNumber());
                // The ACK this packet would have carried (if any) never
                // reached the peer -- nothing to retire, and no further
                // reason to keep tracking it (receivedUnacked already
                // still holds whatever it covered, untouched, so those
                // packet numbers are naturally included in the next ACK
                // this endpoint sends).
                if (coverage != null) {
                    coverage.remove(Long.valueOf(lost.getPacketNumber()));
                }
            }
        }

        @Override
        public void resetStreamFrameReceived(long streamId, long applicationErrorCode, long finalSize) {
            ackEliciting = true;
            Long key = Long.valueOf(streamId);
            if (streams.get(key) == null && peerInitiatedStreamExceedsLimit(streamId)) {
                closeWithError(TRANSPORT_ERROR_STREAM_LIMIT_ERROR,
                        "RESET_STREAM would open a stream beyond the advertised MAX_STREAMS limit");
                return;
            }
            streamReassemblers.remove(key);
            pendingFinOffset.remove(key);
            QuicStreamEndpoint stream = streams.remove(key);
            if (stream != null) {
                stream.markClosed();
                stream.getHandler().disconnected();
            }
        }

        @Override
        public void stopSendingFrameReceived(long streamId, long applicationErrorCode) {
            ackEliciting = true;
            resetStream(streamId, applicationErrorCode);
        }

        @Override
        public void cryptoFrameReceived(long offset, ByteBuffer data) {
            ackEliciting = true;
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            try {
                tlsEngine.receiveCryptoData(level, offset, ByteBuffer.wrap(copy));
            } catch (StreamReassembler.BufferLimitExceededException e) {
                closeWithError(TRANSPORT_ERROR_CRYPTO_BUFFER_EXCEEDED, e.getMessage());
            } catch (TlsProtocolException | IOException e) {
                LOGGER.log(Level.WARNING, "TLS error processing CRYPTO data at " + level, e);
            }
        }

        @Override
        public void newTokenFrameReceived(ByteBuffer token) {
            ackEliciting = true;
        }

        @Override
        public void streamFrameReceived(long streamId, long offset, boolean fin, ByteBuffer data) {
            ackEliciting = true;
            QuicStreamEndpoint stream = streams.get(Long.valueOf(streamId));
            if (stream == null) {
                stream = acceptStream(streamId);
                if (stream == null) {
                    return;
                }
            }
            int length = data.remaining();
            if (!checkAndRecordFlowControl(streamId, offset, length)) {
                return;
            }
            Long key = Long.valueOf(streamId);
            byte[] chunk = new byte[length];
            data.get(chunk);
            StreamReassembler reassembler = streamReassemblers.get(key);
            if (reassembler == null) {
                // No independent cap needed -- checkAndRecordFlowControl
                // above already bounds how far ahead of the delivered
                // cursor a peer can push data at all (RFC 9000 section
                // 4.1), unlike CRYPTO data (see CryptoStreamBuffer).
                reassembler = new StreamReassembler(Long.MAX_VALUE);
                streamReassemblers.put(key, reassembler);
            }
            byte[] contiguous;
            try {
                contiguous = reassembler.receive(offset, chunk);
            } catch (StreamReassembler.BufferLimitExceededException e) {
                // Unreachable in practice, see the field comment above --
                // closed defensively rather than left to hang if it ever
                // somehow were.
                closeWithError(TRANSPORT_ERROR_INTERNAL_ERROR, e.getMessage());
                return;
            }
            if (contiguous.length > 0) {
                stream.deliverData(ByteBuffer.wrap(contiguous));
            }
            if (fin) {
                long finOffset = offset + length;
                if (finOffset <= reassembler.getNextOffset()) {
                    completeStreamFin(streamId, stream);
                } else {
                    // See the pendingFinOffset field comment: this FIN's
                    // own frame is itself out of order, remembered here
                    // until reassembly's cascade catches up to it.
                    pendingFinOffset.put(key, Long.valueOf(finOffset));
                }
            } else if (contiguous.length > 0) {
                Long pending = pendingFinOffset.get(key);
                if (pending != null && pending.longValue() <= reassembler.getNextOffset()) {
                    pendingFinOffset.remove(key);
                    completeStreamFin(streamId, stream);
                }
            }
        }

        @Override
        public void maxDataFrameReceived(long maximumData) {
            ackEliciting = true;
            if (maximumData > peerMaxData) {
                peerMaxData = maximumData;
                dataBlockedSignalled = false;
            }
        }

        @Override
        public void maxStreamDataFrameReceived(long streamId, long maximumStreamData) {
            ackEliciting = true;
            Long key = Long.valueOf(streamId);
            Long current = peerMaxStreamData.get(key);
            if (current == null || maximumStreamData > current.longValue()) {
                peerMaxStreamData.put(key, Long.valueOf(maximumStreamData));
                streamDataBlockedSignalled.remove(key);
            }
        }

        @Override
        public void maxStreamsFrameReceived(boolean bidirectional, long maximumStreams) {
            ackEliciting = true;
            if (maximumStreams > MAX_STREAMS_COUNT) {
                closeWithError(TRANSPORT_ERROR_FRAME_ENCODING_ERROR,
                        "MAX_STREAMS exceeds 2^60");
                return;
            }
            if (bidirectional) {
                if (maximumStreams > peerMaxStreamsBidi) {
                    peerMaxStreamsBidi = maximumStreams;
                    streamsBlockedBidiSignalled = false;
                    drainPendingOpens();
                }
            } else {
                if (maximumStreams > peerMaxStreamsUni) {
                    peerMaxStreamsUni = maximumStreams;
                    streamsBlockedUniSignalled = false;
                    drainPendingOpens();
                }
            }
        }

        // RFC 9000 section 4.1: the peer is blocked sending -- grow our
        // advertised limit right away rather than waiting for more data
        // to arrive and cross the usual half-window threshold. The peer
        // being fully blocked already means it cannot send anything more
        // to advance that passive check, so the unconditional
        // xxxOnBlocked growth is used instead -- see its javadoc.
        @Override
        public void dataBlockedFrameReceived(long maximumData) {
            ackEliciting = true;
            growConnectionLimitOnBlocked();
        }

        @Override
        public void streamDataBlockedFrameReceived(long streamId, long maximumStreamData) {
            ackEliciting = true;
            if (streams.get(Long.valueOf(streamId)) == null && peerInitiatedStreamExceedsLimit(streamId)) {
                closeWithError(TRANSPORT_ERROR_STREAM_LIMIT_ERROR,
                        "STREAM_DATA_BLOCKED would open a stream beyond the advertised MAX_STREAMS limit");
                return;
            }
            growStreamLimitOnBlocked(streamId);
        }

        @Override
        public void streamsBlockedFrameReceived(boolean bidirectional, long maximumStreams) {
            ackEliciting = true;
        }

        @Override
        public void newConnectionIdFrameReceived(long sequenceNumber, long retirePriorTo,
                ByteBuffer connectionId, ByteBuffer statelessResetToken) {
            ackEliciting = true;
            byte[] cid = new byte[connectionId.remaining()];
            connectionId.get(cid);
            byte[] token = new byte[statelessResetToken.remaining()];
            statelessResetToken.get(token);
            connectionIdManager.addPeerConnectionId(sequenceNumber, retirePriorTo, cid, token);
        }

        @Override
        public void retireConnectionIdFrameReceived(long sequenceNumber) {
            ackEliciting = true;
            byte[] retired = connectionIdManager.getOurConnectionId(sequenceNumber);
            connectionIdManager.retireOurs(sequenceNumber);
            if (retired != null) {
                engine.unregisterConnectionId(retired);
                engine.markResetEligible(retired);
            }
        }

        @Override
        public void pathChallengeFrameReceived(ByteBuffer data) {
            ackEliciting = true;
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            // RFC 9000 section 8.2.2: answered on the path the challenge
            // itself arrived on, which may not be remoteAddress yet (e.g.
            // the peer probing a path this endpoint hasn't switched to).
            sendPathResponse(bytes, currentDatagramSource);
        }

        @Override
        public void pathResponseFrameReceived(ByteBuffer data) {
            ackEliciting = true;
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            PathValidationAttempt attempt = pathValidationAttempts.get(currentDatagramSource);
            if (attempt != null && Arrays.equals(bytes, attempt.challengeData)) {
                completeMigration(currentDatagramSource);
            }
        }

        @Override
        public void connectionCloseFrameReceived(boolean applicationError, long errorCode,
                long frameType, String reason) {
            deferredCloseApplicationError = applicationError;
            deferredCloseErrorCode = errorCode;
            deferredCloseReason = reason;
            deferredCloseIsError = true;
            close();
        }

        @Override
        public void handshakeDoneFrameReceived() {
            ackEliciting = true;
            handshakeConfirmed = true;
            notifyClientHandshakeComplete();
        }

        @Override
        public void datagramFrameReceived(ByteBuffer data, int encodedLength) {
            ackEliciting = true;
            // RFC 9221 section 5: DATAGRAM frames MUST only appear in
            // 1-RTT packets (not Initial, Handshake, or 0-RTT).
            if (level != EncryptionLevel.ONE_RTT || zeroRtt) {
                closeWithError(TRANSPORT_ERROR_PROTOCOL_VIOLATION,
                        "DATAGRAM frames are only permitted in 1-RTT packets");
                return;
            }
            long localMax = localTransportParameters.getMaxDatagramFrameSize();
            if (localMax <= 0) {
                closeWithError(TRANSPORT_ERROR_PROTOCOL_VIOLATION,
                        "DATAGRAM received but max_datagram_frame_size was not advertised");
                return;
            }
            if (encodedLength > localMax) {
                closeWithError(TRANSPORT_ERROR_PROTOCOL_VIOLATION,
                        "DATAGRAM frame exceeds advertised max_datagram_frame_size");
                return;
            }
            if (datagramHandler != null) {
                byte[] copy = new byte[data.remaining()];
                data.get(copy);
                datagramHandler.datagramReceived(ByteBuffer.wrap(copy));
            }
        }

        @Override
        public void frameError(String message) {
            String formatted = MessageFormat.format(
                    L10N.getString("warn.frame_error"), remoteAddress, message);
            LOGGER.warning(formatted);
        }
    }

    // The peer's declared max_ack_delay (RFC 9000 section 18.2), or the
    // RFC's own default if the peer's transport parameters haven't
    // arrived yet (e.g. while still building Initial-level packets).
    private long peerMaxAckDelay() {
        return peerTransportParameters == null
                ? TransportParameters.DEFAULT_MAX_ACK_DELAY : peerTransportParameters.getMaxAckDelay();
    }

    // RFC 9000 section 13.2.4: once one of this endpoint's own sent
    // packets is newly acked, the peer has just proven it received the
    // ACK frame that packet carried (if any) -- so whatever peer packet
    // numbers that ACK covered can finally be retired from
    // receivedUnacked. Called from ackFrameReceived for every level on
    // every incoming ACK; a level with no ACK-carrying packets among
    // newlyAcked, or none acked at all, is a cheap no-op.
    private void retireAcknowledgedRanges(EncryptionLevel level, List<SentPacket> newlyAcked) {
        if (newlyAcked.isEmpty()) {
            return;
        }
        Map<Long, long[]> coverage = sentAckCoverage.get(level);
        if (coverage == null || coverage.isEmpty()) {
            return;
        }
        TreeSet<Long> unacked = receivedUnacked.get(level);
        for (SentPacket acked : newlyAcked) {
            long[] covered = coverage.remove(Long.valueOf(acked.getPacketNumber()));
            if (covered != null && unacked != null) {
                for (long pn : covered) {
                    unacked.remove(Long.valueOf(pn));
                }
            }
        }
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
            // 0-RTT shares this packet-number space (RFC 9000 section
            // 12.3) -- a 0-RTT packet can be detected lost the same way
            // as any other, independent of whether the server eventually
            // accepts or rejects 0-RTT at all (that's a separate signal,
            // see earlyDataOutcomeKnown/discardZeroRttDataAndKeys). Only
            // requeue if the keys are still live -- if 0-RTT was already
            // rejected, discardZeroRttDataAndKeys() has already moved
            // every one of these chunks back to pendingStream itself, so
            // sentZeroRttStream is empty and this is a no-op either way.
            Map<Long, List<PendingChunk>> lostZeroRttStreams = sentZeroRttStream.remove(Long.valueOf(packetNumber));
            if (lostZeroRttStreams != null && zeroRttSendKeys != null) {
                for (Map.Entry<Long, List<PendingChunk>> entry : lostZeroRttStreams.entrySet()) {
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
     * Initial, then 0-RTT, then Handshake, then 1-RTT.
     *
     * <p>0-RTT, Handshake, and 1-RTT are built first even though Initial
     * is placed first in the datagram -- their sizes don't depend on
     * Initial's padding, but a client Initial's padding target (RFC 9000
     * section 14.1's 1200-byte minimum) does depend on theirs, once they
     * share a datagram: padding only needs to make up whatever the other
     * levels aren't already contributing.
     */
    void flush() {
        byte[] zeroRttBytes = buildZeroRttPacketOrNull();
        byte[] handshakeBytes = buildLevelPacketOrNull(EncryptionLevel.HANDSHAKE, 0);
        if (handshakeBytes != null) {
            sentHandshakePacket = true;
        }
        byte[] oneRttBytes = buildLevelPacketOrNull(EncryptionLevel.ONE_RTT, 0);

        int zeroRttHandshakeAndOneRttBytes = (zeroRttBytes != null ? zeroRttBytes.length : 0)
                + (handshakeBytes != null ? handshakeBytes.length : 0)
                + (oneRttBytes != null ? oneRttBytes.length : 0);
        int initialMinDatagramSize = !isServer ? Math.max(0, MIN_DATAGRAM_SIZE - zeroRttHandshakeAndOneRttBytes) : 0;
        byte[] initialBytes = buildLevelPacketOrNull(EncryptionLevel.INITIAL, initialMinDatagramSize);

        // RFC 9001 section 4.9: attempted on every flush (not just one
        // that happens to build something new at these levels), since
        // discardEncryptionLevel can itself defer past its first
        // attempt if a chunk was still queued but never yet sent -- see
        // its own javadoc. Placed after every buildLevelPacketOrNull
        // call above so this flush's own Initial/Handshake-level packet
        // (if any) is always built with the still-current keys first;
        // discarding only ever affects later flushes.
        if (isServer ? receivedHandshakePacket : sentHandshakePacket) {
            // RFC 9001 section 4.9.1: a client discards Initial keys
            // once it has sent a Handshake packet; a server discards
            // them once it has successfully processed one.
            discardEncryptionLevel(EncryptionLevel.INITIAL);
        }
        if (handshakeConfirmed) {
            // RFC 9001 section 4.9.2: both sides discard Handshake
            // keys once the handshake is confirmed (see
            // handshakeConfirmed's own two set sites).
            discardEncryptionLevel(EncryptionLevel.HANDSHAKE);
        }

        if (initialBytes == null && zeroRttBytes == null && handshakeBytes == null && oneRttBytes == null) {
            // Nothing to send, but the set of in-flight/lost packets may
            // still have changed (e.g. an ACK just cleared everything
            // this connection had outstanding) -- the loss detection
            // timer must be re-armed (or, per RFC 9002 Appendix A.8,
            // cancelled outright) to reflect that now, not left running
            // on stale state from before this flush. Skipping this call
            // here left a still-armed timer from an earlier, now-obsolete
            // deadline free to fire later and misread "nothing in
            // flight" as a Probe Timeout, sending a spurious anti-
            // deadlock PING that nothing actually required.
            scheduleLossDetectionTimer();
            return;
        }

        int totalLength = (initialBytes != null ? initialBytes.length : 0) + zeroRttHandshakeAndOneRttBytes;
        byte[] datagram = new byte[totalLength];
        int pos = 0;
        if (initialBytes != null) {
            System.arraycopy(initialBytes, 0, datagram, pos, initialBytes.length);
            pos += initialBytes.length;
        }
        if (zeroRttBytes != null) {
            System.arraycopy(zeroRttBytes, 0, datagram, pos, zeroRttBytes.length);
            pos += zeroRttBytes.length;
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
            String formatted = MessageFormat.format(
                    L10N.getString("fine.anti_amplification_limit"),
                    Long.valueOf(amplificationBytesSent),
                    Long.valueOf(amplificationBytesReceived));
            LOGGER.fine(formatted);
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

    // ── Connection migration (RFC 9000 section 9) ──

    // Bundles what used to be four connection-level fields (one
    // outstanding challenge's nonce, deadline, and retry timer) into a
    // single per-candidate record, so multiple candidates can be
    // validated concurrently without one evicting another's state.
    // deadlineMillis is fixed for the attempt's lifetime; challengeData
    // and timerHandle are updated in place on every retry.
    private static final class PathValidationAttempt {
        final long deadlineMillis;
        byte[] challengeData;
        TimerHandle timerHandle;

        PathValidationAttempt(long deadlineMillis) {
            this.deadlineMillis = deadlineMillis;
        }
    }

    // Starts validating a candidate new path: computes the RFC 9000
    // section 8.2.4 abandon deadline (max(3*PTO, 6*kInitialRtt)) for
    // this attempt, then sends the first PATH_CHALLENGE and arms its
    // retry timer. Ordinary traffic keeps going to the old,
    // still-current remoteAddress until this validates.
    //
    // A second, different candidate address arriving while one
    // validation is already in flight no longer evicts it -- each
    // candidate gets its own entry in pathValidationAttempts, up to
    // MAX_CONCURRENT_PATH_VALIDATIONS (RFC 9000 section 9.3's
    // amplification concern: beyond that, further candidates are
    // simply not validated until an existing one completes or is
    // abandoned).
    private void beginMigrationValidation(InetSocketAddress candidate) {
        if (pathValidationAttempts.size() >= MAX_CONCURRENT_PATH_VALIDATIONS) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("Ignoring path validation candidate " + candidate + ": already validating "
                        + pathValidationAttempts.size() + " concurrently");
            }
            return;
        }
        long pto = currentPathValidationPto();
        long deadline = System.currentTimeMillis() + Math.max(3 * pto, 6 * RttEstimator.K_INITIAL_RTT);
        PathValidationAttempt attempt = new PathValidationAttempt(deadline);
        pathValidationAttempts.put(candidate, attempt);
        sendPathChallengeAndScheduleRetry(candidate, attempt, pto);
    }

    // RFC 9000 section 8.2.4: "An endpoint MAY send multiple
    // PATH_CHALLENGE frames to guard against packet loss." Sends a
    // freshly-generated challenge and, if there's still time left
    // before the abandon deadline, arms a timer to do it again after
    // about one PTO -- deliberately independent of lossDetector (see
    // the class-level migration comment: these frames aren't
    // registered with it, so this can't piggyback on its PTO/loss
    // machinery and needs its own).
    private void sendPathChallengeAndScheduleRetry(final InetSocketAddress candidate,
            final PathValidationAttempt attempt, long pto) {
        attempt.challengeData = new byte[QuicFrameHandler.PATH_DATA_LENGTH];
        RANDOM.nextBytes(attempt.challengeData);
        sendPathChallenge(attempt.challengeData, candidate);

        long now = System.currentTimeMillis();
        long delay = Math.min(pto, attempt.deadlineMillis - now);
        if (delay <= 0) {
            abandonMigrationValidation(candidate);
            return;
        }
        attempt.timerHandle = engine.scheduleTimer(delay, new Runnable() {
            @Override
            public void run() {
                onPathValidationTimeout(candidate);
            }
        });
    }

    private void onPathValidationTimeout(InetSocketAddress candidate) {
        PathValidationAttempt attempt = pathValidationAttempts.get(candidate);
        // Already completed, abandoned, or the connection closed out
        // from under this timer -- nothing to do.
        if (closed || attempt == null) {
            return;
        }
        if (System.currentTimeMillis() >= attempt.deadlineMillis) {
            abandonMigrationValidation(candidate);
            return;
        }
        sendPathChallengeAndScheduleRetry(candidate, attempt, currentPathValidationPto());
    }

    // RFC 9000 section 8.2.4: "an endpoint SHOULD abandon path
    // validation based on a timer." Failure here just means staying on
    // the existing, already-validated path -- leaving remoteAddress
    // untouched already achieves that; this only needs to remove the
    // attempt's own entry so a stray late PATH_RESPONSE for the
    // abandoned candidate is no longer treated as validating anything.
    // Other concurrently-outstanding candidates, if any, are untouched.
    private void abandonMigrationValidation(InetSocketAddress candidate) {
        PathValidationAttempt attempt = pathValidationAttempts.remove(candidate);
        if (attempt != null && attempt.timerHandle != null) {
            attempt.timerHandle.cancel();
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Path validation abandoned for " + candidate
                    + ": no PATH_RESPONSE within the deadline");
        }
    }

    // Cancels every outstanding candidate's retry timer and clears the
    // map -- used both when one candidate wins (the others are moot)
    // and on connection teardown (nothing left to validate for).
    private void cancelAllPathValidationAttempts() {
        for (PathValidationAttempt attempt : pathValidationAttempts.values()) {
            if (attempt.timerHandle != null) {
                attempt.timerHandle.cancel();
            }
        }
        pathValidationAttempts.clear();
    }

    // See recentlyMigratedFromAddresses' field comment. Expires lazily
    // (checked here, not swept by a timer) rather than needing its own
    // scheduled cleanup -- an address that's never checked again simply
    // ages out of relevance without costing anything beyond map space,
    // already bounded by MAX_RECENTLY_MIGRATED_FROM.
    private boolean isRecentlyMigratedFrom(InetSocketAddress source) {
        Long migratedAtMillis = recentlyMigratedFromAddresses.get(source);
        if (migratedAtMillis == null) {
            return false;
        }
        if (System.currentTimeMillis() - migratedAtMillis.longValue() >= MIGRATION_COOLDOWN_MILLIS) {
            recentlyMigratedFromAddresses.remove(source);
            return false;
        }
        return true;
    }

    // RFC 9002 Appendix A.3's PTO formula (the same one
    // scheduleLossDetectionTimer ultimately relies on via LossDetector),
    // computed independently of lossDetector's own packet-number-space
    // bookkeeping (see the class-level migration comment for why
    // PATH_CHALLENGE isn't registered with it) -- reuses the same
    // RttEstimator instance lossDetector already maintains from
    // ordinary traffic, since a separate one would just start back at
    // kInitialRtt for no reason.
    //
    // The +peerMaxAckDelay() term is not optional padding: on a fast,
    // low-RTT path (loopback, or any well-connected real path) smoothed
    // RTT and rttvar can both be a millisecond or less, collapsing
    // smoothed+max(4*rttvar,1) to near-zero and turning "retry after
    // about one PTO" into a tight, near-continuous retransmission loop
    // -- exactly the RTT-independent floor max_ack_delay exists to
    // provide (a peer that's simply slow to ack shouldn't look like
    // packet loss).
    private long currentPathValidationPto() {
        RttEstimator rtt = lossDetector.getRttEstimator();
        long smoothed = rtt.hasRttSample() ? rtt.getSmoothedRtt() : RttEstimator.K_INITIAL_RTT;
        long rttVar = rtt.hasRttSample() ? rtt.getRttVar() : RttEstimator.K_INITIAL_RTT / 2;
        return smoothed + Math.max(4 * rttVar, 1) + peerMaxAckDelay();
    }

    // Called once a PATH_RESPONSE has proven the candidate path is real
    // (RFC 9000 section 8.2.3): switches over to it and, per RFC 9000
    // section 9.4, resets congestion control state, since the old
    // path's measurements no longer apply to the new one. Any other
    // concurrently-outstanding candidates are moot once we've actually
    // migrated -- cancelled and dropped rather than left to keep
    // sending PATH_CHALLENGEs for no purpose. The address being left
    // behind is recorded so its continued traffic doesn't immediately
    // look like another fresh migration candidate (see
    // recentlyMigratedFromAddresses' field comment).
    private void completeMigration(InetSocketAddress candidate) {
        if (remoteAddress != null) {
            recentlyMigratedFromAddresses.put(remoteAddress, Long.valueOf(System.currentTimeMillis()));
        }
        remoteAddress = candidate;
        cancelAllPathValidationAttempts();

        // RFC 9000 section 9.5: prefer a peer connection ID not already
        // used on the old path, if the peer has issued a spare one via
        // NEW_CONNECTION_ID; if not (the common case in a simple
        // two-endpoint exchange with no spare IDs), this just returns the
        // same entry already in use and rotation is a no-op.
        ConnectionIdEntry fresh = connectionIdManager.getActivePeerConnectionId();
        if (fresh != null && !Arrays.equals(fresh.getConnectionId(), peerConnectionId)) {
            connectionIdManager.retirePeerConnectionId(activePeerConnectionIdSequence);
            peerConnectionId = fresh.getConnectionId();
            activePeerConnectionIdSequence = fresh.getSequenceNumber();
        }

        lossDetector.getCongestionController().reset();
        requestFlush();
    }

    private void sendPathChallenge(byte[] data, InetSocketAddress destination) {
        sendPathFramePacket(QuicFrameHandler.TYPE_PATH_CHALLENGE, data, destination);
    }

    private void sendPathResponse(byte[] data, InetSocketAddress destination) {
        sendPathFramePacket(QuicFrameHandler.TYPE_PATH_RESPONSE, data, destination);
    }

    private void sendPathFramePacket(long frameType, byte[] data, InetSocketAddress destination) {
        try {
            // RFC 9000 section 8.2.1: a PATH_CHALLENGE-carrying datagram
            // must be expanded to the smallest allowed maximum datagram
            // size, so an off-path attacker can't use it to trigger an
            // amplified response; applied uniformly to PATH_RESPONSE too
            // for simplicity, even though the RFC only requires it of the
            // challenge (a PATH_RESPONSE is tiny either way).
            byte[] packet = buildStandalonePathFramePacket(frameType, data, MIN_DATAGRAM_SIZE);
            if (packet != null) {
                engine.sendTo(destination, packet);
            }
        } catch (PacketProtectionException e) {
            LOGGER.log(Level.WARNING, "Failed to protect outgoing PATH_CHALLENGE/PATH_RESPONSE packet", e);
        }
    }

    // Builds a standalone 1-RTT packet containing a single PATH_CHALLENGE
    // or PATH_RESPONSE frame, addressed to a possibly-different-from-
    // remoteAddress destination -- so, unlike every other outgoing frame,
    // this can't be folded into the normal buildProtectedPacket/flush
    // machinery (which always addresses remoteAddress) and isn't
    // registered with lossDetector (no retransmission tracking for these
    // two frame types in this simplified pass, matching the same
    // accepted gap already documented for MAX_DATA/MAX_STREAM_DATA).
    private byte[] buildStandalonePathFramePacket(long frameType, byte[] data, int minDatagramSize)
            throws PacketProtectionException {
        PacketProtectionKeys keys = sendKeys.get(EncryptionLevel.ONE_RTT);
        if (keys == null) {
            return null; // no 1-RTT keys yet; migration cannot apply before the handshake completes
        }
        int frameBytes = frameType == QuicFrameHandler.TYPE_PATH_CHALLENGE
                ? QuicFrameWriter.pathChallengeLength() : QuicFrameWriter.pathResponseLength();
        long packetNumber = sendPacketNumber[EncryptionLevel.ONE_RTT.ordinal()]++;
        int pnLength = PacketNumberCodec.encodedLength(packetNumber, -1);

        int hpSamplePadding = Math.max(0,
                4 + QuicAeadAlgorithm.SAMPLE_LENGTH - pnLength - QuicAeadAlgorithm.TAG_LENGTH - frameBytes);

        int paddingBytes = 0;
        byte[] header;
        while (true) {
            header = ShortHeaderCodec.build(peerConnectionId, false, packetNumber, pnLength);
            int required = minDatagramSize - (header.length + frameBytes + paddingBytes + QuicAeadAlgorithm.TAG_LENGTH);
            int nextPadding = Math.max(hpSamplePadding, Math.max(0, paddingBytes + required));
            if (nextPadding == paddingBytes) {
                break;
            }
            paddingBytes = nextPadding;
        }
        int totalFrameBytes = frameBytes + paddingBytes;

        ByteBuffer payload = ByteBuffer.allocate(totalFrameBytes);
        if (frameType == QuicFrameHandler.TYPE_PATH_CHALLENGE) {
            QuicFrameWriter.writePathChallenge(payload, data);
        } else {
            QuicFrameWriter.writePathResponse(payload, data);
        }
        if (paddingBytes > 0) {
            QuicFrameWriter.writePadding(payload, paddingBytes);
        }
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
        PacketProtection.xorFirstByte(packet, mask, false);
        PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);

        return packet;
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
    // Computes which queued STREAM chunks are currently eligible to send
    // (within flow control), respecting per-stream ordering (a blocked
    // chunk stops that stream's contribution, so a later chunk never
    // jumps ahead of an earlier blocked one), with the same
    // DATA_BLOCKED/STREAM_DATA_BLOCKED signalling side effects either
    // way -- shared between buildProtectedPacket's ONE_RTT case and
    // buildZeroRttPacketOrNull, since both drain the same pendingStream
    // queue under the same flow-control budget (0-RTT and 1-RTT share
    // one connection-level and per-stream send budget; RFC 9001 section
    // 4.6.1 doesn't create a separate one for 0-RTT).
    private Map<Long, List<PendingChunk>> drainEligibleStreamChunks() {
        Map<Long, List<PendingChunk>> streamChunksToSend = new HashMap<Long, List<PendingChunk>>();
        List<Map.Entry<Long, List<PendingChunk>>> entries =
                new ArrayList<Map.Entry<Long, List<PendingChunk>>>(pendingStream.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<Long, List<PendingChunk>>>() {
            @Override
            public int compare(Map.Entry<Long, List<PendingChunk>> a,
                    Map.Entry<Long, List<PendingChunk>> b) {
                int pa = streamSendPriority.containsKey(a.getKey())
                        ? streamSendPriority.get(a.getKey()).intValue() : 0;
                int pb = streamSendPriority.containsKey(b.getKey())
                        ? streamSendPriority.get(b.getKey()).intValue() : 0;
                if (pa != pb) {
                    return pb - pa;
                }
                return Long.compare(a.getKey().longValue(), b.getKey().longValue());
            }
        });
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<Long, List<PendingChunk>> entry = entries.get(i);
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
        return streamChunksToSend;
    }

    // RFC 9221: DATAGRAM frames that currently fit the peer's advertised
    // max_datagram_frame_size. Not removed from pendingDatagrams until
    // actually written, matching STREAM's drain-then-commit pattern.
    private List<byte[]> eligibleDatagrams() {
        List<byte[]> toSend = new ArrayList<byte[]>();
        if (peerMaxDatagramFrameSize <= 0) {
            return toSend;
        }
        for (int i = 0; i < pendingDatagrams.size(); i++) {
            byte[] payload = pendingDatagrams.get(i);
            if (QuicFrameWriter.datagramLength(payload.length) <= peerMaxDatagramFrameSize) {
                toSend.add(payload);
            }
        }
        return toSend;
    }

    // Converts receivedUnacked.get(level) into the descending, gap-encoded
    // range format QuicFrameWriter.writeAck/ackLength expect (RFC 9000
    // section 19.3: ranges[0] contains the largest acknowledged packet
    // number, each subsequent range strictly lower) -- or null if nothing
    // is currently owed. A single ACK frame covers every packet number
    // received since the last one was sent, not just the most recently
    // received packet, so an earlier packet received just before a later
    // one (e.g. a 0-RTT packet immediately followed by a 1-RTT one, before
    // this endpoint gets a chance to ACK the first) is never skipped.
    private long[][] computeAckRanges(EncryptionLevel level) {
        TreeSet<Long> unacked = receivedUnacked.get(level);
        if (unacked == null || unacked.isEmpty()) {
            return null;
        }
        List<long[]> ranges = new ArrayList<long[]>();
        long rangeHigh = -1;
        long rangeLow = -1;
        boolean inRange = false;
        for (Long boxed : unacked.descendingSet()) {
            long pn = boxed.longValue();
            if (!inRange) {
                rangeHigh = pn;
                rangeLow = pn;
                inRange = true;
            } else if (pn == rangeLow - 1) {
                rangeLow = pn;
            } else {
                ranges.add(new long[] { rangeLow, rangeHigh });
                rangeHigh = pn;
                rangeLow = pn;
            }
        }
        ranges.add(new long[] { rangeLow, rangeHigh });
        return ranges.toArray(new long[0][]);
    }

    // RFC 9000 section 13.2.5/19.3: the ACK Delay field is the time
    // elapsed, in this endpoint's own ack_delay_exponent units, between
    // receiving the largest packet number this ACK acknowledges and
    // sending the ACK itself -- used by the peer to discount that delay
    // out of its own RTT samples. largestReceivedTime is only ever
    // updated for a genuinely new largest received packet number (see
    // processPacket), matching what "receiving the largest acknowledged
    // packet" means here.
    private long computeAckDelay(EncryptionLevel level) {
        long receivedAt = largestReceivedTime[level.ordinal()];
        if (receivedAt < 0) {
            return 0;
        }
        long elapsedMicros = Math.max(0, System.currentTimeMillis() - receivedAt) * 1000;
        return elapsedMicros >>> DEFAULT_ACK_DELAY_EXPONENT;
    }

    private byte[] buildProtectedPacket(EncryptionLevel level, int minDatagramSize) throws PacketProtectionException {
        boolean oneRtt = level == EncryptionLevel.ONE_RTT;
        List<PendingChunk> cryptoChunks = pendingCrypto.get(level);

        Map<Long, List<PendingChunk>> streamChunksToSend = oneRtt
                ? drainEligibleStreamChunks() : new HashMap<Long, List<PendingChunk>>();

        long[][] ackRangesForLevel = ackOwed[level.ordinal()] ? computeAckRanges(level) : null;
        boolean includeAck = ackRangesForLevel != null;
        long ackDelay = includeAck ? computeAckDelay(level) : 0;
        boolean includeHandshakeDone = oneRtt && handshakeDoneOwed;
        boolean includePing = pendingPing[level.ordinal()];
        List<long[]> resetsToSend = oneRtt ? new ArrayList<long[]>(pendingResetStreams) : Collections.<long[]>emptyList();
        List<ConnectionIdEntry> newCidsToSend = oneRtt
                ? connectionIdManager.drainPendingIssuance() : Collections.<ConnectionIdEntry>emptyList();
        for (ConnectionIdEntry issued : newCidsToSend) {
            engine.registerConnectionId(issued.getConnectionId(), this);
        }
        long[] retiresToSend = oneRtt ? connectionIdManager.drainPendingRetirement() : new long[0];
        boolean includeMaxData = oneRtt && maxDataOwed;
        Map<Long, Long> maxStreamDataToSend = oneRtt
                ? new HashMap<Long, Long>(maxStreamDataOwed) : Collections.<Long, Long>emptyMap();
        boolean includeDataBlocked = oneRtt && dataBlockedOwed;
        Map<Long, Long> streamDataBlockedToSend = oneRtt
                ? new HashMap<Long, Long>(streamDataBlockedOwed) : Collections.<Long, Long>emptyMap();
        boolean includeMaxStreamsBidi = oneRtt && maxStreamsBidiOwed;
        boolean includeMaxStreamsUni = oneRtt && maxStreamsUniOwed;
        boolean includeStreamsBlockedBidi = oneRtt && streamsBlockedBidiOwed;
        boolean includeStreamsBlockedUni = oneRtt && streamsBlockedUniOwed;
        List<byte[]> datagramsToSend = oneRtt
                ? eligibleDatagrams() : Collections.<byte[]>emptyList();

        boolean nothingToSend = cryptoChunks.isEmpty() && streamChunksToSend.isEmpty() && !includeAck
                && !includeHandshakeDone && !includePing && resetsToSend.isEmpty() && newCidsToSend.isEmpty()
                && retiresToSend.length == 0 && !includeMaxData && maxStreamDataToSend.isEmpty()
                && !includeDataBlocked && streamDataBlockedToSend.isEmpty()
                && !includeMaxStreamsBidi && !includeMaxStreamsUni
                && !includeStreamsBlockedBidi && !includeStreamsBlockedUni
                && datagramsToSend.isEmpty();
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
        long[][] ackRanges = ackRangesForLevel;
        if (includeAck) {
            frameBytes += QuicFrameWriter.ackLength(ackRanges, ackDelay);
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
        if (includeMaxStreamsBidi) {
            frameBytes += QuicFrameWriter.maxStreamsLength(true, localMaxStreamsBidi);
        }
        if (includeMaxStreamsUni) {
            frameBytes += QuicFrameWriter.maxStreamsLength(false, localMaxStreamsUni);
        }
        if (includeStreamsBlockedBidi) {
            frameBytes += QuicFrameWriter.streamsBlockedLength(true, peerMaxStreamsBidi);
        }
        if (includeStreamsBlockedUni) {
            frameBytes += QuicFrameWriter.streamsBlockedLength(false, peerMaxStreamsUni);
        }
        for (byte[] datagram : datagramsToSend) {
            frameBytes += QuicFrameWriter.datagramLength(datagram.length);
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
            QuicFrameWriter.writeAck(payload, ackRanges, ackDelay);
            ackOwed[level.ordinal()] = false;
            // Deliberately not clearing receivedUnacked here -- this ACK
            // frame has only been written into a buffer, not confirmed
            // (or even necessarily yet sent: sealing, anti-amplification
            // withholding, or the socket send can still all fail after
            // this point). Record what it covered so the entries can be
            // retired once the peer actually confirms receipt (RFC 9000
            // section 13.2.4, see retireAcknowledgedRanges); until then,
            // they stay in receivedUnacked and are simply included again
            // in the next ACK this endpoint sends.
            TreeSet<Long> unacked = receivedUnacked.get(level);
            if (unacked != null && !unacked.isEmpty()) {
                long[] covered = new long[unacked.size()];
                int coveredIndex = 0;
                for (Long pn : unacked) {
                    covered[coveredIndex++] = pn.longValue();
                }
                Map<Long, long[]> coverage = sentAckCoverage.get(level);
                if (coverage == null) {
                    coverage = new HashMap<Long, long[]>();
                    sentAckCoverage.put(level, coverage);
                }
                coverage.put(Long.valueOf(packetNumber), covered);
            }
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
        if (includeMaxStreamsBidi) {
            QuicFrameWriter.writeMaxStreams(payload, true, localMaxStreamsBidi);
            maxStreamsBidiOwed = false;
        }
        if (includeMaxStreamsUni) {
            QuicFrameWriter.writeMaxStreams(payload, false, localMaxStreamsUni);
            maxStreamsUniOwed = false;
        }
        if (includeStreamsBlockedBidi) {
            QuicFrameWriter.writeStreamsBlocked(payload, true, peerMaxStreamsBidi);
            streamsBlockedBidiOwed = false;
        }
        if (includeStreamsBlockedUni) {
            QuicFrameWriter.writeStreamsBlocked(payload, false, peerMaxStreamsUni);
            streamsBlockedUniOwed = false;
        }
        for (byte[] datagram : datagramsToSend) {
            QuicFrameWriter.writeDatagram(payload, datagram);
        }
        if (!datagramsToSend.isEmpty()) {
            pendingDatagrams.removeAll(datagramsToSend);
        }
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
                || includeDataBlocked || !streamDataBlockedToSend.isEmpty()
                || !datagramsToSend.isEmpty();
        // RFC 9002 section 2: a packet is in flight when it's
        // ack-eliciting or carries a PADDING frame -- an ACK-only packet
        // (no ack-eliciting frame, no padding, e.g. a bare
        // buildProtectedPacket(HANDSHAKE, ...) call with nothing but an
        // ACK owed) is neither, and must not inflate bytes-in-flight or
        // be treated as congestion-window-relevant if it's ever declared
        // lost.
        boolean inFlight = ackEliciting || paddingBytes > 0;
        lossDetector.onPacketSent(level, packetNumber, System.currentTimeMillis(), ackEliciting, inFlight, packet.length);
        return packet;
    }

    // Client-only: builds one 0-RTT packet (RFC 9001 section 4.6.1)
    // containing whatever STREAM data is currently eligible to send,
    // or null if there are no 0-RTT keys yet or nothing eligible.
    // Deliberately a standalone builder rather than a branch inside
    // buildProtectedPacket: 0-RTT may only ever carry STREAM (and
    // stream-flow-control-signalling) frames -- RFC 9001 forbids
    // ACK/CRYPTO/HANDSHAKE_DONE/connection-ID-management frames there,
    // all of which buildProtectedPacket's ONE_RTT case also handles, so
    // widening that method's existing oneRtt gate would risk sending
    // something illegal in 0-RTT rather than narrowing what's sent.
    private byte[] buildZeroRttPacketOrNull() {
        // zeroRttSendKeys is deliberately never cleared just because the
        // handshake completes (only on explicit rejection, see
        // discardZeroRttDataAndKeys) -- but 0-RTT protection must still
        // stop being used for new data once established, or a client
        // that (correctly) deferred a non-eligible request until
        // establishment (see HTTP3ClientHandler.isSafeToSendNow) would
        // have that data sent under 0-RTT keys anyway the moment it's
        // finally queued, defeating the whole point of deferring it.
        if (zeroRttSendKeys == null || established) {
            return null;
        }
        try {
            return buildZeroRttProtectedPacket();
        } catch (PacketProtectionException e) {
            LOGGER.log(Level.WARNING, "Failed to protect outgoing 0-RTT packet", e);
            return null;
        }
    }

    private byte[] buildZeroRttProtectedPacket() throws PacketProtectionException {
        Map<Long, List<PendingChunk>> streamChunksToSend = drainEligibleStreamChunks();
        if (streamChunksToSend.isEmpty()) {
            return null;
        }

        int frameBytes = 0;
        for (Map.Entry<Long, List<PendingChunk>> entry : streamChunksToSend.entrySet()) {
            for (PendingChunk chunk : entry.getValue()) {
                frameBytes += QuicFrameWriter.streamLength(entry.getKey().longValue(), chunk.offset, chunk.data.length);
            }
        }

        // Shares ONE_RTT's packet-number space (RFC 9000 section 12.3).
        long packetNumber = sendPacketNumber[EncryptionLevel.ONE_RTT.ordinal()]++;
        int pnLength = PacketNumberCodec.encodedLength(packetNumber, -1);

        // RFC 9001 section 5.4.2: same header-protection-sample rationale
        // as buildProtectedPacket, but no independent padding-to-minimum
        // target -- a 0-RTT packet is always coalesced with an Initial
        // packet in the same datagram (RFC 9000 section 12.2), and that
        // Initial already pads the whole datagram to the 1200-byte
        // minimum (RFC 9000 section 14.1) in flush().
        int paddingBytes = Math.max(0,
                4 + QuicAeadAlgorithm.SAMPLE_LENGTH - pnLength - QuicAeadAlgorithm.TAG_LENGTH - frameBytes);
        byte[] header = LongHeaderCodec.build(LongHeaderCodec.TYPE_0RTT, 1, peerConnectionId, ourConnectionId,
                EMPTY_TOKEN, packetNumber, pnLength, frameBytes + paddingBytes + QuicAeadAlgorithm.TAG_LENGTH);
        int totalFrameBytes = frameBytes + paddingBytes;

        ByteBuffer payload = ByteBuffer.allocate(totalFrameBytes);
        Map<Long, List<PendingChunk>> sentThisPacket = new HashMap<Long, List<PendingChunk>>();
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
            sentThisPacket.put(entry.getKey(), entry.getValue());
        }
        if (paddingBytes > 0) {
            QuicFrameWriter.writePadding(payload, paddingBytes);
        }
        payload.flip();
        byte[] plaintext = new byte[payload.remaining()];
        payload.get(plaintext);

        byte[] ciphertext = PacketProtection.seal(zeroRttSendKeys, packetNumber, header, plaintext);
        byte[] packet = new byte[header.length + ciphertext.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(ciphertext, 0, packet, header.length, ciphertext.length);

        int pnOffset = header.length - pnLength;
        byte[] sample = new byte[QuicAeadAlgorithm.SAMPLE_LENGTH];
        System.arraycopy(packet, pnOffset + 4, sample, 0, QuicAeadAlgorithm.SAMPLE_LENGTH);
        byte[] mask = PacketProtection.headerProtectionMask(zeroRttSendKeys, sample);
        PacketProtection.xorFirstByte(packet, mask, true);
        PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);

        sentZeroRttStream.put(Long.valueOf(packetNumber), sentThisPacket);
        lossDetector.onPacketSent(EncryptionLevel.ONE_RTT, packetNumber, System.currentTimeMillis(), true, true,
                packet.length);
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

    /** RFC 9000 section 20.1: the endpoint encountered an internal error and cannot continue. */
    private static final long TRANSPORT_ERROR_INTERNAL_ERROR = 0x1;
    /** RFC 9000 section 20.1: an endpoint received more data than the flow control limits it advertised permit. */
    private static final long TRANSPORT_ERROR_FLOW_CONTROL_ERROR = 0x3;
    /** RFC 9000 section 20.1: an endpoint received a STREAM/RESET_STREAM/STREAM_DATA_BLOCKED frame that would open more streams than it advertised. */
    private static final long TRANSPORT_ERROR_STREAM_LIMIT_ERROR = 0x4;
    /** RFC 9000 section 20.1: a frame was malformed, e.g. MAX_STREAMS greater than 2^60. */
    private static final long TRANSPORT_ERROR_FRAME_ENCODING_ERROR = 0x7;
    /** RFC 9000 section 20.1: a transport parameter was received with a value not permitted for its type, e.g. a mismatched retry_source_connection_id. */
    private static final long TRANSPORT_ERROR_TRANSPORT_PARAMETER_ERROR = 0x8;
    /** RFC 9000 section 20.1: an endpoint received a frame that violates protocol rules, e.g. a DATAGRAM when max_datagram_frame_size was not advertised (RFC 9221). */
    private static final long TRANSPORT_ERROR_PROTOCOL_VIOLATION = 0xa;
    /** RFC 9000 section 20.1: the amount of buffered reordered CRYPTO data exceeds this endpoint's ability to buffer it. */
    private static final long TRANSPORT_ERROR_CRYPTO_BUFFER_EXCEEDED = 0xd;
    /** RFC 9000 section 19.11: MAX_STREAMS (and initial_max_streams_*) must not exceed 2^60. */
    private static final long MAX_STREAMS_COUNT = 1L << 60;

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
        deferredCloseIsError = true;
        close();
    }

    /**
     * Closes the connection with an application-level error code (RFC
     * 9000 section 19.19, the 0x1d CONNECTION_CLOSE variant), e.g. an
     * ALPN-scoped protocol violation the application layer detected.
     *
     * @param errorCode the application-defined error code
     * @param reason a human-readable reason phrase
     */
    public void closeWithApplicationError(long errorCode, String reason) {
        deferredCloseApplicationError = true;
        deferredCloseErrorCode = errorCode;
        deferredCloseReason = reason;
        deferredCloseIsError = true;
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
        cancelAllPathValidationAttempts();
        // RFC 9000 section 10.2.1: send at only the highest available
        // level -- EncryptionLevel.values() is declared in ascending
        // order (INITIAL, HANDSHAKE, ONE_RTT), so this must walk it
        // backwards; iterating forwards and breaking at the first
        // non-null level (as this used to) picks the *lowest* available
        // level instead. That inversion stayed invisible for as long as
        // Initial/Handshake keys were never actually discarded (see
        // discardEncryptionLevel) -- every level's sendKeys entry was
        // permanently non-null, so this loop always picked INITIAL,
        // and both sides having kept every key forever meant the peer
        // could still decrypt it regardless of the level mismatch.
        EncryptionLevel[] levels = EncryptionLevel.values();
        for (int i = levels.length - 1; i >= 0; i--) {
            EncryptionLevel level = levels[i];
            PacketProtectionKeys keys = sendKeys.get(level);
            if (keys == null) {
                continue;
            }
            try {
                sendConnectionClose(level, keys);
                break;
            } catch (PacketProtectionException e) {
                LOGGER.log(Level.FINE, "Failed to send CONNECTION_CLOSE", e);
            }
        }
        tearDownStreams();
        engine.onConnectionClosed(this);
    }

    /**
     * Drops local connection state without notifying the peer (no
     * CONNECTION_CLOSE, no stream teardown). Used when this endpoint
     * has forgotten the connection but the peer may still send
     * packets -- RFC 9000 section 10.3 stateless reset send path.
     */
    void dropLocalState() {
        if (closed) {
            return;
        }
        closed = true;
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
        }
        cancelAllPathValidationAttempts();
        engine.onConnectionClosed(this);
    }

    private void closeFromStatelessReset() {
        if (closed) {
            return;
        }
        closed = true;
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
        }
        cancelAllPathValidationAttempts();
        deferredCloseIsError = true;
        tearDownStreams(new QuicStatelessResetException());
        engine.onConnectionClosed(this);
    }

    private void tearDownStreams() {
        tearDownStreams(deferredCloseIsError
                ? new QuicConnectionCloseException(
                        deferredCloseApplicationError, deferredCloseErrorCode, deferredCloseReason)
                : null);
    }

    private void tearDownStreams(Exception streamError) {
        for (QuicStreamEndpoint stream : streams.values()) {
            stream.markClosed();
            if (streamError != null) {
                stream.getHandler().error(streamError);
            } else {
                stream.getHandler().disconnected();
            }
        }
        streams.clear();
        failPendingOpens(streamError);
    }

    private void failPendingOpens(Exception streamError) {
        Exception cause = streamError != null ? streamError : new IOException("Connection closed");
        List<ProtocolHandler> pending = new ArrayList<ProtocolHandler>(pendingOpenBidi);
        pending.addAll(pendingOpenUni);
        pendingOpenBidi.clear();
        pendingOpenUni.clear();
        for (int i = 0; i < pending.size(); i++) {
            pending.get(i).error(cause);
        }
    }

    private void sendConnectionClose(EncryptionLevel level, PacketProtectionKeys keys) throws PacketProtectionException {
        String reason = deferredCloseReason != null ? deferredCloseReason : "";
        long errorCode = deferredCloseErrorCode;
        boolean applicationError = deferredCloseApplicationError;
        int frameBytes = QuicFrameWriter.connectionCloseLength(applicationError, errorCode, reason);
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
        QuicFrameWriter.writeConnectionClose(payload, applicationError, errorCode, 0, reason);
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
        selectHkdfAead(selectedCipher());
        byte[] clientSecret = tlsEngine.getClientHandshakeTrafficSecret();
        byte[] serverSecret = tlsEngine.getServerHandshakeTrafficSecret();
        deriveDirectionalKeys(EncryptionLevel.HANDSHAKE, clientSecret, serverSecret);
    }

    // Sets this.hkdf/this.aead from the negotiated cipher suite. Extracted
    // from handshakeSecretsAvailable so earlySecretsAvailable can also use
    // it -- 0-RTT keys must be derivable earlier than that method runs
    // (right after ClientHello, before HANDSHAKE keys exist at all), but
    // the cipher a 0-RTT attempt uses is always the one the resumed
    // session was originally issued under, which TLS 1.3 requires the
    // eventual full handshake to also select if it accepts resumption at
    // all -- so calling this twice (once early, once at
    // handshakeSecretsAvailable time) is a safe, idempotent re-set of the
    // same values, not a real state change.
    private void selectHkdfAead(TlsConstants.CipherSuite cipher) {
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
                    ? !Arrays.equals(retryScid, expectedRetrySourceConnectionId)
                    : retryScid != null;
            if (mismatch) {
                closeWithError(TRANSPORT_ERROR_TRANSPORT_PARAMETER_ERROR, "retry_source_connection_id mismatch");
                return;
            }
            // RFC 9000 section 7.4.1: a server accepting 0-RTT "MUST NOT
            // reduce any limits or alter any values that might be
            // violated by the client with its 0-RTT data" below what it
            // remembered offering (seedRememberedTransportParameters).
            // The RFC places this obligation on the server, not the
            // client -- but this connection already offered 0-RTT under
            // those remembered limits by the time the real ones arrive,
            // so a server that shrinks them here is either broken or
            // actively hostile; either way, continuing under limits the
            // peer has already contradicted isn't safe. Checked only
            // when 0-RTT was actually offered (peerTransportParameters
            // is otherwise still null at this point) -- a connection
            // that never attempted 0-RTT has nothing to protect.
            if (zeroRttState != ZeroRttState.NONE && peerTransportParameters != null) {
                TransportParameters remembered = peerTransportParameters;
                if (transportParameters.getInitialMaxData() < remembered.getInitialMaxData()
                        || transportParameters.getInitialMaxStreamDataBidiLocal()
                                < remembered.getInitialMaxStreamDataBidiLocal()
                        || transportParameters.getInitialMaxStreamDataBidiRemote()
                                < remembered.getInitialMaxStreamDataBidiRemote()
                        || transportParameters.getInitialMaxStreamDataUni()
                                < remembered.getInitialMaxStreamDataUni()
                        || transportParameters.getInitialMaxStreamsBidi() < remembered.getInitialMaxStreamsBidi()
                        || transportParameters.getInitialMaxStreamsUni() < remembered.getInitialMaxStreamsUni()
                        || transportParameters.getMaxDatagramFrameSize()
                                < remembered.getMaxDatagramFrameSize()) {
                    closeWithError(TRANSPORT_ERROR_TRANSPORT_PARAMETER_ERROR,
                            "0-RTT transport parameters reduced below remembered values");
                    return;
                }
            }
        }
        this.peerTransportParameters = transportParameters;
        peerMaxData = transportParameters.getInitialMaxData();
        peerMaxDatagramFrameSize = transportParameters.getMaxDatagramFrameSize();
        if (peerMaxDatagramFrameSize <= 0) {
            pendingDatagrams.clear();
        } else {
            int i = 0;
            while (i < pendingDatagrams.size()) {
                byte[] payload = pendingDatagrams.get(i);
                if (QuicFrameWriter.datagramLength(payload.length) > peerMaxDatagramFrameSize) {
                    pendingDatagrams.remove(i);
                } else {
                    i++;
                }
            }
        }
        long initialMaxStreamsBidi = transportParameters.getInitialMaxStreamsBidi();
        long initialMaxStreamsUni = transportParameters.getInitialMaxStreamsUni();
        if (initialMaxStreamsBidi > MAX_STREAMS_COUNT || initialMaxStreamsUni > MAX_STREAMS_COUNT) {
            closeWithError(TRANSPORT_ERROR_TRANSPORT_PARAMETER_ERROR,
                    "initial_max_streams exceeds 2^60");
            return;
        }
        // RFC 9000 section 19.11: MAX_STREAMS that do not increase the
        // limit MUST be ignored. The same applies when 0-RTT already
        // seeded a remembered ceiling: a later handshake offering the
        // same or a larger value is applied (and may drain queued opens);
        // a smaller value was already rejected above as a 0-RTT shrink.
        if (initialMaxStreamsBidi > peerMaxStreamsBidi) {
            peerMaxStreamsBidi = initialMaxStreamsBidi;
            streamsBlockedBidiSignalled = false;
        }
        if (initialMaxStreamsUni > peerMaxStreamsUni) {
            peerMaxStreamsUni = initialMaxStreamsUni;
            streamsBlockedUniSignalled = false;
        }
        drainPendingOpens();
        if (!isServer) {
            byte[] resetToken = transportParameters.getStatelessResetToken();
            if (resetToken != null) {
                connectionIdManager.setPeerHandshakeResetToken(resetToken);
            }
        }
    }

    @Override
    public void earlySecretsAvailable() {
        // RFC 9001 section 4.6.1: fires before either side has decided
        // whether 0-RTT will actually be accepted -- server-side,
        // Agent15 has already called isEarlyDataAccepted() by this point
        // (see QuicTlsServerEngine), so that decision is known; skip key
        // derivation entirely if the server isn't going to use them.
        if (isServer && !((org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine) tlsEngine).wasEarlyDataAccepted()) {
            return;
        }
        // Agent15 fires this callback on the client side for every
        // handshake, once ServerHello arrives, regardless of whether a
        // session ticket was ever presented (confirmed against its own
        // source -- an internal artifact, not something gumdrop can or
        // needs to influence). A null cipher here just means this
        // connection never attempted resumption; nothing to derive, and
        // nothing worth logging -- this is the common case, not an error.
        TlsConstants.CipherSuite cipher = isServer
                ? ((org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine) tlsEngine).getSelectedCipher()
                : ((org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine) tlsEngine).getEarlyDataCipher();
        if (cipher == null) {
            return;
        }
        selectHkdfAead(cipher);
        byte[] clientEarlyTrafficSecret = tlsEngine.getClientEarlyTrafficSecret();
        PacketProtectionKeys keys = PacketProtectionKeys.derive(hkdf, clientEarlyTrafficSecret, aead);
        if (isServer) {
            zeroRttRecvKeys = keys;
        } else {
            zeroRttSendKeys = keys;
            zeroRttState = ZeroRttState.OFFERED;
            if (earlyDataHandler != null) {
                QuicEngine.EarlyDataHandler handlerToNotify = earlyDataHandler;
                earlyDataHandler = null;
                handlerToNotify.earlyDataReady(this);
            }
        }
    }

    @Override
    public void newSessionTicketReceived(NewSessionTicket ticket) {
        // Client-only (a server never receives a NewSessionTicket message
        // -- it sends them); and nothing useful to remember before this
        // connection's own transport parameters are known.
        if (isServer || peerTransportParameters == null) {
            return;
        }
        String host = serverName != null ? serverName : remoteAddress.getHostString();
        SessionTicketCache.put(host, remoteAddress.getPort(), ticket, peerTransportParameters);
    }

    @Override
    public void earlyDataOutcomeKnown(boolean accepted) {
        // Fires on every client handshake (see QuicTlsClientEngine);
        // only meaningful if this connection actually offered 0-RTT.
        if (zeroRttState != ZeroRttState.OFFERED) {
            return;
        }
        zeroRttState = accepted ? ZeroRttState.ACCEPTED : ZeroRttState.REJECTED;
        if (!accepted) {
            discardZeroRttDataAndKeys();
        }
    }

    // RFC 9001 section 4.6.1: if the server rejects 0-RTT, the client
    // MUST discard the 0-RTT keys and treat any 0-RTT-sent data as if it
    // had never been sent -- i.e. be prepared to resend it at 1-RTT,
    // from scratch, once the real handshake completes.
    //
    // This is correct without any offset/stream-ID bookkeeping: every
    // chunk ever sent as 0-RTT is, by construction, that stream's first
    // data (a stream opened specifically for 0-RTT, from
    // QuicEngine.EarlyDataHandler#earlyDataReady), so its PendingChunk's
    // offset already starts at 0 (assigned at queue time, in
    // queueStreamData, not at send time). Moving those exact chunks back
    // into pendingStream, per stream, ahead of anything else already
    // queued there, and letting the ordinary buildProtectedPacket
    // (ONE_RTT) drain path resend them once real 1-RTT keys exist,
    // reproduces "as if it had never been sent" precisely: same stream
    // ID, same offsets, same bytes -- the peer discarded the rejected
    // 0-RTT stream entirely, so it never actually existed from its side,
    // and reusing the ID here isn't a reuse-of-a-live-stream bug. The
    // application's own ProtocolHandler for that stream is never told
    // anything went wrong; its bytes just arrive later than they would
    // have. The QUIC packet-number sequence itself is deliberately left
    // alone -- packet numbers within one space must stay monotonic (RFC
    // 9000), so only keys and stream data are discarded here, not the
    // sequence.
    private void discardZeroRttDataAndKeys() {
        zeroRttSendKeys = null;
        // Each packet's own chunk list is already in correct
        // ascending-offset order (see drainEligibleStreamChunks), and
        // each is inserted whole at the front of pendingStream's list --
        // so walking packets in DESCENDING packet-number order here
        // means the earliest packet's chunks end up inserted last,
        // landing at the very front, restoring the original overall
        // send order. This matters because gumdrop's own receive side
        // delivers in arrival order only (no offset-based reassembly,
        // see the class documentation), so a resend to another gumdrop
        // peer needs its relative order preserved, not just each
        // individual chunk's offset being correct.
        for (Map.Entry<Long, Map<Long, List<PendingChunk>>> packetEntry
                : new TreeMap<Long, Map<Long, List<PendingChunk>>>(sentZeroRttStream)
                        .descendingMap().entrySet()) {
            for (Map.Entry<Long, List<PendingChunk>> entry : packetEntry.getValue().entrySet()) {
                List<PendingChunk> chunks = pendingStream.get(entry.getKey());
                if (chunks == null) {
                    chunks = new ArrayList<PendingChunk>();
                    pendingStream.put(entry.getKey(), chunks);
                }
                chunks.addAll(0, entry.getValue());
            }
        }
        sentZeroRttStream.clear();
        requestFlush();
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
