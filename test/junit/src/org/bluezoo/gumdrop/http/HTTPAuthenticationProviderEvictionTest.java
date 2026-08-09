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

package org.bluezoo.gumdrop.http;

import org.bluezoo.gumdrop.auth.Realm;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;

import static org.junit.Assert.*;

/**
 * Regression tests for issue #192: {@link HTTPAuthenticationProvider}'s
 * Digest nonce/cnonce tracking previously grew without bound and
 * {@code seenCnonce} contended on a single global lock across every
 * request the provider instance served.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HTTPAuthenticationProviderEvictionTest {

    /** Minimal Digest provider for exercising nonce issuance directly. */
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nonces(HTTPAuthenticationProvider provider) throws Exception {
        Field field = HTTPAuthenticationProvider.class.getDeclaredField("nonces");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(provider);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> cnonces(HTTPAuthenticationProvider provider) throws Exception {
        Field field = HTTPAuthenticationProvider.class.getDeclaredField("cnonces");
        field.setAccessible(true);
        return (Map<String, Long>) field.get(provider);
    }

    /** Backdates a tracked nonce's issue time so it reads as already expired. */
    private static void ageNonce(HTTPAuthenticationProvider provider, String nonce, long ageMs) throws Exception {
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

        Method method = HTTPAuthenticationProvider.class.getDeclaredMethod("getNonceCount", String.class);
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

    @Test
    public void testSeenCnonceDoesNotSerializeUnrelatedRequests() throws Exception {
        // Regression coverage for the single-global-lock bug: many threads
        // registering *distinct* cnonces concurrently must all succeed
        // (each is genuinely new), which would not reliably happen if a
        // bug reintroduced contention that silently dropped/blocked entries.
        final TestProvider provider = new TestProvider();
        final int threadCount = 32;
        final int perThread = 200;
        final CountDownLatch ready = new CountDownLatch(threadCount);
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);
        final boolean[] allNew = new boolean[threadCount];

        final Method seenCnonce =
                HTTPAuthenticationProvider.class.getDeclaredMethod("seenCnonce", String.class);
        seenCnonce.setAccessible(true);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                final int threadIndex = t;
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        boolean ok = true;
                        for (int i = 0; i < perThread; i++) {
                            try {
                                Object result = seenCnonce.invoke(provider,
                                        "thread-" + threadIndex + "-cnonce-" + i);
                                ok &= (Boolean) result;
                            } catch (Exception e) {
                                ok = false;
                            }
                        }
                        allNew[threadIndex] = ok;
                        done.countDown();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            assertTrue("all threads should finish well within the timeout",
                    done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        for (int t = 0; t < threadCount; t++) {
            assertTrue("every distinct cnonce from thread " + t + " must be reported as new", allNew[t]);
        }
        assertEquals("every distinct cnonce across all threads must be tracked exactly once",
                threadCount * perThread, cnonces(provider).size());
    }
}
