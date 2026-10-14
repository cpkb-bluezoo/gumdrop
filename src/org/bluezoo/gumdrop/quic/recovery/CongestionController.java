/*
 * CongestionController.java
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

/**
 * Congestion control: NewReno (RFC 9002 section 7, Appendix B) or CUBIC
 * (RFC 9438), chosen at construction. Both share slow start, the recovery
 * period and persistent congestion's minimum-window reset (RFC 9002
 * section 7.6, triggered by {@link LossDetector} via {@link
 * #onPersistentCongestion()}); they differ in how far the window drops on
 * loss and how it grows in congestion avoidance. ECN processing (RFC 9002
 * section 7.1/7.7) is not implemented; see the package documentation for
 * why.
 *
 * <p>Three states, exactly as RFC 9002 section 7.3 describes: slow
 * start ({@code congestionWindow < ssthresh}, exponential growth on
 * every acknowledgment), recovery (immediately after a congestion
 * event, until a packet sent during recovery is itself acknowledged),
 * and congestion avoidance. {@link #onCongestionEvent} moves from either
 * slow start or congestion avoidance into recovery; {@link
 * #onPacketAcked} moves out of recovery once a recovery-period packet is
 * acknowledged.
 *
 * <p>In congestion avoidance NewReno adds at most one maximum datagram
 * size per window acknowledged. CUBIC follows {@code W_cubic(t) = C (t -
 * K)^3 + W_max} (RFC 9438 section 4.2) from the window it was reduced to,
 * through a plateau at the window where the loss happened ({@code
 * W_max}), and on into probing beyond it, with {@code C = 0.4} segments
 * per second cubed; while that curve would grow more slowly than Reno
 * would on the same path, the Reno-friendly estimate of section 4.3 is
 * used instead. A loss before the window is back at {@code W_max} lowers
 * {@code W_max} further (fast convergence, section 4.7).
 *
 * <p>Not thread-safe: one instance per connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LossDetector
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-7">RFC 9002 section 7</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9438">RFC 9438</a>
 */
public final class CongestionController {

    /** RFC 9002 section 7.3.2: NewReno halves the window on entering recovery. */
    private static final double NEW_RENO_BETA = 0.5;

    /** RFC 9438 section 4.6: CUBIC's multiplicative decrease factor. */
    private static final double CUBIC_BETA = 0.7;

    /** RFC 9438 section 5: the cubic constant, in segments per second cubed. */
    private static final double CUBIC_C = 0.4;

    /** RFC 9438 section 4.3: Reno-friendly growth per round trip, in segments. */
    private static final double CUBIC_ALPHA = 3 * (1 - CUBIC_BETA) / (1 + CUBIC_BETA);

    /** RFC 9002 section 7.2: the floor on the initial window, regardless of {@code maxDatagramSize}. */
    private static final long INITIAL_WINDOW_FLOOR = 14720;

    private final CongestionAlgorithm algorithm;
    private final int maxDatagramSize;
    private final long minimumWindow;

    private long congestionWindow;
    private long bytesInFlight;
    private long ssthresh = Long.MAX_VALUE;

    /**
     * 0 means "not in a recovery period". Otherwise, the time recovery
     * was entered -- a packet sent at or before this time cannot end
     * recovery when acknowledged (RFC 9002 Appendix B.5's {@code InCongestionRecovery}).
     */
    private long congestionRecoveryStartTime;

    // CUBIC state (RFC 9438 section 4). An epoch is one stretch of
    // congestion avoidance, starting with the first acknowledgement after
    // a loss (or after leaving slow start) and ending at the next loss.
    // Times have an arbitrary origin, so the epoch is flagged, not zeroed.
    private long cubicWindowMax;       // W_max, bytes; 0 until the first loss
    private long cubicWindowPrior;     // the window at the last loss, bytes
    private boolean cubicEpochStarted;
    private long cubicEpochStartMicros;
    private double cubicK;             // seconds from epoch start to W_max
    private double cubicWindowEstimate; // W_est, bytes

    /**
     * Creates a NewReno congestion controller with the RFC 9002 section
     * 7.2 initial window.
     *
     * @param maxDatagramSize the sender's current maximum UDP payload
     *                        size, excluding UDP/IP overhead
     */
    public CongestionController(int maxDatagramSize) {
        this(maxDatagramSize, CongestionAlgorithm.NEW_RENO);
    }

    /**
     * Creates a congestion controller with the RFC 9002 section 7.2
     * initial window.
     *
     * @param maxDatagramSize the sender's current maximum UDP payload
     *                        size, excluding UDP/IP overhead
     * @param algorithm the algorithm to run
     */
    public CongestionController(int maxDatagramSize, CongestionAlgorithm algorithm) {
        this.algorithm = algorithm;
        this.maxDatagramSize = maxDatagramSize;
        this.minimumWindow = 2L * maxDatagramSize;
        this.congestionWindow = initialWindow();
    }

