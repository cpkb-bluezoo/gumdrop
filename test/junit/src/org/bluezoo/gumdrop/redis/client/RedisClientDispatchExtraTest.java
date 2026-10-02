/*
 * RedisClientDispatchExtraTest.java
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

import org.bluezoo.gumdrop.redis.codec.RespValue;

import static org.junit.Assert.*;

/**
 * Additional response-dispatch tests for {@link RedisClientProtocolHandler}:
 * Pub/Sub delivery, RESP3 push and map handling, error routing for every
 * handler type, and connection lifecycle.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientDispatchExtraTest {

    private RedisClientProtocolHandler handler;
    private RedisClientProtocolHandlerTest.StubEndpoint endpoint;
    private RedisSession session;
    private Lifecycle lifecycle;

    @Before
    public void setUp() {
        AtomicReference<RedisSession> ref = new AtomicReference<RedisSession>();
        lifecycle = new Lifecycle(ref);
        handler = new RedisClientProtocolHandler(lifecycle);
        endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        handler.connected(endpoint);
        session = ref.get();
    }

    private void feed(String resp) {
        handler.receive(ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8)));
    }

    // ── Pub/Sub ──

    @Test
    public void resp2PubSubMessagesAreRoutedToMessageHandler() {
        Messages m = new Messages();
        session.subscribe(m, "chan");
        session.psubscribe(m, "pat*");
        feed("*3\r\n$9\r\nsubscribe\r\n$4\r\nchan\r\n:1\r\n");
        feed("*3\r\n$10\r\npsubscribe\r\n$4\r\npat*\r\n:2\r\n");
        feed("*3\r\n$7\r\nmessage\r\n$4\r\nchan\r\n$2\r\nhi\r\n");
        feed("*4\r\n$8\r\npmessage\r\n$4\r\npat*\r\n$4\r\nchan\r\n$2\r\nyo\r\n");
        assertEquals("sub:chan:1", m.events.get(0));
        assertEquals("psub:pat*:2", m.events.get(1));
        assertEquals("msg:chan:hi", m.events.get(2));
        assertEquals("pmsg:pat*:chan:yo", m.events.get(3));
    }

    @Test
    public void unsubscribeToZeroLeavesPubSubMode() {
        Messages m = new Messages();
        session.subscribe(m, "chan");
        feed("*3\r\n$11\r\nunsubscribe\r\n$4\r\nchan\r\n:0\r\n");
        assertEquals("unsub:chan:0", m.events.get(0));
        // no longer in pub/sub mode: array falls through to FIFO, nothing
        // pending, so it is ignored without error
        feed("*3\r\n$7\r\nmessage\r\n$4\r\nchan\r\n$2\r\nhi\r\n");
        assertEquals(1, m.events.size());
    }

    @Test
    public void punsubscribeToZeroLeavesPubSubMode() {
        Messages m = new Messages();
        session.psubscribe(m, "p*");
        feed("*3\r\n$12\r\npunsubscribe\r\n$2\r\np*\r\n:0\r\n");
        assertEquals("punsub:p*:0", m.events.get(0));
        feed("*3\r\n$7\r\nmessage\r\n$4\r\nchan\r\n$2\r\nhi\r\n");
        assertEquals(1, m.events.size());
    }

    @Test
    public void unsubscribeWithRemainingSubscriptionsStaysInMode() {
        Messages m = new Messages();
        session.subscribe(m, "a", "b");
        feed("*3\r\n$11\r\nunsubscribe\r\n$1\r\na\r\n:1\r\n");
        feed("*3\r\n$12\r\npunsubscribe\r\n$1\r\nb\r\n:1\r\n");
        feed("*3\r\n$7\r\nmessage\r\n$1\r\nb\r\n$1\r\nx\r\n");
        assertEquals(3, m.events.size());
    }

    @Test
    public void unknownPubSubTypeFallsThroughToPending() {
        Messages m = new Messages();
        session.subscribe(m, "chan");
        // a short array with an unrecognised type is not a pubsub message
        feed("*2\r\n$5\r\nhello\r\n$5\r\nworld\r\n");
        assertEquals(0, m.events.size());
    }

    @Test
    public void unsubscribeAndPunsubscribeCommands() {
        session.unsubscribe();
        session.unsubscribe("a");
        session.punsubscribe();
        session.punsubscribe("b");
        assertEquals(4, endpoint.sentBuffers.size());
    }

    @Test
    public void resp3PushDeliveredToMessageHandler() {
        Messages m = new Messages();
        session.subscribe(m, "chan");
        feed(">3\r\n$7\r\nmessage\r\n$4\r\nchan\r\n$2\r\nhi\r\n");
        assertEquals("msg:chan:hi", m.events.get(0));
    }

    @Test
    public void resp3PushWithoutHandlerIsDropped() {
        feed(">3\r\n$7\r\nmessage\r\n$4\r\nchan\r\n$2\r\nhi\r\n");
        assertTrue(endpoint.open);
    }

    // ── Dispatch by handler type ──

    @Test
    public void unexpectedResponseWithoutPendingCommandIsIgnored() {
        feed("+OK\r\n");
        assertTrue(endpoint.open);
    }

    @Test
    public void booleanHandlerReceivesTrueAndFalse() {
        final List<Boolean> seen = new ArrayList<Boolean>();
        BooleanResultHandler h = new BooleanResultHandler() {
            @Override
            public void handleResult(boolean value, RedisSession s) {
                seen.add(Boolean.valueOf(value));
            }

            @Override
            public void handleError(String error, RedisSession s) {
                seen.add(null);
            }
        };
        session.setnx("a", "b", h);
        session.exists("a", h);
        session.persist("a", h);
        feed(":1\r\n:0\r\n-ERR no\r\n");
        assertEquals(Boolean.TRUE, seen.get(0));
        assertEquals(Boolean.FALSE, seen.get(1));
        assertNull(seen.get(2));
    }

    @Test
    public void arrayHandlerHandlesNullArrayMapAndScalar() {
        final List<String> seen = new ArrayList<String>();
        ArrayResultHandler h = new ArrayResultHandler() {
            @Override
            public void handleResult(List<RespValue> array, RedisSession s) {
                seen.add("array:" + array.size());
            }

            @Override
            public void handleNull(RedisSession s) {
                seen.add("null");
            }

            @Override
            public void handleError(String error, RedisSession s) {
                seen.add("error:" + error);
            }
        };
        session.keys("*", h);
        session.keys("*", h);
        session.keys("*", h);
        session.keys("*", h);
        session.keys("*", h);
        feed("*-1\r\n");
        feed("*2\r\n+a\r\n+b\r\n");
        feed("%1\r\n+k\r\n+v\r\n");
        feed("+scalar\r\n");
        feed("-ERR bad\r\n");
        assertEquals("null", seen.get(0));
        assertEquals("array:2", seen.get(1));
        assertEquals("array:2", seen.get(2));
        assertEquals("array:1", seen.get(3));
        assertEquals("error:ERR bad", seen.get(4));
    }

    @Test
    public void bulkHandlerNullAndValue() {
        final List<String> seen = new ArrayList<String>();
        BulkResultHandler h = new BulkResultHandler() {
            @Override
            public void handleResult(byte[] value, RedisSession s) {
                seen.add("v:" + new String(value, StandardCharsets.UTF_8));
            }

            @Override
            public void handleNull(RedisSession s) {
                seen.add("null");
            }

            @Override
            public void handleError(String error, RedisSession s) {
                seen.add("e:" + error);
            }
        };
        session.get("a", h);
        session.get("b", h);
        session.get("c", h);
        feed("$-1\r\n$2\r\nhi\r\n-ERR x\r\n");
        assertEquals("null", seen.get(0));
        assertEquals("v:hi", seen.get(1));
        assertEquals("e:ERR x", seen.get(2));
    }

    @Test
    public void integerAndStringErrorsRouted() {
        final List<String> seen = new ArrayList<String>();
        IntegerResultHandler ih = new IntegerResultHandler() {
            @Override
            public void handleResult(long value, RedisSession s) {
                seen.add("i:" + value);
            }

            @Override
            public void handleError(String error, RedisSession s) {
                seen.add("ie:" + error);
            }
        };
        StringResultHandler sh = new StringResultHandler() {
            @Override
            public void handleResult(String result, RedisSession s) {
                seen.add("s:" + result);
            }

            @Override
            public void handleError(String error, RedisSession s) {
                seen.add("se:" + error);
            }
        };
        session.incr("a", ih);
        session.incr("a", ih);
        session.ping(sh);
        session.ping(sh);
        feed(":7\r\n-ERR i\r\n+PONG\r\n-ERR s\r\n");
        assertEquals("i:7", seen.get(0));
        assertEquals("ie:ERR i", seen.get(1));
        assertEquals("s:PONG", seen.get(2));
        assertEquals("se:ERR s", seen.get(3));
    }

    @Test
    public void scanHandlerResultNullInvalidAndError() {
        final List<String> seen = new ArrayList<String>();
        ScanResultHandler h = new ScanResultHandler() {
            @Override
            public void handleResult(String cursor, List<RespValue> elements,
                                     RedisSession s) {
                seen.add("r:" + cursor + ":" + elements.size());
            }

            @Override
            public void handleError(String error, RedisSession s) {
                seen.add("e:" + error);
            }
        };
        session.scan("0", h);
        session.scan("0", h);
        session.scan("0", h);
        session.scan("0", h);
        feed("*2\r\n$1\r\n5\r\n*2\r\n+a\r\n+b\r\n");
        feed("_\r\n");
        feed("*1\r\n+x\r\n");
        feed("-ERR scan\r\n");
        assertEquals("r:5:2", seen.get(0));
        assertEquals("e:null response", seen.get(1));
        assertEquals("e:Invalid scan response", seen.get(2));
        assertEquals("e:ERR scan", seen.get(3));
    }

    @Test
    public void scanVariantsWithMatchAndCount() {
        RedisClientProtocolHandlerTest.StubScanHandler h =
                new RedisClientProtocolHandlerTest.StubScanHandler();
        session.scan("0", "m*", 10, h);
        session.scan("0", null, 0, h);
        session.hscan("k", "0", h);
        session.hscan("k", "0", "m*", 5, h);
        session.sscan("k", "0", "m*", 5, h);
        session.zscan("k", "0", null, 0, h);
        assertEquals(6, endpoint.sentBuffers.size());
    }

    @Test
    public void nullCallbackResponseIsConsumed() {
        session.unwatch(null);
        feed("+OK\r\n");
        assertTrue(endpoint.open);
    }

    // ── Lifecycle ──

    @Test
    public void commandAfterCloseReportsNotConnected() {
        handler.close();
        final List<String> errors = new ArrayList<String>();
        session.ping(new StringResultHandler() {
            @Override
            public void handleResult(String result, RedisSession s) {
                errors.add("result");
            }

            @Override
            public void handleError(String error, RedisSession s) {
                errors.add(error);
            }
        });
        assertEquals(1, errors.size());
        assertFalse(endpoint.open);
        handler.close();
    }

    @Test
    public void notConnectedErrorForEveryEncodingPath() {
        RedisClientProtocolHandler h = new RedisClientProtocolHandler(
                lifecycle);
        final List<String> errors = new ArrayList<String>();
        StringResultHandler sh = new StringResultHandler() {
            @Override
            public void handleResult(String result, RedisSession s) {
                errors.add("result");
            }

            @Override
            public void handleError(String error, RedisSession s) {
                errors.add(error);
            }
        };
        h.ping(sh);
        h.get("k", new RedisClientProtocolHandlerTest.StubBulkHandler());
        h.set("k", new byte[] {1}, sh);
        h.mset(sh, "a", "b");
        assertEquals(3, errors.size());
        h.quit();
    }

    @Test
    public void disconnectedNotifiesHandler() {
        handler.disconnected();
        assertTrue(lifecycle.disconnected);
        // commands after a disconnect fail fast
        handler.close();
    }

    @Test
    public void protocolErrorClosesConnection() {
        feed("?bogus\r\n");
        assertNotNull(lifecycle.error);
        assertFalse(endpoint.open);
    }

    @Test
    public void errorIsForwarded() {
        handler.error(new RuntimeException("boom"));
        assertEquals("boom", lifecycle.error.getMessage());
    }

    @Test
    public void securityEstablishedWithoutFineLoggingIsHarmless() {
        handler.securityEstablished(null);
    }

    @Test
    public void quitSendsAndCloses() {
        session.quit();
        assertFalse(endpoint.open);
    }

    @Test
    public void resetClearsPubSubState() {
        Messages m = new Messages();
        session.subscribe(m, "chan");
        session.reset(new RedisClientProtocolHandlerTest.StubStringHandler());
        feed("*3\r\n$7\r\nmessage\r\n$4\r\nchan\r\n$2\r\nhi\r\n");
        assertEquals(0, m.events.size());
    }

    // ── Helpers ──

    private static final class Lifecycle implements RedisConnectionReady {
        private final AtomicReference<RedisSession> ref;
        boolean disconnected;
        Exception error;

        Lifecycle(AtomicReference<RedisSession> ref) {
            this.ref = ref;
        }

        @Override
        public void handleReady(RedisSession s) {
            ref.set(s);
        }

        @Override
        public void onConnected(org.bluezoo.gumdrop.Endpoint ep) {
        }

        @Override
        public void onDisconnected() {
            disconnected = true;
        }

        @Override
        public void onSecurityEstablished(
                org.bluezoo.gumdrop.SecurityInfo info) {
        }

        @Override
        public void onError(Exception e) {
            error = e;
        }
    }

    private static final class Messages implements MessageHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleMessage(String channel, byte[] message) {
            events.add("msg:" + channel + ":"
                    + new String(message, StandardCharsets.UTF_8));
        }

        @Override
        public void handlePatternMessage(String pattern, String channel,
                                         byte[] message) {
            events.add("pmsg:" + pattern + ":" + channel + ":"
                    + new String(message, StandardCharsets.UTF_8));
        }

        @Override
        public void handleSubscribed(String channel, int count) {
            events.add("sub:" + channel + ":" + count);
        }

        @Override
        public void handlePatternSubscribed(String pattern, int count) {
            events.add("psub:" + pattern + ":" + count);
        }

        @Override
        public void handleUnsubscribed(String channel, int count) {
            events.add("unsub:" + channel + ":" + count);
        }

        @Override
        public void handlePatternUnsubscribed(String pattern, int count) {
            events.add("punsub:" + pattern + ":" + count);
        }

        @Override
        public void handleError(String error) {
            events.add("err:" + error);
        }
    }
}
