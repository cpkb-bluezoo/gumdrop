/*
 * ConnectIpAddressTest.java
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
 * Tests for {@link ConnectIpAddress} -- issue #394's RFC 9484 section
 * 4.7.1/4.7.2 {@code ADDRESS_ASSIGN}/{@code ADDRESS_REQUEST} capsule
 * value codec.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectIpAddressTest {

    @Test
    public void testRoundTripSingleIpv4Entry() throws Exception {
        InetAddress address = InetAddress.getByName("192.0.2.5");
        List<ConnectIpAddress> entries = Arrays.asList(new ConnectIpAddress(7, address, 32));

        ByteBuffer encoded = ConnectIpAddress.encodeList(entries);
        List<ConnectIpAddress> decoded = ConnectIpAddress.decodeList(encoded);

        assertEquals(1, decoded.size());
        assertEquals(7, decoded.get(0).getRequestId());
        assertEquals(address, decoded.get(0).getAddress());
        assertEquals(32, decoded.get(0).getPrefixLength());
    }

    @Test
    public void testRoundTripSingleIpv6Entry() throws Exception {
        InetAddress address = InetAddress.getByName("2001:db8::1");
        List<ConnectIpAddress> entries = Arrays.asList(new ConnectIpAddress(0, address, 64));

        ByteBuffer encoded = ConnectIpAddress.encodeList(entries);
        List<ConnectIpAddress> decoded = ConnectIpAddress.decodeList(encoded);

        assertEquals(1, decoded.size());
        assertEquals(0, decoded.get(0).getRequestId());
        assertEquals(address, decoded.get(0).getAddress());
        assertEquals(64, decoded.get(0).getPrefixLength());
    }

    @Test
    public void testRoundTripMultipleMixedEntries() throws Exception {
        List<ConnectIpAddress> entries = new ArrayList<ConnectIpAddress>();
        entries.add(new ConnectIpAddress(1, InetAddress.getByName("10.0.0.1"), 24));
        entries.add(new ConnectIpAddress(2, InetAddress.getByName("fe80::1"), 10));
        entries.add(new ConnectIpAddress(3, InetAddress.getByName("203.0.113.9"), 32));

        ByteBuffer encoded = ConnectIpAddress.encodeList(entries);
        List<ConnectIpAddress> decoded = ConnectIpAddress.decodeList(encoded);

        assertEquals(3, decoded.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(entries.get(i).getRequestId(), decoded.get(i).getRequestId());
            assertEquals(entries.get(i).getAddress(), decoded.get(i).getAddress());
            assertEquals(entries.get(i).getPrefixLength(), decoded.get(i).getPrefixLength());
        }
    }

    @Test
    public void testEncodeEmptyListProducesEmptyBuffer() {
        ByteBuffer encoded = ConnectIpAddress.encodeList(new ArrayList<ConnectIpAddress>());
        assertEquals(0, encoded.remaining());
    }

    @Test
    public void testDecodeEmptyBufferProducesEmptyList() {
        List<ConnectIpAddress> decoded = ConnectIpAddress.decodeList(ByteBuffer.allocate(0));
        assertTrue(decoded.isEmpty());
    }

    @Test
    public void testDecodeTruncatedRecordIsNull() {
        // A well-formed request ID + IP version byte, but the address
        // and prefix-length bytes are missing entirely.
        ByteBuffer truncated = ByteBuffer.wrap(new byte[] { 0x05, 0x04 });
        assertNull(ConnectIpAddress.decodeList(truncated));
    }

    @Test
    public void testDecodeInvalidIpVersionIsNull() {
        ByteBuffer bad = ByteBuffer.wrap(new byte[] { 0x05, 0x07 /* not 4 or 6 */ });
        assertNull(ConnectIpAddress.decodeList(bad));
    }

    @Test
    public void testConstructorRejectsOversizedIpv4PrefixLength() throws Exception {
        try {
            new ConnectIpAddress(1, InetAddress.getByName("192.0.2.5"), 33);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testConstructorRejectsOversizedIpv6PrefixLength() throws Exception {
        try {
            new ConnectIpAddress(1, InetAddress.getByName("2001:db8::1"), 129);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
