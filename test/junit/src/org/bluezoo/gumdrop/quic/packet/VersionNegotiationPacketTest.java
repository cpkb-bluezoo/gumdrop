/*
 * VersionNegotiationPacketTest.java
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


package org.bluezoo.gumdrop.quic.packet;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for the Version Negotiation packet (RFC 9000 section
 * 17.2.1) and for reading the version-independent long header fields
 * (RFC 8999 section 5.1) of a packet whose version is not understood.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class VersionNegotiationPacketTest {

    private static final byte[] DCID = { 1, 2, 3, 4, 5, 6, 7, 8 };
    private static final byte[] SCID = { 9, 8, 7 };

    @Test
    public void testRoundTripPreservesConnectionIdsAndVersions() {
        int[] versions = { 1, 0x1a2a3a4a };
        byte[] wire = VersionNegotiationPacket.build(DCID, SCID, versions, 0x2a);
        VersionNegotiationPacket parsed = VersionNegotiationPacket.parse(wire);
        assertArrayEquals(DCID, parsed.getDestinationConnectionId());
        assertArrayEquals(SCID, parsed.getSourceConnectionId());
        assertArrayEquals(versions, parsed.getSupportedVersions());
    }

    @Test
    public void testHeaderFormBitIsSetAndVersionFieldIsZero() {
        for (int unused = 0; unused < 256; unused += 51) {
            byte[] wire = VersionNegotiationPacket.build(DCID, SCID, new int[] { 1 }, unused);
            assertTrue("long header form bit", (wire[0] & 0x80) != 0);
            assertEquals(0, wire[1] | wire[2] | wire[3] | wire[4]);
        }
    }

    @Test
    public void testWireLayout() {
        byte[] wire = VersionNegotiationPacket.build(DCID, SCID, new int[] { 1 }, 0);
        assertEquals(1 + 4 + 1 + DCID.length + 1 + SCID.length + 4, wire.length);
        assertEquals(DCID.length, wire[5]);
        assertEquals(SCID.length, wire[6 + DCID.length]);
        assertEquals(1, wire[wire.length - 1]);
    }

    private static void assertRejected(byte[] wire) {
        try {
            VersionNegotiationPacket.parse(wire);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // malformed
        }
    }

    @Test
    public void testParseRejectsMalformedPackets() {
        byte[] good = VersionNegotiationPacket.build(DCID, SCID, new int[] { 1 }, 0);
        byte[] shortHeader = good.clone();
        shortHeader[0] = 0x40;
        assertRejected(shortHeader);
        byte[] nonZeroVersion = good.clone();
        nonZeroVersion[4] = 1;
        assertRejected(nonZeroVersion);
        assertRejected(java.util.Arrays.copyOf(good, good.length - 1));
        assertRejected(java.util.Arrays.copyOf(good, good.length - 4));
        assertRejected(java.util.Arrays.copyOf(good, 6));
        assertRejected(new byte[0]);
    }

    @Test
    public void testInvariantsReadConnectionIdsLongerThanTwentyBytes() {
        byte[] dcid = new byte[255];
        byte[] scid = new byte[200];
        java.util.Arrays.fill(dcid, (byte) 0x5a);
        java.util.Arrays.fill(scid, (byte) 0x6b);
        byte[] packet = new byte[1200];
        packet[0] = (byte) 0xc3;
        packet[1] = 0x1a;
        packet[2] = 0x2a;
        packet[3] = 0x3a;
        packet[4] = 0x4a;
        packet[5] = (byte) dcid.length;
        System.arraycopy(dcid, 0, packet, 6, dcid.length);
        packet[6 + dcid.length] = (byte) scid.length;
        System.arraycopy(scid, 0, packet, 7 + dcid.length, scid.length);

        LongHeaderInvariants inv = LongHeaderCodec.parseInvariants(packet);
        assertEquals(0x1a2a3a4a, inv.getVersion());
        assertArrayEquals(dcid, inv.getDestinationConnectionId());
        assertArrayEquals(scid, inv.getSourceConnectionId());
    }

    @Test
    public void testInvariantsRejectTruncatedPackets() {
        byte[] packet = { (byte) 0xc0, 0, 0, 0, 9, 8, 1, 2 };
        try {
            LongHeaderCodec.parseInvariants(packet);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // DCID length overruns the packet
        }
    }
}
