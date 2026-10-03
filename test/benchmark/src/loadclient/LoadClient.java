import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal closed-loop HTTP load generator, used to compare Gumdrop and Netty
 * server implementations under identical client-side conditions . One virtual thread per configured
 * concurrency level; each thread issues blocking requests back-to-back for
 * the configured duration. Latency is recorded into a log2-nanosecond
 * bucketed histogram (HDR-style) rather than storing every sample, so memory
 * use stays flat regardless of request count.
 */
public class LoadClient {

    // HDR-style log-linear buckets: each power-of-two octave [2^k, 2^(k+1))
    // is split into SUBBUCKETS_PER_OCTAVE linear steps, giving ~1/32 = ~3%
    // relative resolution at every scale, not a full 2x per bucket. A pure
    // log2 histogram (one bucket per octave, reporting 2^i as the value)
    // was used originally - its percentile() always returns an exact power
    // of two, so any two measurements landing in adjacent buckets are
    // reported as an exact 2x ratio *by construction*, regardless of their
    // real difference. That produced misleading "exactly 2x" gaps in past
    // benchmark runs that were actually just bucket-boundary artifacts, not
    // a real doubling in the underlying code.
    static final int SUBBUCKET_BITS = 5;
    static final int SUBBUCKETS_PER_OCTAVE = 1 << SUBBUCKET_BITS; // 32
    static final int OCTAVES = 64;
    static final int BUCKETS = OCTAVES * SUBBUCKETS_PER_OCTAVE;

    static final class Histogram {
        final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
        void record(long nanos) {
            int idx;
            if (nanos <= 0) {
                idx = 0;
            } else {
                int octave = 63 - Long.numberOfLeadingZeros(nanos);
                long base = 1L << octave;
                long sub = ((nanos - base) * SUBBUCKETS_PER_OCTAVE) / base;
                idx = octave * SUBBUCKETS_PER_OCTAVE + (int) sub;
                if (idx >= BUCKETS) idx = BUCKETS - 1;
            }
            counts.incrementAndGet(idx);
        }
        long total() {
            long t = 0;
            for (int i = 0; i < BUCKETS; i++) t += counts.get(i);
            return t;
        }
        // Returns approx nanos at given percentile (0-100), using the
        // sub-bucket's lower bound.
        long percentile(double p) {
            long total = total();
            if (total == 0) return 0;
            long target = (long) Math.ceil(total * (p / 100.0));
            long cum = 0;
            for (int i = 0; i < BUCKETS; i++) {
                cum += counts.get(i);
                if (cum >= target) {
                    int octave = i / SUBBUCKETS_PER_OCTAVE;
                    int sub = i % SUBBUCKETS_PER_OCTAVE;
                    long base = 1L << octave;
                    return base + (sub * base) / SUBBUCKETS_PER_OCTAVE;
                }
            }
            return 1L << (OCTAVES - 1);
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        String url = req(opt, "url");
        int concurrency = Integer.parseInt(opt.getOrDefault("concurrency", "50"));
        int durationSec = Integer.parseInt(opt.getOrDefault("duration", "10"));
        int warmupSec = Integer.parseInt(opt.getOrDefault("warmup", "3"));
        String method = opt.getOrDefault("method", "GET");
        String bodyFile = opt.get("body-file");
        String contentType = opt.getOrDefault("content-type", "application/octet-stream");
        boolean insecure = opt.containsKey("insecure");
        boolean closePerRequest = opt.containsKey("close-per-request");
        String label = opt.getOrDefault("label", url);

        byte[] body = bodyFile != null ? Files.readAllBytes(Path.of(bodyFile)) : null;
        boolean http2 = opt.containsKey("http2");

        // Deliberately NOT using a virtual-thread-per-task executor here (that's
        // for `workers` below, the load-generation loops) - handing it to
        // HttpClient as its own internal async-dispatch executor stalls HTTP/2
        // stream multiplexing under concurrency (reproducibly hangs with 0
        // completions against both servers). Let HttpClient use its default.
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .version(http2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5));
        if (insecure) {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{ trustAllManager() }, new SecureRandom());
            clientBuilder.sslContext(sc);
        }
        HttpClient client = clientBuilder.build();

