import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
 * Lean closed-loop HTTP/2 load generator, the HTTP/2 counterpart of
 * RawLoadClient: blocking TLS sockets, a request header block encoded once,
 * and only as much frame handling as it takes to know when a response has
 * ended. Same options and CSV line as LoadClient.
 *
 * The concurrency is spread over {@code --connections} connections (default
 * one, as the JDK HttpClient uses): each connection keeps its share of
 * streams in flight, opening a new stream whenever one of its responses
 * ends. One thread per connection reads the frames and writes the requests.
 *
 * It does not decode HPACK. The request needs none (static table entries and
 * literals that are never indexed), and of the response it looks only at the
 * first octet of the header block, which for a 200 is the static table
 * reference 0x88 from both servers.
 */
public class H2LoadClient {

    static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    static int DEBUG_FRAMES = Integer.parseInt(
            System.getenv("H2_DEBUG_FRAMES") != null ? System.getenv("H2_DEBUG_FRAMES") : "0");

    static final int TYPE_DATA = 0;
    static final int TYPE_HEADERS = 1;
    static final int TYPE_RST_STREAM = 3;
    static final int TYPE_SETTINGS = 4;
    static final int TYPE_PING = 6;
    static final int TYPE_GOAWAY = 7;
    static final int TYPE_WINDOW_UPDATE = 8;

    static final int FLAG_END_STREAM = 0x1;
    static final int FLAG_ACK = 0x1;
    static final int FLAG_END_HEADERS = 0x4;
    static final int FLAG_PADDED = 0x8;
    static final int FLAG_PRIORITY = 0x20;

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = LoadClient.parseArgs(args);
        final URI uri = URI.create(LoadClient.req(opt, "url"));
        final int concurrency = Integer.parseInt(opt.getOrDefault("concurrency", "50"));
        final int connections = Integer.parseInt(opt.getOrDefault("connections", "1"));
        int durationSec = Integer.parseInt(opt.getOrDefault("duration", "10"));
        int warmupSec = Integer.parseInt(opt.getOrDefault("warmup", "3"));
        String label = opt.getOrDefault("label", uri.toString());
        final String host = uri.getHost();
        final int port = uri.getPort();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();

        final byte[] headerBlock = requestHeaderBlock(host + ":" + port, path);

