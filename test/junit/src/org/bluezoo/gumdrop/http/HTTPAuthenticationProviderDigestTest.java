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

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLUtils;
import org.bluezoo.util.ByteArrays;
import org.junit.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.servlet.http.HttpServletRequest;

import static org.junit.Assert.*;

/**
 * Regression tests for HTTP Digest authentication verification in
 * {@link HTTPAuthenticationProvider}.
 */
public class HTTPAuthenticationProviderDigestTest {

    private static final String REALM = "test-realm";
    private static final String USERNAME = "alice";
    private static final String PASSWORD = "secret";
    private static final String HA1 = SASLUtils.computeDigestHA1(
            USERNAME, REALM, PASSWORD);

    private static final class TestProvider extends HTTPAuthenticationProvider {
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

    private static String computeDigestResponse(String ha1Hex, String nonce,
            String qop, String nc, String cnonce, String method, String uri)
            throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(method.getBytes());
        md.update((byte) ':');
        md.update(uri.getBytes());
        String ha2Hex = ByteArrays.toHexString(md.digest());

        md.reset();
        md.update(ha1Hex.getBytes());
        md.update((byte) ':');
        md.update(nonce.getBytes());
        md.update((byte) ':');
        md.update(nc.getBytes());
        md.update((byte) ':');
        md.update(cnonce.getBytes());
        md.update((byte) ':');
        md.update(qop.getBytes());
        md.update((byte) ':');
        md.update(ha2Hex.getBytes());
        return ByteArrays.toHexString(md.digest());
    }

    private static String buildAuthorizationHeader(String nonce, String method,
            String uri, String cnonce, String nc) throws NoSuchAlgorithmException {
        String qop = "auth";
        String response = computeDigestResponse(
                HA1, nonce, qop, nc, cnonce, method, uri);
        return "Digest username=\"" + USERNAME + "\", realm=\"" + REALM
                + "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", response="
                + response + ", qop=" + qop + ", nc=" + nc + ", cnonce=\""
                + cnonce + "\"";
    }

    @Test
    public void testDigestAuthBindsToRequestMethodAndUri() throws Exception {
        TestProvider provider = new TestProvider();
        String nonce = extractNonce(provider.generateChallenge());
        String authHeader = buildAuthorizationHeader(
                nonce, "POST", "/admin", "clientnonce1", "00000001");

        HTTPAuthenticationProvider.AuthenticationResult ok =
                provider.authenticate(authHeader, "POST", "/admin");
        assertTrue(ok.success);

        HTTPAuthenticationProvider.AuthenticationResult wrongMethod =
                provider.authenticate(authHeader, "GET", "/admin");
        assertFalse(wrongMethod.success);

        HTTPAuthenticationProvider.AuthenticationResult wrongUri =
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
