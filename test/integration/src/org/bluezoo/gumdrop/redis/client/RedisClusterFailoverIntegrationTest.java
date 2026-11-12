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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * How soon {@link RedisClusterClient} serves a dead primary's slots again
 * once its replica has been promoted.
 *
 * <p>Opt-in, because it stops a container: set the environment variable
 * {@code GUMDROP_REDIS_FAILOVER_TEST=1}. It needs the podman cluster of
 * {@link RedisClusterIntegrationTest}, whose containers are named
 * {@code gd-valkey-<port>}, and {@code podman} on the path; it takes about
 * half a minute, and starts the container again afterwards.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterFailoverIntegrationTest {

    private static final String HOST = System.getProperty("redis.cluster.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("redis.cluster.port", 7100);
    private static final long WAIT_SECONDS = 10;

    private Gumdrop gumdrop;
    private SelectorLoop loop;
    private RedisClusterClient client;
    private volatile RedisClusterSession session;
    private String stopped;
    private final String prefix = "gd-cluster-failover-" + System.nanoTime() + "-";

    @Before
    public void connect() throws Exception {
        assumeTrue("set GUMDROP_REDIS_FAILOVER_TEST=1 to run", System.getenv("GUMDROP_REDIS_FAILOVER_TEST") != null);
        assumeTrue("no cluster node answering at " + HOST + ":" + PORT, reachable());
        gumdrop = Gumdrop.boot();
        loop = gumdrop.nextWorkerLoop();
        client = new RedisClusterClient().seed(HOST, PORT).selectorLoop(loop);
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
    public void tearDown() throws Exception {
        if (stopped != null) {
            podman("start", stopped);
        }
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

    private static void podman(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "podman";
        System.arraycopy(args, 0, command, 1, args.length);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals("podman " + String.join(" ", args), 0, p.waitFor());
    }

    /** The node a live node says serves the slot, per its own CLUSTER NODES, or null. */
    private static String ownerPerNode(String address, int slot) {
        int colon = address.lastIndexOf(':');
        try (Socket socket = new Socket(address.substring(0, colon),
                Integer.parseInt(address.substring(colon + 1)))) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write("*2\r\n$7\r\nCLUSTER\r\n$5\r\nNODES\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            String header = in.readLine();
            int length = Integer.parseInt(header.substring(1));
            char[] body = new char[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            for (String line : new String(body, 0, read).split("\n")) {
                String[] f = line.trim().split(" ");
                if (f.length < 9 || !f[2].contains("master") || f[2].contains("fail")) {
                    continue;
                }
                for (int i = 8; i < f.length; i++) {
                    String[] range = f[i].split("-");
                    if (range.length == 2 && !f[i].startsWith("[")
                            && slot >= Integer.parseInt(range[0])
                            && slot <= Integer.parseInt(range[1])) {
                        return f[1].substring(0, f[1].indexOf('@'));
                    }
                }
            }
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static final class Outcomes implements BulkResultHandler {
        final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<String>();

        @Override
        public void handleResult(byte[] value, RedisSession s) {
            events.add("value");
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
    public void slotsAreServedAgainSoonAfterTheReplicaIsPromoted() throws Exception {
        String key = prefix + "k";
        int slot = ClusterSlots.slot(key.getBytes(StandardCharsets.UTF_8));
        ClusterNode primary = ownerOf(key);
        Outcomes first = new Outcomes();
        get(key, first);
        assertEquals("null", first.events.poll(WAIT_SECONDS, TimeUnit.SECONDS));

        // some other node to ask who serves the slot
        String observer = null;
        for (int port = 7100; port <= 7105 && observer == null; port++) {
            if (port != primary.getPort()) {
                observer = "127.0.0.1:" + port;
            }
        }
        stopped = "gd-valkey-" + primary.getPort();
        podman("stop", "-t", "0", stopped);

        // Watch for the failover separately from the commands, which may
        // spend seconds waiting, so that its time is not blurred by them.
        final AtomicLong failedOver = new AtomicLong();
        final String watched = observer;
        final String old = primary.getAddress();
        final int watchedSlot = slot;
        final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                Object pause = new Object();
                while (!finished.get() && failedOver.get() == 0) {
                    String owner = ownerPerNode(watched, watchedSlot);
                    if (owner != null && !owner.equals(old)) {
                        failedOver.set(System.nanoTime());
                        return;
                    }
                    synchronized (pause) {
                        try {
                            TimeUnit.MILLISECONDS.timedWait(pause, 100);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        });
        watcher.setDaemon(true);
        watcher.start();

        long servedAt = 0;
        Object pace = new Object();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (servedAt == 0 && System.nanoTime() < deadline) {
            Outcomes outcome = new Outcomes();
            get(key, outcome);
            String event = outcome.events.poll(3, TimeUnit.SECONDS);
            if ("null".equals(event) || "value".equals(event)) {
                servedAt = System.nanoTime();
            } else {
                synchronized (pace) {
                    TimeUnit.MILLISECONDS.timedWait(pace, 250);
                }
            }
        }
        finished.set(true);
        watcher.join(5000);
        long failedOverAt = failedOver.get();
        assertTrue("never served again", servedAt != 0);
        assertTrue("the failover was never seen", failedOverAt != 0);
        long lagMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(servedAt - failedOverAt));
        System.out.println("served again " + lagMs + " ms after the failover completed");
        assertTrue("served again only " + lagMs + " ms after the failover", lagMs < 3000);
    }
}
