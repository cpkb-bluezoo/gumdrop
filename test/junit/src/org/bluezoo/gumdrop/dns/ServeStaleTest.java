/*
 * ServeStaleTest.java
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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.GumdropConfig;
import org.bluezoo.gumdrop.dns.server.UpstreamRelayHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * RFC 8767 serve-stale via {@link DnsCache} and {@link UpstreamRelayHandler}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ServeStaleTest {

    private Gumdrop gumdrop;

    @Before
    public void bootGumdrop() {
        gumdrop = Gumdrop.boot(GumdropConfig.create().workerThreads(1));
    }

    @After
    public void shutdownGumdrop() throws InterruptedException {
        DnsCache.testingResetClock();
        gumdrop.shutdown();
        gumdrop.join();
    }

    private static DnsQuestion question(String name) {
        return new DnsQuestion(name, DnsType.A, DnsClass.IN);
    }

    private static List<DnsResourceRecord> aRecord(String name, int ttl)
            throws Exception {
        return Arrays.asList(DnsResourceRecord.a(name, ttl,
                InetAddress.getByName("192.0.2.55")));
    }

    @Test
    public void testLookupStaleAfterLiveExpiry() throws Exception {
        DnsCache cache = new DnsCache(100, 300, 3600);
        DnsQuestion q = question("stale.example.com");
        cache.cache(q, aRecord("stale.example.com", 300));
        DnsCache.testingAdvanceClock(301_000L);

        assertNull(cache.lookup(q));
        DnsCache.StaleHit stale =
                cache.lookupStale(q, DnsCache.DEFAULT_STALE_ANSWER_TTL);
        assertNotNull(stale);
        assertEquals(1, stale.records.size());
        assertEquals(DnsCache.DEFAULT_STALE_ANSWER_TTL,
                stale.records.get(0).getTTL());
    }

    @Test
    public void testUpstreamFailureServesStaleRecord() throws Exception {
        UpstreamRelayHandler handler = UpstreamRelayHandler.builder()
                .upstreamServers("")
                .serveStaleEnabled(true)
                .staleRetentionSeconds(3600)
                .build();
        handler.start(gumdrop);

        try {
            DnsQuestion q = question("relay.example.com");
            handler.getCache().cache(q, aRecord("relay.example.com", 300));
            DnsCache.testingAdvanceClock(301_000L);

            DnsMessage query = DnsMessage.createQuery(9, "relay.example.com",
                    DnsType.A);
            DnsMessage response = syncHandleQuery(handler, query);

            assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
            assertEquals(1, response.getAnswers().size());
            assertEquals(DnsCache.DEFAULT_STALE_ANSWER_TTL,
                    response.getAnswers().get(0).getTTL());
        } finally {
            handler.stop();
        }
    }

    @Test
    public void testServeStaleDisabledReturnsServfail() throws Exception {
        UpstreamRelayHandler handler = UpstreamRelayHandler.builder()
                .upstreamServers("")
                .serveStaleEnabled(false)
                .build();
        handler.start(gumdrop);

        try {
            DnsQuestion q = question("noserve.example.com");
            handler.getCache().cache(q, aRecord("noserve.example.com", 300));
            DnsCache.testingAdvanceClock(301_000L);

            DnsMessage query = DnsMessage.createQuery(10, "noserve.example.com",
                    DnsType.A);
            DnsMessage response = syncHandleQuery(handler, query);
            assertEquals(DnsMessage.RCODE_SERVFAIL, response.getRcode());
        } finally {
            handler.stop();
        }
    }

    private static DnsMessage syncHandleQuery(UpstreamRelayHandler handler,
                                              DnsMessage query)
            throws Exception {
        final AtomicReference<DnsMessage> result = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        handler.handleQuery(query, null, new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
                result.set(response);
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return result.get();
    }
}
