/*
 * ByteBufferPoolTest.java
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

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ByteBufferPool}, focused on its core safety
 * contract: {@code acquire(n)} must always return a buffer with capacity
 * {@code >= n}, so callers can write {@code n} bytes without overflowing.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ByteBufferPoolTest {

    /**
     * Regression test for the release/acquire bucketing mismatch.
     *
     * <p>{@code acquire(min)} routes to the smallest power-of-two bucket
     * {@code >= min} and hands back whatever buffer that bucket holds
     * without re-checking capacity. Previously {@code release()} bucketed a
     * buffer by rounding its capacity <em>up</em> to the next power of two,
     * so an externally-sized buffer (e.g. 1500 bytes) landed in the 2048
     * bucket; a later {@code acquire(2048)} then returned only 1500 usable
     * bytes and the caller's {@code put()} threw
     * {@link java.nio.BufferOverflowException} — which, on an I/O thread,
     * killed the whole selector loop. This exercises that exact path.
     */
    @Test
    public void acquireHonoursRequestedCapacityAfterOddSizedRelease() {
        // Feed the pool externally-sized (non power-of-two) buffers, which
        // the release() contract explicitly permits.
        int[] releasedCapacities = { 600, 1000, 1500, 3000, 5000, 12000, 40000 };
        for (int cap : releasedCapacities) {
            ByteBufferPool.release(ByteBuffer.allocate(cap));
        }

        int[] requestSizes = { 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536 };
        for (int req : requestSizes) {
            ByteBuffer buf = ByteBufferPool.acquire(req);
            assertTrue("acquire(" + req + ") returned a buffer with capacity "
                    + buf.capacity() + " (< requested)", buf.capacity() >= req);
            // The historical crash was here: writing exactly the requested
            // number of bytes into an under-sized pooled buffer.
            for (int i = 0; i < req; i++) {
                buf.put((byte) 0);
            }
        }
    }

    /**
     * A freshly acquired buffer must be ready to fill from the start:
     * position 0 and limit == capacity.
     */
    @Test
    public void acquireReturnsClearedBuffer() {
        ByteBuffer buf = ByteBufferPool.acquire(4096);
        assertEquals("position should be 0", 0, buf.position());
        assertEquals("limit should equal capacity", buf.capacity(), buf.limit());
        assertTrue("capacity should be at least the request", buf.capacity() >= 4096);
    }

    /**
     * Requests larger than the largest bucket (64K) are satisfied by a direct
     * allocation of at least the requested size and must never be under-sized.
     */
    @Test
    public void acquireAboveMaxBucketAllocatesExactly() {
        int req = 200_000;
        ByteBuffer buf = ByteBufferPool.acquire(req);
        assertTrue("over-max request must still meet capacity",
                buf.capacity() >= req);
        for (int i = 0; i < req; i++) {
            buf.put((byte) 0);
        }
    }

    /**
     * A pooled power-of-two buffer round-trips through release()/acquire()
     * for a same-sized request (reuse path stays correct after the fix).
     */
    @Test
    public void powerOfTwoBufferRoundTrips() {
        ByteBuffer released = ByteBuffer.allocate(8192);
        ByteBufferPool.release(released);
        ByteBuffer reacquired = ByteBufferPool.acquire(8192);
        assertTrue("reused buffer must satisfy the request",
                reacquired.capacity() >= 8192);
    }
}
