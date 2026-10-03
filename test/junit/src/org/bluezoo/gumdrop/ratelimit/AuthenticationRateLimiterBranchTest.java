/*
 * AuthenticationRateLimiterBranchTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.ratelimit;

import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Branch-level unit tests for {@link AuthenticationRateLimiter}: the
 * address and address-plus-username overloads, null handling, lockout expiry,
 * backoff capping, opportunistic and explicit cleanup, cleanup failure
 * handling, all on a manually advanced clock.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AuthenticationRateLimiterBranchTest {

    /** Limiter driven by a manually advanced clock. */
    private static class ManualAuthLimiter extends AuthenticationRateLimiter {
        long now = 1000000L;

        @Override
        long currentTimeMillis() {
            return now;
        }
    }

    private ManualAuthLimiter limiter;
    private InetAddress ip;

    @Before
    public void setUp() throws Exception {
        limiter = new ManualAuthLimiter();
        limiter.setMaxFailures(2);
        limiter.setLockoutDuration(1000L);
        ip = InetAddress.getByAddress(new byte[] {10, 1, 2, 3});
    }

    // ===== address overloads =====

    @Test
    public void testAddressOverloadsTrackByHostAddress() {
        limiter.recordFailure(ip);
        limiter.recordFailure(ip);
        assertTrue(limiter.isLocked(ip));
        assertTrue(limiter.isLocked("10.1.2.3"));
        limiter.recordSuccess(ip);
        assertFalse(limiter.isLocked(ip));
    }

    @Test
    public void testNullAddressIsIgnored() {
        InetAddress none = null;
        limiter.recordFailure(none);
        limiter.recordSuccess(none);
        assertFalse(limiter.isLocked(none));
        limiter.recordFailure(none, "bob");
        limiter.recordSuccess(none, "bob");
        assertEquals(0, limiter.getFailureCount("bob"));
    }

    @Test
    public void testCombinedKeyRecordedOnlyWithUsername() {
        limiter.recordFailure(ip, null);
        assertEquals(1, limiter.getFailureCount("10.1.2.3"));
        limiter.recordFailure(ip, "bob");
        assertEquals(2, limiter.getFailureCount("10.1.2.3"));
        assertEquals(1, limiter.getFailureCount("10.1.2.3:bob"));
        limiter.recordSuccess(ip, null);
        assertEquals(0, limiter.getFailureCount("10.1.2.3"));
        assertEquals(1, limiter.getFailureCount("10.1.2.3:bob"));
        limiter.recordSuccess(ip, "bob");
        assertEquals(0, limiter.getFailureCount("10.1.2.3:bob"));
    }

    @Test
    public void testSuccessForUnknownKeyAndNullKey() {
        limiter.recordSuccess("never-failed");
        limiter.recordSuccess((String) null);
        limiter.recordFailure((String) null);
        assertEquals(0, limiter.getFailureCount("never-failed"));
        assertEquals(0, limiter.getFailureCount(null));
        assertEquals(0L, limiter.getLockoutRemaining(null));
        assertEquals(0L, limiter.getLockoutRemaining("never-failed"));
        assertFalse(limiter.isLocked((String) null));
    }

    // ===== lockout lifecycle =====

    @Test
    public void testLockoutExpiryResetsFailureCount() {
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        assertTrue(limiter.isLocked("k"));
        assertEquals(1000L, limiter.getLockoutRemaining("k"));
        limiter.now += 999L;
        assertTrue(limiter.isLocked("k"));
        limiter.now += 1L;
        assertFalse(limiter.isLocked("k"));
        assertEquals(0, limiter.getFailureCount("k"));
        assertEquals(0L, limiter.getLockoutRemaining("k"));
    }

    @Test
    public void testBackoffIsCappedAtMaxLockout() {
        limiter.setMaxLockoutDuration(2500L);
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        assertEquals(1000L, limiter.getLockoutRemaining("k"));
        limiter.now += 1000L;
        assertFalse(limiter.isLocked("k"));
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        assertEquals(2000L, limiter.getLockoutRemaining("k"));
        limiter.now += 2000L;
        assertFalse(limiter.isLocked("k"));
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        assertEquals("capped at the maximum", 2500L, limiter.getLockoutRemaining("k"));
    }

    @Test
    public void testUnlockRemovesTracker() {
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        limiter.unlock("k");
        assertFalse(limiter.isLocked("k"));
        limiter.unlock(null);
        assertEquals(0, limiter.getFailureCount("k"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxFailuresMustBePositive() {
        limiter.setMaxFailures(0);
    }

    @Test
    public void testConfigurationAccessors() {
        limiter.setMaxFailures(9);
        limiter.setLockoutDuration(1234L);
        assertEquals(9, limiter.getMaxFailures());
        assertEquals(1234L, limiter.getLockoutDuration());
        limiter.shutdown();
        limiter.recordFailure("k");
        limiter.reset();
        assertEquals(0, limiter.getFailureCount("k"));
    }

    // ===== cleanup =====

    @Test
    public void testCleanupDropsOnlyStaleUnlockedTrackers() {
        limiter.setMaxLockoutDuration(1000L);
        limiter.recordFailure("stale");
        limiter.now += 5000L;
        limiter.recordFailure("fresh");
        limiter.cleanup();
        assertEquals(0, limiter.getFailureCount("stale"));
        assertEquals(1, limiter.getFailureCount("fresh"));
    }

    @Test
    public void testCleanupKeepsLockedTrackers() {
        limiter.setMaxLockoutDuration(100L);
        limiter.setLockoutDuration(100000L);
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        limiter.now += 50000L;
        limiter.cleanup();
        assertTrue(limiter.isLocked("k"));
    }

    @Test
    public void testAcceptPathRunsCleanupAfterInterval() {
        limiter.setMaxLockoutDuration(1000L);
        limiter.recordFailure("stale");
        limiter.now += 10000000000000L;
        assertFalse(limiter.isLocked("other"));
        assertEquals(0, limiter.getFailureCount("stale"));
    }

    @Test
    public void testCleanupFailureIsLoggedNotPropagated() {
        ManualAuthLimiter failing = new ManualAuthLimiter() {
            @Override
            public void cleanup() {
                throw new IllegalStateException("boom");
            }
        };
        failing.now += 10000000000000L;
        assertFalse(failing.isLocked("k"));
    }

}
