/*
 * DnsServerPresentationTest.java
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
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.server.DnsQueryHandler;
import org.bluezoo.gumdrop.dns.server.DnsServer;
import org.bluezoo.gumdrop.dns.server.UpstreamRelayHandler;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests the response-presentation paths of {@link DnsServer}: DNSSEC record
 * stripping for clients without DO, MQTYPE merging into an existing OPT
 * record and payload limits, metrics-enabled datagram handling, and the
 * start/stop wiring of DoT and DoQ listeners using mock listeners.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsServerPresentationTest {

    /** Datagram listener that records responses and start/stop calls. */
    private static final class MockListener extends DnsListener {
        final List<ByteBuffer> sent = new ArrayList<ByteBuffer>();
        TelemetryConfig telemetry;

        @Override
        public void sendTo(ByteBuffer data, InetSocketAddress destination) {
            sent.add(data);
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }

        @Override
        public TelemetryConfig getTelemetryConfig() {
            return telemetry;
        }

        @Override
        public void start(Gumdrop gumdrop) {
        }

        @Override
        public void stop() {
        }
    }

    /** DoT listener that does not bind. */
    private static final class MockDoT extends DoTListener {
        int starts;
        int stops;

        @Override
        public void start(Gumdrop gumdrop) {
            starts++;
        }

        @Override
        public void stop() {
            stops++;
        }
    }

    /** DoQ listener that does not bind. */
    private static final class MockDoQ extends DoQListener {
        int starts;
        int stops;

        @Override
        public void start(Gumdrop gumdrop) {
            starts++;
        }

        @Override
        public void stop() {
            stops++;
        }
    }

    private MockListener listener;
    private DnsServer server;
    private InetSocketAddress source;
    private int completed;

    private final Runnable done = new Runnable() {
        @Override
        public void run() {
            completed++;
        }
    };

    @Before
    public void setUp() throws Exception {
        listener = new MockListener();
        server = new DnsServer();
        source = new InetSocketAddress(InetAddress.getByName("192.0.2.77"), 5300);
    }

    private DnsMessage lastResponse() throws Exception {
        assertFalse(listener.sent.isEmpty());
        return DnsMessage.parse(listener.sent.get(listener.sent.size() - 1));
    }

    private static DnsResourceRecord raw(DnsType type, String name) {
        return new DnsResourceRecord(name, type, DnsClass.IN, 300, new byte[] {0, 1, 2, 3});
    }

    private static List<DnsResourceRecord> mixedAnswers() throws Exception {
        List<DnsResourceRecord> list = new ArrayList<DnsResourceRecord>();
        list.add(DnsResourceRecord.a("www.example.com.", 300, InetAddress.getByName("192.0.2.1")));
        list.add(raw(DnsType.RRSIG, "www.example.com."));
        list.add(raw(DnsType.DNSKEY, "www.example.com."));
        list.add(raw(DnsType.DS, "www.example.com."));
        list.add(raw(DnsType.NSEC, "www.example.com."));
        list.add(raw(DnsType.NSEC3, "www.example.com."));
        list.add(raw(DnsType.NSEC3PARAM, "www.example.com."));
        return list;
    }

    private UpstreamRelayHandler dnssecRelayWithCache() throws Exception {
        UpstreamRelayHandler relay = UpstreamRelayHandler.builder().useSystemResolvers(false).build();
        relay.setDnssecEnabled(true);
        DnsCache cache = new DnsCache();
        DnsQuestion q = new DnsQuestion("www.example.com", DnsType.A, DnsClass.IN);
        cache.cache(q, mixedAnswers());
        Field f = UpstreamRelayHandler.class.getDeclaredField("cache");
        f.setAccessible(true);
        f.set(relay, cache);
        return relay;
    }

    @Test
    public void testDnssecRecordsStrippedWithoutDoBit() throws Exception {
        server.setHandler(dnssecRelayWithCache());
        server.handleDatagram(listener,
                DnsMessage.createQuery(1, "www.example.com.", DnsType.A).serialize(), source, done);
        DnsMessage r = lastResponse();
        assertEquals("rcode " + r.getRcode(), 1, r.getAnswers().size());
        assertEquals(DnsType.A, r.getAnswers().get(0).getType());
        assertEquals(1, completed);
    }

    @Test
    public void testDnssecRecordsKeptWithDoBit() throws Exception {
        server.setHandler(dnssecRelayWithCache());
        List<DnsResourceRecord> adds = new ArrayList<DnsResourceRecord>();
        adds.add(DnsResourceRecord.opt(4096, DnsResourceRecord.EDNS_FLAG_DO, new byte[0]));
        DnsMessage q = DnsMessage.createQuery(2, "www.example.com.", DnsType.A, adds);
        server.handleDatagram(listener, q.serialize(), source, done);
        assertEquals(7, lastResponse().getAnswers().size());
    }

    @Test
    public void testRelayWithoutDnssecKeepsRecords() throws Exception {
        UpstreamRelayHandler relay = dnssecRelayWithCache();
        relay.setDnssecEnabled(false);
        server.setHandler(relay);
        server.handleDatagram(listener,
                DnsMessage.createQuery(3, "www.example.com.", DnsType.A).serialize(), source, done);
        assertEquals(7, lastResponse().getAnswers().size());
    }

    @Test
    public void testMqtypeMergesIntoExistingOptAndHonoursPayloadLimit() throws Exception {
        final List<DnsResourceRecord> big = new ArrayList<DnsResourceRecord>();
        for (int i = 0; i < 40; i++) {
            big.add(DnsResourceRecord.txt("www.example.com.", 60,
                    "padding-padding-padding-padding-padding-" + i));
        }
        server.setHandler(new DnsQueryHandler() {
            @Override
            public void handleQuery(DnsMessage query, SelectorLoop loop, DnsQueryCallback cb) {
                DnsType type = query.getQuestions().get(0).getType();
                List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>();
                try {
                    if (type == DnsType.A) {
                        answers.add(DnsResourceRecord.a("www.example.com.", 60,
                                InetAddress.getByName("192.0.2.1")));
                    } else if (type == DnsType.AAAA) {
                        answers.add(DnsResourceRecord.aaaa("www.example.com.", 60,
                                InetAddress.getByName("2001:db8::1")));
                    } else {
                        answers.addAll(big);
                    }
                } catch (Exception e) {
                    cb.onError(e.toString());
                    return;
                }
                DnsMessage base = query.createResponse(answers);
                List<DnsResourceRecord> adds = new ArrayList<DnsResourceRecord>();
                adds.add(DnsResourceRecord.opt(1232));
                cb.onResponse(query.createResponse(answers, base.getAuthorities(), adds));
            }
        });
        List<DnsType> types = new ArrayList<DnsType>();
        types.add(DnsType.AAAA);
        types.add(DnsType.TXT);
        List<DnsResourceRecord> adds = new ArrayList<DnsResourceRecord>();
        adds.add(DnsResourceRecord.opt(512, DnsMultiQType.buildMQTypeQueryOption(types)));
        DnsMessage q = DnsMessage.createQuery(4, "www.example.com.", DnsType.A, adds);
        server.handleDatagram(listener, q.serialize(), source, done);
        DnsMessage r = lastResponse();
        // AAAA fits and is merged; the large TXT set exceeds the 512 byte limit
        assertEquals(2, r.getAnswers().size());
        byte[] option = null;
        for (int i = 0; i < r.getAdditionals().size(); i++) {
            DnsResourceRecord rr = r.getAdditionals().get(i);
            if (rr.getType() == DnsType.OPT) {
                option = DnsCookie.findEdnsOption(rr.getRData(),
                        DnsMultiQType.EDNS_OPTION_MQTYPE_RESPONSE);
            }
        }
        assertNotNull(option);
        List<DnsType> covered = DnsMultiQType.parseMQTypeResponseOption(option);
        assertEquals(Collections.singletonList(DnsType.AAAA), covered);
    }

    @Test
    public void testCookieMergedIntoExistingOptOfResponse() throws Exception {
        server.setHandler(new DnsQueryHandler() {
            @Override
            public void handleQuery(DnsMessage query, SelectorLoop loop, DnsQueryCallback cb) {
                List<DnsResourceRecord> adds = new ArrayList<DnsResourceRecord>();
                adds.add(DnsResourceRecord.opt(1232));
                cb.onResponse(query.createResponse(Collections.<DnsResourceRecord>emptyList(),
                        Collections.<DnsResourceRecord>emptyList(), adds));
            }
        });
        byte[] client = new byte[] {9, 8, 7, 6, 5, 4, 3, 2};
        ByteBuffer opt = ByteBuffer.allocate(4 + client.length);
        opt.putShort((short) DnsCookie.EDNS_OPTION_COOKIE);
        opt.putShort((short) client.length);
        opt.put(client);
        List<DnsResourceRecord> adds = new ArrayList<DnsResourceRecord>();
        adds.add(DnsResourceRecord.opt(4096, opt.array()));
        server.handleDatagram(listener,
                DnsMessage.createQuery(5, "www.example.com.", DnsType.A, adds).serialize(), source, done);
        DnsMessage first = lastResponse();
        byte[] cookie = null;
        for (int i = 0; i < first.getAdditionals().size(); i++) {
            DnsResourceRecord rr = first.getAdditionals().get(i);
            if (rr.getType() == DnsType.OPT) {
                cookie = DnsCookie.findEdnsOption(rr.getRData(), DnsCookie.EDNS_OPTION_COOKIE);
            }
        }
        assertNotNull(cookie);
        assertTrue(cookie.length > 8);
        byte[] full = new byte[4 + cookie.length];
        ByteBuffer fb = ByteBuffer.wrap(full);
        fb.putShort((short) DnsCookie.EDNS_OPTION_COOKIE);
        fb.putShort((short) cookie.length);
        fb.put(cookie);
        List<DnsResourceRecord> adds2 = new ArrayList<DnsResourceRecord>();
        adds2.add(DnsResourceRecord.opt(4096, full));
        server.handleDatagram(listener,
                DnsMessage.createQuery(6, "www.example.com.", DnsType.A, adds2).serialize(), source, done);
        DnsMessage second = lastResponse();
        int opts = 0;
        byte[] echoed = null;
        for (int i = 0; i < second.getAdditionals().size(); i++) {
            DnsResourceRecord rr = second.getAdditionals().get(i);
            if (rr.getType() == DnsType.OPT) {
                opts++;
                echoed = DnsCookie.findEdnsOption(rr.getRData(), DnsCookie.EDNS_OPTION_COOKIE);
            }
        }
        assertEquals(1, opts);
        assertNotNull(echoed);
    }

    @Test
    public void testMetricsRecordedForQueriesAndResponses() throws Exception {
        TelemetryConfig tc = new TelemetryConfig();
        tc.metricsEnabled(true);
        listener.telemetry = tc;
        server.addListener(listener);
        server.setHandler(new DnsQueryHandler() {
            @Override
            public void handleQuery(DnsMessage query, SelectorLoop loop, DnsQueryCallback cb) {
                cb.onResponse(query.createResponse(Collections.<DnsResourceRecord>emptyList()));
            }
        });
        server.start(TestGumdrop.create());
        assertNotNull(server.getMetrics());
        server.handleDatagram(listener,
                DnsMessage.createQuery(7, "www.example.com.", DnsType.A).serialize(), source, done);
        server.handleDatagram(listener, DnsMessage.createNotify(8, "example.com.").serialize(),
                source, done);
        assertEquals(2, completed);
        assertEquals(2, listener.sent.size());
        boolean sawQueries = false;
        for (org.bluezoo.gumdrop.telemetry.metrics.Meter meter : tc.getMeters().values()) {
            List<org.bluezoo.gumdrop.telemetry.metrics.MetricData> data = meter.collect(
                    org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality.CUMULATIVE);
            for (int i = 0; i < data.size(); i++) {
                if ("dns.server.queries".equals(data.get(i).getName())) {
                    sawQueries = true;
                }
            }
        }
        assertTrue(sawQueries);
        server.stop();
    }

    @Test
    public void testStartWiresDotAndDoqListeners() throws Exception {
        MockDoT dot = new MockDoT();
        MockDoQ doq = new MockDoQ();
        server.addListener(dot);
        server.addListener(doq);
        server.start(TestGumdrop.create());
        assertEquals(1, dot.starts);
        assertEquals(1, doq.starts);
        assertNotNull(doq.getSelectorLoop());
        server.stop();
        assertEquals(1, dot.stops);
        assertEquals(1, doq.stops);
    }
}
