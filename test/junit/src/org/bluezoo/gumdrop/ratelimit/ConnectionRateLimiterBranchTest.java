/*
 * ConnectionRateLimiterBranchTest.java
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
import static org.junit.Assert.fail;

/**
 * Branch-level unit tests for {@link ConnectionRateLimiter}: rate-limit
 * specification parsing, the rate-window rejection path, opportunistic and
 * explicit cleanup, cleanup failure handling.
 * Time comes from a manually advanced clock injected through the
 * package-private seams.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectionRateLimiterBranchTest {

    /** Shared manual clock. */
    private static final class Clock {
        long now = 5000000L;
    }

    /** Limiter whose clock and per-IP window limiters are manual. */
    private static class ManualLimiter extends ConnectionRateLimiter {
        final Clock clock = new Clock();

        @Override
        long currentTimeMillis() {
            return clock.now;
        }

        @Override
        RateLimiter newLimiter(int maxEvents, long window) {
            final Clock c = clock;
            return new RateLimiter(maxEvents, window) {
                @Override
                long currentTimeMillis() {
                    return c.now;
                }
            };
        }
    }

    private ManualLimiter limiter;
    private InetAddress ip;

    @Before
    public void setUp() throws Exception {
        limiter = new ManualLimiter();
        ip = InetAddress.getByAddress(new byte[] {10, 0, 0, 7});
    }

    private void expectInvalid(String spec) {
        try {
            limiter.setRateLimit(spec);
            fail("expected IllegalArgumentException for " + spec);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("" + spec));
        }
    }

    // ===== rate-limit specification =====

    @Test
    public void testNullAndEmptySpecificationIsIgnored() {
        limiter.setRateLimit(null);
        limiter.setRateLimit("");
        assertEquals(ConnectionRateLimiter.DEFAULT_MAX_PER_WINDOW,
                limiter.getMaxConnectionsPerWindow());
        assertEquals(ConnectionRateLimiter.DEFAULT_WINDOW_MS, limiter.getWindowMs());
    }

    @Test
    public void testInvalidSpecificationsAreRejected() {
        expectInvalid("100");
        expectInvalid("x/60s");
        expectInvalid("10/abcs");
        expectInvalid("10/");
    }

    @Test
    public void testDurationSuffixes() {
        limiter.setRateLimit("7/500ms");
        assertEquals(7, limiter.getMaxConnectionsPerWindow());
        assertEquals(500L, limiter.getWindowMs());
        limiter.setRateLimit("7/5");
        assertEquals(5L, limiter.getWindowMs());
        limiter.setRateLimit(" 3 / 2M ");
        assertEquals(3, limiter.getMaxConnectionsPerWindow());
        assertEquals(120000L, limiter.getWindowMs());
        limiter.setRateLimit("3/2h");
        assertEquals(7200000L, limiter.getWindowMs());
        limiter.setRateLimit("3/9s");
        assertEquals(9000L, limiter.getWindowMs());
    }

    // ===== window limit =====

    @Test
    public void testWindowLimitRejectsThenRecoversWhenClockAdvances() {
        limiter.setMaxConcurrentPerIP(0);
        limiter.setConnectionRate(2, 1000L);
        assertTrue(limiter.allowConnection(ip));
        limiter.connectionOpened(ip);
        limiter.connectionOpened(ip);
        assertFalse(limiter.allowConnection(ip));
        assertEquals(0, limiter.getRemainingConnections(ip));
        assertEquals(1000L, limiter.getTimeUntilAvailable(ip));
        limiter.clock.now += 400L;
        assertEquals(600L, limiter.getTimeUntilAvailable(ip));
        limiter.clock.now += 700L;
        assertTrue(limiter.allowConnection(ip));
        assertEquals(2, limiter.getRemainingConnections(ip));
    }

    @Test
    public void testUnknownAddressHasFullAllowanceAndNoWait() {
        limiter.setConnectionRate(4, 1000L);
        assertEquals(4, limiter.getRemainingConnections(ip));
        assertEquals(0L, limiter.getTimeUntilAvailable(ip));
        assertEquals(0, limiter.getActiveConnections(ip));
    }

    @Test
    public void testOpenAndCloseWithoutWindowLimit() {
        limiter.setMaxConcurrentPerIP(2);
        limiter.setConnectionRate(0, 1000L);
        limiter.connectionOpened(ip);
        limiter.connectionOpened(ip);
        assertFalse(limiter.allowConnection(ip));
        limiter.connectionClosed(ip);
        assertTrue(limiter.allowConnection(ip));
        limiter.connectionClosed(ip);
        limiter.connectionClosed(ip);
        assertEquals(0, limiter.getActiveConnections(ip));
    }

    @Test
    public void testShutdownIsHarmless() {
        limiter.shutdown();
        assertTrue(limiter.allowConnection(ip));
    }

    // ===== cleanup =====

    @Test
    public void testCleanupRemovesIdleWindowLimiters() {
        limiter.setMaxConcurrentPerIP(0);
        limiter.setConnectionRate(2, 1000L);
        limiter.connectionOpened(ip);
        assertEquals(1, limiter.getRemainingConnections(ip));
        limiter.clock.now += 5000L;
        limiter.cleanup();
        assertEquals("limiter removed once its window is empty",
                2, limiter.getRemainingConnections(ip));
    }

    @Test
    public void testCleanupKeepsBusyLimiters() {
        limiter.setMaxConcurrentPerIP(0);
        limiter.setConnectionRate(2, 1000L);
        limiter.connectionOpened(ip);
        limiter.cleanup();
        assertEquals(1, limiter.getRemainingConnections(ip));
    }

    @Test
    public void testAcceptPathRunsCleanupOncePerInterval() {
        limiter.setMaxConcurrentPerIP(0);
        limiter.setConnectionRate(2, 1000L);
        limiter.connectionOpened(ip);
        limiter.clock.now += 10000000000000L;
        assertTrue(limiter.allowConnection(ip));
        assertEquals("opportunistic cleanup dropped the expired limiter",
                2, limiter.getRemainingConnections(ip));
        limiter.connectionOpened(ip);
        limiter.clock.now += 5000L;
        assertTrue(limiter.allowConnection(ip));
        assertEquals("no second cleanup within the interval",
                2, limiter.getRemainingConnections(ip));
    }

    @Test
    public void testCleanupFailureIsLoggedNotPropagated() {
        final boolean[] called = new boolean[1];
        ManualLimiter failing = new ManualLimiter() {
            @Override
            public void cleanup() {
                called[0] = true;
                throw new IllegalStateException("boom");
            }
        };
        failing.clock.now += 10000000000000L;
        assertTrue(failing.allowConnection(ip));
        assertTrue(called[0]);
        assertTrue(failing.allowConnection(ip));
    }

    
}
