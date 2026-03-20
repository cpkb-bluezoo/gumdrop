package org.bluezoo.gumdrop.socks;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

import org.junit.Test;

import static org.junit.Assert.*;
import static org.bluezoo.gumdrop.socks.SOCKSConstants.*;

/**
 * Unit tests for {@link SOCKSUDPHeader}.
 */
public class SOCKSUDPHeaderTest {

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

        SOCKSUDPHeader.Parsed p = SOCKSUDPHeader.parse(buf);
        assertNotNull(p);
        assertEquals(0, p.frag);
        assertEquals(InetAddress.getByAddress(new byte[]{10, 0, 0, 1}),
                     p.address);
        assertNull(p.hostname);
        assertEquals(8080, p.port);
        assertEquals(10, p.dataOffset);

        InetSocketAddress sa = p.toSocketAddress();
        assertNotNull(sa);
        assertEquals(8080, sa.getPort());
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

        SOCKSUDPHeader.Parsed p = SOCKSUDPHeader.parse(buf);
        assertNotNull(p);
        assertEquals(InetAddress.getByAddress(ipv6), p.address);
        assertNull(p.hostname);
        assertEquals(443, p.port);
        assertEquals(22, p.dataOffset);
        assertNotNull(p.toSocketAddress());
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

        SOCKSUDPHeader.Parsed p = SOCKSUDPHeader.parse(buf);
        assertNotNull(p);
        assertNull(p.address);
        assertEquals("example.com", p.hostname);
        assertEquals(53, p.port);
        assertNull(p.toSocketAddress());
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

        SOCKSUDPHeader.Parsed p = SOCKSUDPHeader.parse(buf);
        assertNotNull(p);
        assertEquals(3, p.frag);
    }

    @Test
    public void testParseTooShort() {
        ByteBuffer buf = ByteBuffer.allocate(3);
        buf.put(new byte[]{0, 0, 0});
        buf.flip();
        assertNull(SOCKSUDPHeader.parse(buf));
    }

    @Test
    public void testParseIPv4TooShort() {
        ByteBuffer buf = ByteBuffer.allocate(7);
        buf.putShort((short) 0);
        buf.put((byte) 0);
        buf.put(SOCKS5_ATYP_IPV4);
        buf.put(new byte[]{1, 2, 3}); // only 3 bytes, need 4 + 2
        buf.flip();
        assertNull(SOCKSUDPHeader.parse(buf));
    }

    @Test
    public void testParseIPv6TooShort() {
        ByteBuffer buf = ByteBuffer.allocate(10);
        buf.putShort((short) 0);
        buf.put((byte) 0);
        buf.put(SOCKS5_ATYP_IPV6);
        buf.put(new byte[]{1, 2, 3, 4, 5, 6}); // 6 bytes, need 16 + 2
        buf.flip();
        assertNull(SOCKSUDPHeader.parse(buf));
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
        assertNull(SOCKSUDPHeader.parse(buf));
    }

    @Test
    public void testParseUnknownATYP() {
        ByteBuffer buf = ByteBuffer.allocate(10);
        buf.putShort((short) 0);
        buf.put((byte) 0);
        buf.put((byte) 0x99); // unknown ATYP
        buf.put(new byte[]{1, 2, 3, 4, 5, 6});
        buf.flip();
        assertNull(SOCKSUDPHeader.parse(buf));
    }

    // ── encode() ──

    @Test
    public void testEncodeIPv4() throws UnknownHostException {
        InetSocketAddress source = new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34}),
                80);
        ByteBuffer payload = ByteBuffer.wrap(new byte[]{0x48, 0x49});

        ByteBuffer result = SOCKSUDPHeader.encode(source, payload);
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
        // header = 4 + 16 + 2 = 22, data = 1
        assertEquals(23, result.remaining());

        result.getShort(); // RSV
        result.get();      // FRAG
        assertEquals(SOCKS5_ATYP_IPV6, result.get());
    }

    @Test
    public void testEncodeEmptyPayload() throws UnknownHostException {
        InetSocketAddress source = new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{1, 2, 3, 4}), 9999);
        ByteBuffer payload = ByteBuffer.allocate(0);

        ByteBuffer result = SOCKSUDPHeader.encode(source, payload);
        assertEquals(10, result.remaining()); // header only, 0 data
    }

    // ── round-trip ──

    @Test
    public void testRoundTripIPv4() throws UnknownHostException {
        InetSocketAddress original = new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), 53);
        byte[] data = {0x01, 0x02, 0x03, 0x04, 0x05};
        ByteBuffer payload = ByteBuffer.wrap(data);

        ByteBuffer encoded = SOCKSUDPHeader.encode(original, payload);
        SOCKSUDPHeader.Parsed parsed = SOCKSUDPHeader.parse(encoded);

        assertNotNull(parsed);
        assertEquals(original.getAddress(), parsed.address);
        assertEquals(original.getPort(), parsed.port);
        assertEquals(0, parsed.frag);

        int remaining = encoded.limit() - parsed.dataOffset;
        assertEquals(data.length, remaining);
    }
}
