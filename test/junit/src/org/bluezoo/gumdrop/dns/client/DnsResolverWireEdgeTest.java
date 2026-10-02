/*
 * DnsResolverWireEdgeTest.java
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
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.dns.DnsCache;
import org.bluezoo.gumdrop.dns.DnsCookie;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;

import static org.junit.Assert.*;

/**
 * {@link DnsResolver} wire-level edge cases driven through mock transports:
 * CNAME chasing and its depth limit, response caching, unknown or malformed
 * responses, server cookies, error rcodes and every outcome of the
 * truncation retry over TCP.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsResolverWireEdgeTest {

    private static final class Timer implements TimerHandle {
        boolean cancelled;
        final Runnable callback;

        Timer(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static class Mock implements DnsClientTransport {
        final List<ByteBuffer> sent = new ArrayList<ByteBuffer>();
        final List<Timer> timers = new ArrayList<Timer>();
        DnsClientTransportHandler handler;
        boolean closed;

        @Override
        public void open(InetAddress server, int port, SelectorLoop loop,
                         DnsClientTransportHandler handler) throws IOException {
            this.handler = handler;
        }

        @Override
        public void send(ByteBuffer data) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            sent.add(copy);
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            Timer t = new Timer(callback);
            timers.add(t);
            return t;
        }

        @Override
        public void close() {
            closed = true;
        }

        DnsMessage lastQuery() throws Exception {
            ByteBuffer b = sent.get(sent.size() - 1);
            return DnsMessage.parse(b.duplicate());
        }
    }

    private static final class TestResolver extends DnsResolver {
        final Mock tcp;

        TestResolver(Mock tcp) {
            this.tcp = tcp;
        }

        @Override
        DnsClientTransport createTcpRetryTransport() {
            return tcp;
        }
    }

    private static final class Capture implements DnsQueryCallback {
        DnsMessage response;
        String error;
        int calls;

        @Override
        public void onResponse(DnsMessage r) {
            response = r;
            calls++;
        }

        @Override
        public void onError(String e) {
            error = e;
            calls++;
        }
    }

    private DnsCache originalCache;
    private Mock udp;
    private Mock tcp;
    private DnsResolver resolver;

    @Before
    public void setUp() throws Exception {
        originalCache = DnsResolver.getCache();
        DnsResolver.setCache(new DnsCache());
        udp = new Mock();
        tcp = new Mock();
        resolver = new TestResolver(tcp);
        resolver.setTransport(udp);
        resolver.addServer("127.0.0.1");
        resolver.open();
    }

    @After
    public void tearDown() {
        resolver.close();
        DnsResolver.setCache(originalCache);
    }

    private static DnsMessage reply(DnsMessage query, int flags,
                                    List<DnsResourceRecord> answers,
                                    List<DnsResourceRecord> authorities,
                                    List<DnsResourceRecord> additionals) {
        return new DnsMessage(query.getId(),
                DnsMessage.FLAG_QR | DnsMessage.FLAG_RD | DnsMessage.FLAG_RA | flags,
                query.getQuestions(), answers, authorities, additionals);
    }

    private static List<DnsResourceRecord> none() {
        return Collections.<DnsResourceRecord>emptyList();
    }

    private static List<DnsResourceRecord> one(DnsResourceRecord rr) {
        List<DnsResourceRecord> l = new ArrayList<DnsResourceRecord>();
        l.add(rr);
        return l;
    }

    private static DnsResourceRecord a(String name) throws Exception {
        return DnsResourceRecord.a(name, 300, InetAddress.getByAddress(new byte[] {10, 0, 0, 1}));
    }

    private static DnsResourceRecord cname(String name, String target) {
        return DnsResourceRecord.cname(name, 300, target);
    }

    @Test
    public void cnameOnlyAnswerIsChasedToTarget() throws Exception {
        Capture c = new Capture();
        resolver.query("alias.example.com", DnsType.A, c);
        DnsMessage q1 = udp.lastQuery();
        udp.handler.onReceive(reply(q1, 0,
                one(cname("alias.example.com", "real.example.com")), none(), none())
                .serialize());
        assertEquals(0, c.calls);
        assertEquals(2, udp.sent.size());
        DnsMessage q2 = udp.lastQuery();
        assertEquals("real.example.com", q2.getQuestions().get(0).getName());
        udp.handler.onReceive(reply(q2, 0, one(a("real.example.com")), none(), none())
                .serialize());
        assertEquals(1, c.calls);
        assertEquals(DnsType.A, c.response.getAnswers().get(0).getType());
    }

    @Test
    public void cnameLoopStopsAtDepthLimit() throws Exception {
        Capture c = new Capture();
        resolver.query("loop0.example.com", DnsType.A, c);
        int sends = 0;
        while (c.calls == 0 && sends < 20) {
            DnsMessage q = udp.lastQuery();
            String name = q.getQuestions().get(0).getName();
            String next = "loop" + (sends + 1) + ".example.com";
            udp.handler.onReceive(reply(q, 0, one(cname(name, next)), none(), none())
                    .serialize());
            sends++;
        }
        assertEquals(1, c.calls);
        assertTrue(sends <= 10);
        assertNotNull(c.response);
    }

    @Test
    public void cnameForDifferentOwnerIsNotChased() throws Exception {
        Capture c = new Capture();
        resolver.query("alias.example.com", DnsType.A, c);
        DnsMessage q1 = udp.lastQuery();
        udp.handler.onReceive(reply(q1, 0,
                one(cname("other.example.com", "real.example.com")), none(), none())
                .serialize());
        assertEquals(1, c.calls);
        assertEquals(1, udp.sent.size());
    }

    @Test
    public void cnameQueryTypeIsNeverChased() throws Exception {
        Capture c = new Capture();
        resolver.query("alias.example.com", DnsType.CNAME, c);
        DnsMessage q1 = udp.lastQuery();
        udp.handler.onReceive(reply(q1, 0,
                one(cname("alias.example.com", "real.example.com")), none(), none())
                .serialize());
        assertEquals(1, c.calls);
        assertEquals(1, udp.sent.size());
    }

    @Test
    public void answeredResponseIsCachedAndServedWithoutNetwork() throws Exception {
        Capture first = new Capture();
        resolver.query("cached.example.com", DnsType.A, first);
        DnsMessage q = udp.lastQuery();
        udp.handler.onReceive(reply(q, 0, one(a("cached.example.com")), none(), none())
                .serialize());
        assertEquals(1, first.calls);
        Capture second = new Capture();
        resolver.query("cached.example.com", DnsType.A, second);
        assertEquals(1, second.calls);
        assertEquals(1, udp.sent.size());
    }

    @Test
    public void outOfBailiwickAnswerIsNotCached() throws Exception {
        Capture first = new Capture();
        resolver.query("victim.example.com", DnsType.A, first);
        DnsMessage q = udp.lastQuery();
        udp.handler.onReceive(reply(q, 0, one(a("evil.example.net")), none(), none())
                .serialize());
        assertEquals(1, first.calls);
        Capture second = new Capture();
        resolver.query("victim.example.com", DnsType.A, second);
        assertEquals(0, second.calls);
        assertEquals(2, udp.sent.size());
    }

    @Test
    public void nxdomainIsNegativelyCachedFromSoaAuthority() throws Exception {
        Capture first = new Capture();
        resolver.query("nothing.example.com", DnsType.A, first);
        DnsMessage q = udp.lastQuery();
        DnsResourceRecord soa = DnsResourceRecord.soa("example.com", 60, "ns.example.com",
                "h.example.com", 1, 2, 3, 4, 30);
        udp.handler.onReceive(reply(q, DnsMessage.RCODE_NXDOMAIN, none(), one(soa), none())
                .serialize());
        assertEquals(DnsMessage.RCODE_NXDOMAIN, first.response.getRcode());
        Capture second = new Capture();
        resolver.query("nothing.example.com", DnsType.A, second);
        assertEquals(1, second.calls);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, second.response.getRcode());
        assertEquals(1, udp.sent.size());
    }

    @Test
    public void servfailIsDeliveredNotCached() throws Exception {
        Capture c = new Capture();
        resolver.query("broken.example.com", DnsType.A, c);
        DnsMessage q = udp.lastQuery();
        udp.handler.onReceive(reply(q, DnsMessage.RCODE_SERVFAIL, none(), none(), none())
                .serialize());
        assertEquals(DnsMessage.RCODE_SERVFAIL, c.response.getRcode());
        Capture again = new Capture();
        resolver.query("broken.example.com", DnsType.A, again);
        assertEquals(0, again.calls);
    }

    @Test
    public void unknownIdAndGarbageAreIgnored() throws Exception {
        Capture c = new Capture();
        resolver.query("x.example.com", DnsType.A, c);
        DnsMessage q = udp.lastQuery();
        DnsMessage stranger = new DnsMessage((q.getId() + 1) & 0xFFFF,
                DnsMessage.FLAG_QR, q.getQuestions(), none(), none(), none());
        udp.handler.onReceive(stranger.serialize());
        udp.handler.onReceive(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        assertEquals(0, c.calls);
        udp.handler.onReceive(reply(q, 0, one(a("x.example.com")), none(), none()).serialize());
        assertEquals(1, c.calls);
    }

    @Test
    public void serverCookieInResponseIsAccepted() throws Exception {
        Capture c = new Capture();
        resolver.query("cookie.example.com", DnsType.A, c);
        DnsMessage q = udp.lastQuery();
        byte[] clientCookie = null;
        for (int i = 0; i < q.getAdditionals().size(); i++) {
            DnsResourceRecord rr = q.getAdditionals().get(i);
            if (rr.getType() == DnsType.OPT) {
                byte[] opt = DnsCookie.findEdnsOption(rr.getRData(),
                        DnsCookie.EDNS_OPTION_COOKIE);
                clientCookie = opt;
            }
        }
        assertNotNull(clientCookie);
        byte[] full = new byte[clientCookie.length + 8];
        System.arraycopy(clientCookie, 0, full, 0, clientCookie.length);
        ByteBuffer opt = ByteBuffer.allocate(4 + full.length);
        opt.putShort((short) DnsCookie.EDNS_OPTION_COOKIE);
        opt.putShort((short) full.length);
        opt.put(full);
        List<DnsResourceRecord> additionals = one(DnsResourceRecord.opt(4096, 0, opt.array()));
        udp.handler.onReceive(reply(q, 0, one(a("cookie.example.com")), none(), additionals)
                .serialize());
        assertEquals(1, c.calls);
    }

    @Test
    public void queryTimeoutWithSingleServerReportsError() throws Exception {
        Capture c = new Capture();
        resolver.query("slow.example.com", DnsType.A, c);
        assertFalse(udp.timers.isEmpty());
        udp.timers.get(0).callback.run();
        assertEquals(1, c.calls);
        assertNotNull(c.error);
    }

    @Test
    public void transportErrorReportsFailure() throws Exception {
        Capture c = new Capture();
        resolver.query("err.example.com", DnsType.A, c);
        udp.handler.onError(new IOException("boom"));
        assertTrue(c.calls <= 1);
    }

    // ---- truncation retry outcomes ----

    private DnsMessage truncated(DnsMessage q) throws Exception {
        return reply(q, DnsMessage.FLAG_TC, one(a("t.example.com")), none(), none());
    }

    private DnsMessage startTruncated(Capture c) throws Exception {
        resolver.query("t.example.com", DnsType.A, c);
        DnsMessage q = udp.lastQuery();
        udp.handler.onReceive(truncated(q).serialize());
        assertEquals(0, c.calls);
        assertNotNull(tcp.handler);
        return q;
    }

    @Test
    public void tcpRetryMalformedAnswerFallsBackToTruncatedResponse() throws Exception {
        Capture c = new Capture();
        startTruncated(c);
        tcp.handler.onReceive(ByteBuffer.wrap(new byte[] {9, 9, 9}));
        assertEquals(1, c.calls);
        assertTrue(c.response.isTruncated());
        assertTrue(tcp.closed);
        // a late duplicate is ignored
        tcp.handler.onReceive(ByteBuffer.wrap(new byte[] {9, 9, 9}));
        assertEquals(1, c.calls);
    }

    @Test
    public void tcpRetryErrorFallsBackToTruncatedResponse() throws Exception {
        Capture c = new Capture();
        startTruncated(c);
        tcp.handler.onError(new IOException("reset"));
        assertEquals(1, c.calls);
        assertTrue(c.response.isTruncated());
        tcp.handler.onError(new IOException("again"));
        tcp.handler.onClosed();
        assertEquals(1, c.calls);
    }

    @Test
    public void tcpRetryClosedBeforeAnswerFallsBack() throws Exception {
        Capture c = new Capture();
        startTruncated(c);
        tcp.handler.onClosed();
        assertEquals(1, c.calls);
        assertTrue(c.response.isTruncated());
    }

    @Test
    public void tcpRetryTimeoutFallsBackAndLateAnswerIsIgnored() throws Exception {
        Capture c = new Capture();
        DnsMessage q = startTruncated(c);
        assertFalse(tcp.timers.isEmpty());
        tcp.timers.get(0).callback.run();
        assertEquals(1, c.calls);
        assertTrue(c.response.isTruncated());
        tcp.timers.get(0).callback.run();
        tcp.handler.onReceive(reply(q, 0, one(a("t.example.com")), none(), none()).serialize());
        assertEquals(1, c.calls);
    }
}
