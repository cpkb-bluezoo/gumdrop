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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The health check of a connection: when commands are waiting and nothing
 * has been heard for the interval, {@code PING}; when that goes unanswered
 * too, the connection is closed and what was waiting is failed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientHealthCheckTest {

    private static final long INTERVAL = 1000;

    private static class Bulk implements BulkResultHandler {
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
            events.add("error:" + error);
        }
    }

    private static final class Listener implements RedirectListener {
        int healthFailures;
        final List<SentCommand> lost = new ArrayList<SentCommand>();

        @Override
        public boolean handleError(SentCommand command, String error) {
            return false;
        }

        @Override
        public void connectionLost(List<SentCommand> unanswered) {
            lost.addAll(unanswered);
        }

        @Override
        public void healthCheckFailed() {
            healthFailures++;
        }
    }

    private RedisClientProtocolHandler handler;
    private RedisClientCommandTimeoutTest.TimedEndpoint endpoint;
    private RedisSession session;
    private long now;
    private int disconnects;

    @Before
    public void setUp() {
        final AtomicReference<RedisSession> ref = new AtomicReference<RedisSession>();
        handler = new RedisClientProtocolHandler(new RedisConnectionReady() {
            @Override
            public void handleReady(RedisSession s) {
                ref.set(s);
            }

            @Override
            public void onConnected(org.bluezoo.gumdrop.Endpoint ep) {
            }

            @Override
            public void onSecurityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
            }

            @Override
            public void onError(Exception cause) {
            }

            @Override
            public void onDisconnected() {
                disconnects++;
            }
        });
        endpoint = new RedisClientCommandTimeoutTest.TimedEndpoint();
        handler.connected(endpoint);
        session = ref.get();
        now = 1000000000L;
        handler.setTicker(new RedisClientProtocolHandler.Ticker() {
            @Override
            public long nanos() {
                return now;
            }
        });
        handler.setHealthCheckMillis(INTERVAL);
    }

    private void advance(long ms) {
        now += ms * 1000000L;
    }

    private void feed(String resp) {
        handler.receive(ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8)));
    }

    private String written() {
        StringBuilder sb = new StringBuilder();
        for (ByteBuffer b : endpoint.sentBuffers) {
            byte[] bytes = new byte[b.remaining()];
            b.duplicate().get(bytes);
            sb.append(new String(bytes, StandardCharsets.ISO_8859_1));
        }
        return sb.toString();
    }

    private static final String PING = "*1\r\n$4\r\nPING\r\n";

    private int pings() {
        String w = written();
        int n = 0;
        for (int i = w.indexOf(PING); i >= 0; i = w.indexOf(PING, i + 1)) {
            n++;
        }
        return n;
    }

    private RedisClientCommandTimeoutTest.Timer live() {
        RedisClientCommandTimeoutTest.Timer found = null;
        for (RedisClientCommandTimeoutTest.Timer t : endpoint.timers) {
            if (!t.cancelled) {
                assertNull("one live timer", found);
                found = t;
            }
        }
        return found;
    }

    private void fire() {
        RedisClientCommandTimeoutTest.Timer t = live();
        assertNotNull("a timer is armed", t);
        t.cancelled = true;
        t.task.run();
    }

    @Test
    public void nothingIsCheckedWhileNothingIsWaiting() {
        assertNull(live());
    }

    @Test
    public void noTimerIsSetWhenHealthChecksAreOff() {
        handler.setHealthCheckMillis(0);
        session.get("k", new Bulk());
        assertNull(live());
    }

    @Test
    public void aWaitingCommandStartsTheCheck() {
        session.get("k", new Bulk());
        assertEquals(INTERVAL, live().delayMs);
    }

    @Test
    public void aQuietConnectionWithAWaitingCommandIsPinged() {
        session.get("k", new Bulk());
        advance(INTERVAL);
        fire();
        assertEquals(1, pings());
        assertEquals("checked again after another interval", INTERVAL, live().delayMs);
        assertTrue(endpoint.isOpen());
    }

    @Test
    public void trafficBeforeTheCheckPutsItOff() {
        Bulk b = new Bulk();
        session.get("k", b);
        session.get("j", new Bulk());
        advance(900);
        feed("$1\r\nv\r\n");
        advance(100);
        fire();
        assertEquals("heard from the server a moment ago", 0, pings());
        assertEquals(INTERVAL - 100, live().delayMs);
    }

    @Test
    public void nothingIsPingedOnceTheWaitingCommandsAreAnswered() {
        Bulk b = new Bulk();
        session.get("k", b);
        advance(500);
        feed("$1\r\nv\r\n");
        advance(500);
        fire();
        assertEquals(0, pings());
        assertNull(live());
    }

    @Test
    public void anAnsweredPingLeavesTheConnectionAlone() {
        Bulk b = new Bulk();
        session.get("k", b);
        advance(INTERVAL);
        fire();
        advance(100);
        feed("$1\r\nv\r\n");
        feed("+PONG\r\n");
        assertEquals("[value:v]", b.events.toString());
        advance(INTERVAL);
        // nothing waits now: the timer finds nothing to check and lapses
        fire();
        assertEquals(1, pings());
        assertNull(live());
        assertTrue(endpoint.isOpen());
    }

    @Test
    public void aPingThatGoesUnansweredClosesTheConnectionAndFailsWhatWaited() {
        Bulk b = new Bulk();
        session.get("k", b);
        advance(INTERVAL);
        fire();
        advance(INTERVAL);
        fire();
        assertFalse(endpoint.isOpen());
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:"));
        assertEquals("the owner is told", 1, disconnects);
    }

    @Test
    public void aCommandAlreadyTimedOutIsNotFailedAgain() {
        handler.setCommandTimeoutMillis(300);
        Bulk b = new Bulk();
        session.get("k", b);
        // the command timer is the earlier one
        advance(300);
        RedisClientCommandTimeoutTest.Timer first = endpoint.timers.get(0);
        first.cancelled = true;
        first.task.run();
        assertEquals(1, b.events.size());
        advance(INTERVAL);
        for (RedisClientCommandTimeoutTest.Timer t : new ArrayList<RedisClientCommandTimeoutTest.Timer>(endpoint.timers)) {
            if (!t.cancelled && t.delayMs == INTERVAL) {
                t.cancelled = true;
                t.task.run();
                break;
            }
        }
        advance(INTERVAL);
        for (RedisClientCommandTimeoutTest.Timer t : new ArrayList<RedisClientCommandTimeoutTest.Timer>(endpoint.timers)) {
            if (!t.cancelled && t.delayMs == INTERVAL) {
                t.cancelled = true;
                t.task.run();
                break;
            }
        }
        assertFalse(endpoint.isOpen());
        assertEquals("told once, by the timeout", 1, b.events.size());
    }

    @Test
    public void theListenerIsToldAndGivenOnlyTheRealCommands() {
        Listener listener = new Listener();
        handler.setRedirectListener(listener);
        session.get("k", new Bulk());
        advance(INTERVAL);
        fire();
        advance(INTERVAL);
        fire();
        assertEquals(1, listener.healthFailures);
        assertEquals(1, listener.lost.size());
        assertEquals("GET", listener.lost.get(0).getCommand());
    }

    @Test
    public void aBlockingCommandIsNotMistakenForADeadConnection() {
        session.blpop(0, new RedisClientCommandTimeoutTest.Many(), "q");
        advance(INTERVAL);
        RedisClientCommandTimeoutTest.Timer t = live();
        if (t != null) {
            t.cancelled = true;
            t.task.run();
        }
        advance(INTERVAL * 5);
        assertEquals(0, pings());
        assertTrue(endpoint.isOpen());
    }

    @Test
    public void aSubscribedConnectionIsPingedWhenQuiet() {
        session.subscribe(new RedisClientSubscriptionAccountingTest.Quiet(), "ch");
        feed(">3\r\n$9\r\nsubscribe\r\n$2\r\nch\r\n:1\r\n");
        assertEquals(INTERVAL, live().delayMs);
        advance(INTERVAL);
        fire();
        assertEquals(1, pings());
        // the answer, an array in RESP2 and a simple string in RESP3
        feed("+PONG\r\n");
        advance(INTERVAL);
        fire();
        assertEquals(2, pings());
        assertTrue(endpoint.isOpen());
    }

    @Test
    public void aSubscribedConnectionThatStopsAnsweringIsClosed() {
        session.subscribe(new RedisClientSubscriptionAccountingTest.Quiet(), "ch");
        feed(">3\r\n$9\r\nsubscribe\r\n$2\r\nch\r\n:1\r\n");
        advance(INTERVAL);
        fire();
        advance(INTERVAL);
        fire();
        assertFalse(endpoint.isOpen());
        assertEquals(1, disconnects);
    }

    @Test
    public void thePingIsNotCountedAgainstTheCap() {
        handler.setMaxPending(1);
        session.get("k", new Bulk());
        advance(INTERVAL);
        fire();
        assertEquals(1, pings());
    }

    @Test
    public void closingStopsTheCheck() {
        session.get("k", new Bulk());
        RedisClientCommandTimeoutTest.Timer t = live();
        handler.disconnected();
        assertTrue(t.cancelled);
    }

    @Test
    public void aPlainConnectionThatDropsFailsWhatWasWaiting() {
        Bulk b = new Bulk();
        session.get("k", b);
        handler.disconnected();
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:"));
        handler.disconnected();
        assertEquals("only once", 1, b.events.size());
    }
}
