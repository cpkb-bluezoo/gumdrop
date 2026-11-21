import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.redis.client.BulkResultHandler;
import org.bluezoo.gumdrop.redis.client.ReplyStream;
import org.bluezoo.gumdrop.redis.codec.ReplyFlow;
import org.bluezoo.gumdrop.redis.client.IntegerResultHandler;
import org.bluezoo.gumdrop.redis.client.RedisClient;
import org.bluezoo.gumdrop.redis.client.RedisClusterClient;
import org.bluezoo.gumdrop.redis.client.RedisClusterReady;
import org.bluezoo.gumdrop.redis.client.RedisConnectionReady;
import org.bluezoo.gumdrop.redis.client.RedisSession;
import org.bluezoo.gumdrop.redis.client.StringResultHandler;
import org.bluezoo.gumdrop.tls.TlsConfig;

/**
 * Closed-loop benchmark of the Redis client against a live Redis or Valkey.
 *
 * <p>One connection (or one cluster client) keeps a fixed number of
 * commands in flight: each completion issues the next command, so the
 * number in flight is the pipeline depth. Statistics are updated on the
 * selector loop only.
 *
 * <p>Usage: {@code RedisBench <workload> <host> <port> <tls> <depth>
 * <valueBytes> <warmupSeconds> <seconds>} where workload is one of
 * <ul>
 * <li>{@code getset}: 50% GET, 50% SET over 1000 keys (keyed on a cluster)</li>
 * <li>{@code publish}: PUBLISH to one channel with no subscribers</li>
 * <li>{@code ping}: PING (keyless)</li>
 * </ul>
 * and the host and port are a standalone node, or, with workload prefix
 * {@code cluster-}, a cluster seed node. Prints one CSV row.
 *
 * <p>CPU time and allocation are for the whole benchmark JVM (the client,
 * the load generator's own handler objects, and the selector loops); the
 * server runs elsewhere.
 */
public class RedisBench {

    static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    /** Latency histogram: 10 microsecond buckets to 1 s, then one overflow bucket. */
    static final int BUCKETS = 100000;

    String workload;
    int depth;
    String value;
    byte[] valueBytes;
    long warmupNanos;
    long runNanos;

    // Loop-thread state.
    RedisSession session;
    long startNanos;
    long measureStart;
    long endNanos;
    boolean measuring;
    boolean stopping;
    int inFlight;
    long seq;
    long ops;
    long errors;
    long[] histogram = new long[BUCKETS + 1];
    long cpu0;
    long alloc0;
    long cpu1;
    long alloc1;
    long measureEnd;
    SelectorLoop loop;
    final CountDownLatch finished = new CountDownLatch(1);

    public static void main(String[] a) throws Exception {
        RedisBench b = new RedisBench();
        String workload = a[0];
        String host = a[1];
        int port = Integer.parseInt(a[2]);
        boolean tls = Boolean.parseBoolean(a[3]);
        b.depth = Integer.parseInt(a[4]);
        int valueSize = Integer.parseInt(a[5]);
        b.warmupNanos = (long) (Double.parseDouble(a[6]) * 1e9);
        b.runNanos = (long) (Double.parseDouble(a[7]) * 1e9);
        StringBuilder sb = new StringBuilder(valueSize);
        for (int i = 0; i < valueSize; i++) {
            sb.append('v');
        }
        b.value = sb.toString();
        b.valueBytes = b.value.getBytes("US-ASCII");
        boolean cluster = workload.startsWith("cluster-");
        b.workload = cluster ? workload.substring(8) : workload;
        b.run(host, port, tls, cluster, workload, valueSize);
    }

