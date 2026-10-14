/*
 * HyStartTest.java
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
 * Verifies HyStart++ (RFC 9406) in {@link CongestionController}'s slow
 * start: a round trip whose minimum RTT has risen by at least an eighth
 * (between 4 and 16 ms) over the previous round's, seen in at least
 * eight samples, moves slow start into Conservative Slow Start, where
 * growth is a quarter of normal; five such rounds end slow start without
 * a loss, while an RTT that falls back to the baseline returns to normal
 * slow start.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9406">RFC 9406</a>
 */
public class HyStartTest {

    private static final int MSS = 1200;
    private static final long BASE_RTT = 100_000;

    private long now = 1_000_000;
    // When the packets acknowledged in the current round were sent: as
    // the previous round's acknowledgements arrived, so the first
    // acknowledgement of each round ends the previous one.
    private long sent = 900_000;

    // One round trip of acknowledgements in slow start: `samples` RTT
    // samples at `rtt`, and a window's worth of bytes acknowledged.
    private void round(CongestionController cc, long rtt, int samples) {
        long window = cc.getCongestionWindow();
        int packets = (int) (window / MSS);
        for (int i = 0; i < packets; i++) {
            if (i < samples) {
                cc.onRttSample(rtt);
            }
            cc.onPacketSent(MSS);
            cc.onPacketAcked(sent, MSS, false, now, rtt);
        }
        sent = now;
        now += rtt;
    }

    @Test
    public void testSlowStartDoublesWhileRttIsFlat() {
        CongestionController cc = new CongestionController(MSS);
        for (int i = 0; i < 6; i++) {
            long before = cc.getCongestionWindow();
            round(cc, BASE_RTT, 10);
            assertEquals(2 * before, cc.getCongestionWindow());
        }
        assertEquals(Long.MAX_VALUE, cc.getSsthresh());
    }

    @Test
    public void testRisingRttEntersConservativeSlowStartThenEndsIt() {
        CongestionController cc = new CongestionController(MSS);
        round(cc, BASE_RTT, 10);
        round(cc, BASE_RTT, 10);
        // Threshold: clamp(100 ms / 8, 4 ms, 16 ms) = 12.5 ms. A rise to
        // 120 ms is detected within this round, and growth drops to a
        // quarter for the rest of it.
        long before = cc.getCongestionWindow();
        round(cc, BASE_RTT + 20_000, 10);
        long after = cc.getCongestionWindow();
        assertTrue("grew " + (after - before) + " of " + before, after - before < before);
        assertEquals(Long.MAX_VALUE, cc.getSsthresh());
        // Four more conservative rounds at the raised RTT: each grows by
        // about a quarter, and the fifth ends slow start.
        for (int i = 0; i < 4; i++) {
            before = cc.getCongestionWindow();
            round(cc, BASE_RTT + 20_000, 10);
            after = cc.getCongestionWindow();
            assertTrue("conservative round grew " + (after - before) + " of " + before,
                    after - before <= before / 4 + MSS);
        }
        round(cc, BASE_RTT + 20_000, 10);
        assertTrue("ssthresh " + cc.getSsthresh(), cc.getSsthresh() < Long.MAX_VALUE);
        assertTrue(cc.getSsthresh() <= cc.getCongestionWindow());
    }

    @Test
    public void testRttFallingBackResumesNormalSlowStart() {
        CongestionController cc = new CongestionController(MSS);
        round(cc, BASE_RTT, 10);
        round(cc, BASE_RTT, 10);
        round(cc, BASE_RTT + 20_000, 10); // conservative
        round(cc, BASE_RTT, 10);          // below the baseline: spurious
        long before = cc.getCongestionWindow();
        round(cc, BASE_RTT, 10);
        assertEquals(2 * before, cc.getCongestionWindow());
        assertEquals(Long.MAX_VALUE, cc.getSsthresh());
    }

    @Test
    public void testFewerThanEightSamplesNeverTriggers() {
        CongestionController cc = new CongestionController(MSS);
        round(cc, BASE_RTT, 7);
        round(cc, BASE_RTT, 7);
        long before = cc.getCongestionWindow();
        round(cc, BASE_RTT + 50_000, 7);
        assertEquals(2 * before, cc.getCongestionWindow());
    }

    @Test
    public void testCubicStartsItsCurveFromTheHyStartExitWindow() {
        CongestionController cc = new CongestionController(MSS, CongestionAlgorithm.CUBIC);
        round(cc, BASE_RTT, 10);
        round(cc, BASE_RTT, 10);
        for (int i = 0; i < 6; i++) {
            round(cc, BASE_RTT + 20_000, 10);
        }
        long exitWindow = cc.getSsthresh();
        assertTrue(exitWindow < Long.MAX_VALUE);
        // Congestion avoidance from there: no loss, so W_max is the window
        // slow start ended at, and the curve sets off upward from it.
        assertEquals(exitWindow, cc.getCubicWindowMax());
        assertTrue(cc.getCongestionWindow() >= exitWindow);
        long before = cc.getCongestionWindow();
        round(cc, BASE_RTT + 20_000, 10);
        assertTrue(cc.getCongestionWindow() > before);
    }
}
