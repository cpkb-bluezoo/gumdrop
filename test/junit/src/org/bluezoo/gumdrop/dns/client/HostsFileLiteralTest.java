/*
 * HostsFileLiteralTest.java
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

package org.bluezoo.gumdrop.dns.client;

import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.*;

/**
 * Tests for the literal address parsers of {@link HostsFile}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HostsFileLiteralTest {

    private static String v6(String s) {
        InetAddress a = HostsFile.parseLiteralIPv6(s);
        if (a == null) {
            return null;
        }
        return a.getHostAddress();
    }

    @Test
    public void testIPv4Valid() {
        InetAddress a = HostsFile.parseLiteralIPv4("192.0.2.7");
        assertNotNull(a);
        assertEquals("192.0.2.7", a.getHostAddress());
    }

    @Test
    public void testIPv4Invalid() {
        assertNull(HostsFile.parseLiteralIPv4("1.2.3"));
        assertNull(HostsFile.parseLiteralIPv4("1.2.3.4.5"));
        assertNull(HostsFile.parseLiteralIPv4("256.1.1.1"));
        assertNull(HostsFile.parseLiteralIPv4("-1.1.1.1"));
        assertNull(HostsFile.parseLiteralIPv4("a.b.c.d"));
        assertNull(HostsFile.parseLiteralIPv4(""));
    }

    @Test
    public void testIPv6Basic() {
        assertEquals("0:0:0:0:0:0:0:1", v6("::1"));
        assertEquals("0:0:0:0:0:0:0:0", v6("::"));
        assertEquals("2001:db8:0:0:0:0:0:1", v6("2001:db8::1"));
        assertEquals("1:2:3:4:5:6:7:8", v6(" 1:2:3:4:5:6:7:8 "));
    }

    @Test
    public void testIPv6Invalid() {
        assertNull(v6(null));
        assertNull(v6(""));
        assertNull(v6("1.2.3.4"));
        assertNull(v6("1:2:3"));
        assertNull(v6("1:2:3:4:5:6:7:8:9"));
        assertNull(v6("1::2::3"));
        assertNull(v6("g::1"));
        assertNull(v6("12345::1"));
        assertNull(v6("-1::1"));
        assertNull(v6("1:2:3:4:5:6:7::8:9"));
    }

    @Test
    public void testIPv6TrailingCompression() {
        assertEquals("2001:db8:0:0:0:0:0:0", v6("2001:db8::"));
        assertEquals("1:0:0:0:0:0:0:0", v6("1::"));
    }

    @Test
    public void testIPv6LeadingCompressionWithGroup() {
        assertEquals("0:0:0:0:0:0:0:2", v6("::2"));
        assertEquals("0:0:0:0:0:0:ab:cd", v6("::ab:cd"));
    }
}
