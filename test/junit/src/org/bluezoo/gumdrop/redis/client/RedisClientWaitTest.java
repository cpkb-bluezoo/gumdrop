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

import org.bluezoo.gumdrop.redis.codec.RespValue;

import static org.junit.Assert.*;

/**
 * On a single node there is no slot to route by: the keys of
 * {@code waitReplicas}, {@code waitAof} and {@code commandForKey} are
 * ignored and each is the plain command.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientWaitTest {

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

    private String written() {
        StringBuilder sb = new StringBuilder();
        for (ByteBuffer b : endpoint.sentBuffers) {
            byte[] bytes = new byte[b.remaining()];
            b.duplicate().get(bytes);
            sb.append(new String(bytes, StandardCharsets.ISO_8859_1));
        }
        return sb.toString();
    }

    private void feed(String wire) {
        handler.receive(ByteBuffer.wrap(wire.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class Count implements IntegerResultHandler {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleResult(long value, RedisSession s) {
            events.add("count:" + value);
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

    @Test
    public void waitReplicasIsAPlainWait() {
        Count c = new Count();
        session.waitReplicas("any-key", 2, 250, c);
        assertEquals("*3\r\n$4\r\nWAIT\r\n$1\r\n2\r\n$3\r\n250\r\n", written());
        feed(":1\r\n");
        assertEquals("[count:1]", c.events.toString());
    }

    @Test
    public void waitAofIsAPlainWaitAof() {
        Many m = new Many();
        session.waitAof("any-key", 1, 1, 50, m);
        assertEquals("*4\r\n$7\r\nWAITAOF\r\n$1\r\n1\r\n$1\r\n1\r\n$2\r\n50\r\n", written());
        feed("*2\r\n:1\r\n:1\r\n");
        assertEquals("[array:2]", m.events.toString());
    }

    @Test
    public void commandForKeyIsAPlainCommand() {
        Many m = new Many();
        session.commandForKey("any-key", m, "CLIENT", "ID");
        assertEquals("*2\r\n$6\r\nCLIENT\r\n$2\r\nID\r\n", written());
        feed(":7\r\n");
        assertEquals("[array:1]", m.events.toString());
    }

    @Test
    public void commandForKeyWithATimeoutIsAPlainCommandWithThatTimeout() {
        Many m = new Many();
        session.commandForKey("any-key", Duration.ofSeconds(3), m, "ECHO", "x".getBytes(StandardCharsets.UTF_8));
        assertEquals("*2\r\n$4\r\nECHO\r\n$1\r\nx\r\n", written());
    }
}
