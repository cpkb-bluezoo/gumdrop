/*
 * RedisClusterSessionTest.java
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.redis.codec.RespValue;

import static org.junit.Assert.*;

/**
 * {@link RedisClusterSession} routing, queuing and redirects, driven through
 * fake node connections: no sockets, replies fed by hand.
 *
 * <p>The map has three masters: A (7000) serves slots 0-5460, B (7001)
 * 5461-10922 and C (7002) 10923-16383. {@code bar} hashes to 5061 (A),
 * {@code foo} to 12182 (C) and {@code hello} to 866 (A); {@code b} is 3300
 * (A) and {@code a} is 15495 (C).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterSessionTest {

    private static final String A = "127.0.0.1:7000";
    private static final String B = "127.0.0.1:7001";
    private static final String C = "127.0.0.1:7002";

    /** One requested connection, completed by the test. */
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

        List<Request> forAddress(String address) {
            List<Request> out = new ArrayList<Request>();
            for (Request r : requests) {
                if (r.node.getAddress().equals(address)) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    /** Records what the result handler is told, and with which session. */
    private static final class Bulk implements BulkResultHandler {
        final List<String> events = new ArrayList<String>();
        final List<RedisSession> sessions = new ArrayList<RedisSession>();

        @Override
        public void handleResult(byte[] value, RedisSession session) {
            events.add("value:" + new String(value, StandardCharsets.UTF_8));
            sessions.add(session);
        }

        @Override
        public void handleNull(RedisSession session) {
            events.add("null");
            sessions.add(session);
        }

        @Override
        public void handleError(String error, RedisSession session) {
            events.add("error:" + error);
            sessions.add(session);
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

    private static final class Count implements IntegerResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(long result, RedisSession session) {
            events.add("int:" + result);
        }

        @Override
        public void handleError(String error, RedisSession session) {
            events.add("error:" + error);
        }
    }

    private SlotMap map;
    private FakeConnector connector;
    private RedisClusterSession session;
    private final Map<String, RedisClientProtocolHandlerTest.StubEndpoint> endpoints =
            new HashMap<String, RedisClientProtocolHandlerTest.StubEndpoint>();

    private static RespValue bulk(String s) {
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
                arr(bulk("127.0.0.1"), RespValue.integer(port)));
    }

    @Before
    public void setUp() {
        map = SlotMap.parse(arr(range(0, 5460, 7000), range(5461, 10922, 7001),
                range(10923, 16383, 7002)), "127.0.0.1");
        connector = new FakeConnector();
        session = new RedisClusterSession(map, connector);
    }

    /** Completes the {@code index}th request for the address with a working connection. */
    private Request up(String address, int index) {
        Request r = connector.forAddress(address).get(index);
        r.endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        r.handler = new RedisClientProtocolHandler(null);
        r.handler.attachForTest(r.endpoint);
        endpoints.put(address + "#" + index, r.endpoint);
        r.listener.ready(r.handler);
        return r;
    }

    private Request up(String address) {
        return up(address, 0);
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

    // ── routing ──

    @Test
    public void aKeyedCommandGoesToTheNodeServingItsSlot() {
        session.get("foo", new Bulk());
        assertEquals(1, connector.requests.size());
        assertEquals(C, connector.requests.get(0).node.getAddress());
    }

    @Test
    public void theCommandWaitsForTheConnectionThenIsWritten() {
        session.get("foo", new Bulk());
        Request c = connector.requests.get(0);
        assertNull("nothing can be written yet", c.endpoint);
        up(C);
        assertEquals(resp("GET", "foo"), written(c));
    }

    @Test
    public void commandsForOneNodeShareOneConnectionInOrder() {
        session.get("foo", new Bulk());
        session.get("a", new Bulk());
        assertEquals("one connection opened", 1, connector.requests.size());
        Request c = up(C);
        session.get("foo", new Bulk());
        assertEquals(resp("GET", "foo") + resp("GET", "a") + resp("GET", "foo"), written(c));
        assertEquals("a ready connection is reused", 1, connector.requests.size());
    }

    @Test
    public void commandsForDifferentNodesUseDifferentConnections() {
        session.get("bar", new Bulk());
        session.get("foo", new Bulk());
        assertEquals(2, connector.requests.size());
        Request a = up(A);
        Request c = up(C);
        assertEquals(resp("GET", "bar"), written(a));
        assertEquals(resp("GET", "foo"), written(c));
    }

    @Test
    public void aHashTagKeepsKeysTogether() {
        session.mget(new ArrayResultHandler() {
            @Override
            public void handleResult(List<RespValue> values, RedisSession s) {
            }

            @Override
            public void handleNull(RedisSession s) {
            }

            @Override
            public void handleError(String error, RedisSession s) {
            }
        }, "{foo}.1", "{foo}.2");
        assertEquals(1, connector.requests.size());
        assertEquals(C, connector.requests.get(0).node.getAddress());
    }

    @Test
    public void binaryArgumentsAreRoutedByTheirKey() {
        session.set("bar", new byte[] { 1, 2 }, new Text());
        assertEquals(A, connector.requests.get(0).node.getAddress());
        Request a = up(A);
        assertTrue(written(a).startsWith("*3\r\n$3\r\nSET\r\n$3\r\nbar\r\n$2\r\n"));
    }

    @Test
    public void aKeylessCommandGoesToSomeNode() {
        session.ping(new Text());
        assertEquals(1, connector.requests.size());
    }

    @Test
    public void aKeylessCommandPrefersAConnectionThatIsAlreadyUp() {
        session.get("foo", new Bulk());
        Request c = up(C);
        session.ping(new Text());
        assertEquals("no new connection for a keyless command", 1, connector.requests.size());
        assertEquals(resp("GET", "foo") + resp("PING"), written(c));
    }

    @Test
    public void crossSlotKeysAreRefusedWithoutSendingAnything() {
        Count count = new Count();
        session.del(count, "foo", "bar");
        assertEquals(1, count.events.size());
        assertTrue(count.events.get(0), count.events.get(0).startsWith("error:CROSSSLOT"));
        assertTrue("no connection was opened", connector.requests.isEmpty());
    }

    @Test
    public void aSlotNobodyServesIsAClusterDownError() {
        SlotMap partial = SlotMap.parse(arr(range(0, 100, 7000)), "127.0.0.1");
        RedisClusterSession s = new RedisClusterSession(partial, connector);
        Bulk b = new Bulk();
        s.get("foo", b);
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:CLUSTERDOWN"));
        assertTrue(connector.requests.isEmpty());
    }

    @Test
    public void aCommandWithNoCallbackIsStillSent() {
        session.unsubscribe();
        session.get("foo", null);
        Request c = up(C);
        assertTrue(written(c).contains(resp("GET", "foo")));
    }

    // ── results ──

    @Test
    public void handlersAreGivenTheClusterSessionNotTheNodeConnection() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        reply(c, "$1\r\nv\r\n");
        assertEquals("value:v", b.events.get(0));
        assertSame(session, b.sessions.get(0));
    }

    @Test
    public void errorsAlsoCarryTheClusterSession() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        reply(c, "-WRONGTYPE nope\r\n");
        assertEquals("error:WRONGTYPE nope", b.events.get(0));
        assertSame(session, b.sessions.get(0));
    }

    // ── connection failure ──

    @Test
    public void aFailedConnectionFailsTheCommandsWaitingForIt() {
        Bulk first = new Bulk();
        Bulk second = new Bulk();
        session.get("foo", first);
        session.get("a", second);
        connector.requests.get(0).listener.failed(new java.io.IOException("refused"));
        assertTrue(first.events.get(0), first.events.get(0).startsWith("error:"));
        assertTrue(first.events.get(0), first.events.get(0).contains("refused"));
        assertEquals(1, second.events.size());
    }

    @Test
    public void aLaterCommandTriesTheNodeAgainOnceTheBackoffHasPassed() {
        final long[] now = { 1000000L };
        session.setClock(new RedisClusterSession.Clock() {
            @Override
            public long millis() {
                return now[0];
            }
        });
        session.get("foo", new Bulk());
        connector.requests.get(0).listener.failed(new java.io.IOException("refused"));
        now[0] += RedisClusterSession.INITIAL_BACKOFF_MS;
        session.get("foo", new Bulk());
        assertEquals(2, connector.requests.size());
        Request c = up(C, 1);
        assertEquals(resp("GET", "foo"), written(c));
    }

    @Test
    public void aClosedConnectionIsReopenedByTheNextCommand() {
        session.get("foo", new Bulk());
        Request first = up(C);
        first.listener.closed();
        session.get("foo", new Bulk());
        assertEquals(2, connector.requests.size());
    }

    // ── MOVED ──

    @Test
    public void movedSendsTheCommandToTheNewOwnerAndRemembersIt() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        assertTrue("the handler is not told of the redirect", b.events.isEmpty());

        Request nodeB = connector.forAddress(B).get(0);
        up(B);
        assertEquals(resp("GET", "foo"), written(nodeB));
        reply(nodeB, "$1\r\nv\r\n");
        assertEquals("value:v", b.events.get(0));
        assertSame(session, b.sessions.get(0));

        assertEquals(map.node("127.0.0.1", 7001), map.nodeFor(12182));
        session.get("foo", new Bulk());
        assertEquals("the next command goes straight to B", resp("GET", "foo") + resp("GET", "foo"),
                written(nodeB));
    }

    @Test
    public void movedWithABlankHostMeansTheNodeThatReplied() {
        Bulk b = new Bulk();
        session.get("foo", b);
        up(C);
        reply(connector.requests.get(0), "-MOVED 12182 :7001\r\n");
        assertEquals(B, connector.requests.get(1).node.getAddress());
    }

    @Test
    public void movedToANodeNotSeenBeforeIsConnectedTo() {
        session.get("foo", new Bulk());
        up(C);
        reply(connector.requests.get(0), "-MOVED 12182 127.0.0.1:7009\r\n");
        assertEquals("127.0.0.1:7009", connector.requests.get(1).node.getAddress());
    }

    @Test
    public void redirectsAreFollowedUpToALimitThenTheErrorIsReported() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request current = up(C);
        String[] ping = { B, C };
        for (int i = 0; i < RedisClusterSession.MAX_REDIRECTS; i++) {
            String target = ping[i % 2];
            reply(current, "-MOVED 12182 " + target + "\r\n");
            assertTrue("redirect " + i + " is followed", b.events.isEmpty());
            List<Request> reqs = connector.forAddress(target);
            current = reqs.get(reqs.size() - 1);
            if (current.endpoint == null) {
                up(target, reqs.size() - 1);
            }
        }
        reply(current, "-MOVED 12182 127.0.0.1:7001\r\n");
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:MOVED"));
    }

    // ── ASK ──

    @Test
    public void askSendsAskingThenTheCommandWithoutChangingTheMap() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        reply(c, "-ASK 12182 127.0.0.1:7001\r\n");
        assertTrue(b.events.isEmpty());

        Request nodeB = up(B);
        assertEquals(resp("ASKING") + resp("GET", "foo"), written(nodeB));
        reply(nodeB, "+OK\r\n$1\r\nv\r\n");
        assertEquals("value:v", b.events.get(0));

        assertEquals("the map still says C", map.node("127.0.0.1", 7002), map.nodeFor(12182));
        session.get("foo", new Bulk());
        assertEquals("the next command still goes to C", resp("GET", "foo") + resp("GET", "foo"),
                written(c));
    }

    @Test
    public void aFailedAskingDoesNotReachTheHandler() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        reply(c, "-ASK 12182 127.0.0.1:7001\r\n");
        Request nodeB = up(B);
        reply(nodeB, "-ERR This instance has cluster support disabled\r\n$1\r\nv\r\n");
        assertEquals(1, b.events.size());
        assertEquals("value:v", b.events.get(0));
    }

    // ── other errors pass straight through ──

    @Test
    public void otherErrorsAreNotRedirects() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        reply(c, "-CLUSTERDOWN The cluster is down\r\n");
        assertEquals("error:CLUSTERDOWN The cluster is down", b.events.get(0));
        assertEquals(1, connector.requests.size());
    }

    // ── connecting ahead of commands ──

    @Test
    public void connectAllOpensAConnectionToEveryMaster() {
        session.connectAll(null);
        assertEquals(3, connector.requests.size());
        assertEquals(1, connector.forAddress(A).size());
        assertEquals(1, connector.forAddress(B).size());
        assertEquals(1, connector.forAddress(C).size());
    }

    @Test
    public void commandsUseTheConnectionsOpenedAhead() {
        session.connectAll(null);
        Request c = up(C);
        session.get("foo", new Bulk());
        assertEquals(3, connector.requests.size());
        assertEquals(resp("GET", "foo"), written(c));
    }

    @Test
    public void aCommandBeforeTheConnectionIsUpWaitsForIt() {
        session.connectAll(null);
        session.get("foo", new Bulk());
        assertEquals("no second connection", 3, connector.requests.size());
        Request c = up(C);
        assertEquals(resp("GET", "foo"), written(c));
    }

    @Test
    public void connectAllReportsTheFirstConnectionToSettleOnce() {
        final int[] settled = new int[1];
        session.connectAll(new Runnable() {
            @Override
            public void run() {
                settled[0]++;
            }
        });
        assertEquals(0, settled[0]);
        up(B);
        assertEquals(1, settled[0]);
        up(A);
        connector.forAddress(C).get(0).listener.failed(new java.io.IOException("refused"));
        assertEquals(1, settled[0]);
    }

    @Test
    public void aFailedConnectionAlsoSettles() {
        final int[] settled = new int[1];
        session.connectAll(new Runnable() {
            @Override
            public void run() {
                settled[0]++;
            }
        });
        connector.forAddress(A).get(0).listener.failed(new java.io.IOException("refused"));
        assertEquals(1, settled[0]);
    }

    // ── closing ──

    @Test
    public void closeClosesEveryNodeConnection() {
        session.get("foo", new Bulk());
        session.get("bar", new Bulk());
        Request c = up(C);
        Request a = up(A);
        session.close();
        assertFalse(c.endpoint.isOpen());
        assertFalse(a.endpoint.isOpen());
    }

    @Test
    public void commandsAfterCloseFailImmediately() {
        session.close();
        Bulk b = new Bulk();
        session.get("foo", b);
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0).startsWith("error:"));
        assertTrue(connector.requests.isEmpty());
    }

    @Test
    public void quitClosesTheSession() {
        session.get("foo", new Bulk());
        Request c = up(C);
        session.quit();
        assertFalse(c.endpoint.isOpen());
    }
}
