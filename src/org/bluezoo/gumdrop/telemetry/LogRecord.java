/*
 * LogRecord.java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A log record that can be associated with a span.
 * In OpenTelemetry, logs contain traceId and spanId for correlation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LogRecord {

    /**
     * Severity number values matching OTLP SeverityNumber enum.
     */
    public static final int SEVERITY_TRACE = 1;
    public static final int SEVERITY_DEBUG = 5;
    public static final int SEVERITY_INFO = 9;
    public static final int SEVERITY_WARN = 13;
    public static final int SEVERITY_ERROR = 17;
    public static final int SEVERITY_FATAL = 21;

    private final long timeUnixNano;
    private final int severityNumber;
    private final String severityText;
    private final String body;
    private final byte[] traceId;
    private final byte[] spanId;
    private final List<Attribute> attributes;

    /**
     * Creates a log record from the current span context.
     *
     * @param span the span to correlate with
     * @param severityNumber the severity level
     * @param body the log message
     */
    public LogRecord(Span span, int severityNumber, String body) {
        this.timeUnixNano = System.currentTimeMillis() * 1_000_000L;
        this.severityNumber = severityNumber;
        this.severityText = getSeverityText(severityNumber);
        this.body = body;
        this.attributes = new ArrayList<Attribute>();

        if (span != null) {
            SpanContext context = span.getSpanContext();
            this.traceId = context.getTraceId();
            this.spanId = context.getSpanId();
        } else {
            this.traceId = null;
            this.spanId = null;
        }
    }

    /**
     * Creates a log record without span correlation.
     *
     * @param severityNumber the severity level
     * @param body the log message
     */
    public LogRecord(int severityNumber, String body) {
        this(null, severityNumber, body);
    }

    private static String getSeverityText(int severityNumber) {
        if (severityNumber <= SEVERITY_TRACE) {
            return "TRACE";
        } else if (severityNumber <= SEVERITY_DEBUG) {
            return "DEBUG";
        } else if (severityNumber <= SEVERITY_INFO) {
            return "INFO";
        } else if (severityNumber <= SEVERITY_WARN) {
            return "WARN";
        } else if (severityNumber <= SEVERITY_ERROR) {
            return "ERROR";
        } else {
            return "FATAL";
        }
    }

    /**
     * Returns the timestamp in nanoseconds since Unix epoch.
     */
    public long getTimeUnixNano() {
        return timeUnixNano;
    }

    /**
     * Returns the severity number.
     */
    public int getSeverityNumber() {
        return severityNumber;
    }

    /**
     * Returns the severity text.
     */
    public String getSeverityText() {
        return severityText;
    }

    /**
     * Returns the log body.
     */
    public String getBody() {
        return body;
    }

    /**
     * Returns a copy of the trace ID bytes, or null if not correlated.
     */
    public byte[] getTraceId() {
        if (traceId == null) {
            return null;
        }
        byte[] copy = new byte[traceId.length];
        System.arraycopy(traceId, 0, copy, 0, traceId.length);
        return copy;
    }

    /**
     * Returns a copy of the span ID bytes, or null if not correlated.
     */
    public byte[] getSpanId() {
        if (spanId == null) {
            return null;
        }
        byte[] copy = new byte[spanId.length];
        System.arraycopy(spanId, 0, copy, 0, spanId.length);
        return copy;
    }

    /**
     * Returns true if this log is correlated with a span.
     */
    public boolean hasSpanContext() {
        return traceId != null && spanId != null;
    }

    /**
     * Returns an unmodifiable view of the log attributes.
     */
    public List<Attribute> getAttributes() {
        return Collections.unmodifiableList(attributes);
    }

    /**
     * Adds an attribute to this log record.
     *
     * @param attribute the attribute
     * @return this record for chaining
     */
    public LogRecord addAttribute(Attribute attribute) {
        if (attribute != null) {
            attributes.add(attribute);
        }
        return this;
    }

    /**
     * Adds a string attribute to this log record.
     *
     * @param key the attribute key
     * @param value the attribute value
     * @return this record for chaining
     */
    public LogRecord addAttribute(String key, String value) {
        return addAttribute(Attribute.string(key, value));
    }

    /**
     * Creates an INFO level log record.
     *
     * @param span the span to correlate with
     * @param body the log message
     * @return a new log record
     */
    public static LogRecord info(Span span, String body) {
        return new LogRecord(span, SEVERITY_INFO, body);
    }

    /**
     * Creates a WARN level log record.
     *
     * @param span the span to correlate with
     * @param body the log message
     * @return a new log record
     */
    public static LogRecord warn(Span span, String body) {
        return new LogRecord(span, SEVERITY_WARN, body);
    }

    /**
     * Creates an ERROR level log record.
     *
     * @param span the span to correlate with
     * @param body the log message
     * @return a new log record
     */
    public static LogRecord error(Span span, String body) {
        return new LogRecord(span, SEVERITY_ERROR, body);
    }

    @Override
    public String toString() {
        return "LogRecord[" + severityText + ": " + body + "]";
    }

}

