/*
 * CubicCongestionControllerTest.java
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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link CongestionController} in its CUBIC mode against RFC
 * 9438: multiplicative decrease by beta, the cubic window function
 * through its plateau at W_max, the Reno-friendly region, fast
 * convergence, and the resets. Windows are in bytes with a 1200-byte
 * maximum datagram, so 120000 bytes is 100 segments.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9438">RFC 9438</a>
 */
public class CubicCongestionControllerTest {

    private static final int MSS = 1200;
    private static final long RTT = 100_000; // 100 ms
    private static final double TOLERANCE = 0.08;

    private static CongestionController cubic() {
        return new CongestionController(MSS, CongestionAlgorithm.CUBIC);
    }

    // Grows the window to `bytes` in slow start.
    private static void growTo(CongestionController cc, long bytes, long now) {
        long acked = bytes - cc.getCongestionWindow();
        cc.onPacketSent((int) acked);
        cc.onPacketAcked(now - 1, (int) acked, false, now, RTT);
        assertEquals(bytes, cc.getCongestionWindow());
    }

    // One round trip of acknowledgements, a window's worth in full
    // segments, each sent a round trip ago. Returns the new time.
    private static long roundTrip(CongestionController cc, long now, long rtt) {
        long window = cc.getCongestionWindow();
        for (long acked = 0; acked + MSS <= window; acked += MSS) {
            cc.onPacketSent(MSS);
            cc.onPacketAcked(now - rtt, MSS, false, now, rtt);
        }
        return now + rtt;
    }

    private static void assertNear(String what, double expected, long actual) {
        assertTrue(what + ": expected about " + (long) expected + " but was " + actual,
                Math.abs(actual - expected) <= expected * TOLERANCE);
    }

    @Test
    public void testLossReducesWindowByBeta() {
        CongestionController cc = cubic();
        growTo(cc, 120000, 1000);
        cc.onCongestionEvent(500, 2000);
        // beta_cubic = 0.7 (RFC 9438 section 4.6)
        assertEquals(84000, cc.getSsthresh());
        assertEquals(84000, cc.getCongestionWindow());
        assertEquals(0.7, cc.getLossReductionFactor(), 0.0);
        assertEquals(CongestionAlgorithm.CUBIC, cc.getAlgorithm());
    }

    @Test
    public void testNewRenoIsTheDefaultAndStillHalves() {
        CongestionController cc = new CongestionController(MSS);
        assertEquals(CongestionAlgorithm.NEW_RENO, cc.getAlgorithm());
        assertEquals(0.5, cc.getLossReductionFactor(), 0.0);
    }

    @Test
    public void testWindowFollowsTheCubicFunctionBackToWmaxAndBeyond() {
        CongestionController cc = cubic();
        growTo(cc, 120000, 1000);
        long now = 1_000_000;
        cc.onCongestionEvent(500, now); // W_max = 100 segments, window 70
        // K = cbrt((W_max - cwnd) / C) = cbrt(30 / 0.4) = 4.217 s
        double k = Math.cbrt(30 / 0.4);
        long epoch = now;
        while (now - epoch < (long) (k * 1_000_000)) {
            now = roundTrip(cc, now, RTT);
        }
        assertNear("window at t = K", 120000, cc.getCongestionWindow());
        while (now - epoch < (long) (2 * k * 1_000_000)) {
            now = roundTrip(cc, now, RTT);
        }
        // W_cubic(2K) = W_max + C * K^3 = 100 + 30 = 130 segments... in
        // the convex region the window overshoots W_max by what it lost.
        assertNear("window at t = 2K", 120000 + 36000, cc.getCongestionWindow());
    }

    @Test
    public void testRenoFriendlyRegionGrowsAtLeastLikeReno() {
        // A small window on a short path: the cubic function alone would
        // barely move in ten round trips, but RFC 9438 section 4.3 grows
        // the window like Reno (alpha_cubic = 3(1 - beta)/(1 + beta) = 0.53
        // segments per round trip) when that is faster.
        CongestionController cc = cubic();
        long rtt = 10_000;
        long now = 1_000_000;
        cc.onCongestionEvent(500, now); // W_max = 10 segments, window 7 (8400)
        assertEquals(8400, cc.getCongestionWindow());
        for (int i = 0; i < 10; i++) {
            now = roundTrip(cc, now, rtt);
        }
        // Cubic alone: W_max + C (0.1 - 1.957)^3 = 7.4 segments (8930 bytes).
        // Reno-friendly: 7 + 10 * 0.53 = 12.3 segments (14760 bytes).
        assertTrue("window " + cc.getCongestionWindow(), cc.getCongestionWindow() > 13000);
    }

    @Test
    public void testFastConvergenceLowersWmaxWhenLossComesBeforeReachingIt() {
        CongestionController cc = cubic();
        growTo(cc, 120000, 1000);
        cc.onCongestionEvent(500, 2000);
        assertEquals(120000, cc.getCubicWindowMax());
        // Grow a little, then lose again below the old W_max.
        long now = roundTrip(cc, 1_000_000, RTT);
        long window = cc.getCongestionWindow();
        assertTrue(window < 120000);
        cc.onCongestionEvent(now - 1, now + 1);
        // RFC 9438 section 4.7: W_max = cwnd (1 + beta) / 2 = 0.85 cwnd
        assertEquals((long) (window * 0.85), cc.getCubicWindowMax());
        assertEquals((long) (window * 0.7), cc.getCongestionWindow());
    }

    @Test
    public void testPersistentCongestionAndResetStartAFreshEpoch() {
        CongestionController cc = cubic();
        growTo(cc, 120000, 1000);
        cc.onCongestionEvent(500, 900_000);
        cc.onPersistentCongestion();
        assertEquals(2 * MSS, cc.getCongestionWindow());
        // Slow start again until ssthresh, then the cubic epoch restarts
        // from the new, lower window: no instant jump towards the old W_max.
        long now = roundTrip(cc, 1_000_000, RTT);
        assertEquals(4 * MSS, cc.getCongestionWindow());

        cc.reset();
        assertEquals(12000, cc.getCongestionWindow());
        assertEquals(Long.MAX_VALUE, cc.getSsthresh());
        assertEquals(0, cc.getCubicWindowMax());
    }

    @Test
    public void testLossDetectorBuildsTheRequestedController() {
        assertEquals(CongestionAlgorithm.CUBIC,
                new LossDetector(MSS, CongestionAlgorithm.CUBIC).getCongestionController().getAlgorithm());
        assertEquals(CongestionAlgorithm.NEW_RENO, new LossDetector(MSS).getCongestionController().getAlgorithm());
    }
}
