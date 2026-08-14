/*
 * CongestionControllerTest.java
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link CongestionController} against RFC 9002 section 7's
 * NewReno algorithm and Appendix B's pseudocode, with hand-computed
 * expected values. Note times are always non-zero: {@code sentTime <= 0}
 * would trivially satisfy {@code InCongestionRecovery}'s initial
 * {@code congestion_recovery_start_time = 0}, an RFC pseudocode quirk
 * that never arises with a real monotonic clock.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#appendix-B">RFC 9002 Appendix B</a>
 */
public class CongestionControllerTest {

    @Test
    public void testInitialWindowIsBoundedByFloorAndTenTimesDatagram() {
        // 10*1200=12000, 2*1200=2400, max(2400,14720)=14720, min(12000,14720)=12000
        assertEquals(12000, new CongestionController(1200).getCongestionWindow());
        // 10*1500=15000, 2*1500=3000, max(3000,14720)=14720, min(15000,14720)=14720
        assertEquals(14720, new CongestionController(1500).getCongestionWindow());
    }

    @Test
    public void testCanSendRespectsWindow() {
        CongestionController cc = new CongestionController(1200); // window 12000
        assertTrue(cc.canSend(12000));
        assertFalse(cc.canSend(12001));

        cc.onPacketSent(5000);
        assertTrue(cc.canSend(7000));
        assertFalse(cc.canSend(7001));
        assertEquals(5000, cc.getBytesInFlight());
    }

    @Test
    public void testSlowStartGrowsByAckedBytes() {
        CongestionController cc = new CongestionController(1200); // window 12000
        cc.onPacketSent(1000);
        cc.onPacketAcked(100, 1000, false);

        assertEquals(13000, cc.getCongestionWindow());
        assertEquals(0, cc.getBytesInFlight());
    }

    @Test
    public void testCongestionAvoidanceGrowsAdditively() {
        CongestionController cc = new CongestionController(1200); // window 12000
        cc.onCongestionEvent(100, 200); // ssthresh=6000, window=6000, recoveryStart=200

        cc.onPacketSent(1000);
        cc.onPacketAcked(300, 1000, false); // sentTime(300) > recoveryStart(200): not in recovery
        // window(6000) not < ssthresh(6000) -> congestion avoidance:
        // window += maxDatagramSize*sentBytes/window = 1200*1000/6000 = 200
        assertEquals(6200, cc.getCongestionWindow());
    }

    @Test
    public void testAckedPacketDuringRecoveryDoesNotGrowWindow() {
        CongestionController cc = new CongestionController(1200);
        cc.onPacketSent(1000);
        cc.onCongestionEvent(100, 200); // window=6000, recoveryStart=200

        cc.onPacketAcked(150, 1000, false); // sent before recovery started: still in recovery
        assertEquals(6000, cc.getCongestionWindow());
        assertEquals(0, cc.getBytesInFlight());
    }

    @Test
    public void testAppOrFlowControlLimitedDoesNotGrowWindow() {
        CongestionController cc = new CongestionController(1200); // window 12000
        cc.onPacketSent(1000);
        cc.onPacketAcked(100, 1000, true);

        assertEquals(12000, cc.getCongestionWindow());
        assertEquals(0, cc.getBytesInFlight());
    }

    @Test
    public void testCongestionEventHalvesWindowAndIgnoresRepeatsWithinRecovery() {
        CongestionController cc = new CongestionController(1200); // window 12000

        cc.onCongestionEvent(100, 200);
        assertEquals(6000, cc.getCongestionWindow());
        assertEquals(6000, cc.getSsthresh());

        // A second loss whose packet was sent before recovery started must not reduce further.
        cc.onCongestionEvent(150, 250);
        assertEquals(6000, cc.getCongestionWindow());

        // A loss sent after recovery started is a new congestion event.
        cc.onCongestionEvent(250, 300);
        assertEquals(3000, cc.getCongestionWindow());
        assertEquals(3000, cc.getSsthresh());
    }

    @Test
    public void testMinimumWindowFloor() {
        CongestionController cc = new CongestionController(1200); // minimum window = 2*1200 = 2400
        cc.onCongestionEvent(100, 200); // window=6000
        cc.onCongestionEvent(250, 300); // window=3000
        cc.onCongestionEvent(350, 400); // ssthresh=1500, floored to 2400
        assertEquals(2400, cc.getCongestionWindow());
    }
}
