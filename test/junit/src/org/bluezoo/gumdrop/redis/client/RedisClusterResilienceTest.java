/*
 * RedisClusterResilienceTest.java
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
 * {@link RedisClusterSession} when things go wrong: connections lost with
 * commands in flight, reconnect backoff, topology refresh, and retries of
 * {@code TRYAGAIN} and {@code CLUSTERDOWN}. A fake clock, scheduler and
 * refresher make all of it deterministic.
 *
 * <p>Map as in {@link RedisClusterSessionTest}: A (7000) serves 0-5460, B
 * (7001) 5461-10922, C (7002) 10923-16383; {@code foo} is slot 12182 (C).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterResilienceTest {

    private static final String A = "127.0.0.1:7000";
    private static final String B = "127.0.0.1:7001";
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

    /** A connection handler that ignores lifecycle events. */
    private static final class QuietReady implements RedisConnectionReady {
        @Override
        public void handleReady(RedisSession session) {
        }

        @Override
        public void onConnected(org.bluezoo.gumdrop.Endpoint endpoint) {
        }

        @Override
        public void onSecurityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
        }

        @Override
        public void onError(Exception cause) {
        }

        @Override
        public void onDisconnected() {
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

    private static final class FakeClock implements RedisClusterSession.Clock {
        long now = 1000000L;

        @Override
        public long millis() {
            return now;
        }
    }

    private static final class Task {
        final long delay;
        final Runnable task;

        Task(long delay, Runnable task) {
            this.delay = delay;
            this.task = task;
        }
    }

    private static final class FakeScheduler implements RedisClusterSession.Scheduler {
        final List<Task> tasks = new ArrayList<Task>();

        @Override
        public void schedule(long delayMs, Runnable task) {
            tasks.add(new Task(delayMs, task));
        }

        void runLast() {
            tasks.remove(tasks.size() - 1).task.run();
        }
    }

    private static final class FakeRefresher implements TopologyRefresher {
        final List<Callback> requests = new ArrayList<Callback>();

        @Override
        public void refresh(Callback callback) {
            requests.add(callback);
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

    private FakeConnector connector;
    private FakeClock clock;
    private FakeScheduler scheduler;
    private FakeRefresher refresher;
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

    private static SlotMap threeMasters() {
        return SlotMap.parse(arr(range(0, 5460, 7000), range(5461, 10922, 7001),
                range(10923, 16383, 7002)), "127.0.0.1");
    }

    @Before
    public void setUp() {
        connector = new FakeConnector();
        clock = new FakeClock();
        scheduler = new FakeScheduler();
        refresher = new FakeRefresher();
        session = new RedisClusterSession(threeMasters(), connector);
        session.setClock(clock);
        session.setScheduler(scheduler);
        session.setRefresher(refresher);
    }

    private Request up(String address, int index) {
        Request r = connector.forAddress(address).get(index);
        r.endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        r.handler = new RedisClientProtocolHandler(new QuietReady());
        r.handler.attachForTest(r.endpoint);
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

    private void failConnect(String address, int index) {
        connector.forAddress(address).get(index).listener.failed(new java.io.IOException("refused"));
    }

    // ── commands in flight when a connection goes ──

    @Test
    public void commandsInFlightFailWhenTheConnectionDrops() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        c.handler.disconnected();
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:"));
        assertTrue(b.events.get(0), b.events.get(0).contains(C));
    }

    @Test
    public void commandsAlreadyAnsweredAreNotFailedByTheDrop() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        reply(c, "$1\r\nv\r\n");
        c.handler.disconnected();
        assertEquals(1, b.events.size());
        assertEquals("value:v", b.events.get(0));
    }

    @Test
    public void everyCommandInFlightIsFailedOnce() {
        Bulk first = new Bulk();
        Bulk second = new Bulk();
        session.get("foo", first);
        session.get("a", second);
        Request c = up(C);
        c.handler.disconnected();
        c.listener.closed();
        assertEquals(1, first.events.size());
        assertEquals(1, second.events.size());
    }

    @Test
    public void closingTheSessionFailsCommandsInFlight() {
        Bulk b = new Bulk();
        session.get("foo", b);
        up(C);
        session.close();
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:"));
    }

    @Test
    public void aDropWithCommandsInFlightRequestsARefresh() {
        session.get("foo", new Bulk());
        Request c = up(C);
        c.handler.disconnected();
        assertEquals(1, refresher.requests.size());
    }

    @Test
    public void anIdleCloseNeedsNeitherRefreshNorBackoff() {
        session.get("foo", new Bulk());
        Request c = up(C);
        reply(c, "$1\r\nv\r\n");
        c.handler.disconnected();
        c.listener.closed();
        assertTrue(refresher.requests.isEmpty());
        session.get("foo", new Bulk());
        assertEquals("reconnects at once", 2, connector.forAddress(C).size());
    }

    // ── backoff ──

    @Test
    public void aFailedConnectionIsNotRetriedAtOnce() {
        session.get("foo", new Bulk());
        failConnect(C, 0);
        Bulk b = new Bulk();
        session.get("foo", b);
        assertEquals("no new attempt inside the backoff", 1, connector.forAddress(C).size());
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:"));
        assertTrue(b.events.get(0), b.events.get(0).contains("refused"));
    }

    @Test
    public void theNodeIsTriedAgainOnceTheBackoffHasPassed() {
        session.get("foo", new Bulk());
        failConnect(C, 0);
        clock.now += 999;
        session.get("foo", new Bulk());
        assertEquals(1, connector.forAddress(C).size());
        clock.now += 1;
        session.get("foo", new Bulk());
        assertEquals(2, connector.forAddress(C).size());
    }

    @Test
    public void theBackoffDoublesWithEachFailure() {
        session.get("foo", new Bulk());
        failConnect(C, 0);
        clock.now += 1000;
        session.get("foo", new Bulk());
        failConnect(C, 1);
        clock.now += 1999;
        session.get("foo", new Bulk());
        assertEquals("2s backoff after the second failure", 2, connector.forAddress(C).size());
        clock.now += 1;
        session.get("foo", new Bulk());
        assertEquals(3, connector.forAddress(C).size());
    }

    @Test
    public void theBackoffIsCapped() {
        session.get("foo", new Bulk());
        int attempt = 0;
        for (int i = 0; i < 8; i++) {
            failConnect(C, attempt);
            clock.now += 30000; // always enough to try again
            session.get("foo", new Bulk());
            attempt++;
        }
        failConnect(C, attempt);
        clock.now += 29999;
        int before = connector.forAddress(C).size();
        session.get("foo", new Bulk());
        assertEquals("still backing off just under the cap", before, connector.forAddress(C).size());
        clock.now += 1;
        session.get("foo", new Bulk());
        assertEquals(before + 1, connector.forAddress(C).size());
    }

    @Test
    public void aSuccessfulConnectionResetsTheBackoff() {
        session.get("foo", new Bulk());
        failConnect(C, 0);
        clock.now += 1000;
        session.get("foo", new Bulk());
        Request c = up(C, 1);
        c.listener.closed();
        session.get("foo", new Bulk());
        assertEquals("no backoff after a success", 3, connector.forAddress(C).size());
    }

    @Test
    public void aConnectionDroppedWithCommandsUnansweredIsBackedOffFrom() {
        // a proxy in front of a dead node accepts connections, then drops them
        session.get("foo", new Bulk());
        Request c = up(C);
        c.handler.disconnected();
        c.listener.closed();
        Bulk b = new Bulk();
        session.get("foo", b);
        assertEquals("no new connection inside the backoff", 1, connector.forAddress(C).size());
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:"));
        clock.now += RedisClusterSession.INITIAL_BACKOFF_MS;
        session.get("foo", new Bulk());
        assertEquals(2, connector.forAddress(C).size());
    }

    @Test
    public void aFailedConnectionRequestsARefresh() {
        session.get("foo", new Bulk());
        failConnect(C, 0);
        assertEquals(1, refresher.requests.size());
    }

    @Test
    public void commandsForOtherNodesAreUnaffectedByOneNodesBackoff() {
        session.get("foo", new Bulk());
        failConnect(C, 0);
        session.get("bar", new Bulk());
        assertEquals(1, connector.forAddress(A).size());
    }

    // ── topology refresh ──

    @Test
    public void movedRequestsARefresh() {
        session.get("foo", new Bulk());
        Request c = up(C);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        assertEquals(1, refresher.requests.size());
    }

    @Test
    public void onlyOneRefreshRunsAtATime() {
        session.get("foo", new Bulk());
        Request c = up(C);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        session.get("a", new Bulk());
        reply(c, "-MOVED 15495 127.0.0.1:7001\r\n");
        assertEquals(1, refresher.requests.size());
    }

    @Test
    public void refreshesAreThrottled() {
        session.get("foo", new Bulk());
        Request c = up(C);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        refresher.requests.get(0).failed(new java.io.IOException("no seed"));
        session.get("a", new Bulk());
        reply(c, "-MOVED 15495 127.0.0.1:7001\r\n");
        assertEquals("too soon after the last", 1, refresher.requests.size());
        clock.now += 1000;
        session.get("somekey", new Bulk()); // slot 11058, still served by C
        reply(c, "-MOVED 11058 127.0.0.1:7001\r\n");
        assertEquals(2, refresher.requests.size());
    }

    @Test
    public void aFreshTopologyReroutesLaterCommands() {
        session.get("foo", new Bulk());
        Request c = up(C);
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n"); // starts a refresh
        // the refreshed map has everything on B
        SlotMap fresh = SlotMap.parse(arr(range(0, 16383, 7001)), "127.0.0.1");
        refresher.requests.get(0).refreshed(fresh);
        session.get("bar", new Bulk()); // slot 5061 used to be A
        assertEquals(1, connector.forAddress(B).size());
        assertEquals("never asked A", 0, connector.forAddress(A).size());
    }

    @Test
    public void connectionsToNodesThatLeftTheClusterAreClosed() {
        session.get("foo", new Bulk());
        session.get("bar", new Bulk());
        Request c = up(C);
        Request a = up(A);
        reply(c, "$1\r\nv\r\n");
        reply(a, "$1\r\nv\r\n");
        session.get("foo", new Bulk());
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        refresher.requests.get(0).refreshed(SlotMap.parse(arr(range(0, 16383, 7001)), "127.0.0.1"));
        assertFalse(c.endpoint.isOpen());
        assertFalse(a.endpoint.isOpen());
    }

    @Test
    public void connectionsToNodesThatRemainAreKept() {
        session.get("bar", new Bulk());
        Request a = up(A);
        reply(a, "$1\r\nv\r\n");
        session.get("foo", new Bulk());
        failConnect(C, 0); // starts a refresh
        refresher.requests.get(0).refreshed(threeMasters());
        assertTrue(a.endpoint.isOpen());
        session.get("bar", new Bulk());
        assertEquals("the same connection is used", 1, connector.forAddress(A).size());
    }

    @Test
    public void aFailedRefreshKeepsTheOldTopology() {
        session.get("foo", new Bulk());
        failConnect(C, 0);
        refresher.requests.get(0).failed(new java.io.IOException("no seed"));
        clock.now += 1000;
        session.get("bar", new Bulk());
        assertEquals(1, connector.forAddress(A).size());
    }

    @Test
    public void theTransactionIsAbandonedIfItsNodeLeavesTheCluster() {
        session.multi(new Text());
        session.set("{foo}.a", "1", new Text());
        Request c = up(C);
        session.get("{foo}.a", new Bulk());
        // replies to MULTI, SET, GET: the last is MOVED; a pinned command is not
        // followed, but the stale map still prompts a refresh
        reply(c, "+OK\r\n+QUEUED\r\n-MOVED 12182 127.0.0.1:7001\r\n");
        refresher.requests.get(0).refreshed(SlotMap.parse(arr(range(0, 16383, 7001)), "127.0.0.1"));
        Many exec = new Many();
        session.exec(exec);
        assertTrue(exec.events.get(0), exec.events.get(0).startsWith("error:ERR EXEC without MULTI"));
    }

    // ── a transaction on a lost connection ──

    @Test
    public void aTransactionIsAbandonedWhenItsConnectionCloses() {
        session.multi(new Text());
        session.set("{foo}.a", "1", new Text());
        Request c = up(C);
        c.handler.disconnected();
        c.listener.closed();
        Many exec = new Many();
        session.exec(exec);
        assertTrue(exec.events.get(0), exec.events.get(0).startsWith("error:ERR EXEC without MULTI"));
    }

    @Test
    public void aWatchIsForgottenWhenItsConnectionCloses() {
        session.watch(new Text(), "{foo}.a");
        Request c = up(C);
        c.handler.disconnected();
        c.listener.closed();
        Text w = new Text();
        session.watch(w, "bar");
        assertTrue("another node can now be watched", w.events.isEmpty());
    }

    // ── TRYAGAIN and CLUSTERDOWN ──

    @Test
    public void tryAgainIsRetriedAfterAShortDelay() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        reply(c, "-TRYAGAIN Multiple keys request during rehashing of slot\r\n");
        assertTrue("the handler is not told yet", b.events.isEmpty());
        assertEquals(1, scheduler.tasks.size());
        assertEquals(100, scheduler.tasks.get(0).delay);
        scheduler.runLast();
        assertEquals(resp("GET", "foo") + resp("GET", "foo"), written(c));
        reply(c, "$1\r\nv\r\n");
        assertEquals("value:v", b.events.get(0));
    }

    @Test
    public void theDelayBetweenRetriesGrows() {
        session.get("foo", new Bulk());
        Request c = up(C);
        reply(c, "-TRYAGAIN x\r\n");
        scheduler.runLast();
        reply(c, "-TRYAGAIN x\r\n");
        assertEquals(200, scheduler.tasks.get(0).delay);
    }

    @Test
    public void retriesStopAtALimitAndTheErrorIsDelivered() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        for (int i = 0; i < RedisClusterSession.MAX_RETRIES; i++) {
            reply(c, "-TRYAGAIN x\r\n");
            assertTrue("retry " + i, b.events.isEmpty());
            scheduler.runLast();
        }
        reply(c, "-TRYAGAIN x\r\n");
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:TRYAGAIN"));
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    public void clusterDownIsRetriedAndRequestsARefresh() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        reply(c, "-CLUSTERDOWN The cluster is down\r\n");
        assertTrue(b.events.isEmpty());
        assertEquals(1, scheduler.tasks.size());
        assertEquals(1, refresher.requests.size());
    }

    @Test
    public void aRetryUsesTheTopologyAsItThenStands() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        reply(c, "-CLUSTERDOWN The cluster is down\r\n");
        refresher.requests.get(0).refreshed(SlotMap.parse(arr(range(0, 16383, 7001)), "127.0.0.1"));
        scheduler.runLast();
        assertEquals("the retry went to B", 1, connector.forAddress(B).size());
    }

    @Test
    public void commandsInATransactionAreNotRetried() {
        session.multi(new Text());
        Text t = new Text();
        session.set("{foo}.a", "1", t);
        Request c = up(C);
        reply(c, "+OK\r\n-TRYAGAIN x\r\n");
        assertEquals(1, t.events.size());
        assertTrue(t.events.get(0), t.events.get(0).startsWith("error:TRYAGAIN"));
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    public void withoutASchedulerTheErrorIsDeliveredAtOnce() {
        RedisClusterSession plain = new RedisClusterSession(threeMasters(), connector);
        Bulk b = new Bulk();
        plain.get("foo", b);
        Request c = connector.requests.get(0);
        c.endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        c.handler = new RedisClientProtocolHandler(new QuietReady());
        c.handler.attachForTest(c.endpoint);
        c.listener.ready(c.handler);
        reply(c, "-TRYAGAIN x\r\n");
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:TRYAGAIN"));
    }

    @Test
    public void aRetryAfterTheSessionIsClosedDoesNothing() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        reply(c, "-TRYAGAIN x\r\n");
        session.close();
        int before = c.endpoint.sentBuffers.size();
        scheduler.runLast();
        assertEquals(before, c.endpoint.sentBuffers.size());
        assertEquals(1, connector.requests.size());
    }

    @Test
    public void otherErrorsAreStillDeliveredAtOnce() {
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(C);
        reply(c, "-WRONGTYPE nope\r\n");
        assertEquals("error:WRONGTYPE nope", b.events.get(0));
        assertTrue(scheduler.tasks.isEmpty());
        assertTrue(refresher.requests.isEmpty());
    }

    // ── slow failovers: refresh while backing off, move what is waiting ──

    /** Fails C twice, so that it is backing off for two seconds, and settles the refreshes. */
    private void backOffC() {
        session.get("foo", new Bulk());
        failConnect(C, 0);
        refresher.requests.get(refresher.requests.size() - 1)
                .failed(new java.io.IOException("not yet"));
        clock.now += RedisClusterSession.INITIAL_BACKOFF_MS;
        session.get("foo", new Bulk());
        failConnect(C, 1);
        refresher.requests.get(refresher.requests.size() - 1)
                .failed(new java.io.IOException("not yet"));
    }

    @Test
    public void aCommandRefusedWhileTheNodeIsBackingOffAsksForATopology() {
        backOffC();
        int before = refresher.requests.size();
        clock.now += RedisClusterSession.REFRESH_INTERVAL_MS + 100;
        Bulk b = new Bulk();
        session.get("foo", b);
        assertEquals("refused at once, no new attempt", 2, connector.forAddress(C).size());
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:"));
        assertEquals("and a refresh was asked for", before + 1, refresher.requests.size());
    }

    @Test
    public void refusedCommandsDoNotRefreshMoreOftenThanTheThrottleAllows() {
        backOffC();
        clock.now += RedisClusterSession.REFRESH_INTERVAL_MS + 100;
        int before = refresher.requests.size();
        for (int i = 0; i < 20; i++) {
            session.get("foo", new Bulk());
        }
        assertEquals("one refresh for the lot", before + 1, refresher.requests.size());
        // that refresh finishes, but another so soon would break the throttle
        refresher.requests.get(refresher.requests.size() - 1)
                .failed(new java.io.IOException("not yet"));
        session.get("foo", new Bulk());
        assertEquals("not within a second of the last", before + 1, refresher.requests.size());
    }

    @Test
    public void aCommandWaitingForANodeThatLeavesTheClusterGoesToTheNewOwner() {
        Bulk waiting = new Bulk();
        session.get("foo", waiting);                       // waits for C to connect
        session.get("bar", new Bulk());
        failConnect(A, 0);                                 // asks for a topology
        // C is gone: B now serves everything from 5461 on, foo's slot included
        refresher.requests.get(0).refreshed(SlotMap.parse(arr(range(0, 5460, 7000),
                range(5461, 16383, 7001)), "127.0.0.1"));
        assertEquals("B is asked for a connection", 1, connector.forAddress(B).size());
        Request b = up(B);
        assertTrue("the GET for foo went to B: " + written(b),
                written(b).contains(resp("GET", "foo")));
        assertTrue("it was not failed: " + waiting.events, waiting.events.isEmpty());
    }

    @Test
    public void aCommandWaitingForASlotThatMovedToAnotherLiveNodeGoesThere() {
        Bulk waiting = new Bulk();
        session.get("foo", waiting);                       // waits for C to connect
        session.get("bar", new Bulk());
        failConnect(A, 0);
        // foo's slot, 12182, now belongs to B; C still serves the rest
        refresher.requests.get(0).refreshed(SlotMap.parse(arr(range(0, 5460, 7000),
                range(5461, 12182, 7001), range(12183, 16383, 7002)), "127.0.0.1"));
        assertEquals("B is asked for a connection", 1, connector.forAddress(B).size());
        Request b = up(B);
        assertTrue("the GET for foo went to B: " + written(b),
                written(b).contains(resp("GET", "foo")));
        // and C's connection, when it comes, has nothing for foo
        Request c = up(C);
        assertFalse(written(c), written(c).contains(resp("GET", "foo")));
        assertTrue(waiting.events.toString(), waiting.events.isEmpty());
    }

    // ── a cap on the reconnect backoff ──

    @Test
    public void theBackoffCanBeCapped() {
        session.setMaxBackoffMillis(2000);
        session.get("foo", new Bulk());
        failConnect(C, 0);                                       // 1 s
        clock.now += 1000;
        session.get("foo", new Bulk());
        failConnect(C, 1);                                       // 2 s
        clock.now += 2000;
        session.get("foo", new Bulk());
        failConnect(C, 2);                                       // would be 4 s: held at 2 s
        assertEquals(3, connector.forAddress(C).size());
        clock.now += 2000;
        session.get("foo", new Bulk());
        assertEquals("tried again after the capped wait", 4, connector.forAddress(C).size());
    }

    @Test
    public void theBackoffGrowsToThirtySecondsByDefault() {
        session.get("foo", new Bulk());
        long wait = 1000;
        for (int attempt = 0; attempt < 8; attempt++) {
            failConnect(C, attempt);
            clock.now += Math.min(wait, RedisClusterSession.MAX_BACKOFF_MS) - 1;
            session.get("foo", new Bulk());
            assertEquals("still backing off just before " + wait, attempt + 1,
                    connector.forAddress(C).size());
            clock.now += 1;
            session.get("foo", new Bulk());
            assertEquals(attempt + 2, connector.forAddress(C).size());
            wait *= 2;
        }
    }
}
