/*
 * OtlpJsonSerializersTest.java
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

package org.bluezoo.gumdrop.telemetry.json;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.TelemetryTestData;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.junit.Test;

/**
 * Broader coverage tests for the OTLP JSON serializers using rich fixtures.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OtlpJsonSerializersTest {

    private static String text(ByteArrayOutputStream out) {
        byte[] bytes = out.toByteArray();
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    public void testTraceFull() throws IOException {
        Map<String, String> attrs = TelemetryTestData.resourceAttributes();
        TraceJsonSerializer s = new TraceJsonSerializer("svc", "1.0", "ns", attrs);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WritableByteChannel ch = Channels.newChannel(out);
        Trace trace = TelemetryTestData.richTrace();
        s.serialize(trace, ch);
        String json = text(out);
        assertTrue(json.contains("\"resourceSpans\""));
        assertTrue(json.contains("child"));
        assertTrue(json.contains("\"events\""));
        assertTrue(json.contains("\"links\""));
        assertTrue(json.contains("host.name"));
        assertTrue(json.contains("\"doubleValue\""));
        assertTrue(json.contains("\"boolValue\""));
        assertTrue(json.contains("\"intValue\""));
    }

    @Test
    public void testTraceMinimal() throws IOException {
        TraceJsonSerializer s = new TraceJsonSerializer("svc");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Trace trace = new Trace("op");
        trace.end();
        s.serialize(trace, Channels.newChannel(out));
        assertTrue(text(out).contains("\"op\""));
    }

    @Test
    public void testLogsBatchSingleAndEmpty() throws IOException {
        Map<String, String> attrs = TelemetryTestData.resourceAttributes();
        LogJsonSerializer s = new LogJsonSerializer("svc", "1.0", "ns", attrs);
        List<LogRecord> records = TelemetryTestData.logRecords();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.serialize(records, Channels.newChannel(out));
        String json = text(out);
        assertTrue(json.contains("\"resourceLogs\""));
        assertTrue(json.contains("with span"));
        assertTrue(json.contains("\"traceId\""));

        ByteArrayOutputStream one = new ByteArrayOutputStream();
        LogRecord first = records.get(1);
        s.serialize(first, Channels.newChannel(one));
        assertTrue(text(one).contains("no span"));

        ByteArrayOutputStream none = new ByteArrayOutputStream();
        s.serialize(new ArrayList<LogRecord>(), Channels.newChannel(none));
        assertTrue(none.size() == 0);
        s.serialize((List<LogRecord>) null, Channels.newChannel(none));
        assertTrue(none.size() == 0);

        LogJsonSerializer plain = new LogJsonSerializer("svc");
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        plain.serialize(records, Channels.newChannel(out2));
        assertTrue(out2.size() > 0);
    }

    @Test
    public void testMetricsAllTypes() throws IOException {
        Map<String, String> attrs = TelemetryTestData.resourceAttributes();
        MetricJsonSerializer s = new MetricJsonSerializer("svc", "1.0", "ns", attrs);
        List<MetricData> metrics = TelemetryTestData.metrics();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.serialize(metrics, "meter", "2.0", Channels.newChannel(out));
        String json = text(out);
        assertTrue(json.contains("\"gauge\""));
        assertTrue(json.contains("\"sum\""));
        assertTrue(json.contains("\"histogram\""));
        assertTrue(json.contains("\"asDouble\""));
        assertTrue(json.contains("\"asInt\""));

        MetricJsonSerializer plain = new MetricJsonSerializer("svc", null, null, null);
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        plain.serialize(metrics, "meter", null, Channels.newChannel(out2));
        assertTrue(out2.size() > 0);

        ByteArrayOutputStream none = new ByteArrayOutputStream();
        plain.serialize(new ArrayList<MetricData>(), "m", "1", Channels.newChannel(none));
        plain.serialize((List<MetricData>) null, "m", "1", Channels.newChannel(none));
        assertTrue(none.size() == 0);
    }
}
