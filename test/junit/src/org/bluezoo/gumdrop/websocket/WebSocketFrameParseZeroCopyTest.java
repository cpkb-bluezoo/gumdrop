/*
 * WebSocketFrameParseZeroCopyTest.java
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

package org.bluezoo.gumdrop.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for issue #323: {@code WebSocketFrame.parse()} always
 * copied the payload into a fresh array (instead of slicing the source
 * buffer where the frame's lifetime allows it, as {@code H2Parser}
 * already does elsewhere in this codebase) and unmasked byte-by-byte
 * (instead of XOR-ing several bytes per iteration).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class WebSocketFrameParseZeroCopyTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** RFC 6455 §5.2 frame builder, masked, supporting all three length encodings. */
    private static ByteBuffer buildMaskedFrame(boolean fin, int opcode, byte[] payload, byte[] maskingKey) {
        int len = payload.length;
        int headerLen = 2 + 4 + (len < 126 ? 0 : (len <= 65535 ? 2 : 8));
        ByteBuffer buf = ByteBuffer.allocate(headerLen + len);
        buf.put((byte) ((fin ? 0x80 : 0x00) | opcode));
        if (len < 126) {
            buf.put((byte) (0x80 | len));
        } else if (len <= 65535) {
            buf.put((byte) (0x80 | 126));
            buf.putShort((short) len);
        } else {
            buf.put((byte) (0x80 | 127));
            buf.putLong(len);
        }
        buf.put(maskingKey);
        for (int i = 0; i < len; i++) {
            buf.put((byte) (payload[i] ^ maskingKey[i % 4]));
        }
        buf.flip();
        return buf;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    // ── Correctness across stride boundaries ──
    //
    // The strided unmask XORs 8 bytes at a time and falls back to a
    // byte-at-a-time tail; lengths spanning 0 through several strides,
    // and on either side of an 8-byte boundary, are exactly where an
    // off-by-one in that split would show up.

    @Test
    public void testUnmaskCorrectAcrossStrideBoundaryLengths() throws Exception {
        byte[] maskingKey = { (byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78 };
        for (int len = 0; len <= 20; len++) {
            byte[] original = randomBytes(len);
            ByteBuffer wire = buildMaskedFrame(true, WebSocketFrame.OPCODE_BINARY, original, maskingKey);
            WebSocketFrame parsed = WebSocketFrame.parse(wire);
            ByteBuffer payload = parsed.getPayload();
            byte[] result = new byte[payload.remaining()];
            payload.get(result);
            assertArrayEquals("mismatch at payload length " + len, original, result);
        }
    }

    @Test
    public void testUnmaskCorrectForLargeMultiStridePayload() throws Exception {
        byte[] maskingKey = randomBytesKey();
        byte[] original = randomBytes(100_003); // not a multiple of 8
        ByteBuffer wire = buildMaskedFrame(true, WebSocketFrame.OPCODE_BINARY, original, maskingKey);
        WebSocketFrame parsed = WebSocketFrame.parse(wire);
        ByteBuffer payload = parsed.getPayload();
        byte[] result = new byte[payload.remaining()];
        payload.get(result);
        assertArrayEquals(original, result);
    }

    private static byte[] randomBytesKey() {
        return randomBytes(4);
    }

    // ── Fragmented-message reassembly through the bulk-transfer path ──

    private static class TestConnection extends WebSocketConnection {
        ByteBuffer lastBinaryMessage;

        TestConnection() {
            setTransport(new WebSocketTransport() {
                @Override
                public void sendFrame(ByteBuffer frameData) { }
                @Override
                public void close(boolean normalClose) { }
            });
            notifyConnectionOpen();
            setMaxMessageSize(0); // unlimited
        }

        @Override protected void opened() { }
        @Override protected void textMessageReceived(String message) { }
        @Override protected void binaryMessageReceived(ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            lastBinaryMessage = ByteBuffer.wrap(copy);
        }
        @Override protected void closed(int code, String reason) { }
        @Override protected void error(Throwable cause) { }
    }

    @Test
    public void testLargeMaskedFragmentedMessageReassemblesCorrectly() throws IOException {
        TestConnection conn = new TestConnection();
        byte[] maskingKey = randomBytesKey();

        int fragmentCount = 20;
        int fragmentSize = 5000; // large enough to exercise many unmask strides per fragment
        byte[][] fragments = new byte[fragmentCount][];
        int total = 0;
        for (int i = 0; i < fragmentCount; i++) {
            fragments[i] = randomBytes(fragmentSize + i); // vary lengths across the 8-byte boundary
            total += fragments[i].length;
        }

        ByteBuffer expected = ByteBuffer.allocate(total);
        for (int i = 0; i < fragmentCount; i++) {
            boolean first = (i == 0);
            boolean last = (i == fragmentCount - 1);
            int opcode = first ? WebSocketFrame.OPCODE_BINARY : WebSocketFrame.OPCODE_CONTINUATION;
            conn.processIncomingData(buildMaskedFrame(last, opcode, fragments[i], maskingKey));
            expected.put(fragments[i]);
        }
        expected.flip();

        byte[] expectedBytes = new byte[expected.remaining()];
        expected.get(expectedBytes);
        byte[] actualBytes = new byte[conn.lastBinaryMessage.remaining()];
        conn.lastBinaryMessage.get(actualBytes);
        assertArrayEquals("fragmented message must reassemble byte-for-byte via the "
                + "bulk-transfer path", expectedBytes, actualBytes);
    }

    // ── Performance: strided unmask should be far cheaper than byte-at-a-time ──

    @Test(timeout = 20000)
    public void testRepeatedLargeFrameParsingAvoidsByteAtATimeUnmask() throws Exception {
        byte[] maskingKey = randomBytesKey();
        byte[] payload = randomBytes(1_000_000);
        ByteBuffer template = buildMaskedFrame(true, WebSocketFrame.OPCODE_BINARY, payload, maskingKey);
        byte[] wireBytes = new byte[template.remaining()];
        template.get(wireBytes);

        int iterations = 300;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            WebSocketFrame frame = WebSocketFrame.parse(ByteBuffer.wrap(wireBytes));
            ByteBuffer result = frame.getPayload();
            // Touch the result so the JIT can't eliminate the parse/unmask as dead code.
            if (result.remaining() != payload.length) {
                throw new AssertionError("unexpected payload length");
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(iterations + " parses of a " + payload.length
                + "-byte masked frame took " + elapsedMs
                + "ms -- expected strided (word-at-a-time) unmasking to keep this "
                + "far below the cost of a byte-at-a-time XOR loop",
                elapsedMs < 150);
    }
}
