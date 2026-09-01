/*
 * HttpDatagramContextTest.java
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

package org.bluezoo.gumdrop.http;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;

import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.websocket.WebSocketEventHandler;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link HttpDatagramContext} -- issue #391: RFC 9298 section 5
 * (also used by RFC 9484) prefixes an HTTP Datagram's payload with a
 * Context ID, a QUIC varint, so several concurrently-negotiated flows
 * (e.g. compression, additional tunnels) can share one request's
 * datagram stream. Context ID 0 is reserved for the payload registered
 * to the request itself.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpDatagramContextTest {

    @Test
    public void testRoundTripRegisteredContextId() {
        byte[] payload = "hello".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer encoded = HttpDatagramContext.encode(
                HttpDatagramContext.REGISTERED_CONTEXT_ID, ByteBuffer.wrap(payload));
        HttpDatagramContext decoded = HttpDatagramContext.decode(encoded);
        assertEquals(HttpDatagramContext.REGISTERED_CONTEXT_ID, decoded.getContextId());
        assertArrayEquals(payload, remaining(decoded.getPayload()));
    }

    @Test
    public void testRoundTripEmptyPayload() {
        ByteBuffer encoded = HttpDatagramContext.encode(0L, ByteBuffer.wrap(new byte[0]));
        HttpDatagramContext decoded = HttpDatagramContext.decode(encoded);
        assertEquals(0L, decoded.getContextId());
        assertEquals(0, decoded.getPayload().remaining());
    }

    @Test
    public void testRoundTripNullPayload() {
        ByteBuffer encoded = HttpDatagramContext.encode(0L, null);
        HttpDatagramContext decoded = HttpDatagramContext.decode(encoded);
        assertEquals(0L, decoded.getContextId());
        assertEquals(0, decoded.getPayload().remaining());
    }

    /**
     * Context IDs at the varint length-class boundaries (RFC 9000
     * section 16: 1/2/4/8-byte encodings) -- exactly where an off-by-one
     * in a hand-rolled prefix would show up.
     */
    @Test
    public void testRoundTripMultiByteContextIds() {
        long[] contextIds = {
            0L, 1L, 0x3fL,                 // 1-byte
            0x40L, 0x3fffL,                 // 2-byte
            0x4000L, 0x3fffffffL,           // 4-byte
            0x40000000L, (1L << 62) - 1,    // 8-byte
        };
        byte[] payload = "flow-data".getBytes(StandardCharsets.US_ASCII);
        for (long contextId : contextIds) {
            ByteBuffer encoded = HttpDatagramContext.encode(contextId, ByteBuffer.wrap(payload));
            HttpDatagramContext decoded = HttpDatagramContext.decode(encoded);
            assertEquals("context ID " + contextId, contextId, decoded.getContextId());
            assertArrayEquals("context ID " + contextId, payload, remaining(decoded.getPayload()));
        }
    }

    @Test
    public void testEncodeRejectsOutOfRangeContextId() {
        try {
            HttpDatagramContext.encode(-1L, ByteBuffer.wrap(new byte[] { 1 }));
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testDecodeNullOnEmptyBuffer() {
        assertNull(HttpDatagramContext.decode(ByteBuffer.allocate(0)));
    }

    @Test
    public void testDecodeNullOnNullBuffer() {
        assertNull(HttpDatagramContext.decode(null));
    }

    /**
     * A multi-byte varint whose length prefix (the first byte's top two
     * bits) promises more continuation bytes than are actually present
     * must not be misread as a shorter, in-range value.
     */
    @Test
    public void testDecodeNullOnTruncatedMultiByteVarint() {
        // 0x40 marks a 2-byte varint but only one byte is present.
        ByteBuffer truncated = ByteBuffer.wrap(new byte[] { (byte) 0x40 });
        assertNull(HttpDatagramContext.decode(truncated));

        // 0xc0 marks an 8-byte varint but only three bytes are present.
        ByteBuffer truncated8 = ByteBuffer.wrap(new byte[] { (byte) 0xc0, 0x01, 0x02 });
        assertNull(HttpDatagramContext.decode(truncated8));
    }

    @Test
    public void testDecodeLeavesRemainderPositionedAfterVarint() {
        byte[] payload = { 0x10, 0x20, 0x30 };
        ByteBuffer encoded = HttpDatagramContext.encode(5L, ByteBuffer.wrap(payload));
        HttpDatagramContext decoded = HttpDatagramContext.decode(encoded);
        assertArrayEquals(payload, remaining(decoded.getPayload()));
    }

    // ── HTTPResponseState.sendDatagram(long, ByteBuffer) convenience ──

    private static final class CapturingResponseState implements HTTPResponseState {
        byte[] sent;

        @Override
        public boolean sendDatagram(ByteBuffer data) {
            sent = remaining(data);
            return true;
        }

        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public boolean isSecure() { return true; }
        @Override public SecurityInfo getSecurityInfo() { return null; }
        @Override public HTTPVersion getVersion() { return HTTPVersion.HTTP_3; }
        @Override public String getScheme() { return "https"; }
        @Override public SelectorLoop getSelectorLoop() { return null; }
        @Override public Principal getPrincipal() { return null; }
        @Override public void headers(Headers headers) { }
        @Override public void startResponseBody() { }
        @Override public void responseBodyContent(ByteBuffer data) { }
        @Override public void endResponseBody() { }
        @Override public void complete() { }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void onWritable(Runnable callback) { }
        @Override public void pauseRequestBody() { }
        @Override public void resumeRequestBody() { }
        @Override public boolean pushPromise(Headers headers) { return false; }
        @Override public void upgradeToWebSocket(String subprotocol, WebSocketEventHandler handler) { }
        @Override public void cancel() { }
    }

    @Test
    public void testSendDatagramConvenienceEncodesContextIdPrefix() {
        CapturingResponseState state = new CapturingResponseState();
        byte[] payload = "tunnel-data".getBytes(StandardCharsets.US_ASCII);

        boolean queued = state.sendDatagram(7L, ByteBuffer.wrap(payload));

        assertTrue(queued);
        HttpDatagramContext decoded = HttpDatagramContext.decode(ByteBuffer.wrap(state.sent));
        assertEquals(7L, decoded.getContextId());
        assertArrayEquals(payload, remaining(decoded.getPayload()));
    }

    private static byte[] remaining(ByteBuffer buf) {
        byte[] bytes = new byte[buf.remaining()];
        buf.duplicate().get(bytes);
        return bytes;
    }
}
