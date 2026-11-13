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
import java.util.ArrayList;
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
 * {@code WAIT} over {@link RedisClusterClient} against a real cluster with
 * replicas: it runs on the primary that took the write, on the connection
 * that carried it. Same cluster and properties as
 * {@link RedisClusterIntegrationTest}.
 *
 * <p>Where a {@code WAIT} ran is read from the servers: after it completes,
 * the connection that ran it is the one whose last command {@code CLIENT LIST}
 * reports as {@code wait}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterWaitIntegrationTest {

    private static final String HOST = System.getProperty("redis.cluster.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("redis.cluster.port", 7100);
    private static final long WAIT_SECONDS = 10;

    private Gumdrop gumdrop;
    private SelectorLoop loop;
    private RedisClusterClient client;
    private volatile RedisClusterSession session;
    private final String prefix = "gd-wait-" + System.nanoTime() + "-";

    @Before
    public void connect() throws Exception {
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

    /** One command to one node over its own connection; returns the bulk string it replies with. */
    private static String rawBulk(String host, int port, String... parts) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(10000);
            StringBuilder sb = new StringBuilder("*" + parts.length + "\r\n");
            for (String p : parts) {
                sb.append('$').append(p.getBytes(StandardCharsets.UTF_8).length).append("\r\n")
                        .append(p).append("\r\n");
            }
            socket.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
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
            return new String(body, 0, read);
        }
    }

    private List<ClusterNode> primaries() throws Exception {
        final List<ClusterNode> out = new ArrayList<ClusterNode>();
        final CountDownLatch done = new CountDownLatch(1);
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                out.addAll(session.slotMap().nodes());
                done.countDown();
            }
        });
        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS));
        return out;
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

    /** The primaries on which some client connection's last command was WAIT. */
    private List<String> primariesWhoseLastCommandWasWait() throws Exception {
        List<String> found = new ArrayList<String>();
        for (ClusterNode node : primaries()) {
            String list = rawBulk(node.getHost(), node.getPort(), "CLIENT", "LIST");
            for (String line : list.split("\n")) {
                if (line.contains(" cmd=wait ") || line.endsWith(" cmd=wait")) {
                    found.add(node.getAddress());
                    break;
                }
            }
        }
        return found;
    }

    private static final class Events implements IntegerResultHandler {
        final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<String>();

        @Override
        public void handleResult(long value, RedisSession s) {
            events.add("count:" + value);
        }

        @Override
        public void handleError(String error, RedisSession s) {
            events.add("error:" + error);
        }
    }

    private static final class Raw implements ArrayResultHandler {
        final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<String>();

        @Override
        public void handleResult(List<RespValue> values, RedisSession s) {
            events.add("value:" + (values.get(0).isInteger() ? values.get(0).asLong() : values.get(0).asString()));
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

    private void evalSet(final String key) throws Exception {
        final Raw done = new Raw();
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                session.eval("return redis.call('SET', KEYS[1], 'v')", 1, new String[] { key },
                        new String[0], done);
            }
        });
        String event = done.events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(event);
        assertFalse(event, event.startsWith("error"));
    }

    private Events waitAfterWrite(final String slotKey, final int replicas, final long timeoutMs)
            throws Exception {
        final Events events = new Events();
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                session.waitReplicas(slotKey, replicas, timeoutMs, events);
            }
        });
        return events;
    }

    @Test
    public void aWaitRunsOnThePrimaryThatTookTheWriteAndNowhereElse() throws Exception {
        String tagged = "{" + prefix + "k}";
        evalSet(tagged + "a");
        Events wait = waitAfterWrite(tagged, 1, 2000);
        String result = wait.events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("no outcome", result);
        assertTrue(result, result.startsWith("count:"));
        assertTrue("replicated: " + result, Long.parseLong(result.substring(6)) >= 1);
        List<String> where = primariesWhoseLastCommandWasWait();
        assertEquals("exactly one primary ran it: " + where, 1, where.size());
        assertEquals(ownerOf(tagged).getAddress(), where.get(0));
    }

    @Test
    public void fewerReplicasThanAskedForIsACountAfterTheServerTimeout() throws Exception {
        String tagged = "{" + prefix + "t}";
        evalSet(tagged + "a");
        long start = System.nanoTime();
        Events wait = waitAfterWrite(tagged, 5, 300);   // no cluster here has five replicas per primary
        String result = wait.events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertNotNull(result);
        assertTrue(result, result.startsWith("count:"));
        assertTrue("fewer than five: " + result, Long.parseLong(result.substring(6)) < 5);
        assertTrue("waited for the timeout: " + elapsedMs, elapsedMs >= 250);
    }

    @Test
    public void waitsForDifferentSlotsEachReachTheirOwnPrimary() throws Exception {
        List<String> tags = new ArrayList<String>();
        List<String> owners = new ArrayList<String>();
        for (int i = 0; i < 200 && owners.size() < 3; i++) {
            String tag = "{" + prefix + "s" + i + "}";
            String owner = ownerOf(tag).getAddress();
            if (!owners.contains(owner)) {
                owners.add(owner);
                tags.add(tag);
            }
        }
        assertEquals("keys on three different primaries", 3, owners.size());
        for (int i = 0; i < tags.size(); i++) {
            evalSet(tags.get(i) + "a");
            Events wait = waitAfterWrite(tags.get(i), 1, 2000);
            assertNotNull(wait.events.poll(WAIT_SECONDS, TimeUnit.SECONDS));
            List<String> where = primariesWhoseLastCommandWasWait();
            assertTrue(where.toString(), where.contains(owners.get(i)));
        }
    }

    @Test
    public void aWriteAnsweredMovedFailsTheWaitBehindIt() throws Exception {
        final String tagged = "{" + prefix + "m}";
        final ClusterNode owner = ownerOf(tagged);
        final int slot = ClusterSlots.slot(tagged.getBytes(StandardCharsets.UTF_8));
        final CountDownLatch swapped = new CountDownLatch(1);
        final Raw setDone = new Raw();
        final Events wait = new Events();
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                // point the slot at another primary, as a stale map would
                SlotMap map = session.slotMap();
                for (ClusterNode other : map.nodes()) {
                    if (!other.equals(owner)) {
                        map.set(slot, other);
                        break;
                    }
                }
                swapped.countDown();
                session.commandForKey(tagged, setDone, "SET", tagged + "a", "v");
                session.waitReplicas(tagged, 1, 1000, wait);
            }
        });
        assertTrue(swapped.await(WAIT_SECONDS, TimeUnit.SECONDS));
        String set = setDone.events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(set);
        String result = wait.events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("the WAIT was never answered", result);
        assertTrue(result, result.startsWith("error:ERR WAIT target moved"));
        assertNull("answered once", wait.events.poll(2, TimeUnit.SECONDS));
    }

    @Test
    public void aCommandForAKeyIsSentToItsSlotsPrimaryOnTheConnectionOfThatSlot() throws Exception {
        String tagged = "{" + prefix + "c}";
        final Raw first = new Raw();
        final Raw second = new Raw();
        final String key = tagged;
        loop.invokeLater(new Runnable() {
            @Override
            public void run() {
                session.commandForKey(key, first, "CLIENT", "ID");
                session.commandForKey(key, second, "CLIENT", "ID");
            }
        });
        String a = first.events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        String b = second.events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(a);
        assertTrue(a, a.startsWith("value:"));
        assertEquals("the same connection both times", a, b);
    }
}
