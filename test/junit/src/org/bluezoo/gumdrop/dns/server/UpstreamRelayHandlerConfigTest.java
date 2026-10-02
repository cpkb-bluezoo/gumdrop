/*
 * UpstreamRelayHandlerConfigTest.java
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

package org.bluezoo.gumdrop.dns.server;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.dns.DnsCache;
import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsServerMetrics;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import static org.junit.Assert.*;

/**
 * {@link UpstreamRelayHandler} configuration parsing and the local answer
 * paths that need no upstream: minimal ANY, positive and negative cache
 * hits (with metrics), SERVFAIL when nothing can answer, lifecycle.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UpstreamRelayHandlerConfigTest {

    private static final class Capture implements DnsQueryCallback {
        DnsMessage response;
        String error;

        @Override
        public void onResponse(DnsMessage r) {
            response = r;
        }

        @Override
        public void onError(String e) {
            error = e;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<InetSocketAddress> servers(UpstreamRelayHandler h) throws Exception {
        Field f = UpstreamRelayHandler.class.getDeclaredField("upstreamServers");
        f.setAccessible(true);
        return (List<InetSocketAddress>) f.get(h);
    }

    private static Object field(UpstreamRelayHandler h, String name) throws Exception {
        Field f = UpstreamRelayHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(h);
    }

    @Test
    public void upstreamServerListParsing() throws Exception {
        UpstreamRelayHandler h = new UpstreamRelayHandler();
        h.setUpstreamServers("192.0.2.1 192.0.2.2:5353 [2001:db8::1]:5300 [2001:db8::2] 2001:db8::3");
        List<InetSocketAddress> list = servers(h);
        assertEquals(5, list.size());
        assertEquals(53, list.get(0).getPort());
        assertEquals(5353, list.get(1).getPort());
        assertEquals(5300, list.get(2).getPort());
        assertEquals(53, list.get(3).getPort());
        assertEquals(53, list.get(4).getPort());
    }

    @Test
    public void badServerEntriesAreSkippedAndBlankClears() throws Exception {
        UpstreamRelayHandler h = new UpstreamRelayHandler();
        h.setUpstreamServers("192.0.2.1:notaport 192.0.2.9");
        assertEquals(1, servers(h).size());
        h.setUpstreamServers("   ");
        assertTrue(servers(h).isEmpty());
        h.setUpstreamServers("192.0.2.1");
        h.setUpstreamServers(null);
        assertTrue(servers(h).isEmpty());
    }

    @Test
    public void nullPoliciesFallBackToDisabled() throws Exception {
        UpstreamRelayHandler h = new UpstreamRelayHandler();
        h.setServeStalePolicy(null);
        h.setNxDomainCutPolicy(null);
        h.setMinimalAnyPolicy(null);
        h.setAggressiveNsecPolicy(null);
        assertSame(ServeStalePolicy.DISABLED, field(h, "serveStalePolicy"));
        assertSame(NxDomainCutPolicy.DISABLED, field(h, "nxDomainCutPolicy"));
        assertSame(MinimalAnyPolicy.DISABLED, field(h, "minimalAnyPolicy"));
        assertSame(AggressiveNsecPolicy.DISABLED, field(h, "aggressiveNsecPolicy"));
        h.setServeStalePolicy(ServeStalePolicy.ENABLED);
        assertSame(ServeStalePolicy.ENABLED, field(h, "serveStalePolicy"));
    }

    @Test
    public void builderAppliesEverySetting() throws Exception {
        UpstreamRelayHandler h = UpstreamRelayHandler.builder()
                .upstreamServers("192.0.2.1")
                .useSystemResolvers(false)
                .cacheEnabled(false)
                .serveStaleEnabled(false)
                .staleRetentionSeconds(10)
                .staleAnswerTtl(7)
                .serveStalePolicy(ServeStalePolicy.DISABLED)
                .nxDomainCutPolicy(NxDomainCutPolicy.DISABLED)
                .minimalAnyPolicy(MinimalAnyPolicy.DISABLED)
                .aggressiveNsecEnabled(false)
                .aggressiveNsecPolicy(AggressiveNsecPolicy.DISABLED)
                .dnssecEnabled(false)
                .build();
        assertEquals(1, servers(h).size());
        assertFalse(h.isDnssecEnabled());
        assertEquals(Integer.valueOf(10), field(h, "staleRetentionSeconds"));
        assertEquals(Integer.valueOf(7), field(h, "staleAnswerTtl"));
        h.start();
        assertNull(h.getCache());
        assertNull(h.getNsecProofCache());
        h.stop();
        h.stop();
    }

    private static DnsMessage query(String name, DnsType type) {
        return DnsMessage.createQuery(5, name, type);
    }

    @Test
    public void minimalAnyIsAnsweredLocally() {
        UpstreamRelayHandler h = new UpstreamRelayHandler();
        h.start();
        Capture c = new Capture();
        h.handleQuery(query("example.com.", DnsType.ANY), null, c);
        assertEquals(DnsMessage.RCODE_NOERROR, c.response.getRcode());
        assertFalse(c.response.getAnswers().isEmpty());
        h.stop();
    }

    @Test
    public void cacheHitsAndMissesWithMetrics() throws Exception {
        UpstreamRelayHandler h = new UpstreamRelayHandler();
        h.setMetrics(new DnsServerMetrics(new TelemetryConfig()));
        h.start();
        DnsCache cache = h.getCache();
        List<DnsResourceRecord> recs = new ArrayList<DnsResourceRecord>();
        recs.add(DnsResourceRecord.a("hit.example.com.", 300,
                InetAddress.getByAddress(new byte[] {10, 0, 0, 1})));
        cache.cache(new DnsQuestion("hit.example.com.", DnsType.A, DnsClass.IN), recs);
        cache.cacheNegative("gone.example.com.");

        Capture hit = new Capture();
        h.handleQuery(query("hit.example.com.", DnsType.A), null, hit);
        assertEquals(1, hit.response.getAnswers().size());

        Capture negative = new Capture();
        h.handleQuery(query("gone.example.com.", DnsType.A), null, negative);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, negative.response.getRcode());

        // miss with no upstream and nothing stale: SERVFAIL
        Capture miss = new Capture();
        h.handleQuery(query("miss.example.com.", DnsType.A), null, miss);
        assertEquals(DnsMessage.RCODE_SERVFAIL, miss.response.getRcode());
        h.stop();
    }

    @Test
    public void cacheDisabledStillFailsCleanlyWithoutUpstream() {
        UpstreamRelayHandler h = new UpstreamRelayHandler();
        h.setCacheEnabled(false);
        h.start();
        Capture c = new Capture();
        h.handleQuery(query("a.example.com.", DnsType.A), null, c);
        assertEquals(DnsMessage.RCODE_SERVFAIL, c.response.getRcode());
        h.stop();
    }
}
