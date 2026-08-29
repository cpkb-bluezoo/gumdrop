/*
 * MDNSCacheTest.java
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

import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DNSClass;
import org.bluezoo.gumdrop.dns.DNSResourceRecord;
import org.bluezoo.gumdrop.dns.DNSType;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MDNSCache} against a {@link FakeRefresher} that
 * captures scheduled callbacks (keyed by their exact delay) instead of
 * running them on a real clock, so tests fire the 80/85/90/95/100%
 * refresh schedule (RFC 6762 section 5.2) deterministically.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MDNSCacheTest {

    private static DNSResourceRecord a(String name, int ttl, String ip, boolean cacheFlush)
            throws Exception {
        InetAddress addr = InetAddress.getByName(ip);
        int rawClass = DNSClass.IN.getValue()
                | (cacheFlush ? DNSResourceRecord.CACHE_FLUSH_BIT : 0);
        return new DNSResourceRecord(name, DNSType.A, DNSType.A.getValue(),
                DNSClass.IN, rawClass, ttl, addr.getAddress());
    }

    @Test
    public void testAddAndLookup() throws Exception {
        FakeRefresher refresher = new FakeRefresher();
        MDNSCache cache = new MDNSCache(refresher);

        cache.addAll(Arrays.asList(a("host.local", 120, "10.0.0.1", true)));

        List<DNSResourceRecord> found = cache.lookup("host.local", DNSType.A);
        assertEquals(1, found.size());
        assertEquals("10.0.0.1", InetAddress.getByAddress(found.get(0).getRData()).getHostAddress());
    }

    @Test
    public void testLookupMissReturnsEmpty() {
        MDNSCache cache = new MDNSCache(new FakeRefresher());
        assertTrue(cache.lookup("nothing.local", DNSType.A).isEmpty());
    }

    @Test
    public void testCacheFlushReplacesRRSetAfterGracePeriod() throws Exception {
        FakeRefresher refresher = new FakeRefresher();
        MDNSCache cache = new MDNSCache(refresher);

        cache.addAll(Arrays.asList(a("host.local", 120, "10.0.0.1", true)));
        // A later cache-flush batch that no longer includes 10.0.0.1.
        cache.addAll(Arrays.asList(a("host.local", 120, "10.0.0.2", true)));

        // Old record isn't gone immediately -- RFC 6762 section 10.2's
        // one-second grace period, in case the response was split.
        List<DNSResourceRecord> immediately = cache.lookup("host.local", DNSType.A);
        assertEquals(2, immediately.size());

        refresher.fireByDelay(1000);

        List<DNSResourceRecord> afterGrace = cache.lookup("host.local", DNSType.A);
        assertEquals(1, afterGrace.size());
        assertEquals("10.0.0.2",
                InetAddress.getByAddress(afterGrace.get(0).getRData()).getHostAddress());
    }

    @Test
    public void testNonFlushRecordsAccumulate() throws Exception {
        MDNSCache cache = new MDNSCache(new FakeRefresher());

        // Shared record types (e.g. PTR in real use) don't set
        // cache-flush and are additive, not replacing.
        cache.addAll(Arrays.asList(a("host.local", 120, "10.0.0.1", false)));
        cache.addAll(Arrays.asList(a("host.local", 120, "10.0.0.2", false)));

        assertEquals(2, cache.lookup("host.local", DNSType.A).size());
    }

    @Test
    public void testGoodbyeRemovesAfterGracePeriod() throws Exception {
        FakeRefresher refresher = new FakeRefresher();
        MDNSCache cache = new MDNSCache(refresher);

        cache.addAll(Arrays.asList(a("host.local", 120, "10.0.0.1", true)));
        cache.addAll(Arrays.asList(a("host.local", 0, "10.0.0.1", true)));

        assertEquals(1, cache.lookup("host.local", DNSType.A).size());
        refresher.fireByDelay(1000);
        assertTrue(cache.lookup("host.local", DNSType.A).isEmpty());
    }

    @Test
    public void testUpsertUsesOneTimerPerRecord() throws Exception {
        FakeRefresher refresher = new FakeRefresher();
        MDNSCache cache = new MDNSCache(refresher);

        cache.addAll(Arrays.asList(a("host.local", 10, "10.0.0.1", true)));
        assertEquals("Each cached record should arm one refresh timer, not five",
                1, refresher.pendingCount());
        assertEquals(8000, refresher.firstPendingDelay());

        // Re-announcement cancels and replaces that single timer.
        cache.addAll(Arrays.asList(a("host.local", 10, "10.0.0.1", true)));
        assertEquals(1, refresher.pendingCount());
    }

    @Test
    public void testActiveRefreshFiresAtEachStageThenExpires() throws Exception {
        FakeRefresher refresher = new FakeRefresher();
        MDNSCache cache = new MDNSCache(refresher);

        // TTL 10s -> refresh at 8000/8500/9000/9500ms, expiry at 10000ms.
        cache.addAll(Arrays.asList(a("host.local", 10, "10.0.0.1", true)));

        refresher.fireByDelay(8000);
        assertEquals(1, refresher.refreshQueries.size());
        // Later stages chain one timer at a time with relative delays.
        refresher.fireNextPending();
        assertEquals(2, refresher.refreshQueries.size());
        refresher.fireNextPending();
        refresher.fireNextPending();
        assertEquals(4, refresher.refreshQueries.size());
        assertEquals("host.local A", refresher.refreshQueries.get(0));

        assertEquals(1, cache.lookup("host.local", DNSType.A).size());
        refresher.fireNextPending();
        assertTrue(cache.lookup("host.local", DNSType.A).isEmpty());
    }

    @Test
    public void testRefreshedRecordCancelsStalePendingTimers() throws Exception {
        FakeRefresher refresher = new FakeRefresher();
        MDNSCache cache = new MDNSCache(refresher);

        cache.addAll(Arrays.asList(a("host.local", 10, "10.0.0.1", true)));
        assertEquals(1, refresher.pendingCount());

        // A fresh answer for the same record arrives before any stage fires.
        cache.addAll(Arrays.asList(a("host.local", 10, "10.0.0.1", true)));

        // Old timer cancelled, one new one scheduled -- still one pending
        // (not two). Firing the first stage now produces exactly one query.
        assertEquals(1, refresher.pendingCount());
        refresher.fireByDelay(8000);
        assertEquals(1, refresher.refreshQueries.size());
    }

    @Test
    public void testClearCancelsAllTimersAndEmptiesCache() throws Exception {
        FakeRefresher refresher = new FakeRefresher();
        MDNSCache cache = new MDNSCache(refresher);

        cache.addAll(Arrays.asList(a("host.local", 120, "10.0.0.1", true)));
        assertEquals(1, refresher.pendingCount());

        cache.clear();

        assertEquals(0, refresher.pendingCount());
        assertTrue(cache.lookup("host.local", DNSType.A).isEmpty());
    }

    /**
     * Captures every {@link MDNSCache.Refresher#scheduleTimer} call
     * (keyed by its exact delay) instead of running it, so tests can
     * fire a specific stage deterministically via {@link #fireByDelay}.
     */
    static class FakeRefresher implements MDNSCache.Refresher {

        static final class Scheduled {
            final long delay;
            final Runnable task;
            boolean cancelled;

            Scheduled(long delay, Runnable task) {
                this.delay = delay;
                this.task = task;
            }
        }

        final List<String> refreshQueries = new ArrayList<String>();
        final List<Scheduled> scheduled = new ArrayList<Scheduled>();

        @Override
        public void sendRefreshQuery(String name, DNSType type) {
            refreshQueries.add(name + " " + type);
        }

        @Override
        public MDNSListener.TimerHandleWrapper scheduleTimer(long delayMs, Runnable task) {
            final Scheduled s = new Scheduled(delayMs, task);
            scheduled.add(s);
            return new MDNSListener.TimerHandleWrapper(new TimerHandle() {
                @Override public void cancel() { s.cancelled = true; }
                @Override public boolean isCancelled() { return s.cancelled; }
            });
        }

        /** Fires every non-cancelled task scheduled with exactly this delay, then forgets them. */
        void fireByDelay(long delay) {
            List<Scheduled> toFire = new ArrayList<Scheduled>();
            for (Scheduled s : scheduled) {
                if (s.delay == delay) {
                    toFire.add(s);
                }
            }
            scheduled.removeAll(toFire);
            for (Scheduled s : toFire) {
                if (!s.cancelled) {
                    s.task.run();
                }
            }
        }

        /** Fires the earliest non-cancelled pending timer, then removes it. */
        void fireNextPending() {
            Scheduled next = null;
            for (Scheduled s : scheduled) {
                if (!s.cancelled && (next == null || s.delay < next.delay)) {
                    next = s;
                }
            }
            if (next == null) {
                fail("no pending timer to fire");
            }
            scheduled.remove(next);
            next.task.run();
        }

        int pendingCount() {
            int n = 0;
            for (Scheduled s : scheduled) {
                if (!s.cancelled) {
                    n++;
                }
            }
            return n;
        }

        long firstPendingDelay() {
            for (Scheduled s : scheduled) {
                if (!s.cancelled) {
                    return s.delay;
                }
            }
            return -1;
        }
    }

}
