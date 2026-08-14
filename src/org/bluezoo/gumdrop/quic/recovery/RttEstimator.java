/*
 * RttEstimator.java
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
 * Round-trip time estimation (RFC 9002 section 5), the exact algorithm
 * from the {@code UpdateRtt} pseudocode in Appendix A.7.
 *
 * <p>All times are milliseconds, supplied explicitly by the caller
 * rather than read from a system clock -- this class has no notion of
 * "now" of its own, which keeps it deterministically testable and
 * reusable regardless of which clock source the eventual owning
 * connection uses.
 *
 * <p>Not thread-safe: one instance per connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-5">RFC 9002 section 5</a>
 */
public final class RttEstimator {

    /** RFC 9002 section 6.2.2: the RTT assumed before any real sample exists. */
    public static final long K_INITIAL_RTT = 333;

    private long latestRtt;
    private long smoothedRtt;
    private long rttvar;
    private long minRtt;
    private boolean hasSample;

    /**
     * Creates an estimator with no RTT sample yet (RFC 9002 Appendix A.4).
     */
    public RttEstimator() {
        smoothedRtt = K_INITIAL_RTT;
        rttvar = K_INITIAL_RTT / 2;
    }

    /**
     * Records a new RTT sample and updates the smoothed estimate (RFC
     * 9002 Appendix A.7's {@code UpdateRtt}).
     *
     * @param latestRttMillis the RTT just measured for a newly
     *                        acknowledged, previously unacknowledged packet
     * @param ackDelayMillis the ACK Delay field from the acknowledging
     *                       ACK frame, already converted to milliseconds
     * @param maxAckDelayMillis the peer's {@code max_ack_delay} transport parameter
     * @param handshakeConfirmed true once the handshake is confirmed
     *                           (RFC 9001 section 4.1.2) -- until then,
     *                           {@code ackDelayMillis} is used unclamped
     */
    public void onRttSample(long latestRttMillis, long ackDelayMillis, long maxAckDelayMillis,
            boolean handshakeConfirmed) {
        this.latestRtt = latestRttMillis;

        if (!hasSample) {
            minRtt = latestRttMillis;
            smoothedRtt = latestRttMillis;
            rttvar = latestRttMillis / 2;
            hasSample = true;
            return;
        }

        minRtt = Math.min(minRtt, latestRttMillis);
        long ackDelay = handshakeConfirmed ? Math.min(ackDelayMillis, maxAckDelayMillis) : ackDelayMillis;

        long adjustedRtt = latestRttMillis;
        if (latestRttMillis >= minRtt + ackDelay) {
            adjustedRtt = latestRttMillis - ackDelay;
        }

        rttvar = (3 * rttvar + Math.abs(smoothedRtt - adjustedRtt)) / 4;
        smoothedRtt = (7 * smoothedRtt + adjustedRtt) / 8;
    }

    /**
     * Returns the most recent RTT sample.
     *
     * @return the latest RTT, in milliseconds
     */
    public long getLatestRtt() {
        return latestRtt;
    }

    /**
     * Returns the smoothed RTT estimate.
     *
     * @return the smoothed RTT, in milliseconds
     */
    public long getSmoothedRtt() {
        return smoothedRtt;
    }

    /**
     * Returns the RTT variation.
     *
     * @return the RTT variation, in milliseconds
     */
    public long getRttVar() {
        return rttvar;
    }

    /**
     * Returns the minimum RTT observed so far (0 if no sample yet).
     *
     * @return the minimum RTT, in milliseconds
     */
    public long getMinRtt() {
        return minRtt;
    }

    /**
     * Returns whether at least one RTT sample has been recorded.
     *
     * @return true if {@link #onRttSample} has been called at least once
     */
    public boolean hasRttSample() {
        return hasSample;
    }
}
