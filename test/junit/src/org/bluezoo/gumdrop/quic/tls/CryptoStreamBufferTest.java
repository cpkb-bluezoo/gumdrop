/*
 * CryptoStreamBufferTest.java
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CryptoStreamBuffer}, focused on reassembly of
 * out-of-order/overlapping/split CRYPTO frames into complete handshake
 * messages -- not on real TLS message semantics, which {@code
 * TlsMessageParser} itself is responsible for and is exercised
 * separately, off the caller's thread, by {@link
 * QuicHandshakeAsyncOffload}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class CryptoStreamBufferTest {

    /** RFC 8446 section 4: handshake message header is type(1) + length(3) octets. */
    private static byte[] message(int type, byte[] payload) {
        byte[] m = new byte[4 + payload.length];
        m[0] = (byte) type;
        m[1] = (byte) (payload.length >>> 16);
        m[2] = (byte) (payload.length >>> 8);
        m[3] = (byte) payload.length;
        System.arraycopy(payload, 0, m, 4, payload.length);
        return m;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] copy = new byte[buffer.remaining()];
        buffer.get(copy);
        return copy;
    }

    @Test
    public void testSingleInOrderMessage() throws Exception {
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        byte[] msg = message(1, new byte[] { 1, 2, 3, 4 });
        List<ByteBuffer> messages = buf.receiveAndExtractMessages(0, ByteBuffer.wrap(msg));
        assertEquals(1, messages.size());
        assertArrayEquals(msg, bytes(messages.get(0)));
    }

    @Test
    public void testMessageSplitAcrossTwoInOrderFrames() throws Exception {
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        byte[] msg = message(1, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        byte[] first = java.util.Arrays.copyOfRange(msg, 0, 6);
        byte[] second = java.util.Arrays.copyOfRange(msg, 6, msg.length);
        List<ByteBuffer> messages = buf.receiveAndExtractMessages(0, ByteBuffer.wrap(first));
        assertEquals("partial message must not be extracted yet", 0, messages.size());
        messages = buf.receiveAndExtractMessages(6, ByteBuffer.wrap(second));
        assertEquals(1, messages.size());
        assertArrayEquals(msg, bytes(messages.get(0)));
    }

    @Test
    public void testOutOfOrderFramesReassembleIntoOneCorrectMessage() throws Exception {
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        byte[] msg = message(1, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        byte[] first = java.util.Arrays.copyOfRange(msg, 0, 6);
        byte[] second = java.util.Arrays.copyOfRange(msg, 6, msg.length);
        // Second half arrives first -- must be buffered, not extracted.
        List<ByteBuffer> messages = buf.receiveAndExtractMessages(6, ByteBuffer.wrap(second));
        assertEquals(0, messages.size());
        // First half closes the gap -- the complete, correctly-ordered
        // message is extracted exactly once.
        messages = buf.receiveAndExtractMessages(0, ByteBuffer.wrap(first));
        assertEquals(1, messages.size());
        assertArrayEquals(msg, bytes(messages.get(0)));
    }

    @Test
    public void testTwoMessagesDeliveredOutOfFrameOrder() throws Exception {
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        byte[] msgA = message(1, new byte[] { 0xA, 0xA, 0xA });
        byte[] msgB = message(2, new byte[] { 0xB, 0xB });
        // Frame carrying msgB arrives before the frame carrying msgA --
        // both messages must still end up extracted exactly once each,
        // in the correct stream order.
        List<ByteBuffer> messages = buf.receiveAndExtractMessages(msgA.length, ByteBuffer.wrap(msgB));
        assertEquals(0, messages.size());
        messages = buf.receiveAndExtractMessages(0, ByteBuffer.wrap(msgA));
        assertEquals(2, messages.size());
        assertArrayEquals(msgA, bytes(messages.get(0)));
        assertArrayEquals(msgB, bytes(messages.get(1)));
    }

    @Test
    public void testOverlappingRetransmissionIgnored() throws Exception {
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        byte[] msg = message(1, new byte[] { 1, 2, 3, 4 });
        List<ByteBuffer> messages = buf.receiveAndExtractMessages(0, ByteBuffer.wrap(msg));
        assertEquals(1, messages.size());
        // Full retransmission of the same bytes (e.g. peer's PTO
        // retransmit racing with the original arriving) must not
        // re-extract the message.
        messages = buf.receiveAndExtractMessages(0, ByteBuffer.wrap(msg));
        assertEquals(0, messages.size());
    }

    @Test
    public void testExtractedMessageSurvivesLaterAccumulatorMutation() throws Exception {
        // A returned ByteBuffer wraps a fresh copy of the accumulator's
        // bytes at extraction time, so it must remain valid to read even
        // after a later call mutates the underlying accumulator.
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        byte[] msgA = message(1, new byte[] { 0xA, 0xA, 0xA });
        List<ByteBuffer> firstBatch = buf.receiveAndExtractMessages(0, ByteBuffer.wrap(msgA));
        assertEquals(1, firstBatch.size());
        byte[] msgB = message(2, new byte[] { 0xB, 0xB, 0xB, 0xB });
        buf.receiveAndExtractMessages(msgA.length, ByteBuffer.wrap(msgB));
        assertArrayEquals("message extracted earlier must be unaffected by later reassembly",
                msgA, bytes(firstBatch.get(0)));
    }

    @Test(expected = StreamReassembler.BufferLimitExceededException.class)
    public void testBufferLimitExceededPropagates() throws Exception {
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        // Out-of-order data far beyond the 64 KiB cap, at a huge offset,
        // must be rejected rather than buffered unboundedly.
        byte[] huge = new byte[70000];
        buf.receiveAndExtractMessages(1_000_000L, ByteBuffer.wrap(huge));
    }

    /**
     * Regression test for issue #330: the reassembly loop called {@code
     * accumulator.toByteArray()} -- a full copy of every byte received so
     * far -- on every CRYPTO frame, rather than tracking a read position
     * into a growable buffer. A large handshake message delivered as many
     * small in-order frames (a realistic pattern for a sizeable
     * Certificate message split across many QUIC packets) makes the
     * eliminated per-frame full-accumulator copy the dominant cost.
     */
    @Test(timeout = 30000)
    public void testManySmallInOrderFramesStayFastAsHandshakeMessageGrows() throws Exception {
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        byte[] payload = new byte[500000];
        new java.util.Random(42).nextBytes(payload);
        byte[] msg = message(1, payload);

        int frameSize = 8;
        List<ByteBuffer> allMessages = new ArrayList<ByteBuffer>();
        long start = System.nanoTime();
        for (int offset = 0; offset < msg.length; offset += frameSize) {
            int len = Math.min(frameSize, msg.length - offset);
            byte[] chunk = java.util.Arrays.copyOfRange(msg, offset, offset + len);
            allMessages.addAll(buf.receiveAndExtractMessages(offset, ByteBuffer.wrap(chunk)));
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertEquals(1, allMessages.size());
        assertArrayEquals(msg, bytes(allMessages.get(0)));
        assertTrue((msg.length / frameSize) + " frames reassembling a " + msg.length
                + "-byte handshake message took " + elapsedMs
                + "ms -- expected reassembly to track a read position into a growable "
                + "buffer instead of copying the whole accumulator on every frame",
                elapsedMs < 300);
    }

}
