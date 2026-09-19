/*
 * SpanErrorRecordingTest.java
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

import java.net.ConnectException;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link Span} error recording and scoping, and for the
 * identifier and event value types.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SpanErrorRecordingTest {

    private static Attribute find(Span span, String key) {
        for (Attribute a : span.getAttributes()) {
            if (a.getKey().equals(key)) {
                return a;
            }
        }
        return null;
    }

    private static Attribute find(SpanEvent event, String key) {
        for (Attribute a : event.getAttributes()) {
            if (a.getKey().equals(key)) {
                return a;
            }
        }
        return null;
    }

    @Test
    public void recordExceptionWithoutDetailsHidesMessageAndStack() {
        Trace trace = new Trace("root");
        Span span = trace.getRootSpan();
        span.recordException(new IllegalStateException("secret"));
        SpanEvent event = span.getEvents().get(0);
        assertEquals("exception", event.getName());
        assertEquals("java.lang.IllegalStateException", find(event, "exception.type").getStringValue());
        assertNull(find(event, "exception.message"));
        assertNull(find(event, "exception.stacktrace"));
        assertTrue(span.getStatus().isError());
        assertEquals("java.lang.IllegalStateException", span.getStatus().getMessage());
    }

    @Test
    public void recordExceptionWithDetailsIncludesMessageAndStack() {
        Trace trace = new Trace("root");
        trace.setIncludeExceptionDetails(true);
        Span span = trace.getRootSpan();
        span.recordException(new IllegalStateException("visible"));
        SpanEvent event = span.getEvents().get(0);
        assertEquals("visible", find(event, "exception.message").getStringValue());
        assertNotNull(find(event, "exception.stacktrace"));
        assertEquals("visible", span.getStatus().getMessage());
    }

    @Test
    public void recordExceptionIgnoresNullAndEndedSpans() {
        Trace trace = new Trace("root");
        Span span = trace.getRootSpan();
        span.recordException(null);
        assertTrue(span.getEvents().isEmpty());
        span.end();
        span.recordException(new RuntimeException());
        assertTrue(span.getEvents().isEmpty());
    }

    @Test
    public void recordExceptionWithCategoryTagsSpan() {
        Trace trace = new Trace("root");
        trace.setIncludeExceptionDetails(true);
        Span span = trace.getRootSpan();
        span.recordException(new RuntimeException("x"), ErrorCategory.TIMEOUT);
        assertEquals("timeout", find(span, "error.category").getStringValue());

        Span second = trace.startSpan("second");
        second.recordException(new RuntimeException("y"), null);
        assertNull(find(second, "error.category"));
        assertEquals(1, second.getEvents().size());
    }

    @Test
    public void recordExceptionCategoryIgnoredWhenEndedOrNull() {
        Trace trace = new Trace("root");
        Span span = trace.getRootSpan();
        span.recordException(null, ErrorCategory.TIMEOUT);
        span.end();
        span.recordException(new RuntimeException(), ErrorCategory.TIMEOUT);
        assertTrue(span.getEvents().isEmpty());
    }

    @Test
    public void recordExceptionWithCategoryInfersCategory() {
        Trace trace = new Trace("root");
        Span span = trace.getRootSpan();
        span.recordExceptionWithCategory(new ConnectException("refused"));
        assertEquals("connection", find(span, "error.category").getStringValue());
        span.recordExceptionWithCategory(null);
        assertEquals(1, span.getEvents().size());
    }

    @Test
    public void recordErrorWithProtocolCode() {
        Trace trace = new Trace("root");
        Span span = trace.getRootSpan();
        span.recordError(ErrorCategory.AUTHENTICATION_FAILED, 535, "bad creds");
        SpanEvent event = span.getEvents().get(0);
        assertEquals("error", event.getName());
        assertEquals("bad creds", find(event, "error.message").getStringValue());
        assertEquals("auth_failed", find(event, "error.category").getStringValue());
        assertEquals("535", find(event, "error.code").getStringValue());
        assertEquals(535L, find(span, "error.code").getIntValue());
        assertEquals("bad creds", span.getStatus().getMessage());
    }

    @Test
    public void recordErrorWithCodeAndNullMessage() {
        Trace trace = new Trace("root");
        Span span = trace.getRootSpan();
        span.recordError(ErrorCategory.NOT_FOUND, 550, null);
        assertNull(find(span.getEvents().get(0), "error.message"));
        assertEquals("not_found", span.getStatus().getMessage());
    }

    @Test
    public void recordErrorWithCodeIgnoredWhenEnded() {
        Trace trace = new Trace("root");
        Span span = trace.getRootSpan();
        span.end();
        span.recordError(ErrorCategory.NOT_FOUND, 550, "x");
        span.recordError(ErrorCategory.NOT_FOUND, "x");
        assertTrue(span.getEvents().isEmpty());
    }

    @Test
    public void recordErrorWithoutCode() {
        Trace trace = new Trace("root");
        Span span = trace.getRootSpan();
        span.recordError(ErrorCategory.LIMIT_EXCEEDED, "too big");
        SpanEvent event = span.getEvents().get(0);
        assertEquals("too big", find(event, "error.message").getStringValue());
        assertEquals("limit_exceeded", find(span, "error.category").getStringValue());
        assertEquals("too big", span.getStatus().getMessage());

        Span other = trace.startSpan("other");
        other.recordError(null, null);
        assertEquals("error", other.getStatus().getMessage());
        Span third = trace.startSpan("third");
        third.recordError(ErrorCategory.TIMEOUT, null);
        assertEquals("timeout", third.getStatus().getMessage());
    }

    @Test
    public void makeCurrentRestoresPreviousSpanOnClose() {
        Trace trace = new Trace("root");
        Span root = trace.getRootSpan();
        Span child = root.startChild("child");
        Span before = trace.getCurrentSpan();
        Span.SpanScope scope = root.makeCurrent();
        assertSame(root, trace.getCurrentSpan());
        scope.close();
        assertSame(before, trace.getCurrentSpan());
        scope.close();
        assertSame(before, trace.getCurrentSpan());
        assertNotNull(child);
    }

    @Test
    public void startChildDefaultsToInternalKind() {
        Trace trace = new Trace("root");
        Span child = trace.getRootSpan().startChild("c");
        assertEquals(SpanKind.INTERNAL, child.getKind());
        assertSame(trace.getRootSpan(), child.getParent());
    }

    @Test
    public void mutationsIgnoredAfterEnd() {
        Trace trace = new Trace("root");
        Span span = trace.getRootSpan();
        span.end();
        span.setStatusOk();
        span.addAttribute("k", "v");
        span.addEvent("e");
        span.addLink(new SpanContext(trace.getTraceId(), SpanId.generate(), 0));
        assertNull(find(span, "k"));
        assertTrue(span.getEvents().isEmpty());
        assertTrue(span.getLinks().isEmpty());
    }

    @Test
    public void spanContextReflectsSampling() {
        Trace trace = new Trace("root");
        SpanContext context = trace.getRootSpan().getSpanContext();
        assertEquals(trace.getTraceId(), context.getTraceId());
    }

    @Test
    public void spanIdAndTraceIdValueSemantics() {
        byte[] eight = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
        SpanId a = new SpanId(eight);
        SpanId b = new SpanId(eight);
        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(null));
        assertFalse(a.equals("x"));
        assertEquals("0102030405060708", a.toString());
        assertArrayEquals(eight, a.getBytes());
        ByteBuffer buf = ByteBuffer.allocate(8);
        a.writeTo(buf);
        assertArrayEquals(eight, buf.array());

        byte[] sixteen = new byte[16];
        sixteen[15] = 9;
        TraceId t = new TraceId(sixteen);
        TraceId u = new TraceId(sixteen);
        assertEquals(t, t);
        assertEquals(t, u);
        assertEquals(t.hashCode(), u.hashCode());
        assertFalse(t.equals(null));
        assertFalse(t.equals("x"));
        assertEquals("00000000000000000000000000000009", t.toString());
        ByteBuffer tb = ByteBuffer.allocate(16);
        t.writeTo(tb);
        assertArrayEquals(sixteen, tb.array());
    }

    @Test(expected = IllegalArgumentException.class)
    public void spanIdRejectsWrongLength() {
        new SpanId(new byte[3]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void traceIdRejectsWrongLength() {
        new TraceId(new byte[3]);
    }

    @Test
    public void spanEventTypedAttributesAndToString() {
        SpanEvent event = new SpanEvent("e", 5L);
        event.addAttribute("b", true);
        event.addAttribute("i", 7L);
        event.addAttribute("d", 1.5d);
        event.addAttribute((Attribute) null);
        assertEquals(3, event.getAttributes().size());
        assertTrue(find(event, "b").getBoolValue());
        assertEquals(7L, find(event, "i").getIntValue());
        assertEquals(1.5d, find(event, "d").getDoubleValue(), 0.0);
        assertEquals("SpanEvent[e at 5]", event.toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void spanEventRequiresName() {
        new SpanEvent(null);
    }

    @Test
    public void spanLinkAttributesAndToString() {
        Trace trace = new Trace("root");
        SpanLink link = new SpanLink(trace.getRootSpan().getSpanContext());
        link.addAttribute("k", "v");
        link.addAttribute((Attribute) null);
        assertEquals(1, link.getAttributes().size());
        assertTrue(link.toString().startsWith("SpanLink["));
    }

    @Test(expected = IllegalArgumentException.class)
    public void spanLinkRequiresContext() {
        new SpanLink(null);
    }
}
