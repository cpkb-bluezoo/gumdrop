/*
 * HTTPAuthenticationProviderDigestTest.java
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

import org.bluezoo.gumdrop.testsupport.InlineHttpAuthenticationProvider;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.testsupport.DigestTestSupport;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;

import static org.junit.Assert.*;

/**
 * Regression tests for HTTP Digest authentication verification in
 * {@link HttpAuthenticationProvider}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPAuthenticationProviderDigestTest {

    private static final String REALM = "test-realm";
    private static final String USERNAME = "alice";
    private static final String PASSWORD = "secret";
    private static final String HA1 = DigestTestSupport.ha1(
            USERNAME, REALM, PASSWORD);

    private static final class TestProvider extends InlineHttpAuthenticationProvider {
        @Override protected String getAuthMethod() {
            return HttpServletRequest.DIGEST_AUTH;
        }
        @Override protected String getRealmName() {
            return REALM;
        }
        @Override protected boolean passwordMatch(String realm, String user, String pass) {
            return false;
        }
        @Override protected String getDigestHA1(String realm, String username) {
            if (REALM.equals(realm) && USERNAME.equals(username)) {
                return HA1;
            }
            return null;
        }
        @Override protected Realm.TokenValidationResult validateBearerToken(String token) {
            return null;
        }
        @Override protected Realm.TokenValidationResult validateOAuthToken(String token) {
            return null;
        }
    }

    private static String extractNonce(String challenge) {
        int i = challenge.indexOf("nonce=\"");
        assertTrue(i >= 0);
        int start = i + "nonce=\"".length();
        int end = challenge.indexOf('"', start);
        return challenge.substring(start, end);
    }

    private static String buildAuthorizationHeader(String nonce, String method,
            String uri, String cnonce, String nc) {
        return buildHeader("SHA-256", HA1, "SHA-256", "auth", nonce, method,
                uri, cnonce, nc);
    }

    /**
     * Builds an Authorization header. hashAlg is the hash used to compute the
     * response; algParam is the algorithm value declared in the header (null
     * to omit); qopParam is the qop value declared (null to omit).
     */
    private static String buildHeader(String hashAlg, String ha1, String algParam,
            String qopParam, String nonce, String method, String uri,
            String cnonce, String nc) {
        String response = DigestTestSupport.response(hashAlg, ha1, nonce, nc,
                cnonce, "auth", method, uri);
        StringBuilder sb = new StringBuilder();
        sb.append("Digest username=\"").append(USERNAME).append("\", realm=\"")
                .append(REALM).append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(uri).append("\", response=")
                .append(response);
        if (qopParam != null) {
            sb.append(", qop=").append(qopParam);
        }
        sb.append(", nc=").append(nc).append(", cnonce=\"").append(cnonce)
                .append("\"");
        if (algParam != null) {
            sb.append(", algorithm=").append(algParam);
        }
        return sb.toString();
    }

    @Test
    public void testChallengeAdvertisesSha256AndQopAuth() {
        TestProvider provider = new TestProvider();
        String challenge = provider.generateChallenge();
        assertTrue(challenge, challenge.contains("algorithm=SHA-256"));
        assertTrue(challenge, challenge.contains("qop=\"auth\""));
    }

    @Test
    public void testSha256ResponseSucceeds() {
        TestProvider provider = new TestProvider();
        String nonce = extractNonce(provider.generateChallenge());
        String h = buildHeader("SHA-256", HA1, "SHA-256", "auth", nonce, "GET",
                "/r", "cn1", "00000001");
        assertTrue(provider.authenticate(h, "GET", "/r").success);
    }

    @Test
    public void testAlgorithmNameIsCaseInsensitive() {
        TestProvider provider = new TestProvider();
        String nonce = extractNonce(provider.generateChallenge());
        String h = buildHeader("SHA-256", HA1, "sha-256", "auth", nonce, "GET",
                "/r", "cn1", "00000001");
        assertTrue(provider.authenticate(h, "GET", "/r").success);
    }

    @Test
    public void testMd5ResponseRejected() {
        TestProvider provider = new TestProvider();
        String nonce = extractNonce(provider.generateChallenge());
        String md5Ha1 = DigestTestSupport.hex("MD5",
                USERNAME + ":" + REALM + ":" + PASSWORD);
        String h = buildHeader("MD5", md5Ha1, "MD5", "auth", nonce, "GET",
                "/r", "cn1", "00000001");
        assertFalse(provider.authenticate(h, "GET", "/r").success);
    }

    @Test
    public void testAlgorithmOmittedRejected() {
        TestProvider provider = new TestProvider();
        String nonce = extractNonce(provider.generateChallenge());
        String h = buildHeader("SHA-256", HA1, null, "auth", nonce, "GET",
                "/r", "cn1", "00000001");
        assertFalse(provider.authenticate(h, "GET", "/r").success);
    }

    @Test
    public void testSha256SessRejected() {
        TestProvider provider = new TestProvider();
        String nonce = extractNonce(provider.generateChallenge());
        String h = buildHeader("SHA-256", HA1, "SHA-256-sess", "auth", nonce,
                "GET", "/r", "cn1", "00000001");
        assertFalse(provider.authenticate(h, "GET", "/r").success);
    }

    @Test
    public void testQopOmittedRejected() {
        TestProvider provider = new TestProvider();
        String nonce = extractNonce(provider.generateChallenge());
        String h = buildHeader("SHA-256", HA1, "SHA-256", null, nonce, "GET",
                "/r", "cn1", "00000001");
        assertFalse(provider.authenticate(h, "GET", "/r").success);
    }

    @Test
    public void testDigestAuthBindsToRequestMethodAndUri() throws Exception {
        TestProvider provider = new TestProvider();
        String nonce = extractNonce(provider.generateChallenge());
        String authHeader = buildAuthorizationHeader(
                nonce, "POST", "/admin", "clientnonce1", "00000001");

        HttpAuthenticationProvider.AuthenticationResult ok =
                provider.authenticate(authHeader, "POST", "/admin");
        assertTrue(ok.success);

        HttpAuthenticationProvider.AuthenticationResult wrongMethod =
                provider.authenticate(authHeader, "GET", "/admin");
        assertFalse(wrongMethod.success);

        HttpAuthenticationProvider.AuthenticationResult wrongUri =
                provider.authenticate(authHeader, "POST", "/");
        assertFalse(wrongUri.success);
    }

    @Test
    public void testDigestCnonceReplayRejected() throws Exception {
        TestProvider provider = new TestProvider();
        String nonce = extractNonce(provider.generateChallenge());
        String authHeader = buildAuthorizationHeader(
                nonce, "GET", "/resource", "clientnonce1", "00000001");

        assertTrue(provider.authenticate(authHeader, "GET", "/resource").success);
        assertFalse(provider.authenticate(authHeader, "GET", "/resource").success);
    }

    @Test
    public void testDigestRequiresRequestTarget() throws Exception {
        TestProvider provider = new TestProvider();
        String nonce = extractNonce(provider.generateChallenge());
        String authHeader = buildAuthorizationHeader(
                nonce, "GET", "/resource", "clientnonce1", "00000001");

        assertFalse(provider.authenticate(authHeader).success);
    }
}
