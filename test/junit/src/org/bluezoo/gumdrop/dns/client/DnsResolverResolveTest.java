/*
 * DnsResolverResolveTest.java
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

package org.bluezoo.gumdrop.dns.client;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsCache;
import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.server.DnsQueryHandler;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for {@link DnsResolver#resolve} and the configuration accessors, using a
 * local query handler so no network is involved.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsResolverResolveTest {

    private DnsCache originalCache;
    private final Map<DnsType, List<DnsResourceRecord>> answers =
            new HashMap<DnsType, List<DnsResourceRecord>>();
    private final Map<DnsType, String> failures = new HashMap<DnsType, String>();
    private final List<String> asked = new ArrayList<String>();
    private DnsResolver resolver;

    private final class Result implements ResolveCallback {
        List<InetAddress> addresses;
        String error;
        int calls;

        @Override
        public void onResolved(List<InetAddress> result) {
            addresses = result;
            calls++;
        }

        @Override
        public void onError(String message) {
            error = message;
            calls++;
        }
    }

    @Before
    public void setUp() throws Exception {
        originalCache = DnsResolver.getCache();
        DnsResolver.setCache(new DnsCache());
        resolver = new DnsResolver();
        resolver.handler(new DnsQueryHandler() {
            @Override
            public void handleQuery(DnsMessage query, SelectorLoop loop, DnsQueryCallback cb) {
                DnsQuestion q = query.getQuestions().get(0);
                asked.add(q.getName() + "/" + q.getType().name());
                String failure = failures.get(q.getType());
                if (failure != null) {
                    cb.onError(failure);
                    return;
                }
                List<DnsResourceRecord> list = answers.get(q.getType());
                if (list == null) {
                    list = Collections.<DnsResourceRecord>emptyList();
                }
                cb.onResponse(query.createResponse(list));
            }
        });
        resolver.open();
    }

    @After
    public void tearDown() throws Exception {
        DnsResolver.setCache(originalCache);
        resolver.close();
        HostsFile.clear();
    }

    private static DnsResourceRecord a(String name, String addr) throws Exception {
        return DnsResourceRecord.a(name, 60, InetAddress.getByName(addr));
    }

    private static DnsResourceRecord aaaa(String name, String addr) throws Exception {
        return DnsResourceRecord.aaaa(name, 60, InetAddress.getByName(addr));
    }

    @Test
    public void testEmptyHostname() {
        Result r = new Result();
        resolver.resolve(null, r);
        assertEquals("Empty hostname", r.error);
        Result r2 = new Result();
        resolver.resolve("", r2);
        assertEquals("Empty hostname", r2.error);
    }

    @Test
    public void testLiteralAddressesNeedNoQuery() {
        Result v4 = new Result();
        resolver.resolve("  192.0.2.5 ", v4);
        assertEquals("192.0.2.5", v4.addresses.get(0).getHostAddress());
        Result v6 = new Result();
        resolver.resolve("2001:db8::5", v6);
        assertEquals("2001:db8:0:0:0:0:0:5", v6.addresses.get(0).getHostAddress());
        assertTrue(asked.isEmpty());
    }

    @Test
    public void testLiteralDeliveredOnSelectorLoop() {
        resolver.setSelectorLoop(new InlineSelectorLoop());
        assertNotNull(resolver.getSelectorLoop());
        Result r = new Result();
        resolver.resolve("192.0.2.6", r);
        assertEquals(1, r.calls);
    }

    @Test
    public void testHostsFileEntryWins() throws Exception {
        Map<String, List<InetAddress>> map = new HashMap<String, List<InetAddress>>();
        map.put("myhost.test", Collections.singletonList(InetAddress.getByName("192.0.2.77")));
        Field f = HostsFile.class.getDeclaredField("entries");
        f.setAccessible(true);
        f.set(null, map);
        Result r = new Result();
        resolver.resolve("myhost.test", r);
        assertEquals("192.0.2.77", r.addresses.get(0).getHostAddress());
        assertTrue(asked.isEmpty());
    }

    @Test
    public void testLocalhostFallbackWhenHostsFileHasNoEntry() throws Exception {
        Field f = HostsFile.class.getDeclaredField("entries");
        f.setAccessible(true);
        f.set(null, Collections.<String, List<InetAddress>>emptyMap());
        Result r = new Result();
        resolver.resolve("LOCALHOST.", r);
        assertEquals(2, r.addresses.size());
        assertTrue(r.addresses.get(0).isLoopbackAddress());
        assertTrue(asked.isEmpty());
    }

    @Test
    public void testDnsQueryCombinesV6ThenV4() throws Exception {
        answers.put(DnsType.A, Collections.singletonList(a("h.example.test.", "192.0.2.1")));
        answers.put(DnsType.AAAA, Collections.singletonList(aaaa("h.example.test.", "2001:db8::1")));
        Result r = new Result();
        resolver.resolve("h.example.test", r);
        assertEquals(2, r.addresses.size());
        assertTrue(r.addresses.get(0).getHostAddress().contains(":"));
        assertEquals("192.0.2.1", r.addresses.get(1).getHostAddress());
        assertEquals(1, r.calls);
    }

    @Test
    public void testMalformedAddressRecordIsSkipped() throws Exception {
        List<DnsResourceRecord> records = new ArrayList<DnsResourceRecord>();
        records.add(new DnsResourceRecord("h.example.test.", DnsType.A,
                DnsClass.IN, 60, new byte[3]));
        records.add(a("h.example.test.", "192.0.2.1"));
        answers.put(DnsType.A, records);
        Result r = new Result();
        resolver.resolve("h.example.test", r);
        assertEquals(1, r.addresses.size());
        assertEquals("192.0.2.1", r.addresses.get(0).getHostAddress());
        assertEquals(1, r.calls);
    }

    @Test
    public void testPartialFailureStillResolves() throws Exception {
        answers.put(DnsType.A, Collections.singletonList(a("h.example.test.", "192.0.2.1")));
        failures.put(DnsType.AAAA, "aaaa failed");
        Result r = new Result();
        resolver.resolve("h.example.test", r);
        assertEquals(1, r.addresses.size());
        assertNull(r.error);
    }

    @Test
    public void testTotalFailureReportsLastError() {
        failures.put(DnsType.A, "a failed");
        failures.put(DnsType.AAAA, "aaaa failed");
        Result r = new Result();
        resolver.resolve("h.example.test", r);
        assertNotNull(r.error);
        assertTrue(r.error.endsWith("failed"));
    }

    @Test
    public void testNoRecordsReportsNotFound() {
        Result r = new Result();
        resolver.resolve("nothing.example.test", r);
        assertEquals("No A or AAAA records found for nothing.example.test", r.error);
    }

    @Test
    public void testTypedQueryHelpers() throws Exception {
        answers.put(DnsType.A, Collections.singletonList(a("q.example.test.", "192.0.2.2")));
        final List<DnsMessage> seen = new ArrayList<DnsMessage>();
        DnsQueryCallback cb = new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
                seen.add(response);
            }

            @Override
            public void onError(String error) {
                fail(error);
            }
        };
        resolver.queryA("q.example.test", cb);
        resolver.queryAAAA("q.example.test", cb);
        resolver.queryMX("q.example.test", cb);
        resolver.queryTXT("q.example.test", cb);
        resolver.queryPTR("q.example.test", cb);
        resolver.querySRV("q.example.test", cb);
        resolver.queryHTTPS("q.example.test", cb);
        resolver.queryTLSA("q.example.test", cb);
        assertEquals(8, seen.size());
        assertEquals(1, seen.get(0).getAnswers().size());
        assertTrue(asked.contains("q.example.test/TXT"));
        assertTrue(asked.contains("q.example.test/HTTPS"));
    }

    @Test
    public void testQueryBatchRejectsEmptyTypes() {
        try {
            resolver.queryBatch("x.example.test", Collections.<DnsType>emptyList(),
                    new BatchQueryCallback() {
                        @Override
                        public void onResult(DnsType type, List<DnsResourceRecord> records) {
                        }

                        @Override
                        public void onTypeError(DnsType type, String error) {
                        }

                        @Override
                        public void onComplete() {
                        }
                    });
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testConfigurationAccessors() throws Exception {
        DnsResolver r = new DnsResolver();
        r.timeoutMs(1234).dnssecEnabled(true);
        assertTrue(r.isDnssecEnabled());
        r.setDdrEnabled(false);
        assertFalse(r.isDdrEnabled());
        r.servers(InetAddress.getByName("192.0.2.1"), InetAddress.getByName("2001:db8::1"));
        r.server(InetAddress.getByName("192.0.2.2"));
        r.server(InetAddress.getByName("192.0.2.3"), 5353);
        assertEquals(4, serverCount(r));
        r.servers((InetAddress[]) null);
        assertEquals(0, serverCount(r));
        try {
            r.servers(new InetAddress[] {null});
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("address", expected.getMessage());
        }
        DnsResolver.setDefaultDnssecEnabled(false);
    }

    private static int serverCount(DnsResolver r) throws Exception {
        Field f = DnsResolver.class.getDeclaredField("servers");
        f.setAccessible(true);
        return ((List<?>) f.get(r)).size();
    }

    @Test
    public void testWellKnownFallbackServers() throws Exception {
        DnsResolver r = new DnsResolver();
        r.addWellKnownPublicFallbacks();
        assertEquals(12, serverCount(r));
        DnsResolver sys = new DnsResolver();
        sys.useSystemResolvers();
        assertTrue(serverCount(sys) > 0);
    }

    @Test
    public void testIpv6EligibilityHelpers() throws Exception {
        DnsResolver r = new DnsResolver();
        // the answer depends on the host; the call itself must not fail
        boolean any = r.hostHasGlobalIpv6();
        assertEquals(any, r.hostHasGlobalIpv6());
        java.lang.reflect.Method m = DnsResolver.class.getDeclaredMethod("isGlobalUnicast",
                java.net.Inet6Address.class);
        m.setAccessible(true);
        assertEquals(Boolean.TRUE, m.invoke(null, InetAddress.getByName("2001:db8::1")));
        assertEquals(Boolean.FALSE, m.invoke(null, InetAddress.getByName("::1")));
        assertEquals(Boolean.FALSE, m.invoke(null, InetAddress.getByName("fe80::1")));
        assertEquals(Boolean.FALSE, m.invoke(null, InetAddress.getByName("fd00::1")));
        assertEquals(Boolean.FALSE, m.invoke(null, InetAddress.getByName("ff02::1")));
        java.lang.reflect.Method nr = DnsResolver.class.getDeclaredMethod("isNoRouteFailure",
                Throwable.class);
        nr.setAccessible(true);
        assertEquals(Boolean.TRUE, nr.invoke(null, new java.net.NoRouteToHostException()));
        assertEquals(Boolean.TRUE, nr.invoke(null, new java.io.IOException("Network is unreachable")));
        assertEquals(Boolean.TRUE, nr.invoke(null, new java.io.IOException("No route to host")));
        assertEquals(Boolean.FALSE, nr.invoke(null, new java.io.IOException("boom")));
        assertEquals(Boolean.FALSE, nr.invoke(null, new java.io.IOException()));
        assertEquals(Boolean.FALSE, nr.invoke(null, (Throwable) null));
    }
}
