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
 * Pub/Sub on {@link RedisClusterSession}: ordinary subscriptions share one
 * dedicated connection; sharded ones live on a connection to the node that
 * owns each channel's slot. Subscriptions are restored after a connection is
 * lost, and follow a slot that moves.
 *
 * <p>Map as in {@link RedisClusterSessionTest}: A (7000) serves 0-5460, B
 * (7001) 5461-10922, C (7002) 10923-16383. {@code foo} is slot 12182 (C),
 * {@code bar} 5061 and {@code baz} 4813 (both A), {@code test} 6918 (B).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterPubSubTest {

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

    private static final class Rec implements MessageHandler {
        final List<String> events = new ArrayList<String>();

        private static String text(byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }

        @Override
        public void handleMessage(String channel, byte[] message) {
            events.add("message:" + channel + ":" + text(message));
        }

        @Override
        public void handlePatternMessage(String pattern, String channel, byte[] message) {
            events.add("pmessage:" + pattern + ":" + channel + ":" + text(message));
        }

        @Override
        public void handleSubscribed(String channel, int count) {
            events.add("subscribed:" + channel + ":" + count);
        }

        @Override
        public void handleUnsubscribed(String channel, int count) {
            events.add("unsubscribed:" + channel + ":" + count);
        }

        @Override
        public void handlePatternSubscribed(String pattern, int count) {
            events.add("psubscribed:" + pattern + ":" + count);
        }

        @Override
        public void handlePatternUnsubscribed(String pattern, int count) {
            events.add("punsubscribed:" + pattern + ":" + count);
        }

        @Override
        public void handleShardMessage(String channel, byte[] message) {
            events.add("smessage:" + channel + ":" + text(message));
        }

        @Override
        public void handleShardSubscribed(String channel, int count) {
            events.add("ssubscribed:" + channel + ":" + count);
        }

        @Override
        public void handleShardUnsubscribed(String channel, int count) {
            events.add("sunsubscribed:" + channel + ":" + count);
        }

        @Override
        public void handleError(String error) {
            events.add("error:" + error);
        }
    }

    private static final class Ignore implements BulkResultHandler {
        @Override
        public void handleResult(byte[] value, RedisSession session) {
        }

        @Override
        public void handleNull(RedisSession session) {
        }

        @Override
        public void handleError(String error, RedisSession session) {
        }
    }

    private static final class Count implements IntegerResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(long value, RedisSession session) {
            events.add("value:" + value);
        }

        @Override
        public void handleError(String error, RedisSession session) {
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

    private FakeConnector connector;
    private FakeClock clock;
    private FakeScheduler scheduler;
    private FakeRefresher refresher;
    private RedisClusterSession session;

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

    private Request up(Request r) {
        r.endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        r.handler = new RedisClientProtocolHandler(new QuietReady());
        r.handler.attachForTest(r.endpoint);
        r.listener.ready(r.handler);
        return r;
    }

    private Request up(String address, int index) {
        return up(connector.forAddress(address).get(index));
    }

    private Request upLatest(String address) {
        List<Request> all = connector.forAddress(address);
        return up(all.get(all.size() - 1));
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

    private static void push(Request r, String kind, String channel, String last) {
        reply(r, "*3\r\n$" + kind.length() + "\r\n" + kind + "\r\n$" + channel.length()
                + "\r\n" + channel + "\r\n" + last);
    }

    private static void lose(Request r) {
        r.handler.disconnected();
        r.listener.closed();
    }

    private static int occurrences(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    // ── ordinary subscriptions ──

    @Test
    public void subscribeSendsSubscribeOnADedicatedConnection() {
        session.subscribe(new Rec(), "news");
        assertEquals(1, connector.requests.size());
        Request r = up(connector.requests.get(0));
        assertEquals(resp("SUBSCRIBE", "news"), written(r));
    }

    @Test
    public void messagesReachTheHandler() {
        Rec h = new Rec();
        session.subscribe(h, "news");
        Request r = up(connector.requests.get(0));
        push(r, "subscribe", "news", ":1\r\n");
        push(r, "message", "news", "$2\r\nhi\r\n");
        assertEquals("[subscribed:news:1, message:news:hi]", h.events.toString());
    }

    @Test
    public void channelsSubscribedSeparatelyShareOneConnection() {
        Rec first = new Rec();
        Rec second = new Rec();
        session.subscribe(first, "one");
        Request r = up(connector.requests.get(0));
        session.subscribe(second, "two");
        assertEquals(1, connector.requests.size());
        assertTrue(written(r), written(r).contains(resp("SUBSCRIBE", "two")));
        push(r, "message", "one", "$1\r\na\r\n");
        push(r, "message", "two", "$1\r\nb\r\n");
        assertEquals("[message:one:a]", first.events.toString());
        assertEquals("[message:two:b]", second.events.toString());
    }

    @Test
    public void patternsAreRelayedToTheirHandler() {
        Rec h = new Rec();
        session.psubscribe(h, "n*");
        Request r = up(connector.requests.get(0));
        assertEquals(resp("PSUBSCRIBE", "n*"), written(r));
        reply(r, "*4\r\n$8\r\npmessage\r\n$2\r\nn*\r\n$4\r\nnews\r\n$2\r\nhi\r\n");
        assertEquals("[pmessage:n*:news:hi]", h.events.toString());
    }

    @Test
    public void unsubscribeIsSentAndAcknowledgedToTheHandler() {
        Rec h = new Rec();
        session.subscribe(h, "news");
        Request r = up(connector.requests.get(0));
        push(r, "subscribe", "news", ":1\r\n");
        session.unsubscribe("news");
        assertTrue(written(r), written(r).endsWith(resp("UNSUBSCRIBE", "news")));
        push(r, "unsubscribe", "news", ":0\r\n");
        assertEquals("[subscribed:news:1, unsubscribed:news:0]", h.events.toString());
        push(r, "message", "news", "$2\r\nhi\r\n");
        assertEquals(2, h.events.size());
    }

    @Test
    public void publishDoesNotUseTheSubscriptionConnection() {
        session.subscribe(new Rec(), "news");
        Request sub = up(connector.requests.get(0));
        Count c = new Count();
        session.publish("news", "hi", c);
        assertEquals(2, connector.requests.size());
        Request pub = up(connector.requests.get(1));
        assertFalse(written(sub), written(sub).contains("PUBLISH"));
        assertTrue(written(pub), written(pub).contains(resp("PUBLISH", "news", "hi")));
    }

    @Test
    public void aLostConnectionIsRestoredAndResubscribed() {
        Rec h = new Rec();
        session.subscribe(h, "news");
        session.psubscribe(h, "n*");
        Request r = up(connector.requests.get(0));
        lose(r);
        assertEquals(1, scheduler.tasks.size());
        assertEquals(RedisClusterSession.INITIAL_BACKOFF_MS, scheduler.tasks.get(0).delay);
        assertEquals(1, connector.requests.size());
        scheduler.runLast();
        assertEquals(2, connector.requests.size());
        Request again = up(connector.requests.get(1));
        assertTrue(written(again), written(again).contains(resp("SUBSCRIBE", "news")));
        assertTrue(written(again), written(again).contains(resp("PSUBSCRIBE", "n*")));
    }

    @Test
    public void anUnsubscribedChannelIsNotRestored() {
        Rec h = new Rec();
        session.subscribe(h, "news", "other");
        Request r = up(connector.requests.get(0));
        session.unsubscribe("other");
        push(r, "unsubscribe", "other", ":1\r\n");
        lose(r);
        scheduler.runLast();
        Request again = up(connector.requests.get(1));
        assertEquals(resp("SUBSCRIBE", "news"), written(again));
    }

    @Test
    public void aFailedConnectionIsTriedAgainWithGrowingBackoff() {
        session.subscribe(new Rec(), "news");
        connector.requests.get(0).listener.failed(new java.io.IOException("refused"));
        assertEquals(RedisClusterSession.INITIAL_BACKOFF_MS, scheduler.tasks.get(0).delay);
        scheduler.runLast();
        assertEquals(2, connector.requests.size());
        connector.requests.get(1).listener.failed(new java.io.IOException("refused"));
        assertEquals(2 * RedisClusterSession.INITIAL_BACKOFF_MS, scheduler.tasks.get(0).delay);
        scheduler.runLast();
        assertEquals(3, connector.requests.size());
    }

    @Test
    public void nothingIsRestoredOnceTheSessionIsClosed() {
        session.subscribe(new Rec(), "news");
        Request r = up(connector.requests.get(0));
        lose(r);
        session.close();
        scheduler.runLast();
        assertEquals(1, connector.requests.size());
    }

    @Test
    public void closingTheSessionClosesTheSubscriptionConnections() {
        session.subscribe(new Rec(), "news");
        session.ssubscribe(new Rec(), "foo");
        Request plain = up(connector.requests.get(0));
        Request shard = up(connector.requests.get(1));
        session.close();
        assertFalse(plain.endpoint.open);
        assertFalse(shard.endpoint.open);
    }

    @Test
    public void subscribingOnAClosedSessionIsAnError() {
        session.close();
        Rec h = new Rec();
        session.subscribe(h, "news");
        session.ssubscribe(h, "foo");
        assertEquals(2, h.events.size());
        assertTrue(h.events.get(0), h.events.get(0).startsWith("error:"));
        assertEquals(0, connector.requests.size());
    }

    // ── sharded subscriptions ──

    @Test
    public void ssubscribeConnectsToTheNodeOwningTheSlot() {
        session.ssubscribe(new Rec(), "foo");
        assertEquals(1, connector.requests.size());
        assertEquals(C, connector.requests.get(0).node.getAddress());
        Request r = up(connector.requests.get(0));
        assertEquals(resp("SSUBSCRIBE", "foo"), written(r));
    }

    @Test
    public void channelsOnDifferentNodesUseDifferentConnections() {
        Rec h = new Rec();
        session.ssubscribe(h, "foo", "bar", "test");
        assertEquals(3, connector.requests.size());
        assertEquals(1, connector.forAddress(A).size());
        assertEquals(1, connector.forAddress(B).size());
        assertEquals(1, connector.forAddress(C).size());
        assertEquals(resp("SSUBSCRIBE", "bar"), written(up(A, 0)));
        assertEquals(resp("SSUBSCRIBE", "test"), written(up(B, 0)));
        assertEquals(resp("SSUBSCRIBE", "foo"), written(up(C, 0)));
    }

    @Test
    public void channelsOnOneNodeButDifferentSlotsAreSubscribedSeparately() {
        session.ssubscribe(new Rec(), "bar", "baz");
        assertEquals(1, connector.requests.size());
        String wire = written(up(connector.requests.get(0)));
        assertEquals(2, occurrences(wire, "SSUBSCRIBE"));
        assertTrue(wire, wire.contains(resp("SSUBSCRIBE", "bar")));
        assertTrue(wire, wire.contains(resp("SSUBSCRIBE", "baz")));
    }

    @Test
    public void shardMessagesReachTheHandler() {
        Rec h = new Rec();
        session.ssubscribe(h, "foo");
        Request r = up(connector.requests.get(0));
        push(r, "ssubscribe", "foo", ":1\r\n");
        push(r, "smessage", "foo", "$2\r\nhi\r\n");
        assertEquals("[ssubscribed:foo:1, smessage:foo:hi]", h.events.toString());
    }

    @Test
    public void sunsubscribeIsSentAndAcknowledgedToTheHandler() {
        Rec h = new Rec();
        session.ssubscribe(h, "foo");
        Request r = up(connector.requests.get(0));
        push(r, "ssubscribe", "foo", ":1\r\n");
        session.sunsubscribe("foo");
        assertTrue(written(r), written(r).endsWith(resp("SUNSUBSCRIBE", "foo")));
        push(r, "sunsubscribe", "foo", ":0\r\n");
        assertEquals("[ssubscribed:foo:1, sunsubscribed:foo:0]", h.events.toString());
    }

    @Test
    public void shardSubscriptionsAreRestoredAfterALoss() {
        session.ssubscribe(new Rec(), "foo");
        Request r = up(connector.requests.get(0));
        lose(r);
        scheduler.runLast();
        Request again = up(connector.requests.get(1));
        assertEquals(C, again.node.getAddress());
        assertEquals(resp("SSUBSCRIBE", "foo"), written(again));
    }

    @Test
    public void movedMovesTheSubscriptionToTheNewOwner() {
        Rec h = new Rec();
        session.ssubscribe(h, "foo");
        Request c = up(connector.requests.get(0));
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        assertEquals(1, connector.forAddress(B).size());
        Request b = up(B, 0);
        assertEquals(resp("SSUBSCRIBE", "foo"), written(b));
        assertEquals(B, session.slotMap().nodeFor(12182).getAddress());
        assertEquals(0, h.events.size());
        push(b, "ssubscribe", "foo", ":1\r\n");
        push(b, "smessage", "foo", "$2\r\nhi\r\n");
        assertEquals("[ssubscribed:foo:1, smessage:foo:hi]", h.events.toString());
    }

    @Test
    public void aSlotThatMovesAwayIsFollowed() {
        Rec h = new Rec();
        session.ssubscribe(h, "foo");
        Request c = up(connector.requests.get(0));
        push(c, "ssubscribe", "foo", ":1\r\n");
        // the server drops the subscription when it gives the slot away
        push(c, "sunsubscribe", "foo", ":0\r\n");
        assertEquals("[ssubscribed:foo:1]", h.events.toString());
        // our map is stale, so the first attempt is redirected
        assertTrue(written(c), written(c).endsWith(resp("SSUBSCRIBE", "foo")));
        assertEquals(2, occurrences(written(c), "SSUBSCRIBE"));
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        Request b = up(B, 0);
        assertEquals(resp("SSUBSCRIBE", "foo"), written(b));
    }

    @Test
    public void redirectsAreCapped() {
        Rec h = new Rec();
        session.ssubscribe(h, "foo");
        Request c = up(connector.requests.get(0));
        Request b = null;
        Request current = c;
        boolean toB = true;
        int round = 0;
        while (h.events.isEmpty() && round < RedisClusterSession.MAX_REDIRECTS + 3) {
            reply(current, "-MOVED 12182 127.0.0.1:" + (toB ? "7001" : "7002") + "\r\n");
            round++;
            if (!h.events.isEmpty()) {
                break;
            }
            if (toB) {
                if (b == null) {
                    b = up(B, 0);
                }
                current = b;
            } else {
                current = c;
            }
            toB = !toB;
        }
        assertEquals(1, h.events.size());
        assertTrue(h.events.get(0), h.events.get(0).startsWith("error:"));
        assertEquals(RedisClusterSession.MAX_REDIRECTS + 1, round);
    }

    @Test
    public void otherSubscribeErrorsGoToTheHandlerAndEndTheSubscription() {
        Rec h = new Rec();
        session.ssubscribe(h, "foo");
        Request r = up(connector.requests.get(0));
        reply(r, "-NOPERM this user has no permissions\r\n");
        assertEquals(1, h.events.size());
        assertTrue(h.events.get(0), h.events.get(0).startsWith("error:NOPERM"));
        lose(r);
        // nothing left to restore
        assertEquals(0, scheduler.tasks.size());
        assertEquals(1, connector.requests.size());
    }

    @Test
    public void aSubscriptionOnANodeThatLeavesTheClusterIsMoved() {
        session.ssubscribe(new Rec(), "foo");
        Request c = up(connector.requests.get(0));
        // a command that cannot reach its node asks for a fresh topology
        session.get("bar", new Ignore());
        connector.forAddress(A).get(0).listener.failed(new java.io.IOException("refused"));
        assertEquals(1, refresher.requests.size());
        refresher.requests.get(0).refreshed(SlotMap.parse(arr(range(0, 8000, 7000),
                range(8001, 16383, 7001)), "127.0.0.1"));
        assertFalse(c.endpoint.open);
        assertEquals(1, connector.forAddress(B).size());
        Request b = up(B, 0);
        assertEquals(resp("SSUBSCRIBE", "foo"), written(b));
    }

    @Test
    public void spublishIsRoutedByChannel() {
        Count c = new Count();
        session.spublish("foo", "hi", c);
        assertEquals(C, connector.requests.get(0).node.getAddress());
        Request r = up(connector.requests.get(0));
        assertEquals(resp("SPUBLISH", "foo", "hi"), written(r));
        reply(r, ":2\r\n");
        assertEquals("[value:2]", c.events.toString());
    }

    // ── generic pushes ──

    private static final class Pushes implements PushHandler {
        final List<String> kinds = new ArrayList<String>();

        @Override
        public void onPush(String kind, List<Object> payload) {
            kinds.add(kind);
        }
    }

    private static final String INVALIDATE = ">2\r\n$10\r\ninvalidate\r\n*1\r\n$3\r\nfoo\r\n";

    @Test
    public void pushesFromEveryNodeConnectionReachThePushHandler() {
        Pushes pushes = new Pushes();
        session.setPushHandler(pushes);
        session.get("foo", new Ignore());
        session.get("bar", new Ignore());
        Request c = up(C, 0);
        Request a = up(A, 0);
        reply(c, INVALIDATE);
        reply(a, INVALIDATE);
        assertEquals(2, pushes.kinds.size());
    }

    @Test
    public void aConnectionOpenedLaterGetsThePushHandlerToo() {
        Pushes pushes = new Pushes();
        session.get("foo", new Ignore());
        Request c = up(C, 0);
        reply(c, INVALIDATE);
        assertEquals(0, pushes.kinds.size());
        session.setPushHandler(pushes);
        reply(c, INVALIDATE);
        assertEquals("the connection already open", 1, pushes.kinds.size());
        session.get("bar", new Ignore());
        reply(up(A, 0), INVALIDATE);
        assertEquals("the connection opened afterwards", 2, pushes.kinds.size());
    }

    @Test
    public void pubSubConnectionsDeliverOtherPushesToo() {
        Pushes pushes = new Pushes();
        session.setPushHandler(pushes);
        session.subscribe(new Rec(), "news");
        session.ssubscribe(new Rec(), "foo");
        reply(up(connector.requests.get(0)), INVALIDATE);
        reply(up(connector.requests.get(1)), INVALIDATE);
        assertEquals(2, pushes.kinds.size());
    }

    // ── keyspace notifications ──

    private static final String PATTERN = "__keyspace@0__:*";

    private static SlotMap fourMasters() {
        return SlotMap.parse(arr(range(0, 4095, 7000), range(4096, 8191, 7001),
                range(8192, 12287, 7002), range(12288, 16383, 7003)), "127.0.0.1");
    }

    private void refreshTo(SlotMap fresh) {
        // a connection that cannot be made asks for a fresh topology
        session.get("foo", new Ignore());
        connector.forAddress(C).get(connector.forAddress(C).size() - 1)
                .listener.failed(new java.io.IOException("refused"));
        refresher.requests.get(refresher.requests.size() - 1).refreshed(fresh);
    }

    @Test
    public void keyspaceSubscriptionConnectsToEveryPrimary() {
        session.subscribeKeyspace(PATTERN, new Rec());
        assertEquals(1, connector.forAddress(A).size());
        assertEquals(1, connector.forAddress(B).size());
        assertEquals(1, connector.forAddress(C).size());
        assertEquals(resp("PSUBSCRIBE", PATTERN), written(up(A, 0)));
        assertEquals(resp("PSUBSCRIBE", PATTERN), written(up(B, 0)));
        assertEquals(resp("PSUBSCRIBE", PATTERN), written(up(C, 0)));
    }

    @Test
    public void eventsFromEveryPrimaryReachTheHandler() {
        Rec h = new Rec();
        session.subscribeKeyspace(PATTERN, h);
        Request a = up(A, 0);
        up(B, 0);
        Request c = up(C, 0);
        reply(a, "*4\r\n$8\r\npmessage\r\n$16\r\n__keyspace@0__:*\r\n$18\r\n__keyspace@0__:bar\r\n$3\r\nset\r\n");
        reply(c, "*4\r\n$8\r\npmessage\r\n$16\r\n__keyspace@0__:*\r\n$18\r\n__keyspace@0__:foo\r\n$3\r\ndel\r\n");
        assertEquals("[pmessage:__keyspace@0__:*:__keyspace@0__:bar:set, "
                + "pmessage:__keyspace@0__:*:__keyspace@0__:foo:del]", h.events.toString());
    }

    @Test
    public void aLaterPatternIsSubscribedOnEveryPrimary() {
        session.subscribeKeyspace(PATTERN, new Rec());
        Request a = up(A, 0);
        up(B, 0);
        up(C, 0);
        session.subscribeKeyspace("__keyevent@0__:expired", new Rec());
        assertTrue(written(a), written(a).endsWith(resp("PSUBSCRIBE", "__keyevent@0__:expired")));
    }

    @Test
    public void aPrimaryFoundByARefreshIsSubscribedToo() {
        session.subscribeKeyspace(PATTERN, new Rec());
        up(A, 0);
        up(B, 0);
        Request c = up(C, 0);
        refreshTo(fourMasters());
        assertEquals(1, connector.forAddress("127.0.0.1:7003").size());
        Request d = upLatest("127.0.0.1:7003");
        assertEquals(resp("PSUBSCRIBE", PATTERN), written(d));
        assertTrue(c.endpoint.open);
    }

    @Test
    public void aPrimaryThatHasLeftIsDropped() {
        session.subscribeKeyspace(PATTERN, new Rec());
        up(A, 0);
        Request b = up(B, 0);
        Request c = up(C, 0);
        refreshTo(SlotMap.parse(arr(range(0, 8000, 7000), range(8001, 16383, 7001)), "127.0.0.1"));
        assertFalse("C's subscription is closed", c.endpoint.open);
        assertTrue(b.endpoint.open);
        int before = connector.requests.size();
        lose(c);
        // nothing restores it
        for (Task t : new ArrayList<Task>(scheduler.tasks)) {
            t.task.run();
        }
        assertEquals(before, connector.forAddress(C).size() + connector.forAddress(A).size()
                + connector.forAddress(B).size());
    }

    @Test
    public void aLostPrimaryConnectionIsRestoredAndResubscribed() {
        session.subscribeKeyspace(PATTERN, new Rec());
        up(A, 0);
        up(B, 0);
        Request c = up(C, 0);
        lose(c);
        scheduler.runLast();
        assertEquals(2, connector.forAddress(C).size());
        assertEquals(resp("PSUBSCRIBE", PATTERN), written(up(C, 1)));
    }

    @Test
    public void keyspaceSubscriptionOnAClosedSessionIsAnError() {
        session.close();
        Rec h = new Rec();
        session.subscribeKeyspace(PATTERN, h);
        assertEquals(1, h.events.size());
        assertTrue(h.events.get(0), h.events.get(0).startsWith("error:"));
        assertEquals(0, connector.requests.size());
    }
}
