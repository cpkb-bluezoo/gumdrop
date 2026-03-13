/*
 * HTTPListenerTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.http;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link HTTPListener} configuration, including RFC 9112
 * idle timeout, max-requests-per-connection, and RFC 9110 TRACE control.
 */
public class HTTPListenerTest {

    @Test
    public void testDefaultIdleTimeout() {
        HTTPListener listener = new HTTPListener();
        assertEquals("Default idle timeout should be 0 (disabled)",
                0, listener.getIdleTimeoutMs());
    }

    @Test
    public void testSetIdleTimeout() {
        // RFC 9112 section 9.8
        HTTPListener listener = new HTTPListener();
        listener.setIdleTimeoutMs(60000);
        assertEquals(60000, listener.getIdleTimeoutMs());
    }

    @Test
    public void testDefaultMaxRequestsPerConnection() {
        HTTPListener listener = new HTTPListener();
        assertEquals("Default max requests should be 0 (unlimited)",
                0, listener.getMaxRequestsPerConnection());
    }

    @Test
    public void testSetMaxRequestsPerConnection() {
        // RFC 9112 section 9.6
        HTTPListener listener = new HTTPListener();
        listener.setMaxRequestsPerConnection(100);
        assertEquals(100, listener.getMaxRequestsPerConnection());
    }

    @Test
    public void testTraceMethodDisabledByDefault() {
        // RFC 9110 section 9.3.8: disabled for security
        HTTPListener listener = new HTTPListener();
        assertFalse("TRACE should be disabled by default",
                listener.isTraceMethodEnabled());
    }

    @Test
    public void testSetTraceMethodEnabled() {
        HTTPListener listener = new HTTPListener();
        listener.setTraceMethodEnabled(true);
        assertTrue(listener.isTraceMethodEnabled());
    }

    @Test
    public void testDefaultPort() {
        HTTPListener listener = new HTTPListener();
        assertEquals(-1, listener.getPort());
    }

    @Test
    public void testMaxConcurrentStreams() {
        HTTPListener listener = new HTTPListener();
        assertEquals(100, listener.getMaxConcurrentStreams());
        listener.setMaxConcurrentStreams(200);
        assertEquals(200, listener.getMaxConcurrentStreams());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxConcurrentStreamsRejectsZero() {
        HTTPListener listener = new HTTPListener();
        listener.setMaxConcurrentStreams(0);
    }

    // RFC 9113 section 6.7: PING keep-alive interval
    @Test
    public void testDefaultPingInterval() {
        HTTPListener listener = new HTTPListener();
        assertEquals("Default ping interval should be 0 (disabled)",
                0, listener.getPingIntervalMs());
    }

    @Test
    public void testSetPingInterval() {
        HTTPListener listener = new HTTPListener();
        listener.setPingIntervalMs(30000);
        assertEquals(30000, listener.getPingIntervalMs());
    }
}
