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
