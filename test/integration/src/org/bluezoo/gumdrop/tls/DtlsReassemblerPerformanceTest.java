/*
 * DtlsReassemblerPerformanceTest.java
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

package org.bluezoo.gumdrop.tls;

import java.util.List;
import java.util.Random;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Wall-clock regression test for {@link DtlsReassembler}: before the
 * bulk-copy fix, {@code PartialMessage.markReceived} tested and set one
 * {@link java.util.BitSet} bit and wrote one byte per call, and the
 * caller looped it once per byte of every fragment. A large handshake
 * message (e.g. a sizeable Certificate chain, RFC 6347 section 4.2.3)
 * delivered as many small DTLS fragments makes that per-byte overhead
 * the dominant cost of reassembly. The fix ({@code markRange}) walks
 * {@code BitSet.nextClearBit}/{@code nextSetBit} to bulk-{@code
 * arraycopy} whole unset runs instead.
 *
 * <p>Covers both the common wholly-new-fragment case and the
 * wholly-duplicate-retransmit case (each fragment sent twice) -- the
 * latter is exactly the path {@code markRange}'s run-skipping exists
 * for.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DtlsReassemblerPerformanceTest {

    private static final int FRAGMENT_HEADER_LEN = 12;

    /** Builds one DTLS handshake fragment record (RFC 6347 section 4.2.3 header + body). */
    private static byte[] fragment(int msgType, int totalLength, int messageSeq,
            int fragmentOffset, byte[] body, int bodyOffset, int fragmentLength) {
        byte[] record = new byte[FRAGMENT_HEADER_LEN + fragmentLength];
        record[0] = (byte) msgType;
        record[1] = (byte) (totalLength >>> 16);
        record[2] = (byte) (totalLength >>> 8);
        record[3] = (byte) totalLength;
        record[4] = (byte) (messageSeq >>> 8);
        record[5] = (byte) messageSeq;
        record[6] = (byte) (fragmentOffset >>> 16);
        record[7] = (byte) (fragmentOffset >>> 8);
        record[8] = (byte) fragmentOffset;
        record[9] = (byte) (fragmentLength >>> 16);
        record[10] = (byte) (fragmentLength >>> 8);
        record[11] = (byte) fragmentLength;
        System.arraycopy(body, bodyOffset, record, FRAGMENT_HEADER_LEN, fragmentLength);
        return record;
    }

    @Test(timeout = 30000)
    public void manySmallInOrderFragmentsStayFastAsMessageGrows() throws Exception {
        byte[] body = new byte[500000];
        new Random(42).nextBytes(body);
        int fragmentSize = 8;

        DtlsReassembler reassembler = new DtlsReassembler();
        List<byte[]> delivered = null;
        long start = System.nanoTime();
        for (int offset = 0; offset < body.length; offset += fragmentSize) {
            int len = Math.min(fragmentSize, body.length - offset);
            byte[] record = fragment(1, body.length, 0, offset, body, offset, len);
            List<byte[]> ready = reassembler.addFragment(record);
            if (!ready.isEmpty()) {
                delivered = ready;
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertEquals(1, delivered.size());
        byte[] message = delivered.get(0);
        assertEquals(4 + body.length, message.length);
        for (int i = 0; i < body.length; i++) {
            assertEquals(body[i], message[4 + i]);
        }
        assertTrue((body.length / fragmentSize) + " in-order fragments reassembling a "
                + body.length + "-byte message took " + elapsedMs
                + "ms -- expected bulk-copying unset runs instead of a per-byte BitSet loop",
                elapsedMs < 1000);
    }

    /**
     * Every fragment arrives twice (a realistic DTLS retransmit pattern
     * under loss) -- the second copy of each fragment is a
     * wholly-duplicate range, exactly what {@code markRange}'s
     * unset-run walk exists to skip cheaply.
     */
    @Test(timeout = 30000)
    public void duplicateRetransmittedFragmentsStayFast() throws Exception {
        byte[] body = new byte[300000];
        new Random(7).nextBytes(body);
        int fragmentSize = 16;

        DtlsReassembler reassembler = new DtlsReassembler();
        List<byte[]> delivered = null;
        long start = System.nanoTime();
        for (int offset = 0; offset < body.length; offset += fragmentSize) {
            int len = Math.min(fragmentSize, body.length - offset);
            byte[] record = fragment(1, body.length, 0, offset, body, offset, len);
            List<byte[]> ready = reassembler.addFragment(record); // first delivery
            if (!ready.isEmpty()) {
                delivered = ready;
            }
            ready = reassembler.addFragment(record); // retransmit
            if (!ready.isEmpty()) {
                delivered = ready;
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertEquals(1, delivered.size());
        assertEquals(4 + body.length, delivered.get(0).length);
        assertTrue((body.length / fragmentSize) + " duplicated fragments (2x each) reassembling a "
                + body.length + "-byte message took " + elapsedMs
                + "ms -- expected duplicate ranges to be recognized and skipped in bulk",
                elapsedMs < 1000);
    }

}
