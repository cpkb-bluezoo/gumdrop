/*
 * OAuthRealmIntrospectionTest.java
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

package org.bluezoo.gumdrop.auth.oauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.HttpStatus;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;
import org.bluezoo.gumdrop.http.client.HttpRequest;
import org.bluezoo.gumdrop.http.client.HttpResponseHandler;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.junit.Test;

/**
 * Token introspection (RFC 7662) in {@link OAuthRealm} against a mock
 * authorization server. The realm's exchange seam is replaced by an
 * in-memory one that plays the HTTP response synchronously (status, body
 * chunks, failure or silence), so every outcome of the streaming
 * introspection handler is reached with no socket, loop or timing. The
 * real loopback exchange stays in the integration test.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OAuthRealmIntrospectionTest {

    /** Mock request that records what the realm sent and then replies. */
    private static final class MockRequest implements HttpRequest {
        final ScriptedExchange owner;
        final Map<String, String> headers = new HashMap<String, String>();
        final StringBuilder body = new StringBuilder();
        HttpResponseHandler handler;

        MockRequest(ScriptedExchange owner) {
            this.owner = owner;
        }

        @Override
        public void header(String name, String value) {
            headers.put(name, value);
        }

        @Override
        public void priority(int weight) {
        }

        @Override
        public void dependency(HttpRequest parent) {
        }

        @Override
        public void exclusive(boolean exclusive) {
        }

        @Override
        public void send(HttpResponseHandler handler) {
            this.handler = handler;
        }

        @Override
        public void startRequestBody(HttpResponseHandler handler) {
            this.handler = handler;
        }

        @Override
        public int requestBodyContent(ByteBuffer data) {
            int n = data.remaining();
            byte[] b = new byte[n];
            data.get(b);
            body.append(new String(b, StandardCharsets.UTF_8));
            return n;
        }

        @Override
        public void endRequestBody() {
            owner.reply(handler);
        }

        @Override
        public void cancel() {
        }
    }

    /** Exchange that answers each call from a script. */
    private static final class ScriptedExchange implements OAuthRealm.Exchange {
        final List<MockRequest> requests = new ArrayList<MockRequest>();
        final List<String> paths = new ArrayList<String>();
        final List<String> events = new ArrayList<String>();
        final BinaryRecordingEndpoint endpoint = new BinaryRecordingEndpoint();
        int status = 200;
        List<String> chunks = new ArrayList<String>();
        Exception connectError;
        Exception requestFailure;
        boolean silent;
        boolean tls;

        void respond(int code, String... parts) {
            status = code;
            chunks = new ArrayList<String>();
            for (int i = 0; i < parts.length; i++) {
                chunks.add(parts[i]);
            }
        }

        @Override
        public void connect(HttpClient client, Gumdrop gumdrop,
                HttpClientHandler handler) {
            events.add("connect");
            if (connectError != null) {
                handler.onError(connectError);
                return;
            }
            if (tls) {
                handler.onSecurityEstablished(new SecurityInfo() {
                    @Override
                    public String getProtocol() {
                        return "TLSv1.3";
                    }

                    @Override
                    public String getCipherSuite() {
                        return "TLS_AES_128_GCM_SHA256";
                    }

                    @Override
                    public int getKeySize() {
                        return 128;
                    }

                    @Override
                    public Certificate[] getPeerCertificates() {
                        return null;
                    }

                    @Override
                    public Certificate[] getLocalCertificates() {
                        return null;
                    }

                    @Override
                    public String getApplicationProtocol() {
                        return null;
                    }

                    @Override
                    public long getHandshakeDurationMs() {
                        return 0L;
                    }

                    @Override
                    public boolean isSessionResumed() {
                        return false;
                    }
                });
            }
            handler.onConnected(endpoint);
            handler.onDisconnected();
        }

        @Override
        public HttpRequest post(HttpClient client, String path) {
            paths.add(path);
            MockRequest r = new MockRequest(this);
            requests.add(r);
            return r;
        }

        void reply(HttpResponseHandler h) {
            if (silent) {
                return;
            }
            if (requestFailure != null) {
                h.failed(requestFailure);
                return;
            }
            HttpStatus st = HttpStatus.OK;
            if (status == 401) {
                st = HttpStatus.UNAUTHORIZED;
            } else if (status == 500) {
                st = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            h.status(st.code);
            h.endHeaders();
            for (int i = 0; i < chunks.size(); i++) {
                byte[] b = chunks.get(i).getBytes(StandardCharsets.UTF_8);
                h.bodyContent(ByteBuffer.wrap(b));
            }
            h.endMessage();
        }
    }

    private static Properties config(String url, boolean cache) {
        Properties config = new Properties();
        config.setProperty("oauth.authorization.server.url", url);
        config.setProperty("oauth.client.id", "cid");
        config.setProperty("oauth.client.secret", "sec");
        // zero: a reply that is not already in hand counts as a timeout, with no waiting
        config.setProperty("oauth.http.timeout", "0");
        config.setProperty("oauth.cache.enabled", Boolean.toString(cache));
        config.setProperty("oauth.scope.mapping.reader", "read");
        return config;
    }

    private static OAuthRealm realm(Properties config, ScriptedExchange exchange) {
        OAuthRealm unbound = new OAuthRealm(config);
        Realm bound = unbound.forSelectorLoop(new InlineSelectorLoop());
        OAuthRealm realm = (OAuthRealm) bound;
        realm.exchange = exchange;
        return realm;
    }

    private static OAuthRealm realm(boolean cache, ScriptedExchange exchange) {
        return realm(config("http://auth.test:8080", cache), exchange);
    }

    @Test
    public void activeTokenYieldsUserScopesAndExpiry() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.respond(200, "{\"active\":true,\"username\":\"alice\",\"scope\":\"  read   write \","
                + "\"exp\":4102444800,\"client_id\":\"x\",\"nested\":{\"exp\":1}}");
        OAuthRealm realm = realm(false, ex);
        Realm.TokenValidationResult r = realm.validateOAuthToken("tok+en");
        assertNotNull(r);
        assertTrue(r.valid);
        assertEquals("alice", r.username);
        assertEquals(2, r.scopes.length);
        assertEquals("read", r.scopes[0]);
        assertEquals("write", r.scopes[1]);
        assertEquals(4102444800L, r.expirationTime);
        assertTrue(realm.isUserInRole("alice", "reader"));
        assertFalse(realm.isUserInRole("alice", "unmapped"));
        assertEquals(1, ex.requests.size());
        assertEquals("/oauth/introspect", ex.paths.get(0));
        MockRequest req = ex.requests.get(0);
        assertEquals("token=tok%2Ben&token_type_hint=access_token", req.body.toString());
        assertEquals("application/x-www-form-urlencoded", req.headers.get("Content-Type"));
        assertEquals("application/json", req.headers.get("Accept"));
        assertTrue(req.headers.get("Authorization").startsWith("Basic "));
    }

    @Test
    public void bodyArrivingInSmallChunksIsParsedIncrementally() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.respond(200, "{\"act", "ive\":tr", "ue,\"user", "name\":\"car", "ol\"}");
        OAuthRealm realm = realm(false, ex);
        Realm.TokenValidationResult r = realm.validateOAuthToken("t");
        assertTrue(r.valid);
        assertEquals("carol", r.username);
    }

    @Test
    public void customIntrospectionEndpointIsUsed() {
        Properties config = config("http://auth.test", false);
        config.setProperty("oauth.token.introspection.endpoint", "/custom/check");
        ScriptedExchange ex = new ScriptedExchange();
        ex.respond(200, "{\"active\":true,\"username\":\"u\"}");
        OAuthRealm realm = realm(config, ex);
        assertTrue(realm.validateBearerToken("t").valid);
        assertEquals("/custom/check", ex.paths.get(0));
    }

    @Test
    public void subjectIsUsedWhenUsernameMissing() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.respond(200, "{\"active\":true,\"sub\":\"subject-1\",\"scope\":\"\"}");
        Realm.TokenValidationResult r = realm(false, ex).validateOAuthToken("t");
        assertTrue(r.valid);
        assertEquals("subject-1", r.username);
        assertEquals(0, r.scopes.length);
    }

    @Test
    public void activeTokenWithoutAnyIdentityIsRejected() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.respond(200, "{\"active\":true,\"username\":\"\"}");
        assertFalse(realm(false, ex).validateOAuthToken("t").valid);
    }

    @Test
    public void nestedMembersCannotOverrideTopLevelFields() {
        ScriptedExchange ex = new ScriptedExchange();
        OAuthRealm realm = realm(false, ex);
        ex.respond(200, "{\"active\":false,\"ext\":{\"active\":true,\"username\":\"mallory\"},"
                + "\"list\":[{\"active\":true}]}");
        assertFalse(realm.validateOAuthToken("t").valid);
        ex.respond(200, "{\"active\":true,\"username\":\"alice\",\"ext\":{\"username\":\"mallory\","
                + "\"scope\":\"admin\"},\"scope\":\"read\"}");
        Realm.TokenValidationResult r = realm.validateOAuthToken("t2");
        assertTrue(r.valid);
        assertEquals("alice", r.username);
        assertEquals(1, r.scopes.length);
        assertEquals("read", r.scopes[0]);
    }

    @Test
    public void inactiveTokenIsRejected() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.respond(200, "{\"active\":false}");
        assertFalse(realm(false, ex).validateOAuthToken("t").valid);
    }

    @Test
    public void errorStatusIsRejectedEvenWithActiveBody() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.respond(401, "{\"active\":true,\"username\":\"mallory\"}");
        assertFalse(realm(false, ex).validateOAuthToken("t").valid);
        ex.respond(500, "");
        assertFalse(realm(false, ex).validateOAuthToken("t").valid);
    }

    @Test
    public void malformedJsonIsRejected() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.respond(200, "{\"active\": tru");
        assertFalse(realm(false, ex).validateOAuthToken("t").valid);
    }

    @Test
    public void garbageAfterParseErrorIsIgnored() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.respond(200, "{]", "more", "{\"active\":true,\"username\":\"x\"}");
        assertFalse(realm(false, ex).validateOAuthToken("t").valid);
    }

    @Test
    public void emptyBodyOnSuccessIsRejected() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.respond(200);
        assertFalse(realm(false, ex).validateOAuthToken("t").valid);
    }

    @Test
    public void connectionErrorIsRejected() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.connectError = new IOException("refused");
        OAuthRealm realm = realm(false, ex);
        assertFalse(realm.validateOAuthToken("t").valid);
        assertTrue(ex.requests.isEmpty());
    }

    @Test
    public void requestFailureIsRejected() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.requestFailure = new IOException("reset");
        assertFalse(realm(false, ex).validateOAuthToken("t").valid);
    }

    @Test
    public void silentServerTimesOutAndIsRejected() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.silent = true;
        OAuthRealm realm = realm(false, ex);
        assertFalse(realm.validateOAuthToken("t").valid);
        assertEquals(1, ex.requests.size());
    }

    @Test
    public void secureServerReportsTlsHandshakeToTheHandler() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.tls = true;
        ex.respond(200, "{\"active\":true,\"username\":\"tls-user\"}");
        OAuthRealm realm = realm(config("https://auth.test", false), ex);
        assertTrue(realm.validateOAuthToken("t").valid);
        assertEquals(1, ex.requests.size());
    }

    @Test
    public void cachedResultAvoidsSecondRequest() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.respond(200, "{\"active\":true,\"username\":\"bob\",\"scope\":\"read\"}");
        OAuthRealm realm = realm(true, ex);
        Realm.TokenValidationResult first = realm.validateOAuthToken("same");
        Realm.TokenValidationResult second = realm.validateOAuthToken("same");
        assertTrue(first.valid);
        assertSame(first, second);
        assertEquals(1, ex.requests.size());
    }

    @Test
    public void failedValidationIsNotCached() {
        ScriptedExchange ex = new ScriptedExchange();
        ex.respond(200, "{\"active\":false}");
        OAuthRealm realm = realm(true, ex);
        assertFalse(realm.validateOAuthToken("same").valid);
        ex.respond(200, "{\"active\":true,\"username\":\"late\"}");
        assertTrue(realm.validateOAuthToken("same").valid);
        assertEquals(2, ex.requests.size());
    }

    @Test
    public void fullCacheEvictsToMakeRoom() {
        Properties config = config("http://auth.test", true);
        config.setProperty("oauth.cache.max.size", "2");
        config.setProperty("oauth.cache.ttl", "3600");
        ScriptedExchange ex = new ScriptedExchange();
        ex.respond(200, "{\"active\":true,\"username\":\"u\"}");
        OAuthRealm realm = realm(config, ex);
        for (int i = 0; i < 6; i++) {
            assertTrue(realm.validateOAuthToken("token-" + i).valid);
        }
        assertEquals(6, ex.requests.size());
    }

    @Test
    public void blankTokenNeverContactsServer() {
        ScriptedExchange ex = new ScriptedExchange();
        OAuthRealm realm = realm(false, ex);
        assertFalse(realm.validateOAuthToken(null).valid);
        assertFalse(realm.validateOAuthToken("   ").valid);
        assertFalse(realm.validateBearerToken("").valid);
        assertTrue(ex.events.isEmpty());
    }

    @Test
    public void rebindingToTheSameLoopKeepsTheRealm() {
        OAuthRealm realm = new OAuthRealm(config("http://auth.test", false));
        InlineSelectorLoop loop = new InlineSelectorLoop();
        Realm bound = realm.forSelectorLoop(loop);
        assertNotSame(realm, bound);
        assertSame(bound, bound.forSelectorLoop(loop));
        assertSame(realm, realm.forSelectorLoop(null));
    }

    @Test
    public void unboundRealmCannotIntrospect() {
        ScriptedExchange ex = new ScriptedExchange();
        OAuthRealm unbound = new OAuthRealm(config("http://auth.test", false));
        unbound.exchange = ex;
        assertFalse(unbound.validateOAuthToken("t").valid);
        assertTrue(ex.events.isEmpty());
    }
}
