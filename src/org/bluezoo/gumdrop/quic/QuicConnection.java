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
import java.net.Inet6Address;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;


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
import org.bluezoo.gumdrop.quic.packet.AckFrequencyDraft;
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
import org.bluezoo.gumdrop.quic.packet.QuicVersion;
import org.bluezoo.gumdrop.quic.packet.VarInt;
import org.bluezoo.gumdrop.quic.packet.VersionNegotiationPacket;
import org.bluezoo.gumdrop.quic.recovery.CongestionController;
import org.bluezoo.gumdrop.quic.recovery.LossDetector;
import org.bluezoo.gumdrop.quic.recovery.RttEstimator;
import org.bluezoo.gumdrop.quic.recovery.SentPacket;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;
import org.bluezoo.gumdrop.crypto.Hkdf;
import org.bluezoo.gumdrop.quic.tls.InitialSecrets;
import org.bluezoo.gumdrop.quic.tls.QuicTlsEngine;
import org.bluezoo.gumdrop.quic.tls.QuicTlsEngineListener;
import org.bluezoo.gumdrop.quic.tls.StreamReassembler;
import org.bluezoo.gumdrop.tls.CipherSuite;
import org.bluezoo.gumdrop.tls.SessionTicket;

/**
 * One QUIC connection: owns the TLS 1.3 handshake, packet protection
 * keys, connection ID lifecycle, loss detection/congestion control, and
 * every stream on the connection.
 *
 * <p>It composes the toolkit packages {@code quic.tls}, {@code
 * quic.packet}, {@code quic.frame}, {@code quic.cid}, and {@code
 * quic.recovery} into a single connection state machine, driven by real
 * socket I/O via {@link QuicEngine}.
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
public final class QuicConnection implements QuicTlsEngineListener, QlogSink {

    private static final Logger LOGGER = Logger.getLogger(QuicConnection.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.quic.L10N");

    /** RFC 9000 section 14.1: every implementation must support at least this size. */
    static final int MIN_DATAGRAM_SIZE = 1200;
    // RFC 9000 section 14: no datagram larger than the path supports. There
    // is no path MTU discovery, so this is the minimum every path must carry.
    static final int MAX_DATAGRAM_SIZE = MIN_DATAGRAM_SIZE;
    private static final int MAX_DATAGRAMS_PER_FLUSH = 256;

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
    /**
     * Added to the wall clock by {@link #nowMillis()}. Always zero in
     * production; a package-private seam so tests can advance the time
     * seen by loss detection and path validation (RFC 9002) without
     * waiting in real time.
     */
    long clockOffsetMillis;
    /** Test seam like {@link #clockOffsetMillis}, for sub-millisecond steps. Zero in production. */
    long clockOffsetNanos;
    /**
     * Test seam: when non-zero, {@link #nowNanos()} and {@link #nowMillis()}
     * stop following the real clock and read this value (plus the offsets),
     * so a test sees exactly the time it has advanced and no more. Zero in
     * production.
     */
    long frozenNanos;

    /**
     * Test hook: run on the event loop after each RETIRE_CONNECTION_ID
     * frame from the peer has been applied, so a test can synchronise on
     * connection ID rotation completing instead of polling.
     */
    volatile Runnable retireConnectionIdHook;

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

    // RFC 9001 section 6: 1-RTT key update state. The current generation's
    // secrets are kept so the next can be derived; the receive side holds
    // the next generation's keys ready (a peer may update at any time) and
    // the previous generation's for a few PTOs, for packets that were
    // reordered across the update. sendKeyPhase/recvKeyPhase are the Key
    // Phase bits the current send/receive keys belong to; they differ only
    // while an update is half way through.
    private byte[] oneRttSendSecret;
    private byte[] oneRttRecvSecret;
    private byte[] nextRecvSecret;
    private PacketProtectionKeys nextRecvKeys;
    private PacketProtectionKeys previousRecvKeys;
    private long previousRecvKeysDiscardAtMillis;
    private boolean sendKeyPhase;
    private boolean recvKeyPhase;
    // Lowest packet number read with the current receive keys, which tells
    // a late packet of the previous phase from the first of the next one.
    private long lowestPnOfCurrentRecvPhase = -1;
    // RFC 9001 section 6.1: no new update until a packet sent with the
    // current send keys has been acknowledged.
    private long firstPnOfCurrentSendPhase;
    private boolean currentSendKeysAcknowledged;
    private int keyUpdateCount;
    private int previousPhasePacketsRead;

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
    // Nanoseconds (nowNanos), so the ACK Delay field can resolve less
    // than a millisecond.
    private final long[] largestReceivedTime = { -1, -1, -1 };
    private final boolean[] ackOwed = new boolean[EncryptionLevel.values().length];
    // RFC 9000 section 13.2.1 delayed-ACK state, per space: ack-eliciting
    // packets received since an ACK was last written, when the first of
    // them arrived (nanoTime based), and whether one calls for an ACK
    // without waiting (an out-of-order arrival).
    private final int[] ackElicitingUnacked = new int[EncryptionLevel.values().length];
    private final long[] firstUnackedNanos = new long[EncryptionLevel.values().length];
    private final boolean[] ackImmediate = new boolean[EncryptionLevel.values().length];
    // The ACK scheduling parameters: RFC 9000 section 13.2 until the peer
    // sends ACK_FREQUENCY frames (draft-ietf-quic-ack-frequency section 4).
    // An ACK is sent once more than ackElicitingThreshold ack-eliciting
    // packets have arrived, or requestedMaxAckDelayMicros have elapsed
    // since the first (negative: this endpoint's own max_ack_delay), and
    // at once for a packet reordered by reorderingThreshold or more
    // (zero: never).
    private long ackElicitingThreshold = AckFrequencyDraft.DEFAULT_ACK_ELICITING_THRESHOLD;
    private long requestedMaxAckDelayMicros = -1;
    private long reorderingThreshold = AckFrequencyDraft.DEFAULT_REORDERING_THRESHOLD;
    private long lastAckFrequencySequence = -1;
    // aggregate server metrics, or null on a client or when metrics are off
    private final QuicServerMetrics metrics;
    private long metricsRttSamples;
    // qlog events (draft-ietf-quic-qlog-quic-events), or null when this connection does not log
    private QuicQlog qlog;
    private boolean qlogClosedLogged;
    private boolean closedByPeer;
    private long[] qlogLastMetrics;
    private String qlogCongestionState;
    private int qlogTuples;
    // The peer's min_ack_delay in microseconds, from its real transport
    // parameters only (never a remembered set): negative when absent
    private long peerMinAckDelayMicros = -1;
    // Sending ACK_FREQUENCY (draft-ietf-quic-ack-frequency sections 4, 6):
    // the next Sequence Number, whether a frame is owed, and the frames in
    // flight keyed by the packet number carrying them: {sequence, requested
    // max ack delay in microseconds, ack-eliciting threshold}
    private long nextAckFrequencySequence;
    private boolean ackFrequencyOwed;
    private boolean ackFrequencyQueuedOnce;
    private long latestAckFrequencySequenceSent = -1;
    private long ackedAckFrequencySequence = -1;
    private final Map<Long, long[]> sentAckFrequency = new HashMap<Long, long[]>();
    // The requested max ack delay the peer is known to apply (the latest
    // acknowledged frame), and that of the latest frame still unacknowledged
    private long ackFrequencyAckedDelayMicros = -1;
    private long ackFrequencyPendingDelayMicros = -1;
    // IMMEDIATE_ACK: owed to a probe packet, or for ACK silence beyond a round trip
    private boolean immediateAckOwed;
    private boolean immediateAckSinceLastAck;
    private long lastAckReceivedMicros = Long.MIN_VALUE;
    private TimerHandle ackTimerHandle;
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
    // Issue #320: pendingStream's key set, incrementally kept in the
    // exact priority order (descending priority, then ascending stream
    // ID for ties) drainEligibleStreamChunks needs -- rather than
    // re-sorting the whole pending set from scratch on every flush.
    // Every pendingStream mutation must go through
    // addPendingStreamChunks/removePendingStream below rather than the
    // map directly, to keep this in sync; a priority change for a stream
    // already pending must remove-then-reinsert it here (a TreeSet's
    // ordering invariant only holds while its comparator's inputs stay
    // fixed for a member), which is why setStreamSendPriority does the
    // same rather than just writing into streamSendPriority.
    private final TreeSet<Long> pendingStreamOrder = new TreeSet<Long>(new Comparator<Long>() {
        @Override
        public int compare(Long a, Long b) {
            int pa = streamSendPriority.containsKey(a) ? streamSendPriority.get(a).intValue() : 0;
            int pb = streamSendPriority.containsKey(b) ? streamSendPriority.get(b).intValue() : 0;
            if (pa != pb) {
                return pb - pa;
            }
            return Long.compare(a.longValue(), b.longValue());
        }
    });
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
    // Streams of the peer's below nextPeerBidiStreamId/nextPeerUniStreamId
    // that a higher-numbered stream opened implicitly and that have not yet
    // been seen themselves (see acceptStream). MAX_STREAMS bounds it.
    private final Set<Long> peerStreamsNotYetSeen = new HashSet<Long>();

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
    // The QUIC version in use. It starts as initialVersion, the version of
    // this attempt's first flight, and changes at most once, when
    // compatible version negotiation (RFC 9368 section 2.3) selects
    // another: the server on reading the client's version_information,
    // the client on seeing a long header of that version.
    private QuicVersion version;
    private final QuicVersion initialVersion;
    // The Destination Connection ID Initial keys are derived from (RFC
    // 9001 section 5.2): the client's first Destination Connection ID, or
    // after a Retry the Retry's Source Connection ID.
    private byte[] initialKeyDcid;
    // After a version switch, Initial packets of initialVersion are still
    // accepted (RFC 9369 section 4.1) until a Handshake packet of the new
    // version is processed.
    private PacketProtectionKeys initialVersionRecvKeys;
    // Client: this attempt was started in response to a Version
    // Negotiation packet (RFC 9368 section 4).
    private final boolean afterVersionNegotiation;
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
    private final Map<PathKey, PathValidationAttempt> pathValidationAttempts = new HashMap<PathKey, PathValidationAttempt>();
    private final Map<InetSocketAddress, Long> recentlyMigratedFromAddresses =
            new LinkedHashMap<InetSocketAddress, Long>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<InetSocketAddress, Long> eldest) {
                    return size() > MAX_RECENTLY_MIGRATED_FROM;
                }
            };
    private InetSocketAddress currentDatagramSource;
    // The path the datagram being processed arrived on (RFC 9000 section
    // 9: a path is a local and a remote address; the local side is which
    // of the engine's sockets it came in by).
    private QuicDatagramPath currentDatagramPath;
    // The path this connection sends on; null means the engine's primary.
    private QuicDatagramPath sendPath;
    // Client: a server preferred_address awaiting handshake confirmation.
    private boolean preferredAddressPending;
    private boolean sawValidOneRttThisReceive;
    private boolean decryptFailedOrUnparseableThisDatagram;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecurityInfo securityInfo;
    private TimerHandle timerHandle;
    private boolean established;

    /**
     * Test-only hooks for deterministic cross-thread synchronization in
     * E2E tests. All run on the connection's {@link SelectorLoop} thread.
     */
    static volatile Runnable lossDetectionIdleObserver;
    static volatile Runnable oneRttKeysReadyObserver;
    static volatile EncryptionLevelDiscardedObserver encryptionLevelDiscardedObserver;
    static volatile MigrationCompletedObserver migrationCompletedObserver;
    static volatile PathValidationRejectedObserver pathValidationRejectedObserver;
    static volatile PathValidationAbandonedObserver pathValidationAbandonedObserver;

    /** @see #encryptionLevelDiscardedObserver */
    interface EncryptionLevelDiscardedObserver {
        void encryptionLevelDiscarded(QuicConnection connection, EncryptionLevel level);
    }

    /** @see #migrationCompletedObserver */
    interface MigrationCompletedObserver {
        void migrationCompleted(QuicConnection connection, InetSocketAddress newRemote);
    }

    /** @see #pathValidationRejectedObserver */
    interface PathValidationRejectedObserver {
        void pathValidationRejected(QuicConnection connection, InetSocketAddress candidate);
    }

    /** @see #pathValidationAbandonedObserver */
    interface PathValidationAbandonedObserver {
        void pathValidationAbandoned(QuicConnection connection, InetSocketAddress candidate);
    }

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
            TransportParameters localTransportParameters, byte[] connectionIdStaticKey, QuicVersion version,
            boolean afterVersionNegotiation) {
        this.engine = engine;
        this.version = version;
        this.initialVersion = version;
        this.initialKeyDcid = initialSecretDcid;
        this.afterVersionNegotiation = afterVersionNegotiation;
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

        PacketProtectionKeys[] initialKeys = deriveInitialKeys(version, initialSecretDcid);
        PacketProtectionKeys clientKeys = initialKeys[0];
        PacketProtectionKeys serverKeys = initialKeys[1];
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
        this.connectionIdManager = new ConnectionIdManager(ourConnectionId, peerConnectionId, connectionIdStaticKey,
                isServer ? engine.getQuicLbConfig() : null);
        this.localMaxData = localTransportParameters.getInitialMaxData();
        this.localMaxStreamsBidi = localTransportParameters.getInitialMaxStreamsBidi();
        this.localMaxStreamsUni = localTransportParameters.getInitialMaxStreamsUni();
        this.metrics = isServer ? engine.getMetrics() : null;
        if (metrics != null) {
            metrics.connectionOpened();
        }
        this.qlog = QuicQlog.create(engine, isServer, initialSecretDcid);
        if (qlog != null) {
            qlogConnectionStarted();
            qlog.emit(QlogEvents.PARAMETERS_SET, nowNanos(),
                    qlogParameters(QlogEvents.OWNER_LOCAL, localTransportParameters));
            qlogRecoveryParameters();
            qlogTupleAssigned();
        }
    }

    // ── qlog (draft-ietf-quic-qlog-quic-events) ──

    boolean isServerSide() {
        return isServer;
    }

    @Override
    public boolean isQlogEnabled() {
        return qlog != null;
    }

    @Override
    public void emitQlog(String schema, String eventName, String dataJson) {
        if (qlog != null) {
            qlog.emit(schema, eventName, nowNanos(), dataJson);
        }
    }

    // Counts the packets just declared lost and a round-trip time sample if
    // the acknowledgement just processed took one.
    private void metricsRecoverySample(int newlyLost) {
        for (int i = 0; i < newlyLost; i++) {
            metrics.packetLost();
        }
        RttEstimator rtt = lossDetector.getRttEstimator();
        if (rtt.getSampleCount() != metricsRttSamples) {
            metricsRttSamples = rtt.getSampleCount();
            metrics.rttSample(rtt.getLatestRtt());
        }
    }

    // The protocols this end offered or accepts, and the one the handshake chose.
    private void qlogAlpnInformation() {
        String chosen = tlsEngine.getNegotiatedApplicationProtocol();
        String configured = engine.getApplicationProtocols();
        if (chosen == null && (configured == null || configured.isEmpty())) {
            return;
        }
        QlogJson data = QlogJson.object();
        if (configured != null && !configured.isEmpty()) {
            data.beginArray(isServer ? QlogEvents.SERVER_ALPNS : QlogEvents.CLIENT_ALPNS);
            String[] protocols = configured.split(",");
            for (int i = 0; i < protocols.length; i++) {
                data.itemRaw(QlogJson.object().put(QlogEvents.STRING_VALUE, protocols[i].trim()).build());
            }
            data.endArray();
        }
        if (chosen != null) {
            data.beginObject(QlogEvents.CHOSEN_ALPN).put(QlogEvents.STRING_VALUE, chosen).endObject();
        }
        qlog.emit(QlogEvents.ALPN_INFORMATION, nowNanos(), data);
    }

    private void qlogConnectionStarted() {
        QlogJson data = QlogJson.object();
        InetSocketAddress src = localAddress;
        InetSocketAddress dst = remoteAddress;
        if (dst != null && dst.getAddress() != null) {
            data.put(QlogEvents.IP_VERSION, dst.getAddress() instanceof java.net.Inet6Address ? "v6" : "v4");
        }
        if (src != null && src.getAddress() != null) {
            data.put(QlogEvents.SRC_IP, src.getAddress().getHostAddress());
            data.put(QlogEvents.SRC_PORT, src.getPort());
        }
        if (dst != null && dst.getAddress() != null) {
            data.put(QlogEvents.DST_IP, dst.getAddress().getHostAddress());
            data.put(QlogEvents.DST_PORT, dst.getPort());
        }
        data.putHex(QlogEvents.SRC_CID, ourConnectionId);
        data.putHex(QlogEvents.DST_CID, peerConnectionId);
        qlog.emit(QlogEvents.CONNECTION_STARTED, nowNanos(), data);
    }

    private static QlogJson qlogParameters(String owner, TransportParameters p) {
        QlogJson data = QlogJson.object();
        if (owner != null) {
            data.put(QlogEvents.OWNER, owner);
        }
        if (p.getOriginalDestinationConnectionId() != null) {
            data.putHex(QlogEvents.ORIGINAL_DESTINATION_CONNECTION_ID, p.getOriginalDestinationConnectionId());
        }
        if (p.getInitialSourceConnectionId() != null) {
            data.putHex(QlogEvents.INITIAL_SOURCE_CONNECTION_ID, p.getInitialSourceConnectionId());
        }
        if (p.getRetrySourceConnectionId() != null) {
            data.putHex(QlogEvents.RETRY_SOURCE_CONNECTION_ID, p.getRetrySourceConnectionId());
        }
        if (p.getStatelessResetToken() != null) {
            data.putHex(QlogEvents.STATELESS_RESET_TOKEN, p.getStatelessResetToken());
        }
        data.put(QlogEvents.MAX_IDLE_TIMEOUT, p.getMaxIdleTimeout());
        data.put(QlogEvents.MAX_UDP_PAYLOAD_SIZE, p.getMaxUdpPayloadSize());
        data.put(QlogEvents.ACK_DELAY_EXPONENT, p.getAckDelayExponent());
        data.put(QlogEvents.MAX_ACK_DELAY, p.getMaxAckDelay());
        if (p.hasMinAckDelay()) {
            data.put(QlogEvents.MIN_ACK_DELAY, p.getMinAckDelay());
        }
        data.put(QlogEvents.INITIAL_MAX_DATA, p.getInitialMaxData());
        data.put(QlogEvents.INITIAL_MAX_STREAM_DATA_BIDI_LOCAL, p.getInitialMaxStreamDataBidiLocal());
        data.put(QlogEvents.INITIAL_MAX_STREAM_DATA_BIDI_REMOTE, p.getInitialMaxStreamDataBidiRemote());
        data.put(QlogEvents.INITIAL_MAX_STREAM_DATA_UNI, p.getInitialMaxStreamDataUni());
        data.put(QlogEvents.INITIAL_MAX_STREAMS_BIDI, p.getInitialMaxStreamsBidi());
        data.put(QlogEvents.INITIAL_MAX_STREAMS_UNI, p.getInitialMaxStreamsUni());
        if (p.getMaxDatagramFrameSize() > 0) {
            data.put(QlogEvents.MAX_DATAGRAM_FRAME_SIZE, p.getMaxDatagramFrameSize());
        }
        return data;
    }

    private static String qlogPacketType(EncryptionLevel level) {
        switch (level) {
            case INITIAL:
                return QlogEvents.PACKET_TYPE_INITIAL;
            case HANDSHAKE:
                return QlogEvents.PACKET_TYPE_HANDSHAKE;
            default:
                return QlogEvents.PACKET_TYPE_1RTT;
        }
    }

    private static String qlogVersion(int wire) {
        byte[] bytes = { (byte) (wire >>> 24), (byte) (wire >>> 16), (byte) (wire >>> 8), (byte) wire };
        return QlogJson.hex(bytes);
    }

    private void qlogVersionInformation(TransportParameters peer) {
        QlogJson data = QlogJson.object();
        String ours = isServer ? QlogEvents.SERVER_VERSIONS : QlogEvents.CLIENT_VERSIONS;
        String theirs = isServer ? QlogEvents.CLIENT_VERSIONS : QlogEvents.SERVER_VERSIONS;
        QuicVersion[] supported = engine.getSupportedVersions();
        data.beginArray(ours);
        for (int i = 0; i < supported.length; i++) {
            data.item(qlogVersion(supported[i].getWireValue()));
        }
        data.endArray();
        if (peer.hasVersionInformation() && peer.getVersionInformationAvailable() != null) {
            int[] available = peer.getVersionInformationAvailable();
            data.beginArray(theirs);
            for (int i = 0; i < available.length; i++) {
                data.item(qlogVersion(available[i]));
            }
            data.endArray();
        }
        data.put(QlogEvents.CHOSEN_VERSION, qlogVersion(version.getWireValue()));
        qlog.emit(QlogEvents.VERSION_INFORMATION, nowNanos(), data);
    }

    private void qlogKeysDiscarded(EncryptionLevel level) {
        String name;
        if (level == EncryptionLevel.INITIAL) {
            name = "initial";
        } else if (level == EncryptionLevel.HANDSHAKE) {
            name = "handshake";
        } else {
            return;
        }
        qlog.emit(QlogEvents.KEY_DISCARDED, nowNanos(),
                QlogJson.object().put(QlogEvents.KEY_TYPE, "client_" + name + "_secret"));
        qlog.emit(QlogEvents.KEY_DISCARDED, nowNanos(),
                QlogJson.object().put(QlogEvents.KEY_TYPE, "server_" + name + "_secret"));
    }

    // Reports the recovery state when it differs from what was last reported.
    private void qlogRecoveryMetrics(boolean lost) {
        qlogCongestionState(lost);
        RttEstimator rtt = lossDetector.getRttEstimator();
        CongestionController cc = lossDetector.getCongestionController();
        long ssthresh = cc.getSsthresh();
        long[] now = { rtt.getMinRtt(), rtt.getSmoothedRtt(), rtt.getLatestRtt(), rtt.getRttVar(),
                cc.getCongestionWindow(), cc.getBytesInFlight(), ssthresh };
        if (qlogLastMetrics != null && Arrays.equals(qlogLastMetrics, now)) {
            return;
        }
        qlogLastMetrics = now;
        QlogJson data = QlogJson.object();
        if (rtt.hasRttSample()) {
            data.putMillis(QlogEvents.MIN_RTT, now[0]);
            data.putMillis(QlogEvents.SMOOTHED_RTT, now[1]);
            data.putMillis(QlogEvents.LATEST_RTT, now[2]);
            data.putMillis(QlogEvents.RTT_VARIANCE, now[3]);
        }
        data.put(QlogEvents.CONGESTION_WINDOW, now[4]);
        data.put(QlogEvents.BYTES_IN_FLIGHT, now[5]);
        if (ssthresh < Long.MAX_VALUE / 2) {
            data.put(QlogEvents.SSTHRESH, ssthresh);
        }
        qlog.emit(QlogEvents.RECOVERY_METRICS_UPDATED, nowNanos(), data);
    }

    private void qlogPacketLost(EncryptionLevel level, long packetNumber, String trigger) {
        QlogJson data = QlogJson.object();
        data.beginObject(QlogEvents.HEADER);
        data.put(QlogEvents.PACKET_TYPE, qlogPacketType(level));
        data.put(QlogEvents.PACKET_NUMBER, packetNumber);
        data.endObject();
        if (trigger != null) {
            data.put(QlogEvents.TRIGGER, trigger);
        }
        qlog.emit(QlogEvents.PACKET_LOST, nowNanos(), data);
    }

    private void qlogPacketSent(String packetType, long packetNumber, int length, boolean longHeader,
            QuicVersion headerVersion, byte[] dcid, QlogFrames frames) {
        QlogJson data = QlogJson.object();
        data.beginObject(QlogEvents.HEADER);
        data.put(QlogEvents.PACKET_TYPE, packetType);
        data.put(QlogEvents.PACKET_NUMBER, packetNumber);
        if (longHeader) {
            data.put(QlogEvents.VERSION, qlogVersion(headerVersion.getWireValue()));
            data.putHex(QlogEvents.SCID, ourConnectionId);
        }
        data.putHex(QlogEvents.DCID, dcid);
        data.endObject();
        data.beginObject(QlogEvents.RAW).put(QlogEvents.LENGTH, length).endObject();
        data.putRaw(QlogEvents.FRAMES, frames.array());
        qlog.emit(QlogEvents.PACKET_SENT, nowNanos(), data);
    }

    private void qlogPacketReceived(String packetType, long packetNumber, byte[] packet, boolean longHeader,
            QlogFrames frames) {
        QlogJson data = QlogJson.object();
        data.beginObject(QlogEvents.HEADER);
        data.put(QlogEvents.PACKET_TYPE, packetType);
        data.put(QlogEvents.PACKET_NUMBER, packetNumber);
        qlogHeaderIds(data, packet, longHeader);
        data.endObject();
        data.beginObject(QlogEvents.RAW).put(QlogEvents.LENGTH, packet.length).endObject();
        data.putRaw(QlogEvents.FRAMES, frames.array());
        qlog.emit(QlogEvents.PACKET_RECEIVED, nowNanos(), data);
    }

    // The version and connection IDs of a received packet's header, which
    // header protection leaves in the clear.
    private void qlogHeaderIds(QlogJson data, byte[] packet, boolean longHeader) {
        if (!longHeader) {
            if (packet.length >= 1 + ourConnectionId.length) {
                data.putHex(QlogEvents.DCID, Arrays.copyOfRange(packet, 1, 1 + ourConnectionId.length));
            }
            return;
        }
        if (packet.length < 7) {
            return;
        }
        int wire = ((packet[1] & 0xff) << 24) | ((packet[2] & 0xff) << 16) | ((packet[3] & 0xff) << 8)
                | (packet[4] & 0xff);
        data.put(QlogEvents.VERSION, qlogVersion(wire));
        int dcidLength = packet[5] & 0xff;
        int scidLengthAt = 6 + dcidLength;
        if (packet.length <= scidLengthAt) {
            return;
        }
        int scidLength = packet[scidLengthAt] & 0xff;
        if (packet.length < scidLengthAt + 1 + scidLength) {
            return;
        }
        data.putHex(QlogEvents.DCID, Arrays.copyOfRange(packet, 6, 6 + dcidLength));
        data.putHex(QlogEvents.SCID, Arrays.copyOfRange(packet, scidLengthAt + 1, scidLengthAt + 1 + scidLength));
    }

    private void qlogDroppedUnparseable(int length) {
        if (qlog != null) {
            qlogPacketDropped(null, length, QlogEvents.TRIGGER_HEADER_PARSE_ERROR);
        }
    }

    private void qlogPacketDropped(String packetType, int length, String trigger) {
        QlogJson data = QlogJson.object();
        if (packetType != null) {
            data.beginObject(QlogEvents.HEADER).put(QlogEvents.PACKET_TYPE, packetType).endObject();
        }
        data.beginObject(QlogEvents.RAW).put(QlogEvents.LENGTH, length).endObject();
        data.put(QlogEvents.TRIGGER, trigger);
        qlog.emit(QlogEvents.PACKET_DROPPED, nowNanos(), data);
    }

    private void qlogRecoveryParameters() {
        CongestionController cc = lossDetector.getCongestionController();
        QlogJson data = QlogJson.object();
        data.put(QlogEvents.REORDERING_THRESHOLD, LossDetector.K_PACKET_THRESHOLD);
        data.put(QlogEvents.TIME_THRESHOLD, LossDetector.K_TIME_THRESHOLD);
        data.putMillis(QlogEvents.TIMER_GRANULARITY, LossDetector.K_GRANULARITY);
        data.putMillis(QlogEvents.INITIAL_RTT, RttEstimator.K_INITIAL_RTT);
        data.put(QlogEvents.MAX_DATAGRAM_SIZE, MIN_DATAGRAM_SIZE);
        data.put(QlogEvents.INITIAL_CONGESTION_WINDOW, cc.getCongestionWindow());
        data.put(QlogEvents.MINIMUM_CONGESTION_WINDOW, 2L * MIN_DATAGRAM_SIZE);
        data.put(QlogEvents.LOSS_REDUCTION_FACTOR, CongestionController.K_LOSS_REDUCTION_FACTOR);
        data.put(QlogEvents.PERSISTENT_CONGESTION_THRESHOLD, LossDetector.K_PERSISTENT_CONGESTION_THRESHOLD);
        qlog.emit(QlogEvents.RECOVERY_PARAMETERS_SET, nowNanos(), data);
    }

    private void qlogKeyUpdated(boolean clientSecret, String level, long generation, String trigger) {
        QlogJson data = QlogJson.object();
        data.put(QlogEvents.KEY_TYPE, (clientSecret ? "client_" : "server_") + level + "_secret");
        data.put(QlogEvents.GENERATION, generation);
        data.put(QlogEvents.TRIGGER, trigger);
        qlog.emit(QlogEvents.KEY_UPDATED, nowNanos(), data);
    }

    private void qlogConnectionIdUpdated(String owner, byte[] old, byte[] updated) {
        QlogJson data = QlogJson.object();
        data.put(QlogEvents.OWNER, owner);
        if (old != null) {
            data.putHex(QlogEvents.OLD, old);
        }
        data.putHex(QlogEvents.NEW, updated);
        qlog.emit(QlogEvents.CONNECTION_ID_UPDATED, nowNanos(), data);
    }

    private static void qlogEndpoint(QlogJson data, String key, InetSocketAddress address) {
        if (address == null || address.getAddress() == null) {
            return;
        }
        data.beginObject(key);
        if (address.getAddress() instanceof java.net.Inet6Address) {
            data.put(QlogEvents.IP_V6, address.getAddress().getHostAddress());
            data.put(QlogEvents.PORT_V6, address.getPort());
        } else {
            data.put(QlogEvents.IP_V4, address.getAddress().getHostAddress());
            data.put(QlogEvents.PORT_V4, address.getPort());
        }
        data.endObject();
    }

    private void qlogTupleAssigned() {
        QlogJson data = QlogJson.object();
        data.put(QlogEvents.TUPLE_ID, "tuple-" + (qlogTuples++));
        qlogEndpoint(data, QlogEvents.TUPLE_REMOTE, remoteAddress);
        qlogEndpoint(data, QlogEvents.TUPLE_LOCAL, localAddress);
        qlog.emit(QlogEvents.TUPLE_ASSIGNED, nowNanos(), data);
    }

    private void qlogStreamState(long streamId, String state) {
        QlogJson data = QlogJson.object();
        data.put(QlogEvents.STREAM_ID, streamId);
        data.put(QlogEvents.STREAM_TYPE, isUnidirectional(streamId) ? "unidirectional" : "bidirectional");
        data.put(QlogEvents.NEW, state);
        qlog.emit(QlogEvents.STREAM_STATE_UPDATED, nowNanos(), data);
    }

    // A stream's data (streamId >= 0) or a datagram's moving between the
    // application, the transport and the network.
    private void qlogDataMoved(String event, long streamId, long offset, int length, String from, String to) {
        QlogJson data = QlogJson.object();
        if (streamId >= 0) {
            data.put(QlogEvents.STREAM_ID, streamId);
            data.put(QlogEvents.OFFSET, offset);
        }
        data.put(QlogEvents.LENGTH, length);
        data.put(QlogEvents.FROM, from);
        data.put(QlogEvents.TO, to);
        qlog.emit(event, nowNanos(), data);
    }

    private void qlogCongestionState(boolean lost) {
        CongestionController cc = lossDetector.getCongestionController();
        String state = lost ? QlogEvents.STATE_RECOVERY
                : cc.getCongestionWindow() < cc.getSsthresh()
                        ? QlogEvents.STATE_SLOW_START : QlogEvents.STATE_CONGESTION_AVOIDANCE;
        if (state.equals(qlogCongestionState)) {
            return;
        }
        QlogJson data = QlogJson.object();
        if (qlogCongestionState != null) {
            data.put(QlogEvents.OLD, qlogCongestionState);
        }
        data.put(QlogEvents.NEW, state);
        qlogCongestionState = state;
        qlog.emit(QlogEvents.CONGESTION_STATE_UPDATED, nowNanos(), data);
    }

    // Reported once, whichever way the connection ends.
    private void qlogConnectionClosed(String owner, String trigger) {
        if (qlog == null || qlogClosedLogged) {
            return;
        }
        qlogClosedLogged = true;
        QlogJson data = QlogJson.object();
        data.put(QlogEvents.OWNER, owner);
        if (deferredCloseIsError) {
            data.put(deferredCloseApplicationError ? QlogEvents.APPLICATION_CODE : QlogEvents.CONNECTION_CODE,
                    deferredCloseErrorCode);
            if (deferredCloseReason != null && !deferredCloseReason.isEmpty()) {
                data.put(QlogEvents.REASON, deferredCloseReason);
            }
        }
        if (trigger == null) {
            trigger = !deferredCloseIsError ? QlogEvents.TRIGGER_CLEAN
                    : deferredCloseApplicationError ? QlogEvents.TRIGGER_APPLICATION : QlogEvents.TRIGGER_ERROR;
        }
        data.put(QlogEvents.TRIGGER, trigger);
        qlog.emit(QlogEvents.CONNECTION_CLOSED, nowNanos(), data);
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

    /**
     * Test-only: runs {@code action} on this connection's loop thread
     * once loss detection has no timer armed ({@code timerHandle ==
     * null}), after a {@link #flush()} to settle in-flight work.
     */
    public void runWhenLossDetectionIdle(final Runnable action) {
        getSelectorLoop().invokeLater(new Runnable() {
            @Override
            public void run() {
                waitForLossDetectionIdle(action);
            }
        });
    }

    private void waitForLossDetectionIdle(final Runnable action) {
        flush();
        if (timerHandle == null) {
            action.run();
            return;
        }
        final Runnable previous = lossDetectionIdleObserver;
        lossDetectionIdleObserver = new Runnable() {
            @Override
            public void run() {
                lossDetectionIdleObserver = previous;
                if (timerHandle == null) {
                    action.run();
                } else {
                    waitForLossDetectionIdle(action);
                }
            }
        };
    }

    /**
     * Test-only: runs {@code action} on this connection's loop thread
     * once 1-RTT send keys exist, or immediately if they already do.
     */
    public void runWhenOneRttSendKeysReady(final Runnable action) {
        getSelectorLoop().invokeLater(new Runnable() {
            @Override
            public void run() {
                if (sendKeys.get(EncryptionLevel.ONE_RTT) != null) {
                    action.run();
                    return;
                }
                final Runnable previous = oneRttKeysReadyObserver;
                oneRttKeysReadyObserver = new Runnable() {
                    @Override
                    public void run() {
                        oneRttKeysReadyObserver = previous;
                        action.run();
                    }
                };
            }
        });
    }

    byte[] getOurConnectionId() {
        return ourConnectionId;
    }

    public SocketAddress getLocalAddress() {
        return sendPath != null ? sendPath.getLocalAddress() : localAddress;
    }

    /** The path this connection sends on, or null for the engine's primary path. */
    QuicDatagramPath getSendPath() {
        return sendPath;
    }

    /** The connection ID this endpoint currently puts in packets to its peer. */
    byte[] getPeerConnectionId() {
        return peerConnectionId == null ? null : peerConnectionId.clone();
    }

    /** The peer's transport parameters, once received. */
    TransportParameters getPeerTransportParameters() {
        return peerTransportParameters;
    }

    /**
     * Server: mints the connection ID conveyed in {@code preferred_address}
     * (RFC 9000 section 5.1.1, sequence number 1). Must be called before
     * the handshake issues any other connection ID.
     */
    ConnectionIdEntry mintPreferredAddressConnectionId() {
        return connectionIdManager.mintPreferredAddressConnectionId();
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
        if (qlog != null) {
            qlogDataMoved(QlogEvents.DATAGRAM_DATA_MOVED, -1, -1, copy.length,
                    QlogEvents.LOCATION_APPLICATION, QlogEvents.LOCATION_TRANSPORT);
        }
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
        if (qlog != null) {
            qlog.emit(QlogEvents.PARAMETERS_RESTORED, nowNanos(), qlogParameters(null, remembered));
        }
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
        if (qlog != null) {
            qlogStreamState(streamId, QlogEvents.STREAM_OPEN);
        }
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
        boolean unidirectional = isUnidirectional(streamId);
        if (!isPeerInitiated(streamId)) {
            // A stream of this endpoint's own that is no longer tracked. If
            // it was opened, it has finished, and this is a late or
            // retransmitted frame for it (RFC 9000 section 3): ignored. It
            // is not the peer opening a stream. If it was never opened, the
            // peer has no business sending on it (section 19.8).
            long next = unidirectional ? nextLocalUniStreamId : nextLocalBidiStreamId;
            if (streamId >= next) {
                closeWithError(TRANSPORT_ERROR_STREAM_STATE_ERROR,
                        "STREAM frame for a stream this endpoint has not opened");
            }
            return null;
        }
        if (peerInitiatedStreamExceedsLimit(streamId)) {
            closeWithError(TRANSPORT_ERROR_STREAM_LIMIT_ERROR,
                    "peer opened a stream beyond the advertised MAX_STREAMS limit");
            return null;
        }
        // RFC 9000 section 3.2: a peer's stream opens every lower-numbered
        // stream of its type with it. Those not yet seen are remembered, so
        // that one whose first frame arrives late is still accepted, while
        // a stream that has been seen and has finished is not accepted a
        // second time: delivering it again would hand the application the
        // same request twice.
        long nextPeer = unidirectional ? nextPeerUniStreamId : nextPeerBidiStreamId;
        if (streamId < nextPeer) {
            if (!peerStreamsNotYetSeen.remove(Long.valueOf(streamId))) {
                return null;
            }
        } else {
            for (long id = nextPeer; id < streamId; id += 4) {
                peerStreamsNotYetSeen.add(Long.valueOf(id));
            }
            if (unidirectional) {
                nextPeerUniStreamId = streamId + 4;
            } else {
                nextPeerBidiStreamId = streamId + 4;
            }
        }
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
        if (qlog != null) {
            qlogStreamState(streamId, QlogEvents.STREAM_OPEN);
        }
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
        Long key = Long.valueOf(streamId);
        List<PendingChunk> chunks = pendingStream.get(key);
        if (chunks == null) {
            chunks = new ArrayList<PendingChunk>();
            addPendingStreamChunks(key, chunks);
        }
        chunks.add(new PendingChunk(offset, copy, fin));
        if (qlog != null) {
            if (copy.length > 0) {
                qlogDataMoved(QlogEvents.STREAM_DATA_MOVED, streamId, offset, copy.length,
                        QlogEvents.LOCATION_APPLICATION, QlogEvents.LOCATION_TRANSPORT);
            }
            if (fin) {
                QuicStreamEndpoint finished = streams.get(key);
                qlogStreamState(streamId, finished != null && finished.isPeerFinished()
                        ? QlogEvents.STREAM_CLOSED : QlogEvents.STREAM_HALF_CLOSED_LOCAL);
            }
        }
        requestFlush();
    }

    // Registers a brand-new pendingStream entry (chunks must not already
    // be in pendingStream under this key) in both the map and the
    // priority-ordered index -- see pendingStreamOrder's field comment.
    private void addPendingStreamChunks(Long streamKey, List<PendingChunk> chunks) {
        pendingStream.put(streamKey, chunks);
        pendingStreamOrder.add(streamKey);
    }

    // Drops a pendingStream entry (once fully sent, reset, or otherwise
    // discarded) from both the map and the priority-ordered index.
    private void removePendingStream(Long streamKey) {
        pendingStream.remove(streamKey);
        pendingStreamOrder.remove(streamKey);
    }

    /**
     * Sets the send priority for a stream when multiplexing STREAM frames
     * (RFC 9218: higher values are sent sooner). Default is 0.
     *
     * @param streamId the stream
     * @param priority higher means sooner
     */
    public void setStreamSendPriority(long streamId, int priority) {
        Long key = Long.valueOf(streamId);
        // pendingStreamOrder's comparator reads streamSendPriority, so a
        // stream already pending must be pulled out before its priority
        // changes underneath it and reinserted after -- otherwise its
        // position in the tree would no longer match what the
        // (now-changed) comparator says, corrupting the ordering
        // invariant for every later lookup, not just this one entry.
        boolean wasPending = pendingStreamOrder.remove(key);
        streamSendPriority.put(key, Integer.valueOf(priority));
        if (wasPending) {
            pendingStreamOrder.add(key);
        }
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
        removePendingStream(Long.valueOf(streamId));
        requestFlush();
    }

    // Set for the duration of receive() so every side effect of
    // processing one incoming datagram (potentially several coalesced
    // packets, each with several frames, each of which can itself
    // trigger further synchronous callbacks -- e.g. the TLS engine delivering a
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
            // Deliberately NOT touching pendingStream/pendingStreamOrder
            // here: isFullyClosed() (both directions logically closed)
            // can be true before this stream's queued data has actually
            // been flushed -- e.g. close() queues its FIN chunk and
            // immediately calls this while still inside receive()'s
            // suppressFlush window, well before that data is eligible to
            // be sent. Removing the entry from pendingStreamOrder here
            // (as this once did) orphaned it: pendingStream still held
            // the unsent data, drainEligibleStreamChunks no longer knew
            // to look for it, and the data was silently never sent --
            // the response a DoQ query was waiting on, for one. The real
            // removal happens once the data is actually drained, in
            // buildProtectedPacket/buildZeroRttPacketOrNull's own
            // post-send cleanup (removePendingStream), or in
            // resetStream.
        }
    }

    // The peer finishing their send direction must not stop this side
    // from still sending its own response on the same (bidirectional)
    // stream -- see QuicStreamEndpoint's markPeerFinished javadoc.
    private void completeStreamFin(final long streamId, final QuicStreamEndpoint stream) {
        // The FIN is delivered after any data the handler has paused
        // reading (or left unconsumed) so it can never overtake that data.
        stream.afterDelivery(new Runnable() {
            @Override
            public void run() {
                stream.markPeerFinished();
                if (qlog != null) {
                    qlogStreamState(streamId, stream.isLocalFinished()
                            ? QlogEvents.STREAM_CLOSED : QlogEvents.STREAM_HALF_CLOSED_REMOTE);
                }
                stream.getHandler().readFinished();
                retireStreamIfFullyClosed(streamId, stream);
            }
        });
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
        receive(datagram, source, null);
    }

    /**
     * Processes one datagram.
     *
     * @param datagram the datagram
     * @param source the peer address it came from
     * @param via the local path it arrived on, or null for the engine's primary
     */
    void receive(ByteBuffer datagram, InetSocketAddress source, QuicDatagramPath via) {
        byte[] bytes = new byte[datagram.remaining()];
        datagram.get(bytes);
        if (isServer && !addressValidated) {
            amplificationBytesReceived += bytes.length;
        }
        int offset = 0;
        suppressFlush = true;
        currentDatagramSource = source;
        currentDatagramPath = via != null ? via : engine.getPrimaryPath();
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
        PathKey arrival = new PathKey(currentDatagramPath, source);
        if (sawValidOneRttThisReceive && established && !arrival.equals(currentPath())
                && !pathValidationAttempts.containsKey(arrival)
                && !isRecentlyMigratedFrom(source)) {
            beginMigrationValidation(arrival, null, 0);
        }
        scheduleAcks();
        requestFlush();
    }

    /**
     * Returns the QUIC version this connection is using. On a client this
     * can still change while the handshake is in progress, when the server
     * selects another version (RFC 9368 section 2.3).
     *
     * @return the version
     */
    public QuicVersion getVersion() {
        return version;
    }

    // Client: whether a server packet of `candidate` may be taken as the
    // result of compatible version negotiation (RFC 9368 section 2.3).
    private boolean canAdoptVersion(QuicVersion candidate) {
        if (candidate == null || version != initialVersion || !initialVersion.isCompatibleWith(candidate)) {
            return false;
        }
        QuicVersion[] configured = engine.getSupportedVersions();
        for (int i = 0; i < configured.length; i++) {
            if (configured[i] == candidate) {
                return true;
            }
        }
        return false;
    }

    // Switches the connection to another version: the Initial keys are
    // re-derived from the same connection ID with the new version's salt
    // and labels (Handshake and 1-RTT keys are derived later, under the
    // new version), and the initial version's receive keys are kept until
    // a Handshake packet arrives (RFC 9369 section 4.1). `initialKeys`
    // are the {client, server} Initial keys of the new version.
    private void adoptVersion(QuicVersion newVersion, PacketProtectionKeys[] initialKeys) {
        initialVersionRecvKeys = recvKeys.get(EncryptionLevel.INITIAL);
        version = newVersion;
        sendKeys.put(EncryptionLevel.INITIAL, isServer ? initialKeys[1] : initialKeys[0]);
        recvKeys.put(EncryptionLevel.INITIAL, isServer ? initialKeys[0] : initialKeys[1]);
    }

    // Derives the Initial protection keys (RFC 9001 section 5.2, RFC 9369
    // section 3.3.1) for both directions: {client, server}.
    private static PacketProtectionKeys[] deriveInitialKeys(QuicVersion version, byte[] dcid) {
        Hkdf hkdf = Hkdf.sha256();
        return new PacketProtectionKeys[] {
            PacketProtectionKeys.derive(hkdf, InitialSecrets.clientSecret(version, dcid),
                    QuicAeadAlgorithm.AES_128_GCM, version),
            PacketProtectionKeys.derive(hkdf, InitialSecrets.serverSecret(version, dcid),
                    QuicAeadAlgorithm.AES_128_GCM, version)
        };
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
        QuicVersion packetQuicVersion = null;
        if (longHeader) {
            // A Version Negotiation packet (version field zero, RFC 9000
            // section 17.2.1) has none of the Initial/Handshake/0-RTT
            // fields and always spans its whole datagram.
            if (bytes.length - offset >= 5 && (bytes[offset + 1] | bytes[offset + 2]
                    | bytes[offset + 3] | bytes[offset + 4]) == 0) {
                handleVersionNegotiation(offset == 0 ? bytes : Arrays.copyOfRange(bytes, offset, bytes.length));
                return bytes.length - offset;
            }
            // A Retry packet has no Length field (RFC 9000 section
            // 17.2.5) -- a genuinely different shape from
            // Initial/Handshake/0-RTT -- so its type must be checked
            // before calling parsePrefix, which assumes that field exists.
            // Read directly off bytes[offset]: unlike parsePrefix, this
            // doesn't need an offset-0 view.
            if (bytes.length - offset < 5) {
                decryptFailedOrUnparseableThisDatagram = true;
                qlogDroppedUnparseable(bytes.length - offset);
                return -1;
            }
            int packetVersion = ((bytes[offset + 1] & 0xff) << 24) | ((bytes[offset + 2] & 0xff) << 16)
                    | ((bytes[offset + 3] & 0xff) << 8) | (bytes[offset + 4] & 0xff);
            packetQuicVersion = QuicVersion.fromWireValue(packetVersion);
            if (packetQuicVersion == null) {
                decryptFailedOrUnparseableThisDatagram = true;
                qlogDroppedUnparseable(bytes.length - offset);
                return -1;
            }
            int packetType = LongHeaderCodec.packetType(packetVersion, bytes[offset]);
            if (packetType == LongHeaderCodec.TYPE_RETRY) {
                // RFC 9369 section 4.1: a Retry is always in the original
                // version; the client ignores one of any other.
                if (packetQuicVersion != initialVersion) {
                    return bytes.length - offset;
                }
                // Rare (at most once per connection) and, per RFC 9000
                // section 12.2, a Retry is never coalesced with anything
                // else -- offset is always 0 here in practice, but the
                // slice stays for the theoretical case it isn't.
                byte[] fromOffset = offset == 0 ? bytes : Arrays.copyOfRange(bytes, offset, bytes.length);
                handleRetryPacket(fromOffset);
                return bytes.length - offset; // a Retry packet always spans the rest of its datagram
            }
            LongHeaderPrefix prefix;
            try {
                // Parses directly out of bytes at offset -- every packet
                // after the first in a coalesced datagram (routine
                // during the handshake, RFC 9000 section 12.2) used to
                // pay for a full Arrays.copyOfRange just to get an
                // offset-0 view here.
                prefix = LongHeaderCodec.parsePrefix(bytes, offset);
            } catch (IllegalArgumentException e) {
                decryptFailedOrUnparseableThisDatagram = true;
                qlogDroppedUnparseable(bytes.length - offset);
                return -1;
            }
            isZeroRtt = prefix.getPacketType() == LongHeaderCodec.TYPE_0RTT;
            level = isZeroRtt ? EncryptionLevel.ONE_RTT
                    : prefix.getPacketType() == LongHeaderCodec.TYPE_INITIAL ? EncryptionLevel.INITIAL
                    : EncryptionLevel.HANDSHAKE;
            // parsePrefix(bytes, offset) returns an absolute packet-number
            // offset into bytes; pnOffset/packetLength below are relative
            // to this packet's own start (offset), matching packet's own
            // indexing once it's sliced out below.
            pnOffset = prefix.getPacketNumberOffset() - offset;
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
            qlogDroppedUnparseable(bytes.length - offset);
            return -1;
        }
        byte[] packet = new byte[packetLength];
        System.arraycopy(bytes, offset, packet, 0, packetLength);
        processPacket(level, packet, pnOffset, isZeroRtt, packetQuicVersion);
        return packetLength;
    }

    // Client-only: the server's connection ID isn't known until its
    // first long-header response arrives (see the class documentation
    // on why this never changes again after that).
    private void learnPeerConnectionId(byte[] scid) {
        if (qlog != null) {
            qlogConnectionIdUpdated(QlogEvents.OWNER_REMOTE, peerConnectionId, scid);
        }
        peerConnectionId = scid;
        peerConnectionIdLearned = true;
    }

    // Client-only: handles a received Version Negotiation packet (RFC 9000
    // section 6.2). If it lists a version this endpoint also supports
    // (other than the one in use), the attempt restarts in that version,
    // taking the engine's preference order; otherwise the attempt ends.
    // Discarded outright on a server, once any other packet from the
    // server has been processed (including a Retry) or the handshake has
    // completed, if it does not echo both connection IDs of this attempt,
    // or if it lists the version already in use (a server never
    // negotiates towards a version the client is using).
    private void handleVersionNegotiation(byte[] packet) {
        if (isServer || closed || established || retryProcessed || afterVersionNegotiation
                || largestReceived[0] >= 0 || largestReceived[1] >= 0 || largestReceived[2] >= 0) {
            return;
        }
        VersionNegotiationPacket vn;
        try {
            vn = VersionNegotiationPacket.parse(packet);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!Arrays.equals(vn.getDestinationConnectionId(), ourConnectionId)
                || !Arrays.equals(vn.getSourceConnectionId(), originalDcid)) {
            return;
        }
        int[] offered = vn.getSupportedVersions();
        for (int i = 0; i < offered.length; i++) {
            if (offered[i] == initialVersion.getWireValue()) {
                return;
            }
        }
        // RFC 9368 section 2.1: retry in the most preferred mutually
        // supported version. 0-RTT data already handed to this connection
        // could not move to the new attempt, so that case fails instead.
        QuicVersion next = QuicVersion.selectFromOffer(engine.getSupportedVersions(), offered);
        if (next != null && zeroRttState == ZeroRttState.NONE) {
            abandonForRestart();
            engine.restartClientAttempt(this, next);
            return;
        }
        closed = true;
        deferredCloseIsError = true;
        qlogConnectionClosed(QlogEvents.OWNER_LOCAL, QlogEvents.TRIGGER_VERSION_MISMATCH);
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
        }
        cancelAllPathValidationAttempts();
        QuicVersionNegotiationException failure = new QuicVersionNegotiationException();
        tearDownStreams(failure);
        // The handler for the not-yet-opened first stream would otherwise
        // never hear that its connection attempt failed.
        ProtocolHandler waiting = clientHandler;
        clientHandler = null;
        if (waiting != null) {
            waiting.error(failure);
        }
        engine.onConnectionClosed(this);
    }

    // Client-only: gives up this attempt without telling its handlers, whose
    // ownership passes to the new attempt the engine starts after Version
    // Negotiation (RFC 9368 section 2.4: a new connection, which needs a
    // fresh ClientHello carrying the new Chosen Version).
    private void abandonForRestart() {
        closed = true;
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
        }
        cancelAllPathValidationAttempts();
        engine.onConnectionClosed(this);
    }

    InetSocketAddress getRemoteSocketAddress() {
        return remoteAddress;
    }

    String getServerName() {
        return serverName;
    }

    ProtocolHandler getClientHandler() {
        return clientHandler;
    }

    QuicEngine.ConnectionAcceptedHandler getClientConnectionAcceptedHandler() {
        return clientConnectionAcceptedHandler;
    }

    QuicEngine.EarlyDataHandler getEarlyDataHandler() {
        return earlyDataHandler;
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
        if (!RetryIntegrityTag.verify(version, originalDcid, retry.getPacketWithoutTag(), retry.getTag())) {
            return; // corrupted, or forged by an off-path attacker without the fixed key
        }

        retryProcessed = true;
        retryToken = retry.getRetryToken();
        expectedRetrySourceConnectionId = retry.getSourceConnectionId();
        if (qlog != null) {
            qlogConnectionIdUpdated(QlogEvents.OWNER_REMOTE, peerConnectionId, retry.getSourceConnectionId());
        }
        peerConnectionId = retry.getSourceConnectionId();
        peerConnectionIdLearned = true;

        // RFC 9001 section 5.2: Initial secrets are derived from the
        // Destination Connection ID the client addresses the server with.
        // After a Retry, that DCID changes to the Retry packet's own
        // Source Connection ID, so Initial keys must be re-derived to match.
        initialKeyDcid = peerConnectionId;
        PacketProtectionKeys[] initialKeys = deriveInitialKeys(version, initialKeyDcid);
        sendKeys.put(EncryptionLevel.INITIAL, initialKeys[0]);
        recvKeys.put(EncryptionLevel.INITIAL, initialKeys[1]);

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
        if (qlog != null) {
            qlogKeysDiscarded(level);
        }
        EncryptionLevelDiscardedObserver observer = encryptionLevelDiscardedObserver;
        if (observer != null) {
            observer.encryptionLevelDiscarded(this, level);
        }
        sendKeys.remove(level);
        recvKeys.remove(level);
        sentCrypto.get(level).clear();
        ackOwed[level.ordinal()] = false;
        ackElicitingUnacked[level.ordinal()] = 0;
        ackImmediate[level.ordinal()] = false;
        receivedUnacked.remove(level);
        sentAckCoverage.remove(level);
        pendingPing[level.ordinal()] = false;
        lossDetector.discardPacketNumberSpace(level);
    }

    // packetVersion is the Version field of a long-header packet, null for
    // a short-header packet.
    private void processPacket(EncryptionLevel level, byte[] packet, int pnOffset, boolean isZeroRtt,
            QuicVersion packetVersion) {
        PacketProtectionKeys keys;
        QuicVersion switchTo = null;
        PacketProtectionKeys[] switchKeys = null;
        if (isZeroRtt) {
            // RFC 9369 section 4.1: 0-RTT is always in the original version.
            keys = packetVersion == initialVersion ? zeroRttRecvKeys : null;
        } else if (level == EncryptionLevel.INITIAL) {
            if (packetVersion == version) {
                keys = recvKeys.get(level);
            } else if (packetVersion == initialVersion) {
                keys = initialVersionRecvKeys;
            } else if (!isServer && canAdoptVersion(packetVersion)) {
                // The client learns the negotiated version from the first
                // long header of another version (RFC 9369 section 4.1);
                // adopted below only if the packet then authenticates.
                switchTo = packetVersion;
                switchKeys = deriveInitialKeys(switchTo, initialKeyDcid);
                keys = switchKeys[1];
            } else {
                keys = null;
            }
        } else if (level == EncryptionLevel.HANDSHAKE) {
            // RFC 9369 section 4.1: Handshake and 1-RTT packets use the
            // negotiated version; any other is dropped.
            keys = packetVersion == version ? recvKeys.get(level) : null;
        } else {
            keys = packetVersion == null || packetVersion == version ? recvKeys.get(level) : null;
        }
        if (keys == null) {
            if (qlog != null) {
                qlogPacketDropped(isZeroRtt ? QlogEvents.PACKET_TYPE_0RTT : qlogPacketType(level), packet.length,
                        QlogEvents.TRIGGER_KEY_UNAVAILABLE);
            }
            return; // keys not derived yet (or not accepted) at this level; drop
        }
        // 0-RTT is wire-long-header despite sharing ONE_RTT's packet
        // number/loss-detection space (see receiveOnePacket).
        boolean longHeader = level != EncryptionLevel.ONE_RTT || isZeroRtt;
        try {
            // pnOffset + 4 is the header-protection sample's fixed offset
            // relative to the (not-yet-known-length) packet-number field
            // (RFC 9001 section 5.4.2) -- reading it straight out of
            // packet, and likewise passing packet+offsets straight into
            // PacketProtection.open below, avoids copying the sample,
            // the AAD (the header, already sitting at packet[0..
            // headerLength)), and the ciphertext (already sitting at
            // packet[headerLength..]) into three dedicated arrays per
            // received packet.
            byte[] mask = PacketProtection.headerProtectionMask(keys, packet, pnOffset + 4);
            PacketProtection.xorFirstByte(packet, mask, longHeader);
            int pnLength = (packet[0] & 0x03) + 1;
            PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);

            long truncatedPn = 0;
            for (int i = 0; i < pnLength; i++) {
                truncatedPn = (truncatedPn << 8) | (packet[pnOffset + i] & 0xff);
            }
            long fullPacketNumber = PacketNumberCodec.decode(largestReceived[level.ordinal()], truncatedPn, pnLength);

            // RFC 9001 section 6: a 1-RTT packet's Key Phase bit (now
            // unprotected) says which generation of keys opens it. Header
            // protection is the same for every generation, so the mask
            // above was right whichever these turn out to be.
            boolean[] updatedRecvKeys = new boolean[1];
            if (level == EncryptionLevel.ONE_RTT && !isZeroRtt) {
                keys = selectOneRttRecvKeys((packet[0] & 0x04) != 0, fullPacketNumber, updatedRecvKeys);
                if (keys == null) {
                    decryptFailedOrUnparseableThisDatagram = true;
                    if (qlog != null) {
                        qlogPacketDropped(QlogEvents.PACKET_TYPE_1RTT, packet.length,
                                QlogEvents.TRIGGER_KEY_UNAVAILABLE);
                    }
                    return;
                }
            }

            int headerLength = pnOffset + pnLength;
            byte[] plaintext = PacketProtection.open(keys, fullPacketNumber,
                    packet, 0, headerLength, packet, headerLength, packet.length - headerLength);

            if (updatedRecvKeys[0]) {
                completeRecvKeyUpdate(fullPacketNumber);
            } else if (keys == previousRecvKeys) {
                previousPhasePacketsRead++;
            } else if (level == EncryptionLevel.ONE_RTT && !isZeroRtt && keys == recvKeys.get(EncryptionLevel.ONE_RTT)
                    && (lowestPnOfCurrentRecvPhase < 0 || fullPacketNumber < lowestPnOfCurrentRecvPhase)) {
                lowestPnOfCurrentRecvPhase = fullPacketNumber;
            }

            if (switchTo != null) {
                adoptVersion(switchTo, switchKeys);
            }
            long previousLargest = largestReceived[level.ordinal()];
            if (fullPacketNumber > previousLargest) {
                largestReceived[level.ordinal()] = fullPacketNumber;
                largestReceivedTime[level.ordinal()] = nowNanos();
            }
            if (level == EncryptionLevel.HANDSHAKE) {
                initialVersionRecvKeys = null;
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
            if (metrics != null) {
                metrics.packetReceived(packet.length);
            }
            if (qlog != null) {
                qlogPacketReceived(isZeroRtt ? QlogEvents.PACKET_TYPE_0RTT : qlogPacketType(level), fullPacketNumber,
                        packet, longHeader, dispatcher.qlogFrames);
            }
            // RFC 9000 section 13.2: only owe an ACK if this packet
            // carried at least one ack-eliciting frame -- acknowledging a
            // packet that itself contained nothing but ACK/PADDING/
            // CONNECTION_CLOSE would let two endpoints that just did that
            // to each other keep acking one another's ACKs forever.
            if (dispatcher.ackEliciting) {
                int space = level.ordinal();
                if (ackElicitingUnacked[space]++ == 0) {
                    firstUnackedNanos[space] = nowNanos();
                }
                // RFC 9000 section 13.2.1: a packet that arrives out of
                // order, below the largest or after a gap, is
                // acknowledged without waiting
                if (previousLargest >= 0 && reorderingCallsForAck(fullPacketNumber, previousLargest)) {
                    ackImmediate[space] = true;
                }
            }
            // Every packet received is named in the next ACK frame this
            // endpoint sends, whether or not it is one that makes an ACK
            // owed (RFC 9000 section 13.2.1: packets that are not
            // ack-eliciting are acknowledged when an ACK frame is sent for
            // other reasons). The peer learns from that which of its
            // ACK-only packets arrived, and so which of this endpoint's
            // packets it may stop acknowledging; if they were left out, a
            // packet the peer had acknowledged only in such a packet would
            // be acknowledged by it for the rest of the connection.
            TreeSet<Long> unacked = receivedUnacked.get(level);
            if (unacked == null) {
                unacked = new TreeSet<Long>();
                receivedUnacked.put(level, unacked);
            }
            unacked.add(Long.valueOf(fullPacketNumber));
        } catch (PacketProtectionException e) {
            if (qlog != null) {
                qlogPacketDropped(isZeroRtt ? QlogEvents.PACKET_TYPE_0RTT : qlogPacketType(level), packet.length,
                        QlogEvents.TRIGGER_DECRYPTION_FAILURE);
            }
            LOGGER.log(Level.FINE, MessageFormat.format(
                    L10N.getString("fine.packet_protection_failure"), level), e);
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

        // the frames of this packet, for the qlog packet_received event; null when not logging
        final QlogFrames qlogFrames;

        FrameDispatcher(EncryptionLevel level, boolean zeroRtt) {
            this.qlogFrames = qlog != null ? new QlogFrames() : null;
            this.level = level;
            this.zeroRtt = zeroRtt;
        }

        @Override
        public void paddingFrameReceived(int length) {
            if (qlogFrames != null) {
                qlogFrames.padding(length);
            }
        }

        @Override
        public void pingFrameReceived() {
            if (qlogFrames != null) {
                qlogFrames.ping();
            }
            ackEliciting = true;
        }

        @Override
        public void ackFrameReceived(long largestAcknowledged, long ackDelay, long[][] ranges) {
            if (qlogFrames != null) {
                qlogFrames.ack(ranges, ackDelayMicros(ackDelay));
            }
            if (level == EncryptionLevel.HANDSHAKE) {
                receivedHandshakeAck = true;
            }
            LossDetector.AckResult result = lossDetector.onAckReceived(level, largestAcknowledged,
                    ackDelayMicros(ackDelay), ranges, peerMaxAckDelayMicros(), nowMicros(), peerAddressValidated());
            retireAcknowledgedRanges(level, result.getNewlyAcked());
            if (level == EncryptionLevel.ONE_RTT) {
                noteOneRttAckReceived(result);
            }
            if (level == EncryptionLevel.ONE_RTT && !currentSendKeysAcknowledged) {
                for (SentPacket acked : result.getNewlyAcked()) {
                    if (acked.getPacketNumber() >= firstPnOfCurrentSendPhase) {
                        currentSendKeysAcknowledged = true;
                        break;
                    }
                }
            }
            Map<Long, long[]> coverage = sentAckCoverage.get(level);
            if (qlog != null) {
                qlogRecoveryMetrics(!result.getNewlyLost().isEmpty());
            }
            if (metrics != null) {
                metricsRecoverySample(result.getNewlyLost().size());
            }
            for (SentPacket lost : result.getNewlyLost()) {
                if (qlog != null) {
                    qlogPacketLost(level, lost.getPacketNumber(), null);
                }
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
            if (qlogFrames != null) {
                qlogFrames.resetStream(streamId, applicationErrorCode, finalSize);
            }
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
            if (qlogFrames != null) {
                qlogFrames.stopSending(streamId, applicationErrorCode);
            }
            ackEliciting = true;
            resetStream(streamId, applicationErrorCode);
        }

        @Override
        public void cryptoFrameReceived(long offset, ByteBuffer data) {
            if (qlogFrames != null) {
                qlogFrames.crypto(offset, data.remaining());
            }
            ackEliciting = true;
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            try {
                tlsEngine.receiveCryptoData(level, offset, ByteBuffer.wrap(copy));
            } catch (StreamReassembler.BufferLimitExceededException e) {
                closeWithError(TRANSPORT_ERROR_CRYPTO_BUFFER_EXCEEDED, e.getMessage());
            }
        }

        @Override
        public void newTokenFrameReceived(ByteBuffer token) {
            if (qlogFrames != null) {
                qlogFrames.newToken(token.remaining());
            }
            ackEliciting = true;
        }

        @Override
        public void streamFrameReceived(long streamId, long offset, boolean fin, ByteBuffer data) {
            if (qlogFrames != null) {
                qlogFrames.stream(streamId, offset, data.remaining(), fin);
            }
            ackEliciting = true;
            // Boxed once and threaded through every per-stream map touched
            // below (streams, checkAndRecordFlowControl's own chain,
            // streamReassemblers, pendingFinOffset) instead of each
            // re-deriving its own Long.valueOf(streamId) -- a stream ID
            // is almost always outside the JVM's cached Long range, so
            // this is the difference between one boxing allocation per
            // received STREAM frame and half a dozen.
            Long key = Long.valueOf(streamId);
            QuicStreamEndpoint stream = streams.get(key);
            if (stream == null) {
                stream = acceptStream(streamId);
                if (stream == null) {
                    return;
                }
            }
            int length = data.remaining();
            if (!checkAndRecordFlowControl(streamId, key, offset, length)) {
                return;
            }
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
                if (qlog != null) {
                    qlogDataMoved(QlogEvents.STREAM_DATA_MOVED, streamId,
                            reassembler.getNextOffset() - contiguous.length, contiguous.length,
                            QlogEvents.LOCATION_TRANSPORT, QlogEvents.LOCATION_APPLICATION);
                }
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
            if (qlogFrames != null) {
                qlogFrames.maxData(maximumData);
            }
            ackEliciting = true;
            if (maximumData > peerMaxData) {
                peerMaxData = maximumData;
                dataBlockedSignalled = false;
            }
        }

        @Override
        public void maxStreamDataFrameReceived(long streamId, long maximumStreamData) {
            if (qlogFrames != null) {
                qlogFrames.maxStreamData(streamId, maximumStreamData);
            }
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
            if (qlogFrames != null) {
                qlogFrames.maxStreams(bidirectional, maximumStreams);
            }
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
            if (qlogFrames != null) {
                qlogFrames.dataBlocked(maximumData);
            }
            ackEliciting = true;
            growConnectionLimitOnBlocked();
        }

        @Override
        public void streamDataBlockedFrameReceived(long streamId, long maximumStreamData) {
            if (qlogFrames != null) {
                qlogFrames.streamDataBlocked(streamId, maximumStreamData);
            }
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
            if (qlogFrames != null) {
                qlogFrames.streamsBlocked(bidirectional, maximumStreams);
            }
            ackEliciting = true;
        }

        @Override
        public void newConnectionIdFrameReceived(long sequenceNumber, long retirePriorTo,
                ByteBuffer connectionId, ByteBuffer statelessResetToken) {
            if (qlogFrames != null) {
                byte[] cidCopy = new byte[connectionId.remaining()];
                connectionId.duplicate().get(cidCopy);
                byte[] tokenCopy = new byte[statelessResetToken.remaining()];
                statelessResetToken.duplicate().get(tokenCopy);
                qlogFrames.newConnectionId(sequenceNumber, retirePriorTo, cidCopy, tokenCopy);
            }
            ackEliciting = true;
            byte[] cid = new byte[connectionId.remaining()];
            connectionId.get(cid);
            byte[] token = new byte[statelessResetToken.remaining()];
            statelessResetToken.get(token);
            connectionIdManager.addPeerConnectionId(sequenceNumber, retirePriorTo, cid, token);
        }

        @Override
        public void retireConnectionIdFrameReceived(long sequenceNumber) {
            if (qlogFrames != null) {
                qlogFrames.retireConnectionId(sequenceNumber);
            }
            ackEliciting = true;
            byte[] retired = connectionIdManager.getOurConnectionId(sequenceNumber);
            connectionIdManager.retireOurs(sequenceNumber);
            if (retired != null) {
                engine.unregisterConnectionId(retired);
                engine.markResetEligible(retired);
            }
            Runnable hook = retireConnectionIdHook;
            if (hook != null) {
                hook.run();
            }
        }

        @Override
        public void pathChallengeFrameReceived(ByteBuffer data) {
            if (qlogFrames != null) {
                byte[] copy = new byte[data.remaining()];
                data.duplicate().get(copy);
                qlogFrames.pathChallenge(copy);
            }
            ackEliciting = true;
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            // RFC 9000 section 8.2.2: answered on the path the challenge
            // itself arrived on, which may not be remoteAddress yet (e.g.
            // the peer probing a path this endpoint hasn't switched to).
            sendPathResponse(bytes, new PathKey(currentDatagramPath, currentDatagramSource));
        }

        @Override
        public void pathResponseFrameReceived(ByteBuffer data) {
            if (qlogFrames != null) {
                byte[] copy = new byte[data.remaining()];
                data.duplicate().get(copy);
                qlogFrames.pathResponse(copy);
            }
            ackEliciting = true;
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            PathKey arrival = new PathKey(currentDatagramPath, currentDatagramSource);
            PathValidationAttempt attempt = pathValidationAttempts.get(arrival);
            if (attempt != null && Arrays.equals(bytes, attempt.challengeData)) {
                completeMigration(arrival, attempt);
            }
        }

        @Override
        public void connectionCloseFrameReceived(boolean applicationError, long errorCode,
                long frameType, String reason) {
            if (qlogFrames != null) {
                qlogFrames.connectionClose(applicationError, errorCode, reason);
            }
            deferredCloseApplicationError = applicationError;
            deferredCloseErrorCode = errorCode;
            deferredCloseReason = reason;
            deferredCloseIsError = true;
            closedByPeer = true;
            close();
        }

        @Override
        public void handshakeDoneFrameReceived() {
            if (qlogFrames != null) {
                qlogFrames.handshakeDone();
            }
            ackEliciting = true;
            handshakeConfirmed = true;
            lossDetector.setHandshakeConfirmed(true);
            notifyClientHandshakeComplete();
            maybeMigrateToPreferredAddress();
        }

        // draft-ietf-quic-ack-frequency section 4
        @Override
        public void ackFrequencyFrameReceived(long sequenceNumber, long ackElicitingThresholdValue,
                long requestedMaxAckDelay, long reorderingThresholdValue) {
            if (qlogFrames != null) {
                qlogFrames.unknown(AckFrequencyDraft.FRAME_TYPE_ACK_FREQUENCY);
            }
            ackEliciting = true;
            if (level != EncryptionLevel.ONE_RTT || zeroRtt) {
                closeWithError(TRANSPORT_ERROR_PROTOCOL_VIOLATION,
                        "ACK_FREQUENCY frames are only permitted in 1-RTT packets");
                return;
            }
            if (requestedMaxAckDelay < AckFrequencyDraft.LOCAL_MIN_ACK_DELAY_MICROS
                    || requestedMaxAckDelay >= AckFrequencyDraft.REQUESTED_MAX_ACK_DELAY_LIMIT_MICROS) {
                closeWithError(TRANSPORT_ERROR_PROTOCOL_VIOLATION,
                        "ACK_FREQUENCY Requested Max Ack Delay out of range");
                return;
            }
            if (sequenceNumber <= lastAckFrequencySequence) {
                return;
            }
            lastAckFrequencySequence = sequenceNumber;
            ackElicitingThreshold = ackElicitingThresholdValue;
            requestedMaxAckDelayMicros = requestedMaxAckDelay;
            reorderingThreshold = reorderingThresholdValue;
            // a timer armed for the old delay is re-armed for the new one
            if (ackTimerHandle != null) {
                ackTimerHandle.cancel();
                ackTimerHandle = null;
            }
        }

        // draft-ietf-quic-ack-frequency section 5
        @Override
        public void immediateAckFrameReceived() {
            if (qlogFrames != null) {
                qlogFrames.unknown(AckFrequencyDraft.FRAME_TYPE_IMMEDIATE_ACK);
            }
            ackEliciting = true;
            if (level != EncryptionLevel.ONE_RTT || zeroRtt) {
                closeWithError(TRANSPORT_ERROR_PROTOCOL_VIOLATION,
                        "IMMEDIATE_ACK frames are only permitted in 1-RTT packets");
                return;
            }
            ackImmediate[level.ordinal()] = true;
        }

        @Override
        public void datagramFrameReceived(ByteBuffer data, int encodedLength) {
            if (qlogFrames != null) {
                qlogFrames.datagram(data.remaining());
            }
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
                if (qlog != null) {
                    qlogDataMoved(QlogEvents.DATAGRAM_DATA_MOVED, -1, -1, copy.length,
                            QlogEvents.LOCATION_TRANSPORT, QlogEvents.LOCATION_APPLICATION);
                }
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
    // draft-ietf-quic-ack-frequency section 6.5: until the latest frame
    // is acknowledged the peer may be applying either value, so the larger
    // of the two is used; once it is, the requested value stands in for
    // the transport parameter.
    private long peerMaxAckDelayMicros() {
        long current = ackFrequencyAckedDelayMicros;
        if (current < 0) {
            long millis = peerTransportParameters == null
                    ? TransportParameters.DEFAULT_MAX_ACK_DELAY : peerTransportParameters.getMaxAckDelay();
            current = millis * 1000L;
        }
        return Math.max(current, ackFrequencyPendingDelayMicros);
    }

    // Whether this endpoint may send ACK_FREQUENCY and IMMEDIATE_ACK:
    // switched on, and only to a peer that advertised min_ack_delay
    private boolean mayAskForFewerAcks() {
        return engine.isAckFrequencyEnabled() && peerMinAckDelayMicros >= 0;
    }

    // Asks for an ACK_FREQUENCY frame to be sent with the next packet
    // that can carry it: once the handshake is confirmed and there is an
    // RTT sample to size the request with, again after the congestion
    // controller is reset by a migration, and again with a new sequence
    // number when a frame is lost.
    private void queueAckFrequency() {
        if (mayAskForFewerAcks()) {
            ackFrequencyOwed = true;
            ackFrequencyQueuedOnce = true;
        }
    }

    // The values to request (draft section 6.1.2, kept conservative since
    // there is no pacing): no longer than a smoothed RTT or max_ack_delay,
    // but no shorter than the peer's min_ack_delay; a threshold of a
    // quarter of the congestion window in full-size packets; and the
    // RFC 9000 reordering rule.
    private long[] ackFrequencyRequest() {
        long srtt = lossDetector.getRttEstimator().getSmoothedRtt();
        long delay = Math.min(srtt, TransportParameters.DEFAULT_MAX_ACK_DELAY * 1000L);
        delay = Math.max(delay, peerMinAckDelayMicros);
        delay = Math.min(delay, AckFrequencyDraft.REQUESTED_MAX_ACK_DELAY_LIMIT_MICROS - 1);
        long window = lossDetector.getCongestionController().getCongestionWindow() / MAX_DATAGRAM_SIZE;
        long threshold = Math.max(AckFrequencyDraft.DEFAULT_ACK_ELICITING_THRESHOLD, window / 4);
        return new long[] { delay, threshold };
    }

    // draft section 6.2: an ACK_FREQUENCY frame was acknowledged
    private void ackFrequencyAcknowledged(long[] sent) {
        if (sent[0] > ackedAckFrequencySequence) {
            ackedAckFrequencySequence = sent[0];
            ackFrequencyAckedDelayMicros = sent[1];
            lossDetector.setAckElicitingThreshold(sent[2]);
        }
        if (sent[0] == latestAckFrequencySequenceSent) {
            ackFrequencyPendingDelayMicros = -1;
        }
    }

    // The ACK Delay field was received in this ACK frame's packet
    private void noteOneRttAckReceived(LossDetector.AckResult result) {
        if (result.getNewlyAcked().isEmpty()) {
            return;
        }
        lastAckReceivedMicros = nowMicros();
        immediateAckSinceLastAck = false;
        for (SentPacket acked : result.getNewlyAcked()) {
            long[] sent = sentAckFrequency.remove(Long.valueOf(acked.getPacketNumber()));
            if (sent != null) {
                ackFrequencyAcknowledged(sent);
            }
        }
        if (!ackFrequencyQueuedOnce && handshakeConfirmed && lossDetector.getRttEstimator().hasRttSample()) {
            queueAckFrequency();
        }
    }

    // draft section 6.3: no ACK has arrived for more than a round trip
    // although ack-eliciting data is in flight
    private boolean ackSilenceCallsForImmediateAck() {
        if (immediateAckSinceLastAck || lastAckReceivedMicros == Long.MIN_VALUE
                || !lossDetector.hasAckElicitingInFlight()) {
            return false;
        }
        return nowMicros() - lastAckReceivedMicros > lossDetector.getRttEstimator().getSmoothedRtt();
    }

    // RFC 9000 section 19.3: the ACK Delay field is in units of
    // 2^ack_delay_exponent microseconds, the exponent being the one the
    // peer declared (18.2), default 3.
    private long ackDelayMicros(long field) {
        long exponentValue = peerTransportParameters == null
                ? TransportParameters.DEFAULT_ACK_DELAY_EXPONENT : peerTransportParameters.getAckDelayExponent();
        // validated to at most 20 when the parameters arrive
        int exponent = (int) Math.min(exponentValue, 20L);
        if (field > (Long.MAX_VALUE >> exponent)) {
            return Long.MAX_VALUE;
        }
        return field << exponent;
    }

    private static long microsToMillisCeil(long micros) {
        return (micros + 999L) / 1000L;
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
        long newestRetired = -1L;
        for (SentPacket acked : newlyAcked) {
            long[] covered = coverage.remove(Long.valueOf(acked.getPacketNumber()));
            if (covered != null && unacked != null) {
                for (long pn : covered) {
                    unacked.remove(Long.valueOf(pn));
                }
                newestRetired = Math.max(newestRetired, acked.getPacketNumber());
            }
        }
        if (newestRetired >= 0 && !coverage.isEmpty()) {
            // An ACK frame names everything then waiting to be
            // acknowledged, so what an earlier packet's ACK covered has
            // either been retired already or was covered again by the one
            // just retired: the earlier entries have nothing left to
            // retire, and one for a packet the peer never acknowledges
            // would otherwise be kept for good.
            for (Iterator<Long> i = coverage.keySet().iterator(); i.hasNext(); ) {
                if (i.next().longValue() < newestRetired) {
                    i.remove();
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
            long[] lostAckFrequency = sentAckFrequency.remove(Long.valueOf(packetNumber));
            if (lostAckFrequency != null && lostAckFrequency[0] == latestAckFrequencySequenceSent) {
                // draft section 6.4: sent again with the current values
                // and a new Sequence Number, not as the old frame
                ackFrequencyOwed = mayAskForFewerAcks();
            }
            Map<Long, List<PendingChunk>> lostStreams = sentStream.remove(Long.valueOf(packetNumber));
            if (lostStreams != null) {
                for (Map.Entry<Long, List<PendingChunk>> entry : lostStreams.entrySet()) {
                    List<PendingChunk> chunks = pendingStream.get(entry.getKey());
                    if (chunks == null) {
                        chunks = new ArrayList<PendingChunk>();
                        addPendingStreamChunks(entry.getKey(), chunks);
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
                        addPendingStreamChunks(entry.getKey(), chunks);
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
        // Issue #505: a datagram carries at most MAX_DATAGRAM_SIZE bytes,
        // so queued stream data can need many of them. Keep going while
        // one more may be eligible; the congestion window and the peer's
        // flow control stop it, and each acknowledgement received flushes
        // again. The cap bounds how long one call can hold the loop.
        for (int datagrams = 0; datagrams < MAX_DATAGRAMS_PER_FLUSH; datagrams++) {
            if (!flushOneDatagram()) {
                return;
            }
        }
    }

    // Builds and sends one datagram. Returns true if it carried 1-RTT
    // data and more stream data is still queued.
    private boolean flushOneDatagram() {
        if (closed) {
            return false;
        }
        // RFC 9000 section 14: the datagram as a whole is bounded, and
        // the Initial packet, though built last (see flush), is the
        // oldest data and goes first: the other levels get only what it
        // will leave them, and nothing at all if that is too little for
        // a packet.
        int initialReserve = initialPacketReserve();
        boolean roomBesideInitial = MAX_DATAGRAM_SIZE - initialReserve >= MIN_COALESCED_PACKET_ROOM;
        coalescedBytes = initialReserve;
        byte[] zeroRttBytes = roomBesideInitial ? buildZeroRttPacketOrNull() : null;
        coalescedBytes = initialReserve + (zeroRttBytes != null ? zeroRttBytes.length : 0);
        byte[] handshakeBytes = roomBesideInitial ? buildLevelPacketOrNull(EncryptionLevel.HANDSHAKE, 0) : null;
        if (handshakeBytes != null) {
            sentHandshakePacket = true;
        }
        coalescedBytes += (handshakeBytes != null ? handshakeBytes.length : 0);
        byte[] oneRttBytes = roomBesideInitial ? buildLevelPacketOrNull(EncryptionLevel.ONE_RTT, 0) : null;

        int zeroRttHandshakeAndOneRttBytes = (zeroRttBytes != null ? zeroRttBytes.length : 0)
                + (handshakeBytes != null ? handshakeBytes.length : 0)
                + (oneRttBytes != null ? oneRttBytes.length : 0);
        int initialMinDatagramSize = !isServer ? Math.max(0, MIN_DATAGRAM_SIZE - zeroRttHandshakeAndOneRttBytes) : 0;
        coalescedBytes = zeroRttHandshakeAndOneRttBytes;
        byte[] initialBytes = buildLevelPacketOrNull(EncryptionLevel.INITIAL, initialMinDatagramSize);
        coalescedBytes = 0;

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
            return false;
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
        boolean sent = false;
        if (!isServer || addressValidated || amplificationBytesSent + datagram.length <= 3 * amplificationBytesReceived) {
            engine.sendPacket(this, datagram);
            sent = true;
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
        if (!sent || closed) {
            return false;
        }
        return (oneRttBytes != null && !pendingStream.isEmpty()) || hasPendingCrypto();
    }

    // Too little of a datagram to be worth starting another packet in.
    private static final int MIN_COALESCED_PACKET_ROOM = 64;

    // Whether handshake data is still queued at a level that can send it:
    // a flight larger than one datagram takes several (see flush).
    private boolean hasPendingCrypto() {
        EncryptionLevel[] levels = EncryptionLevel.values();
        for (int i = 0; i < levels.length; i++) {
            EncryptionLevel level = levels[i];
            if (!discarded[level.ordinal()] && sendKeys.get(level) != null
                    && !pendingCrypto.get(level).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // An upper estimate of the Initial packet the datagram being built
    // will carry, capped at a whole datagram; 0 if there will be none.
    private int initialPacketReserve() {
        EncryptionLevel level = EncryptionLevel.INITIAL;
        if (discarded[level.ordinal()] || sendKeys.get(level) == null) {
            return 0;
        }
        List<PendingChunk> chunks = pendingCrypto.get(level);
        boolean ack = ackOwed[level.ordinal()];
        boolean ping = pendingPing[level.ordinal()];
        if (chunks.isEmpty() && !ack && !ping) {
            return 0;
        }
        int size = packetOverhead(level);
        for (int i = 0; i < chunks.size() && size < MAX_DATAGRAM_SIZE; i++) {
            PendingChunk chunk = chunks.get(i);
            size += QuicFrameWriter.cryptoLength(chunk.offset, chunk.data.length);
        }
        if (ack) {
            size += QuicFrameWriter.ackLength(computeAckRanges(level), computeAckDelay(level));
        }
        if (ping) {
            size += QuicFrameWriter.pingLength();
        }
        return Math.min(size, MAX_DATAGRAM_SIZE);
    }

    // The most a packet's header and AEAD tag can take at a level, with a
    // four-byte packet number and, for a long header, a two-byte Length.
    private int packetOverhead(EncryptionLevel level) {
        if (level == EncryptionLevel.ONE_RTT) {
            return 1 + peerConnectionId.length + 4 + QuicAeadAlgorithm.TAG_LENGTH;
        }
        int size = 1 + 4 + 1 + peerConnectionId.length + 1 + ourConnectionId.length + 2 + 4
                + QuicAeadAlgorithm.TAG_LENGTH;
        if (level == EncryptionLevel.INITIAL) {
            int tokenLength = (retryToken != null) ? retryToken.length : 0;
            size += VarInt.encodedLength(tokenLength) + tokenLength;
        }
        return size;
    }

    // Takes from the head of `pending` the CRYPTO chunks whose frames fit
    // in `budget` bytes, splitting the first that does not: the part that
    // fits is sent and the rest stays queued, at its own offset, for the
    // next packet (RFC 9000 section 14, as drainEligibleStreamChunks does
    // for stream data).
    private static List<PendingChunk> drainCryptoChunks(List<PendingChunk> pending, int budget) {
        List<PendingChunk> toSend = new ArrayList<PendingChunk>();
        while (!pending.isEmpty() && budget > 0) {
            PendingChunk chunk = pending.get(0);
            int frameLength = QuicFrameWriter.cryptoLength(chunk.offset, chunk.data.length);
            if (frameLength > budget) {
                int fit = cryptoDataThatFits(chunk.offset, chunk.data.length, budget);
                if (fit <= 0) {
                    break;
                }
                PendingChunk head = new PendingChunk(chunk.offset, Arrays.copyOfRange(chunk.data, 0, fit));
                PendingChunk tail = new PendingChunk(chunk.offset + fit,
                        Arrays.copyOfRange(chunk.data, fit, chunk.data.length));
                pending.set(0, tail);
                toSend.add(head);
                break;
            }
            pending.remove(0);
            toSend.add(chunk);
            budget -= frameLength;
        }
        return toSend;
    }

    // The most of `available` data bytes of a chunk starting at `offset`
    // whose CRYPTO frame fits in `budget` bytes, or 0 if not even one does.
    private static int cryptoDataThatFits(long offset, int available, int budget) {
        int n = Math.min(available, budget);
        while (n > 0) {
            int over = QuicFrameWriter.cryptoLength(offset, n) - budget;
            if (over <= 0) {
                return n;
            }
            n -= over;
        }
        return 0;
    }

    private byte[] buildLevelPacketOrNull(EncryptionLevel level, int minDatagramSize) {
        if (discarded[level.ordinal()] || sendKeys.get(level) == null) {
            return null;
        }
        try {
            return buildProtectedPacket(level, minDatagramSize);
        } catch (PacketProtectionException e) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("warn.protect_outgoing_packet_failed"), level), e);
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
    /**
     * A network path (RFC 9000 section 9): the local socket a datagram
     * travels by and the peer address. Paths with the same remote address
     * but different local sockets are different paths, which is what a
     * server's preferred_address relies on.
     */
    static final class PathKey {
        final QuicDatagramPath path;
        final InetSocketAddress remote;

        PathKey(QuicDatagramPath path, InetSocketAddress remote) {
            this.path = path;
            this.remote = remote;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PathKey)) {
                return false;
            }
            PathKey that = (PathKey) other;
            return path == that.path && remote.equals(that.remote);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(path) * 31 + remote.hashCode();
        }

        @Override
        public String toString() {
            return remote + " via " + (path == null ? "?" : path.getLocalAddress());
        }
    }

    private static final class PathValidationAttempt {
        final long deadlineMillis;
        byte[] challengeData;
        TimerHandle timerHandle;
        // Client migration to a preferred_address: the peer connection ID
        // (sequence number 1) to use on the new path, else null for the
        // current one.
        byte[] destinationConnectionId;
        long destinationConnectionIdSequence;

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
    /** The path this connection currently sends on. */
    private PathKey currentPath() {
        return new PathKey(sendPath != null ? sendPath : engine.getPrimaryPath(), remoteAddress);
    }

    // RFC 9000 section 9.6.2: once the handshake is confirmed a client
    // moves to the server's preferred address of its current address
    // family (or the other, if that is all the server offered), from a
    // fresh local path and under the connection ID the parameter carried,
    // after validating that path like any other migration.
    private void maybeMigrateToPreferredAddress() {
        if (!preferredAddressPending || closed) {
            return;
        }
        preferredAddressPending = false;
        if (!engine.getFactory().isMigrateToPreferredAddress()) {
            return;
        }
        TransportParameters peer = peerTransportParameters;
        boolean ipv6 = remoteAddress.getAddress() instanceof Inet6Address;
        InetSocketAddress target = ipv6 ? peer.getPreferredAddressIpv6() : peer.getPreferredAddressIpv4();
        if (target == null) {
            target = ipv6 ? peer.getPreferredAddressIpv4() : peer.getPreferredAddressIpv6();
        }
        if (target == null || target.equals(remoteAddress)) {
            return;
        }
        byte[] connectionId = peer.getPreferredAddressConnectionId();
        if (!connectionIdManager.addPeerConnectionId(1, 0, connectionId, peer.getPreferredAddressResetToken())) {
            return;
        }
        QuicDatagramPath path;
        try {
            path = engine.openAdditionalPath(target.getAddress());
        } catch (IOException e) {
            LOGGER.log(Level.FINE, L10N.getString("fine.path_validation_abandoned"), e);
            return;
        }
        if (path == null) {
            return;
        }
        beginMigrationValidation(new PathKey(path, target), connectionId, 1);
    }

    /**
     * Starts validating a path (RFC 9000 section 8.2): a peer address
     * seen on a new path, or, for a client, the server's preferred
     * address.
     *
     * @param destinationConnectionId the peer connection ID to use on the
     *        new path, or null to keep the current one
     * @param destinationConnectionIdSequence its sequence number, if given
     */
    private void beginMigrationValidation(PathKey candidate, byte[] destinationConnectionId,
            long destinationConnectionIdSequence) {
        if (pathValidationAttempts.size() >= MAX_CONCURRENT_PATH_VALIDATIONS) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(MessageFormat.format(
                        L10N.getString("fine.path_validation_candidate_ignored"),
                        candidate, pathValidationAttempts.size()));
            }
            PathValidationRejectedObserver observer = pathValidationRejectedObserver;
            if (observer != null) {
                observer.pathValidationRejected(this, candidate.remote);
            }
            return;
        }
        long pto = currentPathValidationPto();
        long deadline = nowMillis() + Math.max(3 * pto, 6 * microsToMillisCeil(RttEstimator.K_INITIAL_RTT));
        PathValidationAttempt attempt = new PathValidationAttempt(deadline);
        attempt.destinationConnectionId = destinationConnectionId;
        attempt.destinationConnectionIdSequence = destinationConnectionIdSequence;
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
    private void sendPathChallengeAndScheduleRetry(final PathKey candidate,
            final PathValidationAttempt attempt, long pto) {
        attempt.challengeData = new byte[QuicFrameHandler.PATH_DATA_LENGTH];
        RANDOM.nextBytes(attempt.challengeData);
        sendPathChallenge(attempt.challengeData, candidate,
                attempt.destinationConnectionId != null ? attempt.destinationConnectionId : peerConnectionId);

        long now = nowMillis();
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

    private void onPathValidationTimeout(PathKey candidate) {
        PathValidationAttempt attempt = pathValidationAttempts.get(candidate);
        // Already completed, abandoned, or the connection closed out
        // from under this timer -- nothing to do.
        if (closed || attempt == null) {
            return;
        }
        if (nowMillis() >= attempt.deadlineMillis) {
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
    private void abandonMigrationValidation(PathKey candidate) {
        PathValidationAttempt attempt = pathValidationAttempts.remove(candidate);
        if (attempt != null && attempt.timerHandle != null) {
            attempt.timerHandle.cancel();
        }
        if (attempt != null) {
            PathValidationAbandonedObserver observer = pathValidationAbandonedObserver;
            if (observer != null) {
                observer.pathValidationAbandoned(this, candidate.remote);
            }
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(
                    L10N.getString("fine.path_validation_abandoned"), candidate));
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
        if (nowMillis() - migratedAtMillis.longValue() >= MIGRATION_COOLDOWN_MILLIS) {
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
        return microsToMillisCeil(smoothed + Math.max(4 * rttVar, LossDetector.K_GRANULARITY)
                + peerMaxAckDelayMicros());
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
    private void completeMigration(PathKey candidate, PathValidationAttempt attempt) {
        if (remoteAddress != null) {
            recentlyMigratedFromAddresses.put(remoteAddress, Long.valueOf(nowMillis()));
        }
        remoteAddress = candidate.remote;
        if (qlog != null) {
            qlogTupleAssigned();
        }
        sendPath = candidate.path == engine.getPrimaryPath() ? null : candidate.path;
        MigrationCompletedObserver observer = migrationCompletedObserver;
        if (observer != null) {
            observer.migrationCompleted(this, candidate.remote);
        }
        cancelAllPathValidationAttempts();

        if (attempt.destinationConnectionId != null) {
            // RFC 9000 section 9.6.2: the preferred_address connection ID
            // is the one to use there; the one used so far is retired.
            if (!Arrays.equals(attempt.destinationConnectionId, peerConnectionId)) {
                connectionIdManager.retirePeerConnectionId(activePeerConnectionIdSequence);
                if (qlog != null) {
                    qlogConnectionIdUpdated(QlogEvents.OWNER_REMOTE, peerConnectionId, attempt.destinationConnectionId);
                }
                peerConnectionId = attempt.destinationConnectionId;
                activePeerConnectionIdSequence = attempt.destinationConnectionIdSequence;
            }
        } else {
            // RFC 9000 section 9.5: prefer a peer connection ID not already
            // used on the old path, if the peer has issued a spare one via
            // NEW_CONNECTION_ID; if not (the common case in a simple
            // two-endpoint exchange with no spare IDs), this just returns the
            // same entry already in use and rotation is a no-op.
            ConnectionIdEntry fresh = connectionIdManager.getActivePeerConnectionId();
            if (fresh != null && !Arrays.equals(fresh.getConnectionId(), peerConnectionId)) {
                connectionIdManager.retirePeerConnectionId(activePeerConnectionIdSequence);
                if (qlog != null) {
                    qlogConnectionIdUpdated(QlogEvents.OWNER_REMOTE, peerConnectionId, fresh.getConnectionId());
                }
                peerConnectionId = fresh.getConnectionId();
                activePeerConnectionIdSequence = fresh.getSequenceNumber();
            }
        }

        lossDetector.getCongestionController().reset();
        // the request was sized for the old path's window and RTT
        queueAckFrequency();
        requestFlush();
    }

    private void sendPathChallenge(byte[] data, PathKey destination, byte[] destinationConnectionId) {
        sendPathFramePacket(QuicFrameHandler.TYPE_PATH_CHALLENGE, data, destination, destinationConnectionId);
    }

    private void sendPathResponse(byte[] data, PathKey destination) {
        sendPathFramePacket(QuicFrameHandler.TYPE_PATH_RESPONSE, data, destination, peerConnectionId);
    }

    private void sendPathFramePacket(long frameType, byte[] data, PathKey destination,
            byte[] destinationConnectionId) {
        try {
            // RFC 9000 section 8.2.1: a PATH_CHALLENGE-carrying datagram
            // must be expanded to the smallest allowed maximum datagram
            // size, so an off-path attacker can't use it to trigger an
            // amplified response; applied uniformly to PATH_RESPONSE too
            // for simplicity, even though the RFC only requires it of the
            // challenge (a PATH_RESPONSE is tiny either way).
            byte[] packet = buildStandalonePathFramePacket(frameType, data, MIN_DATAGRAM_SIZE,
                    destinationConnectionId);
            if (packet != null) {
                engine.sendTo(destination.path, destination.remote, packet);
            }
        } catch (PacketProtectionException e) {
            LOGGER.log(Level.WARNING,
                    L10N.getString("warn.protect_path_frame_failed"), e);
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
    private byte[] buildStandalonePathFramePacket(long frameType, byte[] data, int minDatagramSize,
            byte[] destinationConnectionId) throws PacketProtectionException {
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
            header = ShortHeaderCodec.build(destinationConnectionId, sendKeyPhase, packetNumber, pnLength);
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

        if (qlog != null) {
            QlogFrames qf = new QlogFrames();
            if (frameType == QuicFrameHandler.TYPE_PATH_CHALLENGE) {
                qf.pathChallenge(data);
            } else {
                qf.pathResponse(data);
            }
            if (paddingBytes > 0) {
                qf.padding(paddingBytes);
            }
            qlogPacketSent(QlogEvents.PACKET_TYPE_1RTT, packetNumber, packet.length, false, version,
                    destinationConnectionId, qf);
        }
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

    // The bytes the peer's connection-level and stream-level limits still
    // let us send on a stream (never negative).
    private int flowControlAllowance(long streamId) {
        long connectionRoom = peerMaxData - connectionBytesSent;
        Long sentSoFar = streamBytesSent.get(Long.valueOf(streamId));
        long sent = sentSoFar != null ? sentSoFar.longValue() : 0;
        long streamRoom = currentPeerStreamLimit(streamId) - sent;
        long room = Math.min(connectionRoom, streamRoom);
        if (room <= 0) {
            return 0;
        }
        return room > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) room;
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

    // See streamFrameReceived's field comment on key -- this whole
    // cluster of methods (down through checkAndRecordFlowControl) shares
    // one boxed streamId across every per-stream map it touches, rather
    // than each re-deriving its own.
    private long currentLocalStreamLimit(long streamId, Long key) {
        Long limit = localMaxStreamData.get(key);
        return limit != null ? limit.longValue() : initialLocalStreamLimit(streamId);
    }

    /**
     * Raises a stream's advertised receive limit to {@code newLimit} (a
     * no-op if it is not actually higher than the current one) and
     * queues a MAX_STREAM_DATA update.
     */
    private void growStreamLimit(long streamId, Long key, long newLimit) {
        if (newLimit > currentLocalStreamLimit(streamId, key)) {
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
    private void maybeGrowStreamLimit(long streamId, Long key, long highestOffset) {
        long windowSize = initialLocalStreamLimit(streamId);
        if (windowSize <= 0) {
            return;
        }
        long currentLimit = currentLocalStreamLimit(streamId, key);
        if (highestOffset > currentLimit - windowSize / 2) {
            growStreamLimit(streamId, key, highestOffset + windowSize / 2);
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
        Long key = Long.valueOf(streamId);
        growStreamLimit(streamId, key, currentLocalStreamLimit(streamId, key) + windowSize);
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
    private boolean checkAndRecordFlowControl(long streamId, Long key, long offset, int length) {
        long highestOffset = offset + length;
        Long previous = streamBytesReceived.get(key);
        long previousHighest = previous != null ? previous.longValue() : 0;
        if (highestOffset <= previousHighest) {
            // No new bytes implied by this frame (duplicate/retransmission
            // of already-accounted-for data) -- nothing to check or record.
            return true;
        }
        long streamLimit = currentLocalStreamLimit(streamId, key);
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
        maybeGrowStreamLimit(streamId, key, highestOffset);
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
    //
    // Issue #320: iterates pendingStreamOrder, which every pendingStream
    // mutation already keeps in this exact priority order (see its field
    // comment) -- no copy-and-sort of the whole pending set on every
    // call. Nothing in this loop's body mutates pendingStream/
    // pendingStreamOrder (the actual send-side removal happens later, in
    // buildProtectedPacket/buildZeroRttPacketOrNull once this has
    // returned), so iterating the live TreeSet directly is safe.
    //
    // Issue #505: `budget` is how many bytes of STREAM frames the packet
    // being built can still carry (RFC 9000 section 14). A chunk that does
    // not fit is split: the part that fits is sent and the rest stays
    // queued, at its own offset, for the next packet. Flow control is
    // charged only for the bytes actually taken.
    private Map<Long, List<PendingChunk>> drainEligibleStreamChunks(int budget) {
        Map<Long, List<PendingChunk>> streamChunksToSend = new HashMap<Long, List<PendingChunk>>();
        for (Long streamKey : pendingStreamOrder) {
            if (budget <= 0) {
                break;
            }
            long streamId = streamKey.longValue();
            List<PendingChunk> queued = pendingStream.get(streamKey);
            List<PendingChunk> toSend = new ArrayList<PendingChunk>();
            for (int index = 0; index < queued.size(); index++) {
                PendingChunk chunk = queued.get(index);
                boolean split = false;
                // RFC 9000 section 4.1: send as much as the peer's flow
                // control window still allows and keep the rest queued.
                // Waiting until the whole chunk fits would deadlock
                // against a peer that raises its limits only as it
                // consumes data it has not yet been sent.
                int allowance = flowControlAllowance(streamId);
                if (allowance > 0 && allowance < chunk.data.length) {
                    PendingChunk head = new PendingChunk(chunk.offset,
                            Arrays.copyOfRange(chunk.data, 0, allowance), false);
                    PendingChunk tail = new PendingChunk(chunk.offset + allowance,
                            Arrays.copyOfRange(chunk.data, allowance, chunk.data.length), chunk.fin);
                    queued.set(index, head);
                    queued.add(index + 1, tail);
                    chunk = head;
                }
                int frameLength = QuicFrameWriter.streamLength(streamId, chunk.offset, chunk.data.length);
                if (frameLength > budget) {
                    int fit = streamDataThatFits(streamId, chunk.offset, chunk.data.length, budget);
                    if (fit <= 0) {
                        budget = 0;
                        break;
                    }
                    PendingChunk head = new PendingChunk(chunk.offset, Arrays.copyOfRange(chunk.data, 0, fit), false);
                    PendingChunk tail = new PendingChunk(chunk.offset + fit,
                            Arrays.copyOfRange(chunk.data, fit, chunk.data.length), chunk.fin);
                    int blockedHead = checkSendBlocked(streamId, head.data.length);
                    if (blockedHead != SEND_NOT_BLOCKED) {
                        signalSendBlocked(streamId, blockedHead);
                        break;
                    }
                    queued.set(index, head);
                    queued.add(index + 1, tail);
                    chunk = head;
                    split = true;
                }
                int blocked = split ? SEND_NOT_BLOCKED : checkSendBlocked(streamId, chunk.data.length);
                if (blocked == SEND_NOT_BLOCKED) {
                    toSend.add(chunk);
                    recordBytesSent(streamId, chunk.data.length);
                    budget -= QuicFrameWriter.streamLength(streamId, chunk.offset, chunk.data.length);
                    if (split) {
                        budget = 0;
                        break;
                    }
                } else {
                    signalSendBlocked(streamId, blocked);
                    break; // preserve order: don't skip ahead of a blocked chunk
                }
            }
            if (!toSend.isEmpty()) {
                streamChunksToSend.put(streamKey, toSend);
            }
        }
        return streamChunksToSend;
    }

    // RFC 9000 section 4.1: tell the peer we're blocked so it has a
    // reason to grow its advertised limit even though (being blocked) we
    // can't send it any more data to trigger that growth passively --
    // without this, once a chunk doesn't fit in the remaining window,
    // nothing would ever unblock it. Only signalled once per limit value
    // (RFC 9000 section 4.1's "SHOULD NOT send more than once for a
    // given limit"); cleared when that limit grows.
    private void signalSendBlocked(long streamId, int blocked) {
        if (blocked == SEND_BLOCKED_BY_STREAM_LIMIT) {
            Long key = Long.valueOf(streamId);
            if (streamDataBlockedSignalled.add(key)) {
                streamDataBlockedOwed.put(key, Long.valueOf(currentPeerStreamLimit(streamId)));
            }
        } else if (!dataBlockedSignalled) {
            dataBlockedSignalled = true;
            dataBlockedOwed = true;
        }
    }

    // The most of `available` data bytes of a chunk starting at `offset`
    // whose STREAM frame fits in `budget` bytes, or 0 if not even one does.
    private static int streamDataThatFits(long streamId, long offset, int available, int budget) {
        int n = Math.min(available, budget);
        while (n > 0) {
            int over = QuicFrameWriter.streamLength(streamId, offset, n) - budget;
            if (over <= 0) {
                return n;
            }
            n -= over;
        }
        return 0;
    }

    // Bytes of the datagram already taken by, or reserved for, the other
    // packets the one being built will share it with (RFC 9000 section
    // 12.2); see flushOneDatagram.
    private int coalescedBytes;

    // The bytes of STREAM frames the next packet may carry: what is left
    // of one datagram after the packets coalesced before it, its own
    // header and the other frames it holds, and none at all while the congestion window has no
    // room for a full datagram (RFC 9002 section 7).
    private int streamBudget(int headerBytes, int otherFrameBytes) {
        if (!lossDetector.getCongestionController().canSend(MAX_DATAGRAM_SIZE)) {
            return 0;
        }
        return MAX_DATAGRAM_SIZE - coalescedBytes - headerBytes - QuicAeadAlgorithm.TAG_LENGTH - otherFrameBytes;
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
        long elapsedMicros = Math.max(0, nowNanos() - receivedAt) / 1000L;
        return elapsedMicros >>> DEFAULT_ACK_DELAY_EXPONENT;
    }

    private byte[] buildProtectedPacket(EncryptionLevel level, int minDatagramSize) throws PacketProtectionException {
        boolean oneRtt = level == EncryptionLevel.ONE_RTT;

        long[][] ackRangesForLevel = ackOwed[level.ordinal()] ? computeAckRanges(level) : null;
        boolean includeAck = ackRangesForLevel != null;
        long ackDelay = includeAck ? computeAckDelay(level) : 0;
        boolean includeHandshakeDone = oneRtt && handshakeDoneOwed;
        boolean includePing = pendingPing[level.ordinal()];
        boolean includeAckFrequency = oneRtt && ackFrequencyOwed && handshakeConfirmed && mayAskForFewerAcks();
        boolean immediateAckDue = oneRtt && immediateAckOwed && mayAskForFewerAcks();
        boolean includeImmediateAck = immediateAckDue || (oneRtt && mayAskForFewerAcks() && ackSilenceCallsForImmediateAck());
        long[] ackFrequencyValues = includeAckFrequency ? ackFrequencyRequest() : null;
        List<long[]> resetsToSend = oneRtt ? new ArrayList<long[]>(pendingResetStreams) : Collections.<long[]>emptyList();
        if (oneRtt && isServer) {
            connectionIdManager.rotateTo(engine.getQuicLbConfig());
        }
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

        int frameBytes = 0;
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
        if (includeAckFrequency) {
            frameBytes += QuicFrameWriter.ackFrequencyLength(nextAckFrequencySequence, ackFrequencyValues[1],
                    ackFrequencyValues[0], AckFrequencyDraft.DEFAULT_REORDERING_THRESHOLD);
        }
        if (includeImmediateAck) {
            frameBytes += QuicFrameWriter.immediateAckLength();
        }
        for (long[] reset : resetsToSend) {
            frameBytes += QuicFrameWriter.resetStreamLength(reset[0], reset[1], reset[2]);
        }
        for (ConnectionIdEntry entry : newCidsToSend) {
            frameBytes += QuicFrameWriter.newConnectionIdLength(entry.getSequenceNumber(), connectionIdManager.getRetirePriorTo(),
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

        // RFC 9000 section 14: handshake data too is bounded by what the
        // other frames leave of one datagram; the rest stays queued for
        // the next packet.
        List<PendingChunk> cryptoToSend = drainCryptoChunks(pendingCrypto.get(level),
                MAX_DATAGRAM_SIZE - coalescedBytes - packetOverhead(level) - frameBytes);
        for (PendingChunk chunk : cryptoToSend) {
            frameBytes += QuicFrameWriter.cryptoLength(chunk.offset, chunk.data.length);
        }

        // RFC 9000 section 14: whatever the other frames leave of one
        // datagram is all the stream data this packet may carry; the rest
        // stays queued for the next packet. Nothing is sent beyond the
        // congestion window either (RFC 9002 section 7).
        Map<Long, List<PendingChunk>> streamChunksToSend = oneRtt
                ? drainEligibleStreamChunks(streamBudget(1 + peerConnectionId.length + 4, frameBytes))
                : new HashMap<Long, List<PendingChunk>>();
        for (Map.Entry<Long, List<PendingChunk>> entry : streamChunksToSend.entrySet()) {
            for (PendingChunk chunk : entry.getValue()) {
                frameBytes += QuicFrameWriter.streamLength(entry.getKey().longValue(), chunk.offset, chunk.data.length);
            }
        }

        boolean nothingToSend = cryptoToSend.isEmpty() && streamChunksToSend.isEmpty() && !includeAck
                && !includeHandshakeDone && !includePing && !includeAckFrequency && !immediateAckDue
                && resetsToSend.isEmpty() && newCidsToSend.isEmpty()
                && retiresToSend.length == 0 && !includeMaxData && maxStreamDataToSend.isEmpty()
                && !includeDataBlocked && streamDataBlockedToSend.isEmpty()
                && !includeMaxStreamsBidi && !includeMaxStreamsUni
                && !includeStreamsBlockedBidi && !includeStreamsBlockedUni
                && datagramsToSend.isEmpty();
        if (nothingToSend) {
            return null;
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
                    ? LongHeaderCodec.build(packetType, version.getWireValue(), peerConnectionId, ourConnectionId,
                            packetType == LongHeaderCodec.TYPE_INITIAL ? retryToken : EMPTY_TOKEN,
                            packetNumber, pnLength, frameBytes + paddingBytes + QuicAeadAlgorithm.TAG_LENGTH)
                    : ShortHeaderCodec.build(peerConnectionId, sendKeyPhase, packetNumber, pnLength);
            int required = minDatagramSize - (header.length + frameBytes + paddingBytes + QuicAeadAlgorithm.TAG_LENGTH);
            int nextPadding = Math.max(hpSamplePadding, Math.max(0, paddingBytes + required));
            if (nextPadding == paddingBytes) {
                break;
            }
            paddingBytes = nextPadding;
        }
        int totalFrameBytes = frameBytes + paddingBytes;

        ByteBuffer payload = ByteBuffer.allocate(totalFrameBytes);
        QlogFrames qf = qlog != null ? new QlogFrames() : null;
        List<PendingChunk> sentCryptoThisPacket = cryptoToSend;
        for (PendingChunk chunk : cryptoToSend) {
            QuicFrameWriter.writeCrypto(payload, chunk.offset, chunk.data);
            if (qf != null) {
                qf.crypto(chunk.offset, chunk.data.length);
            }
        }
        if (!sentCryptoThisPacket.isEmpty()) {
            sentCrypto.get(level).put(Long.valueOf(packetNumber), sentCryptoThisPacket);
        }

        Map<Long, List<PendingChunk>> sentStreamThisPacket = new HashMap<Long, List<PendingChunk>>();
        List<QuicStreamEndpoint> streamsToNotify = new ArrayList<QuicStreamEndpoint>();
        for (Map.Entry<Long, List<PendingChunk>> entry : streamChunksToSend.entrySet()) {
            long streamId = entry.getKey().longValue();
            for (PendingChunk chunk : entry.getValue()) {
                QuicFrameWriter.writeStream(payload, streamId, chunk.offset, chunk.data, chunk.fin);
                if (qf != null) {
                    qf.stream(streamId, chunk.offset, chunk.data.length, chunk.fin);
                }
            }
            List<PendingChunk> queued = pendingStream.get(entry.getKey());
            // entry.getValue() (drainEligibleStreamChunks' toSend) is
            // always a strict prefix of queued, in order -- the drain
            // loop breaks at the first blocked chunk rather than
            // skipping ahead. removeAll(Collection) would do an O(n)
            // contains() scan per element of queued (O(n*m) total);
            // truncating the known prefix is a single O(m) shift.
            queued.subList(0, entry.getValue().size()).clear();
            if (queued.isEmpty()) {
                removePendingStream(entry.getKey());
                QuicStreamEndpoint stream = streams.get(entry.getKey());
                if (stream != null) {
                    streamsToNotify.add(stream);
                }
            }
            sentStreamThisPacket.put(entry.getKey(), entry.getValue());
        }
        if (!sentStreamThisPacket.isEmpty()) {
            sentStream.put(Long.valueOf(packetNumber), sentStreamThisPacket);
        }

        if (includeAck) {
            QuicFrameWriter.writeAck(payload, ackRanges, ackDelay);
            if (qf != null) {
                qf.ack(ackRanges, ackDelay << DEFAULT_ACK_DELAY_EXPONENT);
            }
            ackOwed[level.ordinal()] = false;
            ackElicitingUnacked[level.ordinal()] = 0;
            ackImmediate[level.ordinal()] = false;
            if (oneRtt && ackTimerHandle != null) {
                ackTimerHandle.cancel();
                ackTimerHandle = null;
            }
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
            if (qf != null) {
                qf.handshakeDone();
            }
            handshakeDoneOwed = false;
        }
        if (includePing) {
            QuicFrameWriter.writePing(payload);
            if (qf != null) {
                qf.ping();
            }
            pendingPing[level.ordinal()] = false;
        }
        if (includeAckFrequency) {
            long sequence = nextAckFrequencySequence++;
            QuicFrameWriter.writeAckFrequency(payload, sequence, ackFrequencyValues[1], ackFrequencyValues[0],
                    AckFrequencyDraft.DEFAULT_REORDERING_THRESHOLD);
            if (qf != null) {
                qf.unknown(AckFrequencyDraft.FRAME_TYPE_ACK_FREQUENCY);
            }
            sentAckFrequency.put(Long.valueOf(packetNumber),
                    new long[] { sequence, ackFrequencyValues[0], ackFrequencyValues[1] });
            latestAckFrequencySequenceSent = sequence;
            ackFrequencyPendingDelayMicros = ackFrequencyValues[0];
            ackFrequencyOwed = false;
        }
        if (includeImmediateAck) {
            QuicFrameWriter.writeImmediateAck(payload);
            if (qf != null) {
                qf.unknown(AckFrequencyDraft.FRAME_TYPE_IMMEDIATE_ACK);
            }
            immediateAckOwed = false;
            immediateAckSinceLastAck = true;
        }
        for (long[] reset : resetsToSend) {
            QuicFrameWriter.writeResetStream(payload, reset[0], reset[1], reset[2]);
            if (qf != null) {
                qf.resetStream(reset[0], reset[1], reset[2]);
            }
        }
        if (!resetsToSend.isEmpty()) {
            pendingResetStreams.removeAll(resetsToSend);
        }
        for (ConnectionIdEntry entry : newCidsToSend) {
            QuicFrameWriter.writeNewConnectionId(payload, entry.getSequenceNumber(), connectionIdManager.getRetirePriorTo(),
                    entry.getConnectionId(), entry.getStatelessResetToken());
            if (qf != null) {
                qf.newConnectionId(entry.getSequenceNumber(), connectionIdManager.getRetirePriorTo(),
                        entry.getConnectionId(), entry.getStatelessResetToken());
                qlogConnectionIdUpdated(QlogEvents.OWNER_LOCAL, null, entry.getConnectionId());
            }
        }
        for (long sequenceNumber : retiresToSend) {
            QuicFrameWriter.writeRetireConnectionId(payload, sequenceNumber);
            if (qf != null) {
                qf.retireConnectionId(sequenceNumber);
            }
        }
        if (includeMaxData) {
            QuicFrameWriter.writeMaxData(payload, localMaxData);
            if (qf != null) {
                qf.maxData(localMaxData);
            }
            maxDataOwed = false;
        }
        for (Map.Entry<Long, Long> entry : maxStreamDataToSend.entrySet()) {
            QuicFrameWriter.writeMaxStreamData(payload, entry.getKey().longValue(), entry.getValue().longValue());
            if (qf != null) {
                qf.maxStreamData(entry.getKey().longValue(), entry.getValue().longValue());
            }
        }
        maxStreamDataOwed.keySet().removeAll(maxStreamDataToSend.keySet());
        if (includeDataBlocked) {
            QuicFrameWriter.writeDataBlocked(payload, peerMaxData);
            if (qf != null) {
                qf.dataBlocked(peerMaxData);
            }
            dataBlockedOwed = false;
        }
        for (Map.Entry<Long, Long> entry : streamDataBlockedToSend.entrySet()) {
            QuicFrameWriter.writeStreamDataBlocked(payload, entry.getKey().longValue(), entry.getValue().longValue());
            if (qf != null) {
                qf.streamDataBlocked(entry.getKey().longValue(), entry.getValue().longValue());
            }
        }
        streamDataBlockedOwed.keySet().removeAll(streamDataBlockedToSend.keySet());
        if (includeMaxStreamsBidi) {
            QuicFrameWriter.writeMaxStreams(payload, true, localMaxStreamsBidi);
            if (qf != null) {
                qf.maxStreams(true, localMaxStreamsBidi);
            }
            maxStreamsBidiOwed = false;
        }
        if (includeMaxStreamsUni) {
            QuicFrameWriter.writeMaxStreams(payload, false, localMaxStreamsUni);
            if (qf != null) {
                qf.maxStreams(false, localMaxStreamsUni);
            }
            maxStreamsUniOwed = false;
        }
        if (includeStreamsBlockedBidi) {
            QuicFrameWriter.writeStreamsBlocked(payload, true, peerMaxStreamsBidi);
            if (qf != null) {
                qf.streamsBlocked(true, peerMaxStreamsBidi);
            }
            streamsBlockedBidiOwed = false;
        }
        if (includeStreamsBlockedUni) {
            QuicFrameWriter.writeStreamsBlocked(payload, false, peerMaxStreamsUni);
            if (qf != null) {
                qf.streamsBlocked(false, peerMaxStreamsUni);
            }
            streamsBlockedUniOwed = false;
        }
        for (byte[] datagram : datagramsToSend) {
            QuicFrameWriter.writeDatagram(payload, datagram);
            if (qf != null) {
                qf.datagram(datagram.length);
            }
        }
        if (!datagramsToSend.isEmpty()) {
            pendingDatagrams.removeAll(datagramsToSend);
        }
        if (paddingBytes > 0) {
            QuicFrameWriter.writePadding(payload, paddingBytes);
            if (qf != null) {
                qf.padding(paddingBytes);
            }
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
                || includeHandshakeDone || includePing || includeAckFrequency || includeImmediateAck
                || !resetsToSend.isEmpty() || !newCidsToSend.isEmpty()
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
        lossDetector.onPacketSent(level, packetNumber, nowMicros(), ackEliciting, inFlight, packet.length);
        if (metrics != null) {
            metrics.packetSent(packet.length);
        }
        if (qf != null) {
            qlogPacketSent(qlogPacketType(level), packetNumber, packet.length, longHeader, version, peerConnectionId, qf);
        }
        // Deferred until every bit of this packet's own construction and
        // bookkeeping above is done: notifyWriteReady() synchronously
        // runs application code, which can call stream.send(...) ->
        // queueStreamData -> requestFlush() -> a reentrant flush() call.
        // Firing it from inside the stream-sending loop above, while
        // that loop was still mid-iteration, let such a reentrant call
        // drain and remove a pendingStream entry the loop hadn't reached
        // yet, so its own later pendingStream.get(entry.getKey())
        // returned null -- a pre-existing intermittent
        // NullPointerException here, traced during issue #320's CI
        // investigation.
        for (QuicStreamEndpoint stream : streamsToNotify) {
            stream.notifyWriteReady();
        }
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
        // establishment (see Http3ClientHandler.isSafeToSendNow) would
        // have that data sent under 0-RTT keys anyway the moment it's
        // finally queued, defeating the whole point of deferring it.
        if (zeroRttSendKeys == null || established) {
            return null;
        }
        try {
            return buildZeroRttProtectedPacket();
        } catch (PacketProtectionException e) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.protect_zero_rtt_failed"), e);
            return null;
        }
    }

    private byte[] buildZeroRttProtectedPacket() throws PacketProtectionException {
        // 1 flags byte, version, both connection IDs with their lengths, a
        // 2-byte Length field and a 4-byte packet number.
        Map<Long, List<PendingChunk>> streamChunksToSend = drainEligibleStreamChunks(streamBudget(
                1 + 4 + 1 + peerConnectionId.length + 1 + ourConnectionId.length + 2 + 4, 0));
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
        byte[] header = LongHeaderCodec.build(LongHeaderCodec.TYPE_0RTT, initialVersion.getWireValue(), peerConnectionId, ourConnectionId,
                EMPTY_TOKEN, packetNumber, pnLength, frameBytes + paddingBytes + QuicAeadAlgorithm.TAG_LENGTH);
        int totalFrameBytes = frameBytes + paddingBytes;

        ByteBuffer payload = ByteBuffer.allocate(totalFrameBytes);
        Map<Long, List<PendingChunk>> sentThisPacket = new HashMap<Long, List<PendingChunk>>();
        List<QuicStreamEndpoint> streamsToNotify = new ArrayList<QuicStreamEndpoint>();
        QlogFrames qf = qlog != null ? new QlogFrames() : null;
        for (Map.Entry<Long, List<PendingChunk>> entry : streamChunksToSend.entrySet()) {
            long streamId = entry.getKey().longValue();
            for (PendingChunk chunk : entry.getValue()) {
                QuicFrameWriter.writeStream(payload, streamId, chunk.offset, chunk.data, chunk.fin);
                if (qf != null) {
                    qf.stream(streamId, chunk.offset, chunk.data.length, chunk.fin);
                }
            }
            List<PendingChunk> queued = pendingStream.get(entry.getKey());
            // See buildProtectedPacket's identical drain-truncation site
            // for why this is a subList clear rather than removeAll.
            queued.subList(0, entry.getValue().size()).clear();
            if (queued.isEmpty()) {
                removePendingStream(entry.getKey());
                QuicStreamEndpoint stream = streams.get(entry.getKey());
                if (stream != null) {
                    streamsToNotify.add(stream);
                }
            }
            sentThisPacket.put(entry.getKey(), entry.getValue());
        }
        if (paddingBytes > 0) {
            QuicFrameWriter.writePadding(payload, paddingBytes);
            if (qf != null) {
                qf.padding(paddingBytes);
            }
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
        lossDetector.onPacketSent(EncryptionLevel.ONE_RTT, packetNumber, nowMicros(), true, true,
                packet.length);
        if (metrics != null) {
            metrics.packetSent(packet.length);
        }
        if (qf != null) {
            qlogPacketSent(QlogEvents.PACKET_TYPE_0RTT, packetNumber, packet.length, true, initialVersion, peerConnectionId, qf);
        }
        // Deferred until every bit of this packet's own construction and
        // bookkeeping above is done -- see the identical comment in
        // buildProtectedPacket (issue #320's CI investigation) for why
        // notifyWriteReady() cannot safely fire while pendingStream is
        // still being read for this packet.
        for (QuicStreamEndpoint stream : streamsToNotify) {
            stream.notifyWriteReady();
        }
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
        long now = nowMicros();
        boolean hasHandshakeKeys = sendKeys.get(EncryptionLevel.HANDSHAKE) != null;
        long deadline = lossDetector.getLossDetectionTimeout(false, peerAddressValidated(), hasHandshakeKeys,
                peerMaxAckDelayMicros(), now);
        if (deadline == LossDetector.NO_TIMEOUT) {
            Runnable observer = lossDetectionIdleObserver;
            if (observer != null) {
                observer.run();
            }
            return;
        }
        long delay = Math.max(0, microsToMillisCeil(deadline - now));
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
                peerMaxAckDelayMicros(), nowMicros());
        EncryptionLevel lossSpace = result.getLossSpace();
        if (lossSpace != null) {
            if (metrics != null) {
                metricsRecoverySample(result.getNewlyLost().size());
            }
            for (SentPacket lost : result.getNewlyLost()) {
                if (qlog != null) {
                    qlogPacketLost(lossSpace, lost.getPacketNumber(), QlogEvents.TRIGGER_TIME_THRESHOLD);
                }
                requeueLostPacket(lossSpace, lost.getPacketNumber());
            }
            if (qlog != null) {
                qlogRecoveryMetrics(!result.getNewlyLost().isEmpty());
            }
        } else {
            // Probe Timeout: nothing was naturally queued to retransmit,
            // so send a bare PING to elicit an ACK and keep the
            // connection alive (RFC 9002 Appendix A.9).
            EncryptionLevel probeSpace = result.getProbeSpace();
            if (probeSpace != null && sendKeys.get(probeSpace) != null) {
                pendingPing[probeSpace.ordinal()] = true;
                // draft section 6.3: the probe asks for an ACK at once
                if (probeSpace == EncryptionLevel.ONE_RTT && mayAskForFewerAcks()) {
                    immediateAckOwed = true;
                }
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
    private static final long TRANSPORT_ERROR_STREAM_STATE_ERROR = 0x5;
    /** RFC 9000 section 20.1: a frame was malformed, e.g. MAX_STREAMS greater than 2^60. */
    private static final long TRANSPORT_ERROR_FRAME_ENCODING_ERROR = 0x7;
    /** RFC 9000 section 20.1: a transport parameter was received with a value not permitted for its type, e.g. a mismatched retry_source_connection_id. */
    private static final long TRANSPORT_ERROR_TRANSPORT_PARAMETER_ERROR = 0x8;
    /** RFC 9000 section 20.1: an endpoint received a frame that violates protocol rules, e.g. a DATAGRAM when max_datagram_frame_size was not advertised (RFC 9221). */
    private static final long TRANSPORT_ERROR_PROTOCOL_VIOLATION = 0xa;
    /** RFC 9000 section 20.1: the amount of buffered reordered CRYPTO data exceeds this endpoint's ability to buffer it. */
    private static final long TRANSPORT_ERROR_CRYPTO_BUFFER_EXCEEDED = 0xd;
    /** RFC 9368 section 4: a version negotiation error. */
    private static final long TRANSPORT_ERROR_VERSION_NEGOTIATION_ERROR = 0x11;
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
        qlogConnectionClosed(closedByPeer ? QlogEvents.OWNER_REMOTE : QlogEvents.OWNER_LOCAL, null);
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
                LOGGER.log(Level.FINE, L10N.getString("fine.connection_close_send_failed"), e);
            }
        }
        tearDownStreams();
        engine.onConnectionClosed(this);
    }

    /**
     * Closes the connection without telling the peer (no CONNECTION_CLOSE),
     * still tearing down every stream so their handlers are notified. Used
     * when the owning engine is aborted at shutdown.
     */
    void abort() {
        if (closed) {
            return;
        }
        closed = true;
        qlogConnectionClosed(QlogEvents.OWNER_LOCAL, null);
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
        }
        cancelAllPathValidationAttempts();
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
        deferredCloseIsError = true;
        qlogConnectionClosed(QlogEvents.OWNER_REMOTE, QlogEvents.TRIGGER_STATELESS_RESET);
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
        }
        cancelAllPathValidationAttempts();
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
        // Iterate a snapshot: a handler notified here may close a sibling
        // stream, which retires it from the live table mid-iteration.
        for (QuicStreamEndpoint stream : new ArrayList<QuicStreamEndpoint>(streams.values())) {
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
                ? LongHeaderCodec.build(packetType, version.getWireValue(), peerConnectionId, ourConnectionId,
                        packetType == LongHeaderCodec.TYPE_INITIAL ? retryToken : EMPTY_TOKEN,
                        packetNumber, pnLength, frameBytes + QuicAeadAlgorithm.TAG_LENGTH)
                : ShortHeaderCodec.build(peerConnectionId, sendKeyPhase, packetNumber, pnLength);

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

        if (qlog != null) {
            qlogPacketSent(qlogPacketType(level), packetNumber, packet.length, longHeader, version, peerConnectionId,
                    new QlogFrames().connectionClose(applicationError, errorCode, reason));
        }
        engine.sendPacket(this, packet);
    }

    // ── QuicTlsEngineListener ──

    @Override
    public void cryptoDataReady(EncryptionLevel level, long offset, byte[] data) {
        if (closed) {
            return;
        }
        pendingCrypto.get(level).add(new PendingChunk(offset, data));
        requestFlush();
    }

    @Override
    public void execute(Runnable task) {
        engine.execute(task);
    }

    @Override
    public void cryptoProcessingFailed(EncryptionLevel level, Throwable cause) {
        LOGGER.log(Level.WARNING, MessageFormat.format(
                L10N.getString("warn.crypto_processing_failed"), level), cause);
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
    private void selectHkdfAead(CipherSuite cipher) {
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
        Runnable keysObserver = oneRttKeysReadyObserver;
        if (keysObserver != null) {
            keysObserver.run();
        }
        established = true;
        if (qlog != null) {
            qlogAlpnInformation();
        }
        if (metrics != null) {
            metrics.handshakeCompleted(System.currentTimeMillis() - handshakeStartTime);
        }
        if (isServer) {
            handshakeDoneOwed = true;
            handshakeConfirmed = true; // RFC 9001 section 4.1.2: sending HANDSHAKE_DONE is the server's own confirmation
            lossDetector.setHandshakeConfirmed(true);
            requestFlush();
        } else {
            notifyClientHandshakeComplete();
        }
    }

    @Override
    public void transportParametersReceived(TransportParameters transportParameters) {
        // draft-ietf-quic-ack-frequency section 3: min_ack_delay, in
        // microseconds, must not exceed max_ack_delay, in milliseconds
        if (transportParameters.hasMinAckDelay()
                && transportParameters.getMinAckDelay() > transportParameters.getMaxAckDelay() * 1000L) {
            closeWithError(TRANSPORT_ERROR_TRANSPORT_PARAMETER_ERROR, "min_ack_delay exceeds max_ack_delay");
            return;
        }
        if (transportParameters.getAckDelayExponent() > 20) {
            closeWithError(TRANSPORT_ERROR_TRANSPORT_PARAMETER_ERROR, "ack_delay_exponent exceeds 20");
            return;
        }
        peerMinAckDelayMicros = transportParameters.hasMinAckDelay() ? transportParameters.getMinAckDelay() : -1;
        if (qlog != null) {
            qlog.emit(QlogEvents.PARAMETERS_SET, nowNanos(),
                    qlogParameters(QlogEvents.OWNER_REMOTE, transportParameters));
        }
        if (transportParameters.isVersionInformationMalformed()) {
            closeWithError(TRANSPORT_ERROR_TRANSPORT_PARAMETER_ERROR, "malformed version_information");
            return;
        }
        if (!processPeerVersionInformation(transportParameters)) {
            return;
        }
        if (qlog != null) {
            qlogVersionInformation(transportParameters);
        }
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
        if (!isServer && transportParameters.hasPreferredAddress()) {
            preferredAddressPending = true;
        }
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

    // RFC 9368 section 4 (with RFC 9369 section 4): validates the peer's
    // version_information, and on a server performs compatible version
    // negotiation. Returns false if the connection was closed.
    private boolean processPeerVersionInformation(TransportParameters peer) {
        if (isServer) {
            if (!peer.hasVersionInformation()) {
                return true; // a server MAY complete the handshake without it
            }
            int chosen = peer.getVersionInformationChosen();
            int[] available = peer.getVersionInformationAvailable();
            if (!containsVersion(available, chosen)) {
                closeWithError(TRANSPORT_ERROR_TRANSPORT_PARAMETER_ERROR,
                        "Chosen Version not among Available Versions");
                return false;
            }
            if (chosen != initialVersion.getWireValue()) {
                closeWithError(TRANSPORT_ERROR_VERSION_NEGOTIATION_ERROR,
                        "Chosen Version differs from the version in use");
                return false;
            }
            QuicVersion negotiated = QuicVersion.selectCompatible(initialVersion, available,
                    engine.getSupportedVersions());
            if (negotiated != version) {
                adoptVersion(negotiated, deriveInitialKeys(negotiated, initialKeyDcid));
            }
            return true;
        }
        if (!peer.hasVersionInformation()) {
            if (afterVersionNegotiation) {
                closeWithError(TRANSPORT_ERROR_VERSION_NEGOTIATION_ERROR,
                        "server version_information missing after Version Negotiation");
                return false;
            }
            return true;
        }
        int chosen = peer.getVersionInformationChosen();
        int[] offered = localTransportParameters.getVersionInformationAvailable();
        // The negotiated version, learnt from long headers, must be what
        // the server says it chose, or the header was forged.
        if (chosen != version.getWireValue()
                || (offered != null && !containsVersion(offered, chosen))) {
            closeWithError(TRANSPORT_ERROR_VERSION_NEGOTIATION_ERROR,
                    "server Chosen Version does not match the negotiated version");
            return false;
        }
        if (afterVersionNegotiation && !QuicVersion.validatesNegotiation(engine.getSupportedVersions(),
                peer.getVersionInformationAvailable(), version)) {
            closeWithError(TRANSPORT_ERROR_VERSION_NEGOTIATION_ERROR,
                    "server Available Versions contradict Version Negotiation");
            return false;
        }
        return true;
    }

    private static boolean containsVersion(int[] versions, int wireValue) {
        for (int i = 0; i < versions.length; i++) {
            if (versions[i] == wireValue) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void earlySecretsAvailable() {
        // RFC 9001 section 4.6.1: fires before either side has decided
        // whether 0-RTT will actually be accepted. 0-RTT/session
        // resumption is not implemented yet (see SessionTicketCache's
        // class documentation), so wasEarlyDataAccepted() is always
        // false and getEarlyDataCipher() is always null -- this method
        // never actually derives anything today; kept in the same shape
        // it was in so restoring 0-RTT support is a matter of the
        // underlying engine methods starting to return real values, not
        // rewriting this callback.
        if (isServer && !((org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine) tlsEngine).wasEarlyDataAccepted()) {
            return;
        }
        CipherSuite cipher = isServer
                ? ((org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine) tlsEngine).getSelectedCipher()
                : ((org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine) tlsEngine).getEarlyDataCipher();
        if (cipher == null) {
            return;
        }
        selectHkdfAead(cipher);
        byte[] clientEarlyTrafficSecret = tlsEngine.getClientEarlyTrafficSecret();
        PacketProtectionKeys keys = PacketProtectionKeys.derive(hkdf, clientEarlyTrafficSecret, aead, initialVersion);
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
    public void newSessionTicketReceived(SessionTicket ticket) {
        String host = (serverName != null) ? serverName : remoteAddress.getAddress().getHostAddress();
        SessionTicketCache.put(host, remoteAddress.getPort(), version, ticket,
                peerTransportParameters.copyWithoutMinAckDelay());
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
                    addPendingStreamChunks(entry.getKey(), chunks);
                }
                chunks.addAll(0, entry.getValue());
            }
        }
        sentZeroRttStream.clear();
        requestFlush();
    }

    private CipherSuite selectedCipher() {
        return isServer
                ? ((org.bluezoo.gumdrop.quic.tls.QuicTlsServerEngine) tlsEngine).getSelectedCipher()
                : ((org.bluezoo.gumdrop.quic.tls.QuicTlsClientEngine) tlsEngine).getSelectedCipher();
    }

    private void deriveDirectionalKeys(EncryptionLevel level, byte[] clientSecret, byte[] serverSecret) {
        PacketProtectionKeys clientKeys = PacketProtectionKeys.derive(hkdf, clientSecret, aead, version);
        PacketProtectionKeys serverKeys = PacketProtectionKeys.derive(hkdf, serverSecret, aead, version);
        if (isServer) {
            sendKeys.put(level, serverKeys);
            recvKeys.put(level, clientKeys);
        } else {
            sendKeys.put(level, clientKeys);
            recvKeys.put(level, serverKeys);
        }
        if (qlog != null && (level == EncryptionLevel.HANDSHAKE || level == EncryptionLevel.ONE_RTT)) {
            String name = level == EncryptionLevel.HANDSHAKE ? "handshake" : "1rtt";
            qlogKeyUpdated(true, name, 0, QlogEvents.TRIGGER_TLS);
            qlogKeyUpdated(false, name, 0, QlogEvents.TRIGGER_TLS);
        }
        if (level == EncryptionLevel.ONE_RTT) {
            oneRttSendSecret = isServer ? serverSecret : clientSecret;
            oneRttRecvSecret = isServer ? clientSecret : serverSecret;
            sendKeyPhase = false;
            recvKeyPhase = false;
            lowestPnOfCurrentRecvPhase = -1;
            firstPnOfCurrentSendPhase = sendPacketNumber[level.ordinal()];
            currentSendKeysAcknowledged = true;
            prepareNextRecvKeys();
        }
    }

    // ---- RFC 9001 section 6: key update ----

    /**
     * Initiates a key update (RFC 9001 section 6): packets sent from now
     * on are protected with the next generation of keys and carry the
     * other Key Phase bit, and the peer is expected to follow. Permitted
     * only once the handshake is confirmed, and not while a previous
     * update still awaits acknowledgement of a packet sent under its
     * keys.
     *
     * @return true if the update was initiated, false if it is not
     *         permitted yet (or the connection is closed)
     */
    public boolean requestKeyUpdate() {
        if (closed || !handshakeConfirmed || oneRttSendSecret == null || !currentSendKeysAcknowledged) {
            return false;
        }
        advanceSendKeys(QlogEvents.TRIGGER_LOCAL_UPDATE);
        requestFlush();
        return true;
    }

    /** The Key Phase bit of the packets this endpoint currently sends. */
    boolean getKeyPhase() {
        return sendKeyPhase;
    }

    /** How many times this endpoint's send keys have moved to a new generation. */
    int getKeyUpdateCount() {
        return keyUpdateCount;
    }

    /** How many packets were read with the keys of the phase before the current one. */
    int getPreviousPhasePacketsRead() {
        return previousPhasePacketsRead;
    }

    private void prepareNextRecvKeys() {
        nextRecvSecret = PacketProtectionKeys.nextSecret(hkdf, oneRttRecvSecret, version);
        nextRecvKeys = PacketProtectionKeys.update(hkdf, nextRecvSecret, version, recvKeys.get(EncryptionLevel.ONE_RTT));
    }

    private void advanceSendKeys(String trigger) {
        oneRttSendSecret = PacketProtectionKeys.nextSecret(hkdf, oneRttSendSecret, version);
        sendKeys.put(EncryptionLevel.ONE_RTT, PacketProtectionKeys.update(hkdf, oneRttSendSecret, version,
                sendKeys.get(EncryptionLevel.ONE_RTT)));
        sendKeyPhase = !sendKeyPhase;
        firstPnOfCurrentSendPhase = sendPacketNumber[EncryptionLevel.ONE_RTT.ordinal()];
        currentSendKeysAcknowledged = false;
        keyUpdateCount++;
        if (qlog != null) {
            qlogKeyUpdated(!isServer, "1rtt", keyUpdateCount, trigger);
        }
    }

    // The first packet of the next key phase has just been decrypted with
    // the keys prepared for it: they become current, the old ones are kept
    // a little longer for packets reordered across the update (RFC 9001
    // section 6.5), and, if the peer initiated this update, this endpoint's
    // own send keys move on too before anything else is sent (section 6.2).
    private void completeRecvKeyUpdate(long packetNumber) {
        previousRecvKeys = recvKeys.get(EncryptionLevel.ONE_RTT);
        previousRecvKeysDiscardAtMillis = nowMillis() + 3 * probeTimeoutMillis();
        recvKeys.put(EncryptionLevel.ONE_RTT, nextRecvKeys);
        oneRttRecvSecret = nextRecvSecret;
        recvKeyPhase = !recvKeyPhase;
        lowestPnOfCurrentRecvPhase = packetNumber;
        prepareNextRecvKeys();
        boolean peerInitiated = sendKeyPhase != recvKeyPhase;
        if (peerInitiated) {
            advanceSendKeys(QlogEvents.TRIGGER_REMOTE_UPDATE);
        }
        if (qlog != null) {
            qlogKeyUpdated(isServer, "1rtt", keyUpdateCount,
                    peerInitiated ? QlogEvents.TRIGGER_REMOTE_UPDATE : QlogEvents.TRIGGER_LOCAL_UPDATE);
        }
    }

    // RFC 9002 section 6.2.1, without the backoff: enough for how long
    // the previous generation's receive keys are worth keeping.
    private long probeTimeoutMillis() {
        RttEstimator rtt = lossDetector.getRttEstimator();
        return microsToMillisCeil(rtt.getSmoothedRtt() + Math.max(4 * rtt.getRttVar(), LossDetector.K_GRANULARITY)
                + peerMaxAckDelayMicros());
    }

    // Picks the 1-RTT keys for a packet whose Key Phase bit is known, or
    // null to drop it. updatedRecvKeys[0] is set when the packet claims
    // the next phase, so that a successful decryption completes the update.
    private PacketProtectionKeys selectOneRttRecvKeys(boolean phase, long packetNumber, boolean[] updatedRecvKeys) {
        if (previousRecvKeys != null && nowMillis() > previousRecvKeysDiscardAtMillis) {
            previousRecvKeys = null;
        }
        if (phase == recvKeyPhase) {
            return recvKeys.get(EncryptionLevel.ONE_RTT);
        }
        if (previousRecvKeys != null && lowestPnOfCurrentRecvPhase >= 0 && packetNumber < lowestPnOfCurrentRecvPhase) {
            return previousRecvKeys;
        }
        if (nextRecvKeys == null) {
            return null;
        }
        updatedRecvKeys[0] = true;
        return nextRecvKeys;
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

    // RFC 9000 section 13.2.1: the delayed-ACK timer for the application
    // data space expired; the ACK is owed whatever has arrived since.
    void onAckTimeout() {
        ackTimerHandle = null;
        if (closed) {
            return;
        }
        int app = EncryptionLevel.ONE_RTT.ordinal();
        if (ackElicitingUnacked[app] > 0) {
            ackOwed[app] = true;
            flush();
        }
    }

    // RFC 9000 section 13.2.1, run once a whole datagram has been
    // processed: decide which spaces now owe an ACK, and arm the timer
    // that bounds the delay of the rest.
    private void scheduleAcks() {
        int initial = EncryptionLevel.INITIAL.ordinal();
        int handshake = EncryptionLevel.HANDSHAKE.ordinal();
        int app = EncryptionLevel.ONE_RTT.ordinal();
        // Initial and Handshake packets are acknowledged immediately
        if (ackElicitingUnacked[initial] > 0) {
            ackOwed[initial] = true;
        }
        if (ackElicitingUnacked[handshake] > 0) {
            ackOwed[handshake] = true;
        }
        if (ackElicitingUnacked[app] == 0) {
            return;
        }
        if (ackImmediate[app] || ackElicitingUnacked[app] > ackElicitingThreshold) {
            ackOwed[app] = true;
            return;
        }
        if (ackTimerHandle == null) {
            long remainingNanos = maxAckDelayNanos() - (nowNanos() - firstUnackedNanos[app]);
            long delay = Math.max(0, (remainingNanos + 999999L) / 1000000L);
            ackTimerHandle = engine.scheduleTimer(delay, new Runnable() {
                @Override
                public void run() {
                    onAckTimeout();
                }
            });
        }
    }

    // draft-ietf-quic-ack-frequency section 4: a Reordering Threshold of 0
    // never calls for an immediate ACK; N calls for one when the packet is
    // N or more below the largest received, or opens a gap of N or more
    // (1 is the RFC 9000 section 13.2.1 behaviour of any gap).
    private boolean reorderingCallsForAck(long packetNumber, long previousLargest) {
        if (reorderingThreshold == 0) {
            return false;
        }
        long below = previousLargest - packetNumber;
        long gap = packetNumber - previousLargest - 1;
        return below >= reorderingThreshold || gap >= reorderingThreshold;
    }

    // The longest an ack-eliciting packet may wait for its ACK, in nanoseconds
    private long maxAckDelayNanos() {
        long micros = requestedMaxAckDelayMicros >= 0
                ? requestedMaxAckDelayMicros : localTransportParameters.getMaxAckDelay() * 1000L;
        return micros * 1000L;
    }

    /**
     * Returns a monotonic time in microseconds, plus the test-only offset,
     * for loss detection and RTT estimation. Only differences are meaningful.
     */
    private long nowMicros() {
        return nowNanos() / 1000L;
    }

    /**
     * Returns a monotonic time in nanoseconds, plus the test-only offset.
     * Only differences are meaningful.
     */
    private long nowNanos() {
        long base = (frozenNanos != 0) ? frozenNanos : System.nanoTime();
        return base + clockOffsetMillis * 1000000L + clockOffsetNanos;
    }

    /** Returns the current time in milliseconds, plus the test-only offset. */
    private long nowMillis() {
        long base = (frozenNanos != 0) ? frozenNanos / 1000000L : System.currentTimeMillis();
        return base + clockOffsetMillis + clockOffsetNanos / 1000000L;
    }

}
