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

    // RFC 9002 section 7.6.2 / Appendix B.8: two ack-eliciting packets,
    // sent after the first RTT sample, both declared lost together with
    // consecutive packet numbers (so nothing between them could have
    // been acknowledged -- see LossDetector's own documentation on this
    // approximation) and a send-time gap exceeding section 7.6.1's
    // duration -- must drop the congestion window straight to the
    // minimum, not just halve it.
    @Test
    public void testPersistentCongestionDropsWindowToMinimum() {
        LossDetector detector = new LossDetector(1200); // window 12000, minimum 2400
        detector.onPacketSent(EncryptionLevel.INITIAL, 0, 0, true, true, 50);
        detector.onAckReceived(EncryptionLevel.INITIAL, 0, 0, new long[][] { { 0, 0 } }, 25, 200, true);
        // RTT sample: 200ms -> smoothedRtt=200, rttvar=100; firstRttSampleTime=200.
        // Persistent congestion duration = (200 + max(4*100,1) + 25) * 3 = 1875ms.

        detector.onPacketSent(EncryptionLevel.ONE_RTT, 0, 1000, true, true, 100);
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 1, 2876, true, true, 100); // gap 1876ms > 1875ms
        // Non-ack-eliciting "vehicle" packet: its ack advances largestAcked
        // far enough for packet-threshold loss to declare 0 and 1 lost,
        // without itself contributing a second RTT sample that would
        // perturb the duration threshold computed above.
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 4, 3000, false, true, 50);

        detector.onAckReceived(EncryptionLevel.ONE_RTT, 4, 0, new long[][] { { 4, 4 } }, 25, 3100, true);
        // largestAcked(4) >= packetNumber+3 for both 0 and 1 -> both lost together, consecutively numbered.
        // onPersistentCongestion clears recoveryStartTime to 0, so the
        // vehicle packet's own (already in-flight) ack -- processed right
        // after, per RFC 9002 Appendix A.7's OnPacketsLost-then-OnPacketsAcked
        // order -- is no longer "in recovery" and grows the window by its
        // 50 bytes via ordinary slow start: 2400 + 50 = 2450.

        assertEquals(2450, detector.getCongestionController().getCongestionWindow());
    }

    @Test
    public void testPersistentCongestionNotDetectedWhenAckedPacketBreaksTheRun() {
        LossDetector detector = new LossDetector(1200); // window 12000
        detector.onPacketSent(EncryptionLevel.INITIAL, 0, 0, true, true, 50);
        detector.onAckReceived(EncryptionLevel.INITIAL, 0, 0, new long[][] { { 0, 0 } }, 25, 200, true);

        detector.onPacketSent(EncryptionLevel.ONE_RTT, 0, 1000, true, true, 100);
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 1, 1500, false, true, 100); // acked below, breaking the run
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 2, 2876, true, true, 100); // same gap as the positive test
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 5, 3000, false, true, 50); // packet-threshold vehicle

        // Acknowledge packet 1 and the vehicle together, both
        // non-ack-eliciting so no RTT sample is taken here either.
        detector.onAckReceived(EncryptionLevel.ONE_RTT, 5, 0, new long[][] { { 1, 1 }, { 5, 5 } }, 25, 3100, true);
        // largestAcked(5) declares both 0 (packet numbers 0+3<=5) and 2
        // (2+3<=5) lost together -- but packet 1, sent between them, was
        // acknowledged, not lost, breaking packet-number consecutiveness.

        // Ordinary congestion response (halving) still applies -- only
        // the *persistent*-congestion drop-to-minimum is suppressed.
        assertEquals(6000, detector.getCongestionController().getCongestionWindow());
    }

    @Test
    public void testPersistentCongestionNotDetectedWhenDurationTooShort() {
        LossDetector detector = new LossDetector(1200);
        detector.onPacketSent(EncryptionLevel.INITIAL, 0, 0, true, true, 50);
        detector.onAckReceived(EncryptionLevel.INITIAL, 0, 0, new long[][] { { 0, 0 } }, 25, 200, true);
        // Duration threshold is 1875ms (see the positive test above).

        detector.onPacketSent(EncryptionLevel.ONE_RTT, 0, 1000, true, true, 100);
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 1, 2000, true, true, 100); // gap only 1000ms < 1875ms
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 4, 3000, false, true, 50);

        detector.onAckReceived(EncryptionLevel.ONE_RTT, 4, 0, new long[][] { { 4, 4 } }, 25, 3100, true);

        assertEquals(6000, detector.getCongestionController().getCongestionWindow());
    }

    /**
     * Regression test: before this fix, a time-threshold loss detected
     * via the loss-detection *timer* path ({@link
     * LossDetector#onLossDetectionTimeout}) never reached the congestion
     * controller at all -- only losses detected while processing an ACK
     * ({@link LossDetector#onAckReceived}) did. Both paths call the same
     * {@code DetectAndRemoveLostPackets}; RFC 9002 Appendix A.9's {@code
     * OnLossDetectionTimeout} pseudocode calls {@code OnPacketsLost} for
     * this exact case.
     */
    @Test
    public void testLossDetectionTimeoutNotifiesCongestionControllerOfLoss() {
        LossDetector detector = new LossDetector(1200); // window 12000
        detector.onPacketSent(EncryptionLevel.INITIAL, 0, 0, true, true, 50);
        detector.onAckReceived(EncryptionLevel.INITIAL, 0, 0, new long[][] { { 0, 0 } }, 25, 200, true);
        // smoothedRtt=200, rttvar=100 -> time-threshold lossDelay = 9/8*200 = 225

        detector.onPacketSent(EncryptionLevel.ONE_RTT, 0, 1000, true, true, 100);
        detector.onPacketSent(EncryptionLevel.ONE_RTT, 1, 1050, false, true, 50);
        // Ack packet 1 alone (non-ack-eliciting): packet 0 isn't lost yet
        // by either threshold at this point, but this schedules a
        // time-threshold loss deadline for it (lossTime = 1000+225=1225).
        // Packet 1 itself was in flight, so acking it also grows the
        // still-pristine window by its 50 bytes via ordinary slow start
        // (no congestion event has happened yet): 12000 + 50 = 12050.
        detector.onAckReceived(EncryptionLevel.ONE_RTT, 1, 0, new long[][] { { 1, 1 } }, 25, 1100, true);

        LossDetector.TimeoutResult result = detector.onLossDetectionTimeout(true, true, 25, 1300);
        assertEquals(1, result.getNewlyLost().size());
        // Halved from the grown 12050, not the original 12000: 6025.
        assertEquals(6025, detector.getCongestionController().getCongestionWindow());
    }
}
