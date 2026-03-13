/*
 * WebSocketConnectionTest.java
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link WebSocketConnection} — RFC 6455.
 * Covers close code validation (§7.4), maximum message size enforcement
 * (§7.4.1 code 1009), and RSV bit validation with extensions (§9).
 */
public class WebSocketConnectionTest {

    // ── Test infrastructure ──

    private static class TestConnection extends WebSocketConnection {
        String lastTextMessage;
        ByteBuffer lastBinaryMessage;
        int lastCloseCode = -1;
        String lastCloseReason;
        Throwable lastError;
        final List<ByteBuffer> sentFrames = new ArrayList<>();

        TestConnection() {
            setTransport(new WebSocketTransport() {
                @Override
                public void sendFrame(ByteBuffer frameData) {
                    byte[] copy = new byte[frameData.remaining()];
                    frameData.get(copy);
                    sentFrames.add(ByteBuffer.wrap(copy));
                }
                @Override
                public void close(boolean normalClose) { }
            });
        }

        @Override protected void opened() { }
        @Override protected void textMessageReceived(String message) {
            lastTextMessage = message;
        }
        @Override protected void binaryMessageReceived(ByteBuffer data) {
            lastBinaryMessage = data;
        }
        @Override protected void closed(int code, String reason) {
            lastCloseCode = code;
            lastCloseReason = reason;
        }
        @Override protected void error(Throwable cause) {
            lastError = cause;
        }

        void openConnection() {
            notifyConnectionOpen();
        }
    }

    private TestConnection createOpenConnection() {
        TestConnection conn = new TestConnection();
        conn.openConnection();
        return conn;
    }

    private ByteBuffer buildCloseFrame(int code) {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.put((byte) 0x88); // FIN + opcode 8 (close)
        buf.put((byte) 0x02); // length 2
        buf.putShort((short) code);
        buf.flip();
        return buf;
    }

    // ── #98: Close Code Validation (RFC 6455 §7.4) ──

