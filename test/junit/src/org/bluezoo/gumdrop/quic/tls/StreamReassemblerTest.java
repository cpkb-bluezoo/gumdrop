/*
 * StreamReassemblerTest.java
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

package org.bluezoo.gumdrop.quic.tls;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link StreamReassembler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StreamReassemblerTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static String string(byte[] b) {
        return new String(b, StandardCharsets.US_ASCII);
    }

    @Test
    public void testInOrderDeliveryPassesThroughImmediately() throws Exception {
        StreamReassembler r = new StreamReassembler(Long.MAX_VALUE);
        assertEquals("hello", string(r.receive(0, bytes("hello"))));
        assertEquals(5, r.getNextOffset());
        assertEquals(" world", string(r.receive(5, bytes(" world"))));
        assertEquals(11, r.getNextOffset());
    }

    @Test
    public void testSingleOutOfOrderChunkBufferedThenReleased() throws Exception {
        StreamReassembler r = new StreamReassembler(Long.MAX_VALUE);
        // "world" arrives first, at offset 6 -- must be held, not delivered.
        assertEquals(0, r.receive(6, bytes("world")).length);
        assertEquals(0, r.getNextOffset());
        // "hello " arrives next, closing the gap -- both chunks now deliver
        // together in the correct order.
        assertEquals("hello world", string(r.receive(0, bytes("hello "))));
        assertEquals(11, r.getNextOffset());
    }

    @Test
    public void testMultipleOutOfOrderChunksCascadeInOneDrain() throws Exception {
        StreamReassembler r = new StreamReassembler(Long.MAX_VALUE);
        assertEquals(0, r.receive(10, bytes("CCCC")).length); // offset 10-14
        assertEquals(0, r.receive(5, bytes("BBBBB")).length); // offset 5-10
        // The chunk closing the initial gap should cascade through both
        // buffered chunks in one call, in the correct order.
        assertEquals("AAAAABBBBBCCCC", string(r.receive(0, bytes("AAAAA"))));
        assertEquals(14, r.getNextOffset());
    }

    @Test
    public void testFullyDuplicateFrameIgnored() throws Exception {
        StreamReassembler r = new StreamReassembler(Long.MAX_VALUE);
        assertEquals("hello", string(r.receive(0, bytes("hello"))));
        // Exact retransmission of already-delivered bytes.
        assertEquals(0, r.receive(0, bytes("hello")).length);
        assertEquals(5, r.getNextOffset());
    }

    @Test
    public void testPartiallyOverlappingFrameTrimmed() throws Exception {
        StreamReassembler r = new StreamReassembler(Long.MAX_VALUE);
        assertEquals("hello", string(r.receive(0, bytes("hello"))));
        // Overlaps bytes 3-5 (already delivered) and adds new bytes 5-11.
        assertEquals(" world", string(r.receive(3, bytes("lo world"))));
        assertEquals(11, r.getNextOffset());
    }

    @Test
    public void testOverlappingPendingChunksMergedAtDrainTime() throws Exception {
        StreamReassembler r = new StreamReassembler(Long.MAX_VALUE);
        // Two out-of-order chunks whose ranges overlap each other:
        // [10,30) and [20,40), buffered before the initial gap [0,10) closes.
        assertEquals(0, r.receive(10, bytes("0123456789012345678901")).length); // 10-32
        assertEquals(0, r.receive(20, bytes("0123456789012345678901")).length); // 20-42, overlaps the first
        byte[] head = bytes("HEADHEAD.."); // exactly 10 bytes, offset 0-10
        byte[] result = r.receive(0, head);
        // Must be delivered once, correctly trimmed, with no duplicated
        // or dropped bytes across the overlap.
        assertEquals(42, r.getNextOffset());
        assertEquals(42, result.length);
    }

    @Test
    public void testZeroLengthDataIsNoOp() throws Exception {
        StreamReassembler r = new StreamReassembler(Long.MAX_VALUE);
        assertEquals(0, r.receive(0, new byte[0]).length);
        assertEquals(0, r.getNextOffset());
    }

    @Test
    public void testBufferLimitExceededThrows() throws Exception {
        StreamReassembler r = new StreamReassembler(10);
        try {
            // Out-of-order chunk larger than the configured cap.
            r.receive(100, bytes("this is more than ten bytes"));
            fail("expected BufferLimitExceededException");
        } catch (StreamReassembler.BufferLimitExceededException expected) {
            // pass
        }
    }

    @Test
    public void testBufferLimitNotExceededByInOrderData() throws Exception {
        StreamReassembler r = new StreamReassembler(4);
        // In-order data is never buffered (delivered immediately), so an
        // arbitrarily long in-order stream must not trip the cap.
        assertEquals("hello world, this is a long contiguous stream",
                string(r.receive(0, bytes("hello world, this is a long contiguous stream"))));
    }

    @Test
    public void testGapThenExactlyAtLimitSucceeds() throws Exception {
        StreamReassembler r = new StreamReassembler(5);
        // Buffering exactly up to (not beyond) the cap must not throw.
        assertEquals(0, r.receive(5, bytes("12345")).length);
        assertEquals(0, r.getNextOffset());
        assertEquals("0123412345", string(r.receive(0, bytes("01234"))));
        assertEquals(10, r.getNextOffset());
    }

}
