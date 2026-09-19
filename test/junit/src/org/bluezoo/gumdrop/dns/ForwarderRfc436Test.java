/*
 * ForwarderRfc436Test.java
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
import org.bluezoo.gumdrop.dns.server.MinimalAnyPolicy;
import org.bluezoo.gumdrop.dns.server.NxDomainCutPolicy;
import org.bluezoo.gumdrop.dns.server.UpstreamRelayHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * RFC 8020 NXDOMAIN cut and RFC 8482 minimal ANY for {@link UpstreamRelayHandler}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ForwarderRfc436Test {

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

    @Test
    public void testNxDomainCutSubdomainOfProvenNegative() {
        DnsCache cache = new DnsCache();
        cache.cacheNegative("foo.example.com");
        assertTrue(cache.isNegativelyCached("bar.foo.example.com"));
    }

    @Test
    public void testNxDomainCutExactNameUnchanged() {
        DnsCache cache = new DnsCache();
        cache.cacheNegative("only.example.com");
        assertTrue(cache.isNegativelyCached("only.example.com"));
        assertFalse(cache.isNegativelyCached("other.example.com"));
    }

    @Test
    public void testRelayNxDomainCutWithoutUpstream() throws Exception {
        UpstreamRelayHandler handler = UpstreamRelayHandler.builder()
                .upstreamServers("")
                .build();
        handler.start(gumdrop);
        try {
            handler.getCache().cacheNegative("parent.example.com");
            DnsMessage query = DnsMessage.createQuery(1, "child.parent.example.com",
                    DnsType.A);
            DnsMessage response = syncHandleQuery(handler, query);
            assertEquals(DnsMessage.RCODE_NXDOMAIN, response.getRcode());
        } finally {
            handler.stop();
        }
    }

    @Test
    public void testNxDomainCutDisabledDoesNotInferFromParent() throws Exception {
        UpstreamRelayHandler handler = UpstreamRelayHandler.builder()
                .upstreamServers("")
                .nxDomainCutPolicy(NxDomainCutPolicy.DISABLED)
                .serveStaleEnabled(false)
                .build();
        handler.start(gumdrop);
        try {
            handler.getCache().cacheNegative("parent.example.com");
            DnsMessage query = DnsMessage.createQuery(2, "child.parent.example.com",
                    DnsType.A);
            DnsMessage response = syncHandleQuery(handler, query);
            assertEquals(DnsMessage.RCODE_SERVFAIL, response.getRcode());
        } finally {
            handler.stop();
        }
    }

    @Test
    public void testMinimalAnyResponseWithoutUpstream() throws Exception {
        UpstreamRelayHandler handler = UpstreamRelayHandler.builder()
                .upstreamServers("")
                .build();
        handler.start(gumdrop);
        try {
            DnsMessage query = DnsMessage.createQuery(3, "any.example.com", DnsType.ANY);
            DnsMessage response = syncHandleQuery(handler, query);
            assertEquals(DnsMessage.RCODE_NOERROR, response.getRcode());
            assertEquals(1, response.getAnswers().size());
            assertEquals(DnsType.HINFO, response.getAnswers().get(0).getType());
        } finally {
            handler.stop();
        }
    }

    @Test
    public void testMinimalAnyDisabledForwardsAndFailsWithoutUpstream() throws Exception {
        UpstreamRelayHandler handler = UpstreamRelayHandler.builder()
                .upstreamServers("")
                .minimalAnyPolicy(MinimalAnyPolicy.DISABLED)
                .serveStaleEnabled(false)
                .build();
        handler.start(gumdrop);
        try {
            DnsMessage query = DnsMessage.createQuery(4, "any.example.com", DnsType.ANY);
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
