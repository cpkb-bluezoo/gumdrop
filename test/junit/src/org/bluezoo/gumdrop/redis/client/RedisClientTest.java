/*
 * RedisClientTest.java
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.TcpListener;
import org.junit.After;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Loopback tests for the {@link RedisClient} facade (connect, session, close).
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientTest {

    private static final String HOST = "::1";
    private static final long TIMEOUT_SECONDS = 5;

    private Gumdrop gumdrop;
    private FakeRedisListener listener;

    @After
    public void tearDown() throws InterruptedException {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
            try {
                gumdrop.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        gumdrop = null;
        listener = null;
    }

    @Test
    public void testPingViaFacade() throws Exception {
        listener = new FakeRedisListener();
        listener.setPort(0);
        listener.setAddresses(HOST);
        gumdrop = Gumdrop.boot();
        gumdrop.addListener(listener);
        gumdrop.start();
        assertTrue("fake server should bind within timeout",
                listener.awaitBound(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        int port = listener.getPort();
        assertTrue("fake server should have an ephemeral port assigned", port > 0);

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<String> pong = new AtomicReference<String>();
        AtomicReference<Exception> error = new AtomicReference<Exception>();

        RedisClient client = new RedisClient(HOST, port);
        try {
        client.connect(gumdrop, new RedisConnectionReady() {
            @Override
            public void handleReady(RedisSession session) {
                session.ping(new StringResultHandler() {
                    @Override
                    public void handleResult(String result, RedisSession s) {
                        pong.set(result);
                        doneLatch.countDown();
                    }

                    @Override
                    public void handleError(String message, RedisSession s) {
                        error.set(new IllegalStateException(message));
                        doneLatch.countDown();
                    }
                });
            }

            @Override
            public void onConnected(org.bluezoo.gumdrop.Endpoint endpoint) {
            }

            @Override
            public void onSecurityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
            }

            @Override
            public void onError(Exception cause) {
                error.set(cause);
                doneLatch.countDown();
            }

            @Override
            public void onDisconnected() {
            }
        });

        assertTrue("PING timed out", doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        assertEquals("PONG", pong.get());
        assertTrue(listener.getConnectionCount() >= 1);
        } finally {
            client.close();
        }
    }

    @Test
    public void testFluentDialConfiguration() {
        RedisClient client = new RedisClient()
                .host(HOST)
                .port(6379)
                .secure(false);
        assertEquals(6379, client.getDial().getPort());
        assertEquals(HOST, client.getDial().getHost());
    }

    static final class FakeRedisListener extends TcpListener {
        private int port = -1;
        private int connectionCount;
        private final CountDownLatch boundLatch = new CountDownLatch(1);

        void setPort(int port) {
            this.port = port;
        }

        boolean awaitBound(long timeout, TimeUnit unit) throws InterruptedException {
            return boundLatch.await(timeout, unit);
        }

        int getConnectionCount() {
            return connectionCount;
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        protected void applyBoundTcpPort(int boundPort) {
            if (port == 0) {
                port = boundPort;
            }
            boundLatch.countDown();
        }

        @Override
        protected ProtocolHandler createHandler() {
            connectionCount++;
            return new FakeRedisConnection();
        }

        @Override
        public String getDescription() {
            return "FakeRedis";
        }
    }

    static final class FakeRedisConnection implements ProtocolHandler {
        private Endpoint endpoint;
        private final StringBuilder request = new StringBuilder();

        @Override
        public void connected(Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void receive(ByteBuffer data) {
            request.append(StandardCharsets.UTF_8.decode(data));
            if (request.indexOf("PING") >= 0) {
                endpoint.send(ByteBuffer.wrap("+PONG\r\n".getBytes(StandardCharsets.UTF_8)));
            }
        }

        @Override
        public void disconnected() {
        }

        @Override
        public void error(Exception cause) {
        }

        @Override
        public void securityEstablished(SecurityInfo info) {
        }
    }
}
