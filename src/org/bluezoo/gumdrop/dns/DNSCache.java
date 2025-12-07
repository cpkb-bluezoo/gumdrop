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
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache for DNS responses.
 *
 * <p>Caches DNS resource records respecting their TTL values.
 * Also supports negative caching for NXDOMAIN responses.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSCache {

    private static final int DEFAULT_MAX_ENTRIES = 10000;
    private static final int DEFAULT_NEGATIVE_TTL = 300; // 5 minutes

    private final Map<CacheKey, CacheEntry> cache;
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
            cache.remove(key);
            return null;
        }

        return entry.getRecordsWithAdjustedTTL();
    }

    /**
     * Checks if a name is negatively cached (NXDOMAIN).
     *
     * @param name the domain name
     * @return true if the name is cached as non-existent
     */
    public boolean isNegativelyCached(String name) {
        CacheKey key = new CacheKey(name.toLowerCase(), DNSType.ANY, DNSClass.IN, true);
        CacheEntry entry = cache.get(key);

        if (entry == null) {
            return false;
        }

        if (entry.isExpired()) {
            cache.remove(key);
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

        if (minTTL <= 0) {
            return;
        }

        evictIfNeeded();

        CacheKey key = new CacheKey(question);
        CacheEntry entry = new CacheEntry(records, minTTL);
        cache.put(key, entry);
    }

    /**
     * Caches a negative (NXDOMAIN) response.
     *
     * @param name the non-existent domain name
     */
    public void cacheNegative(String name) {
        evictIfNeeded();

        CacheKey key = new CacheKey(name.toLowerCase(), DNSType.ANY, DNSClass.IN, true);
        CacheEntry entry = new CacheEntry(null, negativeTTL);
        cache.put(key, entry);
    }

    /**
     * Clears all cached entries.
     */
    public void clear() {
        cache.clear();
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
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    private void evictIfNeeded() {
        if (cache.size() >= maxEntries) {
            // Simple eviction: remove expired entries first
            evictExpired();

            // If still too full, remove oldest entries by expiry time
            if (cache.size() >= maxEntries) {
                int toRemove = maxEntries / 10;

                // Collect entries and sort by expiry time
                List<Map.Entry<CacheKey, CacheEntry>> entries = new ArrayList<>(cache.entrySet());
                Collections.sort(entries, new Comparator<Map.Entry<CacheKey, CacheEntry>>() {
                    @Override
                    public int compare(Map.Entry<CacheKey, CacheEntry> a, Map.Entry<CacheKey, CacheEntry> b) {
                        return Long.compare(a.getValue().expiryTime, b.getValue().expiryTime);
                    }
                });

                // Remove oldest entries
                int removed = 0;
                for (Map.Entry<CacheKey, CacheEntry> entry : entries) {
                    if (removed >= toRemove) {
                        break;
                    }
                    cache.remove(entry.getKey());
                    removed++;
                }
            }
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
            this(question.getName().toLowerCase(), question.getType(), question.getDNSClass(), false);
        }

        CacheKey(String name, DNSType type, DNSClass dnsClass, boolean negative) {
            this.name = name;
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
     * Cache entry with expiry time.
     */
    private static final class CacheEntry {
        final List<DNSResourceRecord> records;
        final long expiryTime;
        final long creationTime;
        final int originalTTL;

        CacheEntry(List<DNSResourceRecord> records, int ttl) {
            if (records != null) {
                this.records = new ArrayList<>(records);
            } else {
                this.records = null;
            }
            this.originalTTL = ttl;
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

            long elapsed = (System.currentTimeMillis() - creationTime) / 1000;
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
            return adjusted;
        }
    }

}
