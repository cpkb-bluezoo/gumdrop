/*
 * Trace.java
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

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a distributed trace.
 * A trace contains a tree of spans representing operations across services.
 * The trace ID is a 128-bit identifier shared by all spans in the trace.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Trace {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] traceId;
    private final boolean sampled;
    private final Span rootSpan;
    private Span currentSpan;

    private final List<Span> endedSpans;
    private TelemetryExporter exporter;

    /**
     * Creates a new trace with a generated trace ID and a root span.
     *
     * @param rootSpanName the name for the root span
     */
    public Trace(String rootSpanName) {
        this(rootSpanName, SpanKind.SERVER);
    }

    /**
     * Creates a new trace with a generated trace ID and a root span.
     *
     * @param rootSpanName the name for the root span
     * @param kind the kind for the root span
     */
    public Trace(String rootSpanName, SpanKind kind) {
        this.traceId = generateTraceId();
        this.sampled = true;
        this.endedSpans = new ArrayList<Span>();
        this.rootSpan = new Span(this, (Span) null, rootSpanName, kind);
        this.currentSpan = rootSpan;
    }

    /**
     * Creates a trace continuing from a remote span context.
     * The new root span will be a child of the remote span.
     *
     * @param parentContext the remote parent span context
     * @param rootSpanName the name for the local root span
     * @param kind the kind for the root span
     */
    public Trace(SpanContext parentContext, String rootSpanName, SpanKind kind) {
        if (parentContext == null) {
            throw new IllegalArgumentException("parentContext cannot be null");
        }
        this.traceId = parentContext.getTraceId();
        this.sampled = parentContext.isSampled();
        this.endedSpans = new ArrayList<Span>();
        // Create root span with parent's span ID to continue the trace
        this.rootSpan = new Span(this, parentContext.getSpanId(), rootSpanName, kind);
        this.currentSpan = rootSpan;
    }

    /**
     * Creates a trace from a W3C traceparent header.
     * If the header is invalid, creates a new trace instead.
     *
     * @param traceparent the traceparent header value
     * @param rootSpanName the name for the local root span
     * @return a new trace
     */
    public static Trace fromTraceparent(String traceparent, String rootSpanName) {
        return fromTraceparent(traceparent, rootSpanName, SpanKind.SERVER);
    }

    /**
     * Creates a trace from a W3C traceparent header.
     * If the header is invalid, creates a new trace instead.
     *
     * @param traceparent the traceparent header value
     * @param rootSpanName the name for the local root span
     * @param kind the kind for the root span
     * @return a new trace
     */
    public static Trace fromTraceparent(String traceparent, String rootSpanName, SpanKind kind) {
        SpanContext context = SpanContext.fromTraceparent(traceparent);
        if (context != null) {
            return new Trace(context, rootSpanName, kind);
        }
        return new Trace(rootSpanName, kind);
    }

    private static byte[] generateTraceId() {
        byte[] id = new byte[SpanContext.TRACE_ID_LENGTH];
        RANDOM.nextBytes(id);
        return id;
    }

    /**
     * Returns a copy of the trace ID bytes.
     */
    public byte[] getTraceId() {
        byte[] copy = new byte[SpanContext.TRACE_ID_LENGTH];
        System.arraycopy(traceId, 0, copy, 0, SpanContext.TRACE_ID_LENGTH);
        return copy;
    }

    /**
     * Returns the trace ID as a lowercase hexadecimal string.
     */
    public String getTraceIdHex() {
        return bytesToHex(traceId);
    }

    /**
     * Returns true if this trace is sampled.
     */
    public boolean isSampled() {
        return sampled;
    }

    /**
     * Returns the root span of this trace.
     */
    public Span getRootSpan() {
        return rootSpan;
    }

    /**
     * Returns the current active span.
     */
    public Span getCurrentSpan() {
        return currentSpan;
    }

    /**
     * Sets the current active span.
     *
     * @param span the span to make current
     */
    void setCurrentSpan(Span span) {
        this.currentSpan = span;
    }

    /**
     * Returns the exporter configured for this trace.
     */
    public TelemetryExporter getExporter() {
        return exporter;
    }

    /**
     * Sets the exporter for this trace.
     *
     * @param exporter the exporter
     */
    public void setExporter(TelemetryExporter exporter) {
        this.exporter = exporter;
    }

    /**
     * Called when a span ends.
     *
     * @param span the span that ended
     */
    void spanEnded(Span span) {
        endedSpans.add(span);
        // If the current span ended, move to its parent
        if (currentSpan == span) {
            currentSpan = span.getParent();
            if (currentSpan == null) {
                currentSpan = rootSpan;
            }
        }
    }

    /**
     * Returns an unmodifiable list of all ended spans.
     */
    public List<Span> getEndedSpans() {
        return Collections.unmodifiableList(endedSpans);
    }

    /**
     * Starts a new child span under the current span.
     *
     * @param name the span name
     * @return the new span
     */
    public Span startSpan(String name) {
        return startSpan(name, SpanKind.INTERNAL);
    }

    /**
     * Starts a new child span under the current span.
     *
     * @param name the span name
     * @param kind the span kind
     * @return the new span
     */
    public Span startSpan(String name, SpanKind kind) {
        return currentSpan.startChild(name, kind);
    }

    /**
     * Adds an event to the current span.
     *
     * @param name the event name
     * @return the current span for chaining
     */
    public Span addEvent(String name) {
        return currentSpan.addEvent(name);
    }

    /**
     * Adds an attribute to the current span.
     *
     * @param key the attribute key
     * @param value the attribute value
     * @return the current span for chaining
     */
    public Span addAttribute(String key, String value) {
        return currentSpan.addAttribute(key, value);
    }

    /**
     * Records an exception on the current span.
     *
     * @param exception the exception
     * @return the current span for chaining
     */
    public Span recordException(Throwable exception) {
        return currentSpan.recordException(exception);
    }

    /**
     * Returns the W3C traceparent header value for the current span.
     */
    public String getTraceparent() {
        return currentSpan.getSpanContext().toTraceparent();
    }

    /**
     * Ends the trace by ending the root span and exporting if configured.
     */
    public void end() {
        if (!rootSpan.isEnded()) {
            rootSpan.end();
        }
        if (exporter != null && sampled) {
            exporter.export(this);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            result[i * 2] = hexChars[v >>> 4];
            result[i * 2 + 1] = hexChars[v & 0x0F];
        }
        return new String(result);
    }

    @Override
    public String toString() {
        return "Trace[" + getTraceIdHex() + ", spans=" + endedSpans.size() + "]";
    }

}

