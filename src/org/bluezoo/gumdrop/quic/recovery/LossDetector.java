/*
 * LossDetector.java
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

package org.bluezoo.gumdrop.quic.recovery;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;

/**
 * Per-connection loss detection (RFC 9002 section 6, Appendix A),
 * driving one {@link RttEstimator} and one {@link CongestionController}.
 *
 * <p>Tracks sent packets per packet number space, using
 * {@link EncryptionLevel} directly as the space discriminator -- its
 * three values ({@code INITIAL}/{@code HANDSHAKE}/{@code ONE_RTT}) are
 * exactly RFC 9002's three packet number spaces (Initial/Handshake/
 * ApplicationData), so no separate enumeration is needed.
 *
 * <p>Like every class in this package, time is supplied explicitly by
 * the caller as milliseconds rather than read from a system clock, and
 * PTO/loss timeouts are returned as plain deadlines -- this class never
 * schedules a real timer itself. Not yet wired to a live connection:
 * anti-amplification (RFC 9002 Appendix A.6) and persistent congestion
 * (section 7.6) are not implemented; see the package documentation.
 *
 * <p>Not thread-safe: one instance per connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#appendix-A">RFC 9002 Appendix A</a>
 */
public final class LossDetector {

    /** RFC 9002 section 6.1.1: reordering tolerance in packets before packet-threshold loss. */
    public static final int K_PACKET_THRESHOLD = 3;

    /** RFC 9002 section 6.1.2: reordering tolerance in time, as an RTT multiplier. */
    public static final double K_TIME_THRESHOLD = 9.0 / 8.0;

    /** RFC 9002 Appendix A.2: timer granularity. */
    public static final long K_GRANULARITY = 1;

    /** RFC 9002 section 7.6.1: multiplier on the RTT-based term to get the persistent congestion duration. */
    public static final int K_PERSISTENT_CONGESTION_THRESHOLD = 3;

    /** Sentinel meaning "no packet number space loss/PTO timeout is currently needed". */
    public static final long NO_TIMEOUT = -1;

    /** The result of {@link #onAckReceived}. */
    public static final class AckResult {

        private final List<SentPacket> newlyAcked;
        private final List<SentPacket> newlyLost;

        AckResult(List<SentPacket> newlyAcked, List<SentPacket> newlyLost) {
            this.newlyAcked = newlyAcked;
            this.newlyLost = newlyLost;
        }

        /**
         * Returns the packets newly acknowledged by this ACK frame.
         *
         * @return the newly acknowledged packets, possibly empty
         */
        public List<SentPacket> getNewlyAcked() {
            return newlyAcked;
        }

        /**
         * Returns the packets newly declared lost while processing this ACK frame.
         *
         * @return the newly lost packets, possibly empty
         */
        public List<SentPacket> getNewlyLost() {
            return newlyLost;
        }
    }

    /** The result of {@link #onLossDetectionTimeout}. */
    public static final class TimeoutResult {

        private final List<SentPacket> newlyLost;
        private final EncryptionLevel lossSpace;
        private final EncryptionLevel probeSpace;

        TimeoutResult(List<SentPacket> newlyLost, EncryptionLevel lossSpace, EncryptionLevel probeSpace) {
            this.newlyLost = newlyLost;
            this.lossSpace = lossSpace;
            this.probeSpace = probeSpace;
        }

        /**
         * Returns the packets declared lost by time-threshold loss
         * detection, if that is why the timer fired.
         *
         * @return the newly lost packets, empty if this was a PTO instead
         */
        public List<SentPacket> getNewlyLost() {
            return newlyLost;
        }

        /**
         * Returns the packet number space {@link #getNewlyLost} belongs
         * to, if the timer fired because of time-threshold loss detection.
         *
         * @return the space the loss was detected in, or {@code null} if
         *         this was a Probe Timeout instead
         */
        public EncryptionLevel getLossSpace() {
            return lossSpace;
        }

