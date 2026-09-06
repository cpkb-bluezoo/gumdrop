package org.bluezoo.gumdrop.socks;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.util.ByteBufferPool;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.bluezoo.gumdrop.socks.SOCKSConstants.*;

/**
 * Unit tests for {@link SOCKSUDPHeader}. {@link SOCKSUDPHeader#parse}
 * is a push-style callback API (no materialized "parsed header"
 * object), so {@link #parse} below records a call into a small local
 * test-only holder for assertions.
 */
public class SOCKSUDPHeaderTest {

    private static class Recorded {
        boolean called;
        byte frag;
        InetAddress address;
        String hostname;
        int port;
        byte[] payload;
    }

    private Recorded parse(ByteBuffer data) {
        final Recorded r = new Recorded();
        SOCKSUDPHeader.parse(data, new SOCKSUDPHeader.Handler() {
            @Override
            public void datagram(byte frag, InetAddress address,
                    String hostname, int port, ByteBuffer payload) {
                r.called = true;
                r.frag = frag;
                r.address = address;
                r.hostname = hostname;
                r.port = port;
                r.payload = new byte[payload.remaining()];
                payload.get(r.payload);
            }
        });
        return r;
    }

    // ── parse() ──

    @Test
    public void testParseIPv4() throws UnknownHostException {
        // RSV(2) + FRAG(1) + ATYP(1) + IPv4(4) + PORT(2) + DATA
        ByteBuffer buf = ByteBuffer.allocate(14);
        buf.putShort((short) 0x0000);       // RSV
        buf.put((byte) 0x00);               // FRAG
        buf.put(SOCKS5_ATYP_IPV4);          // ATYP
        buf.put(new byte[]{10, 0, 0, 1});   // DST.ADDR
        buf.putShort((short) 8080);          // DST.PORT
        buf.put(new byte[]{0x41, 0x42});     // DATA ("AB")
        buf.flip();

        Recorded r = parse(buf);
        assertTrue(r.called);
        assertEquals(0, r.frag);
        assertEquals(InetAddress.getByAddress(new byte[]{10, 0, 0, 1}), r.address);
        assertNull(r.hostname);
        assertEquals(8080, r.port);
        assertArrayEquals(new byte[]{0x41, 0x42}, r.payload);
    }

    @Test
    public void testParseIPv6() throws UnknownHostException {
        byte[] ipv6 = new byte[16];
        ipv6[0] = 0x20;
        ipv6[1] = 0x01;
        ipv6[15] = 0x01;

        ByteBuffer buf = ByteBuffer.allocate(4 + 16 + 2 + 3);
        buf.putShort((short) 0);
        buf.put((byte) 0);
        buf.put(SOCKS5_ATYP_IPV6);
        buf.put(ipv6);
        buf.putShort((short) 443);
        buf.put(new byte[]{1, 2, 3});
        buf.flip();

        Recorded r = parse(buf);
        assertTrue(r.called);
        assertEquals(InetAddress.getByAddress(ipv6), r.address);
        assertNull(r.hostname);
        assertEquals(443, r.port);
        assertArrayEquals(new byte[]{1, 2, 3}, r.payload);
    }

    @Test
    public void testParseDomainName() {
        String domain = "example.com";
        byte[] nameBytes = domain.getBytes();

        ByteBuffer buf = ByteBuffer.allocate(
                4 + 1 + nameBytes.length + 2 + 2);
        buf.putShort((short) 0);
        buf.put((byte) 0);
        buf.put(SOCKS5_ATYP_DOMAINNAME);
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        buf.putShort((short) 53);
        buf.put(new byte[]{0x0A, 0x0B});
        buf.flip();

        Recorded r = parse(buf);
        assertTrue(r.called);
        assertNull(r.address);
        assertEquals("example.com", r.hostname);
        assertEquals(53, r.port);
    }

    @Test
    public void testParseFragmented() {
        ByteBuffer buf = ByteBuffer.allocate(14);
        buf.putShort((short) 0);
        buf.put((byte) 0x03);               // FRAG != 0
        buf.put(SOCKS5_ATYP_IPV4);
        buf.put(new byte[]{1, 2, 3, 4});
        buf.putShort((short) 80);
        buf.put(new byte[]{0x41, 0x42});
        buf.flip();

        Recorded r = parse(buf);
        assertTrue(r.called);
        assertEquals(3, r.frag);
    }

    @Test
    public void testParseTooShort() {
        ByteBuffer buf = ByteBuffer.allocate(3);
        buf.put(new byte[]{0, 0, 0});
        buf.flip();
        assertFalse(parse(buf).called);
    }

    @Test
    public void testParseIPv4TooShort() {
        ByteBuffer buf = ByteBuffer.allocate(7);
        buf.putShort((short) 0);
        buf.put((byte) 0);
        buf.put(SOCKS5_ATYP_IPV4);
        buf.put(new byte[]{1, 2, 3}); // only 3 bytes, need 4 + 2
        buf.flip();
        assertFalse(parse(buf).called);
    }