    private long initialWindow() {
        return Math.min(10L * maxDatagramSize, Math.max(2L * maxDatagramSize, INITIAL_WINDOW_FLOOR));
    }

    /**
     * Returns the algorithm this controller runs.
     *
     * @return the algorithm
     */
    public CongestionAlgorithm getAlgorithm() {
        return algorithm;
    }

    /**
     * Returns the factor the window is multiplied by on a congestion
     * event: 0.5 for NewReno (RFC 9002 section 7.3.2), 0.7 for CUBIC
     * (RFC 9438 section 4.6).
     *
     * @return the loss reduction factor
     */
    public double getLossReductionFactor() {
        return algorithm == CongestionAlgorithm.CUBIC ? CUBIC_BETA : NEW_RENO_BETA;
    }

    /**
     * Returns whether {@code bytes} more may be sent without exceeding
     * the congestion window.
     *
     * @param bytes the number of bytes that would be sent
     * @return true if sending would not exceed the congestion window
     */
    public boolean canSend(int bytes) {
        return bytesInFlight + bytes <= congestionWindow;
    }

    /**
     * Records that a packet counting toward bytes in flight was sent
     * (RFC 9002 Appendix B.4's {@code OnPacketSentCC}).
     *
     * @param sentBytes the number of bytes sent
     */
    public void onPacketSent(int sentBytes) {
        bytesInFlight += sentBytes;
    }

    /**
     * Removes bytes from bytes in flight without treating it as an
     * acknowledgment or loss (RFC 9002 Appendix B.9's
     * {@code RemoveFromBytesInFlight}), for packets whose packet number
     * space was discarded (e.g. Initial/Handshake keys dropped) while
     * still unacknowledged.
     *
     * @param sentBytes the number of bytes to remove
     */
    public void removeFromBytesInFlight(int sentBytes) {
        bytesInFlight -= sentBytes;
    }

    /**
     * Records that an in-flight packet was acknowledged (RFC 9002
     * Appendix B.5's {@code OnPacketAcked}). The caller is responsible
     * for only calling this for packets that were in flight.
     *
     * @param sentTimeMicros the time the acknowledged packet was sent
     * @param sentBytes the number of bytes the acknowledged packet sent
     * @param appOrFlowControlLimited true if the sender was not sending
     *                                as much as the congestion window
     *                                would allow (application- or
     *                                flow-control-limited) -- the window
     *                                does not grow in that case
     * @param nowMicros the current time
     * @param smoothedRttMicros the current smoothed round-trip time,
     *                          which CUBIC's window function looks one
     *                          round trip ahead by (RFC 9438 section 4.2)
     */
    public void onPacketAcked(long sentTimeMicros, int sentBytes, boolean appOrFlowControlLimited,
            long nowMicros, long smoothedRttMicros) {
        bytesInFlight -= sentBytes;
        if (appOrFlowControlLimited) {
            return;
        }
        if (inCongestionRecovery(sentTimeMicros)) {
            return;
        }
        if (congestionWindow < ssthresh) {
            congestionWindow += sentBytes; // slow start
            return;
        }
        if (algorithm == CongestionAlgorithm.CUBIC) {
            cubicCongestionAvoidance(sentBytes, nowMicros, smoothedRttMicros);
        } else {
            congestionWindow += ((long) maxDatagramSize * sentBytes) / congestionWindow; // congestion avoidance
        }
    }

    // RFC 9438 sections 4.2 to 4.5, in bytes rather than segments.
    private void cubicCongestionAvoidance(int sentBytes, long nowMicros, long smoothedRttMicros) {
        if (!cubicEpochStarted) {
            cubicEpochStarted = true;
            cubicEpochStartMicros = nowMicros;
            cubicWindowEstimate = congestionWindow;
            if (congestionWindow < cubicWindowMax) {
                // Section 4.2: K = cbrt((W_max - cwnd_epoch) / C), in seconds.
                cubicK = Math.cbrt((cubicWindowMax - congestionWindow) / (CUBIC_C * maxDatagramSize));
            } else {
                cubicK = 0;
                cubicWindowMax = congestionWindow;
            }
        }
        // Section 4.3: the Reno-friendly estimate grows alpha segments per
        // window acknowledged, and a full segment once it has regained the
        // window the loss happened at.
        double alpha = cubicWindowEstimate >= cubicWindowPrior ? 1.0 : CUBIC_ALPHA;
        cubicWindowEstimate += alpha * maxDatagramSize * sentBytes / congestionWindow;

        // Section 4.2: the window the cubic function says we should have one round trip from now.
        double t = (nowMicros - cubicEpochStartMicros + smoothedRttMicros) / 1_000_000.0;
        double offset = t - cubicK;
        double wCubic = CUBIC_C * maxDatagramSize * offset * offset * offset + cubicWindowMax;

        if (wCubic < cubicWindowEstimate) {
            // Section 4.3: Reno-friendly region.
            congestionWindow = Math.max(congestionWindow, (long) cubicWindowEstimate);
            return;
        }
        // Sections 4.4 and 4.5: head for the target, but no more than half
        // again the current window per round trip.
        double target = Math.min(Math.max(wCubic, congestionWindow), 1.5 * congestionWindow);
        congestionWindow += (long) ((target - congestionWindow) * sentBytes / congestionWindow);
    }

