/*
 * RedisClusterClientTest.java
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

package org.bluezoo.gumdrop.redis.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link RedisClusterClient}: discovery from the seeds, then a session that
 * routes by slot, over fake node connections.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterClientTest {

    private static final class Request {
        final ClusterNode node;
        final NodeListener listener;
        RedisClientProtocolHandler handler;
        RedisClientProtocolHandlerTest.StubEndpoint endpoint;

        Request(ClusterNode node, NodeListener listener) {
            this.node = node;
            this.listener = listener;
        }
    }

    private static final class FakeConnector implements NodeConnector {
        final List<Request> requests = new ArrayList<Request>();

        @Override
        public void connect(ClusterNode node, NodeListener listener) {
            requests.add(new Request(node, listener));
        }
    }

    private static final class Ready implements RedisClusterReady {
        RedisSession session;
        Exception failure;
        int calls;

        @Override
        public void handleReady(RedisSession s) {
            session = s;
            calls++;
        }

        @Override
        public void onError(Exception cause) {
            failure = cause;
            calls++;
        }
    }

    private static final class Bulk implements BulkResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(byte[] value, RedisSession session) {
            events.add("value:" + new String(value, StandardCharsets.UTF_8));
        }

        @Override
        public void handleNull(RedisSession session) {
            events.add("null");
        }

        @Override
        public void handleError(String error, RedisSession session) {
            events.add("error:" + error);
        }
    }

    // two masters: 7000 serves 0-8191, 7001 serves 8192-16383
    private static final String TWO_MASTERS = "*2\r\n"
            + "*3\r\n:0\r\n:8191\r\n*2\r\n$9\r\n127.0.0.1\r\n:7000\r\n"
            + "*3\r\n:8192\r\n:16383\r\n*2\r\n$9\r\n127.0.0.1\r\n:7001\r\n";

    private FakeConnector connector;
    private Ready ready;
    private RedisClusterClient client;

    @Before
    public void setUp() {
        connector = new FakeConnector();
        ready = new Ready();
        client = new RedisClusterClient().seed("127.0.0.1", 7000).seed("127.0.0.1", 7001);
    }

    private Request up(int index) {
        Request r = connector.requests.get(index);
        r.endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        r.handler = new RedisClientProtocolHandler(null);
        r.handler.attachForTest(r.endpoint);
        r.listener.ready(r.handler);
        return r;
    }

    private static void reply(Request r, String wire) {
        r.handler.receive(ByteBuffer.wrap(wire.getBytes(StandardCharsets.UTF_8)));
    }

    private static String written(Request r) {
        StringBuilder sb = new StringBuilder();
        for (ByteBuffer b : r.endpoint.sentBuffers) {
            byte[] bytes = new byte[b.remaining()];
            b.duplicate().get(bytes);
            sb.append(new String(bytes, StandardCharsets.ISO_8859_1));
        }
        return sb.toString();
    }

    private void discover() {
        client.connect(connector, ready);
        reply(up(0), TWO_MASTERS);
    }

    @Test
    public void nothingIsReadyUntilTheTopologyIsLoaded() {
        client.connect(connector, ready);
        assertEquals(0, ready.calls);
        assertFalse(client.isOpen());
    }

    @Test
    public void theHandlerGetsASessionOnceTheTopologyIsLoaded() {
        discover();
        assertEquals(1, ready.calls);
        assertNotNull(ready.session);
        assertTrue(client.isOpen());
    }

    /** The request made for the node at {@code address}, after the seed's. */
    private int indexOf(String address) {
        for (int i = 1; i < connector.requests.size(); i++) {
            if (connector.requests.get(i).node.getAddress().equals(address)) {
                return i;
            }
        }
        fail("no connection requested to " + address);
        return -1;
    }

    @Test
    public void theSessionRoutesBySlot() {
        discover();
        Bulk b = new Bulk();
        ready.session.get("foo", b); // slot 12182: second master
        int index = indexOf("127.0.0.1:7001");
        Request node = up(index);
        assertEquals("*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n", written(node));
        reply(node, "$1\r\nv\r\n");
        assertEquals("value:v", b.events.get(0));
        assertEquals("no second connection to that node", 3, connector.requests.size());
    }

    @Test
    public void everyMasterIsConnectedToAtOnce() {
        discover();
        assertEquals(3, connector.requests.size());
        indexOf("127.0.0.1:7000");
        indexOf("127.0.0.1:7001");
    }

    @Test
    public void theSeedConnectionStaysOpenUntilANodeConnectionSettles() {
        client.connect(connector, ready);
        Request seed = up(0);
        reply(seed, TWO_MASTERS);
        assertTrue("kept so the loop has a connection meanwhile", seed.endpoint.isOpen());
        up(indexOf("127.0.0.1:7000"));
        assertFalse(seed.endpoint.isOpen());
    }

    @Test
    public void aNodeConnectionThatFailsAlsoReleasesTheSeedConnection() {
        client.connect(connector, ready);
        Request seed = up(0);
        reply(seed, TWO_MASTERS);
        connector.requests.get(indexOf("127.0.0.1:7000")).listener.failed(new java.io.IOException("refused"));
        assertFalse(seed.endpoint.isOpen());
    }

    @Test
    public void closeAlsoClosesTheSeedConnection() {
        client.connect(connector, ready);
        Request seed = up(0);
        reply(seed, TWO_MASTERS);
        client.close();
        assertFalse(seed.endpoint.isOpen());
    }

    @Test
    public void aSeedThatFailsIsSkipped() {
        client.connect(connector, ready);
        connector.requests.get(0).listener.failed(new java.io.IOException("refused"));
        assertEquals("127.0.0.1:7001", connector.requests.get(1).node.getAddress());
        reply(up(1), TWO_MASTERS);
        assertEquals(1, ready.calls);
        assertNotNull(ready.session);
    }

    @Test
    public void whenNoSeedAnswersTheHandlerIsToldOfTheError() {
        client.connect(connector, ready);
        connector.requests.get(0).listener.failed(new java.io.IOException("refused"));
        connector.requests.get(1).listener.failed(new java.io.IOException("refused"));
        assertEquals(1, ready.calls);
        assertNull(ready.session);
        assertNotNull(ready.failure);
        assertFalse(client.isOpen());
    }

    @Test
    public void closeClosesTheSession() {
        discover();
        ready.session.get("foo", new Bulk());
        Request node = up(indexOf("127.0.0.1:7001"));
        client.close();
        assertFalse(node.endpoint.isOpen());
        assertFalse(client.isOpen());
        Bulk b = new Bulk();
        ready.session.get("foo", b);
        assertTrue(b.events.get(0).startsWith("error:"));
    }

    @Test
    public void closeBeforeTheTopologyArrivesIsHarmless() {
        client.connect(connector, ready);
        client.close();
        assertFalse(client.isOpen());
    }

    @Test(expected = IllegalStateException.class)
    public void connectingWithoutSeedsIsRejected() {
        new RedisClusterClient().connect(connector, ready);
    }
}
