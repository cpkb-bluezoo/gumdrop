/*
 * HTTPAuthenticationProviderNonceTest.java
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

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.bluezoo.gumdrop.auth.Realm;

import static org.junit.Assert.*;

/**
 * Regression tests for Digest authentication nonce generation in
 * {@link HTTPAuthenticationProvider}.
 *
 * <p>Locks in the fix that replaced the non-cryptographic {@code Math.random()}
 * nonce source with {@link java.security.SecureRandom}; verifies that generated
 * nonces are unique across invocations.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPAuthenticationProviderNonceTest {

    /** Minimal Digest provider for exercising challenge/nonce generation. */
    private static final class TestProvider extends HTTPAuthenticationProvider {
        @Override protected String getAuthMethod() {
            return HttpServletRequest.DIGEST_AUTH;
        }
        @Override protected String getRealmName() {
            return "test-realm";
        }
        @Override protected boolean passwordMatch(String realm, String user, String pass) {
            return false;
        }
        @Override protected String getDigestHA1(String realm, String username) {
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
        assertNotNull("challenge should not be null", challenge);
        int i = challenge.indexOf("nonce=\"");
        assertTrue("challenge should contain a nonce: " + challenge, i >= 0);
        int start = i + "nonce=\"".length();
        int end = challenge.indexOf('"', start);
        assertTrue(end > start);
        return challenge.substring(start, end);
    }

    @Test
    public void testNoncesAreUnique() {
        TestProvider provider = new TestProvider();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            String nonce = extractNonce(provider.generateChallenge());
            assertFalse("nonce must be non-empty", nonce.isEmpty());
            assertTrue("nonce repeated - not unpredictable: " + nonce, seen.add(nonce));
        }
    }
}
