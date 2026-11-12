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
 * RESP3 pushes that are not Pub/Sub deliveries (client-side caching
 * invalidations, for one) reach the {@link PushHandler}, not the floor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientPushHandlerTest {

    private static final class Pushes implements PushHandler {
        final List<String> kinds = new ArrayList<String>();
        final List<List<Object>> payloads = new ArrayList<List<Object>>();

        @Override
        public void onPush(String kind, List<Object> payload) {
            kinds.add(kind);
            payloads.add(payload);
        }
    }

    private static final class Messages implements MessageHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleMessage(String channel, byte[] message) {
            events.add("msg:" + channel);
        }

        @Override
        public void handlePatternMessage(String pattern, String channel, byte[] message) {
        }

        @Override
        public void handleSubscribed(String channel, int count) {
            events.add("sub:" + channel);
        }

        @Override
        public void handleUnsubscribed(String channel, int count) {
        }

        @Override
        public void handlePatternSubscribed(String pattern, int count) {
        }

        @Override
        public void handlePatternUnsubscribed(String pattern, int count) {
        }

        @Override
        public void handleError(String error) {
        }
    }

    private static final class Bulk implements BulkResultHandler {
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

    private RedisClientProtocolHandler handler;
    private RedisSession session;
    private Pushes pushes;

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
        handler.connected(new RedisClientProtocolHandlerTest.StubEndpoint());
        session = ref.get();
        pushes = new Pushes();
    }

    private void feed(String resp) {
        handler.receive(ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8)));
    }

    // >2 invalidate [foo]
    private static final String INVALIDATE_FOO =
            ">2\r\n$10\r\ninvalidate\r\n*1\r\n$3\r\nfoo\r\n";

    @Test
    public void anInvalidationReachesThePushHandler() {
        session.setPushHandler(pushes);
        feed(INVALIDATE_FOO);
        assertEquals("[invalidate]", pushes.kinds.toString());
        assertEquals(1, pushes.payloads.get(0).size());
        assertEquals("[foo]", pushes.payloads.get(0).get(0).toString());
    }

    @Test
    public void aFlushInvalidationHasANullPayload() {
        session.setPushHandler(pushes);
        feed(">2\r\n$10\r\ninvalidate\r\n_\r\n");
        assertEquals(1, pushes.payloads.get(0).size());
        assertNull(pushes.payloads.get(0).get(0));
    }

    @Test
    public void payloadValuesAreConvertedToPlainJavaTypes() {
        session.setPushHandler(pushes);
        // >6 custom, +text, :42, ,1.5, #t, %1 {a: 1}
        feed(">6\r\n$6\r\ncustom\r\n+text\r\n:42\r\n,1.5\r\n#t\r\n%1\r\n$1\r\na\r\n:1\r\n");
        assertEquals("custom", pushes.kinds.get(0));
        List<Object> p = pushes.payloads.get(0);
        assertEquals(5, p.size());
        assertEquals("text", p.get(0));
        assertEquals(Long.valueOf(42), p.get(1));
        assertEquals(Double.valueOf(1.5), p.get(2));
        assertEquals(Boolean.TRUE, p.get(3));
        assertTrue(p.get(4) instanceof java.util.Map);
        assertEquals(Long.valueOf(1), ((java.util.Map<?, ?>) p.get(4)).get("a"));
    }

    @Test
    public void pubSubPushesStillGoToTheMessageHandler() {
        session.setPushHandler(pushes);
        Messages messages = new Messages();
        session.subscribe(messages, "news");
        feed(">3\r\n$9\r\nsubscribe\r\n$4\r\nnews\r\n:1\r\n");
        feed(">3\r\n$7\r\nmessage\r\n$4\r\nnews\r\n$2\r\nhi\r\n");
        assertEquals("[sub:news, msg:news]", messages.events.toString());
        assertTrue(pushes.kinds.isEmpty());
    }

    @Test
    public void otherPushesReachThePushHandlerEvenWithAMessageHandler() {
        session.setPushHandler(pushes);
        session.subscribe(new Messages(), "news");
        feed(INVALIDATE_FOO);
        assertEquals("[invalidate]", pushes.kinds.toString());
    }

    @Test
    public void aPubSubPushWithNoMessageHandlerIsNotLost() {
        session.setPushHandler(pushes);
        feed(">3\r\n$7\r\nmessage\r\n$4\r\nnews\r\n$2\r\nhi\r\n");
        assertEquals("[message]", pushes.kinds.toString());
        assertEquals("[news, hi]", pushes.payloads.get(0).toString());
    }

    @Test
    public void withoutAPushHandlerPushesAreIgnored() {
        feed(INVALIDATE_FOO);
        Bulk b = new Bulk();
        session.get("foo", b);
        feed("$1\r\nv\r\n");
        assertEquals("[value:v]", b.events.toString());
    }

    @Test
    public void aPushBetweenACommandAndItsReplyDoesNotTakeTheReply() {
        session.setPushHandler(pushes);
        Bulk b = new Bulk();
        session.get("foo", b);
        feed(INVALIDATE_FOO);
        assertTrue(b.events.isEmpty());
        feed("$1\r\nv\r\n");
        assertEquals("[value:v]", b.events.toString());
        assertEquals(1, pushes.kinds.size());
    }

    @Test
    public void theHandlerCanBeReplacedAndRemoved() {
        session.setPushHandler(pushes);
        Pushes other = new Pushes();
        session.setPushHandler(other);
        feed(INVALIDATE_FOO);
        assertTrue(pushes.kinds.isEmpty());
        assertEquals(1, other.kinds.size());
        session.setPushHandler(null);
        feed(INVALIDATE_FOO);
        assertEquals(1, other.kinds.size());
    }
}
