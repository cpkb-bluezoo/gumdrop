/*
 * RttEstimatorTest.java
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
 * Verifies {@link RttEstimator} against RFC 9002 Appendix A.4/A.7's
 * {@code UpdateRtt} pseudocode, with hand-computed expected values for
 * every branch.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#appendix-A.7">RFC 9002 Appendix A.7</a>
 */
public class RttEstimatorTest {

    @Test
    public void testConstructorUsesInitialRtt() {
        RttEstimator estimator = new RttEstimator();
        assertFalse(estimator.hasRttSample());
        assertEquals(RttEstimator.K_INITIAL_RTT, estimator.getSmoothedRtt());
        assertEquals(RttEstimator.K_INITIAL_RTT / 2, estimator.getRttVar());
    }

    @Test
    public void testFirstSampleSetsAllFieldsDirectly() {
        RttEstimator estimator = new RttEstimator();
        estimator.onRttSample(100, 5, 25, false);

        assertTrue(estimator.hasRttSample());
        assertEquals(100, estimator.getLatestRtt());
        assertEquals(100, estimator.getSmoothedRtt());
        assertEquals(50, estimator.getRttVar());
        assertEquals(100, estimator.getMinRtt());
    }

    @Test
    public void testSecondSampleMatchesUpdateRttFormula() {
        RttEstimator estimator = new RttEstimator();
        estimator.onRttSample(100, 5, 25, false); // smoothedRtt=100, rttvar=50, minRtt=100

        estimator.onRttSample(150, 10, 25, false);
        // minRtt = min(100,150) = 100; ackDelay unclamped (not confirmed) = 10
        // adjustedRtt: 150 >= 100+10 -> 150-10 = 140
        // rttvar = (3*50 + |100-140|) / 4 = 190/4 = 47
        // smoothedRtt = (7*100 + 140) / 8 = 840/8 = 105
        assertEquals(100, estimator.getMinRtt());
        assertEquals(47, estimator.getRttVar());
        assertEquals(105, estimator.getSmoothedRtt());
        assertEquals(150, estimator.getLatestRtt());
    }

    @Test
    public void testAckDelayUnclampedBeforeHandshakeConfirmed() {
        RttEstimator estimator = new RttEstimator();
        estimator.onRttSample(50, 0, 10, false); // smoothedRtt=50, rttvar=25, minRtt=50

        estimator.onRttSample(100, 40, 10, false);
        // ackDelay unclamped = 40; adjustedRtt: 100 >= 50+40 -> 100-40 = 60
        // rttvar = (3*25 + |50-60|)/4 = 85/4 = 21
        // smoothedRtt = (7*50 + 60)/8 = 410/8 = 51
        assertEquals(21, estimator.getRttVar());
        assertEquals(51, estimator.getSmoothedRtt());
    }

    @Test
    public void testAckDelayClampedAfterHandshakeConfirmed() {
        RttEstimator estimator = new RttEstimator();
        estimator.onRttSample(50, 0, 10, false); // smoothedRtt=50, rttvar=25, minRtt=50

        estimator.onRttSample(100, 40, 10, true);
        // ackDelay clamped to maxAckDelayMillis = 10; adjustedRtt: 100 >= 50+10 -> 100-10 = 90
        // rttvar = (3*25 + |50-90|)/4 = 115/4 = 28
        // smoothedRtt = (7*50 + 90)/8 = 440/8 = 55
        assertEquals(28, estimator.getRttVar());
        assertEquals(55, estimator.getSmoothedRtt());
    }

    @Test
    public void testImplausibleAdjustmentLeavesLatestRttUnchanged() {
        RttEstimator estimator = new RttEstimator();
        estimator.onRttSample(50, 0, 100, false); // smoothedRtt=50, rttvar=25, minRtt=50

        estimator.onRttSample(55, 20, 100, false);
        // ackDelay unclamped = 20; adjustedRtt: 55 >= 50+20=70? no -> adjustedRtt stays 55
        // rttvar = (3*25 + |50-55|)/4 = 80/4 = 20
        // smoothedRtt = (7*50 + 55)/8 = 405/8 = 50
        assertEquals(20, estimator.getRttVar());
        assertEquals(50, estimator.getSmoothedRtt());
    }

    @Test
    public void testMinRttIgnoresAckDelay() {
        RttEstimator estimator = new RttEstimator();
        estimator.onRttSample(200, 0, 100, false);
        estimator.onRttSample(50, 40, 100, false); // a smaller latestRtt, despite a large ack delay
        assertEquals(50, estimator.getMinRtt());
    }
}
