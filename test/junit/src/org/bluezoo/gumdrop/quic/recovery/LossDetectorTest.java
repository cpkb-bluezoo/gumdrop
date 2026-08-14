/*
 * LossDetectorTest.java
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
public class LossDetectorTest {

    private static void assertContainsPacketNumber(List<SentPacket> packets, long packetNumber) {
        for (SentPacket packet : packets) {
            if (packet.getPacketNumber() == packetNumber) {
                return;
            }
        }
        throw new AssertionError("Expected packet number " + packetNumber + " in " + packets);
    }

    @Test
    public void testOnPacketSentTracksBytesInFlight() {
        LossDetector detector = new LossDetector(1200);
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 0, 100, true, true, 500);
        assertEquals(500, detector.getCongestionController().getBytesInFlight());
    }

    @Test
    public void testAckAcknowledgesPacketAndTakesRttSample() {
        LossDetector detector = new LossDetector(1200);
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 0, 100, true, true, 500);

        LossDetector.AckResult result = detector.onAckReceived(EncryptionLevel.ONE_RTT, 0, 0,
                new long[][] { { 0, 0 } }, 25, 150, true);

        assertEquals(1, result.getNewlyAcked().size());
        assertEquals(0, result.getNewlyAcked().get(0).getPacketNumber());
        assertTrue(result.getNewlyLost().isEmpty());
        assertEquals(50, detector.getRttEstimator().getLatestRtt());
        assertEquals(0, detector.getCongestionController().getBytesInFlight());
    }

    /**
     * Three packets sent together, a fourth sent later; only the fourth
     * is acknowledged. The gap between it and packet 0 exactly meets
     * {@link LossDetector#K_PACKET_THRESHOLD} (3), while all four
     * packets are recent enough that time-threshold loss detection does
     * not also fire -- isolating packet-threshold behaviour.
     */
    @Test
    public void testPacketThresholdLossDetection() {
        LossDetector detector = new LossDetector(1200);
        // Prime the shared RTT estimator via the Initial space, so the
        // Application Data space's own first ACK doesn't also have to
        // absorb the "first sample" special case.
        detector.onPacketSent(EncryptionLevel.INITIAL, 0, 0, true, true, 50);
        detector.onAckReceived(EncryptionLevel.INITIAL, 0, 0, new long[][] { { 0, 0 } }, 25, 200, true);

        detector.onPacketSent(EncryptionLevel.ONE_RTT, 0, 1000, true, true, 100);
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 1, 1000, true, true, 100);
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 2, 1000, true, true, 100);
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 3, 1005, true, true, 100);

        LossDetector.AckResult result = detector.onAckReceived(EncryptionLevel.ONE_RTT, 3, 0,
                new long[][] { { 3, 3 } }, 25, 1010, true);

        assertEquals(1, result.getNewlyAcked().size());
        assertEquals(3, result.getNewlyAcked().get(0).getPacketNumber());
        // largestAcked(3) >= packetNumber(0) + kPacketThreshold(3) -> lost.
        // Packets 1 and 2 don't meet the threshold (3 >= 1+3=4 and 3 >= 2+3=5 are both false)
        // and were sent too recently for time-threshold loss to also catch them.
        assertEquals(1, result.getNewlyLost().size());
        assertContainsPacketNumber(result.getNewlyLost(), 0);
    }

    /**
     * Two packets sent together, a third sent much later after enough
     * elapsed time that time-threshold loss detection fires for the
     * first two, while the gap between them and the third (only 2, one
     * short of {@link LossDetector#K_PACKET_THRESHOLD}) is too small
     * for packet-threshold loss to also fire -- isolating
     * time-threshold behaviour.
     */
    @Test
    public void testTimeThresholdLossDetection() {
        LossDetector detector = new LossDetector(1200);
        detector.onPacketSent(EncryptionLevel.INITIAL, 0, 0, true, true, 50);
        detector.onAckReceived(EncryptionLevel.INITIAL, 0, 0, new long[][] { { 0, 0 } }, 25, 50, true);
        // First (and so far only) RTT sample: 50ms -> smoothedRtt=50, rttvar=25, minRtt=50.

        detector.onPacketSent(EncryptionLevel.ONE_RTT, 0, 1000, true, true, 100);
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 1, 1000, true, true, 100);
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 2, 1300, true, true, 100);

        // Packet 2's own round trip is a normal ~50ms, keeping the RTT
        // estimate stable, but packets 0/1 were sent 300ms before packet
        // 2 -- long enough to exceed the time-reordering window.
        LossDetector.AckResult result = detector.onAckReceived(EncryptionLevel.ONE_RTT, 2, 0,
                new long[][] { { 2, 2 } }, 25, 1350, true);

        assertEquals(1, result.getNewlyAcked().size());
        assertEquals(2, result.getNewlyAcked().get(0).getPacketNumber());
        // largestAcked(2) >= packetNumber + 3 is false for both (2>=3, 2>=4) --
        // packet-threshold alone would not have caught either.
        assertEquals(2, result.getNewlyLost().size());
        assertContainsPacketNumber(result.getNewlyLost(), 0);
        assertContainsPacketNumber(result.getNewlyLost(), 1);
    }

    @Test
    public void testNoTimeoutWhenNothingAckElicitingInFlight() {
        LossDetector detector = new LossDetector(1200);
        long timeout = detector.getLossDetectionTimeout(false, true, true, 25, 1000);
        assertEquals(LossDetector.NO_TIMEOUT, timeout);
    }

    @Test
    public void testPtoTimeoutComputationAndBackoff() {
        LossDetector detector = new LossDetector(1200); // smoothedRtt=333, rttvar=166 (no sample yet)
        detector.setHandshakeConfirmed(true);
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 0, 1000, true, true, 100);

        // duration = (333 + max(4*166, 1)) * 2^0 = 997; +maxAckDelay(25)*2^0 = 1022
        // timeout = timeOfLastAckEliciting(1000) + 1022 = 2022
        long firstTimeout = detector.getLossDetectionTimeout(false, true, true, 25, 1000);
        assertEquals(2022, firstTimeout);

        LossDetector.TimeoutResult result = detector.onLossDetectionTimeout(true, true, 25, 2022);
        assertTrue(result.getNewlyLost().isEmpty());
        assertEquals(EncryptionLevel.ONE_RTT, result.getProbeSpace());

        // pto_count is now 1: duration doubles, as does the max_ack_delay term.
        // duration = 997 * 2 = 1994; + 25*2 = 50 -> 2044; timeout = 1000 + 2044 = 3044
        long secondTimeout = detector.getLossDetectionTimeout(false, true, true, 25, 2022);
        assertEquals(3044, secondTimeout);
    }

    @Test
    public void testDiscardPacketNumberSpaceClearsStateAndBytesInFlight() {
        LossDetector detector = new LossDetector(1200);
        detector.onPacketSent(EncryptionLevel.INITIAL, 0, 1000, true, true, 300);
        assertEquals(300, detector.getCongestionController().getBytesInFlight());

        detector.discardPacketNumberSpace(EncryptionLevel.INITIAL);

        assertEquals(0, detector.getCongestionController().getBytesInFlight());
        // Nothing left in flight in that space, so a subsequent ACK there acknowledges nothing.
        LossDetector.AckResult result = detector.onAckReceived(EncryptionLevel.INITIAL, 0, 0,
                new long[][] { { 0, 0 } }, 25, 1100, true);
        assertTrue(result.getNewlyAcked().isEmpty());
    }
}
