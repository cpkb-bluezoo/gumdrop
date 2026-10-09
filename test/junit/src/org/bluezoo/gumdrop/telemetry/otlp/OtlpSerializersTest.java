/*
 * OtlpSerializersTest.java
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

package org.bluezoo.gumdrop.telemetry.otlp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.gumdrop.telemetry.TelemetryTestData;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.DoubleHistogram;
import org.bluezoo.gumdrop.telemetry.metrics.LongCounter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import org.bluezoo.protobuf.DefaultProtobufHandler;
import org.bluezoo.protobuf.ProtobufParser;
import java.util.ResourceBundle;
import org.bluezoo.gumdrop.telemetry.LogLevel;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.testsupport.RecordingExporter;
import org.junit.Test;

/**
 * Tests for the OTLP protobuf serializers.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class OtlpSerializersTest {

    @Test
    public void testTraceFull() throws IOException {
        Map<String, String> attrs = TelemetryTestData.resourceAttributes();
        TraceSerializer s = new TraceSerializer("svc", "1.0", "ns", attrs);
        Trace trace = TelemetryTestData.richTrace();
        ByteBuffer buf = s.serialize(trace);
        assertTrue(buf.remaining() > 100);
    }

    @Test
    public void testTraceMinimal() throws IOException {
        TraceSerializer s = new TraceSerializer("svc");
        Trace trace = new Trace("op");
        trace.end();
        ByteBuffer buf = s.serialize(trace);
        assertTrue(buf.remaining() > 0);
    }

    @Test
    public void testLogsBatch() throws IOException {
        Map<String, String> attrs = TelemetryTestData.resourceAttributes();
        LogSerializer s = new LogSerializer("svc", "1.0", "ns", attrs);
        List<LogRecord> records = TelemetryTestData.logRecords();
        ByteBuffer buf = s.serialize(records);
        assertTrue(buf.remaining() > 50);
    }

    @Test
    public void testLogsSingleAndEmpty() throws IOException {
        LogSerializer s = new LogSerializer("svc");
        List<LogRecord> records = TelemetryTestData.logRecords();
        LogRecord first = records.get(0);
        ByteBuffer one = s.serialize(first);
        assertTrue(one.remaining() > 0);
        ByteBuffer none = s.serialize(new ArrayList<LogRecord>());
        assertTrue(none.remaining() >= 0);
    }

    /** Collects the length-delimited fields of one message, by field number. */
    private static final class Fields extends DefaultProtobufHandler {
        final Map<Integer, List<byte[]>> bytes = new HashMap<Integer, List<byte[]>>();

        @Override
        public void handleBytes(int fieldNumber, ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            List<byte[]> list = bytes.get(Integer.valueOf(fieldNumber));
            if (list == null) {
                list = new ArrayList<byte[]>();
                bytes.put(Integer.valueOf(fieldNumber), list);
            }
            list.add(copy);
        }

        List<byte[]> all(int field) {
            List<byte[]> list = bytes.get(Integer.valueOf(field));
            return list != null ? list : new ArrayList<byte[]>();
        }

        byte[] one(int field) {
            List<byte[]> list = all(field);
            assertEquals(1, list.size());
            return list.get(0);
        }

        String string(int field) {
            return new String(one(field), StandardCharsets.UTF_8);
        }
    }

    private static Fields fields(byte[] message) throws Exception {
        Fields fields = new Fields();
        ProtobufParser parser = new ProtobufParser(fields);
        parser.receive(ByteBuffer.wrap(message));
        return fields;
    }

    private static byte[] bytes(ByteBuffer buf) {
        byte[] copy = new byte[buf.remaining()];
        buf.get(copy);
        return copy;
    }

    @Test
    public void logsCarryScopeEventNameAndExceptionType() throws Exception {
        TelemetryConfig config = new TelemetryConfig();
        RecordingExporter exporter = new RecordingExporter();
        config.exporter(exporter);
        config.getLogger(OtlpSerializersTest.class, ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N"))
                .error("err.thing").attr("uri", "/x").thrown(new IllegalStateException("secret")).emit();
        exporter.records.add(new LogRecord(LogLevel.ACCESS, "http.server.request"));
        LogSerializer s = new LogSerializer("svc");
        Fields logsData = fields(bytes(s.serialize(exporter.records)));
        Fields resourceLogs = fields(logsData.one(1));
        List<byte[]> scopeLogs = resourceLogs.all(2);
        assertEquals("one ScopeLogs per scope", 2, scopeLogs.size());

        Fields first = fields(scopeLogs.get(0));
        assertEquals(OtlpSerializersTest.class.getName(), fields(first.one(1)).string(1));
        Fields record = fields(first.one(2));
        assertEquals("err.thing", record.string(12));
        assertTrue(record.all(5).isEmpty());
        List<byte[]> attributes = record.all(6);
        assertEquals(2, attributes.size());
        assertEquals("uri", fields(attributes.get(0)).string(1));
        assertEquals("exception.type", fields(attributes.get(1)).string(1));
        assertEquals("java.lang.IllegalStateException", fields(fields(attributes.get(1)).one(2)).string(1));

        Fields second = fields(scopeLogs.get(1));
        assertEquals("gumdrop", fields(second.one(1)).string(1));
        assertEquals("http.server.request", fields(second.one(2)).string(12));

        LogSerializer detailed = new LogSerializer("svc", null, null, null, true);
        Fields detailedRecord = fields(fields(fields(fields(bytes(detailed.serialize(exporter.records.get(0))))
                .one(1)).one(2)).one(2));
        List<byte[]> more = detailedRecord.all(6);
        assertEquals(4, more.size());
        assertEquals("exception.message", fields(more.get(2)).string(1));
        assertEquals("secret", fields(fields(more.get(2)).one(2)).string(1));
        assertEquals("exception.stacktrace", fields(more.get(3)).string(1));
    }

    @Test
    public void testMetricsAllTypes() throws IOException {
        Map<String, String> attrs = TelemetryTestData.resourceAttributes();
        MetricSerializer s = new MetricSerializer("svc", "1.0", "ns", attrs);
        List<MetricData> metrics = TelemetryTestData.metrics();
        ByteBuffer buf = s.serialize(metrics, "meter", "2.0");
        assertTrue(buf.remaining() > 100);
        MetricSerializer plain = new MetricSerializer("svc", null, null, null);
        ByteBuffer buf2 = plain.serialize(metrics, "meter", null);
        assertTrue(buf2.remaining() > 100);
    }

    @Test
    public void testMetricsEmpty() throws IOException {
        MetricSerializer s = new MetricSerializer("svc", null, null, null);
        List<MetricData> none = new ArrayList<MetricData>();
        ByteBuffer buf = s.serialize(none, "m", "1");
        assertEquals(0, buf.remaining());
        ByteBuffer nul = s.serialize((List<MetricData>) null, "m", "1");
        assertEquals(0, nul.remaining());
    }

    @Test
    public void testMetricsFromMeter() throws IOException {
        Meter meter = new Meter("m", "1");
        LongCounter c = meter.counterBuilder("c").setDescription("d").build();
        c.add(3L);
        c.add(4L, Attributes.of("a", "b"));
        DoubleHistogram h = meter.histogramBuilder("h").build();
        h.record(1.5);
        MetricSerializer s = new MetricSerializer("svc", null, null, null);
        ByteBuffer buf = s.serialize(meter, AggregationTemporality.CUMULATIVE);
        assertTrue(buf.remaining() > 0);
        Meter empty = new Meter("e");
        ByteBuffer none = s.serialize(empty, AggregationTemporality.DELTA);
        assertEquals(0, none.remaining());
    }
}