    void run(String host, int port, boolean tls, boolean cluster, String label, int valueSize)
            throws Exception {
        Gumdrop gumdrop = Gumdrop.boot();
        loop = gumdrop.nextWorkerLoop();
        final CountDownLatch ready = new CountDownLatch(1);
        final Exception[] failure = new Exception[1];
        TlsConfig tlsConfig = new TlsConfig().verifyPeer(false);
        Object client;
        if (cluster) {
            RedisClusterClient c = new RedisClusterClient().seed(host, port).selectorLoop(loop)
                    .secure(tls).tls(tlsConfig).maxPendingCommands(65536);
            client = c;
            c.connect(gumdrop, new RedisClusterReady() {
                public void handleReady(RedisSession s) {
                    session = s;
                    ready.countDown();
                }

                public void onError(Exception cause) {
                    failure[0] = cause;
                    ready.countDown();
                }
            });
        } else {
            RedisClient c = new RedisClient(host, port).selectorLoop(loop)
                    .secure(tls).tls(tlsConfig).maxPendingCommands(65536);
            client = c;
            c.connect(gumdrop, new RedisConnectionReady() {
                public void handleReady(RedisSession s) {
                    session = s;
                    ready.countDown();
                }

                public void onError(Exception cause) {
                    failure[0] = cause;
                    ready.countDown();
                }

                public void onConnected(org.bluezoo.gumdrop.Endpoint endpoint) {
                }

                public void onSecurityEstablished(org.bluezoo.gumdrop.SecurityInfo info) {
                }

                public void onDisconnected() {
                }
            });
        }
        if (!ready.await(15, TimeUnit.SECONDS) || failure[0] != null) {
            System.err.println("connect failed: " + failure[0]);
            System.exit(2);
        }
        loop.invokeLater(new Runnable() {
            public void run() {
                startNanos = System.nanoTime();
                measureStart = startNanos + warmupNanos;
                endNanos = measureStart + runNanos;
                for (int i = 0; i < depth; i++) {
                    issue();
                }
            }
        });
        if (!finished.await(120 + (warmupNanos + runNanos) / 1000000000L, TimeUnit.SECONDS)) {
            System.err.println("benchmark did not finish");
            System.exit(3);
        }
        double secs = (measureEnd - measureStart) / 1e9;
        System.out.printf("%s,%d,%b,%d,%d,%d,%.2f,%.0f,%d,%d,%d,%.2f,%d%n",
                label, depth, tls, valueSize, ops, errors, secs, ops / secs,
                percentile(0.50), percentile(0.99), percentile(0.999),
                ops == 0 ? 0.0 : (cpu1 - cpu0) / 1000.0 / ops,
                ops == 0 ? 0 : (alloc1 - alloc0) / ops);
        if (client instanceof RedisClient) {
            ((RedisClient) client).close();
        } else {
            ((RedisClusterClient) client).close();
        }
        gumdrop.shutdown();
        System.exit(0);
    }

    long percentile(double p) {
        long target = (long) Math.ceil(ops * p);
        long seen = 0;
        for (int i = 0; i <= BUCKETS; i++) {
            seen += histogram[i];
            if (seen >= target) {
                return (i + 1) * 10L;
            }
        }
        return BUCKETS * 10L;
    }

    static long allocatedAllThreads() {
        long[] ids = MX.getAllThreadIds();
        long[] bytes = MX.getThreadAllocatedBytes(ids);
        long total = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] > 0) {
                total += bytes[i];
            }
        }
        return total;
    }

    /** Completion of one command; runs on the loop. */
    class Done implements StringResultHandler, BulkResultHandler, IntegerResultHandler {
        final long sent;

        Done(long sent) {
            this.sent = sent;
        }

        public void handleResult(String result, RedisSession s) {
            finish(false);
        }

        // the bulk reply is discarded as it arrives, as a streaming
        // consumer that wants only the outcome would
        public void handleNull() {
        }

        public ReplyFlow handleBegin(long length, ReplyStream stream) {
            return ReplyFlow.CONTINUE;
        }

        public ReplyFlow handleContent(java.nio.ByteBuffer chunk) {
            return ReplyFlow.CONTINUE;
        }

        public void handleEnd() {
        }

        public void handleComplete(String error, RedisSession s) {
            finish(error != null);
        }

        public void handleResult(long v, RedisSession s) {
            finish(false);
        }

        public void handleError(String error, RedisSession s) {
            finish(true);
        }

        void finish(boolean error) {
            long now = System.nanoTime();
            inFlight--;
            if (measuring && sent >= measureStart) {
                if (error) {
                    errors++;
                } else {
                    ops++;
                    long bucket = (now - sent) / 10000L;
                    histogram[(int) Math.min(bucket, BUCKETS)]++;
                }
            }
            issue();
        }
    }

    void issue() {
        long now = System.nanoTime();
        if (!measuring && now >= measureStart) {
            measuring = true;
            cpu0 = OS.getProcessCpuTime();
            alloc0 = allocatedAllThreads();
        }
        if (now >= endNanos) {
            if (!stopping) {
                stopping = true;
                measureEnd = now;
                cpu1 = OS.getProcessCpuTime();
                alloc1 = allocatedAllThreads();
            }
            if (inFlight == 0) {
                finished.countDown();
            }
            return;
        }
        long n = seq++;
        Done d = new Done(now);
        inFlight++;
        String key = "bench:" + (n % 1000);
        if ("publish".equals(workload)) {
            session.publish("bench-channel", valueBytes, d);
        } else if ("ping".equals(workload)) {
            session.ping(d);
        } else if ((n & 1) == 0) {
            session.get(key, d);
        } else {
            session.set(key, valueBytes, d);
        }
    }
}
