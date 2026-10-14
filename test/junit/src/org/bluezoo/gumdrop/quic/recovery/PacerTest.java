/*
 * PacerTest.java
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

import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link Pacer}'s token bucket and the pacing rate
 * {@link LossDetector} derives from RFC 9002 section 7.7, with
 * hand-computed expected values. Rates are bytes per microsecond.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class PacerTest {

    // 12000-byte window over a 100 ms RTT: 0.12 bytes/us, i.e. 1200 bytes per 10 ms.
    private static final double RATE = 0.12;

    @Test
    public void testBurstIsAllowedUpToTheBucketSize() {
        Pacer pacer = new Pacer(12000);
        for (int i = 0; i < 10; i++) {
            assertEquals(0, pacer.delayMicros(1200, 1000, RATE));
            pacer.onPacketSent(1200, 1000, RATE);
        }
        // The bucket is empty: one more datagram needs 1200 / 0.12 = 10000 us.
        assertEquals(10000, pacer.delayMicros(1200, 1000, RATE));
    }

    @Test
    public void testCreditAccruesFromTheTimeOfTheCheckNotTheScheduledTime() {
        Pacer pacer = new Pacer(12000);
        pacer.onPacketSent(12000, 1000, RATE);
        // Checked 4000 us later: 480 bytes of credit, 720 short, 6000 us to wait.
        assertEquals(6000, pacer.delayMicros(1200, 5000, RATE));
        // A late check is not penalised: 10000 us gives exactly one datagram.
        assertEquals(0, pacer.delayMicros(1200, 11000, RATE));
    }

    @Test
    public void testCreditNeverExceedsTheBurstCapAtLowRates() {
        Pacer pacer = new Pacer(12000);
        pacer.onPacketSent(12000, 1000, RATE);
        // Idle for a whole second at 0.12 bytes/us: still only the 12000-byte burst.
        for (int i = 0; i < 10; i++) {
            pacer.onPacketSent(1200, 1000001, RATE);
        }
        assertEquals(10000, pacer.delayMicros(1200, 1000001, RATE));
    }

    @Test
    public void testOneTimerTickOfCreditIsAllowedAtHighRates() {
        // 1 Gbit/s is 125 bytes/us: a millisecond timer that could release
        // only the 12000-byte burst per tick would cap sending at 96 Mbit/s.
        double rate = 125.0;
        Pacer pacer = new Pacer(12000);
        pacer.onPacketSent(12000, 1000, rate);
        assertTrue(pacer.delayMicros(1200, 1000, rate) > 0);
        // One tick later there is a millisecond of credit: 125000 bytes, 104 datagrams.
        int sent = 0;
        while (pacer.delayMicros(1200, 2000, rate) == 0 && sent < 200) {
            pacer.onPacketSent(1200, 2000, rate);
            sent++;
        }
        assertEquals(104, sent);
        // ...but never more than that, however long the idle period.
        sent = 0;
        while (pacer.delayMicros(1200, 5000000, rate) == 0 && sent < 200) {
            pacer.onPacketSent(1200, 5000000, rate);
            sent++;
        }
        assertEquals(104, sent);
    }

    @Test
    public void testClockMayBeNegative() {
        // System.nanoTime has an arbitrary origin and may be negative.
        Pacer pacer = new Pacer(12000);
        pacer.onPacketSent(12000, -5000000, RATE);
        assertEquals(10000, pacer.delayMicros(1200, -5000000, RATE));
        assertEquals(0, pacer.delayMicros(1200, -4990000, RATE));
    }

    @Test
    public void testAnInfiniteRateNeverDelays() {
        Pacer pacer = new Pacer(12000);
        for (int i = 0; i < 100; i++) {
            pacer.onPacketSent(1200, 1000, Double.POSITIVE_INFINITY);
        }
        assertEquals(0, pacer.delayMicros(1200, 1000, Double.POSITIVE_INFINITY));
    }

    @Test
    public void testRateIsOnePointTwoFiveWindowsPerRttInCongestionAvoidance() {
        // window 12000, ssthresh 6000 (so not slow start), RTT 100000 us
        assertEquals(1.25 * 12000 / 100000, Pacer.rate(12000, 6000, 100000), 1e-12);
    }

    @Test
    public void testRateIsTwoWindowsPerRttInSlowStart() {
        assertEquals(2.0 * 12000 / 100000, Pacer.rate(12000, Long.MAX_VALUE, 100000), 1e-12);
    }

    @Test
    public void testLossDetectorDoesNotPaceBeforeAnRttSample() {
        LossDetector detector = new LossDetector(1200);
        for (int i = 0; i < 50; i++) {
            detector.onPacketSent(EncryptionLevel.ONE_RTT, i, 1000, true, true, 1200);
        }
        assertEquals(0, detector.getPacingDelayMicros(1200, 1000));
    }

    @Test
    public void testLossDetectorPacesAfterAnRttSampleAndBurstIsBounded() {
        LossDetector detector = new LossDetector(1200); // window 12000
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 0, 1000, true, true, 1200);
        detector.onAckReceived(EncryptionLevel.ONE_RTT, 0, 0, new long[][] { { 0, 0 } }, 25000, 101000, true);
        // Slow start, RTT 100 ms: 2 * (window) / RTT. Ten datagrams fit the
        // initial burst, after which the sender must wait.
        long now = 101000;
        int sent = 0;
        while (detector.getPacingDelayMicros(1200, now) == 0 && sent < 100) {
            detector.onPacketSent(EncryptionLevel.ONE_RTT, 1 + sent, now, true, true, 1200);
            sent++;
        }
        assertTrue("burst " + sent, sent >= 9 && sent <= 12);
        assertTrue(detector.getPacingDelayMicros(1200, now) > 0);
    }
}
