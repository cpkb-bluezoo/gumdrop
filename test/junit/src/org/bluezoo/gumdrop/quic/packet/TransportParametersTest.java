/*
 * TransportParametersTest.java
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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.junit.Test;

import org.bluezoo.util.ByteArrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Round-trips {@link TransportParameters} through {@link #encode} and
 * {@link #decode}, for both a client-shaped set (no
 * original_destination_connection_id) and a server-shaped set
 * (includes it).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TransportParametersTest {

    @Test
    public void testClientParametersRoundTrip() {
        TransportParameters params = new TransportParameters();
        params.setMaxIdleTimeout(30000);
        params.setInitialMaxData(1_000_000);
        params.setInitialMaxStreamDataBidiLocal(500_000);
        params.setInitialMaxStreamDataBidiRemote(500_000);
        params.setInitialMaxStreamDataUni(500_000);
        params.setInitialMaxStreamsBidi(100);
        params.setInitialMaxStreamsUni(100);
        byte[] scid = ByteArrays.toByteArray("0102030405060708");
        params.setInitialSourceConnectionId(scid);

        byte[] encoded = params.encode();
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(encoded));

        assertEquals(30000, decoded.getMaxIdleTimeout());
        assertEquals(TransportParameters.DEFAULT_MAX_UDP_PAYLOAD_SIZE, decoded.getMaxUdpPayloadSize());
        assertEquals(1_000_000, decoded.getInitialMaxData());
        assertEquals(500_000, decoded.getInitialMaxStreamDataBidiLocal());
        assertEquals(500_000, decoded.getInitialMaxStreamDataBidiRemote());
        assertEquals(500_000, decoded.getInitialMaxStreamDataUni());
        assertEquals(100, decoded.getInitialMaxStreamsBidi());
        assertEquals(100, decoded.getInitialMaxStreamsUni());
        assertArrayEquals(scid, decoded.getInitialSourceConnectionId());
        assertNull(decoded.getOriginalDestinationConnectionId());
        assertEquals(0, decoded.getMaxDatagramFrameSize());
    }

    @Test
    public void testServerParametersRoundTripIncludesOriginalDcid() {
        TransportParameters params = new TransportParameters();
        byte[] scid = ByteArrays.toByteArray("aabbccddeeff0011");
        byte[] odcid = ByteArrays.toByteArray("8394c8f03e515708");
        params.setInitialSourceConnectionId(scid);
        params.setOriginalDestinationConnectionId(odcid);
        params.setInitialMaxData(2_000_000);

        byte[] encoded = params.encode();
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(encoded));

        assertArrayEquals(scid, decoded.getInitialSourceConnectionId());
        assertArrayEquals(odcid, decoded.getOriginalDestinationConnectionId());
        assertEquals(2_000_000, decoded.getInitialMaxData());
    }

    @Test
    public void testServerParametersIncludeStatelessResetToken() {
        TransportParameters params = new TransportParameters();
        byte[] scid = ByteArrays.toByteArray("aabbccddeeff00112233445566778899");
        byte[] token = ByteArrays.toByteArray("0123456789abcdef0123456789abcdef");
        params.setInitialSourceConnectionId(scid);
        params.setStatelessResetToken(token);

        byte[] encoded = params.encode();
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(encoded));

        assertArrayEquals(token, decoded.getStatelessResetToken());
    }

    @Test
    public void testMaxAckDelayDefaultsToRfcValueWhenNeverSet() {
        TransportParameters params = new TransportParameters();
        byte[] encoded = params.encode();
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(encoded));

        assertEquals(TransportParameters.DEFAULT_MAX_ACK_DELAY, decoded.getMaxAckDelay());
    }

    @Test
    public void testMaxAckDelayRoundTripsWithNonDefaultValue() {
        TransportParameters params = new TransportParameters();
        params.setMaxAckDelay(63);

        byte[] encoded = params.encode();
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(encoded));

        assertEquals(63, decoded.getMaxAckDelay());
    }

    @Test
    public void testUnknownParameterIsIgnored() {
        // A well-formed but unrecognised parameter (id 0x27) followed by
        // a recognised one (initial_max_data) must not disrupt decoding.
        ByteBuffer buf = ByteBuffer.allocate(32);
        VarInt.encode(0x27L, buf);
        VarInt.encode(3L, buf);
        buf.put((byte) 1);
        buf.put((byte) 2);
        buf.put((byte) 3);
        VarInt.encode(TransportParameters.INITIAL_MAX_DATA, buf);
        VarInt.encode(VarInt.encodedLength(42L), buf);
        VarInt.encode(42L, buf);
        buf.flip();

        TransportParameters decoded = TransportParameters.decode(buf);
        assertEquals(42, decoded.getInitialMaxData());
        assertEquals(0, decoded.getMaxDatagramFrameSize());
    }

    @Test
    public void testMaxDatagramFrameSizeRoundTrips() {
        TransportParameters params = new TransportParameters();
        params.setMaxDatagramFrameSize(65527);

        byte[] encoded = params.encode();
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(encoded));

        assertEquals(65527, decoded.getMaxDatagramFrameSize());
    }

    @Test
    public void testMaxDatagramFrameSizeOmittedWhenZero() {
        TransportParameters params = new TransportParameters();
        params.setMaxDatagramFrameSize(0);

        byte[] encoded = params.encode();
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(encoded));

        assertEquals(0, decoded.getMaxDatagramFrameSize());
    }

    @Test
    public void testVersionInformationRoundTrip() {
        TransportParameters params = new TransportParameters();
        params.setVersionInformation(0x6b3343cf, new int[] { 0x6b3343cf, 1 });
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(params.encode()));
        assertTrue(decoded.hasVersionInformation());
        assertFalse(decoded.isVersionInformationMalformed());
        assertEquals(0x6b3343cf, decoded.getVersionInformationChosen());
        assertArrayEquals(new int[] { 0x6b3343cf, 1 }, decoded.getVersionInformationAvailable());
    }

    @Test
    public void testVersionInformationAbsentByDefault() {
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(new TransportParameters().encode()));
        assertFalse(decoded.hasVersionInformation());
        assertFalse(decoded.isVersionInformationMalformed());
    }

    @Test
    public void testVersionInformationMayHaveEmptyAvailableVersions() {
        TransportParameters params = new TransportParameters();
        params.setVersionInformation(1, new int[0]);
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(params.encode()));
        assertTrue(decoded.hasVersionInformation());
        assertEquals(0, decoded.getVersionInformationAvailable().length);
    }

    @Test
    public void testMalformedVersionInformationIsFlagged() {
        // RFC 9368 section 4: too short, length not divisible by four,
        // zero Chosen Version or zero Available Version.
        byte[][] bad = {
            ByteArrays.toByteArray("11020000"),
            ByteArrays.toByteArray("110500000001ff"),
            ByteArrays.toByteArray("110400000000"),
            ByteArrays.toByteArray("11080000000100000000"),
        };
        for (int i = 0; i < bad.length; i++) {
            TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(bad[i]));
            assertTrue("case " + i, decoded.isVersionInformationMalformed());
        }
    }

    private static byte[] fullEncoding() {
        TransportParameters params = new TransportParameters();
        params.setMaxIdleTimeout(30000);
        params.setInitialMaxData(1_000_000);
        params.setInitialMaxStreamsBidi(100);
        params.setMaxDatagramFrameSize(1200);
        params.setInitialSourceConnectionId(ByteArrays.toByteArray("0102030405060708"));
        params.setOriginalDestinationConnectionId(ByteArrays.toByteArray("8394c8f03e515708"));
        params.setStatelessResetToken(ByteArrays.toByteArray("00112233445566778899aabbccddeeff"));
        params.setVersionInformation(1, new int[] {1, 0x6b3343cf});
        return params.encode();
    }

    @Test
    public void testEveryTruncationEitherDecodesOrIsRejectedAsMalformed() {
        byte[] encoded = fullEncoding();
        for (int cut = 0; cut < encoded.length; cut++) {
            byte[] prefix = new byte[cut];
            System.arraycopy(encoded, 0, prefix, 0, cut);
            try {
                TransportParameters.decode(ByteBuffer.wrap(prefix));
            } catch (IllegalArgumentException expected) {
                assertTrue("cut " + cut, expected.getMessage() != null);
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyValueWhereAVarIntIsRequiredIsMalformed() {
        byte[] bad = new byte[] {0x04, 0x00};
        TransportParameters.decode(ByteBuffer.wrap(bad));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLengthBeyondTheBufferIsMalformed() {
        byte[] bad = new byte[] {0x0f, 0x10, 1, 2};
        TransportParameters.decode(ByteBuffer.wrap(bad));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHugeLengthIsMalformedNotANegativeArraySize() {
        byte[] bad = new byte[] {0x0f, (byte) 0xbf, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        TransportParameters.decode(ByteBuffer.wrap(bad));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValueShorterThanItsVarIntIsMalformed() {
        byte[] bad = new byte[] {0x04, 0x01, (byte) 0x80};
        TransportParameters.decode(ByteBuffer.wrap(bad));
    }

    // ---- preferred_address (RFC 9000 section 18.2) ----

    @Test
    public void testPreferredAddressRoundTrip() {
        TransportParameters params = new TransportParameters();
        params.setInitialSourceConnectionId(ByteArrays.toByteArray("01020304"));
        InetSocketAddress v4 = new InetSocketAddress("193.167.100.100", 4434);
        InetSocketAddress v6 = new InetSocketAddress("fd00:cafe:cafe:100::100", 4434);
        byte[] cid = ByteArrays.toByteArray("a1a2a3a4a5a6a7a8");
        byte[] token = ByteArrays.toByteArray("000102030405060708090a0b0c0d0e0f");
        params.setPreferredAddress(v4, v6, cid, token);

        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(params.encode()));
        assertTrue(decoded.hasPreferredAddress());
        assertEquals(v4, decoded.getPreferredAddressIpv4());
        assertEquals(v6, decoded.getPreferredAddressIpv6());
        assertArrayEquals(cid, decoded.getPreferredAddressConnectionId());
        assertArrayEquals(token, decoded.getPreferredAddressResetToken());
    }

    @Test
    public void testPreferredAddressWithOnlyOneFamily() {
        TransportParameters params = new TransportParameters();
        params.setInitialSourceConnectionId(ByteArrays.toByteArray("01020304"));
        InetSocketAddress v6 = new InetSocketAddress("2001:db8::7", 443);
        byte[] cid = ByteArrays.toByteArray("0a0b0c0d");
        byte[] token = new byte[16];
        params.setPreferredAddress(null, v6, cid, token);

        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(params.encode()));
        assertTrue(decoded.hasPreferredAddress());
        assertNull("an all-zero IPv4 address and port means none", decoded.getPreferredAddressIpv4());
        assertEquals(v6, decoded.getPreferredAddressIpv6());
        assertArrayEquals(cid, decoded.getPreferredAddressConnectionId());
    }

    @Test
    public void testPreferredAddressAbsentByDefault() {
        TransportParameters params = new TransportParameters();
        params.setInitialSourceConnectionId(ByteArrays.toByteArray("01020304"));
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(params.encode()));
        assertFalse(decoded.hasPreferredAddress());
        assertNull(decoded.getPreferredAddressIpv4());
        assertNull(decoded.getPreferredAddressIpv6());
        assertNull(decoded.getPreferredAddressConnectionId());
    }

    @Test
    public void testTruncatedPreferredAddressRejected() {
        // id 0x0d, length 10: far too short for the fixed 41 bytes before the token
        byte[] bad = ByteArrays.toByteArray("0d0a" + "00000000000000000000");
        try {
            TransportParameters.decode(ByteBuffer.wrap(bad));
            fail("expected a malformed preferred_address to be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testPreferredAddressWithZeroLengthConnectionIdRejected() {
        // RFC 9000 section 18.2: the connection ID is at least 1 byte long.
        byte[] fixed = ByteArrays.toByteArray("c1a764640000" + "fd00cafecafe01000000000000000100" + "0000" + "00");
        byte[] token = new byte[16];
        byte[] value = new byte[fixed.length + token.length];
        System.arraycopy(fixed, 0, value, 0, fixed.length);
        ByteBuffer buf = ByteBuffer.allocate(2 + value.length);
        buf.put((byte) 0x0d).put((byte) value.length).put(value);
        try {
            TransportParameters.decode(ByteBuffer.wrap(buf.array()));
            fail("expected a zero-length preferred_address connection ID to be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }


    @Test
    public void testMinAckDelayRoundTrip() {
        TransportParameters params = new TransportParameters();
        assertFalse(params.hasMinAckDelay());
        params.setMinAckDelay(1000);
        TransportParameters decoded = TransportParameters.decode(ByteBuffer.wrap(params.encode()));
        assertTrue(decoded.hasMinAckDelay());
        assertEquals(1000, decoded.getMinAckDelay());
    }

    @Test
    public void testMinAckDelayAbsentByDefault() {
        TransportParameters decoded = TransportParameters.decode(
                ByteBuffer.wrap(new TransportParameters().encode()));
        assertFalse(decoded.hasMinAckDelay());
    }

    @Test
    public void testCopyWithoutMinAckDelayKeepsTheRest() {
        TransportParameters params = new TransportParameters();
        params.setMinAckDelay(1000);
        params.setMaxAckDelay(30);
        params.setInitialMaxData(12345);
        params.setInitialSourceConnectionId(new byte[] {1, 2});
        params.setMaxDatagramFrameSize(1200);
        TransportParameters copy = params.copyWithoutMinAckDelay();
        assertFalse(copy.hasMinAckDelay());
        assertEquals(30, copy.getMaxAckDelay());
        assertEquals(12345, copy.getInitialMaxData());
        assertArrayEquals(new byte[] {1, 2}, copy.getInitialSourceConnectionId());
        assertEquals(1200, copy.getMaxDatagramFrameSize());
        assertTrue(params.hasMinAckDelay());
    }
}
