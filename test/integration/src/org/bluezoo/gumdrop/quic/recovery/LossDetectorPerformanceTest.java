/*
 * LossDetectorPerformanceTest.java
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

import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link LossDetector} against RFC 9002 Appendix A's reference
 * pseudocode, with hand-computed expected values for RTT/PTO timing and
 * scenarios specifically constructed to isolate packet-threshold (RFC
 * 9002 section 6.1.1) from time-threshold (section 6.1.2) loss
 * detection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#appendix-A">RFC 9002 Appendix A</a>
 */
/*
 * NOTE: wall-clock thresholds live here, not in the unit suite: unit tests must
 * be deterministic (CONTRIBUTING.md). Extracted from LossDetectorTest.
 */
public class LossDetectorPerformanceTest {

    private static void assertContainsPacketNumber(List<SentPacket> packets, long packetNumber) {
        for (SentPacket packet : packets) {
            if (packet.getPacketNumber() == packetNumber) {
                return;
            }
        }
        throw new AssertionError("Expected packet number " + packetNumber + " in " + packets);
    }















    // RFC 9002 section 7.6.2 / Appendix B.8: two ack-eliciting packets,
    // sent after the first RTT sample, both declared lost together with
    // consecutive packet numbers (so nothing between them could have
    // been acknowledged -- see LossDetector's own documentation on this
    // approximation) and a send-time gap exceeding section 7.6.1's
    // duration -- must drop the congestion window straight to the
    // minimum, not just halve it.








    /**
     * Regression test for issue #307: sent packets were tracked in a
     * plain {@code ArrayList} scanned linearly against every ACK range
     * on every received ACK, an O(unacked packets x ACK ranges) cost per
     * ACK. Sends a large number of packets, then repeatedly acknowledges
     * one at a time from the front -- the pattern of a connection that
     * keeps a large number of packets in flight while ACKs trickle in
     * individually -- and asserts the cumulative cost stays far below
     * what a linear-scan-per-ACK implementation would take.
     */
    @Test(timeout = 15000)
    public void testAckProcessingCostDoesNotScaleLinearlyWithInFlightPacketCount() {
        LossDetector detector = new LossDetector(1200);
        int packetCount = 200000;
        for (int i = 0; i < packetCount; i++) {
            detector.onPacketSent(EncryptionLevel.ONE_RTT, i, 1000, true, true, 100);
        }

        long start = System.nanoTime();
        for (int i = 0; i < 2000; i++) {
            detector.onAckReceived(EncryptionLevel.ONE_RTT, i, 0, new long[][] { { i, i } }, 25, 1000 + i, true);
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue("2000 ACKs against " + packetCount + " in-flight packets took " + elapsedMs
                + "ms -- expected a packet-number-indexed structure to keep this far below linear-scan cost",
                elapsedMs < 1000);
    }

    /**
     * Regression test for issue #307: {@code hasAckElicitingInFlight}
     * scanned every tracked packet linearly, and is called from the loss
     * detection timeout path on essentially every flush. Almost all
     * tracked packets here are non-ack-eliciting (e.g. ACK-only packets,
     * which still count toward bytes in flight but not toward this
     * check), so a linear scan must walk nearly the whole tracked set
     * before finding the one ack-eliciting packet at the end.
     */
    @Test(timeout = 15000)
    public void testHasAckElicitingInFlightCostDoesNotScaleLinearlyWithInFlightPacketCount() {
        LossDetector detector = new LossDetector(1200);
        int packetCount = 200000;
        for (int i = 0; i < packetCount; i++) {
            detector.onPacketSent(EncryptionLevel.ONE_RTT, i, 1000, false, true, 100);
        }
        detector.onPacketSent(EncryptionLevel.ONE_RTT, packetCount, 1000, true, true, 100);

        long start = System.nanoTime();
        for (int i = 0; i < 2000; i++) {
            detector.getLossDetectionTimeout(false, true, true, 25, 2000);
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue("2000 getLossDetectionTimeout calls against " + (packetCount + 1)
                + " in-flight packets took " + elapsedMs
                + "ms -- expected an incremental in-flight counter to keep this O(1) per call, not a scan",
                elapsedMs < 1000);
    }
}
