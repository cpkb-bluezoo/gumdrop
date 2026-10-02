/*
 * BerDecoderPipelinedDequeuePerformanceTest.java
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

package org.bluezoo.gumdrop.ldap.asn1;

import java.nio.ByteBuffer;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * Wall-clock regression test for {@link BerDecoder}: draining N pipelined
 * messages must cost O(N), not O(N squared). Wall-clock assertions do not
 * belong in the deterministic unit suite; run with
 * {@code ant integration-test-performance}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BerDecoderPipelinedDequeuePerformanceTest {

    @Test(timeout = 5000)
    public void testPipelinedDequeueCostScalesLinearlyNotQuadratically()
            throws Asn1Exception {
        long smallBatchNanos = timePipelinedDequeue(1_000);
        long largeBatchNanos = timePipelinedDequeue(10_000);
        double ratio = (double) largeBatchNanos / Math.max(1L, smallBatchNanos);
        assertTrue("draining 10x pipelined messages took " + ratio
                + "x as long (expected well below 100x for O(n) dequeue)",
                ratio < 50.0);
    }

    private static long timePipelinedDequeue(int count) throws Asn1Exception {
        byte[] one = {0x02, 0x01, 0x01};
        byte[] batch = new byte[count * one.length];
        for (int i = 0; i < count; i++) {
            System.arraycopy(one, 0, batch, i * one.length, one.length);
        }

        BerDecoder decoder = new BerDecoder();
        decoder.receive(ByteBuffer.wrap(batch));

        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            if (decoder.next() == null) {
                throw new AssertionError("expected " + count + " messages, got " + i);
            }
        }
        return System.nanoTime() - start;
    }
}
