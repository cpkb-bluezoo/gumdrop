/*
 * RedisClientPubSubEdgeTest.java
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Edge cases of {@link RedisClientProtocolHandler} Pub/Sub and RESP3 push
 * routing: malformed or too-short pub/sub arrays fall through to the pending
 * queue, pushes with an empty body or a null type are dropped, commands
 * issued with a null callback while disconnected fail silently, and the
 * XREAD and generic command builders emit the expected wire arguments.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientPubSubEdgeTest {

    private RedisClientProtocolHandler handler;
    private RedisClientProtocolHandlerTest.StubEndpoint endpoint;
    private RedisSession session;
    private Ready ready;

    @Before
    public void setUp() {
        AtomicReference<RedisSession> ref = new AtomicReference<RedisSession>();
        ready = new Ready(ref);
        handler = new RedisClientProtocolHandler(ready);
        endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        handler.connected(endpoint);
        session = ref.get();
    }

    private void feed(String resp) {
        handler.receive(ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8)));
    }

    private String lastSent() {
        ByteBuffer buf = endpoint.sentBuffers.get(endpoint.sentBuffers.size() - 1);
        byte[] bytes = new byte[buf.remaining()];
        buf.duplicate().get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    public void testNonIntegerSubscriptionCountIsAProtocolErrorNotAnException() {
        Messages m = new Messages();
        session.subscribe(m, "c");
        feed(">3\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n$1\r\nx\r\n");
        assertFalse(endpoint.open);
    }

    @Test
    public void testWrongTypeReplyForAnIntegerCommandIsAProtocolErrorNotAnException() {
        session.incr("k", new IntegerResultHandler() {
            @Override
            public void handleResult(long result, RedisSession s) {
            }

            @Override
            public void handleError(String error, RedisSession s) {
            }
        });
        feed("+OK\r\n");
        assertFalse(endpoint.open);
    }

    @Test
    public void testPushWithEmptyBodyIsDropped() {
        Messages m = new Messages();
        session.subscribe(m, "c");
        feed(">0\r\n");
        assertTrue(m.events.isEmpty());
        assertTrue(endpoint.open);
    }

    @Test
    public void testPushWithNullTypeIsDropped() {
        Messages m = new Messages();
        session.subscribe(m, "c");
        feed(">1\r\n_\r\n");
        assertTrue(m.events.isEmpty());
        assertTrue(endpoint.open);
    }

    @Test
    public void testPushWithUnrecognisedTypeIsDropped() {
        Messages m = new Messages();
        session.subscribe(m, "c");
        feed(">2\r\n$5\r\nother\r\n$1\r\nx\r\n");
        assertTrue(m.events.isEmpty());
    }

    @Test
    public void testPushPatternMessageAndSubscriptionEvents() {
        Messages m = new Messages();
        session.subscribe(m, "c");
        feed(">4\r\n$8\r\npmessage\r\n$2\r\np*\r\n$1\r\nc\r\n$2\r\nhi\r\n");
        feed(">3\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n:1\r\n");
        assertEquals("pmsg:p*:c:hi", m.events.get(0));
        assertEquals("sub:c:1", m.events.get(1));
    }

    @Test
    public void testPubSubModeIgnoresEmptyArrayAndNullTypedArray() {
        Messages m = new Messages();
        session.subscribe(m, "c");
        feed("*0\r\n");
        feed("*1\r\n_\r\n");
        assertTrue(m.events.isEmpty());
        assertTrue(endpoint.open);
    }

    @Test
    public void testPubSubModeScalarResponseConsumesPendingCommand() {
        Messages m = new Messages();
        session.subscribe(m, "c");
        final List<String> seen = new ArrayList<String>();
        session.ping(new StringResultHandler() {
            @Override
            public void handleResult(String result, RedisSession s) {
                seen.add(result);
            }

            @Override
            public void handleError(String error, RedisSession s) {
                seen.add("error:" + error);
            }
        });
        feed("+OK\r\n");
        feed("+PONG\r\n");
        assertEquals(1, seen.size());
        assertEquals("PONG", seen.get(0));
    }

    @Test
    public void testTooShortPubSubArraysAreNotDelivered() {
        Messages m = new Messages();
        session.subscribe(m, "c");
        feed("*2\r\n$7\r\nmessage\r\n$1\r\nc\r\n");
        feed("*3\r\n$8\r\npmessage\r\n$1\r\np\r\n$1\r\nc\r\n");
        feed("*2\r\n$9\r\nsubscribe\r\n$1\r\nc\r\n");
        feed("*2\r\n$10\r\npsubscribe\r\n$1\r\np\r\n");
        feed("*2\r\n$11\r\nunsubscribe\r\n$1\r\nc\r\n");
        feed("*2\r\n$12\r\npunsubscribe\r\n$1\r\np\r\n");
        assertTrue(m.events.toString(), m.events.isEmpty());
    }

    @Test
    public void testNullCallbackWhenDisconnectedIsSilent() {
        RedisClientProtocolHandler unconnected = new RedisClientProtocolHandler(ready);
        unconnected.ping(null);
        unconnected.get("k", (BulkResultHandler) null);
        unconnected.set("k", new byte[] { 1 }, null);
        unconnected.mset(null, "a", "b");
        unconnected.command((ArrayResultHandler) null, "INFO", new String[] { "x" });
        unconnected.command((ArrayResultHandler) null, "GET", new byte[] { 'k' });
        unconnected.close();
        assertTrue(endpoint.sentBuffers.size() == 0);
    }

    @Test
    public void testGenericCommandPassthrough() {
        session.command(null, "INFO", "server");
        assertEquals("*2\r\n$4\r\nINFO\r\n$6\r\nserver\r\n", lastSent());
        session.command(null, "GET", new byte[] { 'k', 'e', 'y' });
        assertEquals("*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n", lastSent());
    }

    @Test
    public void testXreadOptionVariants() {
        session.xread(0, -1, null, "s", "0");
        assertEquals("*4\r\n$5\r\nXREAD\r\n$7\r\nSTREAMS\r\n$1\r\ns\r\n$1\r\n0\r\n", lastSent());
        session.xread(5, 100, null, "s", "0");
        assertEquals("*8\r\n$5\r\nXREAD\r\n$5\r\nCOUNT\r\n$1\r\n5\r\n$5\r\nBLOCK\r\n$3\r\n100\r\n"
                + "$7\r\nSTREAMS\r\n$1\r\ns\r\n$1\r\n0\r\n", lastSent());
        session.xread(0, 0, null, "s", "0");
        assertTrue(lastSent().contains("BLOCK"));
    }

    private static final class Ready implements RedisConnectionReady {
        private final AtomicReference<RedisSession> ref;

        Ready(AtomicReference<RedisSession> ref) {
            this.ref = ref;
        }

        @Override
        public void handleReady(RedisSession s) {
            ref.set(s);
        }

        @Override
        public void onConnected(Endpoint ep) {
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
        }

        @Override
        public void onError(Exception e) {
        }
    }

    private static final class Messages implements MessageHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleMessage(String channel, byte[] message) {
            events.add("msg:" + channel + ":" + new String(message, StandardCharsets.UTF_8));
        }

        @Override
        public void handlePatternMessage(String pattern, String channel, byte[] message) {
            events.add("pmsg:" + pattern + ":" + channel + ":" + new String(message, StandardCharsets.UTF_8));
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