        /**
         * Returns the packet number space a probe should be sent in, if
         * the timer fired because of a Probe Timeout.
         *
         * @return the space to probe in, or {@code null} if this was
         *         time-threshold loss detection instead
         */
        public EncryptionLevel getProbeSpace() {
            return probeSpace;
        }
    }

    private final Map<EncryptionLevel, List<SentPacket>> sentPackets = new EnumMap<EncryptionLevel, List<SentPacket>>(
            EncryptionLevel.class);
    private final Map<EncryptionLevel, Long> largestAckedPacket = new EnumMap<EncryptionLevel, Long>(
            EncryptionLevel.class);
    private final Map<EncryptionLevel, Long> timeOfLastAckEliciting = new EnumMap<EncryptionLevel, Long>(
            EncryptionLevel.class);
    private final Map<EncryptionLevel, Long> lossTime = new EnumMap<EncryptionLevel, Long>(EncryptionLevel.class);

    private final RttEstimator rttEstimator = new RttEstimator();
    private final CongestionController congestionController;

    private int ptoCount;
    private boolean handshakeConfirmed;

    /**
     * 0 until the first RTT sample is taken (RFC 9002 section 7.6.2 --
     * persistent congestion only considers packets sent after this
     * point, since {@code first_rtt_sample} is itself the earliest
     * moment the RTT-based persistent-congestion duration in section
     * 7.6.1 is even meaningful). Set once, connection-wide, never reset.
     */
    private long firstRttSampleTime;

    /**
     * Creates a loss detector.
     *
     * @param maxDatagramSize the sender's current maximum UDP payload
     *                        size, passed through to a new {@link CongestionController}
     */
    public LossDetector(int maxDatagramSize) {
        this.congestionController = new CongestionController(maxDatagramSize);
        for (EncryptionLevel level : EncryptionLevel.values()) {
            sentPackets.put(level, new ArrayList<SentPacket>());
            largestAckedPacket.put(level, -1L);
            timeOfLastAckEliciting.put(level, 0L);
            lossTime.put(level, 0L);
        }
    }

    /**
     * Returns the RTT estimator this detector maintains.
     *
     * @return the RTT estimator
     */
    public RttEstimator getRttEstimator() {
        return rttEstimator;
    }

    /**
     * Returns the congestion controller this detector maintains.
     *
     * @return the congestion controller
     */
    public CongestionController getCongestionController() {
        return congestionController;
    }

    /**
     * Records that the handshake is confirmed (RFC 9001 section 4.1.2),
     * affecting ACK delay clamping ({@link RttEstimator#onRttSample})
     * and PTO computation's treatment of the ApplicationData space.
     *
     * @param handshakeConfirmed the new state
     */
    public void setHandshakeConfirmed(boolean handshakeConfirmed) {
        this.handshakeConfirmed = handshakeConfirmed;
    }

    /**
     * Records that a packet was sent (RFC 9002 Appendix A.5's {@code OnPacketSent}).
     *
     * @param level the packet number space
     * @param packetNumber the packet number
     * @param nowMillis the current time
     * @param ackEliciting true if an acknowledgment is expected
     * @param inFlight true if this packet counts toward bytes in flight
     * @param sentBytes the number of bytes sent
     */
    public void onPacketSent(EncryptionLevel level, long packetNumber, long nowMillis,
            boolean ackEliciting, boolean inFlight, int sentBytes) {
        SentPacket packet = new SentPacket(packetNumber, nowMillis, ackEliciting, inFlight, sentBytes);
        sentPackets.get(level).add(packet);
        if (inFlight) {
            if (ackEliciting) {
                timeOfLastAckEliciting.put(level, nowMillis);
            }
            congestionController.onPacketSent(sentBytes);
        }
    }

