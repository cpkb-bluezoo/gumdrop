/*
 * LogJsonSerializerTest.java
 * Copyright (C) 2026 Chris Burdess
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
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.telemetry.json.LogJsonSerializer;

/**
 * Tests for LogJsonSerializer.
 * Verifies OTLP JSON log serialization.
 */
public class LogJsonSerializerTest {

    @Test
    public void testSerializeSingleLog() throws IOException {
        LogRecord record = new LogRecord(LogRecord.SEVERITY_INFO, "Test message");

        String json = serializeLog(record, "test-service");

        assertTrue("Should contain resourceLogs", json.contains("\"resourceLogs\""));
        assertTrue("Should contain service name", json.contains("\"test-service\""));
        assertTrue("Should contain severity text", json.contains("\"INFO\""));
        assertTrue("Should contain body", json.contains("\"Test message\""));
        assertTrue("Should contain severityNumber", json.contains("\"severityNumber\":9"));
    }

    @Test
    public void testSerializeLogWithSpanContext() throws IOException {
        Trace trace = new Trace("test-op", SpanKind.SERVER);
        Span span = trace.getRootSpan();
        LogRecord record = LogRecord.info(span, "Correlated log");

        String json = serializeLog(record, "test-service");

        assertTrue("Should contain traceId", json.contains("\"traceId\""));
        assertTrue("Should contain spanId", json.contains("\"spanId\""));
    }

    @Test
    public void testSerializeLogWithAttributes() throws IOException {
        LogRecord record = new LogRecord(LogRecord.SEVERITY_WARN, "Warning message");
        record.addAttribute("request.id", "abc-123");

        String json = serializeLog(record, "test-service");

        assertTrue("Should contain attributes", json.contains("\"attributes\""));
        assertTrue("Should contain request.id", json.contains("\"request.id\""));
        assertTrue("Should contain abc-123", json.contains("\"abc-123\""));
    }

    @Test
    public void testSerializeLogBatch() throws IOException {
        List<LogRecord> records = new ArrayList<LogRecord>();
        records.add(new LogRecord(LogRecord.SEVERITY_INFO, "Message 1"));
        records.add(new LogRecord(LogRecord.SEVERITY_ERROR, "Message 2"));

        LogJsonSerializer serializer = new LogJsonSerializer("test-service");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WritableByteChannel channel = Channels.newChannel(out);
        serializer.serialize(records, channel);
        String json = out.toString("UTF-8");

        assertTrue("Should contain Message 1", json.contains("\"Message 1\""));
        assertTrue("Should contain Message 2", json.contains("\"Message 2\""));
    }

    @Test
    public void testLogTimestampsAreStrings() throws IOException {
        LogRecord record = new LogRecord(LogRecord.SEVERITY_INFO, "Timestamp test");

        String json = serializeLog(record, "test-service");

        assertTrue("timeUnixNano should be a string",
                json.contains("\"timeUnixNano\":\""));
        assertTrue("observedTimeUnixNano should be a string",
                json.contains("\"observedTimeUnixNano\":\""));
    }

    private String serializeLog(LogRecord record, String serviceName) throws IOException {
        LogJsonSerializer serializer = new LogJsonSerializer(serviceName);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WritableByteChannel channel = Channels.newChannel(out);
        serializer.serialize(record, channel);
        return out.toString("UTF-8");
    }

}
