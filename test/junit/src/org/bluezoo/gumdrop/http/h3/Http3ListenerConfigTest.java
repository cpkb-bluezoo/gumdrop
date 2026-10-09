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
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.quic.QuicConnection;
import org.bluezoo.gumdrop.quic.QuicConnectionTestFactory;
import org.bluezoo.gumdrop.quic.QuicTransportFactory;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Test;

/**
 * Configuration and wiring tests for {@link Http3Listener}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Http3ListenerConfigTest {

    private final MemoryFileSystem memFs = MemoryFileSystem.create();

    /** Creates an empty file on the in-memory file system. */
    private Path memFile(String name) throws java.io.IOException {
        Path p = memFs.getPath(name);
        Files.createFile(p);
        return p;
    }


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
        l.addSecurityHeaders(false);
        assertFalse(l.getAddSecurityHeaders());
        HttpStreamHandler sh = new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponse stream) {
                return new DefaultHttpRequestHandler();
            }
        };
        l.streamHandler(sh);
        assertSame(sh, l.getStreamHandler());
        assertNull(l.getAuthenticationProvider());
        assertNull(l.getMetrics());
        assertNull(l.getSelectorLoop());
        l.selectorLoop(null);
        l.compressResponses(true);
        assertTrue(l.getCompressResponses());
        l.compressResponses(false);
        assertFalse(l.getCompressResponses());
    }

    @Test
    public void testHsts() {
        Http3Listener l = new Http3Listener();
        assertFalse(l.isHstsEnabled());
        assertNull(l.getStrictTransportSecurityHeaderValue());
        l.hstsEnabled(true);
        l.hstsMaxAge(100L);
        l.hstsIncludeSubDomains(true);
        l.hstsPreload(true);
        String value = l.getStrictTransportSecurityHeaderValue();
        assertTrue(value, value.contains("max-age=100"));
        assertTrue(value.contains("includeSubDomains"));
        assertTrue(value.contains("preload"));
        HstsPolicy p = l.getHstsPolicy();
        assertTrue(p.isEnabled());
        l.hstsPolicy(HstsPolicy.enabled(5L).includeSubDomains(false).preload(false));
        assertEquals(5L, l.getHstsPolicy().getMaxAgeSeconds());
        l.hstsPolicy(null);
        assertFalse(l.isHstsEnabled());
        l.hstsPolicy(HstsPolicy.disabled());
        assertFalse(l.isHstsEnabled());
    }

    @Test
    public void testTransportFactoryConfiguration() throws Exception {
        Http3Listener l = new Http3Listener();
        Path cert = memFile("/h3cert.pem");
        Path key = memFile("/h3key.pem");
        try {
            l.setCertFile(cert);
            l.setKeyFile(key);
            l.quicMaxIdleTimeout(1000);
            l.quicMaxData(2000);
            l.quicMaxStreamDataBidiLocal(3000);
            l.quicMaxStreamDataBidiRemote(4000);
            l.quicMaxStreamDataUni(5000);
            l.quicMaxStreamsBidi(6);
            l.quicMaxStreamsUni(7);
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
        l.quicLbServerIdLength(3);
        assertNotNull(factory(l));
        l.quicLbServerIdLength(4);
        try {
            factory(l);
            fail("expected exception");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
        l.quicLbServerIdLength(-1);
        l.requireRetry(false);
        try {
            factory(l);
            fail("expected exception");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        l.requireRetry(true);
        Path keyFile = memFile("/lbkey.hex");
        try {
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 32; i++) {
                hex.append('a');
            }
            Files.write(keyFile, hex.toString().getBytes(StandardCharsets.US_ASCII));
            l.quicLbCidKeyFile(keyFile);
            l.quicLbConfigId(2);
            l.quicLbNonceLength(10);
            l.quicLbFirstOctetEncodesCidLength(true);
            assertNotNull(factory(l));
            Files.write(keyFile, new byte[16]);
            assertNotNull(factory(l));
        } finally {
            Files.deleteIfExists(keyFile);
        }
        Http3Listener none = new Http3Listener();
        none.quicLbServerId("");
        assertNotNull(factory(none));
        Http3Listener hexId = new Http3Listener();
        hexId.quicLbServerId("0102");
        hexId.quicLbCidKeyFile(null);
        assertNotNull(hexId);
    }

    @Test
    public void testConnectionAccepted() throws Exception {
        Http3Listener l = new Http3Listener();
        l.streamHandler(new HttpStreamHandler() {
            @Override
            public HttpRequestHandler openStream(HttpResponse stream) {
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
