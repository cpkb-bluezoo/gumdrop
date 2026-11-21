/*
 * RedisClientStreamingTest.java
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

import org.bluezoo.gumdrop.TimerHandle;
import org.bluezoo.gumdrop.redis.codec.ReplyFlow;
import org.bluezoo.gumdrop.redis.codec.RespType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Streaming replies on one connection: payloads as chunks, the shapes of
 * handler, skipping and cancelling, pausing and resuming, and how the
 * command timeout, the health check and a lost connection behave while a
 * reply is being streamed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientStreamingTest {

    /** An endpoint that records the timers asked of it and the reads paused. */
    static final class Ep extends RedisClientProtocolHandlerTest.StubEndpoint {
        final List<RedisClientCommandTimeoutTest.Timer> timers =
                new ArrayList<RedisClientCommandTimeoutTest.Timer>();
        int pauses;
        int resumes;

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            RedisClientCommandTimeoutTest.Timer t =
                    new RedisClientCommandTimeoutTest.Timer(delayMs, callback);
            timers.add(t);
            return t;
        }

        @Override
        public void pauseRead() {
            pauses++;
        }

        @Override
        public void resumeRead() {
            resumes++;
        }
    }

    /** A bulk handler that records what it is told, and chooses what to do after the first chunk. */
    private static class Rec implements BulkResultHandler {
        final List<String> log = new ArrayList<String>();
        ReplyFlow afterBegin = ReplyFlow.CONTINUE;
        ReplyFlow afterFirstChunk = ReplyFlow.CONTINUE;
        boolean cancelAtFirstChunk;
        ReplyStream stream;
        long total;
        int chunks;
        int largest;

        @Override
        public void handleNull() {
            log.add("null");
        }

        @Override
        public ReplyFlow handleBegin(long length, ReplyStream s) {
            stream = s;
            log.add("begin:" + length);
            return afterBegin;
        }

        @Override
        public ReplyFlow handleContent(ByteBuffer chunk) {
            chunks++;
            total += chunk.remaining();
            largest = Math.max(largest, chunk.remaining());
            if (chunks == 1) {
                if (cancelAtFirstChunk) {
                    stream.cancel();
                }
                return afterFirstChunk;
            }
            return ReplyFlow.CONTINUE;
        }

        @Override
        public void handleEnd() {
            log.add("end");
        }

        @Override
        public void handleComplete(String error, RedisSession session) {
            log.add(error == null ? "complete" : "complete:" + error.split(" ")[0]);
        }
    }

    private RedisClientProtocolHandler handler;
    private Ep endpoint;
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
        endpoint = new Ep();
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

    private ByteBuffer feed(String resp) {
        ByteBuffer buf = ByteBuffer.wrap(resp.getBytes(StandardCharsets.ISO_8859_1));
        handler.receive(buf);
        return buf;
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

    private String written() {
        StringBuilder sb = new StringBuilder();
        for (ByteBuffer b : endpoint.sentBuffers) {
            byte[] bytes = new byte[b.remaining()];
            b.duplicate().get(bytes);
            sb.append(new String(bytes, StandardCharsets.ISO_8859_1));
        }
        return sb.toString();
    }

    // ── payloads ──

    @Test
    public void aLargePayloadArrivesAsChunksAndIsNotHeld() {
        Rec rec = new Rec();
        session.get("k", rec);
        int size = 3 * 1024 * 1024;
        feed("$" + size + "\r\n");
        byte[] piece = new byte[16 * 1024];
        for (int sent = 0; sent < size; sent += piece.length) {
            ByteBuffer b = ByteBuffer.wrap(piece);
            handler.receive(b);
            assertFalse("everything is consumed", b.hasRemaining());
        }
        feed("\r\n");
        assertEquals(size, rec.total);
        assertEquals(piece.length, rec.largest);
        assertEquals("[begin:" + size + ", end, complete]", rec.log.toString());
    }

    @Test
    public void aNullReplyIsNullThenComplete() {
        Rec rec = new Rec();
        session.get("k", rec);
        feed("$-1\r\n");
        assertEquals("[null, complete]", rec.log.toString());
    }

    @Test
    public void anErrorReplyIsOnlyTheCompletion() {
        Rec rec = new Rec();
        session.get("k", rec);
        feed("-WRONGTYPE bad\r\n");
        assertEquals("[complete:WRONGTYPE]", rec.log.toString());
    }

    @Test
    public void aBlobErrorIsAnErrorToo() {
        Rec rec = new Rec();
        session.get("k", rec);
        feed("!11\r\nERR unknown\r\n");
        assertEquals("[complete:ERR]", rec.log.toString());
    }

    // ── skipping ──

    @Test
    public void skippingFailsOnlyThatCommand() {
        Rec a = new Rec();
        Rec b = new Rec();
        b.afterFirstChunk = ReplyFlow.SKIP;
        Rec c = new Rec();
        session.get("a", a);
        session.get("b", b);
        session.get("c", c);
        feed("$5\r\nhello\r\n$5\r\nworld\r\n$1\r\nC\r\n");
        assertEquals("[begin:5, end, complete]", a.log.toString());
        assertEquals("[begin:5, complete:CANCELLED]", b.log.toString());
        assertEquals("[begin:1, end, complete]", c.log.toString());
        assertTrue(endpoint.open);
    }

    @Test
    public void skippingAtTheBeginDiscardsThePayloadInAnyPieces() {
        Rec b = new Rec();
        b.afterBegin = ReplyFlow.SKIP;
        Rec c = new Rec();
        session.get("b", b);
        session.get("c", c);
        String wire = "$12\r\n0123456789ab\r\n$1\r\nC\r\n";
        for (int i = 0; i < wire.length(); i++) {
            feed(wire.substring(i, i + 1));
        }
        assertEquals("[begin:12, complete:CANCELLED]", b.log.toString());
        assertEquals(0, b.chunks);
        assertEquals("[begin:1, end, complete]", c.log.toString());
    }

    /** An array handler that records, and skips at the element named. */
    private static final class Elements implements ArrayResultHandler {
        final List<String> log = new ArrayList<String>();
        int skipAtElement = -1;
        int elements;

        @Override
        public void handleNull() {
            log.add("null");
        }

        @Override
        public ReplyFlow handleBegin(long count, ReplyStream stream) {
            log.add("begin:" + count);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow handleElementNull() {
            log.add("element:null");
            return next();
        }

        @Override
        public ReplyFlow handleElementBegin(long length) {
            log.add("element:" + length);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow handleElementContent(ByteBuffer chunk) {
            chunk.position(chunk.limit());
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow handleElementEnd() {
            return next();
        }

        private ReplyFlow next() {
            return ++elements == skipAtElement ? ReplyFlow.SKIP : ReplyFlow.CONTINUE;
        }

        @Override
        public void handleEnd() {
            log.add("end");
        }

        @Override
        public void handleComplete(String error, RedisSession session) {
            log.add(error == null ? "complete" : "complete:" + error.split(" ")[0]);
        }
    }

    @Test
    public void skippingInsideAnMgetFailsOnlyTheMget() {
        Elements e = new Elements();
        e.skipAtElement = 2;
        Rec after = new Rec();
        session.mget(e, "a", "b", "c");
        session.get("z", after);
        feed("*3\r\n$1\r\nA\r\n$-1\r\n$1\r\nC\r\n$1\r\nZ\r\n");
        assertEquals("[begin:3, element:1, element:null, complete:CANCELLED]", e.log.toString());
        assertEquals("[begin:1, end, complete]", after.log.toString());
    }

    /** A map handler. */
    private static final class Pairs implements MapResultHandler {
        final List<String> log = new ArrayList<String>();
        String skipAtField;

        @Override
        public void handleNull() {
            log.add("null");
        }

        @Override
        public ReplyFlow handleBegin(long pairs, ReplyStream stream) {
            log.add("begin:" + pairs);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow handleField(String field) {
            log.add("field:" + field);
            return field.equals(skipAtField) ? ReplyFlow.SKIP : ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow handleValueNull() {
            log.add("value:null");
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow handleValueBegin(long length) {
            log.add("value:" + length);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow handleValueContent(ByteBuffer chunk) {
            chunk.position(chunk.limit());
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow handleValueEnd() {
            return ReplyFlow.CONTINUE;
        }

        @Override
        public void handleEnd() {
            log.add("end");
        }

        @Override
        public void handleComplete(String error, RedisSession session) {
            log.add(error == null ? "complete" : "complete:" + error.split(" ")[0]);
        }
    }

    @Test
    public void aMapIsPairsWhetherTheServerSendsAMapOrFlatPairs() {
        Pairs resp2 = new Pairs();
        Pairs resp3 = new Pairs();
        session.hgetall("h", resp2);
        session.hgetall("h", resp3);
        feed("*4\r\n$1\r\nf\r\n$1\r\nv\r\n$1\r\ng\r\n$-1\r\n%2\r\n+f\r\n$1\r\nv\r\n+g\r\n_\r\n");
        String expected = "[begin:2, field:f, value:1, field:g, value:null, end, complete]";
        assertEquals(expected, resp2.log.toString());
        assertEquals(expected, resp3.log.toString());
    }

    @Test
    public void skippingInsideAnHgetallFailsOnlyTheHgetall() {
        Pairs p = new Pairs();
        p.skipAtField = "g";
        Rec after = new Rec();
        session.hgetall("h", p);
        session.get("z", after);
        feed("*6\r\n$1\r\nf\r\n$1\r\nv\r\n$1\r\ng\r\n$1\r\nw\r\n$1\r\nh\r\n$1\r\nx\r\n$1\r\nZ\r\n");
        assertEquals("[begin:3, field:f, value:1, field:g, complete:CANCELLED]", p.log.toString());
        assertEquals("[begin:1, end, complete]", after.log.toString());
    }

    // ── identifiers ──

    private static final class Keys implements KeyArrayResultHandler {
        final List<String> log = new ArrayList<String>();

        @Override
        public void handleNull() {
            log.add("null");
        }

        @Override
        public ReplyFlow handleBegin(long count, ReplyStream stream) {
            log.add("begin:" + count);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow handleKey(String key) {
            log.add(key);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public void handleEnd() {
            log.add("end");
        }

        @Override
        public void handleComplete(String error, RedisSession session) {
            log.add(error == null ? "complete" : "complete:" + error.split(" ")[0]);
        }
    }

    @Test
    public void keysAreDeliveredWholeInAnySplit() {
        Keys k = new Keys();
        session.keys("*", k);
        String wire = "*3\r\n$3\r\nabc\r\n$0\r\n\r\n$5\r\nhello\r\n";
        for (int i = 0; i < wire.length(); i++) {
            feed(wire.substring(i, i + 1));
        }
        assertEquals("[begin:3, abc, , hello, end, complete]", k.log.toString());
    }

    @Test
    public void anIdentifierOverTheLimitFailsItsOwnReplyOnly() {
        handler.setMaxField(8);
        Keys k = new Keys();
        Rec after = new Rec();
        session.keys("*", k);
        session.get("z", after);
        feed("*3\r\n$3\r\nabc\r\n$17\r\nthis-is-too-long!\r\n$3\r\nxyz\r\n$1\r\nZ\r\n");
        assertEquals("[begin:3, abc, complete:TOOLARGE]", k.log.toString());
        assertEquals("[begin:1, end, complete]", after.log.toString());
        assertTrue(endpoint.open);
    }

    @Test
    public void anIdentifierOverTheLimitOfUnknownLengthFailsToo() {
        handler.setMaxField(8);
        Keys k = new Keys();
        session.keys("*", k);
        feed("*1\r\n$?\r\n;6\r\nabcdef\r\n;6\r\nghijkl\r\n;0\r\n");
        assertEquals("[begin:1, complete:TOOLARGE]", k.log.toString());
        assertTrue(endpoint.open);
    }

    private static final class Scan implements ScanResultHandler {
        final List<String> log = new ArrayList<String>();

        @Override
        public ReplyFlow handleCursor(String cursor, ReplyStream stream) {
            log.add("cursor:" + cursor);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow handleKey(String key) {
            log.add(key);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public void handleEnd() {
            log.add("end");
        }

        @Override
        public void handleComplete(String error, RedisSession session) {
            log.add(error == null ? "complete" : "complete:" + error.split(" ")[0]);
        }
    }

    @Test
    public void aScanBatchIsACursorThenKeys() {
        Scan s = new Scan();
        session.scan("0", s);
        feed("*2\r\n$2\r\n17\r\n*2\r\n$1\r\na\r\n$1\r\nb\r\n");
        assertEquals("[cursor:17, a, b, end, complete]", s.log.toString());
    }

    // ── any shape ──

    private static final class Events extends ControlReplies.Acknowledgement {
        final List<String> log = new ArrayList<String>();

        @Override
        void done(String error, RedisSession s) {
            log.add(error == null ? "complete" : "complete:" + error.split(" ")[0]);
        }

        @Override
        public void beginReply(boolean push) {
            log.add("begin");
        }

        @Override
        public void endReply(boolean skipped) {
            log.add("end");
        }

        @Override
        public ReplyFlow integer(long value) {
            log.add("int:" + value);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow beginAggregate(RespType kind, long length) {
            log.add("agg:" + length);
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow endAggregate() {
            log.add("endagg");
            return ReplyFlow.CONTINUE;
        }

        @Override
        public ReplyFlow error(String message) {
            log.add("error:" + message.split(" ")[0]);
            return ReplyFlow.CONTINUE;
        }
    }

    @Test
    public void anEventHandlerHearsTheReplyAsItIsRead() {
        Events e = new Events();
        session.exec(e);
        feed("*2\r\n:1\r\n-ERR inside\r\n");
        assertEquals("[begin, agg:2, int:1, error:ERR, endagg, end, complete]", e.log.toString());
    }

    @Test
    public void anErrorThatIsTheWholeReplyIsOnlyTheCompletion() {
        Events e = new Events();
        session.exec(e);
        feed("-EXECABORT failed\r\n");
        assertEquals("[complete:EXECABORT]", e.log.toString());
    }

    // ── pause and resume ──

    @Test
    public void aPausedReplyStopsReadingHoldsTheRestAndResumesTheSameReply() {
        Rec a = new Rec();
        a.afterFirstChunk = ReplyFlow.PAUSE;
        Rec b = new Rec();
        session.get("a", a);
        session.get("b", b);
        ByteBuffer first = feed("$10\r\n0123456789\r\n$1\r\nZ\r\n");
        assertEquals("the rest is taken from the endpoint's buffer", 0, first.remaining());
        assertEquals(1, endpoint.pauses);
        assertEquals("[begin:10]", a.log.toString());
        // what was already on its way is kept
        feed("+extra\r\n");
        assertEquals("[begin:10]", a.log.toString());
        assertEquals(0, endpoint.resumes);
        a.stream.resume();
        assertEquals("[begin:10, end, complete]", a.log.toString());
        assertEquals("[begin:1, end, complete]", b.log.toString());
        assertEquals(1, endpoint.resumes);
    }

    @Test
    public void resumingFromInsideTheEventThatPausedDoesNotStop() {
        Rec a = new Rec() {
            @Override
            public ReplyFlow handleContent(ByteBuffer chunk) {
                ReplyFlow f = super.handleContent(chunk);
                stream.resume();
                return ReplyFlow.PAUSE;
            }
        };
        session.get("a", a);
        feed("$3\r\nabc\r\n");
        assertEquals("[begin:3, end, complete]", a.log.toString());
        assertEquals(0, endpoint.pauses);
    }

    @Test
    public void cancellingAPausedReplyDiscardsTheRestAndTheConnectionSurvives() {
        Rec a = new Rec();
        a.afterFirstChunk = ReplyFlow.PAUSE;
        Rec b = new Rec();
        session.get("a", a);
        session.get("b", b);
        feed("$10\r\n0123456789\r\n$1\r\nZ\r\n");
        a.stream.cancel();
        assertEquals("[begin:10, complete:CANCELLED]", a.log.toString());
        assertEquals("[begin:1, end, complete]", b.log.toString());
        assertTrue(endpoint.open);
    }

    @Test
    public void cancellingFromInsideTheHandlerDiscardsTheRest() {
        Rec a = new Rec();
        a.cancelAtFirstChunk = true;
        Rec b = new Rec();
        session.get("a", a);
        session.get("b", b);
        feed("$10\r\n0123456789\r\n$1\r\nZ\r\n");
        assertEquals("[begin:10, complete:CANCELLED]", a.log.toString());
        assertEquals("[begin:1, end, complete]", b.log.toString());
    }

    // ── timeouts ──

    @Test
    public void aReplyThatKeepsArrivingIsNotTimedOutAndSilenceIs() {
        handler.setCommandTimeoutMillis(500);
        Rec a = new Rec();
        Rec b = new Rec();
        session.get("a", a);
        feed("$10\r\n01234");
        advance(400);
        feed("56");
        advance(400);
        // the original deadline has passed, but it was heard from 400 ms ago
        fire();
        assertEquals("[begin:10]", a.log.toString());
        advance(100);
        fire();
        assertEquals("[begin:10, complete:TIMEOUT]", a.log.toString());
        // the rest is discarded, and what follows is the next reply
        session.get("b", b);
        feed("789\r\n");
        feed("$1\r\nZ\r\n");
        assertEquals("[begin:10, complete:TIMEOUT]", a.log.toString());
        assertEquals("[begin:1, end, complete]", b.log.toString());
        assertTrue(endpoint.open);
    }

    @Test
    public void aPausedReplyHasNoDeadlineAndTheClockStartsAgainOnResume() {
        handler.setCommandTimeoutMillis(500);
        Rec a = new Rec();
        a.afterFirstChunk = ReplyFlow.PAUSE;
        session.get("a", a);
        feed("$10\r\n01234");
        advance(60000);
        // the timer set for the deadline finds nothing to time out
        fire();
        assertEquals("[begin:10]", a.log.toString());
        assertNull(live());
        a.stream.resume();
        assertNotNull(live());
        assertEquals(500, live().delayMs);
        advance(500);
        fire();
        assertEquals("[begin:10, complete:TIMEOUT]", a.log.toString());
    }

    @Test
    public void aReplyThatCompletesLeavesNoDeadline() {
        handler.setCommandTimeoutMillis(500);
        Rec a = new Rec();
        session.get("a", a);
        feed("$3\r\nabc\r\n");
        advance(60000);
        fire();
        assertEquals("[begin:3, end, complete]", a.log.toString());
    }

    // ── health ──

    private static final String PING = "*1\r\n$4\r\nPING\r\n";

    @Test
    public void aStreamWhoseServerGoesSilentIsPingedAndThenDropped() {
        handler.setHealthCheckMillis(1000);
        Rec a = new Rec();
        session.get("a", a);
        feed("$10\r\n01234");
        advance(1000);
        fire();
        assertTrue(written().contains(PING));
        advance(1000);
        fire();
        assertFalse(endpoint.open);
        assertEquals(1, disconnects);
        assertEquals("[begin:10, complete:CONNLOST]", a.log.toString());
    }

    @Test
    public void aPausedConnectionIsNotPingedOrDropped() {
        handler.setHealthCheckMillis(1000);
        Rec a = new Rec();
        a.afterFirstChunk = ReplyFlow.PAUSE;
        session.get("a", a);
        feed("$10\r\n01234");
        advance(10000);
        RedisClientCommandTimeoutTest.Timer t = live();
        if (t != null) {
            t.cancelled = true;
            t.task.run();
        }
        assertFalse(written().contains("PING"));
        assertTrue(endpoint.open);
        a.stream.resume();
        assertNotNull("checking starts again", live());
    }

    // ── connection lost ──

    @Test
    public void aConnectionLostMidReplyFailsTheCommandAndIsNotRepeated() {
        Rec a = new Rec();
        Rec b = new Rec();
        session.get("a", a);
        session.get("b", b);
        feed("$10\r\n01234");
        handler.disconnected();
        assertEquals("[begin:10, complete:CONNLOST]", a.log.toString());
        // the command behind it never started, so it is simply not connected
        assertEquals("[complete:Not]", b.log.toString());
    }

}
