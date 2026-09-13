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
 * Tests for {@link HttpListener} configuration, including RFC 9112
 * idle timeout, max-requests-per-connection, and RFC 9110 TRACE control.
 */
public class HTTPListenerTest {

    @Test
    public void testDefaultIdleTimeout() {
        HttpListener listener = new HttpListener();
        assertEquals("Default idle timeout should be 0 (disabled)",
                0, listener.getIdleTimeoutMs());
    }

    @Test
    public void testSetIdleTimeout() {
        // RFC 9112 section 9.8
        HttpListener listener = new HttpListener();
        listener.setIdleTimeoutMs(60000);
        assertEquals(60000, listener.getIdleTimeoutMs());
    }

    @Test
    public void testDefaultMaxRequestsPerConnection() {
        HttpListener listener = new HttpListener();
        assertEquals("Default max requests should be 0 (unlimited)",
                0, listener.getMaxRequestsPerConnection());
    }

    @Test
    public void testSetMaxRequestsPerConnection() {
        // RFC 9112 section 9.6
        HttpListener listener = new HttpListener();
        listener.setMaxRequestsPerConnection(100);
        assertEquals(100, listener.getMaxRequestsPerConnection());
    }

    @Test
    public void testTraceMethodDisabledByDefault() {
        // RFC 9110 section 9.3.8: disabled for security
        HttpListener listener = new HttpListener();
        assertFalse("TRACE should be disabled by default",
                listener.isTraceMethodEnabled());
    }

    @Test
    public void testSetTraceMethodEnabled() {
        HttpListener listener = new HttpListener();
        listener.setTraceMethodEnabled(true);
        assertTrue(listener.isTraceMethodEnabled());
    }

    @Test
    public void testDefaultPort() {
        HttpListener listener = new HttpListener();
        assertEquals(-1, listener.getPort());
    }

    @Test
    public void testMaxConcurrentStreams() {
        HttpListener listener = new HttpListener();
        assertEquals(100, listener.getMaxConcurrentStreams());
        listener.setMaxConcurrentStreams(200);
        assertEquals(200, listener.getMaxConcurrentStreams());
    }

    @Test
    public void testDefaultMaxHeaderListSize() {
        HttpListener listener = new HttpListener();
        assertEquals(HttpListener.DEFAULT_MAX_HEADER_LIST_SIZE,
                listener.getMaxHeaderListSize());
    }

    @Test
    public void testSetMaxHeaderListSize() {
        HttpListener listener = new HttpListener();
        listener.setMaxHeaderListSize(16384);
        assertEquals(16384, listener.getMaxHeaderListSize());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxHeaderListSizeRejectsZero() {
        HttpListener listener = new HttpListener();
        listener.setMaxHeaderListSize(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxConcurrentStreamsRejectsZero() {
        HttpListener listener = new HttpListener();
        listener.setMaxConcurrentStreams(0);
    }

    // RFC 9113 section 6.7: PING keep-alive interval
    @Test
    public void testDefaultPingInterval() {
        HttpListener listener = new HttpListener();
        assertEquals("Default ping interval should be 0 (disabled)",
                0, listener.getPingIntervalMs());
    }

    @Test
    public void testSetPingInterval() {
        HttpListener listener = new HttpListener();
        listener.setPingIntervalMs(30000);
        assertEquals(30000, listener.getPingIntervalMs());
    }

    @Test
    public void testDefaultMaxRequestBodySize() {
        HttpListener listener = new HttpListener();
        assertEquals(HttpListener.DEFAULT_MAX_REQUEST_BODY_SIZE,
                listener.getMaxRequestBodySize());
    }

    @Test
    public void testSetMaxRequestBodySize() {
        HttpListener listener = new HttpListener();
        listener.setMaxRequestBodySize(1024);
        assertEquals(1024, listener.getMaxRequestBodySize());
    }

    @Test
    public void testZeroMaxRequestBodySizeMeansUnlimited() {
        HttpListener listener = new HttpListener();
        listener.setMaxRequestBodySize(0);
        assertEquals(0, listener.getMaxRequestBodySize());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxRequestBodySizeRejectsNegative() {
        HttpListener listener = new HttpListener();
        listener.setMaxRequestBodySize(-1);
    }
}
