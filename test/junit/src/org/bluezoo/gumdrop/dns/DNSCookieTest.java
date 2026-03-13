/*
 * DNSCookieTest.java
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

package org.bluezoo.gumdrop.dns;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DNSCookie}.
 * RFC 7873: DNS Cookies.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSCookieTest {

    @Test
    public void testClientCookieLength() {
        DNSCookie cookie = new DNSCookie();
        byte[] cc = cookie.getClientCookie();
        assertEquals(DNSCookie.CLIENT_COOKIE_LENGTH, cc.length);
    }

    @Test
    public void testClientCookieIsRandom() {
        DNSCookie c1 = new DNSCookie();
        DNSCookie c2 = new DNSCookie();
        // Extremely unlikely to be equal
        assertFalse(java.util.Arrays.equals(
                c1.getClientCookie(), c2.getClientCookie()));
    }

    @Test
    public void testRegenerateClientCookie() {
        DNSCookie cookie = new DNSCookie();
        byte[] first = cookie.getClientCookie();
        cookie.regenerateClientCookie();
        byte[] second = cookie.getClientCookie();
        assertFalse(java.util.Arrays.equals(first, second));
    }

    @Test
    public void testBuildCookieOptionDataWithoutServerCookie() {
        DNSCookie cookie = new DNSCookie();
        byte[] data = cookie.buildCookieOptionData("1.2.3.4");
        assertEquals(DNSCookie.CLIENT_COOKIE_LENGTH, data.length);
    }

    @Test
    public void testBuildCookieOption() {
        DNSCookie cookie = new DNSCookie();
        byte[] opt = cookie.buildCookieOption("1.2.3.4");
        // 2 (option code) + 2 (option length) + 8 (client cookie) = 12
        assertEquals(12, opt.length);
        ByteBuffer buf = ByteBuffer.wrap(opt);
        assertEquals(DNSCookie.EDNS_OPTION_COOKIE, buf.getShort() & 0xFFFF);
        assertEquals(8, buf.getShort() & 0xFFFF);
    }

    @Test
    public void testServerCookieRoundTrip() {
        byte[] secret = new byte[16];
        java.util.Arrays.fill(secret, (byte) 0x42);
        DNSCookie cookie = new DNSCookie(secret);

        byte[] clientAddr = { 10, 0, 0, 1 };
        byte[] clientCookie = cookie.getClientCookie();

        byte[] serverCookie = cookie.generateServerCookie(
                clientAddr, clientCookie);
        assertEquals(DNSCookie.MIN_SERVER_COOKIE_LENGTH,
                serverCookie.length);

        assertTrue(cookie.validateServerCookie(
                clientAddr, clientCookie, serverCookie));
    }

    @Test
    public void testServerCookieInvalidation() {
        byte[] secret = new byte[16];
        java.util.Arrays.fill(secret, (byte) 0x42);
        DNSCookie cookie = new DNSCookie(secret);

        byte[] clientAddr = { 10, 0, 0, 1 };
        byte[] clientCookie = cookie.getClientCookie();
        byte[] wrongCookie = new byte[DNSCookie.MIN_SERVER_COOKIE_LENGTH];

        assertFalse(cookie.validateServerCookie(
                clientAddr, clientCookie, wrongCookie));
    }

    @Test
    public void testProcessResponseCookie() {
        DNSCookie cookie = new DNSCookie();

        // Simulate a response with client+server cookie
        byte[] optionData = new byte[16]; // 8 client + 8 server
        System.arraycopy(cookie.getClientCookie(), 0, optionData, 0, 8);
        byte[] fakeServer = { 1, 2, 3, 4, 5, 6, 7, 8 };
        System.arraycopy(fakeServer, 0, optionData, 8, 8);

        cookie.processResponseCookie("1.2.3.4", optionData);

        // Subsequent option should include the server cookie
        byte[] data = cookie.buildCookieOptionData("1.2.3.4");
        assertEquals(16, data.length); // 8 client + 8 server
    }

    @Test
    public void testFindEdnsOption() {
        // Build EDNS0 option: code=10, length=8, data=8 bytes
        ByteBuffer buf = ByteBuffer.allocate(4 + 8);
        buf.putShort((short) DNSCookie.EDNS_OPTION_COOKIE);
        buf.putShort((short) 8);
        buf.put(new byte[]{ 1, 2, 3, 4, 5, 6, 7, 8 });
        byte[] rdata = buf.array();

        byte[] found = DNSCookie.findEdnsOption(rdata,
                DNSCookie.EDNS_OPTION_COOKIE);
        assertNotNull(found);
        assertEquals(8, found.length);
        assertEquals(1, found[0]);
    }

    @Test
    public void testFindEdnsOptionNotPresent() {
        byte[] rdata = new byte[0]; // empty RDATA
        assertNull(DNSCookie.findEdnsOption(rdata,
                DNSCookie.EDNS_OPTION_COOKIE));
    }

    @Test
    public void testFindEdnsOptionSkipsOthers() {
        // Two options: code=1 (LLQ) followed by code=10 (cookie)
        ByteBuffer buf = ByteBuffer.allocate(4 + 2 + 4 + 8);
        buf.putShort((short) 1);  // LLQ
        buf.putShort((short) 2);  // length=2
        buf.put(new byte[]{ 0, 0 });
        buf.putShort((short) DNSCookie.EDNS_OPTION_COOKIE);
        buf.putShort((short) 8);
        buf.put(new byte[]{ 9, 8, 7, 6, 5, 4, 3, 2 });
        byte[] rdata = buf.array();

        byte[] found = DNSCookie.findEdnsOption(rdata,
                DNSCookie.EDNS_OPTION_COOKIE);
        assertNotNull(found);
        assertEquals(9, found[0]);
    }
}
