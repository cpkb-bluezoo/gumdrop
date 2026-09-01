/*
 * ConnectIpRouteTest.java
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

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link ConnectIpRoute} -- issue #394's RFC 9484 section
 * 4.7.3 {@code ROUTE_ADVERTISEMENT} capsule value codec.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectIpRouteTest {

    @Test
    public void testRoundTripSingleIpv4Range() throws Exception {
        InetAddress start = InetAddress.getByName("192.0.2.0");
        InetAddress end = InetAddress.getByName("192.0.2.255");
        List<ConnectIpRoute> entries = Arrays.asList(new ConnectIpRoute(start, end, 17));

        ByteBuffer encoded = ConnectIpRoute.encodeList(entries);
        List<ConnectIpRoute> decoded = ConnectIpRoute.decodeList(encoded);

        assertEquals(1, decoded.size());
        assertEquals(start, decoded.get(0).getStartAddress());
        assertEquals(end, decoded.get(0).getEndAddress());
        assertEquals(17, decoded.get(0).getIpProtocol());
    }

    @Test
    public void testRoundTripIpv6RangeWithAllProtocols() throws Exception {
        InetAddress start = InetAddress.getByName("2001:db8::");
        InetAddress end = InetAddress.getByName("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff");
        List<ConnectIpRoute> entries = Arrays.asList(
                new ConnectIpRoute(start, end, ConnectIpRoute.IP_PROTOCOL_ALL));

        ByteBuffer encoded = ConnectIpRoute.encodeList(entries);
        List<ConnectIpRoute> decoded = ConnectIpRoute.decodeList(encoded);

        assertEquals(1, decoded.size());
        assertEquals(start, decoded.get(0).getStartAddress());
        assertEquals(end, decoded.get(0).getEndAddress());
        assertEquals(0, decoded.get(0).getIpProtocol());
    }

    @Test
    public void testRoundTripMultipleRanges() throws Exception {
        List<ConnectIpRoute> entries = new ArrayList<ConnectIpRoute>();
        entries.add(new ConnectIpRoute(
                InetAddress.getByName("10.0.0.0"), InetAddress.getByName("10.0.0.255"), 6));
        entries.add(new ConnectIpRoute(
                InetAddress.getByName("2001:db8::"), InetAddress.getByName("2001:db8::ffff"), 17));

        ByteBuffer encoded = ConnectIpRoute.encodeList(entries);
        List<ConnectIpRoute> decoded = ConnectIpRoute.decodeList(encoded);

        assertEquals(2, decoded.size());
        assertEquals(entries.get(0).getStartAddress(), decoded.get(0).getStartAddress());
        assertEquals(entries.get(1).getStartAddress(), decoded.get(1).getStartAddress());
    }

    @Test
    public void testEncodeEmptyListProducesEmptyBuffer() {
        ByteBuffer encoded = ConnectIpRoute.encodeList(new ArrayList<ConnectIpRoute>());
        assertEquals(0, encoded.remaining());
    }

    @Test
    public void testDecodeEmptyBufferProducesEmptyList() {
        List<ConnectIpRoute> decoded = ConnectIpRoute.decodeList(ByteBuffer.allocate(0));
        assertTrue(decoded.isEmpty());
    }

    @Test
    public void testDecodeTruncatedRecordIsNull() {
        ByteBuffer truncated = ByteBuffer.wrap(new byte[] { 0x04, 0x01, 0x02, 0x03 });
        assertNull(ConnectIpRoute.decodeList(truncated));
    }

    @Test
    public void testDecodeInvalidIpVersionIsNull() {
        ByteBuffer bad = ByteBuffer.wrap(new byte[] { 0x09 });
        assertNull(ConnectIpRoute.decodeList(bad));
    }

    @Test
    public void testConstructorRejectsMismatchedAddressFamilies() throws Exception {
        try {
            new ConnectIpRoute(InetAddress.getByName("192.0.2.0"),
                    InetAddress.getByName("2001:db8::"), 0);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testConstructorRejectsOutOfRangeProtocol() throws Exception {
        try {
            new ConnectIpRoute(InetAddress.getByName("192.0.2.0"),
                    InetAddress.getByName("192.0.2.255"), 256);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
