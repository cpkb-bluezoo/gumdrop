/*
 * DNSResolverIpv6FallbackTest.java
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

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DnsClass;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #502: the well-known public fallbacks use IPv6 first, with a short
 * head start, without stalling where IPv6 is unrouted.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSResolverIpv6FallbackTest {

    private static final String CF6 = "2606:4700:4700:0:0:0:0:1111";
    private static final String CF4 = "1.1.1.1";
    private static final String Q96 = "2620:fe:0:0:0:0:0:fe";
    private static final String Q94 = "9.9.9.9";

    @Before
    public void setUp() {
        DnsServerCapabilityCache.clear();
        DnsFallbackHealth.reset();
    }

    @After
    public void tearDown() {
        DnsServerCapabilityCache.clear();
        DnsFallbackHealth.reset();
    }

    @Test
    public void listInterleavesIpv6AndIpv4ByProvider() throws Exception {
        Harness h = new Harness(true);
        h.resolver.addWellKnownPublicFallbacks();
        h.resolver.open();
        List<String> opened = new ArrayList<String>(h.transports.keySet());
        assertEquals(12, h.opened.size());
        assertEquals(CF6, h.opened.get(0));
        assertEquals(CF4, h.opened.get(1));
        assertEquals(Q96, h.opened.get(2));
        assertEquals(Q94, h.opened.get(3));
        assertEquals("2001:4860:4860:0:0:0:0:8888", h.opened.get(4));
        assertEquals("8.8.8.8", h.opened.get(5));
        assertEquals("2606:4700:4700:0:0:0:0:1001", h.opened.get(6));
        assertEquals("1.0.0.1", h.opened.get(7));
        assertTrue(opened.size() == 12);
    }

    @Test
    public void ipv6FirstThenIpv4AfterHeadStart() throws Exception {
        Harness h = new Harness(true);
        h.start();
        h.resolver.queryA("a.example", h.callback);
        assertEquals(1, h.transports.get(CF6).sent.size());
        assertEquals(0, h.transports.get(CF4).sent.size());

        h.transports.get(CF6).fire(DnsResolver.IPV6_HEAD_START_MS);
        assertEquals("the IPv4 partner is also asked after the head start", 1, h.transports.get(CF4).sent.size());

        h.transports.get(CF4).answer(0);
        assertEquals(1, h.answers);
    }

    @Test
    public void ipv6AnswerNeedsNoIpv4() throws Exception {
        Harness h = new Harness(true);
        h.start();
        h.resolver.queryA("a.example", h.callback);
        h.transports.get(CF6).answer(0);
        assertEquals(1, h.answers);
        assertEquals(0, h.transports.get(CF4).sent.size());
        // the head start timer was cancelled
        h.transports.get(CF6).fire(DnsResolver.IPV6_HEAD_START_MS);
        assertEquals(0, h.transports.get(CF4).sent.size());
    }

    @Test
    public void ipv4WinSkipsHeadStartUntilItExpires() throws Exception {
        Harness h = new Harness(true);
        h.start();
        h.resolver.queryA("a.example", h.callback);
        h.transports.get(CF6).fire(DnsResolver.IPV6_HEAD_START_MS);
        h.transports.get(CF4).answer(0);

        h.clearSent();
        h.resolver.queryA("b.example", h.callback);
        assertEquals("IPv4 goes first while it is preferred", 1, h.transports.get(CF4).sent.size());
        assertEquals(0, h.transports.get(CF6).sent.size());
        assertEquals(0, h.transports.get(Q96).sent.size());
        h.transports.get(CF4).answer(0);

        h.now += DnsFallbackHealth.IPV4_WIN_MS + 1;
        h.clearSent();
        h.resolver.queryA("c.example", h.callback);
        assertEquals("the next IPv6 probe rotates to another provider", 1, h.transports.get(Q96).sent.size());
        assertEquals(0, h.transports.get(CF6).sent.size());
    }

    @Test
    public void noGlobalIpv6SkipsIpv6WithoutTimeouts() throws Exception {
        Harness h = new Harness(false);
        h.start();
        assertEquals("no IPv6 transport is opened", 6, h.opened.size());
        h.resolver.queryA("a.example", h.callback);
        assertEquals(1, h.transports.get(CF4).sent.size());
        assertTrue("no head start timer", h.transports.get(CF4).timers.size() == 1);
        h.transports.get(CF4).answer(0);
        assertEquals(1, h.answers);
    }

    @Test
    public void ipv6IsTriedAgainAfterNoRouteExpires() throws Exception {
        Harness h = new Harness(false);
        h.start();
        h.hasGlobal = true;
        h.now += DnsFallbackHealth.NO_ROUTE_MS + 1;
        h.resolver.queryA("a.example", h.callback);
        assertTrue("IPv6 opened lazily and used", h.transports.containsKey(CF6));
        assertEquals(1, h.transports.get(CF6).sent.size());
    }

    @Test
    public void unopenableIpv6DoesNotFailTheResolver() throws Exception {
        Harness h = new Harness(true);
        h.failIpv6Open = true;
        h.start();
        assertTrue(DnsFallbackHealth.isNoRoute(h.now));
        h.resolver.queryA("a.example", h.callback);
        assertEquals(1, h.transports.get(CF4).sent.size());
    }

    @Test
    public void oneIpv6AddressTimingOutLeavesTheOthersEligible() throws Exception {
        Harness h = new Harness(true);
        h.start();
        h.resolver.queryA("a.example", h.callback);
        h.transports.get(CF6).fire(DnsResolver.IPV6_HEAD_START_MS);
        // neither Cloudflare address answers: the full query timeout
        h.transports.get(CF6).fire(5000);
        assertEquals("moves on to the next provider's IPv6 address", 1, h.transports.get(Q96).sent.size());
        assertFalse(DnsFallbackHealth.isNoRoute(h.now));

        h.transports.get(Q96).answer(0);
        assertEquals(1, h.answers);

        h.clearSent();
        h.resolver.queryA("b.example", h.callback);
        assertEquals("the failed address is skipped, the next stays first", 0, h.transports.get(CF6).sent.size());
        assertEquals(1, h.transports.get(Q96).sent.size());
    }

    @Test
    public void configuredServersKeepTheirOrder() throws Exception {
        Harness h = new Harness(true);
        h.resolver.addServer("192.0.2.1");
        h.resolver.addServer("192.0.2.2");
        h.resolver.open();
        h.resolver.queryA("a.example", h.callback);
        assertEquals(1, h.transports.get("192.0.2.1").sent.size());
        h.transports.get("192.0.2.1").fire(5000);
        assertEquals(1, h.transports.get("192.0.2.2").sent.size());
    }

    // ── Test doubles ──

    private static final class Harness {
        long now = 1_000_000L;
        boolean hasGlobal;
        boolean failIpv6Open;
        int answers;
        final List<String> opened = new ArrayList<String>();
        final Map<String, ManualTransport> transports = new HashMap<String, ManualTransport>();
        final DnsResolver resolver;
        final DnsQueryCallback callback = new DnsQueryCallback() {
            @Override
            public void onResponse(DnsMessage response) {
                answers++;
            }

            @Override
            public void onError(String error) {
                throw new AssertionError(error);
            }
        };

        Harness(boolean hasGlobal) {
            this.hasGlobal = hasGlobal;
            final Harness self = this;
            resolver = new DnsResolver() {
                @Override
                long currentTimeMillis() {
                    return self.now;
                }

                @Override
                boolean hostHasGlobalIpv6() {
                    return self.hasGlobal;
                }

                @Override
                DnsClientTransport newTransportInstance(DnsTransportType type, DnsServerCapabilities caps) {
                    return new ManualTransport(self);
                }
            };
        }

        void start() throws Exception {
            resolver.addWellKnownPublicFallbacks();
            resolver.open();
        }

        void clearSent() {
            for (ManualTransport t : transports.values()) {
                t.sent.clear();
            }
        }
    }

    private static final class ManualTransport implements DnsClientTransport {
        final Harness harness;
        DnsClientTransportHandler handler;
        final List<byte[]> sent = new ArrayList<byte[]>();
        final List<Object[]> timers = new ArrayList<Object[]>();

        ManualTransport(Harness harness) {
            this.harness = harness;
        }

        @Override
        public void open(InetAddress server, int port, SelectorLoop loop, DnsClientTransportHandler handler)
                throws IOException {
            if (server.getAddress().length == 16 && harness.failIpv6Open) {
                throw new IOException("Network is unreachable");
            }
            this.handler = handler;
            harness.opened.add(server.getHostAddress());
            harness.transports.put(server.getHostAddress(), this);
        }

        @Override
        public void send(ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            sent.add(copy);
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            final boolean[] cancelled = new boolean[1];
            timers.add(new Object[] { Long.valueOf(delayMs), callback, cancelled });
            return new TimerHandle() {
                @Override
                public void cancel() {
                    cancelled[0] = true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelled[0];
                }
            };
        }

        /** Runs this transport's uncancelled timers scheduled for the given delay. */
        void fire(long delayMs) {
            for (Object[] timer : new ArrayList<Object[]>(timers)) {
                boolean[] cancelled = (boolean[]) timer[2];
                if (((Long) timer[0]).longValue() == delayMs && !cancelled[0]) {
                    cancelled[0] = true;
                    ((Runnable) timer[1]).run();
                }
            }
        }

        /** Answers the query sent at position {@code sentIndex}. */
        void answer(int sentIndex) throws Exception {
            byte[] query = sent.get(sentIndex);
            int id = ((query[0] & 0xff) << 8) | (query[1] & 0xff);
            List<DnsQuestion> questions = new ArrayList<DnsQuestion>();
            questions.add(new DnsQuestion("a.example", DnsType.A, DnsClass.IN));
            DnsMessage response = new DnsMessage(id, DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA,
                    questions, Collections.<DnsResourceRecord>emptyList(),
                    Collections.<DnsResourceRecord>emptyList(), Collections.<DnsResourceRecord>emptyList());
            handler.onReceive(response.serialize());
        }

        @Override
        public void close() {
        }
    }
}
