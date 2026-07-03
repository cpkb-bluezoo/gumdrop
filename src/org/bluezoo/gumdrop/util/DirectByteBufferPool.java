/*
 * DirectByteBufferPool.java
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

package org.bluezoo.gumdrop.util;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Global pool of <em>direct</em> {@link ByteBuffer}s, bucketed by
 * power-of-two size classes.
 *
 * <p>Direct buffers let the JVM perform socket reads and writes without the
 * internal copy through a temporary direct buffer that heap buffers require,
 * but they are expensive to allocate and are reclaimed lazily by a
 * {@link java.lang.ref.Cleaner}. Pooling and reusing them removes both the
 * allocation cost and the off-heap churn on the hot I/O paths.
 *
 * <p>Unlike {@link ByteBufferPool} (which is thread-local and pools heap
 * buffers), this pool is global and thread-safe: a connection's network
 * buffers are typically acquired on the accept thread but read, written,
 * grown, and released on the connection's assigned worker thread, so a
 * thread-local pool could not reuse them. Each size-class bucket is a bounded
 * {@link ArrayBlockingQueue}; when a bucket is full, released buffers are
 * simply dropped and left for the cleaner, capping the retained off-heap
 * memory.
 *
 * <p>Buffers are only ever allocated at exact power-of-two sizes, so a
 * released buffer maps to exactly one bucket and can never be handed out for
 * a request larger than its capacity. Callers must not use a buffer after
 * releasing it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DirectByteBufferPool {

    private static final int MIN_BUCKET_SHIFT = 12;   // 4 KiB
    private static final int MAX_BUCKET_SHIFT = 20;   // 1 MiB
    private static final int BUCKET_COUNT =
            MAX_BUCKET_SHIFT - MIN_BUCKET_SHIFT + 1;

    // Approximate off-heap budget retained per size class. The per-bucket
    // count is derived from this so large buffers are pooled in smaller
    // numbers than small ones, bounding total retained memory to a few tens
    // of megabytes across all buckets.
    private static final int PER_BUCKET_BYTE_BUDGET = 4 * 1024 * 1024;
    private static final int MIN_PER_BUCKET = 4;
    private static final int MAX_PER_BUCKET = 256;

    private static final ArrayBlockingQueue<ByteBuffer>[] BUCKETS =
            createBuckets();

    private DirectByteBufferPool() { }

    @SuppressWarnings("unchecked")
    private static ArrayBlockingQueue<ByteBuffer>[] createBuckets() {
        ArrayBlockingQueue<ByteBuffer>[] buckets =
                new ArrayBlockingQueue[BUCKET_COUNT];
        for (int i = 0; i < BUCKET_COUNT; i++) {
            int bufferSize = 1 << (i + MIN_BUCKET_SHIFT);
            int count = PER_BUCKET_BYTE_BUDGET / bufferSize;
            if (count < MIN_PER_BUCKET) {
                count = MIN_PER_BUCKET;
            } else if (count > MAX_PER_BUCKET) {
                count = MAX_PER_BUCKET;
            }
            buckets[i] = new ArrayBlockingQueue<ByteBuffer>(count);
        }
        return buckets;
    }

    /**
     * Acquires a direct {@link ByteBuffer} with at least {@code minCapacity}
     * bytes of capacity. The returned buffer is cleared (position 0, limit ==
     * capacity). Buffers within the managed range have a power-of-two
     * capacity that is at least {@code minCapacity}.
     *
     * @param minCapacity minimum required capacity in bytes
     * @return a direct buffer with at least the requested capacity
     */
    public static ByteBuffer acquire(int minCapacity) {
        int idx = bucketIndex(minCapacity);
        if (idx >= 0) {
            ByteBuffer buf = BUCKETS[idx].poll();
            if (buf != null) {
                buf.clear();
                return buf;
            }
            return ByteBuffer.allocateDirect(1 << (idx + MIN_BUCKET_SHIFT));
        }
        // Larger than the biggest managed bucket: allocate exactly and let it
        // be reclaimed by the cleaner on release (release() will not pool it).
        return ByteBuffer.allocateDirect(minCapacity);
    }

    /**
     * Releases a {@link ByteBuffer} back to the pool. The buffer must not be
     * used by the caller after this call. Non-direct buffers, and buffers
     * whose capacity is not a managed power-of-two size class, are ignored
     * (left for garbage collection).
     *
     * @param buf the buffer to release (may be null)
     */
    public static void release(ByteBuffer buf) {
        if (buf == null || !buf.isDirect()) {
            return;
        }
        int capacity = buf.capacity();
        if (Integer.bitCount(capacity) != 1) {
            return; // not a power of two, so not one of ours
        }
        int shift = Integer.numberOfTrailingZeros(capacity);
        if (shift < MIN_BUCKET_SHIFT || shift > MAX_BUCKET_SHIFT) {
            return;
        }
        // offer() drops the buffer (returning false) when the bucket is full;
        // the dropped buffer is then reclaimed by the cleaner.
        BUCKETS[shift - MIN_BUCKET_SHIFT].offer(buf);
    }

    private static int bucketIndex(int capacity) {
        if (capacity <= 0) {
            return 0;
        }
        int shift = 32 - Integer.numberOfLeadingZeros(capacity - 1);
        if (shift < MIN_BUCKET_SHIFT) {
            shift = MIN_BUCKET_SHIFT;
        }
        if (shift > MAX_BUCKET_SHIFT) {
            return -1;
        }
        return shift - MIN_BUCKET_SHIFT;
    }
}
