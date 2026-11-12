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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * {@link RedisClusterClient} against a real Redis or Valkey Cluster.
 *
 * <p>Not part of the normal integration run: it needs a cluster listening
 * locally, by default with a node on 127.0.0.1:7100 that announces
 * 127.0.0.1 addresses. Override with {@code -Dredis.cluster.host} and
 * {@code -Dredis.cluster.port}. The tests are skipped if nothing answers.
 *
 * <p>A suitable cluster of six Valkey nodes in one podman pod:
 * <pre>
 * podman pod create --name gd-valkey-cluster -p 7100-7105:7100-7105
 * for p in 7100 7101 7102 7103 7104 7105; do
 *   podman run -d --pod gd-valkey-cluster --name gd-valkey-$p docker.io/valkey/valkey:8 \
 *     valkey-server --port $p --cluster-enabled yes --cluster-config-file /tmp/nodes-$p.conf \
 *     --cluster-node-timeout 5000 --appendonly no --save ""
 * done
 * podman exec gd-valkey-7100 valkey-cli --cluster create 127.0.0.1:7100 127.0.0.1:7101 \
 *   127.0.0.1:7102 127.0.0.1:7103 127.0.0.1:7104 127.0.0.1:7105 --cluster-replicas 1 --cluster-yes
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterIntegrationTest {

    private static final String HOST = System.getProperty("redis.cluster.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("redis.cluster.port", 7100);
    private static final long TIMEOUT_SECONDS = 10;

    private Gumdrop gumdrop;
    private SelectorLoop loop;
    private RedisClusterClient client;
    protected RedisClusterSession session;
    protected final String prefix = "gd-cluster-" + System.nanoTime() + "-";

    /** The RESP version the client negotiates; subclasses rerun every test with another. */
    protected int protocol() {
        return 2;
    }

    @Before
    public void connect() throws Exception {
        assumeTrue("no cluster node answering at " + HOST + ":" + PORT, reachable());
        gumdrop = Gumdrop.boot();
        loop = gumdrop.nextWorkerLoop();
        client = new RedisClusterClient().seed(HOST, PORT).selectorLoop(loop).protocol(protocol());
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
        assertTrue("cluster not ready in time", ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
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

    // ── blocking helpers: run on the loop, wait on the test thread ──

    protected void onLoop(Runnable task) {
        loop.invokeLater(task);
    }

    protected String set(final String key, final String value) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> out = new AtomicReference<String>();
        onLoop(new Runnable() {
            @Override
            public void run() {
                session.set(key, value, new StringResultHandler() {
                    @Override
                    public void handleResult(String result, RedisSession s) {
                        out.set(result);
                        done.countDown();
                    }

                    @Override
                    public void handleError(String error, RedisSession s) {
                        out.set("error:" + error);
                        done.countDown();
                    }
                });
            }
        });
        assertTrue("SET timed out", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return out.get();
    }

    protected String get(final String key) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> out = new AtomicReference<String>();
        onLoop(new Runnable() {
            @Override
            public void run() {
                session.get(key, new BulkResultHandler() {
                    @Override
                    public void handleResult(byte[] value, RedisSession s) {
                        out.set(new String(value, StandardCharsets.UTF_8));
                        done.countDown();
                    }

                    @Override
                    public void handleNull(RedisSession s) {
                        out.set(null);
                        done.countDown();
                    }

                    @Override
                    public void handleError(String error, RedisSession s) {
                        out.set("error:" + error);
                        done.countDown();
                    }
                });
            }
        });
        assertTrue("GET timed out", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        return out.get();
    }

    /** Runs a generic command and returns the array reply (or "error:..." as one element). */
    protected List<RespValue> command(final String name, final String... args) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<List<RespValue>> out = new AtomicReference<List<RespValue>>();
        final AtomicReference<String> error = new AtomicReference<String>();
        onLoop(new Runnable() {
            @Override
            public void run() {
                session.command(new ArrayResultHandler() {
                    @Override
                    public void handleResult(List<RespValue> values, RedisSession s) {
                        out.set(values);
                        done.countDown();
                    }

                    @Override
                    public void handleNull(RedisSession s) {
                        done.countDown();
                    }

                    @Override
                    public void handleError(String message, RedisSession s) {
                        error.set(message);
                        done.countDown();
                    }
                }, name, args);
            }
        });
        assertTrue(name + " timed out", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (error.get() != null) {
            fail(name + " failed: " + error.get());
        }
        return out.get();
    }

    // ── tests ──

    @Test
    public void keysSpreadAcrossTheNodesAndReadBackCorrectly() throws Exception {
        for (int i = 0; i < 60; i++) {
            assertEquals("OK", set(prefix + i, "value-" + i));
        }
        for (int i = 0; i < 60; i++) {
            assertEquals("value-" + i, get(prefix + i));
        }
        assertTrue("several nodes were used: " + session.connectionCount(),
                session.connectionCount() >= 3);
    }

    @Test
    public void anAbsentKeyIsNull() throws Exception {
        assertNull(get(prefix + "absent"));
    }

    @Test
    public void keysSharingAHashTagCanBeReadTogether() throws Exception {
        String tag = "{" + prefix + "tag}";
        assertEquals("OK", set(tag + ".a", "1"));
        assertEquals("OK", set(tag + ".b", "2"));
        List<RespValue> values = command("MGET", tag + ".a", tag + ".b");
        assertEquals(2, values.size());
        assertEquals("1", values.get(0).asString());
        assertEquals("2", values.get(1).asString());
    }

    @Test
    public void keysInDifferentSlotsAreRefusedBeforeReachingTheServer() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<String>();
        onLoop(new Runnable() {
            @Override
            public void run() {
                session.del(new IntegerResultHandler() {
                    @Override
                    public void handleResult(long result, RedisSession s) {
                        done.countDown();
                    }

                    @Override
                    public void handleError(String message, RedisSession s) {
                        error.set(message);
                        done.countDown();
                    }
                }, prefix + "x", prefix + "y");
            }
        });
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNotNull(error.get());
        assertTrue(error.get(), error.get().startsWith("CROSSSLOT"));
    }

    @Test
    public void aStaleSlotMapIsCorrectedByFollowingMoved() throws Exception {
        final String key = prefix + "moved";
        assertEquals("OK", set(key, "found"));
        final int slot = ClusterSlots.slot(key.getBytes(StandardCharsets.UTF_8));
        final AtomicReference<ClusterNode> correct = new AtomicReference<ClusterNode>();
        final CountDownLatch swapped = new CountDownLatch(1);
        onLoop(new Runnable() {
            @Override
            public void run() {
                SlotMap map = session.slotMap();
                ClusterNode owner = map.nodeFor(slot);
                correct.set(owner);
                // point the slot at some other master, as a stale map would
                for (ClusterNode other : map.nodes()) {
                    if (!other.equals(owner)) {
                        map.set(slot, other);
                        break;
                    }
                }
                swapped.countDown();
            }
        });
        assertTrue(swapped.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("found", get(key));
        final AtomicReference<ClusterNode> now = new AtomicReference<ClusterNode>();
        final CountDownLatch read = new CountDownLatch(1);
        onLoop(new Runnable() {
            @Override
            public void run() {
                now.set(session.slotMap().nodeFor(slot));
                read.countDown();
            }
        });
        assertTrue(read.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("MOVED corrected the map", correct.get(), now.get());
    }

    @Test
    public void aTransactionRunsOnOneNode() throws Exception {
        final String tag = "{" + prefix + "tx}";
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> outcome = new AtomicReference<String>();
        onLoop(new Runnable() {
            @Override
            public void run() {
                final StringResultHandler ignore = new StringResultHandler() {
                    @Override
                    public void handleResult(String result, RedisSession s) {
                    }

                    @Override
                    public void handleError(String error, RedisSession s) {
                        outcome.set("queue error:" + error);
                    }
                };
                session.multi(ignore);
                session.set(tag + ".a", "1", ignore);
                session.set(tag + ".b", "2", ignore);
                session.exec(new ArrayResultHandler() {
                    @Override
                    public void handleResult(List<RespValue> values, RedisSession s) {
                        if (outcome.get() == null) {
                            outcome.set("exec:" + values.size());
                        }
                        done.countDown();
                    }

                    @Override
                    public void handleNull(RedisSession s) {
                        outcome.set("aborted");
                        done.countDown();
                    }

                    @Override
                    public void handleError(String error, RedisSession s) {
                        outcome.set("exec error:" + error);
                        done.countDown();
                    }
                });
            }
        });
        assertTrue("EXEC timed out", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("exec:2", outcome.get());
        assertEquals("1", get(tag + ".a"));
        assertEquals("2", get(tag + ".b"));
    }

    @Test
    public void aScriptRunsOnTheNodeOfItsKey() throws Exception {
        final String key = prefix + "script";
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> outcome = new AtomicReference<String>();
        onLoop(new Runnable() {
            @Override
            public void run() {
                session.eval("return redis.call('set', KEYS[1], ARGV[1])", 1,
                        new String[] { key }, new String[] { "from-lua" }, new ArrayResultHandler() {
                            @Override
                            public void handleResult(List<RespValue> values, RedisSession s) {
                                outcome.set("array");
                                done.countDown();
                            }

                            @Override
                            public void handleNull(RedisSession s) {
                                outcome.set("null");
                                done.countDown();
                            }

                            @Override
                            public void handleError(String error, RedisSession s) {
                                outcome.set("error:" + error);
                                done.countDown();
                            }
                        });
            }
        });
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertFalse(outcome.get(), outcome.get().startsWith("error:"));
        assertEquals("from-lua", get(key));
    }

    @Test
    public void setWithOptionsWorksThroughTheCluster() throws Exception {
        final String key = prefix + "nx";
        final CountDownLatch done = new CountDownLatch(2);
        final Set<String> outcomes = new HashSet<String>();
        onLoop(new Runnable() {
            @Override
            public void run() {
                BulkResultHandler record = new BulkResultHandler() {
                    @Override
                    public void handleResult(byte[] value, RedisSession s) {
                        synchronized (outcomes) {
                            outcomes.add("set");
                        }
                        done.countDown();
                    }

                    @Override
                    public void handleNull(RedisSession s) {
                        synchronized (outcomes) {
                            outcomes.add("refused");
                        }
                        done.countDown();
                    }

                    @Override
                    public void handleError(String error, RedisSession s) {
                        synchronized (outcomes) {
                            outcomes.add("error:" + error);
                        }
                        done.countDown();
                    }
                };
                session.set(key, "first", new SetOptions().nx().px(60000), record);
                session.set(key, "second", new SetOptions().nx().px(60000), record);
            }
        });
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        synchronized (outcomes) {
            assertTrue(outcomes.toString(), outcomes.contains("set"));
            assertTrue(outcomes.toString(), outcomes.contains("refused"));
        }
        assertEquals("first", get(key));
    }

    // ── Pub/Sub ──

    private static final class Events implements MessageHandler {
        final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>();

        private static String text(byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }

        @Override
        public void handleMessage(String channel, byte[] message) {
            queue.add("message:" + channel + ":" + text(message));
        }

        @Override
        public void handlePatternMessage(String pattern, String channel, byte[] message) {
            queue.add("pmessage:" + pattern + ":" + channel + ":" + text(message));
        }

        @Override
        public void handleSubscribed(String channel, int count) {
            queue.add("subscribed:" + channel);
        }

        @Override
        public void handleUnsubscribed(String channel, int count) {
            queue.add("unsubscribed:" + channel);
        }

        @Override
        public void handlePatternSubscribed(String pattern, int count) {
            queue.add("psubscribed:" + pattern);
        }

        @Override
        public void handlePatternUnsubscribed(String pattern, int count) {
            queue.add("punsubscribed:" + pattern);
        }

        @Override
        public void handleShardMessage(String channel, byte[] message) {
            queue.add("smessage:" + channel + ":" + text(message));
        }

        @Override
        public void handleShardSubscribed(String channel, int count) {
            queue.add("ssubscribed:" + channel);
        }

        @Override
        public void handleShardUnsubscribed(String channel, int count) {
            queue.add("sunsubscribed:" + channel);
        }

        @Override
        public void handleError(String error) {
            queue.add("error:" + error);
        }

        String next() throws InterruptedException {
            return queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static final class Ignored implements IntegerResultHandler {
        @Override
        public void handleResult(long value, RedisSession s) {
        }

        @Override
        public void handleError(String error, RedisSession s) {
        }
    }

    @Test
    public void ordinaryPubSubReachesSubscribersOnAnyNode() throws Exception {
        final String channel = prefix + "news";
        final Events events = new Events();
        onLoop(new Runnable() {
            @Override
            public void run() {
                session.subscribe(events, channel);
                session.psubscribe(events, prefix + "n*");
            }
        });
        assertEquals("subscribed:" + channel, events.next());
        assertEquals("psubscribed:" + prefix + "n*", events.next());
        onLoop(new Runnable() {
            @Override
            public void run() {
                session.publish(channel, "hello", new Ignored());
            }
        });
        // one for the channel, one for the pattern; either order
        Set<String> got = new HashSet<String>();
        got.add(events.next());
        got.add(events.next());
        assertTrue(got.toString(), got.contains("message:" + channel + ":hello"));
        assertTrue(got.toString(), got.contains(
                "pmessage:" + prefix + "n*:" + channel + ":hello"));
    }

    @Test
    public void shardedPubSubFollowsEachChannelToItsNode() throws Exception {
        final Events events = new Events();
        final String[] channels = new String[8];
        for (int i = 0; i < channels.length; i++) {
            channels[i] = prefix + "shard" + i;
        }
        onLoop(new Runnable() {
            @Override
            public void run() {
                session.ssubscribe(events, channels);
            }
        });
        for (int i = 0; i < channels.length; i++) {
            String event = events.next();
            assertTrue(String.valueOf(event), event != null && event.startsWith("ssubscribed:"));
        }
        // the channels are spread over the shards, so several nodes hold some
        assertTrue(session.connectionCount() >= 1);
        onLoop(new Runnable() {
            @Override
            public void run() {
                for (String channel : channels) {
                    session.spublish(channel, "m-" + channel, new Ignored());
                }
            }
        });
        Set<String> got = new HashSet<String>();
        for (int i = 0; i < channels.length; i++) {
            got.add(events.next());
        }
        for (String channel : channels) {
            assertTrue(got.toString(), got.contains("smessage:" + channel + ":m-" + channel));
        }
    }

    // ── keyspace notifications ──

    /** Addresses of the cluster's nodes, split by role, from CLUSTER NODES. */
    private List<String[]> clusterNodes() throws Exception {
        String text = command("CLUSTER", "NODES").get(0).asString();
        List<String[]> out = new ArrayList<String[]>();
        for (String line : text.split("\n")) {
            String[] f = line.trim().split(" ");
            if (f.length > 2) {
                String address = f[1].substring(0, f[1].indexOf('@'));
                out.add(new String[] { address, f[2].contains("slave") ? "replica" : "primary" });
            }
        }
        return out;
    }

    /** One command to one node over its own connection; returns the first reply line. */
    private static String raw(String address, String... parts) throws IOException {
        int colon = address.lastIndexOf(':');
        try (Socket socket = new Socket(address.substring(0, colon),
                Integer.parseInt(address.substring(colon + 1)))) {
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
            return in.readLine();
        }
    }

    /** Switches keyspace notifications on, on every node (replicas may be promoted later). */
    private void enableNotifications() throws Exception {
        for (String[] node : clusterNodes()) {
            assertEquals("+OK", raw(node[0], "CONFIG", "SET", "notify-keyspace-events", "KEA"));
        }
    }

    /** Collects keyspace events; waiters block on a monitor, not by sleeping. */
    private static final class KeyspaceEvents implements MessageHandler {
        private final Object lock = new Object();
        private final Set<String> keys = new HashSet<String>();
        private int subscribed;
        final List<String> errors = java.util.Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void handleMessage(String channel, byte[] message) {
        }

        @Override
        public void handlePatternMessage(String pattern, String channel, byte[] message) {
            if (new String(message, StandardCharsets.UTF_8).equals("set")) {
                synchronized (lock) {
                    keys.add(channel.substring(channel.indexOf(':') + 1));
                    lock.notifyAll();
                }
            }
        }

        @Override
        public void handleSubscribed(String channel, int count) {
        }

        @Override
        public void handleUnsubscribed(String channel, int count) {
        }

        @Override
        public void handlePatternSubscribed(String pattern, int count) {
            synchronized (lock) {
                subscribed++;
                lock.notifyAll();
            }
        }

        @Override
        public void handlePatternUnsubscribed(String pattern, int count) {
        }

        @Override
        public void handleError(String error) {
            errors.add(error);
        }

        int subscribed() {
            synchronized (lock) {
                return subscribed;
            }
        }

        /** Waits until at least {@code n} subscriptions were confirmed. */
        boolean awaitSubscribed(int n, long seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            synchronized (lock) {
                while (subscribed < n) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return false;
                    }
                    TimeUnit.NANOSECONDS.timedWait(lock, left);
                }
                return true;
            }
        }

        /** Waits until a {@code set} event was seen for every key. */
        boolean awaitKeys(List<String> wanted, long seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            synchronized (lock) {
                while (!keys.containsAll(wanted)) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return false;
                    }
                    TimeUnit.NANOSECONDS.timedWait(lock, left);
                }
                return true;
            }
        }

        int seen() {
            synchronized (lock) {
                return keys.size();
            }
        }
    }

    private int primaryCount() throws Exception {
        int primaries = 0;
        for (String[] node : clusterNodes()) {
            if (node[1].equals("primary")) {
                primaries++;
            }
        }
        return primaries;
    }

    private KeyspaceEvents subscribeKeyspace() throws Exception {
        enableNotifications();
        final KeyspaceEvents events = new KeyspaceEvents();
        onLoop(new Runnable() {
            @Override
            public void run() {
                client.subscribeKeyspace("__keyspace@0__:" + prefix + "*", events);
            }
        });
        int primaries = primaryCount();
        assertTrue("subscribed on every primary; errors " + events.errors,
                events.awaitSubscribed(primaries, TIMEOUT_SECONDS));
        return events;
    }

    private List<String> writeKeys(String stem, int count) throws Exception {
        List<String> keys = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            String key = prefix + stem + i;
            keys.add(key);
            set(key, "v");
        }
        return keys;
    }

    @Test
    public void keyspaceEventsArriveFromEveryShard() throws Exception {
        KeyspaceEvents events = subscribeKeyspace();
        List<String> keys = writeKeys("k", 40);
        assertTrue("seen " + events.seen() + " of " + keys.size(),
                events.awaitKeys(keys, TIMEOUT_SECONDS));
    }

    @Test
    public void keyspaceEventsContinueAfterAFailover() throws Exception {
        KeyspaceEvents events = subscribeKeyspace();
        String replica = null;
        for (String[] node : clusterNodes()) {
            if (node[1].equals("replica")) {
                replica = node[0];
                break;
            }
        }
        assertNotNull("the cluster has a replica to promote", replica);
        final int before = events.subscribed();
        assertEquals("+OK", raw(replica, "CLUSTER", "FAILOVER"));
        // The command returns before the promotion. Writes to the slots that
        // moved are redirected, which makes the client refresh its topology
        // and subscribe to the new primary; keep writing until it has.
        boolean followed = false;
        for (int round = 0; round < 20 && !followed; round++) {
            writeKeys("f" + round + "-", 40);
            followed = events.awaitSubscribed(before + 1, 1);
        }
        assertTrue("the new primary was subscribed to; errors " + events.errors, followed);
        // and events from every shard flow again
        List<String> keys = writeKeys("after", 40);
        assertTrue("seen " + events.seen() + " events; errors " + events.errors,
                events.awaitKeys(keys, TIMEOUT_SECONDS));
    }
}
