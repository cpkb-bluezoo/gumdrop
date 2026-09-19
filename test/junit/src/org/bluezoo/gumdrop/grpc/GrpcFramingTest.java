/*
 * GrpcFramingTest.java
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

package org.bluezoo.gumdrop.grpc;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/**
 * Tests for {@link GrpcFraming} message length validation (SEC-011).
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcFramingTest {

    @Test
    public void testReadHeaderRoundTrip() {
        ByteBuffer framed = GrpcFraming.frame(new byte[] {1, 2, 3});
        int length = GrpcFraming.readHeader(framed, 1024);
        assertEquals(3, length);
        assertEquals(3, framed.remaining());
    }

    @Test
    public void testReadHeaderIncompleteReturnsNegativeOne() {
        ByteBuffer buf = ByteBuffer.allocate(3);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.flip();
        assertEquals(-1, GrpcFraming.readHeader(buf, 1024));
    }

    @Test(expected = GrpcException.class)
    public void testReadHeaderRejectsOversizedDeclaredLength() {
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put((byte) 0);
        buf.putInt(1_048_576);
        buf.flip();
        GrpcFraming.readHeader(buf, 1024);
    }

    @Test(expected = GrpcException.class)
    public void testReadHeaderRejectsLengthAboveIntegerMax() {
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put((byte) 0);
        buf.putInt(-1);
        buf.flip();
        GrpcFraming.readHeader(buf);
    }
}
