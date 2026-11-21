import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.bluezoo.gumdrop.redis.codec.ReplyFlow;
import org.bluezoo.gumdrop.redis.codec.RespDecoder;
import org.bluezoo.gumdrop.redis.codec.RespEncoder;
import org.bluezoo.gumdrop.redis.codec.RespEventHandler;
import org.bluezoo.gumdrop.redis.codec.RespType;

/**
 * Micro-benchmark for the RESP codec, with no Redis server involved.
 *
 * <p>Decoder: a bulk-string reply of 1 KiB, 64 KiB and 1 MiB, and an array
 * reply of 100 bulk strings of 1 KiB, each fed in chunks of 512 bytes and
 * 16 KiB. Encoder: SET and GET with a 16-byte and a 64 KiB value.
 *
 * <p>Each case warms up, then runs for a fixed number of iterations on one
 * thread and prints one CSV row: operations per second, bytes per second,
 * allocated bytes per operation and CPU nanoseconds per operation.
 * Allocation is read from the thread's own allocated-bytes counter, so it
 * counts everything the codec allocates, including buffers taken from the
 * pool on a miss.
 *
 * <p>Usage: {@code RespCodecBench [seconds-per-case]} (default 3).
 */
public class RespCodecBench {

    private static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** One unit of work; returns bytes processed. */
    interface Op {
        long run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        long millis = 3000;
        if (args.length > 0) {
            millis = (long) (Double.parseDouble(args[0]) * 1000);
        }
        System.out.println("case,ops_per_s,mb_per_s,alloc_bytes_per_op,cpu_ns_per_op");
        int[] chunks = new int[] { 512, 16384 };
        int[] sizes = new int[] { 1024, 64 * 1024, 1024 * 1024 };
        for (int s = 0; s < sizes.length; s++) {
            for (int c = 0; c < chunks.length; c++) {
                byte[] wire = bulk(sizes[s]);
                run("decode-bulk-" + label(sizes[s]) + "-chunk" + chunks[c],
                        new DecodeOp(wire, chunks[c], false), millis);
            }
        }
        for (int c = 0; c < chunks.length; c++) {
            run("decode-array100x1k-chunk" + chunks[c],
                    new DecodeOp(array(100, 1024), chunks[c], true), millis);
        }
        int[] valueSizes = new int[] { 16, 64 * 1024 };
        for (int v = 0; v < valueSizes.length; v++) {
            final byte[] value = new byte[valueSizes[v]];
            java.util.Arrays.fill(value, (byte) 'v');
            final String valueStr = new String(value, StandardCharsets.US_ASCII);
            final RespEncoder encoder = new RespEncoder();
            run("encode-set-" + label(valueSizes[v]), new Op() {
                public long run() {
                    return encoder.encodeCommand("SET", new String[] { "key:000001", valueStr }).remaining();
                }
            }, millis);
        }
        final RespEncoder getEncoder = new RespEncoder();
        run("encode-get", new Op() {
            public long run() {
                return getEncoder.encodeCommand("GET", new String[] { "key:000001" }).remaining();
            }
        }, millis);
    }

    static String label(int n) {
        if (n >= 1024 * 1024) {
            return (n / (1024 * 1024)) + "m";
        }
        if (n >= 1024) {
            return (n / 1024) + "k";
        }
        return Integer.toString(n);
    }

    static byte[] bulk(int size) {
        StringBuilder sb = new StringBuilder(size + 16);
        sb.append('$').append(size).append("\r\n");
        for (int i = 0; i < size; i++) {
            sb.append('x');
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] array(int count, int elementSize) {
        byte[] element = bulk(elementSize);
        byte[] head = ("*" + count + "\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[head.length + element.length * count];
        System.arraycopy(head, 0, out, 0, head.length);
        for (int i = 0; i < count; i++) {
            System.arraycopy(element, 0, out, head.length + i * element.length, element.length);
        }
        return out;
    }

    /** Counts completed replies and discards everything they hold. */
    static final class Discarding implements RespEventHandler {
        int replies;

        public void beginReply(boolean push) { }
        public void endReply(boolean skipped) { replies++; }
        public ReplyFlow nullValue() { return ReplyFlow.CONTINUE; }
        public ReplyFlow integer(long value) { return ReplyFlow.CONTINUE; }
        public ReplyFlow doubleValue(double value) { return ReplyFlow.CONTINUE; }
        public ReplyFlow booleanValue(boolean value) { return ReplyFlow.CONTINUE; }
        public ReplyFlow bigNumber(String digits) { return ReplyFlow.CONTINUE; }
        public ReplyFlow simpleString(String value) { return ReplyFlow.CONTINUE; }
        public ReplyFlow error(String message) { return ReplyFlow.CONTINUE; }
        public ReplyFlow beginAggregate(RespType kind, long length) { return ReplyFlow.CONTINUE; }
        public ReplyFlow endAggregate() { return ReplyFlow.CONTINUE; }
        public ReplyFlow beginString(RespType kind, long length, String format) { return ReplyFlow.CONTINUE; }
        public ReplyFlow stringChunk(ByteBuffer chunk) { return ReplyFlow.CONTINUE; }
        public ReplyFlow endString() { return ReplyFlow.CONTINUE; }
    }

    /** Feeds one reply into a decoder in fixed-size chunks, discarding what it holds. */
    static class DecodeOp implements Op {
        private final byte[] wire;
        private final int chunk;
        private final RespDecoder decoder = new RespDecoder();
        private final Discarding handler = new Discarding();

        DecodeOp(byte[] wire, int chunk, boolean array) {
            this.wire = wire;
            this.chunk = chunk;
        }

        public long run() throws Exception {
            int off = 0;
            int before = handler.replies;
            while (off < wire.length) {
                int n = Math.min(chunk, wire.length - off);
                decoder.receive(ByteBuffer.wrap(wire, off, n), handler);
                off += n;
            }
            if (handler.replies == before) {
                throw new IllegalStateException("reply not decoded");
            }
            return wire.length;
        }
    }

    static void run(String name, Op op, long millis) throws Exception {
        long warmEnd = System.nanoTime() + millis * 1000000L / 2;
        while (System.nanoTime() < warmEnd) {
            op.run();
        }
        System.gc();
        long id = Thread.currentThread().getId();
        long alloc0 = MX.getThreadAllocatedBytes(id);
        long cpu0 = MX.getCurrentThreadCpuTime();
        long t0 = System.nanoTime();
        long end = t0 + millis * 1000000L;
        long ops = 0;
        long bytes = 0;
        while (System.nanoTime() < end) {
            bytes += op.run();
            ops++;
        }
        long elapsed = System.nanoTime() - t0;
        long cpu = MX.getCurrentThreadCpuTime() - cpu0;
        long alloc = MX.getThreadAllocatedBytes(id) - alloc0;
        System.out.printf("%s,%.0f,%.1f,%d,%d%n", name,
                ops * 1e9 / elapsed, bytes / 1048576.0 * 1e9 / elapsed,
                alloc / ops, cpu / ops);
    }
}
