/*
 * OAuthRealmConfigAndCacheTest.java
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.RealmCalls;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.junit.Test;

/**
 * Tests for {@link OAuthRealm} configuration, role/scope mapping, token
 * caching and the paths of {@code validateOAuthToken} that do not need an
 * HTTP introspection round trip.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OAuthRealmConfigAndCacheTest {

    private static final String SECRET = "another-secret-key-for-hmac-tests";

    private static Properties base() {
        Properties p = new Properties();
        p.setProperty("oauth.authorization.server.url", "http://auth.example.com:8080");
        p.setProperty("oauth.client.id", "cid");
        p.setProperty("oauth.client.secret", "csecret");
        return p;
    }

    private static Properties jwtProps() {
        Properties p = base();
        p.setProperty("oauth.jwt.enabled", "true");
        p.setProperty("oauth.jwt.secret", SECRET);
        return p;
    }

    private static String jwt(String payloadJson) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String input = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + enc.encodeToString(sig);
    }

    private static long future() {
        return System.currentTimeMillis() / 1000 + 3600;
    }

    @Test
    public void missingRequiredPropertyRejected() {
        String[] keys = new String[] {
            "oauth.authorization.server.url", "oauth.client.id", "oauth.client.secret"
        };
        for (int i = 0; i < keys.length; i++) {
            Properties p = base();
            p.remove(keys[i]);
            try {
                new OAuthRealm(p);
                fail("expected failure without " + keys[i]);
            } catch (IllegalArgumentException expected) {
                // expected
            }
            p.setProperty(keys[i], "   ");
            try {
                new OAuthRealm(p);
                fail("expected failure for blank " + keys[i]);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    public void basicRealmContract() {
        OAuthRealm realm = new OAuthRealm(base());
        assertFalse(RealmCalls.passwordMatch(realm, "u", "p"));
        assertNull(RealmCalls.getDigestHA1(realm, "u", "r"));
        Set<SaslMechanism> mechs = realm.getSupportedSASLMechanisms();
        assertEquals(1, mechs.size());
        assertTrue(mechs.contains(SaslMechanism.OAUTHBEARER));
        assertSame(realm, realm.forSelectorLoop(null));
    }

    @Test
    public void configurationSummary() {
        Properties p = base();
        p.setProperty("oauth.scope.mapping.admin", "a:write, a:admin");
        p.setProperty("oauth.cache.enabled", "true");
        p.setProperty("oauth.log.level", "bogus-level");
        OAuthRealm realm = new OAuthRealm(p);
        String s = realm.getConfigurationSummary();
        assertTrue(s.contains("server=http://auth.example.com:8080"));
        assertTrue(s.contains("client=cid"));
        assertTrue(s.contains("endpoint=/oauth/introspect"));
        assertTrue(s.contains("cacheEnabled=true"));
        assertTrue(s.contains("admin"));
    }

    @Test
    public void httpsDefaultPortAndCustomEndpoint() {
        Properties p = base();
        p.setProperty("oauth.authorization.server.url", "https://auth.example.com");
        p.setProperty("oauth.token.introspection.endpoint", "/introspect");
        OAuthRealm realm = new OAuthRealm(p);
        assertTrue(realm.getConfigurationSummary().contains("endpoint=/introspect"));
    }

    @Test
    public void scopeMatching() {
        Properties p = base();
        p.setProperty("oauth.scope.mapping.admin", "a:write, a:admin");
        OAuthRealm realm = new OAuthRealm(p);
        assertTrue(realm.hasRequiredScopes(new String[] {"x", "a:admin"}, new String[] {"a:write", "a:admin"}));
        assertFalse(realm.hasRequiredScopes(new String[] {"x"}, new String[] {"a:write"}));
        assertFalse(realm.hasRequiredScopes(null, new String[] {"a"}));
        assertFalse(realm.hasRequiredScopes(new String[] {"a"}, null));
        assertTrue(realm.hasRoleByScopes(new String[] {"a:write"}, "admin"));
        assertFalse(realm.hasRoleByScopes(new String[] {"a:write"}, "nobody"));
    }

    @Test
    public void validateRejectsEmptyTokens() {
        OAuthRealm realm = new OAuthRealm(base());
        assertFalse(RealmCalls.validateOAuthToken(realm, null).valid);
        assertFalse(RealmCalls.validateOAuthToken(realm, "   ").valid);
        assertFalse(RealmCalls.validateBearerToken(realm, "").valid);
    }

    @Test
    public void opaqueTokenWithoutSelectorLoopFails() {
        OAuthRealm realm = new OAuthRealm(base());
        assertFalse(RealmCalls.validateOAuthToken(realm, "opaque-token").valid);
        OAuthRealm jwtRealm = new OAuthRealm(jwtProps());
        assertFalse(RealmCalls.validateOAuthToken(jwtRealm, "opaque-token").valid);
    }

    @Test
    public void invalidJwtDoesNotFallBackToIntrospection() throws Exception {
        OAuthRealm realm = new OAuthRealm(jwtProps());
        String expired = jwt("{\"sub\":\"u\",\"exp\":1}");
        assertFalse(RealmCalls.validateOAuthToken(realm, expired).valid);
    }

    @Test
    public void validJwtPopulatesRoleLookup() throws Exception {
        Properties p = jwtProps();
        p.setProperty("oauth.scope.mapping.admin", "a:admin");
        p.setProperty("oauth.scope.mapping.empty", "");
        OAuthRealm realm = new OAuthRealm(p);
        String token = jwt("{\"sub\":\"alice\",\"scope\":\"a:admin read\",\"exp\":" + future() + "}");
        Realm.TokenValidationResult r = RealmCalls.validateBearerToken(realm, token);
        assertTrue(r.valid);
        assertEquals("alice", r.username);
        assertTrue(RealmCalls.isUserInRole(realm, "alice", "admin"));
        assertFalse(RealmCalls.isUserInRole(realm, "alice", "unmapped"));
        assertFalse(RealmCalls.isUserInRole(realm, "alice", "empty"));
        assertFalse(RealmCalls.isUserInRole(realm, "bob", "admin"));
    }

    @Test
    public void expiredUserResultIsEvictedFromRoleLookup() throws Exception {
        Properties p = jwtProps();
        p.setProperty("oauth.scope.mapping.admin", "a:admin");
        p.setProperty("oauth.jwt.clock.skew", "100000");
        OAuthRealm realm = new OAuthRealm(p);
        String token = jwt("{\"sub\":\"alice\",\"scope\":\"a:admin\",\"exp\":"
                + (System.currentTimeMillis() / 1000 - 10) + "}");
        Realm.TokenValidationResult r = RealmCalls.validateOAuthToken(realm, token);
        assertTrue(r.valid);
        assertFalse(RealmCalls.isUserInRole(realm, "alice", "admin"));
    }

    @Test
    public void cachedResultIsReturnedForRepeatedToken() throws Exception {
        Properties p = jwtProps();
        p.setProperty("oauth.cache.enabled", "true");
        OAuthRealm realm = new OAuthRealm(p);
        String token = jwt("{\"sub\":\"alice\",\"exp\":" + future() + "}");
        Realm.TokenValidationResult first = RealmCalls.validateOAuthToken(realm, token);
        Realm.TokenValidationResult second = RealmCalls.validateOAuthToken(realm, token);
        assertTrue(first.valid);
        assertSame(first, second);
    }

    @Test
    public void cacheOverflowTriggersCleanup() throws Exception {
        Properties p = jwtProps();
        p.setProperty("oauth.cache.enabled", "true");
        p.setProperty("oauth.cache.max.size", "2");
        p.setProperty("oauth.cache.ttl", "0");
        OAuthRealm realm = new OAuthRealm(p);
        for (int i = 0; i < 6; i++) {
            String token = jwt("{\"sub\":\"user" + i + "\",\"exp\":" + future() + "}");
            Realm.TokenValidationResult r = RealmCalls.validateOAuthToken(realm, token);
            assertNotNull(r);
            assertTrue(r.valid);
        }
    }

    @Test
    public void cacheOverflowWithLongTtlEvictsEntries() throws Exception {
        Properties p = jwtProps();
        p.setProperty("oauth.cache.enabled", "true");
        p.setProperty("oauth.cache.max.size", "2");
        p.setProperty("oauth.cache.ttl", "3600");
        OAuthRealm realm = new OAuthRealm(p);
        for (int i = 0; i < 6; i++) {
            String token = jwt("{\"sub\":\"user" + i + "\",\"exp\":" + future() + "}");
            assertTrue(RealmCalls.validateOAuthToken(realm, token).valid);
        }
    }

    @Test
    public void jwtClaimsHandlerStoresScalarsAndAudience() throws Exception {
        OAuthRealm realm = new OAuthRealm(jwtProps());
        String token = jwt("{\"sub\":\"u\",\"flag\":true,\"aud\":[\"x\",\"y\"],"
                + "\"nested\":{\"k\":\"v\"},\"exp\":" + future() + "}");
        assertTrue(realm.validateJWT(token).valid);
    }

    @Test
    public void jwtAudienceArrayMatching() throws Exception {
        Properties p = jwtProps();
        p.setProperty("oauth.jwt.audience", "y");
        OAuthRealm realm = new OAuthRealm(p);
        String ok = jwt("{\"sub\":\"u\",\"aud\":[\"x\",\"y\"],\"exp\":" + future() + "}");
        assertNotNull(realm.validateJWT(ok));
        String bad = jwt("{\"sub\":\"u\",\"aud\":[\"x\"],\"exp\":" + future() + "}");
        assertNull(realm.validateJWT(bad));
    }

    @Test
    public void jwtWithoutSecretOrKeyIsRejected() throws Exception {
        Properties p = base();
        p.setProperty("oauth.jwt.enabled", "true");
        OAuthRealm realm = new OAuthRealm(p);
        String token = jwt("{\"sub\":\"u\",\"exp\":" + future() + "}");
        assertNull(realm.validateJWT(token));
    }

    @Test
    public void jwtMalformedBase64OrJsonIsRejected() {
        OAuthRealm realm = new OAuthRealm(jwtProps());
        assertNull(realm.validateJWT("!!!.!!!.!!!"));
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String h = enc.encodeToString("{not json".getBytes(StandardCharsets.UTF_8));
        assertNull(realm.validateJWT(h + "." + h + "." + h));
    }

    @Test
    public void jwtShapeCheckDoesNotAttemptIntrospectionForJwtLikeTokens() {
        OAuthRealm realm = new OAuthRealm(jwtProps());
        assertFalse(RealmCalls.validateOAuthToken(realm, "a.b.c").valid);
        assertFalse(RealmCalls.validateOAuthToken(realm, ".b.c").valid);
        assertFalse(RealmCalls.validateOAuthToken(realm, "a..c").valid);
        assertFalse(RealmCalls.validateOAuthToken(realm, "a.b.c.d").valid);
    }
}
