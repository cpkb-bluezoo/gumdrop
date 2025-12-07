/*
 * TraceTest.java
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

import java.util.List;

/**
 * Unit tests for {@link Trace}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TraceTest {

    // ========================================================================
    // Construction Tests
    // ========================================================================

    @Test
    public void testBasicConstruction() {
        Trace trace = new Trace("operation");
        
        assertNotNull(trace);
        assertNotNull(trace.getRootSpan());
        assertEquals("operation", trace.getRootSpan().getName());
        assertEquals(SpanKind.SERVER, trace.getRootSpan().getKind());
    }

    @Test
    public void testConstructionWithKind() {
        Trace trace = new Trace("operation", SpanKind.CLIENT);
        
        assertEquals(SpanKind.CLIENT, trace.getRootSpan().getKind());
    }

    @Test
    public void testTraceId() {
        Trace trace = new Trace("operation");
        
        byte[] traceId = trace.getTraceId();
        assertNotNull(traceId);
        assertEquals(SpanContext.TRACE_ID_LENGTH, traceId.length);
    }

    @Test
    public void testTraceIdHex() {
        Trace trace = new Trace("operation");
        
        String hex = trace.getTraceIdHex();
        assertNotNull(hex);
        assertEquals(32, hex.length()); // 16 bytes = 32 hex chars
        assertEquals(hex.toLowerCase(), hex); // Should be lowercase
    }

    @Test
    public void testTraceIdDefensiveCopy() {
        Trace trace = new Trace("operation");
        
        byte[] id1 = trace.getTraceId();
        byte[] id2 = trace.getTraceId();
        
        assertNotSame(id1, id2);
        assertArrayEquals(id1, id2);
        
        // Modify returned array
        id1[0] = 0;
        assertNotEquals(id1[0], trace.getTraceId()[0]);
    }

    @Test
    public void testDefaultSampled() {
        Trace trace = new Trace("operation");
        
        assertTrue(trace.isSampled());
    }

    // ========================================================================
    // Parent Context Construction Tests
    // ========================================================================

    @Test
    public void testConstructionFromParentContext() {
        byte[] parentTraceId = new byte[16];
        byte[] parentSpanId = new byte[8];
        for (int i = 0; i < 16; i++) parentTraceId[i] = (byte) (i + 1);
        for (int i = 0; i < 8; i++) parentSpanId[i] = (byte) (i + 10);
        
        SpanContext parentCtx = new SpanContext(parentTraceId, parentSpanId, true);
        Trace trace = new Trace(parentCtx, "child-operation", SpanKind.SERVER);
        
        // Should use parent's trace ID
        assertArrayEquals(parentTraceId, trace.getTraceId());
        assertTrue(trace.isSampled());
    }

    @Test
    public void testConstructionFromParentContextNotSampled() {
        byte[] parentTraceId = new byte[16];
        byte[] parentSpanId = new byte[8];
        SpanContext parentCtx = new SpanContext(parentTraceId, parentSpanId, false);
        
        Trace trace = new Trace(parentCtx, "child-operation", SpanKind.SERVER);
        
        assertFalse(trace.isSampled());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructionFromNullContext() {
        new Trace((SpanContext) null, "operation", SpanKind.SERVER);
    }

    // ========================================================================
    // Traceparent Construction Tests
    // ========================================================================

    @Test
    public void testFromTraceparentValid() {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        
        Trace trace = Trace.fromTraceparent(traceparent, "operation");
        
        assertNotNull(trace);
        assertEquals("0af7651916cd43dd8448eb211c80319c", trace.getTraceIdHex());
        assertTrue(trace.isSampled());
    }

    @Test
    public void testFromTraceparentWithKind() {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        
        Trace trace = Trace.fromTraceparent(traceparent, "operation", SpanKind.CLIENT);
        
        assertEquals(SpanKind.CLIENT, trace.getRootSpan().getKind());
    }

    @Test
    public void testFromTraceparentInvalid() {
        Trace trace = Trace.fromTraceparent("invalid", "operation");
        
        // Should create new trace instead of failing
        assertNotNull(trace);
        assertNotNull(trace.getTraceIdHex());
        assertEquals(32, trace.getTraceIdHex().length());
    }

    @Test
    public void testFromTraceparentNull() {
        Trace trace = Trace.fromTraceparent(null, "operation");
        
        // Should create new trace
        assertNotNull(trace);
        assertNotNull(trace.getTraceIdHex());
    }

    // ========================================================================
    // Child Span Tests
    // ========================================================================

    @Test
    public void testStartSpan() {
        Trace trace = new Trace("parent", SpanKind.SERVER);
        
        Span child = trace.startSpan("child", SpanKind.CLIENT);
        
        assertNotNull(child);
        assertEquals("child", child.getName());
        assertEquals(SpanKind.CLIENT, child.getKind());
        assertSame(trace, child.getTrace());
    }

    @Test
    public void testStartSpanDefaultKind() {
        Trace trace = new Trace("parent", SpanKind.SERVER);
        
        Span child = trace.startSpan("child");
        
        assertEquals(SpanKind.INTERNAL, child.getKind());
    }

    @Test
    public void testCurrentSpan() {
        Trace trace = new Trace("root", SpanKind.SERVER);
        
        // Initially current span is root
        assertSame(trace.getRootSpan(), trace.getCurrentSpan());
        
        // After starting child, current span changes
        Span child = trace.startSpan("child");
        assertSame(child, trace.getCurrentSpan());
        
        // After ending child, current returns to parent
        child.end();
        assertSame(trace.getRootSpan(), trace.getCurrentSpan());
    }

    @Test
    public void testNestedSpans() {
        Trace trace = new Trace("root", SpanKind.SERVER);
        
        Span child1 = trace.startSpan("child1");
        Span grandchild = trace.startSpan("grandchild");
        
        assertSame(grandchild, trace.getCurrentSpan());
        
        grandchild.end();
        assertSame(child1, trace.getCurrentSpan());
        
        child1.end();
        assertSame(trace.getRootSpan(), trace.getCurrentSpan());
    }

    // ========================================================================
    // Ended Spans Collection Tests
    // ========================================================================

    @Test
    public void testGetEndedSpans() {
        Trace trace = new Trace("root", SpanKind.SERVER);
        
        // No ended spans initially
        assertTrue(trace.getEndedSpans().isEmpty());
        
        // Start and end a child span
        Span child = trace.startSpan("child");
        child.end();
        
        // Should now have one ended span
        List<Span> ended = trace.getEndedSpans();
        assertEquals(1, ended.size());
        assertSame(child, ended.get(0));
    }

    @Test
    public void testEndedSpansOrder() {
        Trace trace = new Trace("root", SpanKind.SERVER);
        
        Span child1 = trace.startSpan("child1");
        child1.end();
        
        Span child2 = trace.startSpan("child2");
        child2.end();
        
        List<Span> ended = trace.getEndedSpans();
        assertEquals(2, ended.size());
        assertEquals("child1", ended.get(0).getName());
        assertEquals("child2", ended.get(1).getName());
    }

    @Test
    public void testEndedSpansUnmodifiable() {
        Trace trace = new Trace("root", SpanKind.SERVER);
        Span child = trace.startSpan("child");
        child.end();
        
        List<Span> ended = trace.getEndedSpans();
        try {
            ended.add(trace.getRootSpan());
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }

    // ========================================================================
    // Root Span Children Tests
    // ========================================================================

    @Test
    public void testRootSpanHasChildren() {
        Trace trace = new Trace("root", SpanKind.SERVER);
        
        Span child1 = trace.startSpan("child1");
        child1.end();
        
        Span child2 = trace.startSpan("child2");
        child2.end();
        
        trace.getRootSpan().end();
        
        // Root span should have children
        List<Span> children = trace.getRootSpan().getChildren();
        assertEquals(2, children.size());
    }

    // ========================================================================
    // Traceparent Generation Tests
    // ========================================================================

    @Test
    public void testGetTraceparent() {
        Trace trace = new Trace("operation", SpanKind.SERVER);
        
        String traceparent = trace.getTraceparent();
        
        assertNotNull(traceparent);
        assertEquals(55, traceparent.length());
        assertTrue(traceparent.startsWith("00-"));
        assertTrue(traceparent.contains(trace.getTraceIdHex()));
    }

    @Test
    public void testTraceparentRoundTrip() {
        Trace original = new Trace("original", SpanKind.SERVER);
        String traceparent = original.getTraceparent();
        
        Trace continued = Trace.fromTraceparent(traceparent, "continued", SpanKind.SERVER);
        
        assertEquals(original.getTraceIdHex(), continued.getTraceIdHex());
    }

    // ========================================================================
    // toString Tests
    // ========================================================================

    @Test
    public void testToString() {
        Trace trace = new Trace("my-operation", SpanKind.SERVER);
        
        String str = trace.toString();
        assertTrue(str.contains("Trace"));
        assertTrue(str.contains(trace.getTraceIdHex().substring(0, 8))); // At least part of ID
    }

    // ========================================================================
    // Unique Trace IDs Test
    // ========================================================================

    @Test
    public void testUniqueTraceIds() {
        Trace trace1 = new Trace("op1");
        Trace trace2 = new Trace("op2");
        Trace trace3 = new Trace("op3");
        
        // All trace IDs should be different
        assertFalse(trace1.getTraceIdHex().equals(trace2.getTraceIdHex()));
        assertFalse(trace1.getTraceIdHex().equals(trace3.getTraceIdHex()));
        assertFalse(trace2.getTraceIdHex().equals(trace3.getTraceIdHex()));
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void assertArrayEquals(byte[] expected, byte[] actual) {
        assertEquals("Array length mismatch", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("Byte mismatch at index " + i, expected[i], actual[i]);
        }
    }

}

