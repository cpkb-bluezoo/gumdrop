/*
 * GrpcResponseFramingTest.java
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

import org.bluezoo.protobuf.ByteBufferChannel;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Regression tests for issue #326: gRPC response serialization should
 * reserve the 5-byte frame header up front and write it in place after
 * the payload is complete, instead of allocating a second buffer and
 * copying the whole message in {@link GrpcFraming#frame(ByteBuffer)}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class GrpcResponseFramingTest {

    @Test
    public void testInPlaceFramingMatchesCopyFramingWireFormat() throws Exception {
        byte[] payload = buildPayload(64 * 1024);
        ByteBuffer copied = GrpcFraming.frame(payload);

        ByteBufferChannel channel = ByteBufferChannel.withLeadingReserve(
                GrpcFraming.HEADER_SIZE, 4096);
        channel.write(ByteBuffer.wrap(payload));
        ByteBuffer inPlace = frameInPlace(channel);

        assertBuffersEqual(copied, inPlace);
    }

    @Test
    public void testLargeResponseFramingReusesSerializationBuffer() throws Exception {
        int payloadSize = 256 * 1024;
        byte[] payload = buildPayload(payloadSize);

        ByteBufferChannel channel = ByteBufferChannel.withLeadingReserve(
                GrpcFraming.HEADER_SIZE, 4096);
        channel.write(ByteBuffer.wrap(payload));

        ByteBuffer internal = internalBuffer(channel);
        ByteBuffer framed = frameInPlace(channel);

        assertSame("framing must not allocate a separate header+body buffer",
                internal, framed);
        assertEquals(0, framed.position());
        assertEquals(GrpcFraming.framedSize(payloadSize), framed.limit());

        byte[] fromWire = new byte[payloadSize];
        framed.position(GrpcFraming.HEADER_SIZE);
        framed.get(fromWire);
        assertArrayEquals(payload, fromWire);
    }

    private static ByteBuffer frameInPlace(ByteBufferChannel channel) throws Exception {
        int payloadLength = channel.payloadLength();
        ByteBuffer framed = channel.finishWithLeadingReserve();
        GrpcFraming.writeHeader(framed, payloadLength);
        return framed;
    }

    private static byte[] buildPayload(int length) {
        byte[] payload = new byte[length];
        for (int i = 0; i < length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }
        return payload;
    }

    private static void assertBuffersEqual(ByteBuffer expected, ByteBuffer actual) {
        byte[] exp = new byte[expected.remaining()];
        expected.get(exp);
        byte[] act = new byte[actual.remaining()];
        actual.get(act);
        assertArrayEquals(exp, act);
    }

    private static ByteBuffer internalBuffer(ByteBufferChannel channel) throws Exception {
        Field bufferField = ByteBufferChannel.class.getDeclaredField("buffer");
        bufferField.setAccessible(true);
        return (ByteBuffer) bufferField.get(channel);
    }
}
