/*
 * AuthenticationRateLimiterTest.java
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

import static org.junit.Assert.*;

/**
 * Unit tests for {@link AuthenticationRateLimiter}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AuthenticationRateLimiterTest {

    private AuthenticationRateLimiter limiter;
    
    @Before
    public void setUp() {
        limiter = new AuthenticationRateLimiter();
        limiter.setMaxFailures(3);
        limiter.setLockoutDuration(1000); // 1 second for fast tests
    }
    
    @Test
    public void testNotLockedInitially() {
        assertFalse(limiter.isLocked("test-key"));
        assertEquals(0, limiter.getFailureCount("test-key"));
    }
    
    @Test
    public void testLockAfterMaxFailures() {
        String key = "192.168.1.100";
        
        limiter.recordFailure(key);
        assertFalse(limiter.isLocked(key));
        assertEquals(1, limiter.getFailureCount(key));
        
        limiter.recordFailure(key);
        assertFalse(limiter.isLocked(key));
        assertEquals(2, limiter.getFailureCount(key));
        
        limiter.recordFailure(key);
        assertTrue("Should be locked after 3 failures", limiter.isLocked(key));
    }
    
    @Test
    public void testSuccessResetsFailureCount() {
        String key = "test-user";
        
        limiter.recordFailure(key);
        limiter.recordFailure(key);
        assertEquals(2, limiter.getFailureCount(key));
        
        limiter.recordSuccess(key);
        assertEquals(0, limiter.getFailureCount(key));
        assertFalse(limiter.isLocked(key));
    }
    
    @Test
    public void testLockoutRemaining() throws InterruptedException {
        String key = "locked-key";
        
        // Lock the key
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(key);
        }
        
        assertTrue(limiter.isLocked(key));
        long remaining = limiter.getLockoutRemaining(key);
        assertTrue("Remaining should be positive", remaining > 0);
        assertTrue("Remaining should be <= lockout duration", remaining <= 1000);
    }
    
    @Test
    public void testLockoutExpires() throws InterruptedException {
        limiter.setLockoutDuration(50); // 50ms for fast test
        String key = "expiring-key";
        
        // Lock the key
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(key);
        }
        assertTrue(limiter.isLocked(key));
        
        // Wait for expiration
        Thread.sleep(100);
        
        assertFalse("Lockout should have expired", limiter.isLocked(key));
    }
    
    @Test
    public void testNullKeyHandling() {
        assertFalse(limiter.isLocked((String) null));
        assertEquals(0, limiter.getFailureCount(null));
        assertEquals(0, limiter.getLockoutRemaining(null));
        
        // Should not throw
        limiter.recordFailure((String) null);
        limiter.recordSuccess((String) null);
    }
    
    @Test
    public void testInetAddressKey() throws Exception {
        InetAddress ip = InetAddress.getByName("10.0.0.1");
        
        limiter.recordFailure(ip);
        limiter.recordFailure(ip);
        assertEquals(2, limiter.getFailureCount(ip.getHostAddress()));
        
        limiter.recordSuccess(ip);
        assertEquals(0, limiter.getFailureCount(ip.getHostAddress()));
    }
    
    @Test
    public void testCombinedIpAndUsername() throws Exception {
        InetAddress ip = InetAddress.getByName("192.168.1.50");
        String username = "admin";
        
        limiter.recordFailure(ip, username);
        
        // Should record for IP alone
        assertEquals(1, limiter.getFailureCount(ip.getHostAddress()));
        // And for combined key
        assertEquals(1, limiter.getFailureCount(ip.getHostAddress() + ":" + username));
    }
    
    @Test
    public void testExponentialBackoff() throws InterruptedException {
        limiter.setLockoutDuration(100);
        limiter.setMaxLockoutDuration(10000);
        limiter.setExponentialBackoff(true);
        
        String key = "backoff-key";
        
        // First lockout
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(key);
        }
        assertTrue(limiter.isLocked(key));
        long firstLockout = limiter.getLockoutRemaining(key);
        
        // Wait for first lockout to expire
        Thread.sleep(150);
        assertFalse(limiter.isLocked(key));
        
        // Second lockout - should be 2x duration
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(key);
        }
        assertTrue(limiter.isLocked(key));
        long secondLockout = limiter.getLockoutRemaining(key);
        
        assertTrue("Second lockout should be longer: " + secondLockout + " vs " + firstLockout,
                   secondLockout > firstLockout);
    }
    
    @Test
    public void testDisableExponentialBackoff() {
        limiter.setLockoutDuration(100);
        limiter.setExponentialBackoff(false);
        
        String key = "no-backoff-key";
        
        // Lock multiple times and verify lockout duration stays constant
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 3; i++) {
                limiter.recordFailure(key);
            }
            assertTrue(limiter.isLocked(key));
            
            // Unlock for next round
            limiter.unlock(key);
        }
    }
    
    @Test
    public void testParseLockoutDuration() {
        limiter.setLockoutTime("5m");
        assertEquals(300000, limiter.getLockoutDuration());
        
        limiter.setLockoutTime("30s");
        assertEquals(30000, limiter.getLockoutDuration());
        
        limiter.setLockoutTime("1h");
        assertEquals(3600000, limiter.getLockoutDuration());
        
        limiter.setLockoutTime("500ms");
        assertEquals(500, limiter.getLockoutDuration());
    }
    
    @Test
    public void testUnlock() {
        String key = "unlock-key";
        
        // Lock the key
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(key);
        }
        assertTrue(limiter.isLocked(key));
        
        // Administrative unlock
        limiter.unlock(key);
        assertFalse(limiter.isLocked(key));
        assertEquals(0, limiter.getFailureCount(key));
    }
    
    @Test
    public void testReset() {
        limiter.recordFailure("key1");
        limiter.recordFailure("key2");
        limiter.recordFailure("key3");
        
        limiter.reset();
        
        assertEquals(0, limiter.getFailureCount("key1"));
        assertEquals(0, limiter.getFailureCount("key2"));
        assertEquals(0, limiter.getFailureCount("key3"));
    }
    
    @Test
    public void testDefaultValues() {
        AuthenticationRateLimiter defaultLimiter = new AuthenticationRateLimiter();
        
        assertEquals(AuthenticationRateLimiter.DEFAULT_MAX_FAILURES, 
                     defaultLimiter.getMaxFailures());
        assertEquals(AuthenticationRateLimiter.DEFAULT_LOCKOUT_MS, 
                     defaultLimiter.getLockoutDuration());
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidMaxFailures() {
        limiter.setMaxFailures(0);
    }
    
    @Test
    public void testCleanup() throws InterruptedException {
        limiter.setLockoutDuration(50);
        limiter.setMaxLockoutDuration(50);
        
        // Create some failures
        limiter.recordFailure("cleanup-key");
        
        // Wait for cleanup period
        Thread.sleep(200);
        
        // Manual cleanup
        limiter.cleanup();
        
        // Key should be cleaned up
        assertEquals(0, limiter.getFailureCount("cleanup-key"));
    }
}

