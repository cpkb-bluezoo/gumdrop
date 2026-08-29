/*
 * GrpcResponseFramingTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.grpc;

import org.bluezoo.gumdrop.telemetry.protobuf.ByteBufferChannel;
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
