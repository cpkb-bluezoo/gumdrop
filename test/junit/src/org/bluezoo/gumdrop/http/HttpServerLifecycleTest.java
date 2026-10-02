/*
 * HttpServerLifecycleTest.java
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

package org.bluezoo.gumdrop.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.http.h3.Http3Listener;
import org.bluezoo.gumdrop.http.server.DefaultHttpAuthenticationProvider;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.http.server.HstsPolicy;
import org.bluezoo.gumdrop.http.server.HttpAuthenticationProvider;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponseState;
import org.bluezoo.gumdrop.http.server.HttpServerServiceHook;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Configuration, listener wiring, Alt-Svc computation and lifecycle
 * hooks of {@link HttpServer} and the server composed by
 * {@link HttpServer#compose()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpServerLifecycleTest {

    private static final class Hook implements HttpStreamHandler, HttpServerServiceHook {
        final List<String> calls = new ArrayList<String>();
        HttpAuthenticationProvider provider;

        @Override
        public HttpRequestHandler openStream(HttpResponseState stream) {
            return new DefaultHttpRequestHandler();
        }

        @Override
        public void initService(Gumdrop gumdrop) {
            calls.add("init");
        }

        @Override
        public void destroyService() {
            calls.add("destroy");
        }

        @Override
        public HttpAuthenticationProvider getAuthenticationProvider() {
            return provider;
        }
    }

    private static final class Plain implements HttpStreamHandler {
        @Override
        public HttpRequestHandler openStream(HttpResponseState stream) {
            return new DefaultHttpRequestHandler();
        }
    }

    private static final class Bare extends HttpServer {
        final List<String> calls = new ArrayList<String>();

        @Override
        protected void initService(Gumdrop gumdrop) {
            calls.add("init");
        }

        @Override
        protected void destroyService() {
            calls.add("destroy");
        }
    }

    private static Http3Listener h3(int port) {
        Http3Listener l = new Http3Listener();
        l.setPort(port);
        l.setSelectorLoop(new InlineSelectorLoop());
        return l;
    }

    @Test
    public void hstsConfigurationRoundTrips() {
        Bare s = new Bare();
        assertFalse(s.getHstsPolicy().isEnabled());
        s.setHstsPolicy(HstsPolicy.enabled(77L).includeSubDomains(true).preload(true));
        HstsPolicy p = s.getHstsPolicy();
        assertTrue(p.isEnabled());
        assertEquals(77L, p.getMaxAgeSeconds());
        assertTrue(p.isIncludeSubDomains());
        assertTrue(p.isPreload());
        s.setHstsPolicy(null);
        assertFalse(s.getHstsPolicy().isEnabled());
        s.setHstsPolicy(HstsPolicy.enabled(5L));
        s.setHstsPolicy(HstsPolicy.disabled());
        assertFalse(s.getHstsPolicy().isEnabled());
        s.setHstsEnabled(true);
        s.setHstsMaxAge(600L);
        s.setHstsIncludeSubDomains(true);
        s.setHstsPreload(false);
        p = s.getHstsPolicy();
        assertEquals(600L, p.getMaxAgeSeconds());
        assertTrue(p.isIncludeSubDomains());
        assertFalse(p.isPreload());
        try {
            s.setHstsMaxAge(-1L);
            fail();
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void basicAccessorsAndListenerList() {
        Bare s = new Bare();
        assertNull(s.getRealm());
        assertTrue(s.isAddSecurityHeaders());
        s.setAddSecurityHeaders(false);
        assertFalse(s.isAddSecurityHeaders());
        Realm realm = new org.bluezoo.gumdrop.auth.BasicRealm();
        s.setRealm(realm);
        assertSame(realm, s.getRealm());
        Http2Listener a = new Http2Listener();
        Http3Listener b = new Http3Listener();
        s.setListeners(Arrays.asList(a, "ignored", b));
        assertEquals(2, s.getListeners().size());
        try {
            s.getListeners().clear();
            fail();
        } catch (UnsupportedOperationException expected) {
            assertNotNull(s.getListeners());
        }
        assertNull(s.getStreamHandler());
        assertNull(s.getAuthenticationProvider());
    }

    @Test
    public void startWiresListenersAndAdvertisesAltSvc() {
        Hook hook = new Hook();
        Http2Listener secure = new Http2Listener();
        secure.setSecure(true);
        Http2Listener plain = new Http2Listener();
        Http3Listener quicA = h3(4443);
        Http3Listener quicB = h3(5443);
        Http3Listener quicNone = h3(0);
        HttpServer server = HttpServer.compose()
                .listener(secure)
                .listener(plain)
                .listener(quicA)
                .listener(quicB)
                .listener(quicNone)
                .streamHandler(hook)
                .addSecurityHeaders(false)
                .hsts(HstsPolicy.enabled(120L))
                .realm(new org.bluezoo.gumdrop.auth.BasicRealm())
                .server();
        server.start(null);
        assertEquals(Arrays.asList("init"), hook.calls);
        assertSame(hook, secure.getStreamHandler());
        assertTrue(secure.getAuthenticationProvider() instanceof DefaultHttpAuthenticationProvider);
        assertFalse(secure.getAddSecurityHeaders());
        assertTrue(secure.isHstsEnabled());
        assertFalse(plain.isHstsEnabled());
        assertEquals("h3=\":4443\"; ma=86400, h3=\":5443\"; ma=86400", secure.getAltSvc());
        assertTrue(quicA.isHstsEnabled());
        assertSame(hook, quicA.getStreamHandler());
        assertFalse(quicA.getAddSecurityHeaders());
        server.stop();
        assertEquals(Arrays.asList("init", "destroy"), hook.calls);
    }

    @Test
    public void hookProviderWinsOverRealmAndNoAltSvcWithoutBothKinds() {
        Hook hook = new Hook();
        hook.provider = new DefaultHttpAuthenticationProvider(new org.bluezoo.gumdrop.auth.BasicRealm());
        Http2Listener tcp = new Http2Listener();
        HttpServer server = HttpServer.compose().listener(tcp).streamHandler(hook).server();
        server.start(null);
        assertSame(hook.provider, tcp.getAuthenticationProvider());
        assertNull(tcp.getAltSvc());

        Http3Listener only = h3(4443);
        HttpServer quicOnly = HttpServer.compose().listener(only).server();
        quicOnly.start(null);
        assertEquals(1, quicOnly.getListeners().size());
        assertFalse(only.isHstsEnabled());
    }

    @Test
    public void plainHandlerAndBareServerLifecycle() {
        Http2Listener tcp = new Http2Listener();
        HttpServer server = HttpServer.compose().listener(tcp).streamHandler(new Plain()).server();
        server.start(null);
        assertNull(tcp.getAuthenticationProvider());
        server.stop();

        Bare bare = new Bare();
        bare.addListener(new Http2Listener());
        bare.start(null);
        bare.stop();
        assertEquals(Arrays.asList("init", "destroy"), bare.calls);
    }

    @Test
    public void composerValidationAndDeprecatedForms() {
        try {
            HttpServer.compose().listener((Http2Listener) null);
            fail();
        } catch (NullPointerException expected) {
            assertEquals("listener", expected.getMessage());
        }
        try {
            HttpServer.compose().listener((Http3Listener) null);
            fail();
        } catch (NullPointerException expected) {
            assertEquals("listener", expected.getMessage());
        }
        try {
            HttpServer.compose().streamHandler(null);
            fail();
        } catch (NullPointerException expected) {
            assertEquals("streamHandler", expected.getMessage());
        }
        try {
            HttpServer.compose().secureEndpoint(1, (org.bluezoo.gumdrop.tls.TlsConfig) null);
            fail();
        } catch (NullPointerException expected) {
            assertEquals("tls", expected.getMessage());
        }
        try {
            HttpServer.compose().secureEndpoint(1, (org.bluezoo.gumdrop.http.server.HttpTlsConfig) null);
            fail();
        } catch (NullPointerException expected) {
            assertEquals("tls", expected.getMessage());
        }
        HttpServer viaBuilder = HttpServer.builder().plaintextListener(9).hsts(null).realm(null).build();
        assertEquals(1, viaBuilder.getListeners().size());
        assertFalse(viaBuilder.getHstsPolicy().isEnabled());
    }
}