    @Test
    public void testValidCloseCode1000() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.processIncomingData(buildCloseFrame(1000));
        assertEquals(1000, conn.lastCloseCode);
    }

    @Test
    public void testValidCloseCode1001() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.processIncomingData(buildCloseFrame(1001));
        assertEquals(1001, conn.lastCloseCode);
    }

    @Test
    public void testValidCloseCode1002() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.processIncomingData(buildCloseFrame(1002));
        assertEquals(1002, conn.lastCloseCode);
    }

    @Test
    public void testValidCloseCode1003() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.processIncomingData(buildCloseFrame(1003));
        assertEquals(1003, conn.lastCloseCode);
    }

    @Test
    public void testValidCloseCode1009() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.processIncomingData(buildCloseFrame(1009));
        assertEquals(1009, conn.lastCloseCode);
    }

    @Test
    public void testValidCloseCode3000PrivateUse() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.processIncomingData(buildCloseFrame(3000));
        assertEquals(3000, conn.lastCloseCode);
    }

    @Test
    public void testValidCloseCode4999PrivateUse() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.processIncomingData(buildCloseFrame(4999));
        assertEquals(4999, conn.lastCloseCode);
    }

    @Test
    public void testInvalidCloseCode1004ReservedTriggersProtocolError() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.processIncomingData(buildCloseFrame(1004));
        // Should have sent a close frame with 1002 (protocol error)
        assertTrue("Should have sent a close frame", conn.sentFrames.size() > 0);
        ByteBuffer sent = conn.sentFrames.get(0);
        WebSocketFrame closeFrame = WebSocketFrame.parse(sent);
        assertNotNull(closeFrame);
        assertEquals(WebSocketFrame.OPCODE_CLOSE, closeFrame.getOpcode());
        assertEquals(1002, closeFrame.getCloseCode());
    }

    @Test
    public void testInvalidCloseCode1005Reserved() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.processIncomingData(buildCloseFrame(1005));
        assertTrue(conn.sentFrames.size() > 0);
        WebSocketFrame closeFrame = WebSocketFrame.parse(conn.sentFrames.get(0));
        assertEquals(1002, closeFrame.getCloseCode());
    }

    @Test
    public void testInvalidCloseCode1006Reserved() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.processIncomingData(buildCloseFrame(1006));
        assertTrue(conn.sentFrames.size() > 0);
        WebSocketFrame closeFrame = WebSocketFrame.parse(conn.sentFrames.get(0));
        assertEquals(1002, closeFrame.getCloseCode());
    }

    @Test
    public void testInvalidCloseCode1015Reserved() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.processIncomingData(buildCloseFrame(1015));
        assertTrue(conn.sentFrames.size() > 0);
        WebSocketFrame closeFrame = WebSocketFrame.parse(conn.sentFrames.get(0));
        assertEquals(1002, closeFrame.getCloseCode());
    }

    @Test
    public void testInvalidCloseCode999OutOfRange() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.processIncomingData(buildCloseFrame(999));
        assertTrue(conn.sentFrames.size() > 0);
        WebSocketFrame closeFrame = WebSocketFrame.parse(conn.sentFrames.get(0));
        assertEquals(1002, closeFrame.getCloseCode());
    }

    @Test
    public void testInvalidCloseCode5000OutOfRange() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.processIncomingData(buildCloseFrame(5000));
        assertTrue(conn.sentFrames.size() > 0);
        WebSocketFrame closeFrame = WebSocketFrame.parse(conn.sentFrames.get(0));
        assertEquals(1002, closeFrame.getCloseCode());
    }

    // ── #97: Maximum Message Size (RFC 6455 §7.4.1, code 1009) ──

    @Test
    public void testSingleFrameExceedsMaxSize() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.setMaxMessageSize(10);

        // Send a text frame larger than 10 bytes
        ByteBuffer buf = ByteBuffer.allocate(20);
        buf.put((byte) 0x81); // FIN + text
        buf.put((byte) 0x0F); // length 15
        buf.put("123456789012345".getBytes(StandardCharsets.UTF_8));
        buf.flip();

        conn.processIncomingData(buf);

        assertNull("Message should not be delivered", conn.lastTextMessage);
        assertTrue(conn.sentFrames.size() > 0);
        WebSocketFrame closeFrame = WebSocketFrame.parse(conn.sentFrames.get(0));
        assertEquals(1009, closeFrame.getCloseCode());
    }

    @Test
    public void testFragmentedMessageExceedsMaxSize() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.setMaxMessageSize(10);

        // First fragment: 6 bytes (under limit)
        ByteBuffer frag1 = ByteBuffer.allocate(8);
        frag1.put((byte) 0x01); // not FIN, text opcode
        frag1.put((byte) 0x06); // length 6
        frag1.put("abcdef".getBytes(StandardCharsets.UTF_8));
        frag1.flip();

        conn.processIncomingData(frag1);
        assertNull("Should not deliver yet", conn.lastTextMessage);

        // Second fragment: 6 bytes (total 12 > limit)
        ByteBuffer frag2 = ByteBuffer.allocate(8);
        frag2.put((byte) 0x80); // FIN, continuation
        frag2.put((byte) 0x06); // length 6
        frag2.put("ghijkl".getBytes(StandardCharsets.UTF_8));
        frag2.flip();

        conn.processIncomingData(frag2);

        assertNull("Message should not be delivered", conn.lastTextMessage);
        assertTrue(conn.sentFrames.size() > 0);
        WebSocketFrame closeFrame = WebSocketFrame.parse(conn.sentFrames.get(0));
        assertEquals(1009, closeFrame.getCloseCode());
    }

    @Test
    public void testMessageUnderMaxSizeDelivered() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.setMaxMessageSize(100);

        ByteBuffer buf = ByteBuffer.allocate(10);
        buf.put((byte) 0x81); // FIN + text
        buf.put((byte) 0x05); // length 5
        buf.put("hello".getBytes(StandardCharsets.UTF_8));
        buf.flip();

        conn.processIncomingData(buf);
        assertEquals("hello", conn.lastTextMessage);
    }

    @Test
    public void testZeroMaxSizeMeansUnlimited() throws IOException {
        TestConnection conn = createOpenConnection();
        conn.setMaxMessageSize(0);

        byte[] bigPayload = new byte[10000];
        java.util.Arrays.fill(bigPayload, (byte) 'x');

        ByteBuffer buf = ByteBuffer.allocate(10006);
        buf.put((byte) 0x81); // FIN + text
        buf.put((byte) 126); // 16-bit length follows
        buf.putShort((short) 10000);
        buf.put(bigPayload);
        buf.flip();

        conn.processIncomingData(buf);
        assertNotNull(conn.lastTextMessage);
        assertEquals(10000, conn.lastTextMessage.length());
    }

    // ── RSV bit validation with extensions (§5.2, §9) ──

    @Test
    public void testRsv1RejectedWithoutExtension() throws IOException {
        TestConnection conn = createOpenConnection();

        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.put((byte) 0xC1); // FIN=1, RSV1=1, opcode=text
        buf.put((byte) 0x01); // length 1
        buf.put((byte) 'a');
        buf.flip();

        conn.processIncomingData(buf);
        // Should close with protocol error
        assertNull(conn.lastTextMessage);
        assertTrue(conn.sentFrames.size() > 0);
    }

    @Test
    public void testRsv1AcceptedWithExtension() throws IOException {
        TestConnection conn = createOpenConnection();

        // Create a stub extension that uses RSV1
        List<WebSocketExtension> exts = new ArrayList<>();
        exts.add(new StubExtension(true, false, false));
        conn.setExtensions(exts);

        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.put((byte) 0xC1); // FIN=1, RSV1=1, opcode=text
        buf.put((byte) 0x01); // length 1
        buf.put((byte) 'a');
        buf.flip();

        conn.processIncomingData(buf);
        assertEquals("a", conn.lastTextMessage);
    }

    // ── Stub extension for testing ──

    private static class StubExtension implements WebSocketExtension {
        private final boolean rsv1, rsv2, rsv3;

        StubExtension(boolean rsv1, boolean rsv2, boolean rsv3) {
            this.rsv1 = rsv1;
            this.rsv2 = rsv2;
            this.rsv3 = rsv3;
        }

        @Override public String getName() { return "stub"; }
        @Override public boolean usesRsv1() { return rsv1; }
        @Override public boolean usesRsv2() { return rsv2; }
        @Override public boolean usesRsv3() { return rsv3; }
        @Override public java.util.Map<String, String> acceptOffer(java.util.Map<String, String> p) { return p; }
        @Override public java.util.Map<String, String> generateOffer() { return new java.util.LinkedHashMap<>(); }
        @Override public boolean acceptResponse(java.util.Map<String, String> p) { return true; }
        @Override public byte[] encode(byte[] payload) { return payload; }
        @Override public byte[] decode(byte[] payload) { return payload; }
        @Override public void close() { }
    }
}
