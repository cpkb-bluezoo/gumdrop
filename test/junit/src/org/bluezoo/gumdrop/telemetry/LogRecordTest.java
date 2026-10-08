/*
 * LogRecordTest.java
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LogRecordTest {

    @Test
    public void recordOfNowIsStampedNow() {
        long before = System.currentTimeMillis() * 1_000_000L;
        LogRecord r = new LogRecord(LogLevel.INFO, "info.started");
        long after = System.currentTimeMillis() * 1_000_000L;
        assertTrue(r.getTimeUnixNano() >= before);
        assertTrue(r.getTimeUnixNano() <= after);
        assertEquals(LogLevel.INFO, r.getLevel());
        assertEquals("info.started", r.getKey());
        assertNull(r.getBody());
        assertNull(r.getThrown());
        assertNull(r.getScope());
        assertNull(r.getResourceBundle());
        assertTrue(r.getAttributes().isEmpty());
    }

    @Test
    public void recordKeepsTheTimeItIsGiven() {
        LogRecord r = new LogRecord(123_456_789L, LogLevel.QLOG, "quic:packet_sent");
        assertEquals(123_456_789L, r.getTimeUnixNano());
    }

    @Test
    public void levelGivesTheOtlpSeverity() {
        assertEquals(9, new LogRecord(LogLevel.INFO, "k").getSeverityNumber());
        assertEquals("INFO", new LogRecord(LogLevel.INFO, "k").getSeverityText());
        assertEquals(13, new LogRecord(LogLevel.WARN, "k").getSeverityNumber());
        assertEquals("WARN", new LogRecord(LogLevel.WARN, "k").getSeverityText());
        assertEquals(17, new LogRecord(LogLevel.ERROR, "k").getSeverityNumber());
        assertEquals("ERROR", new LogRecord(LogLevel.ERROR, "k").getSeverityText());
        assertEquals(9, new LogRecord(LogLevel.ACCESS, "k").getSeverityNumber());
        assertEquals(5, new LogRecord(LogLevel.QLOG, "k").getSeverityNumber());
        assertEquals("DEBUG", new LogRecord(LogLevel.QLOG, "k").getSeverityText());
    }

    @Test
    public void levelAndKeyAreRequired() {
        try {
            new LogRecord(null, "k");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            new LogRecord(LogLevel.INFO, null);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void attributesKeepTheirOrderAndTypes() {
        LogRecord r = new LogRecord(LogLevel.WARN, "k")
                .attr("s", "text")
                .attr("n", 42L)
                .attr("b", true)
                .attr("d", 2.5)
                .addAttribute(Attribute.string("t", "tail"))
                .addAttribute(null);
        List<Attribute> attributes = r.getAttributes();
        assertEquals(5, attributes.size());
        assertEquals("s", attributes.get(0).getKey());
        assertEquals(Attribute.TYPE_STRING, attributes.get(0).getType());
        assertEquals("n", attributes.get(1).getKey());
        assertEquals(42L, attributes.get(1).getIntValue());
        assertEquals("b", attributes.get(2).getKey());
        assertTrue(attributes.get(2).getBoolValue());
        assertEquals("d", attributes.get(3).getKey());
        assertEquals(2.5, attributes.get(3).getDoubleValue(), 0.0);
        assertEquals("t", attributes.get(4).getKey());
        assertEquals("text", r.getString("s"));
        assertNull(r.getString("n"));
        assertNull(r.getString("missing"));
        assertSame(attributes.get(1), r.getAttribute("n"));
        assertNull(r.getAttribute("missing"));
    }

    @Test
    public void attributeListIsReadOnly() {
        LogRecord r = new LogRecord(LogLevel.INFO, "k").attr("a", "b");
        try {
            r.getAttributes().clear();
            fail();
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void nullStringKeepsItsPlace() {
        LogRecord r = new LogRecord(LogLevel.INFO, "k").attr("first", (String) null).attr("second", "x");
        assertEquals("", r.getAttributes().get(0).getStringValue());
        assertEquals("x", r.getAttributes().get(1).getStringValue());
    }

    @Test
    public void bodyAndThrowableAreCarried() {
        Exception e = new IllegalStateException("bad");
        LogRecord r = new LogRecord(LogLevel.ERROR, "k").body("{}").thrown(e);
        assertEquals("{}", r.getBody());
        assertSame(e, r.getThrown());
    }

    @Test
    public void exportAttributesAddTheExceptionUnderThePolicy() {
        LogRecord plain = new LogRecord(LogLevel.INFO, "k").attr("a", "b");
        assertEquals(1, plain.exportAttributes(true).size());
        IllegalStateException boom = new IllegalStateException("boom");
        LogRecord r = new LogRecord(LogLevel.ERROR, "k").attr("a", "b").thrown(boom);
        List<Attribute> terse = r.exportAttributes(false);
        assertEquals(2, terse.size());
        assertEquals("a", terse.get(0).getKey());
        assertEquals("exception.type", terse.get(1).getKey());
        assertEquals("java.lang.IllegalStateException", terse.get(1).getStringValue());
        List<Attribute> full = r.exportAttributes(true);
        assertEquals(4, full.size());
        assertEquals("exception.message", full.get(2).getKey());
        assertEquals("boom", full.get(2).getStringValue());
        assertEquals("exception.stacktrace", full.get(3).getKey());
        assertTrue(full.get(3).getStringValue().contains("LogRecordTest"));
        // the record itself is unchanged
        assertEquals(1, r.getAttributes().size());
        LogRecord silent = new LogRecord(LogLevel.ERROR, "k").thrown(new IllegalStateException());
        // no message: type and stack trace only
        assertEquals(2, silent.exportAttributes(true).size());
    }

    @Test
    public void spanCorrelatesTheRecord() {
        Trace trace = new Trace("op", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        LogRecord r = new LogRecord(LogLevel.INFO, "k").span(span);
        assertTrue(r.hasSpanContext());
        assertEquals(trace.getTraceId(), r.getTraceId());
        assertEquals(span.getSpanId(), r.getSpanId());
        LogRecord none = new LogRecord(LogLevel.INFO, "k").span(null);
        assertFalse(none.hasSpanContext());
        assertNull(none.getTraceId());
        assertNull(none.getSpanId());
    }

    @Test
    public void recordBuiltOutsideALoggerCannotEmit() {
        try {
            new LogRecord(LogLevel.INFO, "k").emit();
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void toStringNamesLevelAndKey() {
        assertEquals("LogRecord[WARN: warn.thing]", new LogRecord(LogLevel.WARN, "warn.thing").toString());
    }

}
