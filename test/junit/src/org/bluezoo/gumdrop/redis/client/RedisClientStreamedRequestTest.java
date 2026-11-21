/*
 * RedisClientStreamedRequestTest.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.TimerHandle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Requests with a streamed argument: that the value is written in blocks
 * pulled as the endpoint can take them and never held, in order with the
 * commands around it, that a stream with nothing to give waits to be told,
 * that a source that fails or ends short closes the connection, that a source
 * that cannot be read again fails its command, and that the command's clock
 * starts once it has been written.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientStreamedRequestTest {

    /**
     * An endpoint that counts what is sent and keeps only its start and end,
     * and that holds unsent bytes until it is told to drain, like a peer
     * that reads slowly.
     */
    static final class Wire extends RedisClientProtocolHandlerTest.StubEndpoint {
        final StringBuilder head = new StringBuilder();
        final StringBuilder all = new StringBuilder();
        boolean keepAll;
        long total;
        byte last1;
        byte last2;
        // bytes accepted and not yet "written to the network"
        int unsent;
        int maxUnsent;
        // bytes the peer takes each time it is told to drain; 0 = all
        Runnable writeReady;
        final List<RedisClientCommandTimeoutTest.Timer> timers =
                new ArrayList<RedisClientCommandTimeoutTest.Timer>();

        @Override
        public void send(ByteBuffer buf) {
            int n = buf.remaining();
            while (buf.hasRemaining()) {
                byte b = buf.get();
                if (head.length() < 200) {
                    head.append((char) (b & 0xff));
                }
                if (keepAll) {
                    all.append((char) (b & 0xff));
                }
                last2 = last1;
                last1 = b;
            }
            total += n;
            unsent += n;
            maxUnsent = Math.max(maxUnsent, unsent);
        }

        @Override
        public int pendingWriteBytes() {
            return unsent;
        }

        @Override
        public void onWriteReady(Runnable callback) {
            writeReady = callback;
        }

        /** The peer reads everything, and the endpoint says so. */
        void drain() {
            unsent = 0;
            Runnable r = writeReady;
            writeReady = null;
            if (r != null) {
                r.run();
            }
        }

        @Override
        public TimerHandle scheduleTimer(long delayMs, Runnable callback) {
            RedisClientCommandTimeoutTest.Timer t =
                    new RedisClientCommandTimeoutTest.Timer(delayMs, callback);
            timers.add(t);
            return t;
        }

        RedisClientCommandTimeoutTest.Timer live() {
            RedisClientCommandTimeoutTest.Timer found = null;
            for (RedisClientCommandTimeoutTest.Timer t : timers) {
                if (!t.cancelled) {
                    assertNull("one live timer", found);
                    found = t;
                }
            }
            return found;
        }
    }

    /** A source of {@code length} bytes of 'x', generated as it is read. */
    private static class Generated implements ArgumentSource {
        final long length;
        int opens;
        boolean oneShot;
        // when set, the first read gives nothing and waits to be told
        boolean startsEmpty;
        ArgumentSink sink;
        long produced;
        boolean closed;
        // fail after this many bytes
        long failAt = -1;
        // end after this many bytes, short of the length
        long endAt = -1;

        Generated(long length) {
            this.length = length;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public ArgumentStream open(ArgumentSink s) {
            opens++;
            if (oneShot && opens > 1) {
                return null;
            }
            sink = s;
            produced = 0;
            closed = false;
            return new ArgumentStream() {
                private boolean first = true;

                @Override
                public int read(ByteBuffer dst) throws IOException {
                    if (first && startsEmpty) {
                        first = false;
                        return 0;
                    }
                    first = false;
                    if (failAt >= 0 && produced >= failAt) {
                        throw new IOException("disk gone");
                    }
                    if (endAt >= 0 && produced >= endAt) {
                        return -1;
                    }
                    long left = length - produced;
                    if (endAt >= 0) {
                        left = Math.min(left, endAt - produced);
                    }
                    if (failAt >= 0) {
                        left = Math.min(left, failAt - produced);
                    }
                    int n = (int) Math.min(dst.remaining(), left);
                    for (int i = 0; i < n; i++) {
                        dst.put((byte) 'x');
                    }
                    produced += n;
                    return n == 0 && produced >= length ? -1 : n;
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }
    }

    private static final class Str implements StringResultHandler {
        final List<String> log = new ArrayList<String>();

        @Override
        public void handleResult(String result, RedisSession s) {
            log.add("ok:" + result);
        }

        @Override
        public void handleError(String error, RedisSession s) {
            log.add("error:" + error.split(" ")[0]);
        }
    }

    private RedisClientProtocolHandler handler;
    private Wire wire;
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
        wire = new Wire();
        handler.connected(wire);
        session = ref.get();
        now = 1000000000L;
        handler.setTicker(new RedisClientProtocolHandler.Ticker() {
            @Override
            public long nanos() {
                return now;
            }
        });
    }

    private void feed(String resp) {
        handler.receive(ByteBuffer.wrap(resp.getBytes(StandardCharsets.ISO_8859_1)));
    }

    // ── what is written ──

    @Test
    public void theWireIsTheSameAsForAValueHeldInMemory() {
        wire.keepAll = true;
        session.set("key", ArgumentSource.of("hello".getBytes(StandardCharsets.UTF_8)), new Str());
        String streamed = wire.all.toString();
        setUp();
        wire.keepAll = true;
        session.set("key", "hello".getBytes(StandardCharsets.UTF_8), new Str());
        assertEquals(wire.all.toString(), streamed);
        assertEquals("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nhello\r\n", streamed);
    }

    @Test
    public void optionsFollowTheValue() {
        wire.keepAll = true;
        session.set("k", ArgumentSource.of(new byte[] { 'v' }),
                new SetOptions().ex(10), new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertEquals("*5\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n$2\r\nEX\r\n$2\r\n10\r\n",
                wire.all.toString());
    }

    @Test
    public void anEmptyValueIsWritten() {
        wire.keepAll = true;
        session.set("k", ArgumentSource.of(new byte[0]), new Str());
        assertEquals("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$0\r\n\r\n", wire.all.toString());
    }

    @Test
    public void aReplyAfterAStreamedWriteIsDelivered() {
        Str str = new Str();
        session.set("k", ArgumentSource.of(new byte[] { 'v' }), str);
        feed("+OK\r\n");
        assertEquals("[ok:OK]", str.log.toString());
    }

    // ── blocks, and constant memory ──

    @Test
    public void aValueLargerThanMemoryIsWrittenInBoundedBlocksAndNeverHeld() {
        long size = 1L << 30;
        Generated value = new Generated(size);
        Str str = new Str();
        session.set("big", value, str);
        // the peer reads a block at a time, so the writer is made to wait
        int rounds = 0;
        while (value.produced < size && rounds < 10000000) {
            wire.drain();
            rounds++;
        }
        wire.drain();
        assertEquals(size, value.produced);
        String start = "*3\r\n$3\r\nSET\r\n$3\r\nbig\r\n$" + size + "\r\n";
        assertTrue(wire.head.toString().startsWith(start));
        assertEquals(start.length() + size + 2, wire.total);
        assertEquals('\n', wire.last1);
        assertEquals('\r', wire.last2);
        assertTrue("the endpoint never held more than the limit and a block: " + wire.maxUnsent,
                wire.maxUnsent <= RequestWriter.HIGH_WATER + RequestWriter.BLOCK);
        assertTrue(value.closed);
        feed("+OK\r\n");
        assertEquals("[ok:OK]", str.log.toString());
    }

    @Test
    public void aStreamWithNothingToGiveWaitsToBeToldAndThenContinues() {
        Generated value = new Generated(100);
        value.startsEmpty = true;
        Str str = new Str();
        session.set("k", value, str);
        long before = wire.total;
        assertEquals("only the head is out", 0, value.produced);
        value.sink.ready();
        assertEquals(100, value.produced);
        assertTrue(wire.total > before + 100);
        assertTrue(value.closed);
    }

    @Test
    public void commandsSentMeanwhileGoOutAfterTheStreamedOneInOrder() {
        wire.keepAll = true;
        Generated value = new Generated(3);
        value.startsEmpty = true;
        Str a = new Str();
        session.set("a", value, a);
        session.get("b", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertFalse("not before the value is written", wire.all.toString().contains("GET"));
        value.sink.ready();
        String w = wire.all.toString();
        int set = w.indexOf("SET");
        int get = w.indexOf("GET");
        assertTrue(set >= 0 && get > set);
        assertTrue(w.contains("xxx\r\n*2\r\n$3\r\nGET"));
        feed("+OK\r\n$-1\r\n");
        assertEquals("[ok:OK]", a.log.toString());
    }

    // ── failure ──

    @Test
    public void aSourceThatFailsMidValueClosesTheConnectionAndFailsTheCommandsBehind() {
        Generated value = new Generated(100);
        value.failAt = 40;
        Str a = new Str();
        Str b = new Str();
        session.set("a", value, a);
        session.set("b", "v", b);
        assertEquals("[error:ARGUMENT]", a.log.toString());
        assertEquals("[error:Not]", b.log.toString());
        assertFalse(wire.open);
        assertEquals(1, disconnects);
        assertTrue(value.closed);
    }

    @Test
    public void aSourceThatEndsShortIsTheSame() {
        Generated value = new Generated(100);
        value.endAt = 10;
        Str a = new Str();
        session.set("a", value, a);
        assertEquals("[error:ARGUMENT]", a.log.toString());
        assertFalse(wire.open);
    }

    @Test
    public void aSourceThatFailsLaterThroughItsSinkIsTheSame() {
        Generated value = new Generated(100);
        value.startsEmpty = true;
        Str a = new Str();
        session.set("a", value, a);
        value.sink.fail(new IOException("gone"));
        assertEquals("[error:ARGUMENT]", a.log.toString());
        assertFalse(wire.open);
    }

    @Test
    public void aSourceThatCannotBeReadAgainFailsItsSecondAttempt() {
        Generated value = new Generated(3);
        value.oneShot = true;
        final List<String> log = new ArrayList<String>();
        StringResultHandler h = new StringResultHandler() {
            @Override
            public void handleResult(String result, RedisSession s) {
                log.add("ok");
            }

            @Override
            public void handleError(String error, RedisSession s) {
                log.add(error.split(" ")[0]);
            }
        };
        byte[][] args = new byte[][] { "k".getBytes(StandardCharsets.UTF_8), new byte[0] };
        SentCommand command = new SentCommand(h, "SET", null, args).withStream(1, value);
        handler.send(command);
        long afterFirst = wire.total;
        handler.send(command);
        assertEquals("[NOT_REPEATABLE]", log.toString());
        assertEquals("nothing of the second attempt is written", afterFirst, wire.total);
        assertTrue(wire.open);
    }

    @Test
    public void aRepeatableSourceIsOpenedAfreshForEachAttempt() {
        Generated value = new Generated(3);
        byte[][] args = new byte[][] { "k".getBytes(StandardCharsets.UTF_8), new byte[0] };
        SentCommand command = new SentCommand(null, "SET", null, args).withStream(1, value);
        handler.send(command);
        handler.send(command);
        assertEquals(2, value.opens);
    }

    // ── clocks ──

    @Test
    public void theCommandTimeoutStartsOnceTheValueHasBeenWritten() {
        handler.setCommandTimeoutMillis(500);
        Generated value = new Generated(100);
        value.startsEmpty = true;
        Str a = new Str();
        Str b = new Str();
        session.set("a", value, a);
        session.get("b", new RedisClientProtocolHandlerTest.StubBulkHandler());
        assertNull("no clock while the client is still sending", wire.live());
        now += 60000L * 1000000L;
        value.sink.ready();
        RedisClientCommandTimeoutTest.Timer t = wire.live();
        assertNotNull("armed once written", t);
        assertEquals(500, t.delayMs);
    }

    @Test
    public void theHealthCheckDoesNotPingWhileTheClientIsStillSending() {
        handler.setHealthCheckMillis(1000);
        Generated value = new Generated(100);
        value.startsEmpty = true;
        session.set("a", value, new Str());
        assertNull(wire.live());
        value.sink.ready();
        assertNotNull(wire.live());
    }

}
