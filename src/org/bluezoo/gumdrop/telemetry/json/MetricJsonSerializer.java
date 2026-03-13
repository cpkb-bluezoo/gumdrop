/*
 * MetricJsonSerializer.java
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

package org.bluezoo.gumdrop.telemetry.json;

import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.HistogramDataPoint;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.telemetry.metrics.NumberDataPoint;
import org.bluezoo.json.JSONWriter;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Map;

/**
 * Serializes metric data to OTLP JSON format.
 *
 * <p>Produces {@code ExportMetricsServiceRequest} JSON objects conforming to
 * the OTLP JSON Protobuf encoding.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://opentelemetry.io/docs/specs/otlp/">OTLP Specification</a>
 */
public final class MetricJsonSerializer {

    private static final String SCHEMA_URL = "https://opentelemetry.io/schemas/1.25.0";

    private final String serviceName;
    private final String serviceVersion;
    private final String serviceNamespace;
    private final Map<String, String> resourceAttributes;

    public MetricJsonSerializer(String serviceName, String serviceVersion,
                                String serviceNamespace, Map<String, String> resourceAttributes) {
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.serviceNamespace = serviceNamespace;
        this.resourceAttributes = resourceAttributes;
    }

    /**
     * Serializes a list of metrics as an {@code ExportMetricsServiceRequest} JSON object.
     *
     * @param metrics the metrics to serialize
     * @param meterName the meter name
     * @param meterVersion the meter version
     * @param channel the channel to write to
     * @throws IOException if an I/O error occurs
     */
    public void serialize(List<MetricData> metrics, String meterName,
                          String meterVersion, WritableByteChannel channel) throws IOException {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }
        JSONWriter w = new JSONWriter(channel);
        w.writeStartObject();
        w.writeKey("resourceMetrics");
        w.writeStartArray();
        writeResourceMetrics(w, metrics, meterName, meterVersion);
        w.writeEndArray();
        w.writeEndObject();
        w.close();
    }

    private void writeResourceMetrics(JSONWriter w, List<MetricData> metrics,
                                       String meterName, String meterVersion) throws IOException {
        w.writeStartObject();

        w.writeKey("resource");
        writeResource(w);

        w.writeKey("scopeMetrics");
        w.writeStartArray();
        writeScopeMetrics(w, metrics, meterName, meterVersion);
        w.writeEndArray();

        w.writeKey("schemaUrl");
        w.writeString(SCHEMA_URL);

        w.writeEndObject();
    }

    private void writeResource(JSONWriter w) throws IOException {
        w.writeStartObject();
        w.writeKey("attributes");
        w.writeStartArray();

        OTLPJsonUtil.writeStringKeyValue(w, "service.name", serviceName);

        if (serviceVersion != null) {
            OTLPJsonUtil.writeStringKeyValue(w, "service.version", serviceVersion);
        }
        if (serviceNamespace != null) {
            OTLPJsonUtil.writeStringKeyValue(w, "service.namespace", serviceNamespace);
        }
        if (resourceAttributes != null) {
            for (Map.Entry<String, String> entry : resourceAttributes.entrySet()) {
                OTLPJsonUtil.writeStringKeyValue(w, entry.getKey(), entry.getValue());
            }
        }

        w.writeEndArray();
        w.writeEndObject();
    }

    private void writeScopeMetrics(JSONWriter w, List<MetricData> metrics,
                                    String meterName, String meterVersion) throws IOException {
        w.writeStartObject();

        w.writeKey("scope");
        w.writeStartObject();
        w.writeKey("name");
        w.writeString(meterName != null ? meterName : "gumdrop");
        if (meterVersion != null) {
            w.writeKey("version");
            w.writeString(meterVersion);
        }
        w.writeEndObject();

        w.writeKey("metrics");
        w.writeStartArray();
        for (MetricData metric : metrics) {
            writeMetric(w, metric);
        }
        w.writeEndArray();

        w.writeEndObject();
    }

    private void writeMetric(JSONWriter w, MetricData metric) throws IOException {
        w.writeStartObject();

        w.writeKey("name");
        w.writeString(metric.getName());

        if (metric.getDescription() != null && !metric.getDescription().isEmpty()) {
            w.writeKey("description");
            w.writeString(metric.getDescription());
        }

        if (metric.getUnit() != null && !metric.getUnit().isEmpty()) {
            w.writeKey("unit");
            w.writeString(metric.getUnit());
        }

        switch (metric.getType()) {
            case GAUGE:
                w.writeKey("gauge");
                writeGauge(w, metric);
                break;
            case SUM:
                w.writeKey("sum");
                writeSum(w, metric);
                break;
            case HISTOGRAM:
                w.writeKey("histogram");
                writeHistogram(w, metric);
                break;
        }

        w.writeEndObject();
    }

    private void writeGauge(JSONWriter w, MetricData metric) throws IOException {
        w.writeStartObject();
        w.writeKey("dataPoints");
        w.writeStartArray();
        for (NumberDataPoint point : metric.getNumberDataPoints()) {
            writeNumberDataPoint(w, point);
        }
        w.writeEndArray();
        w.writeEndObject();
    }

    private void writeSum(JSONWriter w, MetricData metric) throws IOException {
        w.writeStartObject();

        w.writeKey("dataPoints");
        w.writeStartArray();
        for (NumberDataPoint point : metric.getNumberDataPoints()) {
            writeNumberDataPoint(w, point);
        }
        w.writeEndArray();

        w.writeKey("aggregationTemporality");
        w.writeNumber(Integer.valueOf(metric.getTemporality().getProtoValue()));

        w.writeKey("isMonotonic");
        w.writeBoolean(metric.isMonotonic());

        w.writeEndObject();
    }

    private void writeHistogram(JSONWriter w, MetricData metric) throws IOException {
        w.writeStartObject();

        w.writeKey("dataPoints");
        w.writeStartArray();
        for (HistogramDataPoint point : metric.getHistogramDataPoints()) {
            writeHistogramDataPoint(w, point);
        }
        w.writeEndArray();

        w.writeKey("aggregationTemporality");
        w.writeNumber(Integer.valueOf(metric.getTemporality().getProtoValue()));

        w.writeEndObject();
    }

    private void writeNumberDataPoint(JSONWriter w, NumberDataPoint point) throws IOException {
        w.writeStartObject();

        w.writeKey("startTimeUnixNano");
        w.writeString(Long.toString(point.getStartTimeUnixNano()));

        w.writeKey("timeUnixNano");
        w.writeString(Long.toString(point.getTimeUnixNano()));

        if (point.isDouble()) {
            w.writeKey("asDouble");
            w.writeNumber(Double.valueOf(point.getDoubleValue()));
        } else {
            w.writeKey("asInt");
            w.writeString(Long.toString(point.getLongValue()));
        }

        Attributes attrs = point.getAttributes();
        if (attrs != null && !attrs.isEmpty()) {
            w.writeKey("attributes");
            OTLPJsonUtil.writeAttributes(w, attrs.asList());
        }

        w.writeEndObject();
    }

    private void writeHistogramDataPoint(JSONWriter w, HistogramDataPoint point) throws IOException {
        w.writeStartObject();

        w.writeKey("startTimeUnixNano");
        w.writeString(Long.toString(point.getStartTimeUnixNano()));

        w.writeKey("timeUnixNano");
        w.writeString(Long.toString(point.getTimeUnixNano()));

        w.writeKey("count");
        w.writeString(Long.toString(point.getCount()));

        w.writeKey("sum");
        w.writeNumber(Double.valueOf(point.getSum()));

        long[] bucketCounts = point.getBucketCounts();
        w.writeKey("bucketCounts");
        w.writeStartArray();
        for (long count : bucketCounts) {
            w.writeString(Long.toString(count));
        }
        w.writeEndArray();

        double[] bounds = point.getExplicitBounds();
        w.writeKey("explicitBounds");
        w.writeStartArray();
        for (double bound : bounds) {
            w.writeNumber(Double.valueOf(bound));
        }
        w.writeEndArray();

        Attributes attrs = point.getAttributes();
        if (attrs != null && !attrs.isEmpty()) {
            w.writeKey("attributes");
            OTLPJsonUtil.writeAttributes(w, attrs.asList());
        }

        w.writeKey("min");
        w.writeNumber(Double.valueOf(point.getMin()));

        w.writeKey("max");
        w.writeNumber(Double.valueOf(point.getMax()));

        w.writeEndObject();
    }

}
