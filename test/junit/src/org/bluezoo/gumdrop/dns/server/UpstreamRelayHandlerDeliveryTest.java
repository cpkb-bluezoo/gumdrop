/*
 * UpstreamRelayHandlerDeliveryTest.java
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

import org.bluezoo.gumdrop.dns.DnsCache;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsNsecProofCache;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnssecChainValidator;
import org.bluezoo.gumdrop.dns.client.DnssecTestFixtures;
import org.bluezoo.gumdrop.dns.client.DnssecTestFixtures.CannedResolver;
import org.bluezoo.gumdrop.dns.client.DnssecTestFixtures.TestKey;
import org.bluezoo.gumdrop.dns.client.DnssecTrustAnchor;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.bluezoo.gumdrop.dns.client.DnssecTestFixtures.*;
import static org.junit.Assert.*;

/**
 * Tests for the upstream response delivery, caching, DNSSEC status handling and
 * server list parsing of {@link UpstreamRelayHandler}, without any network I/O.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UpstreamRelayHandlerDeliveryTest {

    private UpstreamRelayHandler handler;

    private static final class Capture implements DnsQueryCallback {
        DnsMessage response;
        int calls;

        @Override
        public void onResponse(DnsMessage message) {
            response = message;
            calls++;
        }

        @Override
        public void onError(String error) {
            fail(error);
        }
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    @Before
    public void setUp() throws Exception {
        handler = UpstreamRelayHandler.builder().useSystemResolvers(false).build();
        set(handler, "cache", new DnsCache());
    }

    private static DnsQuestion question(String name, DnsType type) {
        return new DnsQuestion(name, type, DnsClass.IN);
    }

    private static DnsMessage response(DnsQuestion q, int flags,
                                       List<DnsResourceRecord> answers,
                                       List<DnsResourceRecord> authorities) {
        return new DnsMessage(11, DnsMessage.FLAG_QR | flags, Collections.singletonList(q),
                answers, authorities, Collections.<DnsResourceRecord>emptyList());
    }

    private Capture deliver(DnsMessage query, DnsQuestion q, DnsMessage upstream) throws Exception {
        Method m = UpstreamRelayHandler.class.getDeclaredMethod("deliverUpstreamResponse",
                DnsMessage.class, DnsQuestion.class,
                org.bluezoo.gumdrop.SelectorLoop.class, DnsMessage.class, DnsQueryCallback.class);
        m.setAccessible(true);
        Capture c = new Capture();
        m.invoke(handler, query, q, null, upstream, c);
        return c;
    }

    private Capture ask(String name, DnsType type) {
        Capture c = new Capture();
        handler.handleQuery(DnsMessage.createQuery(21, name, type), null, c);
        return c;
    }

    private static List<DnsResourceRecord> none() {
        return Collections.<DnsResourceRecord>emptyList();
    }

    @Test
    public void testUnvalidatedAnswerIsCachedAndServedFromCache() throws Exception {
        DnsQuestion q = question("www.example.com.", DnsType.A);
        DnsResourceRecord a = DnsResourceRecord.a("www.example.com.", 300,
                InetAddress.getByName("192.0.2.1"));
        DnsMessage up = response(q, 0, list(a), none());
        Capture c = deliver(DnsMessage.createQuery(1, "www.example.com.", DnsType.A), q, up);
        assertSame(up, c.response);
        Capture cached = ask("www.example.com.", DnsType.A);
        assertEquals(1, cached.response.getAnswers().size());
    }

    @Test
    public void testNxdomainIsNegativelyCached() throws Exception {
        DnsQuestion q = question("nope.example.com.", DnsType.A);
        DnsResourceRecord soa = DnsResourceRecord.soa("example.com.", 60, "a.", "b.", 1, 1, 1, 1, 30);
        DnsMessage up = response(q, DnsMessage.RCODE_NXDOMAIN, none(), list(soa));
        deliver(DnsMessage.createQuery(1, "nope.example.com.", DnsType.A), q, up);
        Capture cached = ask("nope.example.com.", DnsType.A);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, cached.response.getRcode());
    }

    @Test
    public void testCacheDisabledSkipsCaching() throws Exception {
        handler.setCacheEnabled(false);
        DnsQuestion q = question("www.example.com.", DnsType.A);
        DnsResourceRecord a = DnsResourceRecord.a("www.example.com.", 300,
                InetAddress.getByName("192.0.2.1"));
        deliver(DnsMessage.createQuery(1, "www.example.com.", DnsType.A), q,
                response(q, 0, list(a), none()));
        DnsCache cache = (DnsCache) get(handler, "cache");
        assertNull(cache.lookup(q));
    }

    @Test
    public void testMinimalAnyAnsweredLocally() {
        handler.setMinimalAnyPolicy(MinimalAnyPolicy.ENABLED);
        Capture c = ask("www.example.com.", DnsType.ANY);
        assertEquals(DnsType.HINFO, c.response.getAnswers().get(0).getType());
        handler.setMinimalAnyPolicy(null);
    }

    @Test
    public void testSettersAcceptNullPolicies() {
        handler.setServeStalePolicy(null);
        handler.setNxDomainCutPolicy(null);
        handler.setAggressiveNsecPolicy(null);
        handler.setMinimalAnyPolicy(null);
        handler.setServeStaleEnabled(true);
        handler.setStaleRetentionSeconds(10);
        handler.setStaleAnswerTtl(5);
        handler.setAggressiveNsecEnabled(true);
        handler.setMetrics(null);
        handler.setTrustAnchor(null);
        handler.dnssecEnabled(true);
        assertTrue(handler.isDnssecEnabled());
        assertNotNull(handler.getCache());
        assertNull(handler.getNsecProofCache());
    }

    @SuppressWarnings("unchecked")
    private List<InetSocketAddress> servers() throws Exception {
        return (List<InetSocketAddress>) get(handler, "upstreamServers");
    }

    @Test
    public void testUpstreamServerParsing() throws Exception {
        handler.setUpstreamServers("192.0.2.1 192.0.2.2:5353 [::1] [::1]:5354 ::1 not-an-ip:99999");
        List<InetSocketAddress> list = servers();
        assertEquals(5, list.size());
        assertEquals(53, list.get(0).getPort());
        assertEquals(5353, list.get(1).getPort());
        assertEquals(53, list.get(2).getPort());
        assertEquals(5354, list.get(3).getPort());
        assertEquals(53, list.get(4).getPort());
        handler.setUpstreamServers("   ");
        assertTrue(servers().isEmpty());
        handler.setUpstreamServers(null);
        assertTrue(servers().isEmpty());
        handler.setUpstreamServers("192.0.2.9:notaport");
        assertTrue(servers().isEmpty());
    }

    // ---- DNSSEC status handling ----

    private CannedResolver resolver;
    private DnssecTrustAnchor anchors;

    private void enableDnssec() throws Exception {
        resolver = new CannedResolver();
        anchors = new DnssecTrustAnchor();
        anchors.clear();
        handler.dnssecEnabled(true);
        set(handler, "chainValidator", new DnssecChainValidator(resolver, anchors));
        set(handler, "nsecProofCache", new DnsNsecProofCache());
    }

    @Test
    public void testBogusResponseBecomesServfail() throws Exception {
        enableDnssec();
        TestKey zsk = ecdsaP256("example.com.", 256);
        DnsQuestion q = question("www.example.com.", DnsType.A);
        List<DnsResourceRecord> rrset = list(DnsResourceRecord.a("www.example.com.", 300,
                InetAddress.getByName("192.0.2.1")));
        long n = now();
        DnsResourceRecord sig = sign(rrset, zsk, "example.com.", n - 7200, n - 3600);
        List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>(rrset);
        answers.add(sig);
        Capture c = deliver(DnsMessage.createQuery(1, "www.example.com.", DnsType.A), q,
                response(q, 0, answers, none()));
        assertEquals(DnsMessage.RCODE_SERVFAIL, c.response.getRcode());
        assertNull(((DnsCache) get(handler, "cache")).lookup(q));
    }

    @Test
    public void testSecureResponseGetsAdBitAndIsCached() throws Exception {
        enableDnssec();
        TestKey ksk = ecdsaP256("example.com.", 257);
        anchors.addDNSKEYAnchor("example.com.", ksk.dnskey);
        DnsQuestion q = question("www.example.com.", DnsType.A);
        List<DnsResourceRecord> rrset = list(DnsResourceRecord.a("www.example.com.", 300,
                InetAddress.getByName("192.0.2.1")));
        DnsResourceRecord sig = signCurrent(rrset, ksk, "example.com.");
        List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>(rrset);
        answers.add(sig);
        answers.add(ksk.dnskey);
        Capture c = deliver(DnsMessage.createQuery(1, "www.example.com.", DnsType.A), q,
                response(q, 0, answers, none()));
        assertTrue(c.response.isAuthenticatedData());
        assertNotNull(((DnsCache) get(handler, "cache")).lookup(q));
    }

    @Test
    public void testInsecureResponsePassedThroughWithoutAdBit() throws Exception {
        enableDnssec();
        DnsQuestion q = question("www.example.com.", DnsType.A);
        List<DnsResourceRecord> rrset = list(DnsResourceRecord.a("www.example.com.", 300,
                InetAddress.getByName("192.0.2.1")));
        Capture c = deliver(DnsMessage.createQuery(1, "www.example.com.", DnsType.A), q,
                response(q, 0, rrset, none()));
        assertFalse(c.response.isAuthenticatedData());
        assertEquals(1, c.response.getAnswers().size());
    }

    @Test
    public void testSecureDenialIsIngestedIntoProofCache() throws Exception {
        enableDnssec();
        TestKey zsk = ecdsaP256("example.com.", 257);
        anchors.addDNSKEYAnchor("example.com.", zsk.dnskey);
        resolver.put("example.com.", DnsType.DNSKEY, message(list(zsk.dnskey), none()));
        DnsQuestion q = question("b.example.com.", DnsType.A);
        DnsResourceRecord nsec = nsec("a.example.com.", "c.example.com.", new int[] {1, 47});
        DnsResourceRecord sig = signCurrent(list(nsec), zsk, "example.com.");
        DnsMessage up = response(q, DnsMessage.RCODE_NXDOMAIN, none(), list(nsec, sig));
        Capture c = deliver(DnsMessage.createQuery(1, "b.example.com.", DnsType.A), q, up);
        assertTrue(c.response.isAuthenticatedData());
        assertEquals(DnsMessage.RCODE_NXDOMAIN, c.response.getRcode());

        // NODATA with NSEC is also a denial
        DnsQuestion q2 = question("a.example.com.", DnsType.AAAA);
        DnsMessage nodata = response(q2, 0, none(), list(nsec, sig));
        Capture c2 = deliver(DnsMessage.createQuery(2, "a.example.com.", DnsType.AAAA), q2, nodata);
        assertEquals(1, c2.calls);
    }

    @Test
    public void testOtherRcodesAreNotDenials() throws Exception {
        enableDnssec();
        DnsQuestion q = question("b.example.com.", DnsType.A);
        DnsMessage refused = response(q, DnsMessage.RCODE_REFUSED, none(), none());
        Capture c = deliver(DnsMessage.createQuery(1, "b.example.com.", DnsType.A), q, refused);
        assertEquals(DnsMessage.RCODE_REFUSED, c.response.getRcode());
        DnsMessage emptyNoerror = response(q, 0, none(), none());
        Capture c2 = deliver(DnsMessage.createQuery(1, "b.example.com.", DnsType.A), q, emptyNoerror);
        assertEquals(1, c2.calls);
    }
}
