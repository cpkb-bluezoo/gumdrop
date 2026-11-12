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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Command timeouts of {@link RedisClusterClient} against a real cluster in
 * which one node is made unresponsive with {@code CLIENT PAUSE}: only the
 * commands for that node's slots wait, and time out. Same cluster and
 * properties as {@link RedisClusterIntegrationTest}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterTimeoutIntegrationTest {

    private static final String HOST = System.getProperty("redis.cluster.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("redis.cluster.port", 7100);
    private static final long WAIT_SECONDS = 10;

    private Gumdrop gumdrop;
    private SelectorLoop loop;
    private RedisClusterClient client;
    private volatile RedisClusterSession session;
    private final String prefix = "gd-cluster-timeout-" + System.nanoTime() + "-";

    @Before
    public void connect() throws Exception {
        connectWith(Duration.ofMillis(300), Duration.ZERO);
    }

    private void connectWith(Duration commandTimeout, Duration healthCheck) throws Exception {
        assumeTrue("no cluster node answering at " + HOST + ":" + PORT, reachable());
        gumdrop = Gumdrop.boot();
        loop = gumdrop.nextWorkerLoop();
        client = new RedisClusterClient().seed(HOST, PORT).selectorLoop(loop)
                .commandTimeout(commandTimeout).healthCheckInterval(healthCheck);
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        client.connect(gumdrop, new RedisClusterReady() {
            @Override
            public void handleReady(RedisSession s) {
                session = (RedisClusterSession) s;
                ready.countDown();
            }

            @Override
            public void onError(Exception cause) {
                failure.set(cause);
                ready.countDown();
            }
        });
        assertTrue("cluster not ready in time", ready.await(WAIT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw failure.get();
        }
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

    private static void pause(String host, int port, long millis) throws IOException {
        try (Socket socket = new Socket(host, port)) {
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

    private static final class Outcomes implements BulkResultHandler {
        final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<String>();

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

    private void get(final String key, final Outcomes outcomes) {
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                session.get(key, outcomes);
            }
        });
    }

    private ClusterNode ownerOf(final String key) throws Exception {
        final AtomicReference<ClusterNode> owner = new AtomicReference<ClusterNode>();
        final CountDownLatch done = new CountDownLatch(1);
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                owner.set(session.slotMap().nodeFor(ClusterSlots.slot(key.getBytes(StandardCharsets.UTF_8))));
                done.countDown();
            }
        });
        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS));
        return owner.get();
    }

    @Test
    public void onlyTheCommandsForAPausedNodeTimeOut() throws Exception {
        // a key on one node, and one on a different node
        String paused = prefix + "a";
        ClusterNode pausedNode = ownerOf(paused);
        String other = null;
        for (int i = 0; i < 200 && other == null; i++) {
            String candidate = prefix + "b" + i;
            if (!ownerOf(candidate).equals(pausedNode)) {
                other = candidate;
            }
        }
        assertNotNull(other);
        final String otherKey = other;
        final CountDownLatch stored = new CountDownLatch(1);
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                session.set(otherKey, "alive", new StringResultHandler() {
                    @Override
                    public void handleResult(String result, RedisSession s) {
                        stored.countDown();
                    }

                    @Override
                    public void handleError(String error, RedisSession s) {
                    }
                });
            }
        });
        assertTrue(stored.await(WAIT_SECONDS, TimeUnit.SECONDS));

        pause(pausedNode.getHost(), pausedNode.getPort(), 1500);
        Outcomes slow = new Outcomes();
        Outcomes fine = new Outcomes();
        get(paused, slow);
        get(other, fine);

        assertEquals("value:alive", fine.events.poll(WAIT_SECONDS, TimeUnit.SECONDS));
        String event = slow.events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("no outcome", event);
        assertTrue(event, event.startsWith("error:TIMEOUT"));
        // the late reply must not reach the handler
        assertNull(slow.events.poll(3, TimeUnit.SECONDS));
        // and the node serves again afterwards
        Outcomes again = new Outcomes();
        get(paused, again);
        assertEquals("null", again.events.poll(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    public void aNodeThatGoesSilentIsDetectedAndUsedAgainOnceItAnswers() throws Exception {
        // no command timeout: only the health check can notice
        tearDown();
        connectWith(Duration.ZERO, Duration.ofMillis(500));
        String key = prefix + "silent";
        ClusterNode node = ownerOf(key);
        Outcomes waiting = new Outcomes();
        pause(node.getHost(), node.getPort(), 4000);
        long start = System.nanoTime();
        get(key, waiting);
        String event = waiting.events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertNotNull("the waiting command was never failed", event);
        assertTrue(event, event.startsWith("error:"));
        assertTrue("found out after " + elapsedMs + " ms, before the pause ended",
                elapsedMs < 3500);
        // once the pause is over and the node has been left alone for its
        // backoff, a new connection serves the key again
        // (while the node is on hold, commands for it fail at once, so the
        // attempts are paced)
        String outcome = null;
        Object pace = new Object();
        for (int attempt = 0; attempt < 40 && !"null".equals(outcome); attempt++) {
            Outcomes again = new Outcomes();
            get(key, again);
            outcome = again.events.poll(2, TimeUnit.SECONDS);
            if (!"null".equals(outcome)) {
                synchronized (pace) {
                    TimeUnit.MILLISECONDS.timedWait(pace, 250);
                }
            }
        }
        assertEquals("null", outcome);
    }
}
