/*
 * Http3ListenerConfigTest.java
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

package org.bluezoo.gumdrop.http.h3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HstsPolicy;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicConnectionTestFactory;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.junit.Test;

/**
 * Configuration and wiring tests for {@link Http3Listener}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Http3ListenerConfigTest {

    private static QuicTransportFactory factory(Http3Listener l) throws Exception {
        Method m = Http3Listener.class.getDeclaredMethod("createTransportFactory");
        m.setAccessible(true);
        return (QuicTransportFactory) m.invoke(l);
    }

    @Test
    public void testSimpleProperties() throws Exception {
        Http3Listener l = new Http3Listener();
        assertEquals("h3", l.getDescription());
        assertSame(l, l.port(4433));
        assertEquals(4433, l.getPort());
        assertSame(l, l.bindWildcard());
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        assertSame(l, l.addresses(lo));
        assertSame(l, l.secure(true));
        assertSame(l, l.requireRetry(false));
        assertFalse(l.isRequireRetry());
        assertTrue(l.getAddSecurityHeaders());
        l.setAddSecurityHeaders(false);
        assertFalse(l.getAddSecurityHeaders());
        HttpStreamHandler sh = new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponseState stream) {
                return new DefaultHttpRequestHandler();
            }
        };
        l.setStreamHandler(sh);
        assertSame(sh, l.getStreamHandler());
        assertNull(l.getAuthenticationProvider());
        assertNull(l.getMetrics());
        assertNull(l.getSelectorLoop());
        l.setSelectorLoop(null);
        l.setCompressResponses(true);
        assertTrue(l.getCompressResponses());
        l.setCompressResponses(false);
        assertFalse(l.getCompressResponses());
    }

    @Test
    public void testHsts() {
        Http3Listener l = new Http3Listener();
        assertFalse(l.isHstsEnabled());
        assertNull(l.getStrictTransportSecurityHeaderValue());
        l.setHstsEnabled(true);
        l.setHstsMaxAge(100L);
        l.setHstsIncludeSubDomains(true);
        l.setHstsPreload(true);
        String value = l.getStrictTransportSecurityHeaderValue();
        assertTrue(value, value.contains("max-age=100"));
        assertTrue(value.contains("includeSubDomains"));
        assertTrue(value.contains("preload"));
        HstsPolicy p = l.getHstsPolicy();
        assertTrue(p.isEnabled());
        l.setHstsPolicy(HstsPolicy.enabled(5L).includeSubDomains(false).preload(false));
        assertEquals(5L, l.getHstsPolicy().getMaxAgeSeconds());
        l.setHstsPolicy(null);
        assertFalse(l.isHstsEnabled());
        l.setHstsPolicy(HstsPolicy.disabled());
        assertFalse(l.isHstsEnabled());
    }

    @Test
    public void testTransportFactoryConfiguration() throws Exception {
        Http3Listener l = new Http3Listener();
        Path cert = Files.createTempFile("h3cert", ".pem");
        Path key = Files.createTempFile("h3key", ".pem");
        try {
            l.setCertFile(cert);
            l.setKeyFile(key);
            l.setQuicMaxIdleTimeout(1000);
            l.setQuicMaxData(2000);
            l.setQuicMaxStreamDataBidiLocal(3000);
            l.setQuicMaxStreamDataBidiRemote(4000);
            l.setQuicMaxStreamDataUni(5000);
            l.setQuicMaxStreamsBidi(6);
            l.setQuicMaxStreamsUni(7);
            assertNotNull(factory(l));
        } finally {
            Files.deleteIfExists(cert);
            Files.deleteIfExists(key);
        }
    }

    @Test
    public void testQuicLb() throws Exception {
        Http3Listener l = new Http3Listener();
        byte[] serverId = new byte[] {1, 2, 3};
        byte[] key = new byte[16];
        assertSame(l, l.quicLb(1, serverId, 8, key, false));
        assertNotNull(factory(l));
        l.setQuicLbServerIdLength(3);
        assertNotNull(factory(l));
        l.setQuicLbServerIdLength(4);
        try {
            factory(l);
            fail("expected exception");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
        l.setQuicLbServerIdLength(-1);
        l.setRequireRetry(false);
        try {
            factory(l);
            fail("expected exception");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        l.setRequireRetry(true);
        Path keyFile = Files.createTempFile("lbkey", ".hex");
        try {
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 32; i++) {
                hex.append('a');
            }
            Files.write(keyFile, hex.toString().getBytes(StandardCharsets.US_ASCII));
            l.setQuicLbCidKeyFile(keyFile);
            l.setQuicLbConfigId(2);
            l.setQuicLbNonceLength(10);
            l.setQuicLbFirstOctetEncodesCidLength(true);
            assertNotNull(factory(l));
            Files.write(keyFile, new byte[16]);
            assertNotNull(factory(l));
        } finally {
            Files.deleteIfExists(keyFile);
        }
        Http3Listener none = new Http3Listener();
        none.setQuicLbServerId("");
        assertNotNull(factory(none));
        Http3Listener hexId = new Http3Listener();
        hexId.setQuicLbServerId("0102");
        hexId.setQuicLbCidKeyFile(null);
        assertNotNull(hexId);
    }

    @Test
    public void testConnectionAccepted() throws Exception {
        Http3Listener l = new Http3Listener();
        l.setStreamHandler(new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponseState stream) {
                return null;
            }
        });
        QuicConnection conn = QuicConnectionTestFactory.create(true);
        l.connectionAccepted(conn);
        l.stop();
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testDeprecatedBuilder() throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        Http3Listener l = Http3Listener.builder().port(8443).addresses(lo).requireRetry(false).build();
        assertEquals(8443, l.getPort());
        assertFalse(l.isRequireRetry());
        try {
            Http3Listener.builder().tls((org.bluezoo.gumdrop.tls.TlsConfig) null);
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("tls", expected.getMessage());
        }
        Http3Listener plain = Http3Listener.builder().build();
        assertEquals(-1, plain.getPort());
    }
}
