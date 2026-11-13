/*
 * NodeAdapterTest.java
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.redis.codec.RespValue;

import static org.junit.Assert.*;

/**
 * {@code WAIT} and {@code WAITAOF} over a cluster session, and commands sent
 * to the node of a slot chosen by key. A {@code WAIT} counts the replicas
 * that have acknowledged the writes made on the connection it is sent on,
 * so it means something only on the connection that carried the write.
 *
 * <p>Map as in {@link RedisClusterSessionTest}: A (7000) serves 0-5460, B
 * (7001) 5461-10922, C (7002) 10923-16383. {@code foo} is slot 12182 (C),
 * {@code bar} 5061 (A), {@code test} 6918 (B).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterWaitTest {

    private static final String A = "127.0.0.1:7000";
    private static final String B = "127.0.0.1:7001";
    private static final String C = "127.0.0.1:7002";

    private static final class Count implements IntegerResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(long value, RedisSession s) {
            events.add("count:" + value);
        }

        @Override
        public void handleError(String error, RedisSession s) {
            events.add("error:" + error);
        }
    }

    private static final class Many implements ArrayResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(List<RespValue> values, RedisSession s) {
            StringBuilder sb = new StringBuilder("array:");
            for (RespValue v : values) {
                sb.append(v.isInteger() ? String.valueOf(v.asLong()) : v.asString()).append(',');
            }
            events.add(sb.toString());
        }

        @Override
        public void handleNull(RedisSession s) {
            events.add("null");
        }

        @Override
        public void handleError(String error, RedisSession s) {
            events.add("error:" + error);
        }
    }

    private static final class Text implements StringResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(String result, RedisSession s) {
            events.add("string:" + result);
        }

        @Override
        public void handleError(String error, RedisSession s) {
            events.add("error:" + error);
        }
    }

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

    private static SlotMap threeMasters() {
        return SlotMap.parse(arr(range(0, 5460, 7000), range(5461, 10922, 7001),
                range(10923, 16383, 7002)), "127.0.0.1");
    }

    private RedisClusterTimeoutTest.FakeConnector connector;
    private RedisClusterTimeoutTest.FakeScheduler scheduler;
    private RedisClusterSession session;

    @Before
    public void setUp() {
        connector = new RedisClusterTimeoutTest.FakeConnector();
        scheduler = new RedisClusterTimeoutTest.FakeScheduler();
        session = new RedisClusterSession(threeMasters(), connector);
        session.setScheduler(scheduler);
    }

    private List<RedisClusterTimeoutTest.Request> requestsFor(String address) {
        List<RedisClusterTimeoutTest.Request> out = new ArrayList<RedisClusterTimeoutTest.Request>();
        for (RedisClusterTimeoutTest.Request r : connector.requests) {
            if (r.node.getAddress().equals(address)) {
                out.add(r);
            }
        }
        return out;
    }

    private RedisClusterTimeoutTest.Request up(RedisClusterTimeoutTest.Request r) {
        r.endpoint = new RedisClusterTimeoutTest.TimedEndpoint();
        r.handler = new RedisClientProtocolHandler(new RedisClusterTimeoutTest.QuietReady());
        r.handler.attachForTest(r.endpoint);
        r.listener.ready(r.handler);
        return r;
    }

    private RedisClusterTimeoutTest.Request up(String address) {
        List<RedisClusterTimeoutTest.Request> all = requestsFor(address);
        return up(all.get(all.size() - 1));
    }

    private static String written(RedisClusterTimeoutTest.Request r) {
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

    private static void reply(RedisClusterTimeoutTest.Request r, String wire) {
        r.handler.receive(ByteBuffer.wrap(wire.getBytes(StandardCharsets.UTF_8)));
    }

    private static void lose(RedisClusterTimeoutTest.Request r) {
        r.handler.disconnected();
        r.listener.closed();
    }

    // ── WAIT follows the write ──

    @Test
    public void waitIsSentRightAfterTheWriteOnTheSameConnection() {
        session.set("foo", "v", new Text());
        RedisClusterTimeoutTest.Request c = up(C);
        Count wait = new Count();
        session.waitReplicas("foo", 1, 100, wait);
        assertEquals(resp("SET", "foo", "v") + resp("WAIT", "1", "100"), written(c));
        assertEquals("no other node was involved", 1, connector.requests.size());
        reply(c, "+OK\r\n");
        reply(c, ":1\r\n");
        assertEquals("[count:1]", wait.events.toString());
    }

    @Test
    public void fewerReplicasThanAskedForIsAResultNotAnError() {
        session.set("foo", "v", new Text());
        RedisClusterTimeoutTest.Request c = up(C);
        Count wait = new Count();
        session.waitReplicas("foo", 2, 100, wait);
        reply(c, "+OK\r\n");
        reply(c, ":0\r\n");
        assertEquals("[count:0]", wait.events.toString());
    }

    @Test
    public void aWaitWithNoWriteBeforeItGoesToTheOwnerOfTheSlot() {
        session.waitReplicas("bar", 1, 100, new Count());
        assertEquals(1, requestsFor(A).size());
        assertEquals(0, requestsFor(B).size() + requestsFor(C).size());
        assertEquals(resp("WAIT", "1", "100"), written(up(A)));
    }

    @Test
    public void waitsForDifferentSlotsEachReachTheirOwnPrimary() {
        session.set("foo", "1", new Text());
        session.set("bar", "2", new Text());
        session.set("test", "3", new Text());
        RedisClusterTimeoutTest.Request c = up(C);
        RedisClusterTimeoutTest.Request a = up(A);
        RedisClusterTimeoutTest.Request b = up(B);
        session.waitReplicas("foo", 1, 100, new Count());
        session.waitReplicas("bar", 1, 100, new Count());
        session.waitReplicas("test", 1, 100, new Count());
        assertEquals(resp("SET", "foo", "1") + resp("WAIT", "1", "100"), written(c));
        assertEquals(resp("SET", "bar", "2") + resp("WAIT", "1", "100"), written(a));
        assertEquals(resp("SET", "test", "3") + resp("WAIT", "1", "100"), written(b));
    }

    @Test
    public void aHashTagNamesTheSlot() {
        session.set("{foo}.a", "v", new Text());
        RedisClusterTimeoutTest.Request c = up(C);
        session.waitReplicas("{foo}.b", 1, 100, new Count());
        assertTrue(written(c), written(c).endsWith(resp("WAIT", "1", "100")));
    }

    @Test
    public void aWaitQueuedBehindAWriteForAConnectionThatIsComingUpGoesAfterIt() {
        session.set("foo", "v", new Text());
        Count wait = new Count();
        session.waitReplicas("foo", 1, 100, wait);
        RedisClusterTimeoutTest.Request c = up(C);
        assertEquals(resp("SET", "foo", "v") + resp("WAIT", "1", "100"), written(c));
        assertTrue(wait.events.isEmpty());
    }

    // ── what a WAIT must never be answered by ──

    @Test
    public void ifTheWriteIsRedirectedTheWaitIsFailedAndItsLateReplyIgnored() {
        Text set = new Text();
        session.set("foo", "v", set);
        RedisClusterTimeoutTest.Request c = up(C);
        Count wait = new Count();
        session.waitReplicas("foo", 1, 100, wait);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        assertEquals("the WAIT is failed when its write moves", 1, wait.events.size());
        assertTrue(wait.events.get(0), wait.events.get(0).startsWith("error:ERR WAIT target moved"));
        // the node that never saw the write answers its WAIT: ignored
        reply(c, ":3\r\n");
        assertEquals(1, wait.events.size());
        // the write itself went on to the new owner, without the WAIT
        RedisClusterTimeoutTest.Request b = up(B);
        assertEquals(resp("SET", "foo", "v"), written(b));
    }

    @Test
    public void aWaitAskedForAfterTheRetryGoesBehindTheRetriedWrite() {
        session.set("foo", "v", new Text());
        RedisClusterTimeoutTest.Request c = up(C);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        RedisClusterTimeoutTest.Request b = up(B);
        Count wait = new Count();
        session.waitReplicas("foo", 1, 100, wait);
        assertEquals(resp("SET", "foo", "v") + resp("WAIT", "1", "100"), written(b));
        reply(b, "+OK\r\n");
        reply(b, ":2\r\n");
        assertEquals("[count:2]", wait.events.toString());
        assertFalse(written(c), written(c).substring(resp("SET", "foo", "v").length()).contains("WAIT"));
    }

    @Test
    public void ifTheConnectionIsLostTheWaitFailsAndIsNotSentAnywhereElse() {
        session.set("foo", "v", new Text());
        RedisClusterTimeoutTest.Request c = up(C);
        Count wait = new Count();
        session.waitReplicas("foo", 1, 100, wait);
        lose(c);
        assertEquals(1, wait.events.size());
        assertTrue(wait.events.get(0), wait.events.get(0).startsWith("error:"));
        assertEquals("no other connection was asked for", 1, connector.requests.size());
    }

    @Test
    public void aWaitOnANewConnectionAfterTheOneThatCarriedTheWriteIsRefused() {
        session.set("foo", "v", new Text());
        RedisClusterTimeoutTest.Request c = up(C);
        lose(c);
        Count wait = new Count();
        session.waitReplicas("foo", 1, 100, wait);
        assertEquals(1, wait.events.size());
        assertTrue(wait.events.get(0), wait.events.get(0).startsWith("error:ERR WAIT target moved"));
        assertEquals("no connection was made for it", 1, connector.requests.size());
    }

    @Test
    public void aWaitAfterAConnectionAttemptThatFailedIsRefusedToo() {
        session.set("foo", "v", new Text());
        requestsFor(C).get(0).listener.failed(new java.io.IOException("refused"));
        Count wait = new Count();
        session.waitReplicas("foo", 1, 100, wait);
        assertEquals(1, wait.events.size());
        assertTrue(wait.events.get(0), wait.events.get(0).startsWith("error:"));
    }

    @Test
    public void aWaitIsNotMovedWhenATopologyShowsTheSlotElsewhere() {
        session.set("foo", "v", new Text());
        session.get("bar", new RedisClusterTimeoutTest.Bulk());      // a refresh is asked for by A
        Count wait = new Count();
        session.waitReplicas("foo", 1, 100, wait);                   // queued on C, pinned
        RedisClusterSession withRefresh = session;
        final List<TopologyRefresher.Callback> requests = new ArrayList<TopologyRefresher.Callback>();
        withRefresh.setRefresher(new TopologyRefresher() {
            @Override
            public void refresh(Callback callback) {
                requests.add(callback);
            }
        });
        requestsFor(A).get(0).listener.failed(new java.io.IOException("refused"));
        requests.get(0).refreshed(SlotMap.parse(arr(range(0, 5460, 7000),
                range(5461, 16383, 7001)), "127.0.0.1"));
        // the SET moved to B; the WAIT stayed where it was, and failed there
        assertEquals(1, wait.events.size());
        assertTrue(wait.events.get(0), wait.events.get(0).startsWith("error:"));
        RedisClusterTimeoutTest.Request b = up(B);
        assertFalse(written(b), written(b).contains("WAIT"));
    }

    @Test
    public void tryAgainAndClusterDownAreGivenToTheHandlerNotRetried() {
        session.set("foo", "v", new Text());
        RedisClusterTimeoutTest.Request c = up(C);
        Count first = new Count();
        session.waitReplicas("foo", 1, 100, first);
        reply(c, "+OK\r\n");
        reply(c, "-TRYAGAIN try later\r\n");
        assertEquals("[error:TRYAGAIN try later]", first.events.toString());
        Count second = new Count();
        session.waitReplicas("foo", 1, 100, second);
        reply(c, "-CLUSTERDOWN down\r\n");
        assertEquals("[error:CLUSTERDOWN down]", second.events.toString());
        assertTrue("nothing was scheduled to try again", scheduler.tasks.isEmpty());
    }

    // ── timeouts and limits ──

    @Test
    public void theClientDeadlineIsTheCommandTimeoutPlusTheWaitTimeout() {
        session.setCommandTimeoutMillis(500);
        session.set("foo", "v", new Text());
        RedisClusterTimeoutTest.Request c = up(C);
        session.waitReplicas("foo", 1, 100, new Count());
        // the SET's 500 ms is the earlier deadline; the WAIT's is 600
        assertEquals(500, c.endpoint.live().delayMs);
    }

    @Test
    public void aWaitThatMayBlockForEverHasNoClientDeadline() {
        session.setCommandTimeoutMillis(500);
        session.waitReplicas("foo", 1, 0, new Count());
        RedisClusterTimeoutTest.Request c = up(C);
        assertNull(c.endpoint.live());
    }

    @Test
    public void aWaitThatTimesOutOnTheClientFailsOnceAndItsLateReplyIsDiscarded() {
        session.setCommandTimeoutMillis(500);
        final long[] now = { 1000000000L };
        session.waitReplicas("foo", 1, 100, new Count());
        RedisClusterTimeoutTest.Request c = connector.requests.get(0);
        c.endpoint = new RedisClusterTimeoutTest.TimedEndpoint();
        c.handler = new RedisClientProtocolHandler(new RedisClusterTimeoutTest.QuietReady());
        c.handler.attachForTest(c.endpoint);
        c.handler.setTicker(new RedisClientProtocolHandler.Ticker() {
            @Override
            public long nanos() {
                return now[0];
            }
        });
        Count again = new Count();
        c.listener.ready(c.handler);
        session.waitReplicas("foo", 1, 100, again);
        assertEquals(600, c.endpoint.live().delayMs);
        now[0] += 600L * 1000000L;
        RedisClusterTimeoutTest.Timer t = c.endpoint.live();
        t.cancelled = true;
        t.task.run();
        assertEquals(1, again.events.size());
        assertTrue(again.events.get(0), again.events.get(0).startsWith("error:TIMEOUT"));
        reply(c, ":1\r\n");
        assertEquals(1, again.events.size());
    }

    @Test
    public void aWaitCountsAgainstThePendingCap() {
        session.setMaxPending(1);
        session.set("foo", "v", new Text());
        RedisClusterTimeoutTest.Request c = up(C);
        Count wait = new Count();
        session.waitReplicas("foo", 1, 100, wait);
        assertEquals(1, wait.events.size());
        assertTrue(wait.events.get(0), wait.events.get(0).startsWith("error:OVERLOAD"));
    }

    // ── transactions ──

    @Test
    public void aWaitInsideMultiFailsAtOnce() {
        session.multi(new Text());
        session.set("foo", "v", new Text());
        Count wait = new Count();
        session.waitReplicas("foo", 1, 100, wait);
        assertEquals(1, wait.events.size());
        assertTrue(wait.events.get(0), wait.events.get(0).startsWith("error:ERR WAIT"));
        // the raw command too
        Many raw = new Many();
        session.command(raw, "WAIT", "1", "100");
        assertEquals(1, raw.events.size());
        assertTrue(raw.events.get(0), raw.events.get(0).startsWith("error:ERR WAIT"));
    }

    // ── WAITAOF ──

    @Test
    public void waitAofFollowsTheWriteTheSameWay() {
        session.set("foo", "v", new Text());
        RedisClusterTimeoutTest.Request c = up(C);
        Many wait = new Many();
        session.waitAof("foo", 1, 0, 100, wait);
        assertEquals(resp("SET", "foo", "v") + resp("WAITAOF", "1", "0", "100"), written(c));
        reply(c, "+OK\r\n");
        reply(c, "*2\r\n:1\r\n:0\r\n");
        assertEquals("[array:1,0,]", wait.events.toString());
    }

    @Test
    public void waitAofIsFailedWhenItsWriteMoves() {
        session.set("foo", "v", new Text());
        RedisClusterTimeoutTest.Request c = up(C);
        Many wait = new Many();
        session.waitAof("foo", 1, 0, 100, wait);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        assertEquals(1, wait.events.size());
        assertTrue(wait.events.get(0), wait.events.get(0).startsWith("error:ERR WAIT target moved"));
    }

    // ── commands routed by a key ──

    @Test
    public void aKeylessCommandGoesToTheOwnerOfTheRoutingKey() {
        Many h = new Many();
        session.commandForKey("foo", h, "CLIENT", "ID");
        assertEquals(1, connector.requests.size());
        assertEquals(C, connector.requests.get(0).node.getAddress());
        assertEquals(resp("CLIENT", "ID"), written(up(C)));
    }

    @Test
    public void aRoutedCommandFollowsMovedLikeAnyKeyedCommand() {
        Many h = new Many();
        session.commandForKey("foo", h, "CLIENT", "ID");
        RedisClusterTimeoutTest.Request c = up(C);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        RedisClusterTimeoutTest.Request b = up(B);
        assertEquals(resp("CLIENT", "ID"), written(b));
        reply(b, ":42\r\n");
        assertEquals("[array:42,]", h.events.toString());
    }

    @Test
    public void aRoutedCommandWithKeysOfItsOwnMustAgreeWithTheRoutingKey() {
        Many ok = new Many();
        session.commandForKey("{foo}x", ok, "GET", "{foo}y");
        assertTrue("same slot: sent", ok.events.isEmpty());
        Many bad = new Many();
        session.commandForKey("foo", bad, "GET", "bar");
        assertEquals(1, bad.events.size());
        assertTrue(bad.events.get(0), bad.events.get(0).startsWith("error:CROSSSLOT"));
    }

    @Test
    public void aRoutedCommandMayTakeATimeoutAndBinaryArguments() {
        session.commandForKey("foo", Duration.ofSeconds(4), new Many(), "CLIENT", "ID".getBytes(StandardCharsets.UTF_8));
        RedisClusterTimeoutTest.Request c = up(C);
        assertEquals(4000, c.endpoint.live().delayMs);
        assertEquals(resp("CLIENT", "ID"), written(c));
    }

    @Test
    public void aRoutedWriteIsRememberedForAWaitToo() {
        session.commandForKey("foo", new Many(), "SET", "{foo}k", "v");
        RedisClusterTimeoutTest.Request c = up(C);
        session.waitReplicas("foo", 1, 100, new Count());
        assertTrue(written(c), written(c).endsWith(resp("WAIT", "1", "100")));
    }
}
