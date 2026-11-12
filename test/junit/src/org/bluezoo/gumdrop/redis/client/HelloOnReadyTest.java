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

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;

import static org.junit.Assert.*;

/**
 * {@link HelloOnReady}: negotiates RESP3 before the application's handler is
 * told the connection is ready.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HelloOnReadyTest {

    private static final class App implements RedisConnectionReady {
        final List<String> events = new ArrayList<String>();

        @Override
        public void handleReady(RedisSession session) {
            events.add("ready");
        }

        @Override
        public void onConnected(Endpoint endpoint) {
            events.add("connected");
        }

        @Override
        public void onSecurityEstablished(SecurityInfo info) {
            events.add("secure");
        }

        @Override
        public void onError(Exception cause) {
            events.add("error:" + cause.getMessage());
        }

        @Override
        public void onDisconnected() {
            events.add("disconnected");
        }
    }

    private App app;
    private RedisClientProtocolHandlerTest.StubEndpoint endpoint;
    private RedisClientProtocolHandler handler;

    @Before
    public void setUp() {
        app = new App();
        endpoint = new RedisClientProtocolHandlerTest.StubEndpoint();
        handler = new RedisClientProtocolHandler(new HelloOnReady(app, 3));
        handler.connected(endpoint);
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

    @Test
    public void helloIsSentAndTheApplicationWaitsForIt() {
        assertEquals("*2\r\n$5\r\nHELLO\r\n$1\r\n3\r\n", written());
        assertFalse(app.events.contains("ready"));
        feed("%1\r\n+server\r\n+redis\r\n");
        assertTrue(app.events.contains("ready"));
    }

    @Test
    public void aRejectedHelloIsAnErrorAndTheConnectionIsClosed() {
        feed("-ERR unknown command 'HELLO'\r\n");
        assertFalse(app.events.contains("ready"));
        assertEquals(1, countStarting("error:"));
        assertFalse(endpoint.isOpen());
    }

    @Test
    public void otherEventsReachTheApplicationUnchanged() {
        HelloOnReady direct = new HelloOnReady(app, 3);
        direct.onConnected(endpoint);
        direct.onSecurityEstablished(null);
        direct.onError(new java.io.IOException("reset"));
        direct.onDisconnected();
        assertEquals("[connected, secure, error:reset, disconnected]", app.events.toString());
    }

    private int countStarting(String prefix) {
        int n = 0;
        for (String e : app.events) {
            if (e.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }
}
