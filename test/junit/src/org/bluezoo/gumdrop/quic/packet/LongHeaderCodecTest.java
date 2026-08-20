/*
 * LongHeaderCodecTest.java
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

import org.bluezoo.util.ByteArrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Verifies {@link LongHeaderCodec} build/parse round trips against the
 * same RFC 9001 Appendix A client and server Initial packet headers
 * used by {@link PacketProtectionRfc9001Test}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LongHeaderCodecTest {

    private static final byte[] DCID = ByteArrays.toByteArray("8394c8f03e515708");

    private static final String CLIENT_UNPROTECTED_HEADER =
            "c300000001088394c8f03e5157080000449e00000002";

    private static final String SERVER_UNPROTECTED_HEADER =
            "c1000000010008f067a5502a4262b50040750001";

    @Test
    public void testBuildClientInitialHeader() {
        byte[] expected = ByteArrays.toByteArray(CLIENT_UNPROTECTED_HEADER);
        byte[] emptyToken = new byte[0];
        byte[] built = LongHeaderCodec.build(
                LongHeaderCodec.TYPE_INITIAL, 1, DCID, new byte[0], emptyToken,
                2, 4, 1162 + QuicAeadAlgorithm.TAG_LENGTH);
        assertArrayEquals(expected, built);
    }

    @Test
    public void testParseClientInitialHeader() {
        byte[] packet = ByteArrays.toByteArray(CLIENT_UNPROTECTED_HEADER);
        LongHeaderPrefix prefix = LongHeaderCodec.parsePrefix(packet);

        assertEquals(LongHeaderCodec.TYPE_INITIAL, prefix.getPacketType());
        assertEquals(1, prefix.getVersion());
        assertArrayEquals(DCID, prefix.getDestinationConnectionId());
        assertArrayEquals(new byte[0], prefix.getSourceConnectionId());
        assertArrayEquals(new byte[0], prefix.getToken());
        assertEquals(18, prefix.getPacketNumberOffset());
        assertEquals(1182, prefix.getRemainingLength());
    }

    @Test
    public void testBuildServerInitialHeader() {
        byte[] expected = ByteArrays.toByteArray(SERVER_UNPROTECTED_HEADER);
        byte[] scid = ByteArrays.toByteArray("f067a5502a4262b5");
        byte[] emptyToken = new byte[0];
        int serverPayloadLength = 99;
        byte[] built = LongHeaderCodec.build(
                LongHeaderCodec.TYPE_INITIAL, 1, new byte[0], scid, emptyToken,
                1, 2, serverPayloadLength + QuicAeadAlgorithm.TAG_LENGTH);
        assertArrayEquals(expected, built);
    }

    @Test
    public void testParseServerInitialHeader() {
        byte[] packet = ByteArrays.toByteArray(SERVER_UNPROTECTED_HEADER);
        LongHeaderPrefix prefix = LongHeaderCodec.parsePrefix(packet);

        assertEquals(LongHeaderCodec.TYPE_INITIAL, prefix.getPacketType());
        assertEquals(1, prefix.getVersion());
        assertArrayEquals(new byte[0], prefix.getDestinationConnectionId());
        assertArrayEquals(ByteArrays.toByteArray("f067a5502a4262b5"), prefix.getSourceConnectionId());
        assertArrayEquals(new byte[0], prefix.getToken());
        assertEquals(18, prefix.getPacketNumberOffset());
        assertEquals(117, prefix.getRemainingLength());
    }
}
