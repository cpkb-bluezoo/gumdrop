/*
 * MDNSCache.java
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

package org.bluezoo.gumdrop.mdns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;

/**
 * The querier-side record cache for multicast DNS (RFC 6762).
 *
 * <p>Unlike {@link org.bluezoo.gumdrop.dns.DNSCache} (passive,
 * lazy-expiring, one immutable RRset per key), this cache actively
 * re-queries each record at 80%, 85%, 90%, and 95% of its original TTL
 * (RFC 6762 section 5.2) so long-lived answers stay fresh without a
 * caller having to ask again, and treats the "cache-flush" bit (section
 * 10.2) as a request to atomically replace a name/type's whole record
 * set rather than accumulate into it &mdash; anything not re-asserted
 * in a cache-flush batch is removed one second later rather than
 * immediately, in case the responder's answer arrives split across more
 * than one packet. A record with TTL 0 (a "goodbye", section 10.1) gets
 * the same one-second grace removal.
 *
 * <p>Every method here is called only from {@link MDNSService}, itself
 * only ever invoked on its listener's single transport thread, so
 * (like {@link MDNSService}) this class needs no synchronization of its
 * own.
 *
 * <p>Simplification: known-answer lists built from this cache (see
 * {@link MDNSService#query}) reuse each record's originally-cached TTL
 * rather than computing its live remaining TTL. A slightly-stale known
 * answer just means a responder answers a query it didn't strictly need
 * to &mdash; harmless, and cheaper than tracking per-record insertion
 * timestamps only for this purpose.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MDNSService
 */
final class MDNSCache {

    // RFC 6762 section 5.2: active refresh schedule, as fractions of
    // the record's original TTL. The final entry (1.0) is expiry, not
    // a refresh query.
    private static final double[] REFRESH_FRACTIONS = { 0.80, 0.85, 0.90, 0.95, 1.00 };

    /** RFC 6762 section 10.1/10.2: grace period before actually removing a withdrawn record. */
    private static final long GOODBYE_GRACE_MS = 1000;

    /**
     * Supplies the transport operations this cache needs: sending a
     * refresh query, and scheduling a callback on the owning
     * listener's transport thread. Implemented by {@link MDNSService}
     * so this class stays independently testable.
     */
    interface Refresher {
        void sendRefreshQuery(String name, DNSType type);
        MDNSListener.TimerHandleWrapper scheduleTimer(long delayMs, Runnable task);
    }

    private static final class Key {
        final String name;
        final DNSType type;

        Key(String name, DNSType type) {
            this.name = name.toLowerCase(Locale.ROOT);
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            Key other = (Key) o;
            return name.equals(other.name) && type == other.type;
        }

        @Override
        public int hashCode() {
            return 31 * name.hashCode() + type.hashCode();
        }
    }

    private static final class CachedRecord {
        DNSResourceRecord record;
        int generation;
        /** Next RFC 6762 section 5.2 stage to fire (0 .. REFRESH_FRACTIONS.length - 1). */
        int refreshStage;
        MDNSListener.TimerHandleWrapper timer;
    }

    private final Map<Key, List<CachedRecord>> entries = new LinkedHashMap<Key, List<CachedRecord>>();
    private final Refresher refresher;

    MDNSCache(Refresher refresher) {
        this.refresher = refresher;
    }

    /**
     * Returns the currently cached records for a name/type, or an
     * empty list if none are cached.
     *
     * @param name the record name
     * @param type the record type
     * @return the cached records (a snapshot; safe to retain)
     */
    List<DNSResourceRecord> lookup(String name, DNSType type) {
        List<CachedRecord> cached = entries.get(new Key(name, type));
        if (cached == null || cached.isEmpty()) {
            return Collections.emptyList();
        }
        List<DNSResourceRecord> result = new ArrayList<DNSResourceRecord>(cached.size());
        for (CachedRecord cr : cached) {
            result.add(cr.record);
        }
        return result;
    }

    /**
     * Feeds a batch of records from one incoming mDNS message into the
     * cache: cache-flush groups replace their whole name/type record
     * set (with a one-second grace period for anything not
     * re-asserted), other records merge in individually, and TTL-0
     * records trigger a graceful, delayed removal.
     *
     * @param records the records to add (from a response's answers,
     *                or a probe's authority section observed from
     *                another host)
     */
    void addAll(List<DNSResourceRecord> records) {
        Map<Key, List<DNSResourceRecord>> groups =
                new LinkedHashMap<Key, List<DNSResourceRecord>>();
        List<DNSResourceRecord> goodbyes = new ArrayList<DNSResourceRecord>();
        for (DNSResourceRecord rr : records) {
            if (rr.getTTL() <= 0) {
                goodbyes.add(rr);
                continue;
            }
            Key key = new Key(rr.getName(), rr.getType());
            List<DNSResourceRecord> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<DNSResourceRecord>();
                groups.put(key, group);
            }
            group.add(rr);
        }

