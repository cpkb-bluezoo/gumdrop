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

import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.redis.codec.RespValue;

import static org.junit.Assert.*;

/**
 * Command timeouts and the cap on pending commands in
 * {@link RedisClusterSession}: on every node connection, and on commands
 * waiting for a connection to come up.
 *
 * <p>Map as in {@link RedisClusterSessionTest}: A (7000) serves 0-5460, B
 * (7001) 5461-10922, C (7002) 10923-16383; {@code foo} is slot 12182 (C).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterTimeoutTest {

    private static final String C = "127.0.0.1:7002";

    static final class Timer implements TimerHandle {
        final long delayMs;
        final Runnable task;
        boolean cancelled;

        Timer(long delayMs, Runnable task) {
            this.delayMs = delayMs;
            this.task = task;
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

    static final class TimedEndpoint extends RedisClientProtocolHandlerTest.StubEndpoint {
        final List<Timer> timers = new ArrayList<Timer>();

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            Timer t = new Timer(delayMs, callback);
            timers.add(t);
            return t;
        }

        Timer live() {
            Timer found = null;
            for (Timer t : timers) {
                if (!t.cancelled) {
                    assertNull("one live timer", found);
                    found = t;
                }
            }
            return found;
        }
    }

    static final class Request {
        final ClusterNode node;
        final NodeListener listener;
        RedisClientProtocolHandler handler;
        TimedEndpoint endpoint;

        Request(ClusterNode node, NodeListener listener) {
            this.node = node;
            this.listener = listener;
        }
    }

    static final class QuietReady implements RedisConnectionReady {
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

    static final class FakeConnector implements NodeConnector {
        final List<Request> requests = new ArrayList<Request>();

        @Override
        public void connect(ClusterNode node, NodeListener listener) {
            requests.add(new Request(node, listener));
        }
    }

    static final class Task {
        final long delay;
        final Runnable task;

        Task(long delay, Runnable task) {
            this.delay = delay;
            this.task = task;
        }
    }

    static final class FakeScheduler implements RedisClusterSession.Scheduler {
        final List<Task> tasks = new ArrayList<Task>();

        @Override
        public void schedule(long delayMs, Runnable task) {
            tasks.add(new Task(delayMs, task));
        }
    }

    static final class Bulk implements BulkResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(byte[] value, RedisSession s) {
            events.add("value:" + new String(value, StandardCharsets.UTF_8));
        }

        @Override
        public void handleNull(RedisSession s) {
            events.add("null");
        }

        @Override
        public void handleError(String error, RedisSession s) {
            events.add("error:" + error + (s instanceof RedisClusterSession ? "" : "!notcluster"));
        }
    }

    private static final class Many implements ArrayResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(List<RespValue> values, RedisSession s) {
            events.add("array:" + values.size());
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

    private FakeConnector connector;
    private FakeScheduler scheduler;
    private RedisClusterSession session;
    private long now;

    @Before
    public void setUp() {
        connector = new FakeConnector();
        scheduler = new FakeScheduler();
        session = new RedisClusterSession(SlotMap.parse(arr(range(0, 5460, 7000),
                range(5461, 10922, 7001), range(10923, 16383, 7002)), "127.0.0.1"), connector);
        session.setScheduler(scheduler);
        now = 1000000000L;
    }

    private Request up(Request r) {
        r.endpoint = new TimedEndpoint();
        r.handler = new RedisClientProtocolHandler(new QuietReady());
        r.handler.attachForTest(r.endpoint);
        r.handler.setTicker(new RedisClientProtocolHandler.Ticker() {
            @Override
            public long nanos() {
                return now;
            }
        });
        r.listener.ready(r.handler);
        return r;
    }

    private static void reply(Request r, String wire) {
        r.handler.receive(ByteBuffer.wrap(wire.getBytes(StandardCharsets.UTF_8)));
    }

    private static int commandsWritten(Request r) {
        int n = 0;
        for (ByteBuffer b : r.endpoint.sentBuffers) {
            byte[] bytes = new byte[b.remaining()];
            b.duplicate().get(bytes);
            String s = new String(bytes, StandardCharsets.ISO_8859_1);
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '*' && (i == 0 || s.charAt(i - 1) == '\n')) {
                    n++;
                }
            }
        }
        return n;
    }

    // ── on a connection ──

    @Test
    public void theDefaultTimeoutAppliesToNodeConnections() {
        session.setCommandTimeoutMillis(500);
        Bulk b = new Bulk();
        session.get("foo", b);
        Request c = up(connector.requests.get(0));
        assertEquals(500, c.endpoint.live().delayMs);
        now += 500L * 1000000L;
        Timer t = c.endpoint.live();
        t.cancelled = true;
        t.task.run();
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:TIMEOUT"));
        assertFalse(b.events.get(0), b.events.get(0).contains("!notcluster"));
        reply(c, "$1\r\nx\r\n");
        assertEquals(1, b.events.size());
    }

    @Test
    public void aPerCallTimeoutIsCarriedToTheConnection() {
        Many m = new Many();
        session.command(Duration.ofSeconds(7), m, "GET", "foo");
        Request c = up(connector.requests.get(0));
        assertEquals(7000, c.endpoint.live().delayMs);
    }

    @Test
    public void aPerCallTimeoutSurvivesARedirect() {
        Many m = new Many();
        session.command(Duration.ofSeconds(7), m, "GET", "foo");
        Request c = up(connector.requests.get(0));
        reply(c, "-MOVED 12182 127.0.0.1:7001\r\n");
        Request b = up(connector.requests.get(1));
        assertEquals(7000, b.endpoint.live().delayMs);
    }

    @Test
    public void noTimeoutIsSetUnlessAskedFor() {
        session.get("foo", new Bulk());
        Request c = up(connector.requests.get(0));
        assertNull(c.endpoint.live());
    }

    @Test
    public void theCapAppliesToEveryNodeConnection() {
        session.setMaxPending(2);
        Request c = null;
        session.get("foo", new Bulk());
        c = up(connector.requests.get(0));
        session.get("foo", new Bulk());
        Bulk third = new Bulk();
        session.get("foo", third);
        assertEquals(2, commandsWritten(c));
        assertEquals(1, third.events.size());
        assertTrue(third.events.get(0), third.events.get(0).startsWith("error:OVERLOAD"));
    }

    // ── commands waiting for a connection ──

    @Test
    public void commandsWaitingForAConnectionAreCappedToo() {
        session.setMaxPending(2);
        session.get("foo", new Bulk());
        session.get("foo", new Bulk());
        Bulk third = new Bulk();
        session.get("foo", third);
        assertEquals(1, third.events.size());
        assertTrue(third.events.get(0), third.events.get(0).startsWith("error:OVERLOAD"));
        Request c = up(connector.requests.get(0));
        assertEquals("the first two are sent", 2, commandsWritten(c));
    }

    @Test
    public void aCommandWaitingTooLongForAConnectionTimesOut() {
        session.setCommandTimeoutMillis(1000);
        Bulk waiting = new Bulk();
        session.get("foo", waiting);
        assertEquals(1, scheduler.tasks.size());
        assertEquals(1000, scheduler.tasks.get(0).delay);
        scheduler.tasks.get(0).task.run();
        assertEquals(1, waiting.events.size());
        assertTrue(waiting.events.get(0), waiting.events.get(0).startsWith("error:TIMEOUT"));
        // and it is not sent when the connection does come up
        Request c = up(connector.requests.get(0));
        assertEquals(0, commandsWritten(c));
    }

    @Test
    public void aCommandSentBeforeItsWaitEndsIsLeftAlone() {
        session.setCommandTimeoutMillis(1000);
        Bulk waiting = new Bulk();
        session.get("foo", waiting);
        Request c = up(connector.requests.get(0));
        scheduler.tasks.get(0).task.run();
        assertTrue(waiting.events.isEmpty());
        assertEquals(1, commandsWritten(c));
    }

    @Test
    public void aWaitingCommandWithItsOwnTimeoutUsesIt() {
        session.setCommandTimeoutMillis(1000);
        session.command(Duration.ofSeconds(9), new Many(), "GET", "foo");
        assertEquals(9000, scheduler.tasks.get(0).delay);
    }

    @Test
    public void noWaitTimerWithoutATimeout() {
        session.get("foo", new Bulk());
        assertTrue(scheduler.tasks.isEmpty());
    }
}
