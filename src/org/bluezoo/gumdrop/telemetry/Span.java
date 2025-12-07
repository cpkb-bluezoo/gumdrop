/*
 * Span.java
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
 * Represents a single operation within a trace.
 * A span has a name, timing information, attributes, events, and links.
 * Spans form a tree structure within a trace, with parent-child relationships.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Span {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Trace trace;
    private final Span parent;
    private final byte[] spanId;
    private final String name;
    private final SpanKind kind;
    private final long startTimeUnixNano;

    private long endTimeUnixNano;
    private SpanStatus status;
    private boolean ended;

    private final List<Attribute> attributes;
    private final List<SpanEvent> events;
    private final List<SpanLink> links;
    private final List<Span> children;

    /**
     * Creates a new span.
     *
     * @param trace the owning trace
     * @param parent the parent span, or null for root span
     * @param name the span name
     * @param kind the span kind
     */
    Span(Trace trace, Span parent, String name, SpanKind kind) {
        if (trace == null) {
            throw new IllegalArgumentException("trace cannot be null");
        }
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null");
        }
        this.trace = trace;
        this.parent = parent;
        this.spanId = generateSpanId();
        this.name = name;
        this.kind = kind != null ? kind : SpanKind.INTERNAL;
        this.startTimeUnixNano = System.currentTimeMillis() * 1_000_000L;
        this.status = SpanStatus.UNSET;
        this.ended = false;

        this.attributes = new ArrayList<Attribute>();
        this.events = new ArrayList<SpanEvent>();
        this.links = new ArrayList<SpanLink>();
        this.children = new ArrayList<Span>();

        if (parent != null) {
            parent.addChild(this);
        }
    }

    /**
     * Creates a root span with an existing span ID.
     * Used when continuing a trace from a remote context.
     *
     * @param trace the owning trace
     * @param spanId the span ID
     * @param name the span name
     * @param kind the span kind
     */
    Span(Trace trace, byte[] spanId, String name, SpanKind kind) {
        if (trace == null) {
            throw new IllegalArgumentException("trace cannot be null");
        }
        if (spanId == null || spanId.length != SpanContext.SPAN_ID_LENGTH) {
            throw new IllegalArgumentException("spanId must be 8 bytes");
        }
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null");
        }
        this.trace = trace;
        this.parent = null;
        this.spanId = new byte[SpanContext.SPAN_ID_LENGTH];
        System.arraycopy(spanId, 0, this.spanId, 0, SpanContext.SPAN_ID_LENGTH);
        this.name = name;
        this.kind = kind != null ? kind : SpanKind.INTERNAL;
        this.startTimeUnixNano = System.currentTimeMillis() * 1_000_000L;
        this.status = SpanStatus.UNSET;
        this.ended = false;

        this.attributes = new ArrayList<Attribute>();
        this.events = new ArrayList<SpanEvent>();
        this.links = new ArrayList<SpanLink>();
        this.children = new ArrayList<Span>();
    }

    private static byte[] generateSpanId() {
        byte[] id = new byte[SpanContext.SPAN_ID_LENGTH];
        RANDOM.nextBytes(id);
        return id;
    }

    private void addChild(Span child) {
        children.add(child);
    }

    /**
     * Returns the owning trace.
     */
    public Trace getTrace() {
        return trace;
    }

    /**
     * Returns the parent span, or null if this is the root span.
     */
    public Span getParent() {
        return parent;
    }

    /**
     * Returns a copy of the span ID bytes.
     */
    public byte[] getSpanId() {
        byte[] copy = new byte[SpanContext.SPAN_ID_LENGTH];
        System.arraycopy(spanId, 0, copy, 0, SpanContext.SPAN_ID_LENGTH);
        return copy;
    }

    /**
     * Returns the span name.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the span kind.
     */
    public SpanKind getKind() {
        return kind;
    }

    /**
     * Returns the start time in nanoseconds since Unix epoch.
     */
    public long getStartTimeUnixNano() {
        return startTimeUnixNano;
    }

    /**
     * Returns the end time in nanoseconds since Unix epoch.
     * Returns 0 if the span has not ended.
     */
    public long getEndTimeUnixNano() {
        return endTimeUnixNano;
    }

    /**
     * Returns the span status.
     */
    public SpanStatus getStatus() {
        return status;
    }

    /**
     * Returns true if this span has ended.
     */
    public boolean isEnded() {
        return ended;
    }

    /**
     * Returns an unmodifiable view of the span attributes.
     */
    public List<Attribute> getAttributes() {
        return Collections.unmodifiableList(attributes);
    }

    /**
     * Returns an unmodifiable view of the span events.
     */
    public List<SpanEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /**
     * Returns an unmodifiable view of the span links.
     */
    public List<SpanLink> getLinks() {
        return Collections.unmodifiableList(links);
    }

    /**
     * Returns an unmodifiable view of the child spans.
     */
    public List<Span> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /**
     * Returns the span context for this span.
     */
    public SpanContext getSpanContext() {
        return new SpanContext(trace.getTraceId(), spanId, trace.isSampled());
    }

    // -- Mutators --

    /**
     * Sets the span status.
     *
     * @param status the status
     * @return this span for chaining
     */
    public Span setStatus(SpanStatus status) {
        if (!ended && status != null) {
            this.status = status;
        }
        return this;
    }

    /**
     * Sets the span status to OK.
     *
     * @return this span for chaining
     */
    public Span setStatusOk() {
        return setStatus(SpanStatus.OK);
    }

    /**
     * Sets the span status to ERROR with a message.
     *
     * @param message the error message
     * @return this span for chaining
     */
    public Span setStatusError(String message) {
        return setStatus(SpanStatus.error(message));
    }

    /**
     * Adds an attribute to this span.
     *
     * @param attribute the attribute
     * @return this span for chaining
     */
    public Span addAttribute(Attribute attribute) {
        if (!ended && attribute != null) {
            attributes.add(attribute);
        }
        return this;
    }

    /**
     * Adds a string attribute to this span.
     *
     * @param key the attribute key
     * @param value the attribute value
     * @return this span for chaining
     */
    public Span addAttribute(String key, String value) {
        return addAttribute(Attribute.string(key, value));
    }

    /**
     * Adds a boolean attribute to this span.
     *
     * @param key the attribute key
     * @param value the attribute value
     * @return this span for chaining
     */
    public Span addAttribute(String key, boolean value) {
        return addAttribute(Attribute.bool(key, value));
    }

    /**
     * Adds an integer attribute to this span.
     *
     * @param key the attribute key
     * @param value the attribute value
     * @return this span for chaining
     */
    public Span addAttribute(String key, long value) {
        return addAttribute(Attribute.integer(key, value));
    }

    /**
     * Adds a double attribute to this span.
     *
     * @param key the attribute key
     * @param value the attribute value
     * @return this span for chaining
     */
    public Span addAttribute(String key, double value) {
        return addAttribute(Attribute.doubleValue(key, value));
    }

    /**
     * Adds an event to this span.
     *
     * @param event the event
     * @return this span for chaining
     */
    public Span addEvent(SpanEvent event) {
        if (!ended && event != null) {
            events.add(event);
        }
        return this;
    }

    /**
     * Adds a named event to this span.
     *
     * @param name the event name
     * @return this span for chaining
     */
    public Span addEvent(String name) {
        return addEvent(new SpanEvent(name));
    }

    /**
     * Adds a link to this span.
     *
     * @param link the link
     * @return this span for chaining
     */
    public Span addLink(SpanLink link) {
        if (!ended && link != null) {
            links.add(link);
        }
        return this;
    }

    /**
     * Adds a link to another span context.
     *
     * @param context the span context to link to
     * @return this span for chaining
     */
    public Span addLink(SpanContext context) {
        return addLink(new SpanLink(context));
    }

    /**
     * Starts a new child span.
     *
     * @param name the child span name
     * @return the new child span
     */
    public Span startChild(String name) {
        return startChild(name, SpanKind.INTERNAL);
    }

    /**
     * Starts a new child span with the specified kind.
     *
     * @param name the child span name
     * @param kind the span kind
     * @return the new child span
     */
    public Span startChild(String name, SpanKind kind) {
        Span child = new Span(trace, this, name, kind);
        trace.setCurrentSpan(child);
        return child;
    }

    /**
     * Records an exception on this span.
     * Adds an event with exception details and sets the status to ERROR.
     *
     * @param exception the exception
     * @return this span for chaining
     */
    public Span recordException(Throwable exception) {
        if (ended || exception == null) {
            return this;
        }
        SpanEvent event = new SpanEvent("exception");
        event.addAttribute("exception.type", exception.getClass().getName());
        if (exception.getMessage() != null) {
            event.addAttribute("exception.message", exception.getMessage());
        }
        addEvent(event);
        setStatusError(exception.getMessage());
        return this;
    }

    /**
     * Records an exception with an error category on this span.
     * Adds standardized error attributes for cross-protocol analysis.
     *
     * @param exception the exception
     * @param category the error category
     * @return this span for chaining
     */
    public Span recordException(Throwable exception, ErrorCategory category) {
        if (ended || exception == null) {
            return this;
        }
        SpanEvent event = new SpanEvent("exception");
        event.addAttribute("exception.type", exception.getClass().getName());
        if (exception.getMessage() != null) {
            event.addAttribute("exception.message", exception.getMessage());
        }
        addEvent(event);

        // Add standardized error attributes
        if (category != null) {
            addAttribute("error.category", category.getCode());
        }

        setStatusError(exception.getMessage());
        return this;
    }

    /**
     * Records an error condition with category and protocol-specific code.
     * This method provides comprehensive error information for telemetry.
     *
     * @param category the error category
     * @param protocolCode the protocol-specific error code (e.g., HTTP 404, SMTP 550)
     * @param message the error message
     * @return this span for chaining
     */
    public Span recordError(ErrorCategory category, int protocolCode, String message) {
        if (ended) {
            return this;
        }
        SpanEvent event = new SpanEvent("error");
        if (message != null) {
            event.addAttribute("error.message", message);
        }
        if (category != null) {
            event.addAttribute("error.category", category.getCode());
        }
        event.addAttribute("error.code", String.valueOf(protocolCode));
        addEvent(event);

        // Add span-level attributes for filtering/querying
        if (category != null) {
            addAttribute("error.category", category.getCode());
        }
        addAttribute("error.code", (long) protocolCode);

        setStatusError(message != null ? message : category.getCode());
        return this;
    }

    /**
     * Records an error condition with category only (no protocol code).
     *
     * @param category the error category
     * @param message the error message
     * @return this span for chaining
     */
    public Span recordError(ErrorCategory category, String message) {
        if (ended) {
            return this;
        }
        SpanEvent event = new SpanEvent("error");
        if (message != null) {
            event.addAttribute("error.message", message);
        }
        if (category != null) {
            event.addAttribute("error.category", category.getCode());
            addAttribute("error.category", category.getCode());
        }
        addEvent(event);

        setStatusError(message != null ? message : (category != null ? category.getCode() : "error"));
        return this;
    }

    /**
     * Records an exception with automatic category detection.
     * Uses {@link ErrorCategory#fromException} to classify the exception.
     *
     * @param exception the exception
     * @return this span for chaining
     */
    public Span recordExceptionWithCategory(Throwable exception) {
        if (exception == null) {
            return this;
        }
        ErrorCategory category = ErrorCategory.fromException(exception);
        return recordException(exception, category);
    }

    /**
     * Ends this span with the current time.
     * After a span is ended, no more modifications can be made.
     */
    public void end() {
        if (!ended) {
            endTimeUnixNano = System.currentTimeMillis() * 1_000_000L;
            ended = true;
            trace.spanEnded(this);
        }
    }

    /**
     * Makes this span the current span in the trace.
     * Returns a scope that will restore the previous current span when closed.
     *
     * @return a scope object
     */
    public SpanScope makeCurrent() {
        Span previous = trace.getCurrentSpan();
        trace.setCurrentSpan(this);
        return new SpanScope(trace, previous);
    }

    @Override
    public String toString() {
        return "Span[" + name + ", " + getSpanContext().getSpanIdHex() + "]";
    }

    /**
     * A scope that restores the previous current span when closed.
     */
    public static class SpanScope {

        private final Trace trace;
        private final Span previous;
        private boolean closed;

        SpanScope(Trace trace, Span previous) {
            this.trace = trace;
            this.previous = previous;
            this.closed = false;
        }

        /**
         * Closes this scope and restores the previous current span.
         */
        public void close() {
            if (!closed) {
                trace.setCurrentSpan(previous);
                closed = true;
            }
        }
    }

}

