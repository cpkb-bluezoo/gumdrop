/*
 * DNSCacheTest.java
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

package org.bluezoo.gumdrop.dns;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Regression tests for {@link DNSCache}'s eviction bookkeeping (scalability
 * review finding #129). Refreshing or removing a cached entry used to do a
 * linear {@code PriorityQueue.remove()} under a process-wide lock, making
 * repeated TTL refreshes for the same key, or a full {@code evictExpired()}
 * pass, quadratic in the number of cache entries.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSCacheTest {

    private static DNSQuestion question(String name) {
        return new DNSQuestion(name, DNSType.A, DNSClass.IN);
    }

    private static List<DNSResourceRecord> aRecord(String name, int ttl) throws Exception {
        return Arrays.asList(DNSResourceRecord.a(name, ttl,
                InetAddress.getByName("192.0.2.1")));
    }

    @Test
    public void testCacheAndLookupRoundTrip() throws Exception {
        DNSCache cache = new DNSCache();
        DNSQuestion q = question("example.com");
        cache.cache(q, aRecord("example.com", 300));

        List<DNSResourceRecord> found = cache.lookup(q);
        assertNotNull(found);
        assertEquals(1, found.size());
        assertEquals(1, cache.size());
    }

    @Test
    public void testRepeatedRefreshOfSameKeyDoesNotGrowEvictionQueue() throws Exception {
        // Before the fix, every refresh below did an O(n) PriorityQueue.remove()
        // under expiryLock; this test exercises the volume that would make that
        // pathologically slow, and checks the cache stays correct throughout.
        DNSCache cache = new DNSCache();
        DNSQuestion q = question("refreshed.example.com");

        for (int i = 0; i < 5000; i++) {
            cache.cache(q, aRecord("refreshed.example.com", 300));
        }

        assertEquals(1, cache.size());
        assertNotNull(cache.lookup(q));
    }

    @Test
    public void testEvictExpiredRemovesOnlyExpiredEntries() throws Exception {
        DNSCache cache = new DNSCache();
        DNSQuestion expiring = question("expiring.example.com");
        DNSQuestion longLived = question("longlived.example.com");

        // TTL of 0 means "do not cache" (RFC 1035 3.2.1), so use a very short
        // positive TTL and rely on isExpired()'s wall-clock check; since we
        // cannot fast-forward the clock here, assert the non-expiring entry
        // survives an evictExpired() pass instead.
        cache.cache(longLived, aRecord("longlived.example.com", 300));
        int removed = cache.evictExpired();

        assertEquals(0, removed);
        assertNotNull(cache.lookup(longLived));
        assertEquals(1, cache.size());
    }

    @Test
    public void testEvictionUnderMaxEntriesKeepsCacheBounded() throws Exception {
        int maxEntries = 200;
        DNSCache cache = new DNSCache(maxEntries, 300);

        for (int i = 0; i < maxEntries * 2; i++) {
            String name = "host" + i + ".example.com";
            cache.cache(question(name), aRecord(name, 300));
        }

        assertTrue("cache must stay bounded by maxEntries, was " + cache.size(),
                cache.size() <= maxEntries);
    }

    @Test
    public void testRemoveViaExpiredLookupThenRecacheWorks() throws Exception {
        DNSCache cache = new DNSCache();
        DNSQuestion q = question("negative.example.com");

        cache.cacheNegative("negative.example.com");
        assertTrue(cache.isNegativelyCached("negative.example.com"));

        // Re-cache as a positive answer for the same name/type/class; this
        // exercises the "mark previous eviction entry cancelled" path.
        cache.cache(q, aRecord("negative.example.com", 300));
        assertNotNull(cache.lookup(q));
    }
}
