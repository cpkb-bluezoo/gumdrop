import java.nio.ByteBuffer;
import org.bluezoo.gumdrop.http.HttpVersion;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.http.HttpClient;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.client.DefaultHttpResponseHandler;
import org.bluezoo.gumdrop.http.client.HttpClientHandler;

/**
 * Closed-loop HTTP/3 load generator with the same options, histogram and
 * CSV line as LoadClient. The JDK HttpClient does not speak HTTP/3, so this
 * is built on Gumdrop's own HTTP/3 client: both servers under test are
 * driven by the same client, but that client is Gumdrop's QUIC stack, so its
 * cost is part of every figure and a Gumdrop-to-Gumdrop run exercises one
 * implementation at both ends.
 *
 * Each concurrent worker has its own connection and issues requests on it
 * one after another, the HTTP/3 counterpart of the keep-alive scenarios.
 */
public class H3LoadClient {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = LoadClient.parseArgs(args);
        final String host = LoadClient.req(opt, "host");
        final int port = Integer.parseInt(LoadClient.req(opt, "port"));
        final String path = opt.getOrDefault("path", "/");
        final int concurrency = Integer.parseInt(opt.getOrDefault("concurrency", "50"));
        int durationSec = Integer.parseInt(opt.getOrDefault("duration", "10"));
        int warmupSec = Integer.parseInt(opt.getOrDefault("warmup", "3"));
        String label = opt.getOrDefault("label", host + ":" + port);

        final Gumdrop gumdrop = Gumdrop.boot();

        System.out.println("=== " + label + " ===");
        System.out.printf("url=https://%s:%d%s concurrency=%d duration=%ds warmup=%ds method=GET client=gumdrop-h3%n",
                host, port, path, concurrency, durationSec, warmupSec);

        final AtomicLong okCount = new AtomicLong();
        final AtomicLong errCount = new AtomicLong();
        final AtomicLong bytesReceived = new AtomicLong();
        final LoadClient.Histogram hist = new LoadClient.Histogram();
        final AtomicReference<String> negotiatedVersion = new AtomicReference<String>();
        final LoadClient.volatileFlag warmupDone = new LoadClient.volatileFlag();
        final LoadClient.volatileFlag stop = new LoadClient.volatileFlag();
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(concurrency);

        final long connectIntervalMs = Long.parseLong(opt.getOrDefault("connect-interval-ms", "0"));
        for (int i = 0; i < concurrency; i++) {
            final int index = i;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    HttpClient client = null;
                    try {
                        startLatch.await();
                        if (connectIntervalMs > 0) {
                            Thread.sleep(connectIntervalMs * index);
                        }
                        client = connect(gumdrop, host, port);
                        if (client == null) {
                            errCount.incrementAndGet();
                            return;
                        }
                        negotiatedVersion.compareAndSet(null, String.valueOf(client.getVersion()));
                        long answered = 0;
                        while (!stop.value) {
                            long start = System.nanoTime();
                            Exchange exchange = new Exchange();
                            client.request(HttpMethod.GET, path, exchange).endMessage();
                            boolean completed = exchange.done.await(10, TimeUnit.SECONDS);
                            long elapsed = System.nanoTime() - start;
                            if (completed && exchange.status >= 200 && exchange.status < 300) {
                                answered++;
                            } else if (!stop.value && FAILURES_REPORTED.incrementAndGet() <= 5) {
                                System.err.println("request not answered: completed=" + completed
                                        + " status=" + exchange.status + " after " + (elapsed / 1000000L)
                                        + " ms, following " + answered + " answered on this connection");
                            }
                            if (!warmupDone.value) {
                                continue;
                            }
                            if (completed && exchange.status >= 200 && exchange.status < 300) {
                                okCount.incrementAndGet();
                                bytesReceived.addAndGet(exchange.bytes);
                                hist.record(elapsed);
                            } else if (!stop.value) {
                                errCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ignored) {
                    } catch (RuntimeException e) {
                        errCount.incrementAndGet();
                    } finally {
                        if (client != null) {
                            client.close();
                        }
                        doneLatch.countDown();
                    }
                }
            }, "h3-load-" + i);
            t.setDaemon(true);
            t.start();
        }

        startLatch.countDown();
        Thread.sleep(warmupSec * 1000L);
        warmupDone.value = true;
        long benchStart = System.nanoTime();
        Thread.sleep(durationSec * 1000L);
        long benchElapsedNanos = System.nanoTime() - benchStart;
        stop.value = true;
        doneLatch.await(15, TimeUnit.SECONDS);

        long total = okCount.get();
        double seconds = benchElapsedNanos / 1_000_000_000.0;
        double rps = total / seconds;
        double mbps = (bytesReceived.get() / (1024.0 * 1024.0)) / seconds;
        System.out.printf("requests=%d errors=%d duration=%.2fs negotiated-version=%s%n",
                total, errCount.get(), seconds, negotiatedVersion.get());
        System.out.printf("throughput: %.1f req/s, %.2f MB/s%n", rps, mbps);
        System.out.printf("latency: p50=%.2fms p90=%.2fms p99=%.2fms p999=%.2fms%n",
                hist.percentile(50) / 1e6, hist.percentile(90) / 1e6,
                hist.percentile(99) / 1e6, hist.percentile(99.9) / 1e6);
        System.out.printf("CSV,%s,%d,%d,%d,%.4f,%.2f,%.2f,%.3f,%.3f,%.3f,%.3f%n",
                label, concurrency, total, errCount.get(), seconds, rps, mbps,
                hist.percentile(50) / 1e6, hist.percentile(90) / 1e6,
                hist.percentile(99) / 1e6, hist.percentile(99.9) / 1e6);
        System.exit(0);
    }

    static final AtomicLong FAILURES_REPORTED = new AtomicLong();

    /** One request and its response. */
    static final class Exchange extends DefaultHttpResponseHandler {
        final CountDownLatch done = new CountDownLatch(1);
        volatile int status;
        volatile long bytes;

        @Override
        public void status(int code) {
            status = code;
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            bytes += data.remaining();
        }

        @Override
        public void endMessage() {
            done.countDown();
        }

        @Override
        public void failed(Exception ex) {
            status = 0;
            if (FAILURES_REPORTED.incrementAndGet() <= 5) {
                System.err.println("request failed: " + ex);
            }
            done.countDown();
        }
    }

    static HttpClient connect(Gumdrop gumdrop, String host, int port) throws InterruptedException {
        HttpClient client = new HttpClient(host, port);
        client.versions(HttpVersion.HTTP_3);
        client.verifyPeer(false);
        client.altSvcEnabled(false);
        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicReference<Exception> error = new AtomicReference<Exception>();
        client.connect(gumdrop, new HttpClientHandler() {
            @Override
            public void onConnected(Endpoint endpoint) {
            }

            @Override
            public void onSecurityEstablished(SecurityInfo info) {
                connected.countDown();
            }

            @Override
            public void onError(Exception cause) {
                error.set(cause);
                connected.countDown();
            }

            @Override
            public void onDisconnected() {
            }
        });
        if (!connected.await(10, TimeUnit.SECONDS) || error.get() != null) {
            System.err.println("HTTP/3 connection failed: " + error.get());
            client.close();
            return null;
        }
        return client;
    }
}
