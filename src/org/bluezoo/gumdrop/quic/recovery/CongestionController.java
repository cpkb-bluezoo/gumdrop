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
 * NewReno congestion control (RFC 9002 section 7, Appendix B), minus ECN
 * processing (section 7.1/7.7 -- an optional additional congestion
 * signal) and minus persistent congestion's minimum-window reset
 * (section 7.6 -- {@link LossDetector} never triggers it, since
 * persistent congestion detection is not implemented).
 *
 * <p>Three states, exactly as RFC 9002 section 7.3 describes: slow
 * start ({@code congestionWindow < ssthresh}, exponential growth on
 * every acknowledgment), recovery (immediately after a congestion
 * event, until a packet sent during recovery is itself acknowledged),
 * and congestion avoidance (additive growth, at most one maximum
 * datagram size per window acknowledged). {@link #onCongestionEvent}
 * moves from either slow start or congestion avoidance into recovery;
 * {@link #onPacketAcked} moves out of recovery once a
 * recovery-period packet is acknowledged.
 *
 * <p>Not thread-safe: one instance per connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LossDetector
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-7">RFC 9002 section 7</a>
 */
public final class CongestionController {

    /** RFC 9002 section 7.3.2: the window is halved on entering recovery. */
    public static final double K_LOSS_REDUCTION_FACTOR = 0.5;

    /** RFC 9002 section 7.2: the floor on the initial window, regardless of {@code maxDatagramSize}. */
    private static final long INITIAL_WINDOW_FLOOR = 14720;

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

    /**
     * Creates a congestion controller with the RFC 9002 section 7.2
     * initial window.
     *
     * @param maxDatagramSize the sender's current maximum UDP payload
     *                        size, excluding UDP/IP overhead
     */
    public CongestionController(int maxDatagramSize) {
        this.maxDatagramSize = maxDatagramSize;
        this.minimumWindow = 2L * maxDatagramSize;
        this.congestionWindow = Math.min(10L * maxDatagramSize, Math.max(2L * maxDatagramSize, INITIAL_WINDOW_FLOOR));
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
     * @param sentTimeMillis the time the acknowledged packet was sent
     * @param sentBytes the number of bytes the acknowledged packet sent
     * @param appOrFlowControlLimited true if the sender was not sending
     *                                as much as the congestion window
     *                                would allow (application- or
     *                                flow-control-limited) -- the window
     *                                does not grow in that case
     */
    public void onPacketAcked(long sentTimeMillis, int sentBytes, boolean appOrFlowControlLimited) {
        bytesInFlight -= sentBytes;
        if (appOrFlowControlLimited) {
            return;
        }
        if (inCongestionRecovery(sentTimeMillis)) {
            return;
        }
        if (congestionWindow < ssthresh) {
            congestionWindow += sentBytes; // slow start
        } else {
            congestionWindow += ((long) maxDatagramSize * sentBytes) / congestionWindow; // congestion avoidance
        }
    }

    /**
     * Records a new congestion event -- packet loss, or an increase in
     * the peer-reported ECN-CE count (not implemented here, so in
     * practice only loss) -- entering a recovery period if not already
     * in one (RFC 9002 Appendix B.6's {@code OnCongestionEvent}).
     *
     * @param sentTimeMillis the send time of the packet whose loss (or
     *                       ECN marking) triggered this event
     * @param nowMillis the current time
     */
    public void onCongestionEvent(long sentTimeMillis, long nowMillis) {
        if (inCongestionRecovery(sentTimeMillis)) {
            return;
        }
        congestionRecoveryStartTime = nowMillis;
        ssthresh = (long) (congestionWindow * K_LOSS_REDUCTION_FACTOR);
        congestionWindow = Math.max(ssthresh, minimumWindow);
    }

    private boolean inCongestionRecovery(long sentTimeMillis) {
        return sentTimeMillis <= congestionRecoveryStartTime;
    }

    /**
     * Resets congestion control state back to a freshly-connected
     * endpoint's starting point (RFC 9000 section 9.4): on confirming a
     * peer's ownership of a new network path (connection migration), the
     * window, slow-start threshold, and recovery state measured against
     * the old path no longer describe the new one's capacity. {@code
     * bytesInFlight} is deliberately left untouched -- it reflects data
     * genuinely still outstanding, regardless of which path carried it.
     */
    public void reset() {
        congestionWindow = Math.min(10L * maxDatagramSize, Math.max(2L * maxDatagramSize, INITIAL_WINDOW_FLOOR));
        ssthresh = Long.MAX_VALUE;
        congestionRecoveryStartTime = 0;
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
}
