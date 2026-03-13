/*
 * DNSCache.java
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

/**
 * In-memory cache for DNS responses.
 * RFC 1035 section 7.4: cached data is periodically discarded using TTL.
 * TTL values in returned records are adjusted to reflect elapsed time
 * since caching (RFC 1035 section 3.2.1).
 * RFC 2308: negative caching of NXDOMAIN responses.
 *
 * <p>RFC 2308 section 5: negative cache TTL is the minimum of the SOA
 * record's TTL and the SOA MINIMUM field from the authority section of
 * the NXDOMAIN response. A configurable default is used as fallback
 * when no SOA record is present.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSCache {

    private static final int DEFAULT_MAX_ENTRIES = 10000;
    // RFC 2308 section 5: negative TTL should be derived from SOA MINIMUM
    private static final int DEFAULT_NEGATIVE_TTL = 300;

    private final Map<CacheKey, CacheEntry> cache;
    private final PriorityQueue<EvictionEntry> expiryQueue;
    private final Map<CacheKey, EvictionEntry> keyToEvictionEntry;
    private final int maxEntries;
    private final int negativeTTL;

    /**
     * Creates a new DNS cache with default settings.
     */
    public DNSCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_NEGATIVE_TTL);
    }

    /**
     * Creates a new DNS cache.
     *
     * @param maxEntries maximum number of cache entries
     * @param negativeTTL TTL for negative (NXDOMAIN) cache entries in seconds
     */
    public DNSCache(int maxEntries, int negativeTTL) {
        this.maxEntries = maxEntries;
        this.negativeTTL = negativeTTL;
        this.cache = new ConcurrentHashMap<>();
        this.expiryQueue = new PriorityQueue<>(
                Comparator.comparingLong(e -> e.entry.expiryTime));
        this.keyToEvictionEntry = new ConcurrentHashMap<>();
    }

    /**
     * Looks up cached records for a question.
     *
     * @param question the DNS question
     * @return list of matching records, or null if not cached
     */
    public List<DNSResourceRecord> lookup(DNSQuestion question) {
        CacheKey key = new CacheKey(question);
        CacheEntry entry = cache.get(key);

        if (entry == null) {
            return null;
        }

        if (entry.isExpired()) {
            removeFromCache(key);
            return null;
        }

        return entry.getRecordsWithAdjustedTTL();
    }

    /**
     * Returns the DNSSEC validation status for a cached entry.
     *
     * @param question the DNS question
     * @return the DNSSEC status, or null if not cached or not validated
     */
    public DNSSECStatus lookupStatus(DNSQuestion question) {
        CacheKey key = new CacheKey(question);
        CacheEntry entry = cache.get(key);
        if (entry == null || entry.isExpired()) {
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
        CacheKey key = new CacheKey(name, DNSType.ANY, DNSClass.IN, true);
        CacheEntry entry = cache.get(key);

        if (entry == null) {
            return false;
        }

        if (entry.isExpired()) {
            removeFromCache(key);
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
    public void cache(DNSQuestion question, List<DNSResourceRecord> records) {
        cache(question, records, null);
    }

    /**
     * Caches records from a DNS response with a DNSSEC validation status.
     *
     * @param question the original question
     * @param records the records to cache
     * @param dnssecStatus the DNSSEC validation status, or null if not validated
     */
    public void cache(DNSQuestion question, List<DNSResourceRecord> records,
                      DNSSECStatus dnssecStatus) {
        if (records == null || records.isEmpty()) {
            return;
        }

        // Find minimum TTL
        int minTTL = Integer.MAX_VALUE;
        for (DNSResourceRecord record : records) {
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
                              List<DNSResourceRecord> authorities) {
        evictIfNeeded();

        int ttl = computeNegativeTTL(authorities);
        CacheKey key = new CacheKey(name, DNSType.ANY, DNSClass.IN, true);
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
    private int computeNegativeTTL(List<DNSResourceRecord> authorities) {
        if (authorities != null) {
            for (DNSResourceRecord rr : authorities) {
                if (rr.getType() == DNSType.SOA) {
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
        expiryQueue.clear();
        keyToEvictionEntry.clear();
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
            if (entry.getValue().isExpired()) {
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
                EvictionEntry evictionEntry;
                while (removed < toRemove
                        && (evictionEntry = expiryQueue.poll()) != null) {
                    if (cache.remove(evictionEntry.key) != null) {
                        keyToEvictionEntry.remove(evictionEntry.key);
                        removed++;
                    }
                }
            }
        }
    }

    private void addToCache(CacheKey key, CacheEntry entry) {
        EvictionEntry evictionEntry = new EvictionEntry(key, entry);
        cache.put(key, entry);
        expiryQueue.add(evictionEntry);
        keyToEvictionEntry.put(key, evictionEntry);
    }

    private void removeFromCache(CacheKey key) {
        if (cache.remove(key) != null) {
            EvictionEntry evictionEntry = keyToEvictionEntry.remove(key);
            if (evictionEntry != null) {
                expiryQueue.remove(evictionEntry);
            }
        }
    }

    /**
     * Wrapper for cache entries ordered by expiry time for eviction.
     */
    private static final class EvictionEntry {
        final CacheKey key;
        final CacheEntry entry;

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
        final DNSType type;
        final DNSClass dnsClass;
        final boolean negative;

        CacheKey(DNSQuestion question) {
            this(question.getName(), question.getType(), question.getDNSClass(), false);
        }

        CacheKey(String name, DNSType type, DNSClass dnsClass, boolean negative) {
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

        final List<DNSResourceRecord> records;
        final long expiryTime;
        final long creationTime;
        final int originalTTL;
        final DNSSECStatus dnssecStatus;

        private List<DNSResourceRecord> cachedAdjusted;
        private long cachedAdjustedTime;

        CacheEntry(List<DNSResourceRecord> records, int ttl) {
            this(records, ttl, null);
        }

        CacheEntry(List<DNSResourceRecord> records, int ttl,
                   DNSSECStatus dnssecStatus) {
            if (records != null) {
                this.records = new ArrayList<>(records);
            } else {
                this.records = null;
            }
            this.originalTTL = ttl;
            this.dnssecStatus = dnssecStatus;
            this.creationTime = System.currentTimeMillis();
            this.expiryTime = creationTime + (ttl * 1000L);
        }

        boolean isExpired() {
            return System.currentTimeMillis() >= expiryTime;
        }

        /**
         * Returns records with TTL adjusted for time elapsed since caching.
         */
        List<DNSResourceRecord> getRecordsWithAdjustedTTL() {
            if (records == null) {
                return null;
            }

            long now = System.currentTimeMillis();
            if (cachedAdjusted != null
                    && (now - cachedAdjustedTime) < ADJUSTED_TTL_CACHE_MS) {
                return cachedAdjusted;
            }

            long elapsed = (now - creationTime) / 1000;
            int adjustedTTL = (int) Math.max(1, originalTTL - elapsed);

            List<DNSResourceRecord> adjusted = new ArrayList<>(records.size());
            for (DNSResourceRecord record : records) {
                DNSResourceRecord adjustedRecord = new DNSResourceRecord(
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
    }

}