        URI uri = URI.create(url);

        System.out.println("=== " + label + " ===");
        System.out.printf("url=%s concurrency=%d duration=%ds warmup=%ds method=%s%n",
                url, concurrency, durationSec, warmupSec, method);

        AtomicLong okCount = new AtomicLong();
        AtomicLong errCount = new AtomicLong();
        AtomicLong bytesReceived = new AtomicLong();
        Histogram hist = new Histogram();
        java.util.concurrent.atomic.AtomicReference<String> negotiatedVersion = new java.util.concurrent.atomic.AtomicReference<>();
        volatileFlag warmupDone = new volatileFlag();
        volatileFlag stop = new volatileFlag();

        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        for (int i = 0; i < concurrency; i++) {
            workers.submit(new Runnable() {
                @Override
                public void run() {
                try {
                    startLatch.await();
                    while (!stop.value) {
                        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                                .timeout(Duration.ofSeconds(10));
                        if (closePerRequest) {
                            rb.header("Connection", "close");
                        }
                        if ("POST".equalsIgnoreCase(method) && body != null) {
                            rb.header("Content-Type", contentType)
                              .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                        } else {
                            rb.GET();
                        }
                        long start = System.nanoTime();
                        try {
                            HttpResponse<byte[]> resp = client.send(rb.build(),
                                    HttpResponse.BodyHandlers.ofByteArray());
                            long elapsed = System.nanoTime() - start;
                            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                                if (warmupDone.value) {
                                    okCount.incrementAndGet();
                                    bytesReceived.addAndGet(resp.body().length);
                                    hist.record(elapsed);
                                    negotiatedVersion.compareAndSet(null, resp.version().toString());
                                }
                            } else {
                                if (warmupDone.value) errCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            if (warmupDone.value) errCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
                }
            });
        }

        startLatch.countDown();
        Thread.sleep(warmupSec * 1000L);
        // Reset counters after warmup by flipping the flag; in-flight requests
        // started before this point are still counted against post-warmup only
        // if they complete after the flag flips, which is an acceptable bias
        // for a short warmup window.
        warmupDone.value = true;
        long benchStart = System.nanoTime();
        Thread.sleep(durationSec * 1000L);
        long benchElapsedNanos = System.nanoTime() - benchStart;
        stop.value = true;
        doneLatch.await(30, TimeUnit.SECONDS);
        workers.shutdownNow();

        long total = okCount.get();
        double seconds = benchElapsedNanos / 1_000_000_000.0;
        double rps = total / seconds;
        double mbps = (bytesReceived.get() / (1024.0 * 1024.0)) / seconds;

        System.out.printf("requests=%d errors=%d duration=%.2fs negotiated-version=%s%n",
                total, errCount.get(), seconds, negotiatedVersion.get());
        System.out.printf("throughput: %.1f req/s, %.2f MB/s%n", rps, mbps);
        System.out.printf("latency: p50=%.2fms p90=%.2fms p99=%.2fms p999=%.2fms max_bucket=%.2fms%n",
                hist.percentile(50) / 1e6,
                hist.percentile(90) / 1e6,
                hist.percentile(99) / 1e6,
                hist.percentile(99.9) / 1e6,
                hist.percentile(100) / 1e6);
        // Machine-readable summary line for aggregation into a results file.
        System.out.printf("CSV,%s,%d,%d,%d,%.4f,%.2f,%.2f,%.3f,%.3f,%.3f,%.3f%n",
                label, concurrency, total, errCount.get(), seconds, rps, mbps,
                hist.percentile(50) / 1e6, hist.percentile(90) / 1e6,
                hist.percentile(99) / 1e6, hist.percentile(99.9) / 1e6);
    }

    static final class volatileFlag {
        volatile boolean value = false;
    }

    static TrustManager trustAllManager() {
        return new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--")) {
                String kv = a.substring(2);
                int eq = kv.indexOf('=');
                if (eq >= 0) m.put(kv.substring(0, eq), kv.substring(eq + 1));
                else m.put(kv, "true");
            }
        }
        return m;
    }

    static String req(Map<String, String> opt, String key) {
        String v = opt.get(key);
        if (v == null) throw new IllegalArgumentException("missing --" + key);
        return v;
    }
}