    /**
     * Processes a received ACK frame (RFC 9002 Appendix A.7's {@code OnAckReceived}).
     *
     * @param level the packet number space the ACK frame arrived in
     * @param largestAcked the ACK frame's Largest Acknowledged field
     * @param ackDelayMillis the ACK frame's ACK Delay field, converted to milliseconds
     * @param ackRanges every acknowledged packet number range in the
     *                  frame, as {@code {low, high}} pairs -- see
     *                  {@code QuicFrameHandler#ackFrameReceived}
     * @param maxAckDelayMillis the peer's {@code max_ack_delay} transport parameter
     * @param nowMillis the current time
     * @param peerAddressValidated true once {@code PeerCompletedAddressValidation()}
     *                             (RFC 9002 Appendix A.8) holds, resetting the PTO count
     * @return the newly acknowledged and newly lost packets
     */
    public AckResult onAckReceived(EncryptionLevel level, long largestAcked, long ackDelayMillis,
            long[][] ackRanges, long maxAckDelayMillis, long nowMillis, boolean peerAddressValidated) {
        long currentLargest = largestAckedPacket.get(level);
        largestAckedPacket.put(level, currentLargest < 0 ? largestAcked : Math.max(currentLargest, largestAcked));

        List<SentPacket> newlyAcked = detectAndRemoveAckedPackets(level, ackRanges);
        if (newlyAcked.isEmpty()) {
            return new AckResult(newlyAcked, new ArrayList<SentPacket>());
        }

        SentPacket largestNewlyAcked = newlyAcked.get(newlyAcked.size() - 1);
        if (largestNewlyAcked.getPacketNumber() == largestAcked && includesAckEliciting(newlyAcked)) {
            rttEstimator.onRttSample(nowMillis - largestNewlyAcked.getTimeSentMillis(), ackDelayMillis,
                    maxAckDelayMillis, handshakeConfirmed);
            if (firstRttSampleTime == 0) {
                firstRttSampleTime = nowMillis;
            }
        }

        List<SentPacket> newlyLost = detectAndRemoveLostPackets(level, nowMillis);
        onPacketsLost(newlyLost, maxAckDelayMillis, nowMillis);

        for (SentPacket acked : newlyAcked) {
            if (acked.isInFlight()) {
                congestionController.onPacketAcked(acked.getTimeSentMillis(), acked.getSentBytes(), false);
            }
        }

        if (peerAddressValidated) {
            ptoCount = 0;
        }
        return new AckResult(newlyAcked, newlyLost);
    }

    // RFC 9002 Appendix A.7's DetectAndRemoveAckedPackets: every sent
    // packet whose number falls in any of ackRanges is newly acked.
    // Returned in ascending packet-number order (sentPackets is kept in
    // send order, which is ascending by construction).
    private List<SentPacket> detectAndRemoveAckedPackets(EncryptionLevel level, long[][] ackRanges) {
        List<SentPacket> newlyAcked = new ArrayList<SentPacket>();
        Iterator<SentPacket> it = sentPackets.get(level).iterator();
        while (it.hasNext()) {
            SentPacket packet = it.next();
            if (isInAnyRange(packet.getPacketNumber(), ackRanges)) {
                newlyAcked.add(packet);
                it.remove();
            }
        }
        return newlyAcked;
    }

