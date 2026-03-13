/*
 * MetricJsonSerializerTest.java
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

import org.bluezoo.gumdrop.telemetry.json.MetricJsonSerializer;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.HistogramDataPoint;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.telemetry.metrics.NumberDataPoint;

/**
 * Tests for MetricJsonSerializer.
 * Verifies OTLP JSON metric serialization.
 */
public class MetricJsonSerializerTest {

    @Test
    public void testSerializeGauge() throws IOException {
        long now = System.currentTimeMillis() * 1_000_000L;
        List<NumberDataPoint> points = new ArrayList<NumberDataPoint>();
        points.add(new NumberDataPoint(Attributes.empty(), now - 60_000_000_000L, now, 42L));

        MetricData metric = MetricData.gauge("cpu.usage")
                .setDescription("CPU usage percentage")
                .setUnit("%")
                .setNumberDataPoints(points)
                .build();

        List<MetricData> metrics = new ArrayList<MetricData>();
        metrics.add(metric);

        String json = serializeMetrics(metrics);

        assertTrue("Should contain resourceMetrics", json.contains("\"resourceMetrics\""));
        assertTrue("Should contain gauge", json.contains("\"gauge\""));
        assertTrue("Should contain cpu.usage", json.contains("\"cpu.usage\""));
        assertTrue("Should contain description", json.contains("\"CPU usage percentage\""));
        assertTrue("Should contain unit", json.contains("\"%\""));
        assertTrue("Should contain asInt", json.contains("\"asInt\":\"42\""));
    }

    @Test
    public void testSerializeCounter() throws IOException {
        long now = System.currentTimeMillis() * 1_000_000L;
        List<NumberDataPoint> points = new ArrayList<NumberDataPoint>();
        points.add(new NumberDataPoint(
                Attributes.of("http.method", "GET"),
                now - 60_000_000_000L, now, 100L));

        MetricData metric = MetricData.sum("http.requests")
                .setDescription("HTTP request count")
                .setTemporality(AggregationTemporality.CUMULATIVE)
                .setMonotonic(true)
                .setNumberDataPoints(points)
                .build();

        List<MetricData> metrics = new ArrayList<MetricData>();
        metrics.add(metric);

        String json = serializeMetrics(metrics);

        assertTrue("Should contain sum", json.contains("\"sum\""));
        assertTrue("Should contain isMonotonic", json.contains("\"isMonotonic\":true"));
        assertTrue("Should contain aggregationTemporality",
                json.contains("\"aggregationTemporality\":2"));
        assertTrue("Should contain http.method attribute", json.contains("\"http.method\""));
    }

    @Test
    public void testSerializeHistogram() throws IOException {
        long now = System.currentTimeMillis() * 1_000_000L;

        double[] bounds = {10.0, 25.0, 50.0, 100.0};
        long[] bucketCounts = {5, 10, 20, 8, 2};

        List<HistogramDataPoint> points = new ArrayList<HistogramDataPoint>();
        points.add(new HistogramDataPoint(
                Attributes.empty(),
                now - 60_000_000_000L, now,
                45, 1250.5,
                bucketCounts, bounds,
                2.1, 98.7));

        MetricData metric = MetricData.histogram("http.request.duration")
                .setDescription("Request duration")
                .setUnit("ms")
                .setTemporality(AggregationTemporality.CUMULATIVE)
                .setHistogramDataPoints(points)
                .build();

        List<MetricData> metrics = new ArrayList<MetricData>();
        metrics.add(metric);

        String json = serializeMetrics(metrics);

        assertTrue("Should contain histogram", json.contains("\"histogram\""));
        assertTrue("Should contain bucketCounts", json.contains("\"bucketCounts\""));
        assertTrue("Should contain explicitBounds", json.contains("\"explicitBounds\""));
        assertTrue("Should contain min", json.contains("\"min\":"));
        assertTrue("Should contain max", json.contains("\"max\":"));
    }

    @Test
    public void testSerializeDoubleGauge() throws IOException {
        long now = System.currentTimeMillis() * 1_000_000L;
        List<NumberDataPoint> points = new ArrayList<NumberDataPoint>();
        points.add(new NumberDataPoint(Attributes.empty(), now - 60_000_000_000L, now, 3.14));

        MetricData metric = MetricData.gauge("temperature")
                .setUnit("celsius")
                .setNumberDataPoints(points)
                .build();

        List<MetricData> metrics = new ArrayList<MetricData>();
        metrics.add(metric);

        String json = serializeMetrics(metrics);

        assertTrue("Should contain asDouble", json.contains("\"asDouble\":"));
    }

    private String serializeMetrics(List<MetricData> metrics) throws IOException {
        MetricJsonSerializer serializer = new MetricJsonSerializer(
                "test-service", "1.0.0", null, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WritableByteChannel channel = Channels.newChannel(out);
        serializer.serialize(metrics, "gumdrop", "2.0", channel);
        return out.toString("UTF-8");
    }

}