        final SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { LoadClient.trustAllManager() }, new SecureRandom());

        System.out.println("=== " + label + " ===");
        System.out.printf("url=%s concurrency=%d connections=%d duration=%ds warmup=%ds method=GET client=h2-raw%n",
                uri, concurrency, connections, durationSec, warmupSec);

        final AtomicLong okCount = new AtomicLong();
        final AtomicLong errCount = new AtomicLong();
        final AtomicLong bytesReceived = new AtomicLong();
        final LoadClient.Histogram hist = new LoadClient.Histogram();
        final LoadClient.volatileFlag warmupDone = new LoadClient.volatileFlag();
        final LoadClient.volatileFlag stop = new LoadClient.volatileFlag();
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(connections);

        for (int i = 0; i < connections; i++) {
            final int streams = concurrency / connections + (i < concurrency % connections ? 1 : 0);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        startLatch.await();
                        Connection c = new Connection(host, port, sslContext, headerBlock, streams);
                        c.run(stop, warmupDone, okCount, errCount, bytesReceived, hist);
                    } catch (InterruptedException ignored) {
                    } catch (IOException e) {
                        if (!stop.value) {
                            errCount.incrementAndGet();
                            System.err.println("connection failed: " + e);
                        }
                    } finally {
                        doneLatch.countDown();
                    }
                }
            }, "h2-load-" + i);
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
        doneLatch.await(5, TimeUnit.SECONDS);

        long total = okCount.get();
        double seconds = benchElapsedNanos / 1_000_000_000.0;
        double rps = total / seconds;
        double mbps = (bytesReceived.get() / (1024.0 * 1024.0)) / seconds;
        System.out.printf("requests=%d errors=%d duration=%.2fs negotiated-version=HTTP_2%n",
                total, errCount.get(), seconds);
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

    /**
     * The header block of {@code GET path}: static table references for
     * :method GET and :scheme https, and literals without indexing, not
     * Huffman-coded, for :path (unless it is "/") and :authority. It does not
     * touch the dynamic table, so the same octets serve for every request.
     */
    static byte[] requestHeaderBlock(String authority, String path) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0x82);                    // :method: GET
        out.write(0x87);                    // :scheme: https
        if ("/".equals(path)) {
            out.write(0x84);                // :path: /
        } else {
            out.write(0x04);                // literal without indexing, name index 4 (:path)
            writeString(out, path);
        }
        out.write(0x01);                    // literal without indexing, name index 1 (:authority)
        writeString(out, authority);
        return out.toByteArray();
    }

    private static void writeString(java.io.ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        if (b.length >= 127) {
            throw new IllegalArgumentException("string too long for this client: " + s);
        }
        out.write(b.length);
        out.write(b, 0, b.length);
    }

    /** One HTTP/2 connection keeping a fixed number of streams in flight. */
    static final class Connection {
        private final byte[] headerBlock;
        private final int streams;
        private final InputStream in;
        private final OutputStream out;
        private final Socket socket;

        private final byte[] buf = new byte[65536];
        private int pos;
        private int len;

        /** Requests are written into this and sent together after each batch of frames read. */
        private final byte[] outBuf = new byte[65536];
        private int outLen;

        private int nextStreamId = 1;
        /** Start times and body lengths of the streams in flight, in a ring indexed by stream id. */
        private final long[] started;
        private final int[] bodyBytes;
        private final boolean[] ok;
        private final int ringMask;
        private int unacknowledgedData;

        Connection(String host, int port, SSLContext sslContext, byte[] headerBlock, int streams) throws IOException {
            this.headerBlock = headerBlock;
            this.streams = streams;
            int ring = 1;
            while (ring < streams * 2) {
                ring <<= 1;
            }
            this.ringMask = ring - 1;
            this.started = new long[ring];
            this.bodyBytes = new int[ring];
            this.ok = new boolean[ring];

            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(10000);
            SSLSocket ss = (SSLSocket) sslContext.getSocketFactory().createSocket(s, host, port, true);
            SSLParameters params = ss.getSSLParameters();
            params.setApplicationProtocols(new String[] { "h2" });
            ss.setSSLParameters(params);
            ss.startHandshake();
            if (!"h2".equals(ss.getApplicationProtocol())) {
                throw new IOException("server did not select h2: " + ss.getApplicationProtocol());
            }
            this.socket = ss;
            this.in = ss.getInputStream();
            this.out = ss.getOutputStream();
        }

        void run(LoadClient.volatileFlag stop, LoadClient.volatileFlag warmupDone, AtomicLong okCount,
                AtomicLong errCount, AtomicLong bytesReceived, LoadClient.Histogram hist) throws IOException {
            try {
                System.arraycopy(PREFACE, 0, outBuf, 0, PREFACE.length);
                outLen = PREFACE.length;
                // SETTINGS: ENABLE_PUSH = 0, INITIAL_WINDOW_SIZE = 1 MiB
                frameHeader(12, TYPE_SETTINGS, 0, 0);
                putShort(0x2);
                putInt(0);
                putShort(0x4);
                putInt(1 << 20);
                // and a large connection window, so flow control stays out of the way
                frameHeader(4, TYPE_WINDOW_UPDATE, 0, 0);
                putInt((1 << 30) - 65535);
                for (int i = 0; i < streams; i++) {
                    request();
                }
                flush();

                while (!stop.value) {
                    readFrame(warmupDone, okCount, errCount, bytesReceived, hist);
                    // answer everything already read before writing: the
                    // requests that replace a batch of finished streams go
                    // out as one write
                    while (len - pos >= 9 && frameAvailable()) {
                        readFrame(warmupDone, okCount, errCount, bytesReceived, hist);
                    }
                    flush();
                }
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        private boolean frameAvailable() {
            int length = ((buf[pos] & 0xff) << 16) | ((buf[pos + 1] & 0xff) << 8) | (buf[pos + 2] & 0xff);
            return len - pos >= 9 + length;
        }

        private void request() {
            int id = nextStreamId;
            nextStreamId += 2;
            int slot = (id >> 1) & ringMask;
            started[slot] = System.nanoTime();
            bodyBytes[slot] = 0;
            ok[slot] = false;
            frameHeader(headerBlock.length, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, id);
            System.arraycopy(headerBlock, 0, outBuf, outLen, headerBlock.length);
            outLen += headerBlock.length;
        }

        private void readFrame(LoadClient.volatileFlag warmupDone, AtomicLong okCount, AtomicLong errCount,
                AtomicLong bytesReceived, LoadClient.Histogram hist) throws IOException {
            need(9);
            int length = ((buf[pos] & 0xff) << 16) | ((buf[pos + 1] & 0xff) << 8) | (buf[pos + 2] & 0xff);
            int type = buf[pos + 3] & 0xff;
            int flags = buf[pos + 4] & 0xff;
            int streamId = ((buf[pos + 5] & 0x7f) << 24) | ((buf[pos + 6] & 0xff) << 16)
                    | ((buf[pos + 7] & 0xff) << 8) | (buf[pos + 8] & 0xff);
            pos += 9;
            need(length);
            int payload = pos;
            pos += length;
            if (DEBUG_FRAMES > 0 && (type == TYPE_HEADERS || type == TYPE_DATA || type == TYPE_SETTINGS)) {
                // H2_DEBUG_FRAMES=n prints the first n frames received
                DEBUG_FRAMES--;
                StringBuilder hex = new StringBuilder();
                for (int i = payload; i < payload + Math.min(length, 48); i++) {
                    hex.append(String.format("%02x ", buf[i] & 0xff));
                }
                System.err.println("frame type=" + type + " flags=" + flags + " stream=" + streamId
                        + " length=" + length + " : " + hex);
            }
            int slot = (streamId >> 1) & ringMask;
            boolean ended = false;
            switch (type) {
                case TYPE_HEADERS: {
                    int p = payload;
                    if ((flags & FLAG_PADDED) != 0) {
                        p++;
                    }
                    if ((flags & FLAG_PRIORITY) != 0) {
                        p += 5;
                    }
                    // 0x88: the static table entry for :status 200
                    if (p < payload + length && (buf[p] & 0xff) == 0x88) {
                        ok[slot] = true;
                    }
                    ended = (flags & FLAG_END_STREAM) != 0;
                    break;
                }
                case TYPE_DATA:
                    bodyBytes[slot] += length;
                    unacknowledgedData += length;
                    if (unacknowledgedData >= (1 << 20)) {
                        // RFC 9113 section 6.9: return connection flow-control credit
                        frameHeader(4, TYPE_WINDOW_UPDATE, 0, 0);
                        putInt(unacknowledgedData);
                        unacknowledgedData = 0;
                    }
                    ended = (flags & FLAG_END_STREAM) != 0;
                    break;
                case TYPE_RST_STREAM:
                    ok[slot] = false;
                    ended = true;
                    break;
                case TYPE_SETTINGS:
                    if ((flags & FLAG_ACK) == 0) {
                        frameHeader(0, TYPE_SETTINGS, FLAG_ACK, 0);
                    }
                    break;
                case TYPE_PING:
                    if ((flags & FLAG_ACK) == 0) {
                        frameHeader(8, TYPE_PING, FLAG_ACK, 0);
                        System.arraycopy(buf, payload, outBuf, outLen, 8);
                        outLen += 8;
                    }
                    break;
                case TYPE_GOAWAY:
                    throw new IOException("GOAWAY received");
                default:
                    break;
            }
            if (ended && streamId != 0) {
                long elapsed = System.nanoTime() - started[slot];
                if (warmupDone.value) {
                    if (ok[slot]) {
                        okCount.incrementAndGet();
                        bytesReceived.addAndGet(bodyBytes[slot]);
                        hist.record(elapsed);
                    } else {
                        errCount.incrementAndGet();
                    }
                }
                request();
            }
        }

        /** Makes at least {@code n} octets available at {@code pos}. */
        private void need(int n) throws IOException {
            if (n > buf.length) {
                throw new IOException("frame too large for the load client: " + n);
            }
            if (len - pos >= n) {
                return;
            }
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, len - pos);
                len -= pos;
                pos = 0;
            }
            // the requests held so far go out before waiting for more input
            flush();
            while (len < n) {
                int r = in.read(buf, len, buf.length - len);
                if (r < 0) {
                    throw new EOFException();
                }
                len += r;
            }
        }

        private void flush() throws IOException {
            if (outLen > 0) {
                out.write(outBuf, 0, outLen);
                out.flush();
                outLen = 0;
            }
        }

        private void frameHeader(int length, int type, int flags, int streamId) {
            outBuf[outLen++] = (byte) (length >> 16);
            outBuf[outLen++] = (byte) (length >> 8);
            outBuf[outLen++] = (byte) length;
            outBuf[outLen++] = (byte) type;
            outBuf[outLen++] = (byte) flags;
            putInt(streamId);
        }

        private void putShort(int v) {
            outBuf[outLen++] = (byte) (v >> 8);
            outBuf[outLen++] = (byte) v;
        }

        private void putInt(int v) {
            outBuf[outLen++] = (byte) (v >> 24);
            outBuf[outLen++] = (byte) (v >> 16);
            outBuf[outLen++] = (byte) (v >> 8);
            outBuf[outLen++] = (byte) v;
        }
    }
}
