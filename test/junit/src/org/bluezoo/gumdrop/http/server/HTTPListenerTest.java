/*
 * HTTPListenerTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.http.server;

import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.*;

/**
 * Tests for {@link Http2Listener} configuration, including RFC 9112
 * idle timeout, max-requests-per-connection, and RFC 9110 TRACE control.
 */
public class HTTPListenerTest {

    @Test
    public void testDefaultIdleTimeout() {
        Http2Listener listener = new Http2Listener();
        assertEquals("Default idle timeout should be 0 (disabled)",
                0, listener.getIdleTimeoutMs());
    }

    @Test
    public void testSetIdleTimeout() {
        // RFC 9112 section 9.8
        Http2Listener listener = new Http2Listener();
        listener.setIdleTimeoutMs(60000);
        assertEquals(60000, listener.getIdleTimeoutMs());
    }

    @Test
    public void testDefaultMaxRequestsPerConnection() {
        Http2Listener listener = new Http2Listener();
        assertEquals("Default max requests should be 0 (unlimited)",
                0, listener.getMaxRequestsPerConnection());
    }

    @Test
    public void testSetMaxRequestsPerConnection() {
        // RFC 9112 section 9.6
        Http2Listener listener = new Http2Listener();
        listener.setMaxRequestsPerConnection(100);
        assertEquals(100, listener.getMaxRequestsPerConnection());
    }

    @Test
    public void testTraceMethodDisabledByDefault() {
        // RFC 9110 section 9.3.8: disabled for security
        Http2Listener listener = new Http2Listener();
        assertFalse("TRACE should be disabled by default",
                listener.isTraceMethodEnabled());
    }

    @Test
    public void testSetTraceMethodEnabled() {
        Http2Listener listener = new Http2Listener();
        listener.setTraceMethodEnabled(true);
        assertTrue(listener.isTraceMethodEnabled());
    }

    @Test
    public void testDefaultPort() {
        Http2Listener listener = new Http2Listener();
        assertEquals(-1, listener.getPort());
    }

    @Test
    public void testMaxConcurrentStreams() {
        Http2Listener listener = new Http2Listener();
        assertEquals(100, listener.getMaxConcurrentStreams());
        listener.setMaxConcurrentStreams(200);
        assertEquals(200, listener.getMaxConcurrentStreams());
    }

    @Test
    public void testDefaultMaxHeaderListSize() {
        Http2Listener listener = new Http2Listener();
        assertEquals(Http2Listener.DEFAULT_MAX_HEADER_LIST_SIZE,
                listener.getMaxHeaderListSize());
    }

    @Test
    public void testSetMaxHeaderListSize() {
        Http2Listener listener = new Http2Listener();
        listener.setMaxHeaderListSize(16384);
        assertEquals(16384, listener.getMaxHeaderListSize());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxHeaderListSizeRejectsZero() {
        Http2Listener listener = new Http2Listener();
        listener.setMaxHeaderListSize(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxConcurrentStreamsRejectsZero() {
        Http2Listener listener = new Http2Listener();
        listener.setMaxConcurrentStreams(0);
    }

    // RFC 9113 section 6.7: PING keep-alive interval
    @Test
    public void testDefaultPingInterval() {
        Http2Listener listener = new Http2Listener();
        assertEquals("Default ping interval should be 0 (disabled)",
                0, listener.getPingIntervalMs());
    }

    @Test
    public void testSetPingInterval() {
        Http2Listener listener = new Http2Listener();
        listener.setPingIntervalMs(30000);
        assertEquals(30000, listener.getPingIntervalMs());
    }

    @Test
    public void testDefaultMaxRequestBodySize() {
        Http2Listener listener = new Http2Listener();
        assertEquals(Http2Listener.DEFAULT_MAX_REQUEST_BODY_SIZE,
                listener.getMaxRequestBodySize());
    }

    @Test
    public void testSetMaxRequestBodySize() {
        Http2Listener listener = new Http2Listener();
        listener.setMaxRequestBodySize(1024);
        assertEquals(1024, listener.getMaxRequestBodySize());
    }

    @Test
    public void testZeroMaxRequestBodySizeMeansUnlimited() {
        Http2Listener listener = new Http2Listener();
        listener.setMaxRequestBodySize(0);
        assertEquals(0, listener.getMaxRequestBodySize());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxRequestBodySizeRejectsNegative() {
        Http2Listener listener = new Http2Listener();
        listener.setMaxRequestBodySize(-1);
    }

    @Test
    public void testFluentBindWildcard() {
        Http2Listener listener = new Http2Listener()
                .port(8443)
                .bindWildcard()
                .secure(true);
        assertTrue(listener.isWildcard());
        assertEquals(8443, listener.getPort());
        assertTrue(listener.isSecure());
    }

    @Test
    public void testFluentAddressesReplaceWildcard() throws Exception {
        InetAddress loopback = InetAddress.ofLiteral("127.0.0.1");
        Http2Listener listener = new Http2Listener()
                .bindWildcard()
                .addresses(loopback);
        assertFalse(listener.isWildcard());
        assertTrue(listener.getAddresses().contains(loopback));
    }
}
