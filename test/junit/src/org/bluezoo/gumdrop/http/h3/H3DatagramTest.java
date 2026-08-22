/*
 * H3DatagramTest.java
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

package org.bluezoo.gumdrop.http.h3;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * RFC 9297 section 2.1 HTTP/3 Datagram quarter-stream-ID tests.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3DatagramTest {

    @Test
    public void testRoundTripEmptyPayload() {
        byte[] encoded = H3Datagram.encode(0, new byte[0]);
        H3Datagram decoded = H3Datagram.decode(ByteBuffer.wrap(encoded));
        assertEquals(0L, decoded.getStreamId());
        assertEquals(0, decoded.getPayload().length);
    }

    @Test
    public void testRoundTripWithPayload() {
        byte[] encoded = H3Datagram.encode(8, "hello".getBytes(StandardCharsets.US_ASCII));
        H3Datagram decoded = H3Datagram.decode(ByteBuffer.wrap(encoded));
        assertEquals(8L, decoded.getStreamId());
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), decoded.getPayload());
    }

    @Test
    public void testRejectsNonClientBidiStreamId() {
        assertNull(H3Datagram.encode(1, new byte[] { 'x' }));
    }

    @Test
    public void testDecodeTruncatedIsNull() {
        assertNull(H3Datagram.decode(ByteBuffer.allocate(0)));
    }

    @Test
    public void testSettingsAndErrorCodeMatchRfc() {
        assertEquals(0x33L, H3FrameHandler.SETTINGS_H3_DATAGRAM);
        assertEquals(0x33L, H3ErrorCode.H3_DATAGRAM_ERROR);
    }
}