    private static boolean isInAnyRange(long packetNumber, long[][] ranges) {
        for (long[] range : ranges) {
            if (packetNumber >= range[0] && packetNumber <= range[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean includesAckEliciting(List<SentPacket> packets) {
        for (SentPacket packet : packets) {
            if (packet.isAckEliciting()) {
                return true;
            }
        }
        return false;
    }

    // RFC 9002 Appendix A.10's DetectAndRemoveLostPackets.
    private List<SentPacket> detectAndRemoveLostPackets(EncryptionLevel level, long nowMillis) {
        long largestAcked = largestAckedPacket.get(level);
        lossTime.put(level, 0L);
        List<SentPacket> lost = new ArrayList<SentPacket>();

        long lossDelay = (long) (K_TIME_THRESHOLD * Math.max(rttEstimator.getLatestRtt(), rttEstimator.getSmoothedRtt()));
        lossDelay = Math.max(lossDelay, K_GRANULARITY);
        long lostSendTime = nowMillis - lossDelay;

        Iterator<SentPacket> it = sentPackets.get(level).iterator();
        while (it.hasNext()) {
            SentPacket packet = it.next();
            if (packet.getPacketNumber() > largestAcked) {
                continue;
            }
            if (packet.getTimeSentMillis() <= lostSendTime || largestAcked >= packet.getPacketNumber() + K_PACKET_THRESHOLD) {
                it.remove();
                lost.add(packet);
            } else {
                long candidateLossTime = packet.getTimeSentMillis() + lossDelay;
                long currentLossTime = lossTime.get(level);
                lossTime.put(level, currentLossTime == 0 ? candidateLossTime : Math.min(currentLossTime, candidateLossTime));
            }
        }
        return lost;
    }

    // RFC 9002 Appendix B.8's OnPacketsLost: notifies the congestion
    // controller of the loss (shared by both callers of
    // detectAndRemoveLostPackets -- onAckReceived and
    // onLossDetectionTimeout -- so a timer-driven time-threshold loss
    // shrinks the congestion window exactly like an ACK-driven one does,
    // which onLossDetectionTimeout previously skipped entirely), then
    // checks whether that loss also constitutes persistent congestion.
    private void onPacketsLost(List<SentPacket> lost, long maxAckDelayMillis, long nowMillis) {
        if (lost.isEmpty()) {
            return;
        }
        long sentTimeOfLastLoss = 0;
        for (SentPacket packet : lost) {
            if (packet.isInFlight()) {
                sentTimeOfLastLoss = Math.max(sentTimeOfLastLoss, packet.getTimeSentMillis());
            }
        }
        if (sentTimeOfLastLoss != 0) {
            congestionController.onCongestionEvent(sentTimeOfLastLoss, nowMillis);
        }

        if (firstRttSampleTime == 0) {
            return;
        }
        if (isInPersistentCongestion(lost, maxAckDelayMillis)) {
            congestionController.onPersistentCongestion();
        }
    }

    // RFC 9002 section 7.6.2: persistent congestion requires two
    // ack-eliciting packets, both sent after the first RTT sample, that
    // are declared lost with the duration between their send times
    // exceeding section 7.6.1's threshold and nothing sent between them
    // ever acknowledged.
    //
    // The "nothing acknowledged between them" check is approximated
    // here, conservatively, as a maximal run of *consecutive* packet
    // numbers within `lost` that are all lost together: since
    // sentPackets only ever holds still-outstanding packets (an
    // acknowledged one is removed the moment detectAndRemoveAckedPackets
    // sees it, well before loss detection runs), a gap in packet-number
    // continuity within a batch of otherwise-lost packets can only be
    // explained by an acknowledgment having removed the missing packet
    // number at some point -- a still-pending, not-yet-lost packet would
    // still be present and wouldn't create a gap. This is strictly more
    // conservative than the RFC's literal text (which only requires the
    // two *boundary* packets to be lost, not everything between them),
    // so this can only under-detect persistent congestion relative to a
    // fully cross-packet-number-space-aware implementation, never
    // over-detect -- an accepted simplification given persistent
    // congestion is itself an additional-safety-margin mechanism on top
    // of ordinary loss-based congestion response, not the primary signal.
    private boolean isInPersistentCongestion(List<SentPacket> lost, long maxAckDelayMillis) {
        long durationThreshold = (rttEstimator.getSmoothedRtt()
                + Math.max(4 * rttEstimator.getRttVar(), K_GRANULARITY) + maxAckDelayMillis)
                * K_PERSISTENT_CONGESTION_THRESHOLD;

        SentPacket runFirstAckEliciting = null;
        SentPacket previous = null;
        for (SentPacket packet : lost) {
            if (packet.getTimeSentMillis() <= firstRttSampleTime) {
                runFirstAckEliciting = null;
                previous = null;
                continue;
            }
            boolean continuesRun = previous != null && packet.getPacketNumber() == previous.getPacketNumber() + 1;
            if (!continuesRun) {
                runFirstAckEliciting = null;
            }
            if (packet.isAckEliciting()) {
                if (runFirstAckEliciting == null) {
                    runFirstAckEliciting = packet;
                } else if (packet.getTimeSentMillis() - runFirstAckEliciting.getTimeSentMillis() >= durationThreshold) {
                    return true;
                }
            }
            previous = packet;
        }
        return false;
    }

    /**
     * Computes when the loss detection timer should next fire (RFC 9002
     * Appendix A.8's {@code SetLossDetectionTimer}), for the caller to
     * arm via a real timer.
     *
     * @param serverAtAntiAmplificationLimit true if this endpoint is a
     *                                       server still limited by the
     *                                       RFC 9000 section 8.1
     *                                       anti-amplification limit
     *                                       (not otherwise tracked here)
     * @param peerAddressValidated true once {@code PeerCompletedAddressValidation()} holds
     * @param hasHandshakeKeys true once Handshake-level packet
     *                         protection keys have been derived --
     *                         determines which space an anti-deadlock
     *                         probe targets when nothing is in flight
     *                         and the peer's address is not yet validated
     * @param maxAckDelayMillis the peer's {@code max_ack_delay} transport parameter
     * @param nowMillis the current time
     * @return the absolute deadline, or {@link #NO_TIMEOUT} if no timer is needed
     */
    public long getLossDetectionTimeout(boolean serverAtAntiAmplificationLimit, boolean peerAddressValidated,
            boolean hasHandshakeKeys, long maxAckDelayMillis, long nowMillis) {
        long earliestLossTime = earliestLossTime();
        if (earliestLossTime != 0) {
            return earliestLossTime;
        }
        if (serverAtAntiAmplificationLimit) {
            return NO_TIMEOUT;
        }
        if (!hasAckElicitingInFlight() && peerAddressValidated) {
            return NO_TIMEOUT;
        }
        return (Long) ptoTimeAndSpace(peerAddressValidated, hasHandshakeKeys, maxAckDelayMillis, nowMillis)[0];
    }

    private long earliestLossTime() {
        long earliest = 0;
        for (EncryptionLevel level : EncryptionLevel.values()) {
            long candidate = lossTime.get(level);
            if (candidate != 0 && (earliest == 0 || candidate < earliest)) {
                earliest = candidate;
            }
        }
        return earliest;
    }

    private EncryptionLevel earliestLossSpace() {
        long earliest = 0;
        EncryptionLevel space = EncryptionLevel.INITIAL;
        for (EncryptionLevel level : EncryptionLevel.values()) {
            long candidate = lossTime.get(level);
            if (candidate != 0 && (earliest == 0 || candidate < earliest)) {
                earliest = candidate;
                space = level;
            }
        }
        return space;
    }

    private boolean hasAckElicitingInFlight() {
        for (EncryptionLevel level : EncryptionLevel.values()) {
            for (SentPacket packet : sentPackets.get(level)) {
                if (packet.isAckEliciting() && packet.isInFlight()) {
                    return true;
                }
            }
        }
        return false;
    }

    // RFC 9002 Appendix A.8's GetPtoTimeAndSpace, returned as {timeout,
    // space} since Java has no tuple type -- the caller only ever needs
    // the pair together (getLossDetectionTimeout for the timeout,
    // onLossDetectionTimeout for the space).
    private Object[] ptoTimeAndSpace(boolean peerAddressValidated, boolean hasHandshakeKeys,
            long maxAckDelayMillis, long nowMillis) {
        long duration = (rttEstimator.getSmoothedRtt() + Math.max(4 * rttEstimator.getRttVar(), K_GRANULARITY))
                * (1L << ptoCount);

        if (!hasAckElicitingInFlight()) {
            // Anti-deadlock PTO: only reachable before peerAddressValidated.
            EncryptionLevel space = hasHandshakeKeys ? EncryptionLevel.HANDSHAKE : EncryptionLevel.INITIAL;
            return new Object[] { nowMillis + duration, space };
        }

        long ptoTimeout = Long.MAX_VALUE;
        EncryptionLevel ptoSpace = EncryptionLevel.INITIAL;
        for (EncryptionLevel level : EncryptionLevel.values()) {
            if (!hasAckElicitingInFlight(level)) {
                continue;
            }
            long levelDuration = duration;
            if (level == EncryptionLevel.ONE_RTT) {
                if (!handshakeConfirmed) {
                    return new Object[] { ptoTimeout == Long.MAX_VALUE ? NO_TIMEOUT : ptoTimeout, ptoSpace };
                }
                levelDuration += maxAckDelayMillis * (1L << ptoCount);
            }
            long candidate = timeOfLastAckEliciting.get(level) + levelDuration;
            if (candidate < ptoTimeout) {
                ptoTimeout = candidate;
                ptoSpace = level;
            }
        }
        return new Object[] { ptoTimeout == Long.MAX_VALUE ? NO_TIMEOUT : ptoTimeout, ptoSpace };
    }

    private boolean hasAckElicitingInFlight(EncryptionLevel level) {
        for (SentPacket packet : sentPackets.get(level)) {
            if (packet.isAckEliciting() && packet.isInFlight()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handles loss detection timer expiry (RFC 9002 Appendix A.9's
     * {@code OnLossDetectionTimeout}).
     *
     * @param peerAddressValidated true once {@code PeerCompletedAddressValidation()} holds
     * @param hasHandshakeKeys true once Handshake-level packet
     *                         protection keys have been derived
     * @param maxAckDelayMillis the peer's {@code max_ack_delay} transport parameter
     * @param nowMillis the current time
     * @return either the packets lost by time-threshold detection, or
     *         (if nothing was lost) the packet number space a probe
     *         should be sent in
     */
    public TimeoutResult onLossDetectionTimeout(boolean peerAddressValidated, boolean hasHandshakeKeys,
            long maxAckDelayMillis, long nowMillis) {
        long earliestLossTime = earliestLossTime();
        if (earliestLossTime != 0) {
            EncryptionLevel space = earliestLossSpace();
            List<SentPacket> lost = detectAndRemoveLostPackets(space, nowMillis);
            onPacketsLost(lost, maxAckDelayMillis, nowMillis);
            return new TimeoutResult(lost, space, null);
        }

        Object[] ptoTimeAndSpace = ptoTimeAndSpace(peerAddressValidated, hasHandshakeKeys, maxAckDelayMillis, nowMillis);
        EncryptionLevel probeSpace = (EncryptionLevel) ptoTimeAndSpace[1];
        ptoCount++;
        return new TimeoutResult(new ArrayList<SentPacket>(), null, probeSpace);
    }

    /**
     * Discards all tracked state for a packet number space (RFC 9002
     * Appendix A.11), for when Initial or Handshake keys are dropped.
     *
     * @param level the packet number space being discarded (never
     *              {@link EncryptionLevel#ONE_RTT})
     */
    public void discardPacketNumberSpace(EncryptionLevel level) {
        for (SentPacket packet : sentPackets.get(level)) {
            if (packet.isInFlight()) {
                congestionController.removeFromBytesInFlight(packet.getSentBytes());
            }
        }
        sentPackets.get(level).clear();
        timeOfLastAckEliciting.put(level, 0L);
        lossTime.put(level, 0L);
        ptoCount = 0;
    }
}
