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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.redis.codec.RespValue;

import static org.junit.Assert.*;

/**
 * Per-command timeouts and the cap on commands awaiting a reply, on a
 * single connection. A fake clock and a recording timer make the timing
 * deterministic.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientCommandTimeoutTest {

    private static final class Timer implements TimerHandle {
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

    /** An endpoint that records the timers asked of it. */
    private static final class TimedEndpoint extends RedisClientProtocolHandlerTest.StubEndpoint {
        final List<Timer> timers = new ArrayList<Timer>();

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            Timer t = new Timer(delayMs, callback);
            timers.add(t);
            return t;
        }

        /** The timers not yet cancelled or run. */
        List<Timer> live() {
            List<Timer> out = new ArrayList<Timer>();
            for (Timer t : timers) {
                if (!t.cancelled) {
                    out.add(t);
                }
            }
            return out;
        }
    }

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

    private RedisClientProtocolHandler handler;
    private TimedEndpoint endpoint;
    private RedisSession session;
    private long now;

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
            }
        });
        endpoint = new TimedEndpoint();
        handler.connected(endpoint);
        session = ref.get();
        now = 1000000000L;
        handler.setTicker(new RedisClientProtocolHandler.Ticker() {
            @Override
            public long nanos() {
                return now;
            }
        });
    }

    private void advance(long ms) {
        now += ms * 1000000L;
    }

    private void feed(String resp) {
        handler.receive(ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8)));
    }

    private int written() {
        int n = 0;
        for (ByteBuffer b : endpoint.sentBuffers) {
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

    /** Runs the one live timer. */
    private void fire() {
        List<Timer> live = endpoint.live();
        assertEquals("exactly one timer is armed", 1, live.size());
        Timer t = live.get(0);
        t.cancelled = true;
        t.task.run();
    }

    // ── timeouts ──

    @Test
    public void withoutATimeoutNothingIsScheduled() {
        session.get("k", new Bulk());
        assertTrue(endpoint.timers.isEmpty());
    }

    @Test
    public void aCommandPastTheDefaultTimeoutFailsWithATimeoutError() {
        handler.setCommandTimeoutMillis(500);
        Bulk b = new Bulk();
        session.get("k", b);
        assertEquals(1, endpoint.live().size());
        assertEquals(500, endpoint.live().get(0).delayMs);
        advance(500);
        fire();
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:TIMEOUT"));
    }

    @Test
    public void aReplyBeforeTheDeadlineIsDeliveredAndNoTimeoutFollows() {
        handler.setCommandTimeoutMillis(500);
        Bulk b = new Bulk();
        session.get("k", b);
        advance(100);
        feed("$1\r\nv\r\n");
        advance(500);
        List<Timer> live = endpoint.live();
        if (!live.isEmpty()) {
            fire();
        }
        assertEquals("[value:v]", b.events.toString());
    }

    @Test
    public void aReplyAfterTheTimeoutIsIgnored() {
        handler.setCommandTimeoutMillis(500);
        Bulk first = new Bulk();
        session.get("a", first);
        advance(500);
        fire();
        feed("$1\r\nx\r\n");
        assertEquals(1, first.events.size());
        assertTrue(first.events.get(0).startsWith("error:TIMEOUT"));
    }

    @Test
    public void aLateReplyDoesNotShiftTheRepliesOfLaterCommands() {
        handler.setCommandTimeoutMillis(500);
        Bulk first = new Bulk();
        session.get("a", first);
        advance(500);
        fire();
        Bulk second = new Bulk();
        session.get("b", second);
        feed("$1\r\nx\r\n");
        feed("$1\r\ny\r\n");
        assertEquals("[value:y]", second.events.toString());
        assertEquals(1, first.events.size());
    }

    @Test
    public void anErrorReplyAfterTheTimeoutIsIgnoredToo() {
        handler.setCommandTimeoutMillis(500);
        Bulk first = new Bulk();
        session.get("a", first);
        advance(500);
        fire();
        feed("-ERR late\r\n");
        assertEquals(1, first.events.size());
    }

    @Test
    public void theTimerIsSetForTheEarliestDeadlineAndRearmedForTheNext() {
        handler.setCommandTimeoutMillis(1000);
        Bulk a = new Bulk();
        Bulk b = new Bulk();
        session.get("a", a);
        advance(300);
        session.get("b", b);
        assertEquals(1000, endpoint.live().get(0).delayMs);
        advance(700);
        fire();
        assertEquals(1, a.events.size());
        assertTrue(b.events.isEmpty());
        assertEquals(1, endpoint.live().size());
        assertEquals(300, endpoint.live().get(0).delayMs);
        advance(300);
        fire();
        assertEquals(1, b.events.size());
    }

    @Test
    public void aPerCallTimeoutOverridesTheDefault() {
        handler.setCommandTimeoutMillis(500);
        Many m = new Many();
        session.command(Duration.ofSeconds(5), m, "ECHO", "x");
        assertEquals(5000, endpoint.live().get(0).delayMs);
    }

    @Test
    public void aPerCallTimeoutWorksWithoutADefault() {
        Many m = new Many();
        session.command(Duration.ofMillis(250), m, "ECHO", "x");
        assertEquals(250, endpoint.live().get(0).delayMs);
        advance(250);
        fire();
        assertTrue(m.events.get(0), m.events.get(0).startsWith("error:TIMEOUT"));
    }

    @Test
    public void aZeroPerCallTimeoutMeansNone() {
        handler.setCommandTimeoutMillis(500);
        session.command(Duration.ZERO, new Many(), "ECHO", "x");
        assertTrue(endpoint.timers.isEmpty());
    }

    @Test
    public void evalAndEvalshaTakeATimeoutToo() {
        Many a = new Many();
        Many b = new Many();
        session.eval("return 1", 0, new String[0], new String[0], Duration.ofSeconds(2), a);
        session.evalsha("abc", 0, new String[0], new String[0], Duration.ofSeconds(3), b);
        // one timer serves both: set for the earlier, then for the later
        assertEquals(1, endpoint.live().size());
        assertEquals(2000, endpoint.live().get(0).delayMs);
        advance(2000);
        fire();
        assertTrue(a.events.get(0), a.events.get(0).startsWith("error:TIMEOUT"));
        assertTrue(b.events.isEmpty());
        assertEquals(1000, endpoint.live().get(0).delayMs);
    }

    @Test
    public void aBinaryCommandTakesATimeoutToo() {
        session.command(Duration.ofSeconds(4), new Many(), "PING", new byte[0][]);
        assertEquals(4000, endpoint.live().get(0).delayMs);
    }

    @Test
    public void aTimedOutCommandIsReportedOnce() {
        handler.setCommandTimeoutMillis(500);
        Bulk b = new Bulk();
        session.get("k", b);
        advance(500);
        fire();
        advance(5000);
        assertTrue(endpoint.live().isEmpty());
        assertEquals(1, b.events.size());
    }

    @Test
    public void aHandlerMayIssueACommandFromItsTimeoutCallback() {
        handler.setCommandTimeoutMillis(500);
        final Bulk retry = new Bulk();
        session.get("k", new Bulk() {
            @Override
            public void handleError(String error, RedisSession s) {
                s.get("k", retry);
            }
        });
        advance(500);
        fire();
        assertEquals(1, endpoint.live().size());
        feed("$1\r\nx\r\n");
        feed("$1\r\nv\r\n");
        assertEquals("[value:v]", retry.events.toString());
    }

    @Test
    public void closingTheConnectionCancelsTheTimer() {
        handler.setCommandTimeoutMillis(500);
        session.get("k", new Bulk());
        Timer t = endpoint.live().get(0);
        handler.disconnected();
        assertTrue(t.cancelled);
    }

    // ── commands that wait on purpose ──

    @Test
    public void aBlockingCommandIsGivenItsOwnWaitOnTopOfTheTimeout() {
        handler.setCommandTimeoutMillis(1000);
        session.blpop(5, new Many(), "q");
        assertEquals(6000, endpoint.live().get(0).delayMs);
    }

    @Test
    public void aBlockingCommandThatWaitsForeverHasNoTimeout() {
        handler.setCommandTimeoutMillis(1000);
        session.blpop(0, new Many(), "q");
        assertTrue(endpoint.timers.isEmpty());
    }

    @Test
    public void xreadWithBlockIsGivenItsWaitToo() {
        handler.setCommandTimeoutMillis(1000);
        session.xread(10, 2500, new Many(), "s", "$");
        assertEquals(3500, endpoint.live().get(0).delayMs);
    }

    @Test
    public void blockingMillisReadsEachBlockingForm() {
        assertEquals(-1, CommandTimeouts.blockingMillis("GET", new String[] { "k" }, null));
        assertEquals(1500, CommandTimeouts.blockingMillis("BRPOP",
                new String[] { "a", "b", "1.5" }, null));
        assertEquals(0, CommandTimeouts.blockingMillis("BLPOP", new String[] { "a", "0" }, null));
        assertEquals(2000, CommandTimeouts.blockingMillis("BLMOVE",
                new String[] { "s", "d", "LEFT", "RIGHT", "2" }, null));
        assertEquals(3000, CommandTimeouts.blockingMillis("BLMPOP",
                new String[] { "3", "1", "k", "LEFT" }, null));
        assertEquals(700, CommandTimeouts.blockingMillis("xread",
                new String[] { "COUNT", "5", "block", "700", "STREAMS", "s", "$" }, null));
        assertEquals(-1, CommandTimeouts.blockingMillis("XREAD",
                new String[] { "STREAMS", "s", "0" }, null));
        assertEquals(0, CommandTimeouts.blockingMillis("XREADGROUP",
                new String[] { "GROUP", "g", "c", "BLOCK", "0", "STREAMS", "s", ">" }, null));
        assertEquals(100, CommandTimeouts.blockingMillis("WAIT", new String[] { "1", "100" }, null));
        assertEquals(-1, CommandTimeouts.blockingMillis("BLPOP", new String[] { "a", "oops" }, null));
    }

    // ── the cap ──

    @Test
    public void aCommandBeyondTheCapFailsAtOnceWithoutBeingSent() {
        handler.setMaxPending(2);
        Bulk a = new Bulk();
        Bulk b = new Bulk();
        Bulk c = new Bulk();
        session.get("a", a);
        session.get("b", b);
        session.get("c", c);
        assertEquals(2, written());
        assertEquals(1, c.events.size());
        assertTrue(c.events.get(0), c.events.get(0).startsWith("error:OVERLOAD"));
        assertTrue(a.events.isEmpty());
    }

    @Test
    public void roomIsMadeByReplies() {
        handler.setMaxPending(1);
        session.get("a", new Bulk());
        feed("$1\r\nx\r\n");
        Bulk b = new Bulk();
        session.get("b", b);
        feed("$1\r\ny\r\n");
        assertEquals("[value:y]", b.events.toString());
    }

    @Test
    public void aTimedOutCommandStillHoldsItsPlaceUntilItsReplyArrives() {
        handler.setCommandTimeoutMillis(500);
        handler.setMaxPending(1);
        session.get("a", new Bulk());
        advance(500);
        fire();
        Bulk b = new Bulk();
        session.get("b", b);
        assertTrue(b.events.get(0), b.events.get(0).startsWith("error:OVERLOAD"));
        feed("$1\r\nx\r\n");
        Bulk c = new Bulk();
        session.get("c", c);
        feed("$1\r\nz\r\n");
        assertEquals("[value:z]", c.events.toString());
    }

    @Test
    public void commandsWithNoHandlerAndSubscriptionsAreNotRefused() {
        handler.setMaxPending(1);
        session.get("a", new Bulk());
        session.subscribe(new RedisClientSubscriptionAccountingTest.Quiet(), "ch");
        session.quit();
        assertEquals(3, written());
    }

    @Test
    public void thereIsAGenerousDefaultCap() {
        for (int i = 0; i < 1000; i++) {
            Bulk b = new Bulk();
            session.get("k" + i, b);
            assertTrue(b.events.isEmpty());
        }
    }
}
