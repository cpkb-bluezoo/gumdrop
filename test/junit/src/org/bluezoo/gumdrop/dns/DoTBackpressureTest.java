/*
 * DnsQueryContextTest.java
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.server.DnsQueryHandler;
import org.bluezoo.gumdrop.dns.server.DnsServer;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;

import static org.junit.Assert.*;

/**
 * Backpressure on a DoT or TCP DNS connection: no more than a set number of
 * the queries a client pipelines are in flight at once, the rest wait,
 * unread, until a response completes, and every one is answered.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DoTBackpressureTest {

    /** Holds every query's callback until the test completes it. */
    private static final class HoldingHandler implements DnsQueryHandler {
        final List<DnsQueryCallback> held = new ArrayList<DnsQueryCallback>();
        final List<DnsMessage> queries = new ArrayList<DnsMessage>();
        int outstanding;
        int maxOutstanding;
        boolean answerAtOnce;

        @Override
        public void handleQuery(DnsMessage query, SelectorLoop loop, DnsQueryCallback callback) {
            queries.add(query);
            if (answerAtOnce) {
                callback.onResponse(query.createResponse(
                        Collections.<DnsResourceRecord>emptyList()));
                return;
            }
            outstanding++;
            maxOutstanding = Math.max(maxOutstanding, outstanding);
            held.add(callback);
        }

        /** Completes the oldest held query. */
        void completeOne(DnsMessage forQuery) {
            DnsQueryCallback cb = held.remove(0);
            outstanding--;
            cb.onResponse(forQuery.createResponse(Collections.<DnsResourceRecord>emptyList()));
        }
    }

    private HoldingHandler handler;
    private DnsServer server;
    private BinaryRecordingEndpoint endpoint;
    private int next;

    @Before
    public void setUp() {
        handler = new HoldingHandler();
        server = new DnsServer();
        server.handler(handler);
        endpoint = new BinaryRecordingEndpoint();
        next = 0;
    }

    private static byte[] framed(int id, String name) {
        DnsMessage q = DnsMessage.createQuery(id, name, DnsType.A);
        ByteBuffer b = q.serialize();
        byte[] body = new byte[b.remaining()];
        b.get(body);
        byte[] out = new byte[body.length + 2];
        out[0] = (byte) (body.length >> 8);
        out[1] = (byte) body.length;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    private static byte[] concat(List<byte[]> parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private byte[] pipeline(int count) {
        List<byte[]> parts = new ArrayList<byte[]>();
        for (int i = 0; i < count; i++) {
            parts.add(framed(++next, "q" + next + ".example.com"));
        }
        return concat(parts);
    }

    private DoTProtocolHandler connection(int limit) {
        DoTProtocolHandler h = new DoTProtocolHandler(server, "dot", limit);
        h.connected(endpoint);
        return h;
    }

    /** The number of responses written so far. */
    private int responses() {
        byte[] all = endpoint.getAllBytes();
        int count = 0;
        int at = 0;
        while (at + 2 <= all.length) {
            int len = ((all[at] & 0xFF) << 8) | (all[at + 1] & 0xFF);
            at += 2 + len;
            count++;
        }
        return count;
    }

    @Test
    public void noMoreThanTheLimitAreStartedAtOnce() {
        DoTProtocolHandler h = connection(3);
        h.receive(ByteBuffer.wrap(pipeline(10)));
        assertEquals(3, handler.queries.size());
        assertEquals(3, handler.outstanding);
        assertTrue("reading stopped", endpoint.isReadPaused());
        assertEquals(1, endpoint.getPauseReadCount());
    }

    @Test
    public void aCompletedQueryLetsTheNextOneStart() {
        DoTProtocolHandler h = connection(3);
        h.receive(ByteBuffer.wrap(pipeline(10)));
        handler.completeOne(handler.queries.get(0));
        assertEquals("one more started", 4, handler.queries.size());
        assertEquals("still no more than the limit in flight", 3, handler.outstanding);
        assertEquals(1, responses());
    }

    @Test
    public void everyQueryIsAnsweredAndTheLimitIsNeverPassed() {
        DoTProtocolHandler h = connection(3);
        h.receive(ByteBuffer.wrap(pipeline(10)));
        int answered = 0;
        while (!handler.held.isEmpty()) {
            handler.completeOne(handler.queries.get(answered));
            answered++;
        }
        assertEquals(10, handler.queries.size());
        assertEquals(10, responses());
        assertTrue("at most 3 at once, was " + handler.maxOutstanding, handler.maxOutstanding <= 3);
        assertFalse("reading resumed", endpoint.isReadPaused());
        assertEquals(endpoint.getPauseReadCount(), endpoint.getResumeReadCount());
    }

    @Test
    public void dataThatArrivesWhilePausedIsKeptNotDropped() {
        DoTProtocolHandler h = connection(2);
        h.receive(ByteBuffer.wrap(pipeline(5)));
        assertEquals(2, handler.queries.size());
        h.receive(ByteBuffer.wrap(pipeline(3)));
        assertEquals("still only the limit", 2, handler.queries.size());
        int answered = 0;
        while (!handler.held.isEmpty()) {
            handler.completeOne(handler.queries.get(answered));
            answered++;
        }
        assertEquals(8, handler.queries.size());
        assertEquals(8, responses());
        assertTrue(handler.maxOutstanding <= 2);
    }

    @Test
    public void aMessageSplitAcrossReadsIsStillAssembledUnderTheLimit() {
        DoTProtocolHandler h = connection(1);
        byte[] two = pipeline(2);
        h.receive(ByteBuffer.wrap(two, 0, 5));
        assertEquals(0, handler.queries.size());
        h.receive(ByteBuffer.wrap(two, 5, two.length - 5));
        assertEquals(1, handler.queries.size());
        handler.completeOne(handler.queries.get(0));
        assertEquals(2, handler.queries.size());
        handler.completeOne(handler.queries.get(1));
        assertEquals(2, responses());
    }

    @Test
    public void aLimitOfZeroMeansNoLimit() {
        DoTProtocolHandler h = connection(0);
        h.receive(ByteBuffer.wrap(pipeline(50)));
        assertEquals(50, handler.queries.size());
        assertEquals(0, endpoint.getPauseReadCount());
    }

    @Test
    public void aHandlerThatAnswersAtOnceNeverTriggersBackpressure() {
        handler.answerAtOnce = true;
        DoTProtocolHandler h = connection(2);
        h.receive(ByteBuffer.wrap(pipeline(100)));
        assertEquals(100, handler.queries.size());
        assertEquals(100, responses());
        assertEquals(0, endpoint.getPauseReadCount());
    }

    @Test
    public void completingQueriesAfterTheConnectionClosedIsHarmless() {
        DoTProtocolHandler h = connection(2);
        h.receive(ByteBuffer.wrap(pipeline(6)));
        h.disconnected();
        handler.completeOne(handler.queries.get(0));
        handler.completeOne(handler.queries.get(1));
        assertEquals("nothing more is started for a closed connection", 2, handler.queries.size());
    }

    @Test
    public void theDefaultLimitIsGenerous() {
        DoTProtocolHandler h = new DoTProtocolHandler(server);
        h.connected(endpoint);
        h.receive(ByteBuffer.wrap(pipeline(DoTProtocolHandler.DEFAULT_MAX_IN_FLIGHT)));
        assertEquals(DoTProtocolHandler.DEFAULT_MAX_IN_FLIGHT, handler.queries.size());
        assertTrue("full, so no more is read", endpoint.isReadPaused());
        h.receive(ByteBuffer.wrap(pipeline(1)));
        assertEquals("the next one waits", DoTProtocolHandler.DEFAULT_MAX_IN_FLIGHT,
                handler.queries.size());
        assertTrue(endpoint.isReadPaused());
    }

    // ── the listeners ──

    @Test
    public void bothListenersTakeAnInFlightLimit() {
        DoTListener dot = new DoTListener();
        assertEquals(DoTProtocolHandler.DEFAULT_MAX_IN_FLIGHT, dot.getMaxInFlightQueries());
        assertSame(dot, dot.maxInFlightQueries(8));
        assertEquals(8, dot.getMaxInFlightQueries());
        DnsTcpListener tcp = new DnsTcpListener();
        assertEquals(DoTProtocolHandler.DEFAULT_MAX_IN_FLIGHT, tcp.getMaxInFlightQueries());
        assertSame(tcp, tcp.maxInFlightQueries(0));
        assertEquals(0, tcp.getMaxInFlightQueries());
    }

    @Test
    public void aNegativeLimitIsRefused() {
        try {
            new DoTListener().maxInFlightQueries(-1);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new DnsTcpListener().maxInFlightQueries(-1);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void theListenersHandlersUseTheirLimit() {
        DoTListener dot = new DoTListener().maxInFlightQueries(2);
        dot.server(server);
        DoTProtocolHandler h = (DoTProtocolHandler) dot.createHandler();
        h.connected(endpoint);
        h.receive(ByteBuffer.wrap(pipeline(6)));
        assertEquals(2, handler.queries.size());
    }

    // ── connection limits on the listeners ──

    @Test
    public void dotListenerHonoursItsConnectionLimits() throws Exception {
        DoTListener l = new DoTListener().maxConnections(2);
        InetSocketAddress a = new InetSocketAddress(InetAddress.getByName("192.0.2.10"), 4000);
        InetSocketAddress b = new InetSocketAddress(InetAddress.getByName("192.0.2.11"), 4000);
        InetSocketAddress c = new InetSocketAddress(InetAddress.getByName("192.0.2.12"), 4000);
        assertTrue(l.acceptConnection(a));
        l.connectionOpened(a);
        assertTrue(l.acceptConnection(b));
        l.connectionOpened(b);
        assertFalse("the third is over the cap", l.acceptConnection(c));
        l.connectionClosed(a);
        assertTrue("room again once one closes", l.acceptConnection(c));
    }

    @Test
    public void dotListenerHonoursItsPerAddressLimit() throws Exception {
        DoTListener l = new DoTListener().maxConnectionsPerIP(1);
        InetSocketAddress a1 = new InetSocketAddress(InetAddress.getByName("192.0.2.10"), 4000);
        InetSocketAddress a2 = new InetSocketAddress(InetAddress.getByName("192.0.2.10"), 4001);
        InetSocketAddress other = new InetSocketAddress(InetAddress.getByName("192.0.2.11"), 4000);
        assertTrue(l.acceptConnection(a1));
        l.connectionOpened(a1);
        assertFalse("a second from the same address", l.acceptConnection(a2));
        assertTrue("another address is unaffected", l.acceptConnection(other));
    }

    @Test
    public void tcpListenerHasTheSameLimitsAndTimeoutsAsFluentSetters() {
        DnsTcpListener l = new DnsTcpListener();
        assertSame(l, l.maxConnections(10));
        assertSame(l, l.maxConnectionsPerIP(2));
        assertSame(l, l.idleTimeoutMs(60000));
        assertSame(l, l.readTimeoutMs(5000));
        assertSame(l, l.connectionTimeoutMs(5000));
        assertSame(l, l.rateLimit("5/10s"));
        assertEquals(10, l.getMaxConnections());
    }

    // ── metrics ──

    private static MetricData find(TelemetryConfig tc, String name) {
        for (Meter meter : tc.getMeters().values()) {
            for (MetricData d : meter.collect(AggregationTemporality.CUMULATIVE)) {
                if (d.getName().equals(name)) {
                    return d;
                }
            }
        }
        return null;
    }

    private static final class MeteredListener extends DnsListener {
        final TelemetryConfig telemetry = new TelemetryConfig();

        MeteredListener() {
            telemetry.metricsEnabled(true);
        }

        @Override
        public void sendTo(ByteBuffer data, InetSocketAddress destination) {
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

    @Test
    public void inFlightQueriesAndBackpressureAreReported() {
        MeteredListener metered = new MeteredListener();
        server.addListener(metered);
        server.start((Gumdrop) null);
        DoTProtocolHandler h = connection(2);
        h.receive(ByteBuffer.wrap(pipeline(6)));
        int answered = 0;
        while (!handler.held.isEmpty()) {
            handler.completeOne(handler.queries.get(answered));
            answered++;
        }
        MetricData backpressure = find(metered.telemetry, "dns.server.connection.backpressure");
        assertNotNull(backpressure);
        assertEquals(1L, backpressure.getNumberDataPoints().get(0).getLongValue());
        MetricData inFlight = find(metered.telemetry, "dns.server.connection.inflight");
        assertNotNull(inFlight);
        assertEquals("one reading per query started", 6L,
                inFlight.getHistogramDataPoints().get(0).getCount());
        server.stop();
    }
}
