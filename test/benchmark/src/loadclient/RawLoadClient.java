import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

/**
 * Lean closed-loop HTTP/1.1 load generator: one platform thread and one
 * blocking socket per concurrent client, a pre-encoded request, and the
 * least response parsing that still finds the end of each response
 * (Content-Length or chunked). Same options and CSV line as LoadClient, but
 * far cheaper per request than the JDK HttpClient, so that the server and
 * not the load generator is what limits throughput.
 */
public class RawLoadClient {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = LoadClient.parseArgs(args);
        final URI uri = URI.create(LoadClient.req(opt, "url"));
        final int concurrency = Integer.parseInt(opt.getOrDefault("concurrency", "50"));
        int durationSec = Integer.parseInt(opt.getOrDefault("duration", "10"));
        int warmupSec = Integer.parseInt(opt.getOrDefault("warmup", "3"));
        String method = opt.getOrDefault("method", "GET");
        String bodyFile = opt.get("body-file");
        String contentType = opt.getOrDefault("content-type", "application/octet-stream");
        final boolean closePerRequest = opt.containsKey("close-per-request");
        String label = opt.getOrDefault("label", uri.toString());
        final boolean tls = "https".equals(uri.getScheme());
        final String host = uri.getHost();
        final int port = uri.getPort();

        byte[] body = bodyFile != null ? Files.readAllBytes(Path.of(bodyFile)) : null;
        StringBuilder sb = new StringBuilder();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append(':').append(port).append("\r\n");
        sb.append("User-Agent: RawLoadClient\r\n");
        if (closePerRequest) {
            sb.append("Connection: close\r\n");
        }
        if (body != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.US_ASCII);
        final byte[] request;
        if (body != null) {
            request = new byte[head.length + body.length];
            System.arraycopy(head, 0, request, 0, head.length);
            System.arraycopy(body, 0, request, head.length, body.length);
        } else {
            request = head;
        }

