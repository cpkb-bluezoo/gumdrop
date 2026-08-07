/*
 * RecoveryPolicyTest.java
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

package org.bluezoo.gumdrop.amqp.client;

import org.junit.Test;

import static org.junit.Assert.*;

public class RecoveryPolicyTest {

    @Test
    public void testDefaultsExponentialBackoffCappedAtMax() {
        RecoveryPolicy policy = new RecoveryPolicy();
        assertEquals(1000L, policy.delayFor(1));
        assertEquals(2000L, policy.delayFor(2));
        assertEquals(4000L, policy.delayFor(3));
        assertEquals(8000L, policy.delayFor(4));
        assertEquals(16000L, policy.delayFor(5));
        assertEquals(30000L, policy.delayFor(6)); // capped
        assertEquals(30000L, policy.delayFor(100));
    }

    @Test
    public void testCustomPolicy() {
        RecoveryPolicy policy = new RecoveryPolicy()
                .withInitialDelayMs(500L)
                .withMultiplier(3.0)
                .withMaxDelayMs(5000L)
                .withMaxAttempts(10);
        assertEquals(500L, policy.delayFor(1));
        assertEquals(1500L, policy.delayFor(2));
        assertEquals(4500L, policy.delayFor(3));
        assertEquals(5000L, policy.delayFor(4)); // capped
        assertEquals(10, policy.getMaxAttempts());
    }

    @Test
    public void testUnlimitedAttemptsByDefault() {
        assertEquals(0, new RecoveryPolicy().getMaxAttempts());
    }

    @Test
    public void testFirstAttemptUsesInitialDelay() {
        RecoveryPolicy policy = new RecoveryPolicy().withInitialDelayMs(2500L);
        assertEquals(2500L, policy.delayFor(1));
    }
}
