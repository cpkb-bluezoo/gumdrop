/*
 * UpstreamRelayHandlerEncryptedTest.java
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

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.TcpEndpoint;
import org.bluezoo.gumdrop.TcpTransportFactory;
import org.bluezoo.gumdrop.UdpEndpoint;
import org.bluezoo.gumdrop.UdpTransportFactory;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsClientTransport;
import org.bluezoo.gumdrop.dns.client.DnsClientTransportHandler;
import org.bluezoo.gumdrop.testsupport.InlineSelectorLoop;

import static org.junit.Assert.*;

/**
 * {@link UpstreamRelayHandler} over DoT and DoQ: one persistent connection
 * per upstream, pipelined queries matched by ID, lazy reconnect with backoff.
 * Driven through a mock transport; no sockets, explicit timers and clock.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class UpstreamRelayHandlerEncryptedTest {

    /** A timer the test fires explicitly. */
    private static final class MockTimer implements TimerHandle {
        final Runnable callback;
        boolean cancelled;

        MockTimer(Runnable callback) {
            this.callback = callback;
        }

        void fire() {
            if (!cancelled) {
                callback.run();
            }
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

    /** Records everything the relay asks of one upstream connection. */
    private static final class MockTransport implements DnsClientTransport {
        final UpstreamRelayHandler.Transport kind;
        DnsClientTransportHandler handler;
        int port;
        boolean closed;
        final List<byte[]> sent = new ArrayList<byte[]>();
        final List<MockTimer> timers = new ArrayList<MockTimer>();

        MockTransport(UpstreamRelayHandler.Transport kind) {
            this.kind = kind;
        }

        @Override
        public void open(InetAddress server, int port, SelectorLoop loop,
                         DnsClientTransportHandler handler) throws IOException {
            this.handler = handler;
            this.port = port;
        }

        @Override
        public void send(ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            sent.add(copy);
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            MockTimer t = new MockTimer(callback);
            timers.add(t);
            return t;
        }

        @Override
        public void close() {
            closed = true;
        }

        DnsMessage sentQuery(int index) throws Exception {
            return DnsMessage.parse(ByteBuffer.wrap(sent.get(index)));
        }
    }

    private static final class Source implements EncryptedUpstream.TransportSource {
        final List<MockTransport> created = new ArrayList<MockTransport>();

        @Override
        public DnsClientTransport create(UpstreamRelayHandler.Transport kind) {
            MockTransport t = new MockTransport(kind);
            created.add(t);
            return t;
        }
    }

    private static final class FakeClock implements EncryptedUpstream.Clock {
        long now = 1_000_000L;

        @Override
        public long millis() {
            return now;
        }
    }

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

    /** Plain factories that must never be used when DoT/DoQ is selected. */
    private static final class ForbiddenUdp extends UdpTransportFactory {
        int connects;

        @Override
        public UdpEndpoint connect(Gumdrop gumdrop, InetAddress host, int port,
                ProtocolHandler handler, SelectorLoop loop) {
            connects++;
            return null;
        }
    }

    private static final class ForbiddenTcp extends TcpTransportFactory {
        int connects;

        @Override
        public TcpEndpoint connect(Gumdrop gumdrop, InetAddress host, int port,
                ProtocolHandler handler, SelectorLoop loop) {
            connects++;
            return null;
        }
    }

    private UpstreamRelayHandler handler;
    private Source source;
    private FakeClock clock;
    private ForbiddenUdp udp;
    private ForbiddenTcp tcp;
    private final SelectorLoop loop = new InlineSelectorLoop();

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private void relay(UpstreamRelayHandler.Transport transport, String servers) throws Exception {
        handler = UpstreamRelayHandler.builder().useSystemResolvers(false)
                .transport(transport).upstreamServers(servers).build();
        source = new Source();
        clock = new FakeClock();
        udp = new ForbiddenUdp();
        tcp = new ForbiddenTcp();
        set(handler, "encryptedTransportSource", source);
        set(handler, "clock", clock);
        set(handler, "upstreamUdpFactory", udp);
        set(handler, "upstreamTcpFactory", tcp);
    }

    private Capture ask(int id, String name) {
        Capture c = new Capture();
        handler.handleQuery(DnsMessage.createQuery(id, name, DnsType.A), loop, c);
        return c;
    }

    private static DnsMessage answer(DnsMessage query, String address) throws Exception {
        List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>();
        answers.add(DnsResourceRecord.a(
                query.getQuestions().get(0).getName(), 120,
                InetAddress.getByName(address)));
        return query.createResponse(answers);
    }

    @Test
    public void dotQueryWaitsForConnectionThenIsAnsweredAndIdRestored() throws Exception {
        relay(UpstreamRelayHandler.Transport.DOT, "192.0.2.1");
        Capture c = ask(21, "www.example.com");
        assertEquals(1, source.created.size());
        MockTransport t = source.created.get(0);
        assertEquals(UpstreamRelayHandler.Transport.DOT, t.kind);
        assertEquals("an upstream without a port uses 853", 853, t.port);
        assertTrue("nothing is sent before the connection is ready", t.sent.isEmpty());

        t.handler.onConnected();
        assertEquals(1, t.sent.size());
        DnsMessage sent = t.sentQuery(0);
        t.handler.onReceive(answer(sent, "192.0.2.7").serialize());

        assertEquals(1, c.calls);
        assertEquals(21, c.response.getId());
        assertEquals(1, c.response.getAnswers().size());
        assertEquals("plain UDP and TCP are not used", 0, udp.connects + tcp.connects);
        assertFalse("the connection stays open for reuse", t.closed);
    }

    @Test
    public void explicitPortIsHonoured() throws Exception {
        relay(UpstreamRelayHandler.Transport.DOT, "192.0.2.1:8853");
        ask(1, "www.example.com");
        assertEquals(8853, source.created.get(0).port);
    }

    @Test
    public void doqUsesTheQuicTransport() throws Exception {
        relay(UpstreamRelayHandler.Transport.DOQ, "192.0.2.1");
        ask(1, "www.example.com");
        assertEquals(UpstreamRelayHandler.Transport.DOQ, source.created.get(0).kind);
        assertEquals(853, source.created.get(0).port);
    }

    @Test
    public void queriesArePipelinedOnOneConnectionAndMatchedById() throws Exception {
        relay(UpstreamRelayHandler.Transport.DOT, "192.0.2.1");
        Capture a = ask(31, "a.example.com");
        Capture b = ask(32, "b.example.com");
        assertEquals("one connection serves both", 1, source.created.size());
        MockTransport t = source.created.get(0);
        t.handler.onConnected();
        assertEquals(2, t.sent.size());

        // answered out of order
        t.handler.onReceive(answer(t.sentQuery(1), "192.0.2.2").serialize());
        assertEquals(0, a.calls);
        assertEquals(1, b.calls);
        t.handler.onReceive(answer(t.sentQuery(0), "192.0.2.1").serialize());
        assertEquals(1, a.calls);
        assertEquals(31, a.response.getId());
        assertEquals(32, b.response.getId());
        assertEquals("b.example.com", b.response.getQuestions().get(0).getName()
                .replaceAll("\\.$", ""));
    }

    @Test
    public void laterQueryReusesReadyConnection() throws Exception {
        relay(UpstreamRelayHandler.Transport.DOT, "192.0.2.1");
        ask(1, "a.example.com");
        MockTransport t = source.created.get(0);
        t.handler.onConnected();
        t.handler.onReceive(answer(t.sentQuery(0), "192.0.2.1").serialize());

        Capture c = ask(2, "b.example.com");
        assertEquals(1, source.created.size());
        assertEquals("sent at once on the ready connection", 2, t.sent.size());
        t.handler.onReceive(answer(t.sentQuery(1), "192.0.2.2").serialize());
        assertEquals(1, c.calls);
    }

    @Test
    public void orderlyCloseByUpstreamReconnectsLazilyWithoutBackoff() throws Exception {
        relay(UpstreamRelayHandler.Transport.DOT, "192.0.2.1");
        ask(1, "a.example.com");
        MockTransport first = source.created.get(0);
        first.handler.onConnected();
        first.handler.onReceive(answer(first.sentQuery(0), "192.0.2.1").serialize());

        first.handler.onClosed(); // idle timeout at the upstream

        Capture c = ask(2, "b.example.com");
        assertEquals("a new connection is opened at once", 2, source.created.size());
        MockTransport second = source.created.get(1);
        second.handler.onConnected();
        second.handler.onReceive(answer(second.sentQuery(0), "192.0.2.2").serialize());
        assertEquals(1, c.calls);
        assertEquals(1, c.response.getAnswers().size());
    }

    @Test
    public void connectionErrorFailsPendingThenBacksOffThenReconnects() throws Exception {
        relay(UpstreamRelayHandler.Transport.DOT, "192.0.2.1");
        Capture c = ask(1, "a.example.com");
        MockTransport first = source.created.get(0);
        first.handler.onConnected();
        first.handler.onError(new IOException("reset"));
        assertEquals(1, c.calls);
        assertEquals(DnsMessage.RCODE_SERVFAIL, c.response.getRcode());
        assertTrue(first.closed);

        Capture early = ask(2, "b.example.com");
        assertEquals("no reconnect inside the backoff window", 1, source.created.size());
        assertEquals(DnsMessage.RCODE_SERVFAIL, early.response.getRcode());

        clock.now += 60_000L;
        ask(3, "c.example.com");
        assertEquals("reconnects once the backoff has passed", 2, source.created.size());
    }

    @Test
    public void connectionClosedWithQueryOutstandingFailsIt() throws Exception {
        relay(UpstreamRelayHandler.Transport.DOT, "192.0.2.1");
        Capture c = ask(1, "a.example.com");
        MockTransport t = source.created.get(0);
        t.handler.onConnected();
        t.handler.onClosed();
        assertEquals(1, c.calls);
        assertEquals(DnsMessage.RCODE_SERVFAIL, c.response.getRcode());
    }

    @Test
    public void unknownIdIsIgnoredAndMismatchedQuestionFailsTheQuery() throws Exception {
        relay(UpstreamRelayHandler.Transport.DOT, "192.0.2.1");
        Capture c = ask(1, "a.example.com");
        MockTransport t = source.created.get(0);
        t.handler.onConnected();
        DnsMessage sent = t.sentQuery(0);

        DnsMessage stranger = new DnsMessage((sent.getId() + 1) & 0xFFFF,
                DnsMessage.FLAG_QR, sent.getQuestions(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList());
        t.handler.onReceive(stranger.serialize());
        assertEquals("a response for an unknown ID is dropped", 0, c.calls);

        DnsMessage other = DnsMessage.createQuery(sent.getId(), "other.example.com", DnsType.A);
        t.handler.onReceive(answer(other, "192.0.2.9").serialize());
        assertEquals(1, c.calls);
        assertEquals(DnsMessage.RCODE_SERVFAIL, c.response.getRcode());
        assertFalse("one bad answer does not drop the connection", t.closed);
    }

    @Test
    public void queryTimeoutFailsOnlyThatQuery() throws Exception {
        relay(UpstreamRelayHandler.Transport.DOT, "192.0.2.1");
        Capture slow = ask(1, "slow.example.com");
        Capture fast = ask(2, "fast.example.com");
        MockTransport t = source.created.get(0);
        t.handler.onConnected();
        t.handler.onReceive(answer(t.sentQuery(1), "192.0.2.2").serialize());
        assertEquals(1, fast.calls);

        t.timers.get(0).fire();
        assertEquals(1, slow.calls);
        assertEquals(DnsMessage.RCODE_SERVFAIL, slow.response.getRcode());
        assertFalse(t.closed);
    }

    @Test
    public void secondServerIsTriedWhenFirstFails() throws Exception {
        relay(UpstreamRelayHandler.Transport.DOT, "192.0.2.1 192.0.2.2");
        Capture c = ask(1, "a.example.com");
        MockTransport first = source.created.get(0);
        first.handler.onConnected();
        first.handler.onError(new IOException("refused"));
        assertEquals(2, source.created.size());
        MockTransport second = source.created.get(1);
        second.handler.onConnected();
        second.handler.onReceive(answer(second.sentQuery(0), "192.0.2.5").serialize());
        assertEquals(1, c.calls);
        assertEquals(1, c.response.getAnswers().size());
    }

    @Test
    public void stopClosesTheConnections() throws Exception {
        relay(UpstreamRelayHandler.Transport.DOT, "192.0.2.1");
        ask(1, "a.example.com");
        MockTransport t = source.created.get(0);
        handler.stop();
        assertTrue(t.closed);
    }
}