    @Test
    public void testParseIPv6TooShort() {
        ByteBuffer buf = ByteBuffer.allocate(10);
        buf.putShort((short) 0);
        buf.put((byte) 0);
        buf.put(SOCKS5_ATYP_IPV6);
        buf.put(new byte[]{1, 2, 3, 4, 5, 6}); // 6 bytes, need 16 + 2
        buf.flip();
        assertFalse(parse(buf).called);
    }

    @Test
    public void testParseDomainNameTooShort() {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putShort((short) 0);
        buf.put((byte) 0);
        buf.put(SOCKS5_ATYP_DOMAINNAME);
        buf.put((byte) 10); // claims 10 bytes, but only 3 remain
        buf.put(new byte[]{0x61, 0x62, 0x63});
        buf.flip();
        assertFalse(parse(buf).called);
    }

    @Test
    public void testParseUnknownATYP() {
        ByteBuffer buf = ByteBuffer.allocate(10);
        buf.putShort((short) 0);
        buf.put((byte) 0);
        buf.put((byte) 0x99); // unknown ATYP
        buf.put(new byte[]{1, 2, 3, 4, 5, 6});
        buf.flip();
        assertFalse(parse(buf).called);
    }

    // ── encode() ──

    @Test
    public void testEncodeIPv4() throws UnknownHostException {
        InetSocketAddress source = new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34}),
                80);
        ByteBuffer payload = ByteBuffer.wrap(new byte[]{0x48, 0x49});

        ByteBuffer result = SOCKSUDPHeader.encode(source, payload);
        try {
            assertNotNull(result);
            assertEquals(12, result.remaining()); // 4+4+2 header + 2 data

            assertEquals(0, result.getShort());         // RSV
            assertEquals(0, result.get());              // FRAG
            assertEquals(SOCKS5_ATYP_IPV4, result.get()); // ATYP

            byte[] addr = new byte[4];
            result.get(addr);
            assertEquals(93, addr[0] & 0xFF);
            assertEquals(80, result.getShort() & 0xFFFF); // PORT
            assertEquals(0x48, result.get());              // DATA[0]
            assertEquals(0x49, result.get());              // DATA[1]
        } finally {
            ByteBufferPool.release(result);
        }
    }

    @Test
    public void testEncodeIPv6() throws UnknownHostException {
        byte[] ipv6 = new byte[16];
        ipv6[0] = 0x20;
        ipv6[1] = 0x01;

        InetSocketAddress source = new InetSocketAddress(
                InetAddress.getByAddress(ipv6), 443);
        ByteBuffer payload = ByteBuffer.wrap(new byte[]{0x01});

        ByteBuffer result = SOCKSUDPHeader.encode(source, payload);
        try {
            // header = 4 + 16 + 2 = 22, data = 1
            assertEquals(23, result.remaining());

            result.getShort(); // RSV
            result.get();      // FRAG
            assertEquals(SOCKS5_ATYP_IPV6, result.get());
        } finally {
            ByteBufferPool.release(result);
        }
    }

    @Test
    public void testEncodeEmptyPayload() throws UnknownHostException {
        InetSocketAddress source = new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{1, 2, 3, 4}), 9999);
        ByteBuffer payload = ByteBuffer.allocate(0);

        ByteBuffer result = SOCKSUDPHeader.encode(source, payload);
        try {
            assertEquals(10, result.remaining()); // header only, 0 data
        } finally {
            ByteBufferPool.release(result);
        }
    }

    /**
     * Regression for issue #333: outbound UDP ASSOCIATE headers must use
     * {@link ByteBufferPool} rather than allocating a fresh heap buffer per
     * datagram.
     */
    @Test
    public void testEncodeUsesByteBufferPool() throws UnknownHostException {
        InetSocketAddress source = new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), 53);
        ByteBuffer payload = ByteBuffer.wrap(new byte[]{0x01, 0x02});

        ByteBuffer encoded = SOCKSUDPHeader.encode(source, payload);
        int capacity = encoded.capacity();
        try {
            Recorded r = parse(encoded.duplicate());
            assertTrue(r.called);
            assertEquals(source.getAddress(), r.address);
            assertEquals(source.getPort(), r.port);
        } finally {
            ByteBufferPool.release(encoded);
        }

        ByteBuffer reacquired = ByteBufferPool.acquire(capacity);
        try {
            assertSame("encode() must draw from ByteBufferPool", encoded, reacquired);
        } finally {
            ByteBufferPool.release(reacquired);
        }
    }

    // ── round-trip ──

    @Test
    public void testRoundTripIPv4() throws UnknownHostException {
        InetSocketAddress original = new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), 53);
        byte[] data = {0x01, 0x02, 0x03, 0x04, 0x05};
        ByteBuffer payload = ByteBuffer.wrap(data);

        ByteBuffer encoded = SOCKSUDPHeader.encode(original, payload);
        try {
            Recorded r = parse(encoded);

            assertTrue(r.called);
            assertEquals(original.getAddress(), r.address);
            assertEquals(original.getPort(), r.port);
            assertEquals(0, r.frag);
            assertArrayEquals(data, r.payload);
        } finally {
            ByteBufferPool.release(encoded);
        }
    }
}