        for (Map.Entry<Key, List<DNSResourceRecord>> e : groups.entrySet()) {
            processGroup(e.getKey(), e.getValue());
        }
        for (DNSResourceRecord rr : goodbyes) {
            processGoodbye(new Key(rr.getName(), rr.getType()), rr);
        }
    }

    private void processGroup(Key key, List<DNSResourceRecord> incoming) {
        boolean flush = false;
        for (int i = 0; i < incoming.size(); i++) {
            if (incoming.get(i).isCacheFlush()) {
                flush = true;
                break;
            }
        }
        List<CachedRecord> existing = entries.get(key);
        if (existing == null) {
            existing = new ArrayList<CachedRecord>();
            entries.put(key, existing);
        }
        if (flush) {
            for (CachedRecord cr : new ArrayList<CachedRecord>(existing)) {
                if (!containsRdata(incoming, cr.record.getRData())) {
                    scheduleGoodbyeRemoval(key, existing, cr);
                }
            }
        }
        for (int i = 0; i < incoming.size(); i++) {
            upsert(key, existing, incoming.get(i));
        }
    }

    private void processGoodbye(Key key, DNSResourceRecord rr) {
        List<CachedRecord> existing = entries.get(key);
        if (existing == null) {
            return;
        }
        for (CachedRecord cr : new ArrayList<CachedRecord>(existing)) {
            if (Arrays.equals(cr.record.getRData(), rr.getRData())) {
                scheduleGoodbyeRemoval(key, existing, cr);
            }
        }
    }

    private static boolean containsRdata(List<DNSResourceRecord> records, byte[] rdata) {
        for (int i = 0; i < records.size(); i++) {
            if (Arrays.equals(records.get(i).getRData(), rdata)) {
                return true;
            }
        }
        return false;
    }

    private void upsert(Key key, List<CachedRecord> existing, DNSResourceRecord rr) {
        CachedRecord cr = null;
        for (int i = 0; i < existing.size(); i++) {
            if (Arrays.equals(existing.get(i).record.getRData(), rr.getRData())) {
                cr = existing.get(i);
                break;
            }
        }
        if (cr == null) {
            cr = new CachedRecord();
            existing.add(cr);
        } else {
            cancelTimer(cr);
        }
        cr.record = rr;
        cr.generation++;
        cr.refreshStage = 0;
        scheduleNextRefreshStage(key, existing, cr);
    }

    private void scheduleNextRefreshStage(final Key key, final List<CachedRecord> existing,
            final CachedRecord cr) {
        final int stage = cr.refreshStage;
        if (stage >= REFRESH_FRACTIONS.length) {
            return;
        }
        long ttlMs = cr.record.getTTL() * 1000L;
        long delay = stage == 0
                ? (long) (ttlMs * REFRESH_FRACTIONS[0])
                : (long) (ttlMs * (REFRESH_FRACTIONS[stage] - REFRESH_FRACTIONS[stage - 1]));
        final int expectedGeneration = cr.generation;
        cr.timer = refresher.scheduleTimer(delay, new Runnable() {
            @Override
            public void run() {
                if (cr.generation != expectedGeneration) {
                    return;
                }
                if (stage < REFRESH_FRACTIONS.length - 1) {
                    refresher.sendRefreshQuery(key.name, key.type);
                    cr.refreshStage = stage + 1;
                    scheduleNextRefreshStage(key, existing, cr);
                } else {
                    removeRecord(key, existing, cr);
                }
            }
        });
    }

    private void scheduleGoodbyeRemoval(final Key key, final List<CachedRecord> existing,
                                         final CachedRecord cr) {
        cancelTimer(cr);
        cr.generation++;
        final int expectedGeneration = cr.generation;
        cr.timer = refresher.scheduleTimer(GOODBYE_GRACE_MS, new Runnable() {
            @Override public void run() {
                if (cr.generation == expectedGeneration) {
                    removeRecord(key, existing, cr);
                }
            }
        });
    }

    private void removeRecord(Key key, List<CachedRecord> existing, CachedRecord cr) {
        existing.remove(cr);
        if (existing.isEmpty()) {
            entries.remove(key);
        }
    }

    private static void cancelTimer(CachedRecord cr) {
        if (cr.timer != null) {
            cr.timer.cancel();
            cr.timer = null;
        }
    }

    /** Cancels every pending timer and clears the cache. Called on service stop. */
    void clear() {
        for (List<CachedRecord> cached : entries.values()) {
            for (CachedRecord cr : cached) {
                cancelTimer(cr);
            }
        }
        entries.clear();
    }

}
