/*
 * DnsCache.java
 * Copyright (C) 2025 Chris Burdess
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory cache for DNS responses.
 * RFC 1035 section 7.4: cached data is periodically discarded using TTL.
 * TTL values in returned records are adjusted to reflect elapsed time
 * since caching (RFC 1035 section 3.2.1).
 * RFC 2308: negative caching of NXDOMAIN responses.
 * RFC 8020: optional NXDOMAIN cut (subdomains of a proven-negative name).
 * RFC 8767: optional retention of expired entries for serve-stale.
 *
 * <p>RFC 2308 section 5: negative cache TTL is the minimum of the SOA
 * record's TTL and the SOA MINIMUM field from the authority section of
 * the NXDOMAIN response. A configurable default is used as fallback
 * when no SOA record is present.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsCache {

    public static final int DEFAULT_MAX_ENTRIES = 10000;
    // RFC 2308 section 5: negative TTL should be derived from SOA MINIMUM
    public static final int DEFAULT_NEGATIVE_TTL = 300;
    /** RFC 8767: default stale retention (one day). */
    public static final int DEFAULT_STALE_RETENTION_SECONDS = 86_400;
    /** RFC 8767 section 4: recommended cap on stale answer TTL. */
    public static final int DEFAULT_STALE_ANSWER_TTL = 30;

    private final Map<CacheKey, CacheEntry> cache;

    // The bound is kept with a count of live entries and eviction state
    // split into stripes by key. Each stripe has its own lock and queue of
    // entries by expiry time, and publishes when its head expires, so that
    // the victim for a full cache can be chosen without taking any lock:
    // the entry nearest its expiry across the stripes goes. Writers on
    // different worker loops rarely meet, and a full cache gives up one
    // entry per insert, not a tenth of itself under a process-wide lock.
    // Lookups go straight to the map and take no lock.
    private final Stripe[] stripes;
    private final int stripeMask;
    private final AtomicInteger liveCount = new AtomicInteger();
    private final int maxEntries;
    private final int negativeTTL;
    private final long staleRetentionMs;

    /**
     * Number of accumulated tombstones that must build up before a poll of
     * the expiry queue triggers a sweep. A TTL refresh or explicit removal
     * marks the superseded EvictionEntry cancelled in O(1) instead of doing
     * a linear PriorityQueue.remove(); this bounds how many tombstones can
     * accumulate between sweeps. Mirrors ScheduledTimer's PURGE_THRESHOLD.
     */
    private static final int PURGE_THRESHOLD = 256;

    private static final int MAX_STRIPES = 16;

    private static final AtomicLong TEST_CLOCK_SKEW_MS = new AtomicLong();

    static void testingAdvanceClock(long millis) {
        TEST_CLOCK_SKEW_MS.addAndGet(millis);
    }

    static void testingResetClock() {
        TEST_CLOCK_SKEW_MS.set(0L);
    }

    private static long clockMillis() {
        return System.currentTimeMillis() + TEST_CLOCK_SKEW_MS.get();
    }

    /**
     * Creates a new DNS cache with default settings.
     */
    public DnsCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_NEGATIVE_TTL,
                DEFAULT_STALE_RETENTION_SECONDS);
    }

    /**
     * Creates a new DNS cache.
     *
     * @param maxEntries maximum number of cache entries
     * @param negativeTTL TTL for negative (NXDOMAIN) cache entries in seconds
     */
    public DnsCache(int maxEntries, int negativeTTL) {
        this(maxEntries, negativeTTL, DEFAULT_STALE_RETENTION_SECONDS);
    }

    /**
     * Creates a new DNS cache.
     *
     * @param maxEntries maximum number of cache entries
     * @param negativeTTL TTL for negative (NXDOMAIN) cache entries in seconds
     * @param staleRetentionSeconds how long expired entries are kept for RFC
     *                              8767 serve-stale ({@code 0} disables)
     */
    public DnsCache(int maxEntries, int negativeTTL,
                    int staleRetentionSeconds) {
        this.maxEntries = Math.max(0, maxEntries);
        this.negativeTTL = negativeTTL;
        this.staleRetentionMs = staleRetentionSeconds <= 0
                ? 0L : staleRetentionSeconds * 1000L;
        this.cache = new ConcurrentHashMap<>();
        this.stripes = new Stripe[MAX_STRIPES];
        this.stripeMask = MAX_STRIPES - 1;
        for (int i = 0; i < MAX_STRIPES; i++) {
            stripes[i] = new Stripe();
        }
    }

    /**
     * Looks up cached records for a question.
     *
     * @param question the DNS question
     * @return list of matching records, or null if not cached
     */
    public List<DnsResourceRecord> lookup(DnsQuestion question) {
        CacheKey key = new CacheKey(question);
        CacheEntry entry = cache.get(key);

        if (entry == null) {
            return null;
        }

        if (entry.isExpired()) {
            if (entry.isPastStaleWindow(staleRetentionMs)) {
                removeFromCache(key);
            }
            return null;
        }

        return entry.getRecordsWithAdjustedTTL();
    }

    /**
     * RFC 8767: returns a stale cache hit after the live TTL has expired but
     * before the stale retention window ends.
     *
     * @param question the DNS question
     * @param maxAnswerTtl maximum TTL to set on returned records (seconds)
     * @return stale hit, or {@code null} if none
     */
    public StaleHit lookupStale(DnsQuestion question, int maxAnswerTtl) {
        CacheKey key = new CacheKey(question);
        CacheEntry entry = cache.get(key);
        if (entry == null || entry.records == null) {
            return null;
        }
        if (!entry.isExpired()) {
            return null;
        }
        if (entry.isPastStaleWindow(staleRetentionMs)) {
            removeFromCache(key);
            return null;
        }
        return new StaleHit(
                entry.getRecordsWithStaleTTL(maxAnswerTtl), false);
    }

    /**
     * RFC 8767 stale negative cache hit.
     */
    public boolean lookupStaleNegative(String name) {
        return lookupStaleNegative(name, true);
    }

    /**
     * RFC 8767 stale negative cache hit, optionally with RFC 8020 NXDOMAIN cut.
     */
    public boolean lookupStaleNegative(String name, boolean nxDomainCut) {
        for (String candidate : negativeLookupNames(name, nxDomainCut)) {
            if (isExactNameStaleNegative(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExactNameStaleNegative(String name) {
        CacheKey key = new CacheKey(name, DnsType.ANY, DnsClass.IN, true);
        CacheEntry entry = cache.get(key);
        if (entry == null || entry.records != null) {
            return false;
        }
        if (!entry.isExpired()) {
            return false;
        }
        if (entry.isPastStaleWindow(staleRetentionMs)) {
            removeFromCache(key);
            return false;
        }
        return true;
    }

    /**
     * A serve-stale cache hit (positive or {@linkplain #lookupStaleNegative
     * negative}).
     */
    public static final class StaleHit {
        public final List<DnsResourceRecord> records;
        public final boolean negative;

        public StaleHit(List<DnsResourceRecord> records, boolean negative) {
            this.records = records;
            this.negative = negative;
        }

        public static StaleHit negativeHit() {
            return new StaleHit(null, true);
        }
    }

    /**
     * Returns the DNSSEC validation status for a cached entry.
     *
     * @param question the DNS question
     * @return the DNSSEC status, or null if not cached or not validated
     */
    public DnssecStatus lookupStatus(DnsQuestion question) {
        CacheKey key = new CacheKey(question);
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            if (entry.isPastStaleWindow(staleRetentionMs)) {
                removeFromCache(key);
            }
            return null;
        }
        return entry.dnssecStatus;
    }

    /**
     * Checks if a name is negatively cached (NXDOMAIN), applying RFC 8020
     * NXDOMAIN cut by default (any ancestor name with a live negative entry).
     *
     * @param name the domain name
     * @return true if the name is cached as non-existent
     */
    public boolean isNegativelyCached(String name) {
        return isNegativelyCached(name, true);
    }

    /**
     * Checks if a name is negatively cached (NXDOMAIN).
     *
     * @param name the domain name
     * @param nxDomainCut when {@code true}, also match subdomains of any
     *                    live negatively cached ancestor name (RFC 8020)
     * @return true if the name is cached as non-existent
     */
    public boolean isNegativelyCached(String name, boolean nxDomainCut) {
        for (String candidate : negativeLookupNames(name, nxDomainCut)) {
            if (isExactNameNegativelyCached(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExactNameNegativelyCached(String name) {
        CacheKey key = new CacheKey(name, DnsType.ANY, DnsClass.IN, true);
        CacheEntry entry = cache.get(key);

        if (entry == null) {
            return false;
        }

        if (entry.isExpired()) {
            if (entry.isPastStaleWindow(staleRetentionMs)) {
                removeFromCache(key);
            }
            return false;
        }

        return true;
    }

    private static Iterable<String> negativeLookupNames(String name,
                                                        boolean nxDomainCut) {
        final List<String> names = new ArrayList<>();
        String current = name;
        while (current != null && !current.isEmpty()) {
            names.add(current);
            if (!nxDomainCut) {
                break;
            }
            int dot = current.indexOf('.');
            if (dot < 0) {
                break;
            }
            current = current.substring(dot + 1);
        }
        return names;
    }

    /**
     * Caches records from a DNS response.
     *
     * @param question the original question
     * @param records the records to cache
     */
    public void cache(DnsQuestion question, List<DnsResourceRecord> records) {
        cache(question, records, null);
    }

    /**
     * Caches records from a DNS response with a DNSSEC validation status.
     *
     * @param question the original question
     * @param records the records to cache
     * @param dnssecStatus the DNSSEC validation status, or null if not validated
     */
    public void cache(DnsQuestion question, List<DnsResourceRecord> records,
                      DnssecStatus dnssecStatus) {
        if (records == null || records.isEmpty()) {
            return;
        }

        // Find minimum TTL
        int minTTL = Integer.MAX_VALUE;
        for (DnsResourceRecord record : records) {
            int ttl = record.getTTL();
            if (ttl < minTTL) {
                minTTL = ttl;
            }
        }

        // RFC 1035 section 3.2.1: TTL of 0 means do not cache
        if (minTTL <= 0) {
            return;
        }

        CacheKey key = new CacheKey(question);
        CacheEntry entry = new CacheEntry(records, minTTL, dnssecStatus);
        addToCache(key, entry);
    }

    /**
     * Caches a negative (NXDOMAIN) response.
     * RFC 2308 section 5: the negative TTL is the minimum of the SOA
     * record's TTL and the SOA MINIMUM field. Falls back to the
     * configured default when no SOA record is present.
     *
     * @param name the non-existent domain name
     * @param authorities the authority section from the NXDOMAIN response
     */
    public void cacheNegative(String name,
                              List<DnsResourceRecord> authorities) {
        int ttl = computeNegativeTTL(authorities);
        CacheKey key = new CacheKey(name, DnsType.ANY, DnsClass.IN, true);
        CacheEntry entry = new CacheEntry(null, ttl);
        addToCache(key, entry);
    }

    /**
     * Caches a negative (NXDOMAIN) response using the default TTL.
     *
     * @param name the non-existent domain name
     */
    public void cacheNegative(String name) {
        cacheNegative(name, null);
    }

    // RFC 2308 section 5: min(SOA.TTL, SOA.MINIMUM)
    private int computeNegativeTTL(List<DnsResourceRecord> authorities) {
        if (authorities != null) {
            for (DnsResourceRecord rr : authorities) {
                if (rr.getType() == DnsType.SOA) {
                    int soaMinimum = extractSOAMinimum(rr.getRData());
                    if (soaMinimum >= 0) {
                        return Math.min(rr.getTTL(), soaMinimum);
                    }
                }
            }
        }
        return negativeTTL;
    }

    /**
     * Extracts the MINIMUM field from SOA RDATA.
     * RFC 1035 section 3.3.13: SOA RDATA is MNAME, RNAME (domain names),
     * followed by SERIAL, REFRESH, RETRY, EXPIRE, MINIMUM (all 32-bit).
     * MINIMUM is the last 4 bytes.
     */
    private static int extractSOAMinimum(byte[] rdata) {
        // SOA RDATA ends with 5 x 32-bit integers (20 bytes)
        // MINIMUM is the last 4 bytes
        if (rdata.length < 22) {
            return -1;
        }
        int off = rdata.length - 4;
        return ((rdata[off] & 0xFF) << 24)
                | ((rdata[off + 1] & 0xFF) << 16)
                | ((rdata[off + 2] & 0xFF) << 8)
                | (rdata[off + 3] & 0xFF);
    }

    /**
     * Clears all cached entries.
     */
    public void clear() {
        cache.clear();
        for (Stripe stripe : stripes) {
            synchronized (stripe) {
                stripe.queue.clear();
                stripe.index.clear();
                stripe.dead = 0;
                stripe.publishHead();
            }
        }
        liveCount.set(0);
    }

    /**
     * Returns the number of cached entries.
     *
     * @return the cache size
     */
    public int size() {
        return cache.size();
    }

    /**
     * Removes expired entries from the cache.
     *
     * @return the number of entries removed
     */
    public int evictExpired() {
        int removed = 0;
        Iterator<Map.Entry<CacheKey, CacheEntry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<CacheKey, CacheEntry> entry = it.next();
            CacheEntry value = entry.getValue();
            if (value.isPastStaleWindow(staleRetentionMs)) {
                removeFromCache(entry.getKey());
                removed++;
            }
        }
        return removed;
    }

    private Stripe stripeFor(CacheKey key) {
        int h = key.hashCode();
        return stripes[(h ^ (h >>> 16)) & stripeMask];
    }

    private void addToCache(CacheKey key, CacheEntry entry) {
        if (maxEntries <= 0) {
            return;
        }
        EvictionEntry evictionEntry = new EvictionEntry(key, entry);
        Stripe stripe = stripeFor(key);
        synchronized (stripe) {
            EvictionEntry prev = stripe.index.put(key, evictionEntry);
            if (prev == null) {
                liveCount.incrementAndGet();
            } else {
                // a refresh of the TTL: mark the prior eviction entry
                // cancelled rather than doing a linear PriorityQueue.remove();
                // it is skipped lazily when polled or swept.
                markCancelled(stripe, prev);
            }
            cache.put(key, entry);
            stripe.queue.add(evictionEntry);
            stripe.publishHead();
        }
        // over the bound: the entry nearest its expiry makes way, never the
        // one just added
        while (liveCount.get() > maxEntries && evictNearestExpiry(evictionEntry)) {
            // evicting
        }
    }

    // Removes the live entry nearest its expiry, other than {@code keep}.
    // False if there is none. Chooses the stripe by the head each publishes,
    // which needs no lock; only the stripe chosen is locked.
    private boolean evictNearestExpiry(EvictionEntry keep) {
        long skipped = 0;
        for (int attempt = 0; attempt < stripes.length; attempt++) {
            Stripe best = null;
            long bestExpiry = Long.MAX_VALUE;
            for (int i = 0; i < stripes.length; i++) {
                if ((skipped & (1L << i)) == 0 && stripes[i].headExpiry <= bestExpiry
                        && stripes[i].headExpiry != Long.MAX_VALUE) {
                    best = stripes[i];
                    bestExpiry = stripes[i].headExpiry;
                }
            }
            if (best == null) {
                return false;
            }
            if (evictFrom(best, keep)) {
                return true;
            }
            for (int i = 0; i < stripes.length; i++) {
                if (stripes[i] == best) {
                    skipped |= 1L << i;
                }
            }
        }
        return false;
    }

    private boolean evictFrom(Stripe stripe, EvictionEntry keep) {
        synchronized (stripe) {
            EvictionEntry kept = null;
            boolean evicted = false;
            EvictionEntry evictionEntry;
            while ((evictionEntry = stripe.queue.poll()) != null) {
                if (evictionEntry.cancelled) {
                    stripe.dead = Math.max(0, stripe.dead - 1);
                    continue;
                }
                if (evictionEntry == keep) {
                    kept = evictionEntry;
                    continue;
                }
                stripe.index.remove(evictionEntry.key, evictionEntry);
                cache.remove(evictionEntry.key);
                liveCount.decrementAndGet();
                evicted = true;
                break;
            }
            if (kept != null) {
                stripe.queue.add(kept);
            }
            stripe.publishHead();
            return evicted;
        }
    }

    private void removeFromCache(CacheKey key) {
        Stripe stripe = stripeFor(key);
        synchronized (stripe) {
            if (cache.remove(key) != null) {
                EvictionEntry evictionEntry = stripe.index.remove(key);
                if (evictionEntry != null) {
                    markCancelled(stripe, evictionEntry);
                    liveCount.decrementAndGet();
                    stripe.publishHead();
                }
            }
        }
    }

    /**
     * Marks an eviction entry cancelled (the stripe's lock is held) and
     * sweeps the stripe's queue once enough tombstones have accumulated.
     */
    private void markCancelled(Stripe stripe, EvictionEntry evictionEntry) {
        evictionEntry.cancelled = true;
        if (++stripe.dead >= PURGE_THRESHOLD) {
            purgeCancelled(stripe);
        }
    }

    /**
     * Sweeps cancelled entries out of a stripe's expiry queue, with its lock
     * held. A single filter-and-rebuild pass, not repeated
     * PriorityQueue.remove() calls (each of those is itself O(n), making a
     * loop of them O(n^2)).
     */
    private void purgeCancelled(Stripe stripe) {
        List<EvictionEntry> live = new ArrayList<>(stripe.queue.size());
        for (EvictionEntry e : stripe.queue) {
            if (!e.cancelled) {
                live.add(e);
            }
        }
        if (live.size() != stripe.queue.size()) {
            stripe.queue.clear();
            stripe.queue.addAll(live);
        }
        stripe.dead = 0;
    }

    /**
     * One stripe of the eviction state: the entries of the keys that hash to
     * it, ordered by expiry. Guarded by its own monitor, except for
     * {@link #headExpiry}, which is published for lock-free reading.
     */
    private static final class Stripe {
        final PriorityQueue<EvictionEntry> queue = new PriorityQueue<>(
                new Comparator<EvictionEntry>() {
                    @Override
                    public int compare(EvictionEntry a, EvictionEntry b) {
                        return Long.compare(a.entry.expiryTime, b.entry.expiryTime);
                    }
                });
        final Map<CacheKey, EvictionEntry> index = new java.util.HashMap<>();
        int dead;
        // when the entry at the head of the queue expires, or MAX_VALUE if none
        volatile long headExpiry = Long.MAX_VALUE;

        void publishHead() {
            EvictionEntry head = queue.peek();
            headExpiry = head == null ? Long.MAX_VALUE : head.entry.expiryTime;
        }
    }

    /**
     * Wrapper for cache entries ordered by expiry time for eviction.
     */
    private static final class EvictionEntry {
        final CacheKey key;
        final CacheEntry entry;
        volatile boolean cancelled;

        EvictionEntry(CacheKey key, CacheEntry entry) {
            this.key = key;
            this.entry = entry;
        }
    }

    /**
     * Cache key combining name, type, and class.
     */
    private static final class CacheKey {
        final String name;
        final DnsType type;
        final DnsClass dnsClass;
        final boolean negative;

        CacheKey(DnsQuestion question) {
            this(question.getName(), question.getType(), question.getDNSClass(), false);
        }

        CacheKey(String name, DnsType type, DnsClass dnsClass, boolean negative) {
            this.name = name == null ? null : name.toLowerCase();
            this.type = type;
            this.dnsClass = dnsClass;
            this.negative = negative;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CacheKey)) {
                return false;
            }
            CacheKey cacheKey = (CacheKey) o;
            return negative == cacheKey.negative &&
                   name.equals(cacheKey.name) &&
                   type == cacheKey.type &&
                   dnsClass == cacheKey.dnsClass;
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + type.hashCode();
            result = 31 * result + dnsClass.hashCode();
            result = 31 * result + (negative ? 1 : 0);
            return result;
        }
    }

    /**
     * Cache entry with expiry time and optional DNSSEC status.
     */
    private static final class CacheEntry {
        private static final long ADJUSTED_TTL_CACHE_MS = 1000;

        final List<DnsResourceRecord> records;
        final long expiryTime;
        final long creationTime;
        final int originalTTL;
        final DnssecStatus dnssecStatus;

        private List<DnsResourceRecord> cachedAdjusted;
        private long cachedAdjustedTime;

        CacheEntry(List<DnsResourceRecord> records, int ttl) {
            this(records, ttl, null);
        }

        CacheEntry(List<DnsResourceRecord> records, int ttl,
                   DnssecStatus dnssecStatus) {
            if (records != null) {
                this.records = new ArrayList<>(records);
            } else {
                this.records = null;
            }
            this.originalTTL = ttl;
            this.dnssecStatus = dnssecStatus;
            this.creationTime = clockMillis();
            this.expiryTime = creationTime + (ttl * 1000L);
        }

        boolean isExpired() {
            return clockMillis() >= expiryTime;
        }

        boolean isPastStaleWindow(long staleRetentionMs) {
            if (staleRetentionMs <= 0) {
                return isExpired();
            }
            return clockMillis() >= expiryTime + staleRetentionMs;
        }

        /**
         * Returns records with TTL adjusted for time elapsed since caching.
         */
        List<DnsResourceRecord> getRecordsWithAdjustedTTL() {
            if (records == null) {
                return null;
            }

            long now = clockMillis();
            if (cachedAdjusted != null
                    && (now - cachedAdjustedTime) < ADJUSTED_TTL_CACHE_MS) {
                return cachedAdjusted;
            }

            long elapsed = (now - creationTime) / 1000;
            int adjustedTTL = (int) Math.max(1, originalTTL - elapsed);

            List<DnsResourceRecord> adjusted = new ArrayList<>(records.size());
            for (DnsResourceRecord record : records) {
                DnsResourceRecord adjustedRecord = new DnsResourceRecord(
                        record.getName(),
                        record.getType(),
                        record.getDNSClass(),
                        adjustedTTL,
                        record.getRData()
                );
                adjusted.add(adjustedRecord);
            }
            cachedAdjusted = Collections.unmodifiableList(adjusted);
            cachedAdjustedTime = now;
            return cachedAdjusted;
        }

        List<DnsResourceRecord> getRecordsWithStaleTTL(int maxAnswerTtl) {
            if (records == null) {
                return null;
            }
            int ttl = Math.max(1, maxAnswerTtl);
            List<DnsResourceRecord> stale = new ArrayList<>(records.size());
            for (DnsResourceRecord record : records) {
                stale.add(new DnsResourceRecord(
                        record.getName(),
                        record.getType(),
                        record.getDNSClass(),
                        ttl,
                        record.getRData()));
            }
            return Collections.unmodifiableList(stale);
        }
    }

}
