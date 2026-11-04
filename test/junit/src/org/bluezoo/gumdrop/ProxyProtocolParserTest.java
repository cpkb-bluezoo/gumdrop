/*
 * ProxyProtocolParserTest.java
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

package org.bluezoo.gumdrop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ProxyProtocolParser}: PROXY protocol v1 and v2
 * headers, TLVs, malformed input and arbitrary chunking.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ProxyProtocolParserTest {

    private static final byte[] SIG = {
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    };

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < parts.length; i++) {
            out.write(parts[i], 0, parts[i].length);
        }
        return out.toByteArray();
    }

    private static byte[] v2(int verCmd, int famProto, byte[] body) {
        return concat(SIG, new byte[] {
            (byte) verCmd, (byte) famProto,
            (byte) (body.length >> 8), (byte) body.length }, body);
    }

    private static byte[] ip4Body(String src, String dst, int sp, int dp)
            throws IOException {
        return concat(InetAddress.getByName(src).getAddress(),
                InetAddress.getByName(dst).getAddress(),
                new byte[] { (byte) (sp >> 8), (byte) sp,
                             (byte) (dp >> 8), (byte) dp });
    }

    private static byte[] tlv(int type, byte[] value) {
        return concat(new byte[] { (byte) type,
                (byte) (value.length >> 8), (byte) value.length }, value);
    }

    /** Feeds data in chunks of the given size; returns the leftover bytes. */
    private static byte[] feed(ProxyProtocolParser p, byte[] data, int chunk)
            throws IOException {
        int off = 0;
        while (off < data.length) {
            int n = Math.min(chunk, data.length - off);
            ByteBuffer buf = ByteBuffer.wrap(data, off, n);
            boolean done = p.receive(buf);
            off += n - buf.remaining();
            if (done) {
                byte[] rest = new byte[data.length - off];
                System.arraycopy(data, off, rest, 0, rest.length);
                return rest;
            }
            assertFalse("underflow must consume all offered bytes",
                    buf.hasRemaining());
        }
        return null;
    }

    @Test
    public void v1Tcp4() throws Exception {
        for (int chunk = 1; chunk <= 8; chunk++) {
            ProxyProtocolParser p = new ProxyProtocolParser();
            byte[] rest = feed(p, concat(
                    ascii("PROXY TCP4 192.0.2.7 198.51.100.9 51000 25\r\n"),
                    ascii("EHLO")), chunk);
            assertNotNull(rest);
            assertEquals("EHLO", new String(rest, StandardCharsets.US_ASCII));
            assertEquals(new InetSocketAddress(
                    InetAddress.getByName("192.0.2.7"), 51000), p.getSource());
            assertEquals(new InetSocketAddress(
                    InetAddress.getByName("198.51.100.9"), 25), p.getDestination());
            assertNull(p.getTlvs());
        }
    }

    @Test
    public void v1Tcp6() throws Exception {
        ProxyProtocolParser p = new ProxyProtocolParser();
        byte[] rest = feed(p, ascii("PROXY TCP6 2001:db8::1 2001:db8::2 1000 465\r\n"), 3);
        assertEquals(0, rest.length);
        assertEquals(1000, p.getSource().getPort());
        assertEquals(465, p.getDestination().getPort());
        assertEquals(InetAddress.getByName("2001:db8::2"),
                p.getDestination().getAddress());
    }

    @Test
    public void v1UnknownKeepsSocketAddresses() throws Exception {
        ProxyProtocolParser p = new ProxyProtocolParser();
        byte[] rest = feed(p, concat(ascii("PROXY UNKNOWN ignored stuff\r\n"),
                ascii("x")), 4);
        assertEquals(1, rest.length);
        assertNull(p.getSource());
        assertNull(p.getDestination());
    }

    @Test(expected = IOException.class)
    public void v1TooLong() throws Exception {
        StringBuilder sb = new StringBuilder("PROXY UNKNOWN ");
        while (sb.length() < 120) {
            sb.append('x');
        }
        feed(new ProxyProtocolParser(), ascii(sb.toString() + "\r\n"), 16);
    }

    @Test
    public void v1MaxLengthAccepted() throws Exception {
        StringBuilder sb = new StringBuilder("PROXY UNKNOWN ");
        while (sb.length() < 105) {
            sb.append('x');
        }
        sb.append("\r\n");
        assertEquals(107, sb.length());
        ProxyProtocolParser p = new ProxyProtocolParser();
        assertEquals(0, feed(p, ascii(sb.toString()), 107).length);
    }

    @Test(expected = IOException.class)
    public void v1BadAddress() throws Exception {
        feed(new ProxyProtocolParser(),
                ascii("PROXY TCP4 not-an-ip 198.51.100.9 1 2\r\n"), 64);
    }

    @Test(expected = IOException.class)
    public void v1BarePortOutOfRange() throws Exception {
        feed(new ProxyProtocolParser(),
                ascii("PROXY TCP4 192.0.2.1 192.0.2.2 70000 25\r\n"), 64);
    }

    @Test(expected = IOException.class)
    public void v1MissingCr() throws Exception {
        feed(new ProxyProtocolParser(),
                ascii("PROXY TCP4 192.0.2.1 192.0.2.2 1 2\n"), 64);
    }

    @Test(expected = IOException.class)
    public void notProxyAtAll() throws Exception {
        feed(new ProxyProtocolParser(), ascii("EHLO example.org\r\n"), 64);
    }

    @Test(expected = IOException.class)
    public void notProxyDetectedAtFirstWrongByte() throws Exception {
        feed(new ProxyProtocolParser(), ascii("PROXZ"), 1);
    }

    @Test
    public void v2Ipv4() throws Exception {
        for (int chunk = 1; chunk <= 9; chunk++) {
            ProxyProtocolParser p = new ProxyProtocolParser();
            byte[] rest = feed(p, concat(
                    v2(0x21, 0x11, ip4Body("192.0.2.7", "198.51.100.9", 51000, 25)),
                    ascii("HELO")), chunk);
            assertEquals("HELO", new String(rest, StandardCharsets.US_ASCII));
            assertEquals(51000, p.getSource().getPort());
            assertEquals(InetAddress.getByName("198.51.100.9"),
                    p.getDestination().getAddress());
        }
    }

    @Test
    public void v2Ipv6() throws Exception {
        byte[] body = concat(InetAddress.getByName("2001:db8::1").getAddress(),
                InetAddress.getByName("2001:db8::2").getAddress(),
                new byte[] { 0x03, (byte) 0xE8, 0x01, (byte) 0xD1 });
        ProxyProtocolParser p = new ProxyProtocolParser();
        assertEquals(0, feed(p, v2(0x21, 0x21, body), 5).length);
        assertEquals(1000, p.getSource().getPort());
        assertEquals(465, p.getDestination().getPort());
    }

    @Test
    public void v2LocalKeepsSocketAddresses() throws Exception {
        ProxyProtocolParser p = new ProxyProtocolParser();
        byte[] rest = feed(p, concat(v2(0x20, 0x00, new byte[0]), ascii("Q")), 2);
        assertEquals(1, rest.length);
        assertNull(p.getSource());
        assertNull(p.getDestination());
    }

    @Test
    public void v2LocalSkipsBodyEvenWithFamily() throws Exception {
        ProxyProtocolParser p = new ProxyProtocolParser();
        byte[] rest = feed(p, concat(
                v2(0x20, 0x11, ip4Body("192.0.2.7", "198.51.100.9", 1, 2)),
                ascii("Q")), 7);
        assertEquals(1, rest.length);
        assertNull(p.getSource());
    }

    @Test
    public void v2AfUnspecWithPayloadIsConsumed() throws Exception {
        ProxyProtocolParser p = new ProxyProtocolParser();
        byte[] rest = feed(p, concat(v2(0x21, 0x00, tlv(0x05, new byte[] { 1, 2, 3 })),
                ascii("Q")), 3);
        assertEquals(1, rest.length);
        assertNull(p.getSource());
    }

    @Test
    public void v2UnixKeepsSocketAddresses() throws Exception {
        ProxyProtocolParser p = new ProxyProtocolParser();
        assertEquals(0, feed(p, v2(0x21, 0x31, new byte[216]), 50).length);
        assertNull(p.getSource());
        assertNull(p.getDestination());
    }

    @Test
    public void v2Tlvs() throws Exception {
        byte[] aws = concat(new byte[] { 0x01 }, ascii("vpce-0123456789abcdef0"));
        byte[] body = concat(
                ip4Body("192.0.2.7", "198.51.100.9", 1, 2),
                tlv(0x01, ascii("smtp")),
                tlv(0x02, ascii("mx.example.org")),
                tlv(0x05, new byte[] { 9, 8, 7 }),
                tlv(0x20, new byte[] { 1, 0, 0, 0, 0 }),
                tlv(0xEA, aws));
        ProxyProtocolParser p = new ProxyProtocolParser();
        assertEquals(0, feed(p, v2(0x21, 0x11, body), 1).length);
        ProxyTlvs t = p.getTlvs();
        assertNotNull(t);
        assertEquals(5, t.size());
        assertEquals("smtp", t.getAlpn());
        assertEquals("mx.example.org", t.getAuthority());
        assertArrayEquals(new byte[] { 9, 8, 7 }, t.getUniqueId());
        assertArrayEquals(new byte[] { 1, 0, 0, 0, 0 }, t.getSsl());
        assertEquals("vpce-0123456789abcdef0", t.getAwsVpcEndpointId());
    }

    @Test
    public void v2TlvsOnLocal() throws Exception {
        ProxyProtocolParser p = new ProxyProtocolParser();
        feed(p, v2(0x20, 0x00, tlv(0x05, new byte[] { 1 })), 4);
        assertNotNull(p.getTlvs());
        assertArrayEquals(new byte[] { 1 }, p.getTlvs().getUniqueId());
    }

    @Test(expected = IOException.class)
    public void v2TruncatedTlvHeader() throws Exception {
        byte[] body = concat(ip4Body("192.0.2.7", "198.51.100.9", 1, 2),
                new byte[] { 0x05, 0x00 });
        feed(new ProxyProtocolParser(), v2(0x21, 0x11, body), 64);
    }

    @Test(expected = IOException.class)
    public void v2TruncatedTlvValue() throws Exception {
        byte[] body = concat(ip4Body("192.0.2.7", "198.51.100.9", 1, 2),
                new byte[] { 0x05, 0x00, 0x05, 1, 2 });
        feed(new ProxyProtocolParser(), v2(0x21, 0x11, body), 64);
    }

    @Test(expected = IOException.class)
    public void v2LengthShorterThanAddresses() throws Exception {
        feed(new ProxyProtocolParser(),
                v2(0x21, 0x11, new byte[] { 1, 2, 3 }), 64);
    }

    @Test(expected = IOException.class)
    public void v2BadVersion() throws Exception {
        feed(new ProxyProtocolParser(), v2(0x31, 0x11, new byte[12]), 64);
    }

    @Test(expected = IOException.class)
    public void v2BadCommand() throws Exception {
        feed(new ProxyProtocolParser(), v2(0x22, 0x11, new byte[12]), 64);
    }

    @Test
    public void stackedHeaderIsApplicationData() throws Exception {
        byte[] first = ascii("PROXY TCP4 192.0.2.7 198.51.100.9 1 25\r\n");
        byte[] second = ascii("PROXY TCP4 10.0.0.1 10.0.0.2 3 4\r\n");
        ProxyProtocolParser p = new ProxyProtocolParser();
        byte[] rest = feed(p, concat(first, second), 64);
        assertArrayEquals(second, rest);
        assertEquals(InetAddress.getByName("192.0.2.7"),
                p.getSource().getAddress());
    }

    @Test
    public void completionLeavesFollowingBytesUnconsumed() throws Exception {
        byte[] hdr = v2(0x21, 0x11, ip4Body("192.0.2.7", "198.51.100.9", 1, 2));
        byte[] tls = { 0x16, 0x03, 0x01, 0x00, 0x05 };
        ByteBuffer buf = ByteBuffer.wrap(concat(hdr, tls));
        ProxyProtocolParser p = new ProxyProtocolParser();
        assertTrue(p.receive(buf));
        assertEquals(tls.length, buf.remaining());
        assertEquals(0x16, buf.get() & 0xFF);
    }

}