        final SSLContext sslContext;
        if (tls) {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { LoadClient.trustAllManager() }, new SecureRandom());
        } else {
            sslContext = null;
        }

        System.out.println("=== " + label + " ===");
        System.out.printf("url=%s concurrency=%d duration=%ds warmup=%ds method=%s client=raw%n",
                uri, concurrency, durationSec, warmupSec, method);

        final AtomicLong okCount = new AtomicLong();
        final AtomicLong errCount = new AtomicLong();
        final AtomicLong bytesReceived = new AtomicLong();
        final LoadClient.Histogram hist = new LoadClient.Histogram();
        final LoadClient.volatileFlag warmupDone = new LoadClient.volatileFlag();
        final LoadClient.volatileFlag stop = new LoadClient.volatileFlag();
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(concurrency);

        for (int i = 0; i < concurrency; i++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    Connection c = new Connection(host, port, sslContext);
                    try {
                        startLatch.await();
                        while (!stop.value) {
                            long start = System.nanoTime();
                            try {
                                if (!c.isOpen()) {
                                    c.open();
                                }
                                int status = c.exchange(request);
                                if (closePerRequest) {
                                    c.close();
                                }
                                long elapsed = System.nanoTime() - start;
                                if (warmupDone.value) {
                                    if (status >= 200 && status < 300) {
                                        okCount.incrementAndGet();
                                        bytesReceived.addAndGet(c.lastBodyLength);
                                        hist.record(elapsed);
                                    } else {
                                        errCount.incrementAndGet();
                                    }
                                }
                            } catch (IOException e) {
                                c.close();
                                if (warmupDone.value && !stop.value) {
                                    errCount.incrementAndGet();
                                }
                            }
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        c.close();
                        doneLatch.countDown();
                    }
                }
            }, "load-" + i);
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
        doneLatch.await(10, TimeUnit.SECONDS);

        long total = okCount.get();
        double seconds = benchElapsedNanos / 1_000_000_000.0;
        double rps = total / seconds;
        double mbps = (bytesReceived.get() / (1024.0 * 1024.0)) / seconds;
        System.out.printf("requests=%d errors=%d duration=%.2fs%n", total, errCount.get(), seconds);
        System.out.printf("throughput: %.1f req/s, %.2f MB/s%n", rps, mbps);
        System.out.printf("latency: p50=%.2fms p90=%.2fms p99=%.2fms p999=%.2fms%n",
                hist.percentile(50) / 1e6, hist.percentile(90) / 1e6,
                hist.percentile(99) / 1e6, hist.percentile(99.9) / 1e6);
        System.out.printf("CSV,%s,%d,%d,%d,%.4f,%.2f,%.2f,%.3f,%.3f,%.3f,%.3f%n",
                label, concurrency, total, errCount.get(), seconds, rps, mbps,
                hist.percentile(50) / 1e6, hist.percentile(90) / 1e6,
                hist.percentile(99) / 1e6, hist.percentile(99.9) / 1e6);
    }

    /** One blocking HTTP/1.1 connection and its response reader. */
    static final class Connection {
        private final String host;
        private final int port;
        private final SSLContext sslContext;
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        private final byte[] buf = new byte[16384];
        private int len;
        int lastBodyLength;

        Connection(String host, int port, SSLContext sslContext) {
            this.host = host;
            this.port = port;
            this.sslContext = sslContext;
        }

        boolean isOpen() {
            return socket != null;
        }

        void open() throws IOException {
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(10000);
            if (sslContext != null) {
                SSLSocket ss = (SSLSocket) sslContext.getSocketFactory().createSocket(s, host, port, true);
                SSLParameters params = ss.getSSLParameters();
                params.setApplicationProtocols(new String[] { "http/1.1" });
                ss.setSSLParameters(params);
                ss.startHandshake();
                s = ss;
            }
            socket = s;
            in = s.getInputStream();
            out = s.getOutputStream();
            len = 0;
        }

        void close() {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                socket = null;
            }
        }

        private void fill() throws IOException {
            if (len == buf.length) {
                throw new IOException("response too large for the load client");
            }
            int n = in.read(buf, len, buf.length - len);
            if (n < 0) {
                throw new EOFException();
            }
            len += n;
        }

        /** Sends the request, reads one whole response, returns its status. */
        int exchange(byte[] request) throws IOException {
            out.write(request);
            out.flush();
            len = 0;
            int headerEnd = -1;
            int scanned = 0;
            while (headerEnd < 0) {
                fill();
                for (int i = Math.max(scanned, 3); i < len; i++) {
                    if (buf[i] == '\n' && buf[i - 1] == '\r' && buf[i - 2] == '\n' && buf[i - 3] == '\r') {
                        headerEnd = i + 1;
                        break;
                    }
                }
                scanned = len;
            }
            if (len < 12) {
                throw new IOException("short status line");
            }
            int status = (buf[9] - '0') * 100 + (buf[10] - '0') * 10 + (buf[11] - '0');
            long contentLength = -1;
            boolean chunked = false;
            int lineStart = 0;
            for (int i = 0; i < headerEnd - 1; i++) {
                if (buf[i] == '\r' && buf[i + 1] == '\n') {
                    if (startsWith(lineStart, i, "content-length:")) {
                        contentLength = 0;
                        for (int j = lineStart + 15; j < i; j++) {
                            if (buf[j] >= '0' && buf[j] <= '9') {
                                contentLength = contentLength * 10 + (buf[j] - '0');
                            }
                        }
                    } else if (startsWith(lineStart, i, "transfer-encoding:")) {
                        chunked = true;
                    }
                    lineStart = i + 2;
                }
            }
            if (chunked) {
                int p = headerEnd;
                int bodyLen = 0;
                for (;;) {
                    int size = 0;
                    for (;;) {
                        while (p >= len) {
                            fill();
                        }
                        byte b = buf[p++];
                        if (b == '\r') {
                            continue;
                        }
                        if (b == '\n') {
                            break;
                        }
                        int d = Character.digit(b, 16);
                        if (d >= 0) {
                            size = size * 16 + d;
                        }
                    }
                    int need = p + size + 2;
                    while (len < need) {
                        fill();
                    }
                    p = need;
                    if (size == 0) {
                        break;
                    }
                    bodyLen += size;
                }
                lastBodyLength = bodyLen;
            } else {
                long need = headerEnd + Math.max(contentLength, 0);
                while (len < need) {
                    fill();
                }
                lastBodyLength = (int) Math.max(contentLength, 0);
            }
            return status;
        }

        private boolean startsWith(int from, int to, String lowerPrefix) {
            int n = lowerPrefix.length();
            if (to - from < n) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                int b = buf[from + i];
                if (b >= 'A' && b <= 'Z') {
                    b += 32;
                }
                if (b != lowerPrefix.charAt(i)) {
                    return false;
                }
            }
            return true;
        }
    }
}
