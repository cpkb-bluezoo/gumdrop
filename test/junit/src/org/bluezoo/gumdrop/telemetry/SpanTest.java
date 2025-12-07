/*
 * SpanTest.java
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
 * Unit tests for {@link Span}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SpanTest {

    // ========================================================================
    // Construction Tests
    // ========================================================================

    @Test
    public void testRootSpanCreation() {
        Trace trace = new Trace("test-operation", SpanKind.SERVER);
        Span root = trace.getRootSpan();
        
        assertNotNull(root);
        assertEquals("test-operation", root.getName());
        assertEquals(SpanKind.SERVER, root.getKind());
        assertNull(root.getParent());
        assertSame(trace, root.getTrace());
    }

    @Test
    public void testSpanHasId() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        byte[] spanId = span.getSpanId();
        assertNotNull(spanId);
        assertEquals(SpanContext.SPAN_ID_LENGTH, spanId.length);
    }

    @Test
    public void testSpanIdDefensiveCopy() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        byte[] id1 = span.getSpanId();
        byte[] id2 = span.getSpanId();
        
        assertNotSame(id1, id2);
        assertArrayEquals(id1, id2);
        
        // Modify returned array
        id1[0] = 0;
        assertNotEquals(id1[0], span.getSpanId()[0]);
    }

    @Test
    public void testSpanIdHex() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        String hex = span.getSpanContext().getSpanIdHex();
        assertNotNull(hex);
        assertEquals(16, hex.length()); // 8 bytes = 16 hex chars
        assertEquals(hex.toLowerCase(), hex); // Should be lowercase
    }

    // ========================================================================
    // Child Span Tests
    // ========================================================================

    @Test
    public void testChildSpan() {
        Trace trace = new Trace("parent", SpanKind.SERVER);
        Span child = trace.startSpan("child", SpanKind.CLIENT);
        
        assertNotNull(child);
        assertEquals("child", child.getName());
        assertEquals(SpanKind.CLIENT, child.getKind());
        assertNotNull(child.getParent());
        assertEquals(trace.getRootSpan(), child.getParent());
    }

    @Test
    public void testNestedChildSpans() {
        Trace trace = new Trace("root", SpanKind.SERVER);
        Span child1 = trace.startSpan("child1", SpanKind.INTERNAL);
        Span child2 = trace.startSpan("child2", SpanKind.INTERNAL);
        child2.end();
        child1.end();
        trace.getRootSpan().end();
        
        List<Span> children = trace.getRootSpan().getChildren();
        assertTrue(children.size() >= 1);
    }

    // ========================================================================
    // Timing Tests
    // ========================================================================

    @Test
    public void testStartTime() {
        long before = System.currentTimeMillis() * 1_000_000L;
        Trace trace = new Trace("test", SpanKind.SERVER);
        long after = System.currentTimeMillis() * 1_000_000L;
        
        Span span = trace.getRootSpan();
        long startTime = span.getStartTimeUnixNano();
        
        assertTrue("Start time should be >= before", startTime >= before);
        assertTrue("Start time should be <= after", startTime <= after);
    }

    @Test
    public void testEndTime() throws InterruptedException {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        Thread.sleep(10); // Small delay
        span.end();
        
        long endTime = span.getEndTimeUnixNano();
        assertTrue("End time should be > start time", endTime > span.getStartTimeUnixNano());
    }

    @Test
    public void testNotEndedHasZeroEndTime() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        assertEquals(0L, span.getEndTimeUnixNano());
        assertFalse(span.isEnded());
    }

    @Test
    public void testEndedFlag() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        assertFalse(span.isEnded());
        span.end();
        assertTrue(span.isEnded());
    }

    // ========================================================================
    // Attribute Tests
    // ========================================================================

    @Test
    public void testAddStringAttribute() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        span.addAttribute("key", "value");
        
        List<Attribute> attrs = span.getAttributes();
        assertEquals(1, attrs.size());
        assertEquals("key", attrs.get(0).getKey());
        assertEquals("value", attrs.get(0).getStringValue());
    }

    @Test
    public void testAddLongAttribute() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        span.addAttribute("count", 42L);
        
        List<Attribute> attrs = span.getAttributes();
        assertEquals(1, attrs.size());
        assertEquals(42L, attrs.get(0).getIntValue());
    }

    @Test
    public void testAddBooleanAttribute() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        span.addAttribute("enabled", true);
        
        List<Attribute> attrs = span.getAttributes();
        assertEquals(1, attrs.size());
        assertTrue(attrs.get(0).getBoolValue());
    }

    @Test
    public void testAddDoubleAttribute() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        span.addAttribute("ratio", 3.14);
        
        List<Attribute> attrs = span.getAttributes();
        assertEquals(1, attrs.size());
        assertEquals(3.14, attrs.get(0).getDoubleValue(), 0.001);
    }

    @Test
    public void testAddAttributeObject() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        Attribute attr = Attribute.string("custom", "value");
        span.addAttribute(attr);
        
        List<Attribute> attrs = span.getAttributes();
        assertEquals(1, attrs.size());
        assertSame(attr, attrs.get(0));
    }

    @Test
    public void testMultipleAttributes() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        span.addAttribute("string", "value");
        span.addAttribute("int", 42L);
        span.addAttribute("bool", true);
        
        assertEquals(3, span.getAttributes().size());
    }

    @Test
    public void testAttributesUnmodifiable() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        span.addAttribute("key", "value");
        
        List<Attribute> attrs = span.getAttributes();
        try {
            attrs.add(Attribute.string("new", "attr"));
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }

    // ========================================================================
    // Event Tests
    // ========================================================================

    @Test
    public void testAddEventByName() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        span.addEvent("event-name");
        
        List<SpanEvent> events = span.getEvents();
        assertEquals(1, events.size());
        assertEquals("event-name", events.get(0).getName());
    }

    @Test
    public void testAddEventObject() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        SpanEvent event = new SpanEvent("custom-event");
        event.addAttribute("key", "value");
        span.addEvent(event);
        
        List<SpanEvent> events = span.getEvents();
        assertEquals(1, events.size());
        assertSame(event, events.get(0));
    }

    @Test
    public void testMultipleEvents() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        span.addEvent("event1");
        span.addEvent("event2");
        span.addEvent("event3");
        
        assertEquals(3, span.getEvents().size());
    }

    // ========================================================================
    // Link Tests
    // ========================================================================

    @Test
    public void testAddLink() {
        byte[] traceId = new byte[16];
        byte[] spanId = new byte[8];
        for (int i = 0; i < 16; i++) traceId[i] = (byte) i;
        for (int i = 0; i < 8; i++) spanId[i] = (byte) (i + 10);
        
        SpanContext linkedCtx = new SpanContext(traceId, spanId, true);
        
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        span.addLink(linkedCtx);
        
        List<SpanLink> links = span.getLinks();
        assertEquals(1, links.size());
    }

    @Test
    public void testAddLinkWithAttributes() {
        byte[] traceId = new byte[16];
        byte[] spanId = new byte[8];
        SpanContext linkedCtx = new SpanContext(traceId, spanId, true);
        
        SpanLink link = new SpanLink(linkedCtx);
        link.addAttribute("link.type", "follows-from");
        
        Trace trace = new Trace("test", SpanKind.SERVER);
        trace.getRootSpan().addLink(link);
        
        List<SpanLink> links = trace.getRootSpan().getLinks();
        assertEquals(1, links.size());
        assertEquals(1, links.get(0).getAttributes().size());
    }

    // ========================================================================
    // Status Tests
    // ========================================================================

    @Test
    public void testDefaultStatus() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        assertEquals(SpanStatus.UNSET, span.getStatus());
    }

    @Test
    public void testSetStatusOk() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        span.setStatusOk();
        
        assertEquals(SpanStatus.OK, span.getStatus());
    }

    @Test
    public void testSetStatusError() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        span.setStatusError("Something went wrong");
        
        assertTrue(span.getStatus().isError());
        assertEquals("Something went wrong", span.getStatus().getMessage());
    }

    @Test
    public void testSetStatusErrorNullMessage() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        span.setStatusError(null);
        
        assertTrue(span.getStatus().isError());
        assertNull(span.getStatus().getMessage());
    }

    // ========================================================================
    // Exception Recording Tests
    // ========================================================================

    @Test
    public void testRecordException() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        RuntimeException ex = new RuntimeException("Test error");
        span.recordException(ex);
        
        // Should have added an event
        List<SpanEvent> events = span.getEvents();
        assertFalse(events.isEmpty());
        
        // Find exception event
        SpanEvent exEvent = null;
        for (SpanEvent e : events) {
            if ("exception".equals(e.getName())) {
                exEvent = e;
                break;
            }
        }
        assertNotNull("Should have exception event", exEvent);
        
        // Check attributes
        List<Attribute> attrs = exEvent.getAttributes();
        assertTrue(attrs.size() >= 2);
    }

    // ========================================================================
    // SpanContext Tests
    // ========================================================================

    @Test
    public void testGetSpanContext() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        SpanContext ctx = span.getSpanContext();
        
        assertNotNull(ctx);
        assertArrayEquals(trace.getTraceId(), ctx.getTraceId());
        assertArrayEquals(span.getSpanId(), ctx.getSpanId());
        assertEquals(trace.isSampled(), ctx.isSampled());
    }

    // ========================================================================
    // toString Tests
    // ========================================================================

    @Test
    public void testToString() {
        Trace trace = new Trace("my-operation", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        String str = span.toString();
        assertTrue(str.contains("Span"));
        assertTrue(str.contains("my-operation"));
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

