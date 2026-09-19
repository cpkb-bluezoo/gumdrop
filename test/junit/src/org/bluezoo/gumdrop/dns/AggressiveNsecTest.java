/*
 * AggressiveNsecTest.java
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
import org.bluezoo.gumdrop.dns.server.AggressiveNsecPolicy;
import org.bluezoo.gumdrop.dns.server.UpstreamRelayHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * RFC 8198 aggressive use of validated NSEC cache on the forwarder.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class AggressiveNsecTest {

    private Gumdrop gumdrop;

    @Before
    public void bootGumdrop() {
        gumdrop = Gumdrop.boot(GumdropConfig.create()
                .workerThreads(1)
                .drainTimeoutMs(0));
    }

    @After
    public void shutdownGumdrop() {
        DnsNsecProofCache.testingResetClock();
        if (gumdrop != null) {
            gumdrop.shutdown();
        }
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (int i = 0; i < parts.length; i++) {
            len += parts[i].length;
        }
        byte[] out = new byte[len];
        int off = 0;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, out, off, parts[i].length);
            off += parts[i].length;
        }
        return out;
    }

    private static DnsResourceRecord nsec(String owner, String next, int ttl)
            throws Exception {
        byte[] nextName = DnsMessage.encodeName(next);
        byte[] bitmap = new byte[] { 0, 1, 0x60 };
        return new DnsResourceRecord(owner, DnsType.NSEC, DnsClass.IN, ttl,
                concat(nextName, bitmap));
    }

    @Test(timeout = 30_000)
    public void synthesizesNxdomainFromProofCacheWithoutUpstream()
            throws Exception {
        UpstreamRelayHandler handler = UpstreamRelayHandler.builder()
                .upstreamServers("")
                .useSystemResolvers(false)
                .dnssecEnabled(true)
                .build();
        handler.start(gumdrop);
        try {
            handler.getNsecProofCache().ingestAuthoritySection(
                    "example.com.",
                    Arrays.asList(nsec("a.example.com.", "z.example.com.", 300)));

            DnsMessage query = DnsMessage.createQuery(42, "missing.example.com",
                    DnsType.A);
            DnsMessage response = syncHandleQuery(handler, query);

            assertEquals(DnsMessage.RCODE_NXDOMAIN, response.getRcode());
            assertFalse(response.getAuthorities().isEmpty());
            assertTrue(response.isAuthenticatedData());
        } finally {
            handler.stop();
        }
    }

    @Test(timeout = 30_000)
    public void disabledPolicyForwardsDespiteProofCache() throws Exception {
        UpstreamRelayHandler handler = UpstreamRelayHandler.builder()
                .upstreamServers("")
                .useSystemResolvers(false)
                .dnssecEnabled(true)
                .aggressiveNsecPolicy(AggressiveNsecPolicy.DISABLED)
                .build();
        handler.start(gumdrop);
        try {
            handler.getNsecProofCache().ingestAuthoritySection(
                    "example.com.",
                    Arrays.asList(nsec("a.example.com.", "z.example.com.", 300)));

            DnsMessage query = DnsMessage.createQuery(43, "missing.example.com",
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
        final AtomicReference<DnsMessage> result = new AtomicReference<DnsMessage>();
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
