/*
 * VariableLengthEncodingTest.java
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

package org.bluezoo.gumdrop.mqtt.codec;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.ByteBuffer;

public class VariableLengthEncodingTest {

    @Test
    public void testEncodeDecodeZero() {
        assertRoundTrip(0);
    }

    @Test
    public void testEncodeDecodeOneByte() {
        assertRoundTrip(0);
        assertRoundTrip(1);
        assertRoundTrip(127);
    }

    @Test
    public void testEncodeDecodeTwoBytes() {
        assertRoundTrip(128);
        assertRoundTrip(16383);
    }

    @Test
    public void testEncodeDecodeThreeBytes() {
        assertRoundTrip(16384);
        assertRoundTrip(2097151);
    }

    @Test
    public void testEncodeDecodeFourBytes() {
        assertRoundTrip(2097152);
        assertRoundTrip(268_435_455);
    }

    @Test
    public void testEncodedLength() {
        assertEquals(1, VariableLengthEncoding.encodedLength(0));
        assertEquals(1, VariableLengthEncoding.encodedLength(127));
        assertEquals(2, VariableLengthEncoding.encodedLength(128));
        assertEquals(2, VariableLengthEncoding.encodedLength(16383));
        assertEquals(3, VariableLengthEncoding.encodedLength(16384));
        assertEquals(3, VariableLengthEncoding.encodedLength(2097151));
        assertEquals(4, VariableLengthEncoding.encodedLength(2097152));
        assertEquals(4, VariableLengthEncoding.encodedLength(268_435_455));
    }

    @Test
    public void testDecodeNeedsMoreData() {
        ByteBuffer buf = ByteBuffer.allocate(1);
        buf.put((byte) 0x80); // continuation bit set, but no more bytes
        buf.flip();

        int result = VariableLengthEncoding.decode(buf);
        assertEquals(VariableLengthEncoding.NEEDS_MORE_DATA, result);
        assertEquals(0, buf.position()); // position restored
    }

    @Test
    public void testDecodeEmptyBuffer() {
        ByteBuffer buf = ByteBuffer.allocate(0);
        int result = VariableLengthEncoding.decode(buf);
        assertEquals(VariableLengthEncoding.NEEDS_MORE_DATA, result);
    }

    @Test
    public void testDecodeMalformed() {
        // 5 continuation bytes — malformed
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put((byte) 0x80);
        buf.put((byte) 0x80);
        buf.put((byte) 0x80);
        buf.put((byte) 0x80);
        buf.put((byte) 0x01);
        buf.flip();

        int result = VariableLengthEncoding.decode(buf);
        assertEquals(VariableLengthEncoding.MALFORMED, result);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEncodeNegativeValue() {
        ByteBuffer buf = ByteBuffer.allocate(4);
        VariableLengthEncoding.encode(buf, -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEncodeOverMaxValue() {
        ByteBuffer buf = ByteBuffer.allocate(4);
        VariableLengthEncoding.encode(buf, VariableLengthEncoding.MAX_VALUE + 1);
    }

    private void assertRoundTrip(int value) {
        ByteBuffer buf = ByteBuffer.allocate(4);
        VariableLengthEncoding.encode(buf, value);
        buf.flip();
        assertEquals(VariableLengthEncoding.encodedLength(value), buf.remaining());
        int decoded = VariableLengthEncoding.decode(buf);
        assertEquals(value, decoded);
        assertFalse(buf.hasRemaining());
    }
}
