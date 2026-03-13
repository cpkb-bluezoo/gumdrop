/*
 * WebSocketFrameTest.java
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

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link WebSocketFrame} — RFC 6455 §5.
 * Covers masking key entropy (§5.3), RSV bit handling (§5.2/§9),
 * frame encoding/decoding, and close code extraction.
 */
public class WebSocketFrameTest {

    // ── #99: SecureRandom masking keys (RFC 6455 §5.3) ──

    @Test
    public void testMaskingKeyIsRandom() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            byte[] key = WebSocketFrame.generateMaskingKey();
            assertEquals(4, key.length);
            keys.add(java.util.Arrays.toString(key));
        }
        // With SecureRandom, 100 keys should all be unique (extremely high probability)
        assertTrue("Masking keys should be random", keys.size() > 90);
    }

    @Test
    public void testMaskingKeyLength() {
        byte[] key = WebSocketFrame.generateMaskingKey();
        assertEquals(4, key.length);
    }

    @Test
    public void testMaskedFrameUsesSecureKey() throws Exception {
        WebSocketFrame frame = new WebSocketFrame(
                WebSocketFrame.OPCODE_TEXT, "hello".getBytes(StandardCharsets.UTF_8), true);
        assertTrue(frame.isMasked());
        assertNotNull(frame.getMaskingKey());
        assertEquals(4, frame.getMaskingKey().length);
    }

    // ── RSV bit handling (§5.2, §9) ──

    @Test
    public void testFrameWithRsv1Allowed() throws Exception {
        // Extension frames with RSV1 should be constructible
        WebSocketFrame frame = new WebSocketFrame(
                WebSocketFrame.OPCODE_TEXT, "test".getBytes(StandardCharsets.UTF_8),
                false, true);
        assertTrue(frame.isRsv1());
        assertFalse(frame.isRsv2());
        assertFalse(frame.isRsv3());
    }

    @Test
    public void testFrameWithRsv1EncodesCorrectly() throws Exception {
        byte[] payload = "hi".getBytes(StandardCharsets.UTF_8);
        WebSocketFrame frame = new WebSocketFrame(
                WebSocketFrame.OPCODE_TEXT, payload, false, true);

        ByteBuffer encoded = frame.encode();
        byte firstByte = encoded.get(0);
        // RSV1 bit is 0x40
        assertTrue("RSV1 should be set", (firstByte & 0x40) != 0);
    }

    @Test
    public void testParseFrameWithRsv1() throws Exception {
        // Build a frame with RSV1=1 manually
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.put((byte) 0xC1); // FIN=1, RSV1=1, opcode=1 (text)
        buf.put((byte) 0x02); // no mask, length=2
        buf.put((byte) 'h');
        buf.put((byte) 'i');
        buf.flip();

        WebSocketFrame parsed = WebSocketFrame.parse(buf);
        assertNotNull(parsed);
        assertTrue(parsed.isFin());
        assertTrue(parsed.isRsv1());
        assertFalse(parsed.isRsv2());
        assertEquals(WebSocketFrame.OPCODE_TEXT, parsed.getOpcode());
        assertEquals("hi", parsed.getTextPayload());
    }

    @Test
    public void testParseFrameWithoutRsv() throws Exception {
        WebSocketFrame frame = WebSocketFrame.createTextFrame("hello", false);
        ByteBuffer encoded = frame.encode();
        WebSocketFrame parsed = WebSocketFrame.parse(encoded);
        assertNotNull(parsed);
        assertFalse(parsed.isRsv1());
        assertFalse(parsed.isRsv2());
        assertFalse(parsed.isRsv3());
    }

    // ── Basic frame operations ──

    @Test
    public void testTextFrameRoundTrip() throws Exception {
        String text = "Hello, WebSocket!";
        WebSocketFrame frame = WebSocketFrame.createTextFrame(text, false);
        ByteBuffer encoded = frame.encode();
        WebSocketFrame parsed = WebSocketFrame.parse(encoded);
        assertNotNull(parsed);
        assertEquals(text, parsed.getTextPayload());
    }

    @Test
    public void testMaskedTextFrameRoundTrip() throws Exception {
        String text = "Masked message";
        WebSocketFrame frame = WebSocketFrame.createTextFrame(text, true);
        assertTrue(frame.isMasked());
        ByteBuffer encoded = frame.encode();
        WebSocketFrame parsed = WebSocketFrame.parse(encoded);
        assertNotNull(parsed);
        assertEquals(text, parsed.getTextPayload());
    }

    @Test
    public void testBinaryFrameRoundTrip() throws Exception {
        byte[] data = {0x01, 0x02, 0x03, 0x04, 0x05};
        WebSocketFrame frame = WebSocketFrame.createBinaryFrame(
                ByteBuffer.wrap(data), false);
        ByteBuffer encoded = frame.encode();
        WebSocketFrame parsed = WebSocketFrame.parse(encoded);
        assertNotNull(parsed);
        assertEquals(WebSocketFrame.OPCODE_BINARY, parsed.getOpcode());
        ByteBuffer payload = parsed.getPayload();
        byte[] result = new byte[payload.remaining()];
        payload.get(result);
        assertArrayEquals(data, result);
    }

    @Test
    public void testCloseFrameCodeExtraction() throws Exception {
        WebSocketFrame close = WebSocketFrame.createCloseFrame(1000, "Normal", false);
        ByteBuffer encoded = close.encode();
        WebSocketFrame parsed = WebSocketFrame.parse(encoded);
        assertNotNull(parsed);
        assertEquals(1000, parsed.getCloseCode());
        assertEquals("Normal", parsed.getCloseReason());
    }

    @Test
    public void testPingFrameRoundTrip() throws Exception {
        byte[] payload = "ping".getBytes(StandardCharsets.UTF_8);
        WebSocketFrame ping = WebSocketFrame.createPingFrame(
                ByteBuffer.wrap(payload), false);
        assertTrue(ping.isControlFrame());
        ByteBuffer encoded = ping.encode();
        WebSocketFrame parsed = WebSocketFrame.parse(encoded);
        assertNotNull(parsed);
        assertEquals(WebSocketFrame.OPCODE_PING, parsed.getOpcode());
    }

    @Test(expected = WebSocketProtocolException.class)
    public void testControlFrameCannotBeFragmented() throws Exception {
        new WebSocketFrame(false, false, false, false,
                WebSocketFrame.OPCODE_PING, false, null,
                "data".getBytes(StandardCharsets.UTF_8));
    }

    @Test(expected = WebSocketProtocolException.class)
    public void testControlFramePayloadMax125() throws Exception {
        byte[] bigPayload = new byte[126];
        new WebSocketFrame(true, false, false, false,
                WebSocketFrame.OPCODE_PING, false, null, bigPayload);
    }

    @Test
    public void testInsufficientDataReturnsNull() throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(1);
        buf.put((byte) 0x81);
        buf.flip();
        assertNull(WebSocketFrame.parse(buf));
    }
}
