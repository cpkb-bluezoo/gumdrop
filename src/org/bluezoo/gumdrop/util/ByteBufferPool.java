/*
 * ByteBufferPool.java
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
import java.util.ArrayDeque;

/**
 * Thread-local pooling of heap {@link ByteBuffer}s, bucketed by power-of-two
 * size classes.  Buffers are reused within the same thread to reduce GC
 * pressure on the hot I/O paths.
 *
 * <p>Usage:
 * <pre>{@code
 *     ByteBuffer buf = ByteBufferPool.acquire(needed);
 *     try {
 *         // use buf ...
 *     } finally {
 *         ByteBufferPool.release(buf);
 *     }
 * }</pre>
 *
 * <p>Callers must not use a buffer after releasing it.  Buffers that were
 * not obtained from this pool may still be released safely (they are
 * simply added to the pool for future reuse).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ByteBufferPool {

    private static final int MIN_BUCKET_SHIFT = 9;   // 512
    private static final int MAX_BUCKET_SHIFT = 16;   // 64K
    private static final int BUCKET_COUNT =
            MAX_BUCKET_SHIFT - MIN_BUCKET_SHIFT + 1;
    private static final int MAX_PER_BUCKET = 32;

    private static final ThreadLocal<ArrayDeque<ByteBuffer>[]> POOL =
            ThreadLocal.withInitial(ByteBufferPool::createBuckets);

    private ByteBufferPool() { }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<ByteBuffer>[] createBuckets() {
        ArrayDeque<ByteBuffer>[] buckets = new ArrayDeque[BUCKET_COUNT];
        for (int i = 0; i < BUCKET_COUNT; i++) {
            buckets[i] = new ArrayDeque<>();
        }
        return buckets;
    }

    /**
     * Acquires a heap {@link ByteBuffer} with at least
     * {@code minCapacity} bytes of capacity.  The returned buffer is
     * cleared (position 0, limit == capacity).
     *
     * @param minCapacity minimum required capacity in bytes
     * @return a buffer with at least the requested capacity
     */
    public static ByteBuffer acquire(int minCapacity) {
        int idx = bucketIndex(minCapacity);
        if (idx >= 0) {
            ByteBuffer buf = POOL.get()[idx].pollFirst();
            if (buf != null) {
                buf.clear();
                return buf;
            }
            return ByteBuffer.allocate(1 << (idx + MIN_BUCKET_SHIFT));
        }
        return ByteBuffer.allocate(minCapacity);
    }

    /**
     * Releases a {@link ByteBuffer} back to the pool.  The buffer must
     * not be used by the caller after this call.
     *
     * @param buf the buffer to release (may be null, in which case
     *            this method does nothing)
     */
    public static void release(ByteBuffer buf) {
        if (buf == null || buf.isDirect()) {
            return;
        }
        int idx = bucketIndex(buf.capacity());
        if (idx >= 0) {
            ArrayDeque<ByteBuffer> bucket = POOL.get()[idx];
            if (bucket.size() < MAX_PER_BUCKET) {
                bucket.addFirst(buf);
            }
        }
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
