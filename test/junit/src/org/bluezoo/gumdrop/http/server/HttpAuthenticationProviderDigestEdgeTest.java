/*
 * HttpAuthenticationProviderDigestEdgeTest.java
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
import org.junit.Test;

import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.testsupport.DigestTestSupport;

import jakarta.servlet.http.HttpServletRequest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Malformed and edge-case Digest Authorization headers (RFC 7616) for
 * {@link HttpAuthenticationProvider}: missing parameters, request-URI
 * binding, nonce-count validation, algorithm selection and unparsable
 * header syntax.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpAuthenticationProviderDigestEdgeTest {

    private static final String REALM = "edge-realm";
    private static final String HA1 = DigestTestSupport.ha1("alice", REALM, "secret");

    private static final class Provider extends InlineHttpAuthenticationProvider {
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
            if (REALM.equals(realm) && "alice".equals(username)) {
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

    private static String nonceOf(Provider p) {
        String challenge = p.generateChallenge();
        int start = challenge.indexOf("nonce=\"") + 7;
        int end = challenge.indexOf('"', start);
        return challenge.substring(start, end);
    }

    private static String response(String nonce, String nc, String cnonce, String method, String uri) {
        return DigestTestSupport.response(HA1, nonce, nc, cnonce, method, uri);
    }

    private static HttpAuthenticationProvider.AuthenticationResult run(Provider p, String header) {
        return p.authenticate(header, "GET", "/r");
    }

    @Test
    public void everyRequiredParameterMustBePresent() throws Exception {
        Provider p = new Provider();
        String nonce = nonceOf(p);
        String resp = response(nonce, "00000001", "c1", "GET", "/r");
        String[] full = {
            "username=\"alice\"", "realm=\"" + REALM + "\"", "nonce=\"" + nonce + "\"",
            "response=\"" + resp + "\"", "cnonce=\"c1\"", "nc=00000001", "qop=auth", "algorithm=SHA-256", "uri=\"/r\""};
        for (int skip = 0; skip < 6; skip++) {
            StringBuilder header = new StringBuilder("Digest ");
            boolean first = true;
            for (int i = 0; i < full.length; i++) {
                if (i == skip) {
                    continue;
                }
                if (!first) {
                    header.append(", ");
                }
                header.append(full[i]);
                first = false;
            }
            HttpAuthenticationProvider.AuthenticationResult r = run(p, header.toString());
            assertFalse("without " + full[skip], r.success);
        }
    }

    @Test
    public void uriParameterMustMatchTheRequestTarget() throws Exception {
        Provider p = new Provider();
        String nonce = nonceOf(p);
        String resp = response(nonce, "00000001", "c1", "GET", "/r");
        String header = "Digest username=\"alice\", realm=\"" + REALM + "\", nonce=\"" + nonce
                + "\", uri=\"/other\", response=\"" + resp + "\", qop=auth, nc=00000001, cnonce=\"c1\", algorithm=SHA-256";
        assertFalse(run(p, header).success);
    }

    @Test
    public void nonceCountProblemsAreRejected() throws Exception {
        Provider p = new Provider();
        String nonce = nonceOf(p);
        String resp = response(nonce, "00000002", "c1", "GET", "/r");
        String skipped = "Digest username=\"alice\", realm=\"" + REALM + "\", nonce=\"" + nonce
                + "\", uri=\"/r\", response=\"" + resp + "\", qop=auth, nc=00000002, cnonce=\"c1\", algorithm=SHA-256";
        assertFalse(run(p, skipped).success);

        String notHex = "Digest username=\"alice\", realm=\"" + REALM + "\", nonce=\"" + nonce
                + "\", uri=\"/r\", response=\"" + resp + "\", qop=auth, nc=zz, cnonce=\"c1\", algorithm=SHA-256";
        assertFalse(run(p, notHex).success);

        String unknown = "Digest username=\"alice\", realm=\"" + REALM + "\", nonce=\"never-issued\""
                + ", uri=\"/r\", response=\"" + resp + "\", qop=auth, nc=00000001, cnonce=\"c1\", algorithm=SHA-256";
        assertFalse(run(p, unknown).success);
    }

    @Test
    public void explicitAlgorithmsAreHonoured() throws Exception {
        Provider p = new Provider();
        String nonce = nonceOf(p);
        String resp = response(nonce, "00000001", "c1", "GET", "/r");
        String good = "Digest username=\"alice\", realm=\"" + REALM + "\", nonce=\"" + nonce
                + "\", uri=\"/r\", response=\"" + resp + "\", qop=auth, nc=00000001, cnonce=\"c1\""
                + ", algorithm=SHA-256";
        HttpAuthenticationProvider.AuthenticationResult ok = run(p, good);
        assertTrue(ok.errorMessage, ok.success);

        String nonce2 = nonceOf(p);
        String resp2 = response(nonce2, "00000001", "c2", "GET", "/r");
        String sess = "Digest username=\"alice\", realm=\"" + REALM + "\", nonce=\"" + nonce2
                + "\", uri=\"/r\", response=\"" + resp2 + "\", qop=auth, nc=00000001, cnonce=\"c2\""
                + ", algorithm=SHA-256-sess";
        assertFalse(run(p, sess).success);

        String nonce3 = nonceOf(p);
        String resp3 = response(nonce3, "00000001", "c3", "GET", "/r");
        String bogus = "Digest username=\"alice\", realm=\"" + REALM + "\", nonce=\"" + nonce3
                + "\", uri=\"/r\", response=\"" + resp3 + "\", qop=auth, nc=00000001, cnonce=\"c3\""
                + ", algorithm=NOPE-999";
        HttpAuthenticationProvider.AuthenticationResult failed = run(p, bogus);
        assertFalse(failed.success);
        assertNotNull(failed.errorMessage);
    }

    @Test
    public void unparsableHeadersAreRejected() {
        Provider p = new Provider();
        assertFalse(run(p, "Digest justaword").success);
        assertFalse(run(p, "Digest username=\"alice").success);
        assertFalse(run(p, "Digest ,username=alice").success);
        assertFalse(run(p, "Digest username=\"nobody\", realm=\"" + REALM + "\"").success);
    }
}