    /**
     * Records a new congestion event -- packet loss, or an increase in
     * the peer-reported ECN-CE count (not implemented here, so in
     * practice only loss) -- entering a recovery period if not already
     * in one (RFC 9002 Appendix B.6's {@code OnCongestionEvent}).
     *
     * @param sentTimeMicros the send time of the packet whose loss (or
     *                       ECN marking) triggered this event
     * @param nowMicros the current time
     */
    public void onCongestionEvent(long sentTimeMicros, long nowMicros) {
        if (inCongestionRecovery(sentTimeMicros)) {
            return;
        }
        congestionRecoveryStartTime = nowMicros;
        if (algorithm == CongestionAlgorithm.CUBIC) {
            // RFC 9438 section 4.7, fast convergence: a loss before the
            // window regained the last W_max means the available capacity
            // has shrunk, so aim lower than the window the loss happened at.
            cubicWindowMax = congestionWindow < cubicWindowMax
                    ? (long) (congestionWindow * (1 + CUBIC_BETA) / 2) : congestionWindow;
            cubicWindowPrior = congestionWindow;
            cubicEpochStarted = false;
        }
        ssthresh = (long) (congestionWindow * getLossReductionFactor());
        congestionWindow = Math.max(ssthresh, minimumWindow);
    }

    private boolean inCongestionRecovery(long sentTimeMicros) {
        return sentTimeMicros <= congestionRecoveryStartTime;
    }

    /**
     * Records that {@link LossDetector} has declared persistent
     * congestion (RFC 9002 section 7.6.2): the congestion window drops
     * straight to the minimum, bypassing the usual reduction {@link
     * #onCongestionEvent} already applied for the same loss -- and
     * {@code congestion_recovery_start_time} is cleared (RFC 9002
     * Appendix B.8) so the connection can leave recovery and re-enter
     * slow start once it starts sending again, rather than staying
     * artificially capped by a now-stale recovery period. CUBIC's epoch
     * ends too: congestion avoidance, when slow start next reaches it,
     * starts a fresh curve from that lower window.
     */
    public void onPersistentCongestion() {
        congestionWindow = minimumWindow;
        congestionRecoveryStartTime = 0;
        cubicEpochStarted = false;
    }

    /**
     * Resets congestion control state back to a freshly-connected
     * endpoint's starting point (RFC 9000 section 9.4): on confirming a
     * peer's ownership of a new network path (connection migration), the
     * window, slow-start threshold, recovery state and CUBIC history
     * measured against the old path no longer describe the new one's
     * capacity. {@code bytesInFlight} is deliberately left untouched --
     * it reflects data genuinely still outstanding, regardless of which
     * path carried it.
     */
    public void reset() {
        congestionWindow = initialWindow();
        ssthresh = Long.MAX_VALUE;
        congestionRecoveryStartTime = 0;
        cubicWindowMax = 0;
        cubicWindowPrior = 0;
        cubicEpochStarted = false;
    }

    /**
     * Returns the current congestion window.
     *
     * @return the congestion window, in bytes
     */
    public long getCongestionWindow() {
        return congestionWindow;
    }

    /**
     * Returns the current bytes in flight.
     *
     * @return the bytes in flight
     */
    public long getBytesInFlight() {
        return bytesInFlight;
    }

    /**
     * Returns the current slow start threshold.
     *
     * @return the slow start threshold, in bytes, or {@link Long#MAX_VALUE}
     *         if still infinite (no congestion event yet)
     */
    public long getSsthresh() {
        return ssthresh;
    }

    // CUBIC's W_max (RFC 9438 section 4.2), for tests: the window the
    // cubic curve plateaus at, or 0 before any loss.
    long getCubicWindowMax() {
        return cubicWindowMax;
    }
}
