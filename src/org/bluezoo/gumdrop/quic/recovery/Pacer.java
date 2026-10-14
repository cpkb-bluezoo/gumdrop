/*
 * Pacer.java
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
 * Send pacing as RFC 9002 section 7.7 describes: a token bucket that
 * spreads the congestion window over the round trip instead of sending
 * all of it back to back.
 *
 * <p>Credit, in bytes, accrues at the pacing rate from the time of
 * each check, up to a cap: the burst size, or one timer tick's worth
 * of credit at the current rate, whichever is larger. Because credit
 * accrues from the time the check actually runs rather than the time
 * it was scheduled for, a timer that fires late or whose delay was
 * rounded up to a whole millisecond only makes the next burst larger,
 * never lowers the average rate. And because a tick's worth is always
 * allowed, a millisecond timer can sustain any rate: at 1 Gbit/s a tick
 * releases about 104 full datagrams. No timer finer than a millisecond
 * is needed.
 *
 * <p>Rates are in bytes per microsecond. {@link Double#POSITIVE_INFINITY}
 * means "do not pace".
 *
 * <p>Takes time explicitly, like the other classes in this package.
 * Not thread-safe: one instance per connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-7.7">RFC 9002 section 7.7</a>
 */
public final class Pacer {

    /** The pacing rate is this many windows per round trip in congestion avoidance (RFC 9002 section 7.7's N). */
    static final double CONGESTION_AVOIDANCE_GAIN = 1.25;

    /**
     * Slow start grows the window by up to a factor of two each round
     * trip, so pacing at the window rate would hold it back.
     */
    static final double SLOW_START_GAIN = 2.0;

    /**
     * The coarsest timer the owning connection relies on, in
     * microseconds: a tick's worth of credit at the pacing rate is
     * always allowed to accrue, so the timer never caps the rate.
     */
    static final long TIMER_GRANULARITY_MICROS = 1000;

    private final double burstBytes;
    private double credit;
    // Set on the first check; times have an arbitrary origin (they may
    // be negative), so no value can mean "not yet".
    private boolean started;
    private long lastUpdateMicros;

    /**
     * Creates a pacer whose bucket starts full.
     *
     * @param burstBytes the most that may be sent back to back; RFC 9002
     *                   section 7.7 suggests the initial congestion window
     */
    public Pacer(long burstBytes) {
        this.burstBytes = burstBytes;
        this.credit = burstBytes;
    }

    /**
     * Returns the pacing rate for the current congestion state.
     *
     * @param congestionWindow the congestion window, in bytes
     * @param ssthresh the slow start threshold, in bytes
     * @param smoothedRttMicros the smoothed round-trip time; zero or less
     *                          means no estimate and no pacing
     * @return bytes per microsecond, or {@link Double#POSITIVE_INFINITY}
     */
    public static double rate(long congestionWindow, long ssthresh, long smoothedRttMicros) {
        if (smoothedRttMicros <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        double gain = congestionWindow < ssthresh ? SLOW_START_GAIN : CONGESTION_AVOIDANCE_GAIN;
        return gain * congestionWindow / smoothedRttMicros;
    }

    /**
     * Returns how long to wait before {@code bytes} may be sent.
     *
     * @param bytes the size of the next send
     * @param nowMicros the current time
     * @param rate the pacing rate, bytes per microsecond
     * @return 0 if it may be sent now, otherwise the wait in microseconds
     */
    public long delayMicros(int bytes, long nowMicros, double rate) {
        refill(nowMicros, rate);
        if (credit >= bytes) {
            return 0;
        }
        return (long) Math.ceil((bytes - credit) / rate);
    }

    /**
     * Spends credit for a packet that was sent. Packets sent while the
     * bucket was empty (those that are not paced, such as probes) can
     * leave it in debt, which later sends repay by waiting; the debt is
     * bounded by one burst.
     *
     * @param bytes the size of the packet
     * @param nowMicros the current time
     * @param rate the pacing rate, bytes per microsecond
     */
    public void onPacketSent(int bytes, long nowMicros, double rate) {
        refill(nowMicros, rate);
        credit = Math.max(credit - bytes, -burstBytes);
    }

    private void refill(long nowMicros, double rate) {
        if (!started) {
            started = true;
            lastUpdateMicros = nowMicros;
            return;
        }
        long elapsed = nowMicros - lastUpdateMicros;
        if (elapsed <= 0) {
            return;
        }
        lastUpdateMicros = nowMicros;
        if (rate == Double.POSITIVE_INFINITY) {
            credit = burstBytes;
            return;
        }
        double cap = Math.max(burstBytes, rate * TIMER_GRANULARITY_MICROS);
        credit = Math.min(cap, credit + elapsed * rate);
    }
}
