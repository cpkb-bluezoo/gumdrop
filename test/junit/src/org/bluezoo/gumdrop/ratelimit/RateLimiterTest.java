/*
 * RateLimiterTest.java
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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link RateLimiter}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RateLimiterTest {

    /** Rate limiter driven by a manually advanced clock. */
    private static final class ManualRateLimiter extends RateLimiter {
        private long now = 1000000L;

        ManualRateLimiter(int maxEvents, long windowMs) {
            super(maxEvents, windowMs);
        }

        void advance(long ms) {
            now += ms;
        }

        @Override
        long currentTimeMillis() {
            return now;
        }
    }

    @Test
    public void testBasicAcquisition() {
        ManualRateLimiter limiter = new ManualRateLimiter(5, 1000);
        
        // Should allow 5 acquisitions
        for (int i = 0; i < 5; i++) {
            assertTrue("Acquisition " + i + " should succeed", limiter.tryAcquire());
        }
        
        // 6th should fail
        assertFalse("6th acquisition should fail", limiter.tryAcquire());
    }
    
    @Test
    public void testCanAcquireDoesNotConsume() {
        ManualRateLimiter limiter = new ManualRateLimiter(2, 1000);
        
        // canAcquire should not consume permits
        assertTrue(limiter.canAcquire());
        assertTrue(limiter.canAcquire());
        assertTrue(limiter.canAcquire());
        
        // Count should still be 0
        assertEquals(0, limiter.getCount());
        
        // Now actually acquire
        assertTrue(limiter.tryAcquire());
        assertEquals(1, limiter.getCount());
    }
    
    @Test
    public void testExpirationOverTime() {
        ManualRateLimiter limiter = new ManualRateLimiter(3, 50); // 50ms window
        
        // Fill up the limiter
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire()); // Should fail
        
        // Wait for window to expire
        limiter.advance(60);
        
        // Now should allow new acquisitions
        assertTrue(limiter.tryAcquire());
    }
    
    @Test
    public void testSlidingWindow() {
        // Use short window for test
        ManualRateLimiter limiter = new ManualRateLimiter(3, 50);
        
        // Acquire 3 permits
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        
        // Now full
        assertFalse(limiter.tryAcquire());
        assertEquals(0, limiter.getRemaining());
        
        // Wait for window to expire
        limiter.advance(60);
        
        // Should be able to acquire again
        assertTrue(limiter.canAcquire());
    }
    
    @Test
    public void testGetRemaining() {
        ManualRateLimiter limiter = new ManualRateLimiter(5, 1000);
        
        assertEquals(5, limiter.getRemaining());
        
        limiter.tryAcquire();
        assertEquals(4, limiter.getRemaining());
        
        limiter.tryAcquire();
        limiter.tryAcquire();
        assertEquals(2, limiter.getRemaining());
    }
    
    @Test
    public void testReset() {
        ManualRateLimiter limiter = new ManualRateLimiter(3, 1000);
        
        limiter.tryAcquire();
        limiter.tryAcquire();
        limiter.tryAcquire();
        assertFalse(limiter.tryAcquire());
        
        limiter.reset();
        
        // Should be able to acquire again
        assertTrue(limiter.tryAcquire());
        assertEquals(1, limiter.getCount());
    }
    
    @Test
    public void testGetters() {
        ManualRateLimiter limiter = new ManualRateLimiter(10, 5000);
        
        assertEquals(10, limiter.getMaxEvents());
        assertEquals(5000, limiter.getWindowMs());
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidMaxEventsZero() {
        new ManualRateLimiter(0, 1000);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidMaxEventsNegative() {
        new ManualRateLimiter(-1, 1000);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidWindowZero() {
        new ManualRateLimiter(10, 0);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidWindowNegative() {
        new ManualRateLimiter(10, -1);
    }
    
    @Test
    public void testTimeUntilAvailable() {
        ManualRateLimiter limiter = new ManualRateLimiter(2, 1000);
        
        // No acquisitions yet - should return 0
        assertEquals(0, limiter.getTimeUntilAvailable());
        
        // Acquire all permits
        limiter.tryAcquire();
        limiter.tryAcquire();
        
        // Now full - should return time until first expires
        assertEquals(1000, limiter.getTimeUntilAvailable());
        limiter.advance(400);
        assertEquals(600, limiter.getTimeUntilAvailable());
    }
    
    @Test
    public void testCircularBufferWrapAround() {
        ManualRateLimiter limiter = new ManualRateLimiter(3, 30);
        
        // Fill the buffer multiple times to test wrap-around
        for (int round = 0; round < 3; round++) {
            assertTrue("Round " + round + " acq 1", limiter.tryAcquire());
            assertTrue("Round " + round + " acq 2", limiter.tryAcquire());
            assertTrue("Round " + round + " acq 3", limiter.tryAcquire());
            assertFalse("Round " + round + " acq 4", limiter.tryAcquire());
            
            // Wait for window to expire
            limiter.advance(40);
        }
    }
    
    @Test
    public void testSingleEvent() {
        ManualRateLimiter limiter = new ManualRateLimiter(1, 1000);
        
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        assertEquals(0, limiter.getRemaining());
    }
}

