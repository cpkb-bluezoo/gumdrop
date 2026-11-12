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
import org.bluezoo.gumdrop.testsupport.BinaryRecordingEndpoint;

import static org.junit.Assert.*;

/**
 * State a handler keeps for the connection a query arrived on: it lasts as
 * long as the connection, is not shared with other connections, and is
 * released when the connection closes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsConnectionAttributesTest {

    private static final DnsQueryContext.Key<String> IDENTITY =
            DnsQueryContext.Key.of("identity");
    private static final DnsQueryContext.Key<Integer> COUNT =
            DnsQueryContext.Key.of("count");

    /** Keeps the context of every query it sees; answers NOERROR. */
    private static final class Recorder implements DnsQueryHandler {
        final List<DnsQueryContext> contexts = new ArrayList<DnsQueryContext>();

        @Override
        public void handleQuery(DnsMessage query, SelectorLoop loop,
                                DnsQueryCallback callback) {
            callback.onResponse(query.createResponse(
                    Collections.<DnsResourceRecord>emptyList()));
        }

        @Override
        public void handleQuery(DnsMessage query, SelectorLoop loop,
                                DnsQueryContext context, DnsQueryCallback callback) {
            contexts.add(context);
            callback.onResponse(query.createResponse(
                    Collections.<DnsResourceRecord>emptyList()));
        }
    }

    private static final class MockListener extends DnsListener {
        @Override
        public void sendTo(ByteBuffer data, InetSocketAddress destination) {
        }

        @Override
        public SelectorLoop getSelectorLoop() {
            return null;
        }

        @Override
        public TelemetryConfig getTelemetryConfig() {
            return new TelemetryConfig();
        }

        @Override
        public void start(Gumdrop gumdrop) {
        }

        @Override
        public void stop() {
        }
    }

    private Recorder handler;
    private DnsServer server;

    @Before
    public void setUp() {
        handler = new Recorder();
        server = new DnsServer();
        server.handler(handler);
    }

    private static byte[] frame(int id) {
        ByteBuffer b = DnsMessage.createQuery(id, "www.example.com.", DnsType.A).serialize();
        byte[] body = new byte[b.remaining()];
        b.get(body);
        byte[] out = new byte[body.length + 2];
        out[0] = (byte) (body.length >> 8);
        out[1] = (byte) body.length;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    private DoTProtocolHandler connect() {
        DoTProtocolHandler h = new DoTProtocolHandler(server);
        h.connected(new BinaryRecordingEndpoint());
        return h;
    }

    private void query(DoTProtocolHandler h, int id) {
        h.receive(ByteBuffer.wrap(frame(id)));
    }

    @Test
    public void whatTheFirstQuerySetsLaterQueriesOnTheConnectionSee() {
        DoTProtocolHandler h = connect();
        query(h, 1);
        assertNull(handler.contexts.get(0).getConnectionAttribute(IDENTITY));
        handler.contexts.get(0).setConnectionAttribute(IDENTITY, "alice");
        query(h, 2);
        query(h, 3);
        assertEquals("alice", handler.contexts.get(1).getConnectionAttribute(IDENTITY));
        assertEquals("alice", handler.contexts.get(2).getConnectionAttribute(IDENTITY));
    }

    @Test
    public void aNewConnectionStartsWithNothing() {
        DoTProtocolHandler first = connect();
        query(first, 1);
        handler.contexts.get(0).setConnectionAttribute(IDENTITY, "alice");
        DoTProtocolHandler second = connect();
        query(second, 2);
        assertNull(handler.contexts.get(1).getConnectionAttribute(IDENTITY));
        handler.contexts.get(1).setConnectionAttribute(IDENTITY, "bob");
        assertEquals("the first is unaffected", "alice",
                handler.contexts.get(0).getConnectionAttribute(IDENTITY));
    }

    @Test
    public void keysAreTypedAndDoNotCollideByName() {
        DnsQueryContext.Key<String> one = DnsQueryContext.Key.of("same");
        DnsQueryContext.Key<String> two = DnsQueryContext.Key.of("same");
        DoTProtocolHandler h = connect();
        query(h, 1);
        DnsQueryContext context = handler.contexts.get(0);
        context.setConnectionAttribute(one, "one");
        context.setConnectionAttribute(COUNT, Integer.valueOf(7));
        assertEquals("one", context.getConnectionAttribute(one));
        assertNull("a different key of the same name", context.getConnectionAttribute(two));
        Integer count = context.getConnectionAttribute(COUNT);
        assertEquals(7, count.intValue());
    }

    @Test
    public void settingNullRemovesTheAttribute() {
        DoTProtocolHandler h = connect();
        query(h, 1);
        DnsQueryContext context = handler.contexts.get(0);
        context.setConnectionAttribute(IDENTITY, "alice");
        context.setConnectionAttribute(IDENTITY, null);
        assertNull(context.getConnectionAttribute(IDENTITY));
    }

    @Test
    public void whenTheConnectionClosesTheAttributesAreReleased() {
        DoTProtocolHandler h = connect();
        query(h, 1);
        DnsQueryContext context = handler.contexts.get(0);
        context.setConnectionAttribute(IDENTITY, "alice");
        h.disconnected();
        assertNull("released", context.getConnectionAttribute(IDENTITY));
        // a handler that still holds the context cannot bring state back
        context.setConnectionAttribute(IDENTITY, "again");
        assertNull(context.getConnectionAttribute(IDENTITY));
    }

    @Test
    public void tcpConnectionsHaveThemToo() {
        DoTProtocolHandler h = new DoTProtocolHandler(server, "tcp");
        h.connected(new BinaryRecordingEndpoint());
        query(h, 1);
        handler.contexts.get(0).setConnectionAttribute(IDENTITY, "tcp-client");
        query(h, 2);
        assertEquals("tcp-client", handler.contexts.get(1).getConnectionAttribute(IDENTITY));
    }

    @Test
    public void aDatagramHasNoConnectionSoNothingIsKept() throws Exception {
        InetSocketAddress source = new InetSocketAddress(
                InetAddress.getByName("192.0.2.77"), 5300);
        for (int i = 0; i < 2; i++) {
            server.handleDatagram(new MockListener(),
                    DnsMessage.createQuery(4 + i, "www.example.com.", DnsType.A).serialize(),
                    source, new Runnable() {
                        @Override
                        public void run() {
                        }
                    });
        }
        DnsQueryContext first = handler.contexts.get(0);
        first.setConnectionAttribute(IDENTITY, "alice");
        assertNull(first.getConnectionAttribute(IDENTITY));
        assertNull(handler.contexts.get(1).getConnectionAttribute(IDENTITY));
    }

    @Test
    public void aContextBuiltByHandWithoutAConnectionKeepsNothing() {
        DnsQueryContext context = DnsQueryContext.of("udp", DnsQueryTransport.UDP, null, null, null);
        context.setConnectionAttribute(IDENTITY, "x");
        assertNull(context.getConnectionAttribute(IDENTITY));
    }

    @Test
    public void eachDoqStreamIsItsOwnQueryAndKeepsNothingBetweenThem() {
        BinaryRecordingEndpoint stream1 = new BinaryRecordingEndpoint();
        DoQStreamHandler first = new DoQStreamHandler(server);
        first.connected(stream1);
        first.receive(ByteBuffer.wrap(frame(1)));
        first.readFinished();
        handler.contexts.get(0).setConnectionAttribute(IDENTITY, "alice");
        DoQStreamHandler second = new DoQStreamHandler(server);
        second.connected(new BinaryRecordingEndpoint());
        second.receive(ByteBuffer.wrap(frame(2)));
        second.readFinished();
        assertNull(handler.contexts.get(1).getConnectionAttribute(IDENTITY));
    }
}
