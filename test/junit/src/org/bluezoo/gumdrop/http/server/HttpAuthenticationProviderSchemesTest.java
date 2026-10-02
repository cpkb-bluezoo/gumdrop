/*
 * HttpAuthenticationProviderSchemesTest.java
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
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslUtils;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Basic, Bearer, OAuth and JWT handling, challenge generation, and the
 * Digest rejection paths of {@link HttpAuthenticationProvider}
 * (RFC 7617, RFC 6750, RFC 7616).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpAuthenticationProviderSchemesTest {

    private static final class P extends HttpAuthenticationProvider {
        String method;
        String realm = "r";
        boolean digest = true;
        Realm.TokenValidationResult token;
        boolean throwOnToken;

        P(String method) {
            this.method = method;
        }

        @Override protected String getAuthMethod() { return method; }
        @Override protected String getRealmName() { return realm; }
        @Override protected boolean passwordMatch(String r, String u, String p) {
            return "alice".equals(u) && "pw".equals(p);
        }
        @Override protected String getDigestHA1(String r, String u) {
            if ("alice".equals(u)) {
                return SaslUtils.computeDigestHA1("alice", "r", "pw");
            }
            return null;
        }
        @Override protected Realm.TokenValidationResult validateBearerToken(String t) {
            if (throwOnToken) {
                throw new IllegalStateException("boom");
            }
            return token;
        }
        @Override protected Realm.TokenValidationResult validateOAuthToken(String t) {
            return token;
        }
        @Override protected boolean supportsDigestAuth() { return digest; }
    }

    private static String basic(String userPass) {
        byte[] b = userPass.getBytes(StandardCharsets.US_ASCII);
        return "Basic " + Base64.getEncoder().encodeToString(b);
    }

    @Test
    public void testBasicSuccessAndFailures() {
        P p = new P(HttpServletRequest.BASIC_AUTH);
        HttpAuthenticationProvider.AuthenticationResult ok = p.authenticate(basic("alice:pw"));
        assertTrue(ok.success);
        assertEquals("alice", ok.username);
        assertEquals("Basic", ok.scheme);
        assertTrue(ok.toString().contains("alice"));
        assertFalse(p.authenticate(basic("alice:bad")).success);
        assertFalse(p.authenticate(basic("nocolon")).success);
        assertFalse(p.authenticate(basic(":pw")).success);
        assertFalse(p.authenticate("Basic !!!notbase64").success);
        assertTrue(p.authenticate("bAsIc " + basic("alice:pw").substring(6)).success);
    }

    @Test
    public void testHeaderValidation() {
        P p = new P(HttpServletRequest.BASIC_AUTH);
        assertFalse(p.authenticate(null).success);
        assertFalse(p.authenticate("NoSpace").success);
        assertFalse(p.authenticate(" leadingspace").success);
        HttpAuthenticationProvider.AuthenticationResult mismatch = p.authenticate("Bearer abc");
        assertFalse(mismatch.success);
        assertNotNull(mismatch.errorMessage);
        assertTrue(mismatch.toString().contains("success=false"));
        P none = new P(null);
        assertFalse(none.authenticate(basic("alice:pw")).success);
    }

    @Test
    public void testBearerOutcomes() {
        P p = new P(HttpAuthenticationMethods.BEARER_AUTH);
        assertFalse(p.authenticate("Bearer t").success);
        p.token = Realm.TokenValidationResult.failure();
        assertFalse(p.authenticate("Bearer t").success);
        p.token = Realm.TokenValidationResult.success("bob", new String[] {"a"}, "Bearer", 1L);
        assertFalse(p.authenticate("Bearer t").success);
        p.token = Realm.TokenValidationResult.success("bob", new String[] {"a"}, "Bearer");
        HttpAuthenticationProvider.AuthenticationResult r = p.authenticate("Bearer t");
        assertTrue(r.success);
        assertEquals("bob", r.username);
        assertEquals("Bearer", r.scheme);
        p.throwOnToken = true;
        assertFalse(p.authenticate("Bearer t").success);
    }

    @Test
    public void testOAuthOutcomes() {
        P p = new P(HttpAuthenticationMethods.OAUTH_AUTH);
        assertFalse(p.authenticate("Bearer t").success);
        p.token = Realm.TokenValidationResult.failure();
        assertFalse(p.authenticate("Bearer t").success);
        p.token = Realm.TokenValidationResult.success("bob", null, "Bearer", 1L);
        assertFalse(p.authenticate("Bearer t").success);
        p.token = Realm.TokenValidationResult.success("bob", null, "Bearer");
        HttpAuthenticationProvider.AuthenticationResult r = p.authenticate("Bearer t");
        assertTrue(r.success);
        assertEquals("OAuth", r.scheme);
    }

    @Test
    public void testJwtOutcomes() {
        P p = new P(HttpAuthenticationMethods.JWT_AUTH);
        assertFalse(p.authenticate("Bearer t").success);
        p.token = Realm.TokenValidationResult.failure();
        assertFalse(p.authenticate("Bearer t").success);
        p.token = Realm.TokenValidationResult.success("bob", null, "JWT", 1L);
        assertFalse(p.authenticate("Bearer t").success);
        p.token = Realm.TokenValidationResult.success("bob", null, "JWT");
        HttpAuthenticationProvider.AuthenticationResult r = p.authenticate("Bearer t");
        assertTrue(r.success);
        assertEquals("JWT", r.scheme);
        p.throwOnToken = true;
        assertFalse(p.authenticate("Bearer t").success);
    }

    @Test
    public void testChallenges() {
        assertEquals("Basic realm=\"r\"", new P(HttpServletRequest.BASIC_AUTH).generateChallenge());
        assertEquals("Bearer realm=\"r\"", new P(HttpAuthenticationMethods.BEARER_AUTH).generateChallenge());
        String oauth = new P(HttpAuthenticationMethods.OAUTH_AUTH).generateChallenge();
        assertTrue(oauth, oauth.contains("scope="));
        String jwt = new P(HttpAuthenticationMethods.JWT_AUTH).generateChallenge();
        assertTrue(jwt, jwt.contains("JWT"));
        String dig = new P(HttpServletRequest.DIGEST_AUTH).generateChallenge();
        assertTrue(dig, dig.startsWith("Digest realm=\"r\""));
        P noDigest = new P(HttpServletRequest.DIGEST_AUTH);
        noDigest.digest = false;
        assertNull(noDigest.generateChallenge());
        assertNull(new P(null).generateChallenge());
        assertNull(new P(HttpServletRequest.FORM_AUTH).generateChallenge());
        P noRealm = new P(HttpServletRequest.BASIC_AUTH);
        noRealm.realm = null;
        assertNull(noRealm.generateChallenge());
        assertTrue(noRealm.isAuthenticationRequired());
    }

    @Test
    public void testSupportedSchemes() {
        P basic = new P(HttpServletRequest.BASIC_AUTH);
        assertTrue(basic.supportsScheme("basic"));
        assertFalse(basic.supportsScheme("Digest"));
        P dig = new P(HttpServletRequest.DIGEST_AUTH);
        assertTrue(dig.supportsScheme("DIGEST"));
        P b = new P(HttpAuthenticationMethods.BEARER_AUTH);
        assertTrue(b.supportsScheme("Bearer"));
        P o = new P(HttpAuthenticationMethods.OAUTH_AUTH);
        assertTrue(o.supportsScheme("bearer"));
        P j = new P(HttpAuthenticationMethods.JWT_AUTH);
        assertTrue(j.supportsScheme("bearer"));
        assertFalse(new P(null).supportsScheme("Basic"));
        assertFalse(new P(HttpServletRequest.FORM_AUTH).supportsScheme("Basic"));
        Set<String> s = basic.getSupportedSchemes();
        assertTrue(s.contains("Basic"));
        assertTrue(dig.getSupportedSchemes().contains("Digest"));
        assertTrue(j.getSupportedSchemes().contains("Bearer"));
        assertTrue(new P(null).getSupportedSchemes().isEmpty());
        assertTrue(new P(HttpServletRequest.FORM_AUTH).getSupportedSchemes().isEmpty());
    }

    private static String digestHeader(String body) {
        return "Digest " + body;
    }

    @Test
    public void testDigestRejections() {
        P p = new P(HttpServletRequest.DIGEST_AUTH);
        assertFalse(p.authenticate(digestHeader("username=\"alice\""), "GET", "/").success);
        assertFalse(p.authenticate(digestHeader("username=\"alice\", realm=\"r\""), null, "/").success);
        assertFalse(p.authenticate(digestHeader("garbage"), "GET", "/").success);
        assertFalse(p.authenticate(digestHeader("username=\"alice, realm=\"r\""), "GET", "/").success);
        assertFalse(p.authenticate(digestHeader("username=\"nobody\", realm=\"r\""), "GET", "/").success);
        String unknownNonce = "username=\"alice\", realm=\"r\", nonce=\"zzz\", uri=\"/\", "
                + "response=\"00\", qop=auth, nc=00000001, cnonce=\"c\"";
        assertFalse(p.authenticate(digestHeader(unknownNonce), "GET", "/").success);
        String badNc = "username=\"alice\", realm=\"r\", nonce=\"zzz\", uri=\"/\", "
                + "response=\"00\", qop=auth, nc=xyz, cnonce=\"c\"";
        assertFalse(p.authenticate(digestHeader(badNc), "GET", "/").success);
        String uriMismatch = "username=\"alice\", realm=\"r\", nonce=\"zzz\", uri=\"/other\", "
                + "response=\"00\", qop=auth, nc=00000001, cnonce=\"c\"";
        assertFalse(p.authenticate(digestHeader(uriMismatch), "GET", "/").success);
        P noDigest = new P(HttpServletRequest.DIGEST_AUTH);
        noDigest.digest = false;
        HttpAuthenticationProvider.AuthenticationResult r =
                noDigest.authenticate(digestHeader("username=\"alice\""), "GET", "/");
        assertFalse(r.success);
        assertEquals("Digest", r.scheme);
    }

    @Test
    public void testDigestAuthIntQopRejected() {
        P p = new P(HttpServletRequest.DIGEST_AUTH);
        String challenge = p.generateChallenge();
        int i = challenge.indexOf("nonce=\"") + 7;
        String nonce = challenge.substring(i, challenge.indexOf('"', i));
        String hdr = "username=\"alice\", realm=\"r\", nonce=\"" + nonce + "\", uri=\"/\", "
                + "response=\"00\", qop=auth-int, nc=00000001, cnonce=\"c\"";
        assertFalse(p.authenticate(digestHeader(hdr), "GET", "/").success);
    }

    @Test
    public void testDigestNonceCountMustIncrease() {
        P p = new P(HttpServletRequest.DIGEST_AUTH);
        String challenge = p.generateChallenge();
        int i = challenge.indexOf("nonce=\"") + 7;
        String nonce = challenge.substring(i, challenge.indexOf('"', i));
        String hdr = "username=\"alice\", realm=\"r\", nonce=\"" + nonce + "\", uri=\"/\", "
                + "response=\"00\", qop=auth, nc=00000005, cnonce=\"c\"";
        assertFalse(p.authenticate(digestHeader(hdr), "GET", "/").success);
    }

    @Test
    public void testAuthenticationMethodsHelpers() {
        assertTrue(HttpAuthenticationMethods.isTokenBased(HttpAuthenticationMethods.BEARER_AUTH));
        assertTrue(HttpAuthenticationMethods.isTokenBased(HttpAuthenticationMethods.OAUTH_AUTH));
        assertTrue(HttpAuthenticationMethods.isTokenBased(HttpAuthenticationMethods.JWT_AUTH));
        assertFalse(HttpAuthenticationMethods.isTokenBased(HttpAuthenticationMethods.BASIC_AUTH));
        assertTrue(HttpAuthenticationMethods.isCredentialBased(HttpAuthenticationMethods.BASIC_AUTH));
        assertTrue(HttpAuthenticationMethods.isCredentialBased(HttpAuthenticationMethods.DIGEST_AUTH));
        assertTrue(HttpAuthenticationMethods.isCredentialBased(HttpAuthenticationMethods.FORM_AUTH));
        assertFalse(HttpAuthenticationMethods.isCredentialBased(HttpAuthenticationMethods.JWT_AUTH));
        assertEquals("Basic", HttpAuthenticationMethods.getScheme(HttpAuthenticationMethods.BASIC_AUTH));
        assertEquals("Digest", HttpAuthenticationMethods.getScheme(HttpAuthenticationMethods.DIGEST_AUTH));
        assertEquals("Bearer", HttpAuthenticationMethods.getScheme(HttpAuthenticationMethods.BEARER_AUTH));
        assertEquals("Bearer", HttpAuthenticationMethods.getScheme(HttpAuthenticationMethods.OAUTH_AUTH));
        assertEquals("Bearer", HttpAuthenticationMethods.getScheme(HttpAuthenticationMethods.JWT_AUTH));
        assertNull(HttpAuthenticationMethods.getScheme("other"));
    }
}
