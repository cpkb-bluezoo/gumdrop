/*
 * DnsServerLifecycleTest.java
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsListener;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsServerMetrics;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.DoQListener;
import org.bluezoo.gumdrop.dns.DoTListener;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

import static org.junit.Assert.*;

/**
 * {@link DnsServer} configuration, lifecycle and handler-chain behaviour:
 * composer, listener lists, start/stop wiring, metrics enablement,
 * {@link DnsQueryHandlers#chain}, {@link EmptyDnsQueryHandler} and
 * {@link SyncDnsQueryHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsServerLifecycleTest {

    /** Listener that records start/stop instead of binding a socket. */
    private static final class MockListener extends DnsListener {
        int starts;
        int stops;
        boolean failStart;
        boolean failStop;
        TelemetryConfig telemetry;
        final List<ByteBuffer> sent = new ArrayList<ByteBuffer>();

        @Override
        public void start(Gumdrop gumdrop) {
            starts++;
            if (failStart) {
                throw new IllegalStateException("start");
            }
        }

        @Override
        public void stop() {
            stops++;
            if (failStop) {
                throw new IllegalStateException("stop");
            }
        }

        @Override
        public TelemetryConfig getTelemetryConfig() {
            return telemetry;
        }

        @Override
        public void sendTo(ByteBuffer data, InetSocketAddress destination) {
            sent.add(data);
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }
    }

    /** Handler that records lifecycle calls. */
    private static final class LifeHandler implements DnsQueryHandler {
        int starts;
        int stops;
        int gumdropStarts;

        @Override
        public void handleQuery(DnsMessage query, SelectorLoop loop,
                                DnsQueryCallback callback) {
            callback.onResponse(query.createResponse(
                    Collections.<DnsResourceRecord>emptyList()));
        }

        @Override
        public void start(Gumdrop gumdrop) {
            gumdropStarts++;
        }

        @Override
        public void start() {
            starts++;
        }

        @Override
        public void stop() {
            stops++;
        }
    }

    private static final class CaptureCallback implements DnsQueryCallback {
        DnsMessage response;
        String error;
        List<DnsMessage> sequence;

        @Override
        public void onResponse(DnsMessage response) {
            this.response = response;
        }

        @Override
        public void onError(String error) {
            this.error = error;
        }

        @Override
        public void onResponseSequence(List<DnsMessage> responses) {
            this.sequence = responses;
        }
    }

    private static DnsMessage query() {
        return DnsMessage.createQuery(3, "a.example.com.", DnsType.A);
    }

    @Test(expected = IllegalStateException.class)
    public void composerRequiresListener() {
        DnsServer.compose().server();
    }

    @Test
    public void composerBuildsServerWithListenersAndHandler() {
        MockListener udp = new MockListener();
        DoTListener dot = new DoTListener();
        DoQListener doq = new DoQListener();
        LifeHandler handler = new LifeHandler();
        DnsServer server = DnsServer.compose().listener(udp).listener(dot)
                .listener(doq).handler(handler).server();
        assertEquals(3, server.getListeners().size());
        try {
            server.getListeners().add(udp);
            fail();
        } catch (UnsupportedOperationException expected) {
            // unmodifiable
        }
        DnsServer noHandler = DnsServer.compose().listener(udp).server();
        assertEquals(1, noHandler.getListeners().size());
    }

    @Test
    public void setListenersAcceptsKnownTypesAndSkipsOthers() {
        DnsServer server = new DnsServer();
        List<Object> items = new ArrayList<Object>();
        items.add(new MockListener());
        items.add(new DoTListener());
        items.add(new DoQListener());
        items.add(new org.bluezoo.gumdrop.dns.DnsTcpListener());
        items.add("not a listener");
        server.setListeners(items);
        assertEquals(4, server.getListeners().size());
        server.addListener(new MockListener());
        assertEquals(5, server.getListeners().size());
    }

    @Test
    public void startWiresListenersStartsHandlerAndStopTearsDown() {
        MockListener ok = new MockListener();
        MockListener failing = new MockListener();
        failing.failStart = true;
        failing.failStop = true;
        LifeHandler handler = new LifeHandler();
        DnsServer server = new DnsServer();
        server.setHandler(handler);
        server.addListener(ok);
        server.addListener(failing);
        server.start((Gumdrop) null);
        assertSame(server, ok.getServer());
        assertSame(server, failing.getServer());
        assertEquals(1, ok.starts);
        assertEquals(1, failing.starts);
        assertEquals(1, handler.gumdropStarts);
        assertNull(server.getMetrics());
        server.stop();
        assertEquals(1, ok.stops);
        assertEquals(1, failing.stops);
        assertEquals(1, handler.stops);
        // second stop has no active handler left
        server.stop();
        assertEquals(1, handler.stops);
    }

    @Test
    public void startEnablesMetricsWhenListenerTelemetryAsksForThem() {
        MockListener plain = new MockListener();
        plain.telemetry = new TelemetryConfig();
        MockListener metered = new MockListener();
        TelemetryConfig tc = new TelemetryConfig();
        tc.metricsEnabled(true);
        metered.telemetry = tc;
        DnsServer server = new DnsServer();
        server.addListener(plain);
        server.addListener(metered);
        server.start((Gumdrop) null);
        DnsServerMetrics metrics = server.getMetrics();
        assertNotNull(metrics);
        server.stop();
    }

    @Test
    public void startWithoutHandlerUsesEmptyHandlerAndMaxMqtypesSetter() throws Exception {
        MockListener l = new MockListener();
        DnsServer server = new DnsServer();
        server.addListener(l);
        server.setMaxMQTypes(2);
        server.start((Gumdrop) null);
        CaptureCallback cb = new CaptureCallback();
        server.processQuery(query(), null, cb);
        assertNotNull(cb.response);
        assertTrue(cb.response.getAnswers().isEmpty());
        server.stop();
    }

    @Test
    public void dispatchNonQueryWithoutHandlerIsNotimp() {
        DnsServer server = new DnsServer();
        CaptureCallback cb = new CaptureCallback();
        server.dispatchNonQueryOpcode(DnsMessage.createNotify(1, "example.com."), null, cb);
        assertEquals(DnsMessage.RCODE_NOTIMP, cb.response.getRcode());
    }

    @Test
    public void rcodeNames() {
        assertEquals("NOERROR", DnsServer.rcodeToString(DnsMessage.RCODE_NOERROR));
        assertEquals("FORMERR", DnsServer.rcodeToString(DnsMessage.RCODE_FORMERR));
        assertEquals("SERVFAIL", DnsServer.rcodeToString(DnsMessage.RCODE_SERVFAIL));
        assertEquals("NXDOMAIN", DnsServer.rcodeToString(DnsMessage.RCODE_NXDOMAIN));
        assertEquals("NOTIMP", DnsServer.rcodeToString(DnsMessage.RCODE_NOTIMP));
        assertEquals("REFUSED", DnsServer.rcodeToString(DnsMessage.RCODE_REFUSED));
        assertEquals("9", DnsServer.rcodeToString(9));
    }

    @Test
    public void standardQueryClassification() {
        assertTrue(DnsServer.isStandardQuery(query()));
        assertFalse(DnsServer.isStandardQuery(DnsMessage.createNotify(1, "example.com.")));
        assertFalse(DnsServer.isStandardQuery(
                query().createResponse(Collections.<DnsResourceRecord>emptyList())));
    }

    // ---- handlers ----

    private static final class Scripted implements DnsQueryHandler {
        DnsMessage reply;
        List<DnsMessage> sequence;
        boolean error;
        boolean claimNonQuery;
        int calls;

        @Override
        public void handleQuery(DnsMessage query, SelectorLoop loop,
                                DnsQueryCallback callback) {
            calls++;
            if (error) {
                callback.onError("scripted");
            } else if (sequence != null) {
                callback.onResponseSequence(sequence);
            } else {
                callback.onResponse(reply);
            }
        }

        @Override
        public boolean handleNonQueryOpcode(DnsMessage query, SelectorLoop loop,
                                            DnsQueryCallback callback) {
            if (claimNonQuery) {
                callback.onResponse(query.createErrorResponse(DnsMessage.RCODE_REFUSED));
                return true;
            }
            return false;
        }
    }

    @Test(expected = NullPointerException.class)
    public void chainRejectsNull() {
        DnsQueryHandlers.chain(null, DnsQueryHandlers.empty());
    }

    @Test(expected = NullPointerException.class)
    public void chainRejectsNullSecond() {
        DnsQueryHandlers.chain(DnsQueryHandlers.empty(), null);
    }

    @Test
    public void chainLifecycleFansOutInReverseOnStop() {
        LifeHandler a = new LifeHandler();
        LifeHandler b = new LifeHandler();
        DnsQueryHandler chain = DnsQueryHandlers.chain(a, b);
        chain.start();
        chain.start((Gumdrop) null);
        chain.stop();
        assertEquals(1, a.starts);
        assertEquals(1, b.starts);
        assertEquals(1, a.gumdropStarts);
        assertEquals(1, b.gumdropStarts);
        assertEquals(1, a.stops);
        assertEquals(1, b.stops);
    }

    @Test
    public void chainDelegatesOnEmptyNoerrorOnly() throws Exception {
        Scripted first = new Scripted();
        Scripted second = new Scripted();
        DnsMessage q = query();
        second.reply = q.createErrorResponse(DnsMessage.RCODE_REFUSED);
        DnsQueryHandler chain = DnsQueryHandlers.chain(first, second);

        // empty NOERROR: delegated
        first.reply = q.createResponse(Collections.<DnsResourceRecord>emptyList());
        CaptureCallback cb = new CaptureCallback();
        chain.handleQuery(q, null, cb);
        assertEquals(DnsMessage.RCODE_REFUSED, cb.response.getRcode());
        assertEquals(1, second.calls);

        // non-empty answer: not delegated
        List<DnsResourceRecord> answers = new ArrayList<DnsResourceRecord>();
        answers.add(DnsResourceRecord.a("a.example.com.", 60,
                InetAddress.getLoopbackAddress()));
        first.reply = q.createResponse(answers);
        cb = new CaptureCallback();
        chain.handleQuery(q, null, cb);
        assertEquals(1, cb.response.getAnswers().size());
        assertEquals(1, second.calls);

        // error rcode: not delegated
        first.reply = q.createErrorResponse(DnsMessage.RCODE_NXDOMAIN);
        cb = new CaptureCallback();
        chain.handleQuery(q, null, cb);
        assertEquals(DnsMessage.RCODE_NXDOMAIN, cb.response.getRcode());
        assertEquals(1, second.calls);

        // null response: delegated
        first.reply = null;
        cb = new CaptureCallback();
        chain.handleQuery(q, null, cb);
        assertEquals(2, second.calls);

        // upstream error: delegated
        first.error = true;
        cb = new CaptureCallback();
        chain.handleQuery(q, null, cb);
        assertEquals(3, second.calls);
    }

    @Test
    public void chainSequenceBranches() {
        Scripted first = new Scripted();
        Scripted second = new Scripted();
        DnsMessage q = query();
        second.reply = q.createErrorResponse(DnsMessage.RCODE_REFUSED);
        DnsQueryHandler chain = DnsQueryHandlers.chain(first, second);

        first.sequence = new ArrayList<DnsMessage>();
        CaptureCallback cb = new CaptureCallback();
        chain.handleQuery(q, null, cb);
        assertEquals(1, second.calls);

        List<DnsMessage> empties = new ArrayList<DnsMessage>();
        empties.add(q.createResponse(Collections.<DnsResourceRecord>emptyList()));
        first.sequence = empties;
        cb = new CaptureCallback();
        chain.handleQuery(q, null, cb);
        assertEquals(2, second.calls);

        List<DnsMessage> real = new ArrayList<DnsMessage>();
        real.add(q.createErrorResponse(DnsMessage.RCODE_NXDOMAIN));
        first.sequence = real;
        cb = new CaptureCallback();
        chain.handleQuery(q, null, cb);
        assertSame(real, cb.sequence);
        assertEquals(2, second.calls);
    }

    @Test
    public void chainNonQueryOpcodeTriesBoth() {
        Scripted first = new Scripted();
        Scripted second = new Scripted();
        DnsQueryHandler chain = DnsQueryHandlers.chain(first, second);
        DnsMessage notify = DnsMessage.createNotify(1, "example.com.");
        CaptureCallback cb = new CaptureCallback();
        assertFalse(chain.handleNonQueryOpcode(notify, null, cb));
        second.claimNonQuery = true;
        assertTrue(chain.handleNonQueryOpcode(notify, null, cb));
        assertEquals(DnsMessage.RCODE_REFUSED, cb.response.getRcode());
        first.claimNonQuery = true;
        cb = new CaptureCallback();
        assertTrue(chain.handleNonQueryOpcode(notify, null, cb));
    }

    @Test
    public void emptyHandlerAndDefaultTransportDispatch() {
        DnsQueryHandler h = DnsQueryHandlers.empty();
        assertSame(EmptyDnsQueryHandler.INSTANCE, h);
        CaptureCallback cb = new CaptureCallback();
        h.handleQuery(query(), null,
                org.bluezoo.gumdrop.dns.DnsQueryTransport.FRAMED_TCP, cb);
        assertEquals(DnsMessage.RCODE_NOERROR, cb.response.getRcode());
        assertFalse(h.handleNonQueryOpcode(query(), null, cb));
        h.start();
        h.stop();
    }

    @Test
    public void syncHandlerFallsBackToEmptyOnNull() {
        final DnsMessage[] reply = new DnsMessage[1];
        SyncDnsQueryHandler h = new SyncDnsQueryHandler() {
            @Override
            protected DnsMessage resolveQuery(DnsMessage query) {
                return reply[0];
            }
        };
        CaptureCallback cb = new CaptureCallback();
        h.handleQuery(query(), null, cb);
        assertTrue(cb.response.getAnswers().isEmpty());
        reply[0] = query().createErrorResponse(DnsMessage.RCODE_REFUSED);
        cb = new CaptureCallback();
        h.handleQuery(query(), null, cb);
        assertEquals(DnsMessage.RCODE_REFUSED, cb.response.getRcode());
    }
}
