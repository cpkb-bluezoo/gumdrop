/*
 * RedisClientShardPubSubTest.java
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
 * Sharded Pub/Sub ({@code SSUBSCRIBE}, {@code SUNSUBSCRIBE},
 * {@code SPUBLISH}) on a single-node {@link RedisClientProtocolHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientShardPubSubTest {

    /** Records every event, shard and ordinary. */
    private static final class Events implements MessageHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleMessage(String channel, byte[] message) {
            events.add("msg:" + channel + ":" + new String(message, StandardCharsets.UTF_8));
        }

        @Override
        public void handlePatternMessage(String pattern, String channel, byte[] message) {
            events.add("pmsg:" + pattern + ":" + channel);
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
        public void handleShardMessage(String channel, byte[] message) {
            events.add("smsg:" + channel + ":" + new String(message, StandardCharsets.UTF_8));
        }

        @Override
        public void handleShardSubscribed(String channel, int count) {
            events.add("ssub:" + channel + ":" + count);
        }

        @Override
        public void handleShardUnsubscribed(String channel, int count) {
            events.add("sunsub:" + channel + ":" + count);
        }

        @Override
        public void handleError(String error) {
            events.add("error:" + error);
        }
    }

    /** Knows only the original events: it must still work with sharded traffic. */
    private static final class OldStyle implements MessageHandler {
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
        public void handlePatternSubscribed(String pattern, int count) {
        }

        @Override
        public void handleUnsubscribed(String channel, int count) {
        }

        @Override
        public void handlePatternUnsubscribed(String pattern, int count) {
        }

        @Override
        public void handleError(String error) {
        }
    }

    private static final class Count implements IntegerResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(long result, RedisSession session) {
            events.add("int:" + result);
        }

        @Override
        public void handleError(String error, RedisSession session) {
            events.add("error:" + error);
        }
    }

    private RedisClientProtocolHandler handler;
    private RedisClientProtocolHandlerTest.StubEndpoint endpoint;
    private RedisSession session;

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
        endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        handler.connected(endpoint);
        session = ref.get();
    }

    private void feed(String resp) {
        handler.receive(ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8)));
    }

    private String sent() {
        StringBuilder sb = new StringBuilder();
        for (ByteBuffer b : endpoint.sentBuffers) {
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

    // ── sending ──

    @Test
    public void ssubscribeSendsTheChannels() {
        session.ssubscribe(new Events(), "orders", "orders2");
        assertEquals(resp("SSUBSCRIBE", "orders", "orders2"), sent());
    }

    @Test
    public void sunsubscribeNamesTheChannels() {
        session.sunsubscribe("orders");
        assertEquals(resp("SUNSUBSCRIBE", "orders"), sent());
    }

    @Test
    public void sunsubscribeWithNoChannelsMeansAll() {
        session.sunsubscribe();
        assertEquals(resp("SUNSUBSCRIBE"), sent());
    }

    @Test
    public void spublishSendsTextAndBinaryMessages() {
        session.spublish("orders", "hello", new Count());
        session.spublish("orders", new byte[] { 1, 2 }, new Count());
        assertEquals(resp("SPUBLISH", "orders", "hello")
                + "*3\r\n$8\r\nSPUBLISH\r\n$6\r\norders\r\n$2\r\n\u0001\u0002\r\n", sent());
    }

    @Test
    public void spublishReportsTheNumberOfReceivers() {
        Count c = new Count();
        session.spublish("orders", "hello", c);
        feed(":2\r\n");
        assertEquals("int:2", c.events.get(0));
    }

    // ── receiving ──

    @Test
    public void shardEventsReachTheHandler() {
        Events e = new Events();
        session.ssubscribe(e, "orders");
        feed("*3\r\n$10\r\nssubscribe\r\n$6\r\norders\r\n:1\r\n");
        feed("*3\r\n$8\r\nsmessage\r\n$6\r\norders\r\n$2\r\nhi\r\n");
        feed("*3\r\n$12\r\nsunsubscribe\r\n$6\r\norders\r\n:0\r\n");
        assertEquals("ssub:orders:1", e.events.get(0));
        assertEquals("smsg:orders:hi", e.events.get(1));
        assertEquals("sunsub:orders:0", e.events.get(2));
    }

    @Test
    public void aShardPushInResp3IsDelivered() {
        Events e = new Events();
        session.ssubscribe(e, "orders");
        feed(">3\r\n$8\r\nsmessage\r\n$6\r\norders\r\n$2\r\nhi\r\n");
        assertEquals("smsg:orders:hi", e.events.get(0));
    }

    @Test
    public void ordinaryAndShardEventsAreKeptApart() {
        Events e = new Events();
        session.subscribe(e, "news");
        session.ssubscribe(e, "orders");
        feed("*3\r\n$7\r\nmessage\r\n$4\r\nnews\r\n$1\r\na\r\n");
        feed("*3\r\n$8\r\nsmessage\r\n$6\r\norders\r\n$1\r\nb\r\n");
        assertEquals("msg:news:a", e.events.get(0));
        assertEquals("smsg:orders:b", e.events.get(1));
    }

    @Test
    public void aHandlerThatKnowsOnlyTheOriginalEventsIsUnharmed() {
        OldStyle h = new OldStyle();
        session.ssubscribe(h, "orders");
        feed("*3\r\n$10\r\nssubscribe\r\n$6\r\norders\r\n:1\r\n");
        feed("*3\r\n$8\r\nsmessage\r\n$6\r\norders\r\n$2\r\nhi\r\n");
        feed("*3\r\n$12\r\nsunsubscribe\r\n$6\r\norders\r\n:0\r\n");
        assertTrue(h.events.toString(), h.events.isEmpty());
    }

    // ── the connection stays in Pub/Sub mode while either kind remains ──

    @Test
    public void shardMessagesStillArriveAfterOrdinaryUnsubscribesToZero() {
        Events e = new Events();
        session.subscribe(e, "news");
        session.ssubscribe(e, "orders");
        feed("*3\r\n$9\r\nsubscribe\r\n$4\r\nnews\r\n:1\r\n");
        feed("*3\r\n$10\r\nssubscribe\r\n$6\r\norders\r\n:1\r\n");
        feed("*3\r\n$11\r\nunsubscribe\r\n$4\r\nnews\r\n:0\r\n");
        feed("*3\r\n$8\r\nsmessage\r\n$6\r\norders\r\n$2\r\nhi\r\n");
        assertEquals("smsg:orders:hi", e.events.get(e.events.size() - 1));
    }

    @Test
    public void ordinaryMessagesStillArriveAfterShardUnsubscribesToZero() {
        Events e = new Events();
        session.subscribe(e, "news");
        session.ssubscribe(e, "orders");
        feed("*3\r\n$9\r\nsubscribe\r\n$4\r\nnews\r\n:1\r\n");
        feed("*3\r\n$10\r\nssubscribe\r\n$6\r\norders\r\n:1\r\n");
        feed("*3\r\n$12\r\nsunsubscribe\r\n$6\r\norders\r\n:0\r\n");
        feed("*3\r\n$7\r\nmessage\r\n$4\r\nnews\r\n$1\r\na\r\n");
        assertEquals("msg:news:a", e.events.get(e.events.size() - 1));
    }

    @Test
    public void theModeEndsWhenBothKindsAreGone() {
        Events e = new Events();
        session.ssubscribe(e, "orders");
        feed("*3\r\n$10\r\nssubscribe\r\n$6\r\norders\r\n:1\r\n");
        feed("*3\r\n$12\r\nsunsubscribe\r\n$6\r\norders\r\n:0\r\n");
        int before = e.events.size();
        // no longer Pub/Sub: an array reply goes to the command waiting for it
        feed("*3\r\n$8\r\nsmessage\r\n$6\r\norders\r\n$2\r\nhi\r\n");
        assertEquals(before, e.events.size());
    }

    @Test
    public void anOrdinaryUnsubscribeToZeroStillEndsTheModeWhenNoShardChannelsWereSubscribed() {
        Events e = new Events();
        session.subscribe(e, "news");
        feed("*3\r\n$11\r\nunsubscribe\r\n$4\r\nnews\r\n:0\r\n");
        int before = e.events.size();
        feed("*3\r\n$7\r\nmessage\r\n$4\r\nnews\r\n$1\r\na\r\n");
        assertEquals(before, e.events.size());
    }

    @Test
    public void aShardSubscriptionNotYetAcknowledgedKeepsTheModeOpen() {
        Events e = new Events();
        session.subscribe(e, "news");
        session.ssubscribe(e, "orders");
        feed("*3\r\n$9\r\nsubscribe\r\n$4\r\nnews\r\n:1\r\n");
        feed("*3\r\n$11\r\nunsubscribe\r\n$4\r\nnews\r\n:0\r\n"); // before ssubscribe's ack
        feed("*3\r\n$10\r\nssubscribe\r\n$6\r\norders\r\n:1\r\n");
        assertEquals("ssub:orders:1", e.events.get(e.events.size() - 1));
    }
}
