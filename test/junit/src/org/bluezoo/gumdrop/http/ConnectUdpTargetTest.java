/*
 * ConnectUdpTargetTest.java
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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests for {@link ConnectUdpTarget} -- issue #393's RFC 9298 section 3
 * URI Template codec for {@code /.well-known/masque/udp/{target_host}/{target_port}/}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectUdpTargetTest {

    @Test
    public void testParseHostname() {
        ConnectUdpTarget target = ConnectUdpTarget.parse("/.well-known/masque/udp/example.com/443/");
        assertEquals("example.com", target.getHost());
        assertEquals(443, target.getPort());
    }

    @Test
    public void testParseIPv4Literal() {
        ConnectUdpTarget target = ConnectUdpTarget.parse("/.well-known/masque/udp/192.0.2.6/53/");
        assertEquals("192.0.2.6", target.getHost());
        assertEquals(53, target.getPort());
    }

    /** RFC 3986 section 2.1: the IPv6 literal's colons must be percent-encoded in a path segment. */
    @Test
    public void testParsePercentEncodedIPv6Literal() {
        ConnectUdpTarget target = ConnectUdpTarget.parse("/.well-known/masque/udp/2001%3Adb8%3A%3A6/9000/");
        assertEquals("2001:db8::6", target.getHost());
        assertEquals(9000, target.getPort());
    }

    @Test
    public void testRoundTripEncodeThenParse() {
        String path = ConnectUdpTarget.encode("2001:db8::6", 9000);
        ConnectUdpTarget target = ConnectUdpTarget.parse(path);
        assertEquals("2001:db8::6", target.getHost());
        assertEquals(9000, target.getPort());
    }

    @Test
    public void testRoundTripHostnameNoEncodingNeeded() {
        String path = ConnectUdpTarget.encode("example.com", 443);
        assertEquals("/.well-known/masque/udp/example.com/443/", path);
    }

    @Test
    public void testParseWrongPrefixIsNull() {
        assertNull(ConnectUdpTarget.parse("/masque/udp/example.com/443/"));
    }

    @Test
    public void testParseMissingTrailingSlashIsNull() {
        assertNull(ConnectUdpTarget.parse("/.well-known/masque/udp/example.com/443"));
    }

    @Test
    public void testParseMissingPortSegmentIsNull() {
        assertNull(ConnectUdpTarget.parse("/.well-known/masque/udp/example.com/"));
    }

    @Test
    public void testParseEmptyHostIsNull() {
        assertNull(ConnectUdpTarget.parse("/.well-known/masque/udp//443/"));
    }

    @Test
    public void testParseNonNumericPortIsNull() {
        assertNull(ConnectUdpTarget.parse("/.well-known/masque/udp/example.com/https/"));
    }

    @Test
    public void testParsePortZeroIsNull() {
        assertNull(ConnectUdpTarget.parse("/.well-known/masque/udp/example.com/0/"));
    }

    @Test
    public void testParsePortTooLargeIsNull() {
        assertNull(ConnectUdpTarget.parse("/.well-known/masque/udp/example.com/65536/"));
    }

    @Test
    public void testParsePortMaxIsValid() {
        ConnectUdpTarget target = ConnectUdpTarget.parse("/.well-known/masque/udp/example.com/65535/");
        assertEquals(65535, target.getPort());
    }

    @Test
    public void testParseNullPathIsNull() {
        assertNull(ConnectUdpTarget.parse(null));
    }

    @Test
    public void testParseTruncatedPercentEscapeIsNull() {
        assertNull(ConnectUdpTarget.parse("/.well-known/masque/udp/example.com%2/443/"));
    }

    @Test
    public void testParseInvalidPercentDigitsIsNull() {
        assertNull(ConnectUdpTarget.parse("/.well-known/masque/udp/example.com%zz/443/"));
    }

    @Test
    public void testEncodeRejectsOutOfRangePort() {
        try {
            ConnectUdpTarget.encode("example.com", 0);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
