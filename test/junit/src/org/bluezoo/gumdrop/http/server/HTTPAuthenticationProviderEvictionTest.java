/*
 * HTTPAuthenticationProviderEvictionTest.java
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

import org.bluezoo.gumdrop.auth.Realm;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import static org.junit.Assert.*;

/**
 * Regression tests for issue #192: {@link HttpAuthenticationProvider}'s
 * Digest nonce/cnonce tracking previously grew without bound and
 * {@code seenCnonce} contended on a single global lock across every
 * request the provider instance served.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPAuthenticationProviderEvictionTest {

    /** Minimal Digest provider for exercising nonce issuance directly. */
    private static final class TestProvider extends HttpAuthenticationProvider {
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nonces(HttpAuthenticationProvider provider) throws Exception {
        Field field = HttpAuthenticationProvider.class.getDeclaredField("nonces");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(provider);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> cnonces(HttpAuthenticationProvider provider) throws Exception {
        Field field = HttpAuthenticationProvider.class.getDeclaredField("cnonces");
        field.setAccessible(true);
        return (Map<String, Long>) field.get(provider);
    }

    /** Backdates a tracked nonce's issue time so it reads as already expired. */
    private static void ageNonce(HttpAuthenticationProvider provider, String nonce, long ageMs) throws Exception {
        Object entry = nonces(provider).get(nonce);
        assertNotNull("nonce must be tracked before it can be aged", entry);
        Field createdAtField = entry.getClass().getDeclaredField("createdAt");
        createdAtField.setAccessible(true);
        createdAtField.setLong(entry, System.currentTimeMillis() - ageMs);
    }

    private static String extractNonce(String challenge) {
        int i = challenge.indexOf("nonce=\"");
        assertTrue(i >= 0);
        int start = i + "nonce=\"".length();
        int end = challenge.indexOf('"', start);
        return challenge.substring(start, end);
    }

    @Test
    public void testExpiredNonceIsTreatedAsUnknown() throws Exception {
        TestProvider provider = new TestProvider();
        String nonce = extractNonce(provider.generateChallenge());
        assertTrue("nonce should be tracked immediately after issuance",
                nonces(provider).containsKey(nonce));

        // Older than NONCE_TTL_MS (5 minutes) -- must now read as invalid.
        ageNonce(provider, nonce, 6L * 60L * 1000L);

        Method method = HttpAuthenticationProvider.class.getDeclaredMethod("getNonceCount", String.class);
        method.setAccessible(true);
        int count = (Integer) method.invoke(provider, nonce);
        assertEquals("an expired nonce must be reported as unknown (-1)", -1, count);
        assertFalse("an expired nonce must have been evicted from the tracking map on lookup",
                nonces(provider).containsKey(nonce));
    }

    @Test
    public void testEvictionSweepRemovesOnlyExpiredEntries() throws Exception {
        TestProvider provider = new TestProvider();

        String freshNonce = extractNonce(provider.generateChallenge());
        String staleNonce = extractNonce(provider.generateChallenge());
        ageNonce(provider, staleNonce, 6L * 60L * 1000L);

        // Push nonces + cnonces past EVICTION_SWEEP_THRESHOLD (10,000) so the
        // next tracked-entry insertion triggers a sweep.
        for (int i = 0; i < 10_000; i++) {
            extractNonce(provider.generateChallenge());
        }

        assertTrue("a fresh nonce must survive an eviction sweep",
                nonces(provider).containsKey(freshNonce));
        assertFalse("a nonce older than the TTL must not survive an eviction sweep",
                nonces(provider).containsKey(staleNonce));
    }
}
