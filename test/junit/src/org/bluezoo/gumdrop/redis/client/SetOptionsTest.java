/*
 * SetOptionsTest.java
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
 * {@link SetOptions} and the {@code SET} overloads that take them: the
 * arguments sent, how each reply reaches the handler, and the checks on
 * contradictory options.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SetOptionsTest {

    private static final class Outcome implements BulkResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(byte[] value, RedisSession session) {
            events.add("value:" + new String(value, StandardCharsets.UTF_8));
        }

        @Override
        public void handleNull(RedisSession session) {
            events.add("null");
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

    // ── what is sent ──

    @Test
    public void emptyOptionsSendAPlainSet() {
        session.set("k", "v", new SetOptions(), new Outcome());
        assertEquals(resp("SET", "k", "v"), sent());
    }

    @Test
    public void nxWithMillisecondExpiry() {
        session.set("lock", "me", new SetOptions().nx().px(30000), new Outcome());
        assertEquals(resp("SET", "lock", "me", "NX", "PX", "30000"), sent());
    }

    @Test
    public void xxWithSecondExpiry() {
        session.set("k", "v", new SetOptions().xx().ex(60), new Outcome());
        assertEquals(resp("SET", "k", "v", "XX", "EX", "60"), sent());
    }

    @Test
    public void absoluteExpiries() {
        session.set("k", "v", new SetOptions().exat(1700000000L), new Outcome());
        session.set("k", "v", new SetOptions().pxat(1700000000123L), new Outcome());
        assertEquals(resp("SET", "k", "v", "EXAT", "1700000000")
                + resp("SET", "k", "v", "PXAT", "1700000000123"), sent());
    }

    @Test
    public void keepTtlAndGet() {
        session.set("k", "v", new SetOptions().keepTtl().get(), new Outcome());
        assertEquals(resp("SET", "k", "v", "GET", "KEEPTTL"), sent());
    }

    @Test
    public void binaryValueIsSentUnchanged() {
        session.set("k", new byte[] { (byte) 0xFF, 0, 1 }, new SetOptions().nx(), new Outcome());
        String expected = "*4\r\n$3\r\nSET\r\n$1\r\nk\r\n$3\r\n\u00FF\u0000\u0001\r\n$2\r\nNX\r\n";
        assertEquals(expected, sent());
    }

    // ── replies ──

    @Test
    public void okReplyReachesTheHandlerAsOk() {
        Outcome o = new Outcome();
        session.set("k", "v", new SetOptions().nx(), o);
        feed("+OK\r\n");
        assertEquals("value:OK", o.events.get(0));
    }

    @Test
    public void unmetConditionIsNull() {
        Outcome o = new Outcome();
        session.set("k", "v", new SetOptions().nx(), o);
        feed("$-1\r\n");
        assertEquals("null", o.events.get(0));
    }

    @Test
    public void getReturnsThePreviousValue() {
        Outcome o = new Outcome();
        session.set("k", "new", new SetOptions().get(), o);
        feed("$3\r\nold\r\n");
        assertEquals("value:old", o.events.get(0));
    }

    @Test
    public void errorReplyReachesTheHandler() {
        Outcome o = new Outcome();
        session.set("k", "v", new SetOptions().get(), o);
        feed("-WRONGTYPE bad\r\n");
        assertEquals("error:WRONGTYPE bad", o.events.get(0));
    }

    // ── validation ──

    @Test(expected = IllegalStateException.class)
    public void nxAndXxAreExclusive() {
        new SetOptions().nx().xx();
    }

    @Test(expected = IllegalStateException.class)
    public void twoExpiriesAreRejected() {
        new SetOptions().ex(1).px(1);
    }

    @Test(expected = IllegalStateException.class)
    public void keepTtlWithAnExpiryIsRejected() {
        new SetOptions().px(10).keepTtl();
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroExpiryIsRejected() {
        new SetOptions().ex(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeExpiryIsRejected() {
        new SetOptions().pxat(-5);
    }

    @Test
    public void repeatingTheSameOptionIsHarmless() {
        session.set("k", "v", new SetOptions().nx().nx().get().get(), new Outcome());
        assertEquals(resp("SET", "k", "v", "NX", "GET"), sent());
    }
}
