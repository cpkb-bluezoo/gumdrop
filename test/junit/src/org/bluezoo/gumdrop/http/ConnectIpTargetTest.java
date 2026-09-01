/*
 * ConnectIpTargetTest.java
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * Tests for {@link ConnectIpTarget} -- issue #394's RFC 9484 section 3
 * URI Template codec for {@code /.well-known/masque/ip/{target}/{ipproto}/}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectIpTargetTest {

    @Test
    public void testParseWildcardBoth() {
        ConnectIpTarget target = ConnectIpTarget.parse("/.well-known/masque/ip/*/*/");
        assertTrue(target.isTargetUnspecified());
        assertTrue(target.isIpProtoUnspecified());
    }

    @Test
    public void testParseSpecificIpv4PrefixAndProto() {
        // RFC 9484 section 4.6: "/" in an IP prefix is percent-encoded as "%2F".
        ConnectIpTarget target = ConnectIpTarget.parse("/.well-known/masque/ip/192.0.2.5%2F32/17/");
        assertEquals("192.0.2.5/32", target.getTarget());
        assertFalse(target.isTargetUnspecified());
        assertEquals("17", target.getIpProto());
        assertFalse(target.isIpProtoUnspecified());
    }

    @Test
    public void testParsePercentEncodedIpv6PrefixLiteral() {
        // RFC 9484 section 4.6: an IPv6 literal's colons are percent-encoded as "%3A".
        ConnectIpTarget target = ConnectIpTarget.parse(
                "/.well-known/masque/ip/2001%3Adb8%3A%3A%2F64/*/");
        assertEquals("2001:db8::/64", target.getTarget());
        assertTrue(target.isIpProtoUnspecified());
    }

    @Test
    public void testParseHostname() {
        ConnectIpTarget target = ConnectIpTarget.parse("/.well-known/masque/ip/example.com/*/");
        assertEquals("example.com", target.getTarget());
    }

    @Test
    public void testRoundTripEncodeThenParseWildcard() {
        String path = ConnectIpTarget.encode(ConnectIpTarget.WILDCARD, ConnectIpTarget.WILDCARD);
        assertEquals("/.well-known/masque/ip/*/*/", path);
        ConnectIpTarget target = ConnectIpTarget.parse(path);
        assertTrue(target.isTargetUnspecified());
        assertTrue(target.isIpProtoUnspecified());
    }

    @Test
    public void testRoundTripEncodeThenParseIpv6Prefix() {
        String path = ConnectIpTarget.encode("2001:db8::/64", "6");
        ConnectIpTarget target = ConnectIpTarget.parse(path);
        assertEquals("2001:db8::/64", target.getTarget());
        assertEquals("6", target.getIpProto());
    }

    @Test
    public void testParseWrongPrefixIsNull() {
        assertNull(ConnectIpTarget.parse("/masque/ip/*/*/"));
    }

    @Test
    public void testParseMissingTrailingSlashIsNull() {
        assertNull(ConnectIpTarget.parse("/.well-known/masque/ip/*/*"));
    }

    @Test
    public void testParseMissingIpProtoSegmentIsNull() {
        assertNull(ConnectIpTarget.parse("/.well-known/masque/ip/*/"));
    }

    @Test
    public void testParseEmptyTargetIsNull() {
        assertNull(ConnectIpTarget.parse("/.well-known/masque/ip//17/"));
    }

    @Test
    public void testParseNonNumericIpProtoIsNull() {
        assertNull(ConnectIpTarget.parse("/.well-known/masque/ip/*/udp/"));
    }

    @Test
    public void testParseIpProtoOutOfRangeIsNull() {
        assertNull(ConnectIpTarget.parse("/.well-known/masque/ip/*/256/"));
    }

    @Test
    public void testParseIpProtoZeroIsValid() {
        // 0 is a real (if unusual) IP protocol number, and IP_PROTOCOL_ALL
        // in ConnectIpRoute -- unlike CONNECT-UDP's port, 0 must parse.
        ConnectIpTarget target = ConnectIpTarget.parse("/.well-known/masque/ip/*/0/");
        assertEquals("0", target.getIpProto());
    }

    @Test
    public void testParseIpProtoMaxIsValid() {
        ConnectIpTarget target = ConnectIpTarget.parse("/.well-known/masque/ip/*/255/");
        assertEquals("255", target.getIpProto());
    }

    @Test
    public void testParseNullPathIsNull() {
        assertNull(ConnectIpTarget.parse(null));
    }

    @Test
    public void testEncodeRejectsOutOfRangeIpProto() {
        try {
            ConnectIpTarget.encode(ConnectIpTarget.WILDCARD, "256");
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testEncodeRejectsNonNumericIpProto() {
        try {
            ConnectIpTarget.encode(ConnectIpTarget.WILDCARD, "udp");
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
