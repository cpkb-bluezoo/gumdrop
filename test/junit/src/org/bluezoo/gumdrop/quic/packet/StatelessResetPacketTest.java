/*
 * StatelessResetPacketTest.java
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
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import org.bluezoo.util.ByteArrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link StatelessResetPacket} sizing, wire layout, and token
 * comparison overloads.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class StatelessResetPacketTest {

    private static final byte[] TOKEN = ByteArrays.toByteArray(
            "0123456789abcdef0123456789abcdef");

    @Test
    public void testComputeLengthRejectsShortDatagrams() {
        assertEquals(-1, StatelessResetPacket.computeLength(20));
    }

    @Test
    public void testComputeLengthAtLeastTwentyOne() {
        assertEquals(21, StatelessResetPacket.computeLength(21));
    }

    @Test
    public void testComputeLengthCapsAtThreeTimesReceived() {
        assertEquals(21, StatelessResetPacket.computeLength(21));
        assertEquals(25, StatelessResetPacket.computeLength(25));
    }

    @Test
    public void testComputeLengthAllowsLargeResetForLargeReceived() {
        assertEquals(5000, StatelessResetPacket.computeLength(5000));
    }

    @Test
    public void testBuildSetsFixedBitAndTailToken() {
        byte[] packet = StatelessResetPacket.build(21, TOKEN, new SecureRandom());
        assertNotNull(packet);
        assertEquals(21, packet.length);
        assertTrue((packet[0] & 0x40) != 0);
        assertTrue(Arrays.equals(TOKEN, Arrays.copyOfRange(packet, 5, 21)));
    }

    @Test
    public void testBuildReturnsNullWhenTooShort() {
        assertNull(StatelessResetPacket.build(10, TOKEN, new SecureRandom()));
    }

    @Test
    public void testMatchesTokenOnArray() {
        byte[] datagram = new byte[21];
        System.arraycopy(TOKEN, 0, datagram, 5, TOKEN.length);
        assertTrue(StatelessResetPacket.matchesToken(datagram, 0, datagram.length, TOKEN));
        assertFalse(StatelessResetPacket.matchesToken(datagram, 0, datagram.length,
                ByteArrays.toByteArray("ffffffffffffffffffffffffffffffff")));
    }

    @Test
    public void testMatchesTokenOnByteBuffer() {
        byte[] datagram = new byte[21];
        System.arraycopy(TOKEN, 0, datagram, 5, TOKEN.length);
        ByteBuffer buf = ByteBuffer.wrap(datagram);
        assertTrue(StatelessResetPacket.matchesToken(buf, 0, datagram.length, TOKEN));
    }

    @Test
    public void testMatchesAnyKnownToken() {
        byte[] datagram = new byte[21];
        System.arraycopy(TOKEN, 0, datagram, 5, TOKEN.length);
        assertTrue(StatelessResetPacket.matchesAnyKnownToken(datagram, 0, datagram.length,
                Collections.singletonList(TOKEN)));
        assertFalse(StatelessResetPacket.matchesAnyKnownToken(datagram, 0, datagram.length,
                Collections.<byte[]>emptyList()));
    }
}
