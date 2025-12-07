/*
 * ConnectionRateLimiterTest.java
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
 * Unit tests for {@link ConnectionRateLimiter}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectionRateLimiterTest {

    private ConnectionRateLimiter limiter;
    private InetAddress testIP;
    
    @Before
    public void setUp() throws Exception {
        limiter = new ConnectionRateLimiter();
        limiter.setMaxConcurrentPerIP(5);
        limiter.setConnectionRate(10, 1000); // 10 per second
        testIP = InetAddress.getByName("192.168.1.100");
    }
    
    @Test
    public void testAllowsConnectionInitially() {
        assertTrue(limiter.allowConnection(testIP));
    }
    
    @Test
    public void testConcurrentConnectionLimit() {
        // Open 5 connections
        for (int i = 0; i < 5; i++) {
            assertTrue("Connection " + i + " should be allowed", limiter.allowConnection(testIP));
            limiter.connectionOpened(testIP);
        }
        
        // 6th should be denied
        assertFalse("6th connection should be denied", limiter.allowConnection(testIP));
        
        // Close one, then should be allowed
        limiter.connectionClosed(testIP);
        assertTrue("Should allow after closing one", limiter.allowConnection(testIP));
    }
    
    @Test
    public void testConnectionRateLimitWithRemaining() {
        // Test that remaining connections decreases as connections are opened
        int remaining = limiter.getRemainingConnections(testIP);
        assertEquals(10, remaining);  // Initial rate limit from setUp
        
        // Open one connection
        limiter.connectionOpened(testIP);
        
        // Remaining should decrease
        int newRemaining = limiter.getRemainingConnections(testIP);
        assertTrue("Remaining should decrease after opening connection",
                   newRemaining < remaining);
    }
    
    @Test
    public void testGetActiveConnections() throws Exception {
        assertEquals(0, limiter.getActiveConnections(testIP));
        
        limiter.connectionOpened(testIP);
        assertEquals(1, limiter.getActiveConnections(testIP));
        
        limiter.connectionOpened(testIP);
        assertEquals(2, limiter.getActiveConnections(testIP));
        
        limiter.connectionClosed(testIP);
        assertEquals(1, limiter.getActiveConnections(testIP));
        
        limiter.connectionClosed(testIP);
        assertEquals(0, limiter.getActiveConnections(testIP));
    }
    
    @Test
    public void testDifferentIPsAreIndependent() throws Exception {
        InetAddress ip1 = InetAddress.getByName("10.0.0.1");
        InetAddress ip2 = InetAddress.getByName("10.0.0.2");
        
        // Fill up ip1's connections
        for (int i = 0; i < 5; i++) {
            limiter.connectionOpened(ip1);
        }
        
        // ip1 should be blocked, but ip2 should be allowed
        assertFalse(limiter.allowConnection(ip1));
        assertTrue(limiter.allowConnection(ip2));
    }
    
    @Test
    public void testNullIPHandling() {
        // Should not throw and should allow
        assertTrue(limiter.allowConnection(null));
        limiter.connectionOpened(null);
        limiter.connectionClosed(null);
    }
    
    @Test
    public void testSetRateLimitString() {
        limiter.setRateLimit("100/60s");
        assertEquals(100, limiter.getMaxConnectionsPerWindow());
        assertEquals(60000, limiter.getWindowMs());
        
        limiter.setRateLimit("50/30s");
        assertEquals(50, limiter.getMaxConnectionsPerWindow());
        assertEquals(30000, limiter.getWindowMs());
        
        limiter.setRateLimit("1000/1m");
        assertEquals(1000, limiter.getMaxConnectionsPerWindow());
        assertEquals(60000, limiter.getWindowMs());
        
        limiter.setRateLimit("500/1h");
        assertEquals(500, limiter.getMaxConnectionsPerWindow());
        assertEquals(3600000, limiter.getWindowMs());
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidRateLimitFormat() {
        limiter.setRateLimit("invalid");
    }
    
    @Test
    public void testGetRemainingConnections() {
        assertEquals(10, limiter.getRemainingConnections(testIP));
        
        limiter.connectionOpened(testIP);
        assertEquals(9, limiter.getRemainingConnections(testIP));
    }
    
    @Test
    public void testReset() {
        limiter.connectionOpened(testIP);
        limiter.connectionOpened(testIP);
        assertEquals(2, limiter.getActiveConnections(testIP));
        
        limiter.reset();
        assertEquals(0, limiter.getActiveConnections(testIP));
    }
    
    @Test
    public void testDisableConcurrentLimit() throws Exception {
        limiter.setMaxConcurrentPerIP(0);
        // Also need to disable rate limit to test unlimited concurrent
        limiter.setConnectionRate(0, 1000);
        
        // Should allow unlimited concurrent connections when both limits are disabled
        for (int i = 0; i < 100; i++) {
            assertTrue("Connection " + i + " should be allowed", limiter.allowConnection(testIP));
            limiter.connectionOpened(testIP);
        }
    }
    
    @Test
    public void testDefaultValues() {
        ConnectionRateLimiter defaultLimiter = new ConnectionRateLimiter();
        
        assertEquals(ConnectionRateLimiter.DEFAULT_MAX_CONCURRENT, 
                     defaultLimiter.getMaxConcurrentPerIP());
        assertEquals(ConnectionRateLimiter.DEFAULT_MAX_PER_WINDOW, 
                     defaultLimiter.getMaxConnectionsPerWindow());
        assertEquals(ConnectionRateLimiter.DEFAULT_WINDOW_MS, 
                     defaultLimiter.getWindowMs());
    }
    
    @Test
    public void testCleanup() {
        // Open and close a connection
        limiter.connectionOpened(testIP);
        limiter.connectionClosed(testIP);
        
        // Manual cleanup
        limiter.cleanup();
        
        // Should still work normally
        assertTrue(limiter.allowConnection(testIP));
    }
    
    @Test
    public void testCloseMoreThanOpened() {
        // Close without opening - should handle gracefully
        limiter.connectionClosed(testIP);
        assertEquals(0, limiter.getActiveConnections(testIP));
    }
}

