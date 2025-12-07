/*
 * LogRecordTest.java
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
 * Unit tests for {@link LogRecord}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LogRecordTest {

    // ========================================================================
    // Construction Tests
    // ========================================================================

    @Test
    public void testBasicConstruction() {
        LogRecord log = new LogRecord(LogRecord.SEVERITY_INFO, "Test message");
        
        assertEquals(LogRecord.SEVERITY_INFO, log.getSeverityNumber());
        assertEquals("INFO", log.getSeverityText());
        assertEquals("Test message", log.getBody());
    }

    @Test
    public void testConstructionWithSpan() {
        Trace trace = new Trace("test-operation", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        LogRecord log = new LogRecord(span, LogRecord.SEVERITY_WARN, "Warning message");
        
        assertTrue(log.hasSpanContext());
        assertArrayEquals(trace.getTraceId(), log.getTraceId());
        assertArrayEquals(span.getSpanId(), log.getSpanId());
    }

    @Test
    public void testConstructionWithNullSpan() {
        LogRecord log = new LogRecord(null, LogRecord.SEVERITY_INFO, "Message");
        
        assertFalse(log.hasSpanContext());
        assertNull(log.getTraceId());
        assertNull(log.getSpanId());
    }

    @Test
    public void testTimestamp() {
        long before = System.currentTimeMillis() * 1_000_000L;
        LogRecord log = new LogRecord(LogRecord.SEVERITY_INFO, "Message");
        long after = System.currentTimeMillis() * 1_000_000L;
        
        assertTrue("Timestamp should be >= before", log.getTimeUnixNano() >= before);
        assertTrue("Timestamp should be <= after", log.getTimeUnixNano() <= after);
    }

    // ========================================================================
    // Severity Level Tests
    // ========================================================================

    @Test
    public void testSeverityTrace() {
        LogRecord log = new LogRecord(LogRecord.SEVERITY_TRACE, "Trace");
        
        assertEquals(LogRecord.SEVERITY_TRACE, log.getSeverityNumber());
        assertEquals("TRACE", log.getSeverityText());
    }

    @Test
    public void testSeverityDebug() {
        LogRecord log = new LogRecord(LogRecord.SEVERITY_DEBUG, "Debug");
        
        assertEquals(LogRecord.SEVERITY_DEBUG, log.getSeverityNumber());
        assertEquals("DEBUG", log.getSeverityText());
    }

    @Test
    public void testSeverityInfo() {
        LogRecord log = new LogRecord(LogRecord.SEVERITY_INFO, "Info");
        
        assertEquals(LogRecord.SEVERITY_INFO, log.getSeverityNumber());
        assertEquals("INFO", log.getSeverityText());
    }

    @Test
    public void testSeverityWarn() {
        LogRecord log = new LogRecord(LogRecord.SEVERITY_WARN, "Warn");
        
        assertEquals(LogRecord.SEVERITY_WARN, log.getSeverityNumber());
        assertEquals("WARN", log.getSeverityText());
    }

    @Test
    public void testSeverityError() {
        LogRecord log = new LogRecord(LogRecord.SEVERITY_ERROR, "Error");
        
        assertEquals(LogRecord.SEVERITY_ERROR, log.getSeverityNumber());
        assertEquals("ERROR", log.getSeverityText());
    }

    @Test
    public void testSeverityFatal() {
        LogRecord log = new LogRecord(LogRecord.SEVERITY_FATAL, "Fatal");
        
        assertEquals(LogRecord.SEVERITY_FATAL, log.getSeverityNumber());
        assertEquals("FATAL", log.getSeverityText());
    }

    @Test
    public void testSeverityBetweenLevels() {
        // OTLP severity numbers can be between levels
        // The code uses <= comparison, so:
        // 2 > TRACE(1) and <= DEBUG(5) -> DEBUG
        LogRecord log2 = new LogRecord(2, "Between TRACE and DEBUG");
        assertEquals("DEBUG", log2.getSeverityText());
        
        // 6 > DEBUG(5) and <= INFO(9) -> INFO
        LogRecord log6 = new LogRecord(6, "Between DEBUG and INFO");
        assertEquals("INFO", log6.getSeverityText());
        
        // 10 > INFO(9) and <= WARN(13) -> WARN
        LogRecord log10 = new LogRecord(10, "Between INFO and WARN");
        assertEquals("WARN", log10.getSeverityText());
    }

    @Test
    public void testSeverityConstants() {
        assertEquals(1, LogRecord.SEVERITY_TRACE);
        assertEquals(5, LogRecord.SEVERITY_DEBUG);
        assertEquals(9, LogRecord.SEVERITY_INFO);
        assertEquals(13, LogRecord.SEVERITY_WARN);
        assertEquals(17, LogRecord.SEVERITY_ERROR);
        assertEquals(21, LogRecord.SEVERITY_FATAL);
    }

    // ========================================================================
    // Factory Method Tests
    // ========================================================================

    @Test
    public void testInfoFactory() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        LogRecord log = LogRecord.info(span, "Info message");
        
        assertEquals(LogRecord.SEVERITY_INFO, log.getSeverityNumber());
        assertEquals("Info message", log.getBody());
        assertTrue(log.hasSpanContext());
    }

    @Test
    public void testWarnFactory() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        LogRecord log = LogRecord.warn(span, "Warning message");
        
        assertEquals(LogRecord.SEVERITY_WARN, log.getSeverityNumber());
        assertEquals("Warning message", log.getBody());
    }

    @Test
    public void testErrorFactory() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        
        LogRecord log = LogRecord.error(span, "Error message");
        
        assertEquals(LogRecord.SEVERITY_ERROR, log.getSeverityNumber());
        assertEquals("Error message", log.getBody());
    }

    @Test
    public void testFactoryWithNullSpan() {
        LogRecord log = LogRecord.info(null, "Message");
        
        assertFalse(log.hasSpanContext());
    }

    // ========================================================================
    // Attribute Tests
    // ========================================================================

    @Test
    public void testAddAttribute() {
        LogRecord log = new LogRecord(LogRecord.SEVERITY_INFO, "Message");
        
        log.addAttribute("key", "value");
        
        List<Attribute> attrs = log.getAttributes();
        assertEquals(1, attrs.size());
        assertEquals("key", attrs.get(0).getKey());
        assertEquals("value", attrs.get(0).getStringValue());
    }

    @Test
    public void testAddAttributeObject() {
        LogRecord log = new LogRecord(LogRecord.SEVERITY_INFO, "Message");
        
        Attribute attr = Attribute.integer("count", 42);
        log.addAttribute(attr);
        
        List<Attribute> attrs = log.getAttributes();
        assertEquals(1, attrs.size());
        assertSame(attr, attrs.get(0));
    }

    @Test
    public void testAddAttributeChaining() {
        LogRecord log = new LogRecord(LogRecord.SEVERITY_INFO, "Message")
            .addAttribute("key1", "value1")
            .addAttribute("key2", "value2");
        
        assertEquals(2, log.getAttributes().size());
    }

    @Test
    public void testAddNullAttribute() {
        LogRecord log = new LogRecord(LogRecord.SEVERITY_INFO, "Message");
        
        log.addAttribute((Attribute) null);
        
        assertEquals(0, log.getAttributes().size());
    }

    @Test
    public void testAttributesUnmodifiable() {
        LogRecord log = new LogRecord(LogRecord.SEVERITY_INFO, "Message");
        log.addAttribute("key", "value");
        
        List<Attribute> attrs = log.getAttributes();
        try {
            attrs.add(Attribute.string("new", "attr"));
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }

    // ========================================================================
    // Span Context Defensive Copy Tests
    // ========================================================================

    @Test
    public void testTraceIdDefensiveCopy() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        LogRecord log = new LogRecord(trace.getRootSpan(), LogRecord.SEVERITY_INFO, "Message");
        
        byte[] id1 = log.getTraceId();
        byte[] id2 = log.getTraceId();
        
        assertNotSame(id1, id2);
        assertArrayEquals(id1, id2);
        
        // Modify returned array
        id1[0] = 0;
        assertNotEquals(id1[0], log.getTraceId()[0]);
    }

    @Test
    public void testSpanIdDefensiveCopy() {
        Trace trace = new Trace("test", SpanKind.SERVER);
        LogRecord log = new LogRecord(trace.getRootSpan(), LogRecord.SEVERITY_INFO, "Message");
        
        byte[] id1 = log.getSpanId();
        byte[] id2 = log.getSpanId();
        
        assertNotSame(id1, id2);
        assertArrayEquals(id1, id2);
    }

    // ========================================================================
    // toString Tests
    // ========================================================================

    @Test
    public void testToString() {
        LogRecord log = new LogRecord(LogRecord.SEVERITY_ERROR, "Error occurred");
        
        String str = log.toString();
        assertTrue(str.contains("LogRecord"));
        assertTrue(str.contains("ERROR"));
        assertTrue(str.contains("Error occurred"));
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

