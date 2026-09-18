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
    // The priority queue is not thread-safe; all mutations of it (and the
    // paired eviction index that must stay consistent with it) are guarded by
    // expiryLock so eviction stays correct across DNS worker loops.
    private final PriorityQueue<EvictionEntry> expiryQueue;
    private final Map<CacheKey, EvictionEntry> keyToEvictionEntry;
    private final Object expiryLock = new Object();
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

    private final AtomicInteger deadCount = new AtomicInteger();

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
        this.maxEntries = maxEntries;
        this.negativeTTL = negativeTTL;
        this.staleRetentionMs = staleRetentionSeconds <= 0
                ? 0L : staleRetentionSeconds * 1000L;
        this.cache = new ConcurrentHashMap<>();
        this.expiryQueue = new PriorityQueue<>(new Comparator<EvictionEntry>() {
            @Override
            public int compare(EvictionEntry a, EvictionEntry b) {
                return Long.compare(a.entry.expiryTime, b.entry.expiryTime);
            }
        });
        this.keyToEvictionEntry = new ConcurrentHashMap<>();
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
     * Checks if a name is negatively cached (NXDOMAIN).
     *
     * @param name the domain name
     * @return true if the name is cached as non-existent
     */
    public boolean isNegativelyCached(String name) {
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

        evictIfNeeded();

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
        evictIfNeeded();

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
        synchronized (expiryLock) {
            expiryQueue.clear();
            keyToEvictionEntry.clear();
            deadCount.set(0);
        }
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

    private void evictIfNeeded() {
        if (cache.size() >= maxEntries) {
            evictExpired();

            if (cache.size() >= maxEntries) {
                int toRemove = maxEntries / 10;
                int removed = 0;
                synchronized (expiryLock) {
                    purgeCancelledIfNeeded();
                    EvictionEntry evictionEntry;
                    while (removed < toRemove
                            && (evictionEntry = expiryQueue.poll()) != null) {
                        if (evictionEntry.cancelled) {
                            continue;
                        }
                        // Only drop the index mapping if it still points at the
                        // entry we polled (it may have been refreshed since).
                        keyToEvictionEntry.remove(evictionEntry.key, evictionEntry);
                        if (cache.remove(evictionEntry.key) != null) {
                            removed++;
                        }
                    }
                }
            }
        }
    }

    private void addToCache(CacheKey key, CacheEntry entry) {
        EvictionEntry evictionEntry = new EvictionEntry(key, entry);
        cache.put(key, entry);
        synchronized (expiryLock) {
            // Mark any prior eviction entry for this key (e.g. a TTL refresh)
            // cancelled rather than doing a linear PriorityQueue.remove(); it
            // is skipped lazily when polled or swept.
            EvictionEntry prev = keyToEvictionEntry.put(key, evictionEntry);
            if (prev != null) {
                markCancelled(prev);
            }
            expiryQueue.add(evictionEntry);
        }
    }

    private void removeFromCache(CacheKey key) {
        if (cache.remove(key) != null) {
            synchronized (expiryLock) {
                EvictionEntry evictionEntry = keyToEvictionEntry.remove(key);
                if (evictionEntry != null) {
                    markCancelled(evictionEntry);
                }
            }
        }
    }

    /**
     * Marks an eviction entry cancelled (lock already held) and wakes a
     * sweep once enough tombstones have accumulated.
     */
    private void markCancelled(EvictionEntry evictionEntry) {
        evictionEntry.cancelled = true;
        if (deadCount.incrementAndGet() >= PURGE_THRESHOLD) {
            purgeCancelledIfNeeded();
        }
    }

    /**
     * Sweeps cancelled entries out of the expiry queue when enough have
     * accumulated. Must be called with {@link #expiryLock} held. A single
     * filter-and-rebuild pass, not repeated PriorityQueue.remove() calls
     * (each of those is itself O(n), making a loop of them O(n^2)).
     */
    private void purgeCancelledIfNeeded() {
        if (deadCount.get() >= PURGE_THRESHOLD) {
            List<EvictionEntry> live = new ArrayList<>(expiryQueue.size());
            for (EvictionEntry e : expiryQueue) {
                if (!e.cancelled) {
                    live.add(e);
                }
            }
            if (live.size() != expiryQueue.size()) {
                expiryQueue.clear();
                expiryQueue.addAll(live);
            }
            deadCount.set(0);
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
