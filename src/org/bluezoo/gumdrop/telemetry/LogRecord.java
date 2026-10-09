/*
 * LogRecord.java
 * Copyright (C) 2025, 2026 Chris Burdess
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;

/**
 * One log event. A record has a {@link LogLevel}, a key that names the
 * event ({@code event.name} in OpenTelemetry terms), attributes, and
 * optionally a body, a throwable and the span it happened under.
 *
 * <p>An operational event is built by an {@link EventLogger} and sent
 * with {@link #emit}. Its key is a resource-bundle key, and its
 * attributes, in the order they were added, are the arguments of that
 * bundle string: the default exporter formats the localised sentence
 * from them, while OTLP and JSONL export the key and the attributes as
 * they are, with no lookup, for the collector to format as it likes.
 *
 * <pre>
 * events.warn("warn.server_push_failed").attr("uri", uri).thrown(e).emit();
 * </pre>
 *
 * <p>An access or qlog record is built directly and handed to the
 * exporter by the code that owns that stream.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class LogRecord {

    private final EventLogger logger;
    private final long timeUnixNano;
    private final LogLevel level;
    private final String key;
    private final List<Attribute> attributes;
    private String body;
    private Throwable thrown;
    private TraceId traceId;
    private SpanId spanId;

    /**
     * Creates a record of an event happening now.
     *
     * @param level the level
     * @param key the event name
     */
    public LogRecord(LogLevel level, String key) {
        this(null, System.currentTimeMillis() * 1_000_000L, level, key);
    }

    /**
     * Creates a record of an event that happened at a given time.
     *
     * @param timeUnixNano the time of the event, in nanoseconds since the Unix epoch
     * @param level the level
     * @param key the event name
     */
    public LogRecord(long timeUnixNano, LogLevel level, String key) {
        this(null, timeUnixNano, level, key);
    }

    LogRecord(EventLogger logger, LogLevel level, String key) {
        this(logger, System.currentTimeMillis() * 1_000_000L, level, key);
    }

    private LogRecord(EventLogger logger, long timeUnixNano, LogLevel level, String key) {
        if (level == null) {
            throw new IllegalArgumentException("level");
        }
        if (key == null) {
            throw new IllegalArgumentException("key");
        }
        this.logger = logger;
        this.timeUnixNano = timeUnixNano;
        this.level = level;
        this.key = key;
        this.attributes = new ArrayList<Attribute>();
    }

    // -- Building --

    /**
     * Adds a string attribute. A null value is recorded as the empty
     * string, so that the attribute still occupies its position among
     * the bundle string's arguments.
     *
     * @param name the attribute name
     * @param value the value
     * @return this record
     */
    public LogRecord attr(String name, String value) {
        attributes.add(Attribute.string(name, value == null ? "" : value));
        return this;
    }

    /**
     * Adds an integer attribute.
     *
     * @param name the attribute name
     * @param value the value
     * @return this record
     */
    public LogRecord attr(String name, long value) {
        attributes.add(Attribute.integer(name, value));
        return this;
    }

    /**
     * Adds a boolean attribute.
     *
     * @param name the attribute name
     * @param value the value
     * @return this record
     */
    public LogRecord attr(String name, boolean value) {
        attributes.add(Attribute.bool(name, value));
        return this;
    }

    /**
     * Adds a floating-point attribute.
     *
     * @param name the attribute name
     * @param value the value
     * @return this record
     */
    public LogRecord attr(String name, double value) {
        attributes.add(Attribute.doubleValue(name, value));
        return this;
    }

    /**
     * Adds an attribute.
     *
     * @param attribute the attribute; null is ignored
     * @return this record
     */
    public LogRecord addAttribute(Attribute attribute) {
        if (attribute != null) {
            attributes.add(attribute);
        }
        return this;
    }

    /**
     * Sets the body: text that is the event itself rather than an
     * argument of it, such as a qlog event's data.
     *
     * @param body the body
     * @return this record
     */
    public LogRecord body(String body) {
        this.body = body;
        return this;
    }

    /**
     * Sets the throwable the event reports. Each exporter decides how
     * much of it to pass on.
     *
     * @param thrown the throwable
     * @return this record
     */
    public LogRecord thrown(Throwable thrown) {
        this.thrown = thrown;
        return this;
    }

    /**
     * Correlates the event with a span.
     *
     * @param span the span, or null for none
     * @return this record
     */
    public LogRecord span(Span span) {
        if (span != null) {
            SpanContext context = span.getSpanContext();
            this.traceId = context.getTraceId();
            this.spanId = context.getSpanId();
        }
        return this;
    }

    /**
     * Sends this record to the exporter of the logger that built it.
     *
     * @throws IllegalStateException if the record was not built by an {@link EventLogger}
     */
    public void emit() {
        if (logger == null) {
            throw new IllegalStateException("record was not built by an EventLogger");
        }
        logger.emit(this);
    }

    // -- Reading --

    /**
     * Returns the time of the event in nanoseconds since the Unix epoch.
     */
    public long getTimeUnixNano() {
        return timeUnixNano;
    }

    /**
     * Returns the level.
     */
    public LogLevel getLevel() {
        return level;
    }

    /**
     * Returns the OTLP severity number of the level.
     */
    public int getSeverityNumber() {
        return level.getSeverityNumber();
    }

    /**
     * Returns the OTLP severity text of the level.
     */
    public String getSeverityText() {
        return level.getSeverityText();
    }

    /**
     * Returns the event name: for an operational event, the resource-bundle key.
     */
    public String getKey() {
        return key;
    }

    /**
     * Returns the body, or null if the event has none.
     */
    public String getBody() {
        return body;
    }

    /**
     * Returns the throwable the event reports, or null.
     */
    public Throwable getThrown() {
        return thrown;
    }

    /**
     * Returns the instrumentation scope: the name of the class that
     * emitted the event, or null for a record built outside a logger.
     */
    public String getScope() {
        return logger == null ? null : logger.getScope();
    }

    /**
     * Returns the resource bundle the key is looked up in, or null for a
     * record built outside a logger.
     */
    public ResourceBundle getResourceBundle() {
        return logger == null ? null : logger.getResourceBundle();
    }

    /**
     * Returns the trace ID, or null if not correlated.
     */
    public TraceId getTraceId() {
        return traceId;
    }

    /**
     * Returns the span ID, or null if not correlated.
     */
    public SpanId getSpanId() {
        return spanId;
    }

    /**
     * Returns true if this record is correlated with a span.
     */
    public boolean hasSpanContext() {
        return traceId != null && spanId != null;
    }

    /**
     * Returns an unmodifiable view of the attributes, in the order they were added.
     */
    public List<Attribute> getAttributes() {
        return Collections.unmodifiableList(attributes);
    }

    /**
     * Returns the attributes an exporter writes: the record's own, and
     * for a record with a throwable the OpenTelemetry exception
     * attributes. {@code exception.type} is always among them;
     * {@code exception.message} and {@code exception.stacktrace} only
     * when the exporter is configured to include exception details,
     * since a message or a stack trace may carry what should not leave
     * the host.
     *
     * @param includeExceptionDetails whether to add the message and stack trace
     * @return the attributes, in order
     */
    public List<Attribute> exportAttributes(boolean includeExceptionDetails) {
        if (thrown == null) {
            return getAttributes();
        }
        List<Attribute> result = new ArrayList<Attribute>(attributes.size() + 3);
        result.addAll(attributes);
        result.add(Attribute.string("exception.type", thrown.getClass().getName()));
        if (includeExceptionDetails) {
            if (thrown.getMessage() != null) {
                result.add(Attribute.string("exception.message", thrown.getMessage()));
            }
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement element : thrown.getStackTrace()) {
                sb.append(element.toString()).append('\n');
            }
            if (sb.length() > 0) {
                result.add(Attribute.string("exception.stacktrace", sb.toString()));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns the first attribute with a name.
     *
     * @param name the attribute name
     * @return the attribute, or null if there is none
     */
    public Attribute getAttribute(String name) {
        for (Attribute attribute : attributes) {
            if (attribute.getKey().equals(name)) {
                return attribute;
            }
        }
        return null;
    }

    /**
     * Returns the value of a string attribute.
     *
     * @param name the attribute name
     * @return the value, or null if there is no string attribute of that name
     */
    public String getString(String name) {
        Attribute attribute = getAttribute(name);
        return attribute != null && attribute.getType() == Attribute.TYPE_STRING
                ? attribute.getStringValue() : null;
    }

    @Override
    public String toString() {
        return "LogRecord[" + level + ": " + key + "]";
    }

}
