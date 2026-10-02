/*
 * DnsCacheEdgeTest.java
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
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Edge branches of {@link DnsCache} driven by its testing clock: stale
 * windows, negative caching with RFC 8020 cuts, SOA-derived negative TTLs,
 * eviction under pressure and the tombstone purge threshold.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsCacheEdgeTest {

    @Before
    public void resetClockBefore() {
        DnsCache.testingResetClock();
    }

    @After
    public void resetClockAfter() {
        DnsCache.testingResetClock();
    }

    private static DnsQuestion q(String name) {
        return new DnsQuestion(name, DnsType.A, DnsClass.IN);
    }

    private static List<DnsResourceRecord> recs(String name, int ttl) throws Exception {
        List<DnsResourceRecord> l = new ArrayList<DnsResourceRecord>();
        l.add(DnsResourceRecord.a(name, ttl, InetAddress.getByName("192.0.2.1")));
        return l;
    }

    @Test
    public void lookupMissAndPastStaleWindow() throws Exception {
        DnsCache c = new DnsCache(100, 300, 10);
        assertNull(c.lookup(q("none.test.")));
        c.cache(q("a.test."), recs("a.test.", 5));
        DnsCache.testingAdvanceClock(6000L);
        // expired but inside stale window: miss, entry retained
        assertNull(c.lookup(q("a.test.")));
        assertEquals(1, c.size());
        DnsCache.testingAdvanceClock(10000L);
        assertNull(c.lookup(q("a.test.")));
        assertEquals(0, c.size());
    }

    @Test
    public void lookupAdjustsTtlAndCachesAdjustedList() throws Exception {
        DnsCache c = new DnsCache();
        c.cache(q("a.test."), recs("a.test.", 100));
        List<DnsResourceRecord> first = c.lookup(q("a.test."));
        List<DnsResourceRecord> again = c.lookup(q("a.test."));
        assertSame(first, again);
        DnsCache.testingAdvanceClock(30000L);
        List<DnsResourceRecord> later = c.lookup(q("a.test."));
        assertNotSame(first, later);
        assertTrue(later.get(0).getTTL() <= 70);
    }

    @Test
    public void lookupStaleBranches() throws Exception {
        DnsCache c = new DnsCache(100, 300, 100);
        assertNull(c.lookupStale(q("none.test."), 30));
        c.cacheNegative("neg.test.");
        assertNull(c.lookupStale(new DnsQuestion("neg.test.", DnsType.ANY, DnsClass.IN), 30));
        c.cache(q("a.test."), recs("a.test.", 10));
        assertNull(c.lookupStale(q("a.test."), 30));
        DnsCache.testingAdvanceClock(11000L);
        DnsCache.StaleHit hit = c.lookupStale(q("a.test."), 0);
        assertNotNull(hit);
        assertFalse(hit.negative);
        assertEquals(1, hit.records.get(0).getTTL());
        hit = c.lookupStale(q("a.test."), 30);
        assertEquals(30, hit.records.get(0).getTTL());
        DnsCache.testingAdvanceClock(200000L);
        assertNull(c.lookupStale(q("a.test."), 30));
    }

    @Test
    public void staleHitNegativeFactory() {
        DnsCache.StaleHit h = DnsCache.StaleHit.negativeHit();
        assertTrue(h.negative);
        assertNull(h.records);
    }

    @Test
    public void lookupStaleNegativeBranches() throws Exception {
        DnsCache c = new DnsCache(100, 10, 100);
        assertFalse(c.lookupStaleNegative("x.test."));
        // a positive entry for the name does not count as negative
        c.cache(q("pos.test."), recs("pos.test.", 5));
        assertFalse(c.lookupStaleNegative("pos.test."));
        c.cacheNegative("test.");
        // not yet expired
        assertFalse(c.lookupStaleNegative("sub.test."));
        DnsCache.testingAdvanceClock(11000L);
        assertTrue(c.lookupStaleNegative("sub.test."));
        assertFalse(c.lookupStaleNegative("sub.test.", false));
        assertTrue(c.lookupStaleNegative("test.", false));
        DnsCache.testingAdvanceClock(200000L);
        assertFalse(c.lookupStaleNegative("sub.test."));
        assertFalse(c.lookupStaleNegative(null));
        assertFalse(c.lookupStaleNegative(""));
    }

    @Test
    public void lookupStatusBranches() throws Exception {
        DnsCache c = new DnsCache(100, 300, 5);
        assertNull(c.lookupStatus(q("none.test.")));
        c.cache(q("a.test."), recs("a.test."  , 10), DnssecStatus.SECURE);
        assertEquals(DnssecStatus.SECURE, c.lookupStatus(q("a.test.")));
        c.cache(q("b.test."), recs("b.test.", 10));
        assertNull(c.lookupStatus(q("b.test.")));
        DnsCache.testingAdvanceClock(11000L);
        assertNull(c.lookupStatus(q("a.test.")));
        assertEquals(2, c.size());
        DnsCache.testingAdvanceClock(10000L);
        assertNull(c.lookupStatus(q("a.test.")));
        assertEquals(1, c.size());
    }

    @Test
    public void negativeCachingCutAndExpiry() {
        DnsCache c = new DnsCache(100, 10, 0);
        c.cacheNegative("test.");
        assertTrue(c.isNegativelyCached("a.b.test."));
        assertFalse(c.isNegativelyCached("a.b.test.", false));
        assertTrue(c.isNegativelyCached("test.", false));
        assertFalse(c.isNegativelyCached("other."));
        assertFalse(c.isNegativelyCached(null));
        assertFalse(c.isNegativelyCached(""));
        DnsCache.testingAdvanceClock(11000L);
        assertFalse(c.isNegativelyCached("a.b.test."));
        assertEquals(0, c.size());
    }

    @Test
    public void negativeEntryExpiredButInStaleWindowIsKept() {
        DnsCache c = new DnsCache(100, 10, 100);
        c.cacheNegative("test.");
        DnsCache.testingAdvanceClock(11000L);
        assertFalse(c.isNegativelyCached("test."));
        assertEquals(1, c.size());
    }

    @Test
    public void cacheIgnoresNullEmptyAndZeroTtl() throws Exception {
        DnsCache c = new DnsCache();
        c.cache(q("a.test."), null);
        c.cache(q("a.test."), new ArrayList<DnsResourceRecord>());
        c.cache(q("a.test."), recs("a.test.", 0));
        assertEquals(0, c.size());
    }

    @Test
    public void cacheUsesMinimumTtl() throws Exception {
        DnsCache c = new DnsCache(100, 300, 0);
        List<DnsResourceRecord> l = recs("a.test.", 100);
        l.addAll(recs("a.test.", 5));
        c.cache(q("a.test."), l);
        DnsCache.testingAdvanceClock(6000L);
        assertNull(c.lookup(q("a.test.")));
    }

    private static DnsResourceRecord soa(int ttl, int minimum) {
        return DnsResourceRecord.soa("test.", ttl, "ns.test.", "h.test.", 1, 2, 3, 4, minimum);
    }

    @Test
    public void negativeTtlFromSoaIsMinimumOfTtlAndMinimum() throws Exception {
        DnsCache c = new DnsCache(100, 1000, 0);
        List<DnsResourceRecord> auth = new ArrayList<DnsResourceRecord>();
        auth.add(recs("x.test.", 5).get(0));
        auth.add(soa(50, 20));
        c.cacheNegative("a.test.", auth);
        DnsCache.testingAdvanceClock(19000L);
        assertTrue(c.isNegativelyCached("a.test.", false));
        DnsCache.testingAdvanceClock(2000L);
        assertFalse(c.isNegativelyCached("a.test.", false));

        auth = new ArrayList<DnsResourceRecord>();
        auth.add(soa(8, 500));
        c.cacheNegative("b.test.", auth);
        DnsCache.testingAdvanceClock(9000L);
        assertFalse(c.isNegativelyCached("b.test.", false));
    }

    @Test
    public void negativeTtlFallsBackToDefaultWithoutUsableSoa() {
        DnsCache c = new DnsCache(100, 10, 0);
        List<DnsResourceRecord> auth = new ArrayList<DnsResourceRecord>();
        auth.add(new DnsResourceRecord("test.", DnsType.SOA, DnsClass.IN, 50,
                new byte[5]));
        c.cacheNegative("a.test.", auth);
        DnsCache.testingAdvanceClock(9000L);
        assertTrue(c.isNegativelyCached("a.test.", false));
        DnsCache.testingAdvanceClock(2000L);
        assertFalse(c.isNegativelyCached("a.test.", false));
    }

    @Test
    public void evictExpiredAndClear() throws Exception {
        DnsCache c = new DnsCache(100, 300, 0);
        c.cache(q("a.test."), recs("a.test.", 5));
        c.cache(q("b.test."), recs("b.test.", 500));
        DnsCache.testingAdvanceClock(6000L);
        assertEquals(1, c.evictExpired());
        assertEquals(1, c.size());
        c.clear();
        assertEquals(0, c.size());
        assertNull(c.lookup(q("b.test.")));
    }

    @Test
    public void pressureEvictsExpiredFirstThenOldest() throws Exception {
        DnsCache c = new DnsCache(10, 300, 0);
        for (int i = 0; i < 5; i++) {
            c.cache(q("short" + i + ".test."), recs("short" + i + ".test.", 5));
        }
        for (int i = 0; i < 5; i++) {
            c.cache(q("long" + i + ".test."), recs("long" + i + ".test.", 500));
        }
        DnsCache.testingAdvanceClock(6000L);
        // full: expired ones are swept to make room
        c.cache(q("new.test."), recs("new.test.", 500));
        assertEquals(6, c.size());
        // fill with live entries only, forcing the oldest-first eviction
        for (int i = 0; i < 20; i++) {
            c.cache(q("fill" + i + ".test."), recs("fill" + i + ".test.", 500 + i));
        }
        assertTrue(c.size() <= 10);
    }

    @Test
    public void manyRefreshesTriggerTombstonePurge() throws Exception {
        DnsCache c = new DnsCache(50, 300, 0);
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 40; i++) {
                c.cache(q("h" + i + ".test."), recs("h" + i + ".test.", 500));
            }
        }
        assertEquals(40, c.size());
        // now overflow so the purge-before-poll path runs under pressure
        for (int i = 0; i < 40; i++) {
            c.cache(q("n" + i + ".test."), recs("n" + i + ".test.", 500));
        }
        assertTrue(c.size() <= 50);
        c.clear();
    }

    @Test
    public void keysAreCaseInsensitiveAndTypeSensitive() throws Exception {
        DnsCache c = new DnsCache();
        c.cache(q("A.Test."), recs("a.test.", 100));
        assertNotNull(c.lookup(q("a.TEST.")));
        assertNull(c.lookup(new DnsQuestion("a.test.", DnsType.AAAA, DnsClass.IN)));
        assertNull(c.lookup(new DnsQuestion("a.test.", DnsType.A, DnsClass.CH)));
        c.cacheNegative("a.test.");
        assertEquals(2, c.size());
    }
}
