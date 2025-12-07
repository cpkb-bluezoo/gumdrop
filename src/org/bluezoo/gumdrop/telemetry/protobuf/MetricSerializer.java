/*
 * MetricSerializer.java
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

package org.bluezoo.gumdrop.telemetry.protobuf;

import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.metrics.AggregationTemporality;
import org.bluezoo.gumdrop.telemetry.metrics.Attributes;
import org.bluezoo.gumdrop.telemetry.metrics.HistogramDataPoint;
import org.bluezoo.gumdrop.telemetry.metrics.Meter;
import org.bluezoo.gumdrop.telemetry.metrics.MetricData;
import org.bluezoo.gumdrop.telemetry.metrics.NumberDataPoint;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Serializes metric data to OTLP protobuf format.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MetricSerializer {

    private static final String SCHEMA_URL = "https://opentelemetry.io/schemas/1.25.0";

    private final String serviceName;
    private final String serviceVersion;
    private final String serviceNamespace;
    private final Map<String, String> resourceAttributes;

    public MetricSerializer(String serviceName, String serviceVersion,
                           String serviceNamespace, Map<String, String> resourceAttributes) {
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.serviceNamespace = serviceNamespace;
        this.resourceAttributes = resourceAttributes;
    }

    /**
     * Serializes a list of metrics to OTLP MetricsData protobuf.
     */
    public WriteResult serialize(List<MetricData> metrics, String meterName, 
                                String meterVersion, ByteBuffer buffer) {
        if (metrics == null || metrics.isEmpty()) {
            return WriteResult.SUCCESS;
        }

        ProtobufWriter writer = new ProtobufWriter(buffer);

        // MetricsData { repeated ResourceMetrics resource_metrics = 1; }
        writer.writeMessageField(OTLPFieldNumbers.METRICS_DATA_RESOURCE_METRICS,
                new ResourceMetricsWriter(metrics, meterName, meterVersion));

        return writer.getResult();
    }

    /**
     * Serializes metrics from a single meter.
     */
    public WriteResult serialize(Meter meter, AggregationTemporality temporality,
                                ByteBuffer buffer) {
        List<MetricData> metrics = meter.collect(temporality);
        if (metrics.isEmpty()) {
            return WriteResult.SUCCESS;
        }
        return serialize(metrics, meter.getName(), meter.getVersion(), buffer);
    }

    // -- Inner classes for message content --

    private class ResourceMetricsWriter implements ProtobufWriter.MessageContent {
        private final List<MetricData> metrics;
        private final String meterName;
        private final String meterVersion;

        ResourceMetricsWriter(List<MetricData> metrics, String meterName, String meterVersion) {
            this.metrics = metrics;
            this.meterName = meterName;
            this.meterVersion = meterVersion;
        }

        @Override
        public void writeTo(ProtobufWriter writer) {
            // Resource resource = 1
            writer.writeMessageField(OTLPFieldNumbers.RESOURCE_METRICS_RESOURCE,
                    new ResourceWriter());

            // repeated ScopeMetrics scope_metrics = 2
            writer.writeMessageField(OTLPFieldNumbers.RESOURCE_METRICS_SCOPE_METRICS,
                    new ScopeMetricsWriter(metrics, meterName, meterVersion));

            // string schema_url = 3
            writer.writeStringField(OTLPFieldNumbers.RESOURCE_METRICS_SCHEMA_URL, SCHEMA_URL);
        }
    }

    private class ResourceWriter implements ProtobufWriter.MessageContent {
        @Override
        public void writeTo(ProtobufWriter writer) {
            // repeated KeyValue attributes = 1
            writeKeyValue(writer, OTLPFieldNumbers.RESOURCE_ATTRIBUTES,
                    "service.name", serviceName);

            if (serviceVersion != null) {
                writeKeyValue(writer, OTLPFieldNumbers.RESOURCE_ATTRIBUTES,
                        "service.version", serviceVersion);
            }

            if (serviceNamespace != null) {
                writeKeyValue(writer, OTLPFieldNumbers.RESOURCE_ATTRIBUTES,
                        "service.namespace", serviceNamespace);
            }

            if (resourceAttributes != null) {
                for (Map.Entry<String, String> entry : resourceAttributes.entrySet()) {
                    writeKeyValue(writer, OTLPFieldNumbers.RESOURCE_ATTRIBUTES,
                            entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private class ScopeMetricsWriter implements ProtobufWriter.MessageContent {
        private final List<MetricData> metrics;
        private final String meterName;
        private final String meterVersion;

        ScopeMetricsWriter(List<MetricData> metrics, String meterName, String meterVersion) {
            this.metrics = metrics;
            this.meterName = meterName;
            this.meterVersion = meterVersion;
        }

        @Override
        public void writeTo(ProtobufWriter writer) {
            // InstrumentationScope scope = 1
            writer.writeMessageField(OTLPFieldNumbers.SCOPE_METRICS_SCOPE,
                    new InstrumentationScopeWriter(meterName, meterVersion));

            // repeated Metric metrics = 2
            for (MetricData metric : metrics) {
                writer.writeMessageField(OTLPFieldNumbers.SCOPE_METRICS_METRICS,
                        new MetricWriter(metric));
            }
        }
    }

    private static class InstrumentationScopeWriter implements ProtobufWriter.MessageContent {
        private final String name;
        private final String version;

        InstrumentationScopeWriter(String name, String version) {
            this.name = name;
            this.version = version;
        }

        @Override
        public void writeTo(ProtobufWriter writer) {
            // string name = 1
            writer.writeStringField(OTLPFieldNumbers.INSTRUMENTATION_SCOPE_NAME,
                    name != null ? name : "gumdrop");
            // string version = 2
            if (version != null) {
                writer.writeStringField(OTLPFieldNumbers.INSTRUMENTATION_SCOPE_VERSION, version);
            }
        }
    }

    private static class MetricWriter implements ProtobufWriter.MessageContent {
        private final MetricData metric;

        MetricWriter(MetricData metric) {
            this.metric = metric;
        }

        @Override
        public void writeTo(ProtobufWriter writer) {
            // string name = 1
            writer.writeStringField(OTLPFieldNumbers.METRIC_NAME, metric.getName());

            // string description = 2
            if (metric.getDescription() != null && !metric.getDescription().isEmpty()) {
                writer.writeStringField(OTLPFieldNumbers.METRIC_DESCRIPTION, metric.getDescription());
            }

            // string unit = 3
            if (metric.getUnit() != null && !metric.getUnit().isEmpty()) {
                writer.writeStringField(OTLPFieldNumbers.METRIC_UNIT, metric.getUnit());
            }

            // Write data based on type
            switch (metric.getType()) {
                case GAUGE:
                    writer.writeMessageField(OTLPFieldNumbers.METRIC_GAUGE,
                            new GaugeWriter(metric));
                    break;
                case SUM:
                    writer.writeMessageField(OTLPFieldNumbers.METRIC_SUM,
                            new SumWriter(metric));
                    break;
                case HISTOGRAM:
                    writer.writeMessageField(OTLPFieldNumbers.METRIC_HISTOGRAM,
                            new HistogramWriter(metric));
                    break;
            }
        }
    }

    private static class GaugeWriter implements ProtobufWriter.MessageContent {
        private final MetricData metric;

        GaugeWriter(MetricData metric) {
            this.metric = metric;
        }

        @Override
        public void writeTo(ProtobufWriter writer) {
            // repeated NumberDataPoint data_points = 1
            for (NumberDataPoint point : metric.getNumberDataPoints()) {
                writer.writeMessageField(OTLPFieldNumbers.GAUGE_DATA_POINTS,
                        new NumberDataPointWriter(point));
            }
        }
    }

    private static class SumWriter implements ProtobufWriter.MessageContent {
        private final MetricData metric;

        SumWriter(MetricData metric) {
            this.metric = metric;
        }

        @Override
        public void writeTo(ProtobufWriter writer) {
            // repeated NumberDataPoint data_points = 1
            for (NumberDataPoint point : metric.getNumberDataPoints()) {
                writer.writeMessageField(OTLPFieldNumbers.SUM_DATA_POINTS,
                        new NumberDataPointWriter(point));
            }

            // AggregationTemporality aggregation_temporality = 2
            writer.writeVarintField(OTLPFieldNumbers.SUM_AGGREGATION_TEMPORALITY,
                    metric.getTemporality().getProtoValue());

            // bool is_monotonic = 3
            writer.writeBoolField(OTLPFieldNumbers.SUM_IS_MONOTONIC, metric.isMonotonic());
        }
    }

    private static class HistogramWriter implements ProtobufWriter.MessageContent {
        private final MetricData metric;

        HistogramWriter(MetricData metric) {
            this.metric = metric;
        }

        @Override
        public void writeTo(ProtobufWriter writer) {
            // repeated HistogramDataPoint data_points = 1
            for (HistogramDataPoint point : metric.getHistogramDataPoints()) {
                writer.writeMessageField(OTLPFieldNumbers.HISTOGRAM_DATA_POINTS,
                        new HistogramDataPointWriter(point));
            }

            // AggregationTemporality aggregation_temporality = 2
            writer.writeVarintField(OTLPFieldNumbers.HISTOGRAM_AGGREGATION_TEMPORALITY,
                    metric.getTemporality().getProtoValue());
        }
    }

    private static class NumberDataPointWriter implements ProtobufWriter.MessageContent {
        private final NumberDataPoint point;

        NumberDataPointWriter(NumberDataPoint point) {
            this.point = point;
        }

        @Override
        public void writeTo(ProtobufWriter writer) {
            // fixed64 start_time_unix_nano = 2
            writer.writeFixed64Field(OTLPFieldNumbers.NUMBER_DATA_POINT_START_TIME_UNIX_NANO,
                    point.getStartTimeUnixNano());

            // fixed64 time_unix_nano = 3
            writer.writeFixed64Field(OTLPFieldNumbers.NUMBER_DATA_POINT_TIME_UNIX_NANO,
                    point.getTimeUnixNano());

            // value (field 4 = double, field 6 = int)
            if (point.isDouble()) {
                writer.writeDoubleField(OTLPFieldNumbers.NUMBER_DATA_POINT_AS_DOUBLE,
                        point.getDoubleValue());
            } else {
                writer.writeFixed64Field(OTLPFieldNumbers.NUMBER_DATA_POINT_AS_INT,
                        point.getLongValue());
            }

            // repeated KeyValue attributes = 7
            Attributes attrs = point.getAttributes();
            if (attrs != null && !attrs.isEmpty()) {
                for (Attribute attr : attrs.asList()) {
                    writer.writeMessageField(OTLPFieldNumbers.NUMBER_DATA_POINT_ATTRIBUTES,
                            new AttributeWriter(attr));
                }
            }
        }
    }

    private static class HistogramDataPointWriter implements ProtobufWriter.MessageContent {
        private final HistogramDataPoint point;

        HistogramDataPointWriter(HistogramDataPoint point) {
            this.point = point;
        }

        @Override
        public void writeTo(ProtobufWriter writer) {
            // fixed64 start_time_unix_nano = 2
            writer.writeFixed64Field(OTLPFieldNumbers.HISTOGRAM_DATA_POINT_START_TIME_UNIX_NANO,
                    point.getStartTimeUnixNano());

            // fixed64 time_unix_nano = 3
            writer.writeFixed64Field(OTLPFieldNumbers.HISTOGRAM_DATA_POINT_TIME_UNIX_NANO,
                    point.getTimeUnixNano());

            // fixed64 count = 4
            writer.writeFixed64Field(OTLPFieldNumbers.HISTOGRAM_DATA_POINT_COUNT,
                    point.getCount());

            // double sum = 5
            writer.writeDoubleField(OTLPFieldNumbers.HISTOGRAM_DATA_POINT_SUM, point.getSum());

            // repeated fixed64 bucket_counts = 6 (packed)
            long[] bucketCounts = point.getBucketCounts();
            for (long count : bucketCounts) {
                writer.writeFixed64Field(OTLPFieldNumbers.HISTOGRAM_DATA_POINT_BUCKET_COUNTS, count);
            }

            // repeated double explicit_bounds = 7 (packed)
            double[] bounds = point.getExplicitBounds();
            for (double bound : bounds) {
                writer.writeDoubleField(OTLPFieldNumbers.HISTOGRAM_DATA_POINT_EXPLICIT_BOUNDS, bound);
            }

            // repeated KeyValue attributes = 9
            Attributes attrs = point.getAttributes();
            if (attrs != null && !attrs.isEmpty()) {
                for (Attribute attr : attrs.asList()) {
                    writer.writeMessageField(OTLPFieldNumbers.HISTOGRAM_DATA_POINT_ATTRIBUTES,
                            new AttributeWriter(attr));
                }
            }

            // double min = 11
            writer.writeDoubleField(OTLPFieldNumbers.HISTOGRAM_DATA_POINT_MIN, point.getMin());

            // double max = 12
            writer.writeDoubleField(OTLPFieldNumbers.HISTOGRAM_DATA_POINT_MAX, point.getMax());
        }
    }

    private static class AttributeWriter implements ProtobufWriter.MessageContent {
        private final Attribute attr;

        AttributeWriter(Attribute attr) {
            this.attr = attr;
        }

        @Override
        public void writeTo(ProtobufWriter writer) {
            // string key = 1
            writer.writeStringField(OTLPFieldNumbers.KEY_VALUE_KEY, attr.getKey());

            // AnyValue value = 2
            writer.writeMessageField(OTLPFieldNumbers.KEY_VALUE_VALUE,
                    new AnyValueWriter(attr));
        }
    }

    private static class AnyValueWriter implements ProtobufWriter.MessageContent {
        private final Attribute attr;

        AnyValueWriter(Attribute attr) {
            this.attr = attr;
        }

        @Override
        public void writeTo(ProtobufWriter writer) {
            switch (attr.getType()) {
                case Attribute.TYPE_STRING:
                    writer.writeStringField(OTLPFieldNumbers.ANY_VALUE_STRING_VALUE,
                            attr.getStringValue());
                    break;
                case Attribute.TYPE_BOOL:
                    writer.writeBoolField(OTLPFieldNumbers.ANY_VALUE_BOOL_VALUE,
                            attr.getBoolValue());
                    break;
                case Attribute.TYPE_INT:
                    writer.writeVarintField(OTLPFieldNumbers.ANY_VALUE_INT_VALUE,
                            attr.getIntValue());
                    break;
                case Attribute.TYPE_DOUBLE:
                    writer.writeDoubleField(OTLPFieldNumbers.ANY_VALUE_DOUBLE_VALUE,
                            attr.getDoubleValue());
                    break;
            }
        }
    }

    // -- Helper method --

    private static void writeKeyValue(ProtobufWriter writer, int fieldNumber,
                                       String key, String value) {
        writer.writeMessageField(fieldNumber, new StringKeyValueWriter(key, value));
    }

    private static class StringKeyValueWriter implements ProtobufWriter.MessageContent {
        private final String key;
        private final String value;

        StringKeyValueWriter(String key, String value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public void writeTo(ProtobufWriter writer) {
            writer.writeStringField(OTLPFieldNumbers.KEY_VALUE_KEY, key);
            writer.writeMessageField(OTLPFieldNumbers.KEY_VALUE_VALUE,
                    new StringAnyValueWriter(value));
        }
    }

    private static class StringAnyValueWriter implements ProtobufWriter.MessageContent {
        private final String value;

        StringAnyValueWriter(String value) {
            this.value = value;
        }

        @Override
        public void writeTo(ProtobufWriter writer) {
            writer.writeStringField(OTLPFieldNumbers.ANY_VALUE_STRING_VALUE, value);
        }
    }

}
