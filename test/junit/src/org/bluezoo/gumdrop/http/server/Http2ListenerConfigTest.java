/*
 * Http2ListenerConfigTest.java
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


package org.bluezoo.gumdrop.http.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;

import org.bluezoo.gumdrop.tls.TlsConfig;
import org.junit.Test;

/**
 * Configuration accessors, validation and fluent/builder API of
 * {@link Http2Listener}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Http2ListenerConfigTest {

    @Test
    public void testDescriptionFollowsSecureFlag() {
        Http2Listener l = new Http2Listener();
        l.secure(false);
        assertEquals("http", l.getDescription());
        l.secure(true);
        assertEquals("https", l.getDescription());
    }

    @Test
    public void testFluentPortAndSecure() {
        Http2Listener l = new Http2Listener();
        Http2Listener r = l.port(8080);
        assertSame(l, r);
        assertEquals(8080, l.getPort());
        assertSame(l, l.secure(true));
        assertSame(l, l.bindWildcard());
        assertSame(l, l.addresses(InetAddress.getLoopbackAddress()));
    }

    @Test
    public void testFramePaddingBounds() {
        Http2Listener l = new Http2Listener();
        l.framePadding(0);
        l.framePadding(255);
        assertEquals(255, l.getFramePadding());
        try {
            l.framePadding(-1);
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            l.framePadding(256);
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testMaxConcurrentStreamsValidation() {
        Http2Listener l = new Http2Listener();
        l.maxConcurrentStreams(7);
        assertEquals(7, l.getMaxConcurrentStreams());
        try {
            l.maxConcurrentStreams(0);
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testMaxHeaderListSizeValidation() {
        Http2Listener l = new Http2Listener();
        l.maxHeaderListSize(1234);
        assertEquals(1234, l.getMaxHeaderListSize());
        try {
            l.maxHeaderListSize(0);
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testMaxRequestBodySizeValidation() {
        Http2Listener l = new Http2Listener();
        l.maxRequestBodySize(0);
        assertEquals(0L, l.getMaxRequestBodySize());
        l.maxRequestBodySize(99L);
        assertEquals(99L, l.getMaxRequestBodySize());
        try {
            l.maxRequestBodySize(-1);
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testSimpleProperties() {
        Http2Listener l = new Http2Listener();
        l.altSvc("h3=\":443\"");
        assertEquals("h3=\":443\"", l.getAltSvc());
        l.idleTimeoutMs(1500L);
        assertEquals(1500L, l.getIdleTimeoutMs());
        l.maxRequestsPerConnection(5);
        assertEquals(5, l.getMaxRequestsPerConnection());
        l.traceMethodEnabled(true);
        assertTrue(l.isTraceMethodEnabled());
        l.pingIntervalMs(250L);
        assertEquals(250L, l.getPingIntervalMs());
        l.compressResponses(false);
        assertFalse(l.getCompressResponses());
        l.addSecurityHeaders(false);
        assertFalse(l.getAddSecurityHeaders());
        assertNull(l.getAuthenticationProvider());
        assertNull(l.getStreamHandler());
        assertNull(l.getMetrics());
        l.stop();
    }

    @Test
    public void testHstsConfiguration() {
        Http2Listener l = new Http2Listener();
        assertFalse(l.isHstsEnabled());
        l.secure(true);
        l.hstsEnabled(true);
        l.hstsMaxAge(3600L);
        l.hstsIncludeSubDomains(true);
        l.hstsPreload(true);
        assertTrue(l.isHstsEnabled());
        String value = l.getStrictTransportSecurityHeaderValue();
        assertTrue(value, value.contains("max-age=3600"));
        assertTrue(value, value.contains("includeSubDomains"));
        assertTrue(value, value.contains("preload"));
        HstsPolicy p = l.getHstsPolicy();
        assertEquals(3600L, p.getMaxAgeSeconds());
        try {
            l.hstsMaxAge(-1L);
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testHstsHeaderOnlyOverTls() {
        Http2Listener l = new Http2Listener();
        l.secure(false);
        l.hstsEnabled(true);
        assertNull(l.getStrictTransportSecurityHeaderValue());
    }

    @Test
    public void testSetHstsPolicyEnabledAndDisabled() {
        Http2Listener l = new Http2Listener();
        HstsPolicy on = HstsPolicy.enabled(120L);
        l.hstsPolicy(on);
        assertTrue(l.isHstsEnabled());
        assertEquals(120L, l.getHstsPolicy().getMaxAgeSeconds());
        l.hstsPolicy(HstsPolicy.disabled());
        assertFalse(l.isHstsEnabled());
        l.hstsEnabled(true);
        l.hstsPolicy(null);
        assertFalse(l.isHstsEnabled());
    }

    @Test
    public void testCreateHandlerUsesConfiguration() {
        Http2Listener l = new Http2Listener();
        l.maxConcurrentStreams(3);
        Object handler = l.createHandler();
        assertTrue(handler instanceof HttpProtocolHandler);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testBuilder() {
        TlsConfig tls = TlsConfig.pem(java.nio.file.Paths.get("c.pem"),
                java.nio.file.Paths.get("k.pem"));
        Http2Listener.Builder b = Http2Listener.builder();
        Http2Listener.Builder b2 = b.port(9443);
        assertSame(b, b2);
        b.addresses(InetAddress.getLoopbackAddress());
        b.secure(true);
        b.tls(tls);
        Http2Listener l = b.build();
        assertEquals(9443, l.getPort());
        assertEquals("https", l.getDescription());
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testBuilderLegacyTlsAndNullRejection() {
        Http2Listener.Builder b = Http2Listener.builder();
        HttpTlsConfig legacy = HttpTlsConfig.pem(java.nio.file.Paths.get("c.pem"),
                java.nio.file.Paths.get("k.pem"));
        b.tls(legacy);
        Http2Listener plain = Http2Listener.builder().build();
        assertNotNull(plain);
        try {
            b.tls((TlsConfig) null);
            fail();
        } catch (NullPointerException expected) {
            assertNotNull(expected);
        }
        try {
            b.tls((HttpTlsConfig) null);
            fail();
        } catch (NullPointerException expected) {
            assertNotNull(expected);
        }
    }
}
