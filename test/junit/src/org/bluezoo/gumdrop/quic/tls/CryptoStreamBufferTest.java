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

import tech.kwik.agent15.ProtectionKeysType;
import tech.kwik.agent15.engine.MessageProcessor;
import tech.kwik.agent15.engine.TlsMessageParser;
import tech.kwik.agent15.handshake.HandshakeMessage;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CryptoStreamBuffer}, focused on reassembly of
 * out-of-order/overlapping/split CRYPTO frames -- not on real TLS message
 * semantics, which {@link TlsMessageParser} itself is already responsible
 * for and doesn't need re-testing here. {@link RecordingParser} replaces
 * real parsing with simply recording the complete, reassembled message
 * bytes handed to it, so these tests only exercise
 * {@link CryptoStreamBuffer}'s own message-boundary/offset logic.
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

    /**
     * Bypasses real TLS parsing entirely -- just records the complete
     * message bytes {@link CryptoStreamBuffer} hands it, in call order.
     */
    private static class RecordingParser extends TlsMessageParser {

        final List<byte[]> messages = new ArrayList<byte[]>();

        @Override
        public HandshakeMessage parseAndProcessHandshakeMessage(ByteBuffer buffer,
                MessageProcessor processor, ProtectionKeysType keysType) {
            byte[] copy = new byte[buffer.remaining()];
            buffer.get(copy);
            messages.add(copy);
            return null;
        }
    }

    @Test
    public void testSingleInOrderMessage() throws Exception {
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        RecordingParser parser = new RecordingParser();
        byte[] msg = message(1, new byte[] { 1, 2, 3, 4 });
        buf.receive(0, ByteBuffer.wrap(msg), parser, null, EncryptionLevel.INITIAL);
        assertEquals(1, parser.messages.size());
        assertArrayEquals(msg, parser.messages.get(0));
    }

    @Test
    public void testMessageSplitAcrossTwoInOrderFrames() throws Exception {
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        RecordingParser parser = new RecordingParser();
        byte[] msg = message(1, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        byte[] first = java.util.Arrays.copyOfRange(msg, 0, 6);
        byte[] second = java.util.Arrays.copyOfRange(msg, 6, msg.length);
        buf.receive(0, ByteBuffer.wrap(first), parser, null, EncryptionLevel.INITIAL);
        assertEquals("partial message must not be dispatched yet", 0, parser.messages.size());
        buf.receive(6, ByteBuffer.wrap(second), parser, null, EncryptionLevel.INITIAL);
        assertEquals(1, parser.messages.size());
        assertArrayEquals(msg, parser.messages.get(0));
    }

    @Test
    public void testOutOfOrderFramesReassembleIntoOneCorrectMessage() throws Exception {
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        RecordingParser parser = new RecordingParser();
        byte[] msg = message(1, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        byte[] first = java.util.Arrays.copyOfRange(msg, 0, 6);
        byte[] second = java.util.Arrays.copyOfRange(msg, 6, msg.length);
        // Second half arrives first -- must be buffered, not dispatched.
        buf.receive(6, ByteBuffer.wrap(second), parser, null, EncryptionLevel.INITIAL);
        assertEquals(0, parser.messages.size());
        // First half closes the gap -- the complete, correctly-ordered
        // message is dispatched exactly once.
        buf.receive(0, ByteBuffer.wrap(first), parser, null, EncryptionLevel.INITIAL);
        assertEquals(1, parser.messages.size());
        assertArrayEquals(msg, parser.messages.get(0));
    }

    @Test
    public void testTwoMessagesDeliveredOutOfFrameOrder() throws Exception {
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        RecordingParser parser = new RecordingParser();
        byte[] msgA = message(1, new byte[] { 0xA, 0xA, 0xA });
        byte[] msgB = message(2, new byte[] { 0xB, 0xB });
        byte[] combined = concat(msgA, msgB);
        // Frame carrying (part of) msgB's tail arrives before the frame
        // carrying msgA -- both messages must still end up dispatched
        // exactly once each, in the correct stream order.
        buf.receive(msgA.length, ByteBuffer.wrap(msgB), parser, null, EncryptionLevel.HANDSHAKE);
        assertEquals(0, parser.messages.size());
        buf.receive(0, ByteBuffer.wrap(msgA), parser, null, EncryptionLevel.HANDSHAKE);
        assertEquals(2, parser.messages.size());
        assertArrayEquals(msgA, parser.messages.get(0));
        assertArrayEquals(msgB, parser.messages.get(1));
    }

    @Test
    public void testOverlappingRetransmissionIgnored() throws Exception {
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        RecordingParser parser = new RecordingParser();
        byte[] msg = message(1, new byte[] { 1, 2, 3, 4 });
        buf.receive(0, ByteBuffer.wrap(msg), parser, null, EncryptionLevel.INITIAL);
        assertEquals(1, parser.messages.size());
        // Full retransmission of the same bytes (e.g. peer's PTO
        // retransmit racing with the original arriving) must not
        // re-dispatch the message.
        buf.receive(0, ByteBuffer.wrap(msg), parser, null, EncryptionLevel.INITIAL);
        assertEquals(1, parser.messages.size());
    }

    @Test(expected = StreamReassembler.BufferLimitExceededException.class)
    public void testBufferLimitExceededPropagates() throws Exception {
        CryptoStreamBuffer buf = new CryptoStreamBuffer();
        RecordingParser parser = new RecordingParser();
        // Out-of-order data far beyond the 64 KiB cap, at a huge offset,
        // must be rejected rather than buffered unboundedly.
        byte[] huge = new byte[70000];
        buf.receive(1_000_000L, ByteBuffer.wrap(huge), parser, null, EncryptionLevel.INITIAL);
    }

}
