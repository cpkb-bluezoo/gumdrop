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
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.redis.codec.RespValue;

import static org.junit.Assert.*;

/**
 * What {@link RedisClusterSession} does when a connection's health check
 * fails: the commands waiting are failed, the node is left alone for a
 * while, a fresh topology is asked for, and a Pub/Sub connection is
 * restored.
 *
 * <p>Map as in {@link RedisClusterSessionTest}; {@code foo} is slot 12182
 * (C, 7002).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterHealthTest {

    private static final long INTERVAL = 1000;
    private static final String C = "127.0.0.1:7002";

    private static final class Refresher implements TopologyRefresher {
        final List<Callback> requests = new ArrayList<Callback>();

        @Override
        public void refresh(Callback callback) {
            requests.add(callback);
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

    private RedisClusterTimeoutTest.FakeConnector connector;
    private RedisClusterTimeoutTest.FakeScheduler scheduler;
    private Refresher refresher;
    private RedisClusterSession session;
    private long now;
    private long clockMs;

    @Before
    public void setUp() {
        connector = new RedisClusterTimeoutTest.FakeConnector();
        scheduler = new RedisClusterTimeoutTest.FakeScheduler();
        refresher = new Refresher();
        session = new RedisClusterSession(SlotMap.parse(arr(range(0, 5460, 7000),
                range(5461, 10922, 7001), range(10923, 16383, 7002)), "127.0.0.1"), connector);
        session.setScheduler(scheduler);
        session.setRefresher(refresher);
        clockMs = 5000000L;
        session.setClock(new RedisClusterSession.Clock() {
            @Override
            public long millis() {
                return clockMs;
            }
        });
        session.setHealthCheckMillis(INTERVAL);
        now = 1000000000L;
    }

    private RedisClusterTimeoutTest.Request up(RedisClusterTimeoutTest.Request r) {
        r.endpoint = new RedisClusterTimeoutTest.TimedEndpoint();
        r.handler = new RedisClientProtocolHandler(new RedisClusterTimeoutTest.QuietReady());
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

    private void advance(long ms) {
        now += ms * 1000000L;
        clockMs += ms;
    }

    private static void fire(RedisClusterTimeoutTest.Request r) {
        RedisClusterTimeoutTest.Timer t = r.endpoint.live();
        assertNotNull("a timer is armed", t);
        t.cancelled = true;
        t.task.run();
    }

    private static void feed(RedisClusterTimeoutTest.Request r, String wire) {
        r.handler.receive(ByteBuffer.wrap(wire.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void aNodeThatStopsAnsweringFailsWhatWasWaitingAndAsksForATopology() {
        RedisClusterTimeoutTest.Bulk b = new RedisClusterTimeoutTest.Bulk();
        session.get("foo", b);
        RedisClusterTimeoutTest.Request c = up(connector.requests.get(0));
        assertEquals(INTERVAL, c.endpoint.live().delayMs);
        advance(INTERVAL);
        fire(c);
        advance(INTERVAL);
        fire(c);
        assertFalse(c.endpoint.isOpen());
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:"));
        assertTrue(b.events.get(0), b.events.get(0).contains(C));
        assertEquals(1, refresher.requests.size());
    }

    @Test
    public void aNodeThatFailedIsLeftAloneForAWhileThenTriedAgain() {
        session.get("foo", new RedisClusterTimeoutTest.Bulk());
        RedisClusterTimeoutTest.Request c = up(connector.requests.get(0));
        advance(INTERVAL);
        fire(c);
        advance(INTERVAL);
        fire(c);
        c.listener.closed();
        RedisClusterTimeoutTest.Bulk quick = new RedisClusterTimeoutTest.Bulk();
        session.get("foo", quick);
        assertEquals("not tried at once", 1, connector.requests.size());
        assertEquals(1, quick.events.size());
        advance(RedisClusterSession.INITIAL_BACKOFF_MS);
        RedisClusterTimeoutTest.Bulk later = new RedisClusterTimeoutTest.Bulk();
        session.get("foo", later);
        assertEquals("tried again", 2, connector.requests.size());
        assertTrue(later.events.isEmpty());
    }

    @Test
    public void aSubscriptionConnectionThatStopsAnsweringIsRestored() {
        session.subscribe(new RedisClientSubscriptionAccountingTest.Quiet(), "news");
        RedisClusterTimeoutTest.Request r = up(connector.requests.get(0));
        feed(r, ">3\r\n$9\r\nsubscribe\r\n$4\r\nnews\r\n:1\r\n");
        assertEquals(INTERVAL, r.endpoint.live().delayMs);
        advance(INTERVAL);
        fire(r);
        advance(INTERVAL);
        fire(r);
        assertFalse(r.endpoint.isOpen());
        r.listener.closed();
        assertEquals("a fresh topology is asked for", 1, refresher.requests.size());
        assertEquals(1, scheduler.tasks.size());
        scheduler.tasks.get(0).task.run();
        assertEquals("and the connection is made again", 2, connector.requests.size());
    }

    @Test
    public void noCheckUnlessAskedFor() {
        session.setHealthCheckMillis(0);
        session.get("foo", new RedisClusterTimeoutTest.Bulk());
        RedisClusterTimeoutTest.Request c = up(connector.requests.get(0));
        assertNull(c.endpoint.live());
    }
}
