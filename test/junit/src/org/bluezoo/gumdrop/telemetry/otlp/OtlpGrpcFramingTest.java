/*
 * OtlpGrpcFramingTest.java
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


package org.bluezoo.gumdrop.telemetry.otlp;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.grpc.GrpcFraming;
import org.junit.Test;

/**
 * Checks that the OTLP gRPC exporter's own message framing matches the gRPC
 * wire format as implemented by {@link GrpcFraming}. The exporter frames
 * messages itself so the telemetry module does not depend on the gRPC
 * module; this test is what keeps the two copies of the format in step.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OtlpGrpcFramingTest {

    private static byte[] bytes(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.duplicate().get(out);
        return out;
    }

    private static void assertSameFraming(byte[] payload) {
        ByteBuffer expected = GrpcFraming.frame(ByteBuffer.wrap(payload));
        ByteBuffer actual = OtlpGrpcEndpoint.frame(ByteBuffer.wrap(payload));
        org.junit.Assert.assertArrayEquals(bytes(expected), bytes(actual));
        assertEquals(5 + payload.length, actual.remaining());
    }

    @Test
    public void emptyMessage() {
        assertSameFraming(new byte[0]);
    }

    @Test
    public void smallMessage() {
        assertSameFraming(new byte[] {1, 2, 3, 4, 5});
    }

    @Test
    public void lengthUsesAllFourBytes() {
        assertSameFraming(new byte[70000]);
    }

    @Test
    public void framingConsumesTheMessage() {
        ByteBuffer in = ByteBuffer.wrap(new byte[] {9, 8, 7});
        OtlpGrpcEndpoint.frame(in);
        assertEquals(0, in.remaining());
    }
}
