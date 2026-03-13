/*
 * TraceJsonSerializerTest.java
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.bluezoo.gumdrop.telemetry.json.TraceJsonSerializer;

/**
 * Tests for TraceJsonSerializer.
 * Verifies OTLP JSON trace serialization.
 */
public class TraceJsonSerializerTest {

    @Test
    public void testSerializeSimpleTrace() throws IOException {
        Trace trace = new Trace("test-operation", SpanKind.SERVER);
        trace.getRootSpan().addAttribute("test.key", "test-value");
        trace.getRootSpan().end();

        String json = serializeTrace(trace, "test-service");

        assertTrue("Should contain resourceSpans", json.contains("\"resourceSpans\""));
        assertTrue("Should contain service name", json.contains("\"service.name\""));
        assertTrue("Should contain test-service", json.contains("\"test-service\""));
        assertTrue("Should contain span name", json.contains("\"test-operation\""));
        assertTrue("Should contain attribute key", json.contains("\"test.key\""));
        assertTrue("Should contain attribute value", json.contains("\"test-value\""));
        assertTrue("Should contain traceId", json.contains("\"traceId\""));
        assertTrue("Should contain spanId", json.contains("\"spanId\""));
    }

    @Test
    public void testSerializeTraceWithEvents() throws IOException {
        Trace trace = new Trace("operation-with-events", SpanKind.SERVER);
        Span root = trace.getRootSpan();

        root.addEvent("event1");
        SpanEvent event2 = new SpanEvent("event2");
        event2.addAttribute("event.key", "event-value");
        root.addEvent(event2);

        root.end();

        String json = serializeTrace(trace, "test-service");

        assertTrue("Should contain events", json.contains("\"events\""));
        assertTrue("Should contain event1", json.contains("\"event1\""));
        assertTrue("Should contain event2", json.contains("\"event2\""));
        assertTrue("Should contain event attribute", json.contains("\"event-value\""));
    }

    @Test
    public void testSerializeTraceWithChildSpans() throws IOException {
        Trace trace = new Trace("parent-operation", SpanKind.SERVER);

        Span child1 = trace.startSpan("child-1", SpanKind.INTERNAL);
        child1.addAttribute("child", "1");
        child1.end();

        Span child2 = trace.startSpan("child-2", SpanKind.CLIENT);
        child2.addAttribute("child", "2");
        child2.setStatusOk();
        child2.end();

        trace.getRootSpan().end();

        String json = serializeTrace(trace, "test-service");

        assertTrue("Should contain child-1", json.contains("\"child-1\""));
        assertTrue("Should contain child-2", json.contains("\"child-2\""));
        assertTrue("Should contain parentSpanId", json.contains("\"parentSpanId\""));
    }

    @Test
    public void testSerializeTraceWithLinks() throws IOException {
        byte[] remoteTraceId = new byte[16];
        byte[] remoteSpanId = new byte[8];
        for (int i = 0; i < 16; i++) {
            remoteTraceId[i] = (byte) (i + 1);
        }
        for (int i = 0; i < 8; i++) {
            remoteSpanId[i] = (byte) (i + 10);
        }
        SpanContext remoteContext = new SpanContext(remoteTraceId, remoteSpanId, true);

        Trace trace = new Trace("linked-operation", SpanKind.SERVER);
        trace.getRootSpan().addLink(remoteContext);
        trace.getRootSpan().end();

        String json = serializeTrace(trace, "test-service");

        assertTrue("Should contain links", json.contains("\"links\""));
    }

    @Test
    public void testSerializeTraceWithStatus() throws IOException {
        Trace trace = new Trace("error-operation", SpanKind.SERVER);
        trace.getRootSpan().setStatusError("Something went wrong");
        trace.getRootSpan().end();

        String json = serializeTrace(trace, "test-service");

        assertTrue("Should contain status", json.contains("\"status\""));
        assertTrue("Should contain error message", json.contains("\"Something went wrong\""));
        assertTrue("Should contain status code 2", json.contains("\"code\":2"));
    }

    @Test
    public void testSerializeWithResourceAttributes() throws IOException {
        Map<String, String> resourceAttrs = new HashMap<String, String>();
        resourceAttrs.put("deployment.environment", "test");
        resourceAttrs.put("host.name", "test-host");

        Trace trace = new Trace("attributed-operation", SpanKind.SERVER);
        trace.getRootSpan().end();

        TraceJsonSerializer serializer = new TraceJsonSerializer(
                "test-service", "2.0.0", "test-namespace", resourceAttrs);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WritableByteChannel channel = Channels.newChannel(out);
        serializer.serialize(trace, channel);
        String json = out.toString("UTF-8");

        assertTrue("Should contain service name", json.contains("\"test-service\""));
        assertTrue("Should contain service version", json.contains("\"2.0.0\""));
        assertTrue("Should contain namespace", json.contains("\"test-namespace\""));
        assertTrue("Should contain deployment.environment", json.contains("\"deployment.environment\""));
        assertTrue("Should contain host.name", json.contains("\"host.name\""));
    }

    @Test
    public void testTraceIdIsHex() throws IOException {
        Trace trace = new Trace("hex-test", SpanKind.SERVER);
        trace.getRootSpan().end();

        String json = serializeTrace(trace, "test-service");

        // The traceId in JSON should be hex encoded (32 chars for 16 bytes)
        String traceIdHex = trace.getTraceIdHex();
        assertTrue("Should contain hex trace ID", json.contains("\"" + traceIdHex + "\""));
    }

    @Test
    public void testTimestampsAreStrings() throws IOException {
        Trace trace = new Trace("timestamp-test", SpanKind.SERVER);
        trace.getRootSpan().end();

        String json = serializeTrace(trace, "test-service");

        // OTLP JSON encodes fixed64 as strings
        assertTrue("startTimeUnixNano should be a string",
                json.contains("\"startTimeUnixNano\":\""));
        assertTrue("endTimeUnixNano should be a string",
                json.contains("\"endTimeUnixNano\":\""));
    }

    @Test
    public void testSchemaUrl() throws IOException {
        Trace trace = new Trace("schema-test", SpanKind.SERVER);
        trace.getRootSpan().end();

        String json = serializeTrace(trace, "test-service");

        assertTrue("Should contain schema URL",
                json.contains("\"schemaUrl\":\"https://opentelemetry.io/schemas/1.25.0\""));
    }

    @Test
    public void testAttributeTypes() throws IOException {
        Trace trace = new Trace("attr-types-test", SpanKind.SERVER);
        Span root = trace.getRootSpan();
        root.addAttribute("str_key", "str_value");
        root.addAttribute("bool_key", true);
        root.addAttribute("int_key", 42L);
        root.addAttribute("double_key", 3.14);
        root.end();

        String json = serializeTrace(trace, "test-service");

        assertTrue("Should contain stringValue", json.contains("\"stringValue\":\"str_value\""));
        assertTrue("Should contain boolValue", json.contains("\"boolValue\":true"));
        // int values are encoded as strings in OTLP JSON
        assertTrue("Should contain intValue", json.contains("\"intValue\":\"42\""));
        assertTrue("Should contain doubleValue", json.contains("\"doubleValue\":"));
    }

    private String serializeTrace(Trace trace, String serviceName) throws IOException {
        TraceJsonSerializer serializer = new TraceJsonSerializer(serviceName);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WritableByteChannel channel = Channels.newChannel(out);
        serializer.serialize(trace, channel);
        return out.toString("UTF-8");
    }

}
