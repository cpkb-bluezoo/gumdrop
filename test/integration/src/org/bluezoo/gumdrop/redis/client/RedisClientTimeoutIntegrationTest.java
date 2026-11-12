/*
 * RedisClusterIntegrationTest.java
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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.redis.codec.RespValue;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Command timeouts and the pending-command cap of {@link RedisClient}
 * against a real server, which is made unresponsive with
 * {@code CLIENT PAUSE}. Uses the same server as
 * {@link RedisClusterIntegrationTest} ({@code -Dredis.cluster.host} and
 * {@code -Dredis.cluster.port}, default 127.0.0.1:7100) and is skipped if
 * nothing answers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClientTimeoutIntegrationTest {

    private static final String HOST = System.getProperty("redis.cluster.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("redis.cluster.port", 7100);
    private static final long WAIT_SECONDS = 10;

    private Gumdrop gumdrop;
    private SelectorLoop loop;
    private RedisClient client;
    private volatile RedisSession session;

    @Before
    public void connect() throws Exception {
        connectWith(Duration.ofMillis(300), Duration.ZERO);
    }

    private void connectWith(Duration commandTimeout, Duration healthCheck) throws Exception {
        assumeTrue("no server answering at " + HOST + ":" + PORT, reachable());
        gumdrop = Gumdrop.boot();
        loop = gumdrop.nextWorkerLoop();
        client = new RedisClient().host(HOST).port(PORT).selectorLoop(loop)
                .commandTimeout(commandTimeout).maxPendingCommands(3)
                .healthCheckInterval(healthCheck);
        final CountDownLatch ready = new CountDownLatch(1);
        client.connect(gumdrop, new RedisConnectionReady() {
            @Override
            public void handleReady(RedisSession s) {
                session = s;
                ready.countDown();
            }

            @Override
            public void onConnected(org.bluezoo.gumdrop.Endpoint endpoint) {
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
        assertTrue("not connected in time", ready.await(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
        }
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Pauses every client of the server, this one included, for {@code millis}. */
    private static void pause(long millis) throws IOException {
        try (Socket socket = new Socket(HOST, PORT)) {
            socket.setSoTimeout(10000);
            String cmd = "*4\r\n$6\r\nCLIENT\r\n$5\r\nPAUSE\r\n$"
                    + String.valueOf(millis).length() + "\r\n" + millis + "\r\n$3\r\nALL\r\n";
            socket.getOutputStream().write(cmd.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("+OK", in.readLine());
        }
    }

    /** Collects the outcomes of ECHO commands, in the order they happen. */
    private static final class Outcomes implements ArrayResultHandler {
        final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<String>();

        @Override
        public void handleResult(List<RespValue> values, RedisSession s) {
            events.add("reply:" + values.get(0).asString());
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

    private void echo(final String text, final Outcomes outcomes) {
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                session.command(outcomes, "ECHO", text);
            }
        });
    }

    @Test
    public void aCommandToAPausedServerTimesOutOnceAndItsLateReplyIsDiscarded() throws Exception {
        Outcomes first = new Outcomes();
        pause(1500);
        long start = System.nanoTime();
        echo("one", first);
        String event = first.events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertNotNull("no outcome", event);
        assertTrue(event, event.startsWith("error:TIMEOUT"));
        assertTrue("took " + elapsedMs + " ms", elapsedMs >= 250 && elapsedMs < 1400);
        // the server answers when the pause ends; the handler must not hear of it
        assertNull(first.events.poll(3, TimeUnit.SECONDS));
        // and the connection still pairs replies with commands
        Outcomes second = new Outcomes();
        echo("two", second);
        assertEquals("reply:two", second.events.poll(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    public void commandsBeyondTheCapAreRefusedAtOnce() throws Exception {
        pause(1000);
        Outcomes[] all = new Outcomes[6];
        for (int i = 0; i < all.length; i++) {
            all[i] = new Outcomes();
            echo("c" + i, all[i]);
        }
        // the cap is three: the rest are refused without waiting for the pause
        for (int i = 3; i < all.length; i++) {
            String event = all[i].events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("command " + i, event);
            assertTrue(event, event.startsWith("error:OVERLOAD"));
        }
        // the first three time out (the pause outlasts the timeout)
        for (int i = 0; i < 3; i++) {
            String event = all[i].events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull("command " + i, event);
            assertTrue(event, event.startsWith("error:TIMEOUT"));
        }
    }

    @Test
    public void aCommandWithItsOwnLongerTimeoutOutlastsThePause() throws Exception {
        pause(700);
        final Outcomes outcomes = new Outcomes();
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                session.command(Duration.ofSeconds(5), outcomes, "ECHO", "patient");
            }
        });
        assertEquals("reply:patient", outcomes.events.poll(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    public void aServerThatGoesSilentIsDetectedByTheHealthCheck() throws Exception {
        // no command timeout: only the health check can notice
        tearDown();
        connectWith(Duration.ZERO, Duration.ofMillis(500));
        pause(4000);
        Outcomes waiting = new Outcomes();
        long start = System.nanoTime();
        echo("anyone there", waiting);
        String event = waiting.events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertNotNull("the waiting command was never failed", event);
        assertTrue(event, event.startsWith("error:"));
        assertTrue("found out after " + elapsedMs + " ms, before the pause ended", elapsedMs < 3500);
        assertNull("only once", waiting.events.poll(3, TimeUnit.SECONDS));
    }
}
