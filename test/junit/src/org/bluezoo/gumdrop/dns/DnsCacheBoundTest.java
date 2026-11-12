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

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The size bound of {@link DnsCache}: it is honoured exactly, each insert
 * past it makes room by evicting one entry rather than a tenth of the cache,
 * the entry nearest its expiry goes first, and a bound of zero disables
 * caching.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsCacheBoundTest {

    @After
    public void resetClock() {
        DnsCache.testingResetClock();
    }

    private static DnsQuestion question(String name) {
        return new DnsQuestion(name, DnsType.A, DnsClass.IN);
    }

    private static List<DnsResourceRecord> a(String name, int ttl) throws Exception {
        return Arrays.asList(DnsResourceRecord.a(name, ttl, InetAddress.getByName("192.0.2.1")));
    }

    private static void put(DnsCache cache, String name, int ttl) throws Exception {
        cache.cache(question(name), a(name, ttl));
    }

    @Test
    public void theBoundIsHonouredExactly() throws Exception {
        for (int max : new int[] { 1, 2, 3, 5, 16, 100, 1000 }) {
            DnsCache cache = new DnsCache(max, 300);
            for (int i = 0; i < max * 5; i++) {
                put(cache, "host" + i + ".example.com", 300);
                assertTrue("max " + max + ", size " + cache.size(), cache.size() <= max);
            }
            assertEquals("a full cache stays full", max, cache.size());
        }
    }

    @Test
    public void aFullCacheMakesRoomForOneEntryNotATenth() throws Exception {
        DnsCache cache = new DnsCache(1000, 300);
        for (int i = 0; i < 10000; i++) {
            put(cache, "host" + i + ".example.com", 300);
        }
        assertEquals(1000, cache.size());
        put(cache, "one-more.example.com", 300);
        assertEquals("one in, one out", 1000, cache.size());
    }

    @Test
    public void theEntryNearestExpiryGoesFirst() throws Exception {
        DnsCache cache = new DnsCache(1, 300);
        put(cache, "soon.example.com", 10);
        put(cache, "later.example.com", 1000);
        assertNull(cache.lookup(question("soon.example.com")));
        assertNotNull(cache.lookup(question("later.example.com")));
        // and a newcomer that expires sooner than what is there displaces it
        put(cache, "sooner.example.com", 5);
        assertNull(cache.lookup(question("later.example.com")));
        assertNotNull(cache.lookup(question("sooner.example.com")));
    }

    @Test
    public void expiredEntriesAreEvictedBeforeLiveOnes() throws Exception {
        DnsCache cache = new DnsCache(64, 300, 0);
        for (int i = 0; i < 640; i++) {
            put(cache, "short" + i + ".example.com", 1);
        }
        assertEquals("full of entries about to expire", 64, cache.size());
        DnsCache.testingAdvanceClock(5000);
        for (int i = 0; i < 8; i++) {
            put(cache, "long" + i + ".example.com", 1000);
        }
        int live = 0;
        for (int i = 0; i < 8; i++) {
            if (cache.lookup(question("long" + i + ".example.com")) != null) {
                live++;
            }
        }
        assertEquals("the expired entries made way for the new ones", 8, live);
        assertEquals(64, cache.size());
    }

    @Test
    public void aBoundOfZeroDisablesCaching() throws Exception {
        DnsCache cache = new DnsCache(0, 300);
        put(cache, "a.example.com", 300);
        cache.cacheNegative("nx.example.com");
        assertEquals(0, cache.size());
        assertNull(cache.lookup(question("a.example.com")));
    }

    @Test
    public void theBoundHoldsUnderConcurrentWriters() throws Exception {
        final DnsCache cache = new DnsCache(500, 300);
        int writers = 8;
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(writers);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        List<Thread> threads = new ArrayList<Thread>();
        for (int w = 0; w < writers; w++) {
            final int writer = w;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await();
                        for (int i = 0; i < 5000; i++) {
                            put(cache, "w" + writer + "-" + i + ".example.com", 60 + (i % 50));
                        }
                    } catch (Throwable e) {
                        failure.set(e);
                    } finally {
                        done.countDown();
                    }
                }
            });
            threads.add(t);
            t.start();
        }
        go.countDown();
        done.await();
        assertNull(String.valueOf(failure.get()), failure.get());
        assertEquals("exactly at the bound once the writers are done", 500, cache.size());
    }
}
