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

import java.nio.ByteBuffer;

import org.junit.Test;

import org.bluezoo.util.ByteArrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
