/*
 * Amqp1RecoveryPolicyTest.java
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

package org.bluezoo.gumdrop.amqp1.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for {@link Amqp1RecoveryPolicy}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Amqp1RecoveryPolicyTest {

    @Test
    public void testDefaults() {
        Amqp1RecoveryPolicy p = new Amqp1RecoveryPolicy();
        assertEquals(1000L, p.getInitialDelayMs());
        assertEquals(30000L, p.getMaxDelayMs());
        assertEquals(2.0, p.getMultiplier(), 0.0);
        assertEquals("unlimited by default", 0, p.getMaxAttempts());
    }

    @Test
    public void testDelayGrowsExponentiallyFromTheInitialDelay() {
        Amqp1RecoveryPolicy p = new Amqp1RecoveryPolicy();
        assertEquals(1000L, p.delayFor(1));
        assertEquals(2000L, p.delayFor(2));
        assertEquals(4000L, p.delayFor(3));
        assertEquals(8000L, p.delayFor(4));
    }

    @Test
    public void testDelayIsCappedAtTheMaximum() {
        Amqp1RecoveryPolicy p = new Amqp1RecoveryPolicy();
        assertEquals(30000L, p.delayFor(10));
        assertEquals(30000L, p.delayFor(1000));
    }

    @Test
    public void testCustomisedPolicy() {
        Amqp1RecoveryPolicy p = new Amqp1RecoveryPolicy().withInitialDelayMs(50)
                .withMaxDelayMs(500).withMultiplier(3.0).withMaxAttempts(4);
        assertEquals(50L, p.delayFor(1));
        assertEquals(150L, p.delayFor(2));
        assertEquals(450L, p.delayFor(3));
        assertEquals(500L, p.delayFor(4));
        assertEquals(4, p.getMaxAttempts());
    }

    @Test
    public void testAttemptBelowOneIsTreatedAsTheFirst() {
        assertEquals(1000L, new Amqp1RecoveryPolicy().delayFor(0));
        assertEquals(1000L, new Amqp1RecoveryPolicy().delayFor(-3));
    }
}
