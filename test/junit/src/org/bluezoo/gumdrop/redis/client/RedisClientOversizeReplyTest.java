/*
 * RedisClientOversizeReplyTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A reply too large to hold fails its own command only: the replies before
 * and after it on the same connection are delivered, and the connection is
 * not closed. A reply to a command that has been told it timed out is
 * discarded as it arrives.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientOversizeReplyTest {

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
        handler.setMaxBuiltReply(1024);
    }

    private void feed(String resp) {
        handler.receive(ByteBuffer.wrap(resp.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static String bulk(int size) {
        StringBuilder sb = new StringBuilder("$" + size + "\r\n");
        for (int i = 0; i < size; i++) {
            sb.append('x');
        }
        return sb.append("\r\n").toString();
    }

    @Test
    public void anOversizeReplyFailsItsOwnCommandOnly() {
        Bulk before = new Bulk();
        Bulk big = new Bulk();
        Bulk after = new Bulk();
        session.get("a", before);
        session.get("b", big);
        session.get("c", after);
        feed("$1\r\nA\r\n" + bulk(5000) + "$1\r\nC\r\n");
        assertEquals("[value:A]", before.events.toString());
        assertEquals(1, big.events.size());
        assertTrue(big.events.get(0), big.events.get(0).startsWith("error:TOOLARGE"));
        assertEquals("[value:C]", after.events.toString());
        assertTrue("the connection survives", endpoint.open);
    }

    @Test
    public void anOversizeReplySplitAtEveryPointStillFailsOnlyItsCommand() {
        String wire = bulk(3000) + "$1\r\nZ\r\n";
        for (int split = 1; split < 40; split += 3) {
            setUp();
            Bulk big = new Bulk();
            Bulk after = new Bulk();
            session.get("b", big);
            session.get("c", after);
            byte[] bytes = wire.getBytes(StandardCharsets.ISO_8859_1);
            for (int off = 0; off < bytes.length; off += split) {
                handler.receive(ByteBuffer.wrap(bytes, off, Math.min(split, bytes.length - off)));
            }
            assertTrue(big.events.get(0), big.events.get(0).startsWith("error:TOOLARGE"));
            assertEquals("split " + split, "[value:Z]", after.events.toString());
        }
    }

    @Test
    public void anAggregateOverTheCapFailsItsCommandToo() {
        Many many = new Many();
        Bulk after = new Bulk();
        session.lrange("k", 0, -1, many);
        session.get("c", after);
        StringBuilder sb = new StringBuilder("*100\r\n");
        for (int i = 0; i < 100; i++) {
            sb.append(bulk(100));
        }
        sb.append("$1\r\nC\r\n");
        feed(sb.toString());
        assertEquals(1, many.events.size());
        assertTrue(many.events.get(0), many.events.get(0).startsWith("error:TOOLARGE"));
        assertEquals("[value:C]", after.events.toString());
    }

    @Test
    public void aReplyUnderTheCapIsStillDelivered() {
        Bulk b = new Bulk();
        session.get("k", b);
        feed(bulk(500));
        assertEquals(1, b.events.size());
        assertTrue(b.events.get(0).startsWith("value:"));
    }

}
