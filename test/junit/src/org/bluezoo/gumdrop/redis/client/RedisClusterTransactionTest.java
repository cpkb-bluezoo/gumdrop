/*
 * RedisClusterTransactionTest.java
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

import org.bluezoo.gumdrop.redis.codec.RespValue;

import static org.junit.Assert.*;

/**
 * {@code MULTI}/{@code EXEC}/{@code WATCH} on a {@link RedisClusterSession}:
 * a transaction (and a watch) lives on one connection, so everything in it
 * is pinned to one node, and to one slot.
 *
 * <p>Map as in {@link RedisClusterSessionTest}: A (7000) serves 0-5460, B
 * (7001) 5461-10922, C (7002) 10923-16383. {@code {foo}.x} hashes to 12182
 * (C), {@code bar} to 5061 (A) and {@code b} to 3300 (A).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterTransactionTest {

    private static final String A = "127.0.0.1:7000";
    private static final String C = "127.0.0.1:7002";

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

        Request forAddress(String address) {
            for (Request r : requests) {
                if (r.node.getAddress().equals(address)) {
                    return r;
                }
            }
            return null;
        }
    }

    private static final class Text implements StringResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(String result, RedisSession session) {
            events.add("string:" + result);
        }

        @Override
        public void handleError(String error, RedisSession session) {
            events.add("error:" + error);
        }
    }

    private static final class Many implements ArrayResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(List<RespValue> values, RedisSession session) {
            events.add("array:" + values.size());
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

    private FakeConnector connector;
    private RedisClusterSession session;

    private static RespValue bulkValue(String s) {
        return RespValue.bulkString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static RespValue arr(RespValue... items) {
        List<RespValue> list = new ArrayList<RespValue>();
        for (RespValue v : items) {
            list.add(v);
        }
        return RespValue.array(list);
    }

    private static RespValue range(int from, int to, int port) {
        return arr(RespValue.integer(from), RespValue.integer(to),
                arr(bulkValue("127.0.0.1"), RespValue.integer(port)));
    }

    @Before
    public void setUp() {
        SlotMap map = SlotMap.parse(arr(range(0, 5460, 7000), range(5461, 10922, 7001),
                range(10923, 16383, 7002)), "127.0.0.1");
        connector = new FakeConnector();
        session = new RedisClusterSession(map, connector);
    }

    private Request up(String address) {
        Request r = connector.forAddress(address);
        r.endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        r.handler = new RedisClientProtocolHandler(null);
        r.handler.attachForTest(r.endpoint);
        r.listener.ready(r.handler);
        return r;
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

    private static String resp(String... parts) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(parts.length).append("\r\n");
        for (String p : parts) {
            sb.append('$').append(p.length()).append("\r\n").append(p).append("\r\n");
        }
        return sb.toString();
    }

    private static void reply(Request r, String wire) {
        r.handler.receive(ByteBuffer.wrap(wire.getBytes(StandardCharsets.UTF_8)));
    }

    // ── MULTI/EXEC ──

    @Test
    public void multiAnswersAtOnceAndOpensNoConnection() {
        Text t = new Text();
        session.multi(t);
        assertEquals("string:OK", t.events.get(0));
        assertTrue(connector.requests.isEmpty());
    }

    @Test
    public void theTransactionIsSentToTheNodeOfItsFirstKey() {
        session.multi(new Text());
        session.set("{foo}.a", "1", new Text());
        assertEquals(1, connector.requests.size());
        Request c = up(C);
        session.get("{foo}.b", new Bulk());
        session.exec(new Many());
        assertEquals(resp("MULTI") + resp("SET", "{foo}.a", "1") + resp("GET", "{foo}.b")
                + resp("EXEC"), written(c));
        assertEquals("no other node is used", 1, connector.requests.size());
    }

    @Test
    public void repliesReachTheirHandlersInOrder() {
        session.multi(new Text());
        Text set = new Text();
        Bulk get = new Bulk();
        Many exec = new Many();
        session.set("{foo}.a", "1", set);
        Request c = up(C);
        session.get("{foo}.a", get);
        session.exec(exec);
        reply(c, "+OK\r\n+QUEUED\r\n+QUEUED\r\n*2\r\n+OK\r\n$1\r\n1\r\n");
        assertEquals("string:QUEUED", set.events.get(0));
        assertEquals("value:QUEUED", get.events.get(0));
        assertEquals("array:2", exec.events.get(0));
    }

    @Test
    public void aKeyInAnotherSlotIsRefusedAndTheTransactionGoesOn() {
        session.multi(new Text());
        session.set("{foo}.a", "1", new Text());
        Request c = up(C);
        Text bad = new Text();
        session.set("bar", "2", bad);
        assertEquals(1, bad.events.size());
        assertTrue(bad.events.get(0), bad.events.get(0).startsWith("error:CROSSSLOT"));
        session.get("{foo}.a", new Bulk());
        assertEquals(resp("MULTI") + resp("SET", "{foo}.a", "1") + resp("GET", "{foo}.a"),
                written(c));
    }

    @Test
    public void aKeyOnTheSameNodeButAnotherSlotIsAlsoRefused() {
        session.multi(new Text());
        session.set("bar", "1", new Text());
        up(A);
        Text bad = new Text();
        session.set("b", "2", bad); // slot 3300, also node A
        assertTrue(bad.events.get(0), bad.events.get(0).startsWith("error:CROSSSLOT"));
    }

    @Test
    public void aKeylessCommandBeforeAnyKeyPicksANodeThatTheKeysMustMatch() {
        session.multi(new Text());
        session.ping(new Text());
        assertEquals(1, connector.requests.size());
        ClusterNode chosen = connector.requests.get(0).node;
        Text bad = new Text();
        // a key served by some other node is refused
        String other = chosen.getPort() == 7002 ? "bar" : "{foo}.a";
        session.set(other, "x", bad);
        assertTrue(bad.events.get(0), bad.events.get(0).startsWith("error:CROSSSLOT"));
    }

    @Test
    public void commandsInATransactionAreNotRedirected() {
        session.multi(new Text());
        Text set = new Text();
        session.set("{foo}.a", "1", set);
        Request c = up(C);
        reply(c, "+OK\r\n+QUEUED\r\n"); // MULTI, then the first SET
        session.set("{foo}.b", "2", set);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        assertTrue(set.events.toString(), set.events.get(1).startsWith("error:MOVED"));
        assertEquals("nothing was sent to B", 1, connector.requests.size());
    }

    @Test
    public void execWithNothingQueuedAnswersAtOnceWithAnEmptyArray() {
        session.multi(new Text());
        Many exec = new Many();
        session.exec(exec);
        assertEquals("array:0", exec.events.get(0));
        assertTrue(connector.requests.isEmpty());
    }

    @Test
    public void discardWithNothingQueuedAnswersOk() {
        session.multi(new Text());
        Text d = new Text();
        session.discard(d);
        assertEquals("string:OK", d.events.get(0));
        assertTrue(connector.requests.isEmpty());
    }

    @Test
    public void discardIsSentToTheTransactionsNode() {
        session.multi(new Text());
        session.set("{foo}.a", "1", new Text());
        Request c = up(C);
        session.discard(new Text());
        assertEquals(resp("MULTI") + resp("SET", "{foo}.a", "1") + resp("DISCARD"), written(c));
    }

    @Test
    public void afterExecCommandsAreRoutedAgain() {
        session.multi(new Text());
        session.set("{foo}.a", "1", new Text());
        Request c = up(C);
        session.exec(new Many());
        session.get("bar", new Bulk());
        assertEquals("the next command is routed by its own key", 2, connector.requests.size());
        assertEquals(A, connector.requests.get(1).node.getAddress());
        up(A);
        // and a fresh transaction can pick a different node
        session.multi(new Text());
        session.set("bar", "1", new Text());
        assertEquals(resp("MULTI") + resp("SET", "{foo}.a", "1") + resp("EXEC"), written(c));
    }

    @Test
    public void execWithoutMultiIsAnError() {
        Many exec = new Many();
        session.exec(exec);
        assertTrue(exec.events.get(0), exec.events.get(0).startsWith("error:ERR EXEC without MULTI"));
        assertTrue(connector.requests.isEmpty());
    }

    @Test
    public void discardWithoutMultiIsAnError() {
        Text d = new Text();
        session.discard(d);
        assertTrue(d.events.get(0), d.events.get(0).startsWith("error:ERR DISCARD without MULTI"));
    }

    @Test
    public void multiCannotBeNested() {
        session.multi(new Text());
        Text second = new Text();
        session.multi(second);
        assertTrue(second.events.get(0), second.events.get(0).startsWith("error:ERR MULTI calls can not be nested"));
    }

    // ── WATCH ──

    @Test
    public void watchPinsTheTransactionToTheWatchedNode() {
        session.watch(new Text(), "{foo}.a");
        Request c = up(C);
        session.multi(new Text());
        session.set("{foo}.a", "1", new Text());
        session.exec(new Many());
        assertEquals(resp("WATCH", "{foo}.a") + resp("MULTI") + resp("SET", "{foo}.a", "1")
                + resp("EXEC"), written(c));
        assertEquals(1, connector.requests.size());
    }

    @Test
    public void aTransactionOnAnotherNodeThanTheWatchIsRefused() {
        session.watch(new Text(), "{foo}.a");
        up(C);
        session.multi(new Text());
        Text bad = new Text();
        session.set("bar", "1", bad);
        assertTrue(bad.events.get(0), bad.events.get(0).startsWith("error:CROSSSLOT"));
    }

    @Test
    public void watchingKeysOnTwoNodesIsRefused() {
        session.watch(new Text(), "{foo}.a");
        up(C);
        Text bad = new Text();
        session.watch(bad, "bar");
        assertTrue(bad.events.get(0), bad.events.get(0).startsWith("error:CROSSSLOT"));
    }

    @Test
    public void watchingMoreKeysOnTheSameNodeIsFine() {
        session.watch(new Text(), "{foo}.a");
        Request c = up(C);
        Text more = new Text();
        session.watch(more, "{foo}.b");
        assertTrue(more.events.isEmpty());
        assertEquals(resp("WATCH", "{foo}.a") + resp("WATCH", "{foo}.b"), written(c));
    }

    @Test
    public void watchInsideMultiIsAnError() {
        session.multi(new Text());
        Text w = new Text();
        session.watch(w, "{foo}.a");
        assertTrue(w.events.get(0), w.events.get(0).startsWith("error:ERR WATCH inside MULTI is not allowed"));
    }

    @Test
    public void unwatchGoesToTheWatchedNodeAndClearsTheWatch() {
        session.watch(new Text(), "{foo}.a");
        Request c = up(C);
        session.unwatch(new Text());
        assertEquals(resp("WATCH", "{foo}.a") + resp("UNWATCH"), written(c));
        // the watch is gone, so another node can now be watched
        Text w = new Text();
        session.watch(w, "bar");
        assertTrue(w.events.isEmpty());
        assertEquals(A, connector.requests.get(1).node.getAddress());
    }

    @Test
    public void unwatchWithNothingWatchedAnswersOk() {
        Text u = new Text();
        session.unwatch(u);
        assertEquals("string:OK", u.events.get(0));
        assertTrue(connector.requests.isEmpty());
    }

    @Test
    public void execClearsTheWatch() {
        session.watch(new Text(), "{foo}.a");
        up(C);
        session.multi(new Text());
        session.set("{foo}.a", "1", new Text());
        session.exec(new Many());
        Text w = new Text();
        session.watch(w, "bar");
        assertTrue("watching another node is fine after EXEC", w.events.isEmpty());
    }

    @Test
    public void aWatchIsNotRedirected() {
        Text w = new Text();
        session.watch(w, "{foo}.a");
        Request c = up(C);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        assertTrue(w.events.get(0), w.events.get(0).startsWith("error:MOVED"));
        assertEquals(1, connector.requests.size());
    }
}
