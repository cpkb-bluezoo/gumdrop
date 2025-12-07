/*
 * SpanContextTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.telemetry;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link SpanContext}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SpanContextTest {

    // ========================================================================
    // Construction Tests
    // ========================================================================

    @Test
    public void testConstructionSampled() {
        byte[] traceId = createTraceId();
        byte[] spanId = createSpanId();
        
        SpanContext ctx = new SpanContext(traceId, spanId, true);
        
        assertArrayEquals(traceId, ctx.getTraceId());
        assertArrayEquals(spanId, ctx.getSpanId());
        assertTrue(ctx.isSampled());
        assertEquals(SpanContext.FLAG_SAMPLED, ctx.getTraceFlags());
    }

    @Test
    public void testConstructionNotSampled() {
        byte[] traceId = createTraceId();
        byte[] spanId = createSpanId();
        
        SpanContext ctx = new SpanContext(traceId, spanId, false);
        
        assertFalse(ctx.isSampled());
        assertEquals(0, ctx.getTraceFlags());
    }

    @Test
    public void testConstructionWithFlags() {
        byte[] traceId = createTraceId();
        byte[] spanId = createSpanId();
        
        SpanContext ctx = new SpanContext(traceId, spanId, 0x01);
        
        assertTrue(ctx.isSampled());
        assertEquals(0x01, ctx.getTraceFlags());
    }

    @Test
    public void testConstructionDefensiveCopy() {
        byte[] traceId = createTraceId();
        byte[] spanId = createSpanId();
        
        SpanContext ctx = new SpanContext(traceId, spanId, true);
        
        // Modify original arrays
        traceId[0] = 0;
        spanId[0] = 0;
        
        // Context should have original values
        assertNotEquals(0, ctx.getTraceId()[0]);
        assertNotEquals(0, ctx.getSpanId()[0]);
    }

    @Test
    public void testGettersReturnCopies() {
        byte[] traceId = createTraceId();
        byte[] spanId = createSpanId();
        
        SpanContext ctx = new SpanContext(traceId, spanId, true);
        
        // Modify returned arrays
        byte[] returnedTraceId = ctx.getTraceId();
        byte[] returnedSpanId = ctx.getSpanId();
        returnedTraceId[0] = 0;
        returnedSpanId[0] = 0;
        
        // Context should still have original values
        assertNotEquals(0, ctx.getTraceId()[0]);
        assertNotEquals(0, ctx.getSpanId()[0]);
    }

    // ========================================================================
    // Invalid Construction Tests
    // ========================================================================

    @Test(expected = IllegalArgumentException.class)
    public void testNullTraceId() {
        new SpanContext(null, createSpanId(), true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullSpanId() {
        new SpanContext(createTraceId(), null, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidTraceIdLength() {
        new SpanContext(new byte[8], createSpanId(), true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidSpanIdLength() {
        new SpanContext(createTraceId(), new byte[16], true);
    }

    // ========================================================================
    // Hex Conversion Tests
    // ========================================================================

    @Test
    public void testTraceIdHex() {
        byte[] traceId = new byte[] {
            0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef,
            0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef
        };
        byte[] spanId = createSpanId();
        
        SpanContext ctx = new SpanContext(traceId, spanId, true);
        
        assertEquals("0123456789abcdef0123456789abcdef", ctx.getTraceIdHex());
    }

    @Test
    public void testSpanIdHex() {
        byte[] traceId = createTraceId();
        byte[] spanId = new byte[] {
            0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef
        };
        
        SpanContext ctx = new SpanContext(traceId, spanId, true);
        
        assertEquals("0123456789abcdef", ctx.getSpanIdHex());
    }

    @Test
    public void testHexLowercase() {
        byte[] traceId = new byte[] {
            (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };
        byte[] spanId = createSpanId();
        
        SpanContext ctx = new SpanContext(traceId, spanId, true);
        String hex = ctx.getTraceIdHex();
        
        assertEquals(hex.toLowerCase(), hex); // Should be lowercase
        assertTrue(hex.startsWith("abcdef"));
    }

    // ========================================================================
    // W3C Traceparent Tests
    // ========================================================================

    @Test
    public void testToTraceparent() {
        byte[] traceId = new byte[] {
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            (byte) 0x88, (byte) 0x99, (byte) 0xaa, (byte) 0xbb, 
            (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff
        };
        byte[] spanId = new byte[] {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        };
        
        SpanContext ctx = new SpanContext(traceId, spanId, true);
        String traceparent = ctx.toTraceparent();
        
        assertEquals("00-00112233445566778899aabbccddeeff-0102030405060708-01", traceparent);
    }

    @Test
    public void testToTraceparentNotSampled() {
        byte[] traceId = createTraceId();
        byte[] spanId = createSpanId();
        
        SpanContext ctx = new SpanContext(traceId, spanId, false);
        String traceparent = ctx.toTraceparent();
        
        assertTrue(traceparent.endsWith("-00"));
    }

    @Test
    public void testToTraceparentLength() {
        SpanContext ctx = new SpanContext(createTraceId(), createSpanId(), true);
        
        assertEquals(55, ctx.toTraceparent().length());
    }

    @Test
    public void testFromTraceparentValid() {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        
        SpanContext ctx = SpanContext.fromTraceparent(traceparent);
        
        assertNotNull(ctx);
        assertEquals("0af7651916cd43dd8448eb211c80319c", ctx.getTraceIdHex());
        assertEquals("b7ad6b7169203331", ctx.getSpanIdHex());
        assertTrue(ctx.isSampled());
    }

    @Test
    public void testFromTraceparentNotSampled() {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00";
        
        SpanContext ctx = SpanContext.fromTraceparent(traceparent);
        
        assertNotNull(ctx);
        assertFalse(ctx.isSampled());
    }

    @Test
    public void testFromTraceparentRoundTrip() {
        byte[] traceId = createTraceId();
        byte[] spanId = createSpanId();
        SpanContext original = new SpanContext(traceId, spanId, true);
        
        String traceparent = original.toTraceparent();
        SpanContext parsed = SpanContext.fromTraceparent(traceparent);
        
        assertNotNull(parsed);
        assertArrayEquals(original.getTraceId(), parsed.getTraceId());
        assertArrayEquals(original.getSpanId(), parsed.getSpanId());
        assertEquals(original.isSampled(), parsed.isSampled());
    }

    // ========================================================================
    // Invalid Traceparent Tests
    // ========================================================================

    @Test
    public void testFromTraceparentNull() {
        assertNull(SpanContext.fromTraceparent(null));
    }

    @Test
    public void testFromTraceparentEmpty() {
        assertNull(SpanContext.fromTraceparent(""));
    }

    @Test
    public void testFromTraceparentTooShort() {
        assertNull(SpanContext.fromTraceparent("00-abc-def-01"));
    }

    @Test
    public void testFromTraceparentInvalidVersion() {
        // Version must be "00"
        assertNull(SpanContext.fromTraceparent("01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"));
    }

    @Test
    public void testFromTraceparentMissingDash() {
        // Missing dash after version
        assertNull(SpanContext.fromTraceparent("00x0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"));
    }

    @Test
    public void testFromTraceparentInvalidHex() {
        // 'g' is not a valid hex character
        assertNull(SpanContext.fromTraceparent("00-0gf7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"));
    }

    // ========================================================================
    // toString Tests
    // ========================================================================

    @Test
    public void testToString() {
        byte[] traceId = new byte[] {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10
        };
        byte[] spanId = new byte[] {
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18
        };
        
        SpanContext ctx = new SpanContext(traceId, spanId, true);
        String str = ctx.toString();
        
        assertTrue(str.contains("SpanContext"));
        assertTrue(str.contains("traceId="));
        assertTrue(str.contains("spanId="));
        assertTrue(str.contains("sampled=true"));
    }

    // ========================================================================
    // Constants Tests
    // ========================================================================

    @Test
    public void testConstants() {
        assertEquals(16, SpanContext.TRACE_ID_LENGTH);
        assertEquals(8, SpanContext.SPAN_ID_LENGTH);
        assertEquals(0x01, SpanContext.FLAG_SAMPLED);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private byte[] createTraceId() {
        byte[] id = new byte[16];
        for (int i = 0; i < 16; i++) {
            id[i] = (byte) (i + 1);
        }
        return id;
    }

    private byte[] createSpanId() {
        byte[] id = new byte[8];
        for (int i = 0; i < 8; i++) {
            id[i] = (byte) (i + 10);
        }
        return id;
    }

    private void assertArrayEquals(byte[] expected, byte[] actual) {
        assertEquals("Array length mismatch", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("Byte mismatch at index " + i, expected[i], actual[i]);
        }
    }

}

