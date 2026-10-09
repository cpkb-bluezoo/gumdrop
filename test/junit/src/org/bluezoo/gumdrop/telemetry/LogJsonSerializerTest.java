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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.telemetry.json.LogJsonSerializer;
import org.bluezoo.gumdrop.testsupport.RecordingExporter;

/**
 * Tests for LogJsonSerializer.
 * Verifies OTLP JSON log serialization.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LogJsonSerializerTest {

    @Test
    public void testSerializeSingleLog() throws IOException {
        LogRecord record = new LogRecord(LogLevel.INFO, "info.test").body("Test message");

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
        LogRecord record = new LogRecord(LogLevel.INFO, "info.test").body("Correlated log").span(span);

        String json = serializeLog(record, "test-service");

        assertTrue("Should contain traceId", json.contains("\"traceId\""));
        assertTrue("Should contain spanId", json.contains("\"spanId\""));
    }

    @Test
    public void testSerializeLogWithAttributes() throws IOException {
        LogRecord record = new LogRecord(LogLevel.WARN, "warn.test").body("Warning message");
        record.attr("request.id", "abc-123");

        String json = serializeLog(record, "test-service");

        assertTrue("Should contain attributes", json.contains("\"attributes\""));
        assertTrue("Should contain request.id", json.contains("\"request.id\""));
        assertTrue("Should contain abc-123", json.contains("\"abc-123\""));
    }

    @Test
    public void testSerializeLogBatch() throws IOException {
        List<LogRecord> records = new ArrayList<LogRecord>();
        records.add(new LogRecord(LogLevel.INFO, "info.test").body("Message 1"));
        records.add(new LogRecord(LogLevel.ERROR, "err.test").body("Message 2"));

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
        LogRecord record = new LogRecord(LogLevel.INFO, "info.test").body("Timestamp test");

        String json = serializeLog(record, "test-service");

        assertTrue("timeUnixNano should be a string",
                json.contains("\"timeUnixNano\":\""));
        assertTrue("observedTimeUnixNano should be a string",
                json.contains("\"observedTimeUnixNano\":\""));
    }

    @Test
    public void eventNameAndScopeComeFromTheRecord() throws IOException {
        TelemetryConfig config = new TelemetryConfig();
        RecordingExporter exporter = new RecordingExporter();
        config.exporter(exporter);
        config.getLogger(LogJsonSerializerTest.class, EventLoggerTest.BUNDLE)
                .warn("warn.thing").attr("uri", "/x").emit();
        String json = serializeLog(exporter.records.get(0), "svc");
        assertTrue(json, json.contains("\"eventName\":\"warn.thing\""));
        assertTrue(json, json.contains("\"name\":\"" + LogJsonSerializerTest.class.getName() + "\""));
        assertTrue(json, json.contains("\"uri\""));
        assertFalse(json, json.contains("\"body\""));
        // a record built outside a logger is the server's own
        String plain = serializeLog(new LogRecord(LogLevel.ACCESS, "http.server.request"), "svc");
        assertTrue(plain, plain.contains("\"name\":\"gumdrop\""));
        assertTrue(plain, plain.contains("\"eventName\":\"http.server.request\""));
    }

    @Test
    public void recordsAreGroupedByScope() throws IOException {
        TelemetryConfig config = new TelemetryConfig();
        RecordingExporter exporter = new RecordingExporter();
        config.exporter(exporter);
        config.getLogger(LogJsonSerializerTest.class, EventLoggerTest.BUNDLE).info("a").emit();
        config.getLogger(LogRecordTest.class, EventLoggerTest.BUNDLE).info("b").emit();
        config.getLogger(LogJsonSerializerTest.class, EventLoggerTest.BUNDLE).info("c").emit();
        LogJsonSerializer serializer = new LogJsonSerializer("svc");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        serializer.serialize(exporter.records, Channels.newChannel(out));
        String json = out.toString("UTF-8");
        int first = json.indexOf("\"name\":\"" + LogJsonSerializerTest.class.getName());
        int second = json.indexOf("\"name\":\"" + LogRecordTest.class.getName());
        assertTrue(first >= 0 && second > first);
        // two scopes, so two scope entries, and the first holds a and c
        assertEquals(2, json.split("\"scope\":").length - 1);
        assertTrue(json.indexOf("\"eventName\":\"c\"") < second);
    }

    @Test
    public void exceptionDetailsFollowTheSerializerPolicy() throws IOException {
        LogRecord record = new LogRecord(LogLevel.ERROR, "err.thing").thrown(new IllegalStateException("secret"));
        String terse = serialize(new LogJsonSerializer("svc", null, null, null, false), record);
        assertTrue(terse, terse.contains("\"exception.type\""));
        assertTrue(terse, terse.contains("IllegalStateException"));
        assertFalse(terse, terse.contains("secret"));
        assertFalse(terse, terse.contains("exception.stacktrace"));
        String full = serialize(new LogJsonSerializer("svc", null, null, null, true), record);
        assertTrue(full, full.contains("\"exception.message\""));
        assertTrue(full, full.contains("secret"));
        assertTrue(full, full.contains("\"exception.stacktrace\""));
    }

    private String serialize(LogJsonSerializer serializer, LogRecord record) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        serializer.serialize(record, Channels.newChannel(out));
        return out.toString("UTF-8");
    }

    private String serializeLog(LogRecord record, String serviceName) throws IOException {
        LogJsonSerializer serializer = new LogJsonSerializer(serviceName);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WritableByteChannel channel = Channels.newChannel(out);
        serializer.serialize(record, channel);
        return out.toString("UTF-8");
    }

}
