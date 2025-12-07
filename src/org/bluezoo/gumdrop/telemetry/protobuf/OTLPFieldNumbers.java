/*
 * OTLPFieldNumbers.java
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

/**
 * Field numbers for OTLP protobuf messages.
 * Based on opentelemetry-proto definitions.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class OTLPFieldNumbers {

    private OTLPFieldNumbers() {
    }

    // TracesData
    static final int TRACES_DATA_RESOURCE_SPANS = 1;

    // ResourceSpans
    static final int RESOURCE_SPANS_RESOURCE = 1;
    static final int RESOURCE_SPANS_SCOPE_SPANS = 2;
    static final int RESOURCE_SPANS_SCHEMA_URL = 3;

    // Resource
    static final int RESOURCE_ATTRIBUTES = 1;
    static final int RESOURCE_DROPPED_ATTRIBUTES_COUNT = 2;

    // ScopeSpans
    static final int SCOPE_SPANS_SCOPE = 1;
    static final int SCOPE_SPANS_SPANS = 2;
    static final int SCOPE_SPANS_SCHEMA_URL = 3;

    // InstrumentationScope
    static final int INSTRUMENTATION_SCOPE_NAME = 1;
    static final int INSTRUMENTATION_SCOPE_VERSION = 2;
    static final int INSTRUMENTATION_SCOPE_ATTRIBUTES = 3;
    static final int INSTRUMENTATION_SCOPE_DROPPED_ATTRIBUTES_COUNT = 4;

    // Span
    static final int SPAN_TRACE_ID = 1;
    static final int SPAN_SPAN_ID = 2;
    static final int SPAN_TRACE_STATE = 3;
    static final int SPAN_PARENT_SPAN_ID = 4;
    static final int SPAN_NAME = 5;
    static final int SPAN_KIND = 6;
    static final int SPAN_START_TIME_UNIX_NANO = 7;
    static final int SPAN_END_TIME_UNIX_NANO = 8;
    static final int SPAN_ATTRIBUTES = 9;
    static final int SPAN_DROPPED_ATTRIBUTES_COUNT = 10;
    static final int SPAN_EVENTS = 11;
    static final int SPAN_DROPPED_EVENTS_COUNT = 12;
    static final int SPAN_LINKS = 13;
    static final int SPAN_DROPPED_LINKS_COUNT = 14;
    static final int SPAN_STATUS = 15;

    // Span.Event
    static final int EVENT_TIME_UNIX_NANO = 1;
    static final int EVENT_NAME = 2;
    static final int EVENT_ATTRIBUTES = 3;
    static final int EVENT_DROPPED_ATTRIBUTES_COUNT = 4;

    // Span.Link
    static final int LINK_TRACE_ID = 1;
    static final int LINK_SPAN_ID = 2;
    static final int LINK_TRACE_STATE = 3;
    static final int LINK_ATTRIBUTES = 4;
    static final int LINK_DROPPED_ATTRIBUTES_COUNT = 5;
    static final int LINK_FLAGS = 6;

    // Status
    static final int STATUS_MESSAGE = 2;
    static final int STATUS_CODE = 3;

    // KeyValue
    static final int KEY_VALUE_KEY = 1;
    static final int KEY_VALUE_VALUE = 2;

    // AnyValue
    static final int ANY_VALUE_STRING_VALUE = 1;
    static final int ANY_VALUE_BOOL_VALUE = 2;
    static final int ANY_VALUE_INT_VALUE = 3;
    static final int ANY_VALUE_DOUBLE_VALUE = 4;
    static final int ANY_VALUE_ARRAY_VALUE = 5;
    static final int ANY_VALUE_KVLIST_VALUE = 6;
    static final int ANY_VALUE_BYTES_VALUE = 7;

    // LogsData
    static final int LOGS_DATA_RESOURCE_LOGS = 1;

    // ResourceLogs
    static final int RESOURCE_LOGS_RESOURCE = 1;
    static final int RESOURCE_LOGS_SCOPE_LOGS = 2;
    static final int RESOURCE_LOGS_SCHEMA_URL = 3;

    // ScopeLogs
    static final int SCOPE_LOGS_SCOPE = 1;
    static final int SCOPE_LOGS_LOG_RECORDS = 2;
    static final int SCOPE_LOGS_SCHEMA_URL = 3;

    // LogRecord
    static final int LOG_RECORD_TIME_UNIX_NANO = 1;
    static final int LOG_RECORD_OBSERVED_TIME_UNIX_NANO = 11;
    static final int LOG_RECORD_SEVERITY_NUMBER = 2;
    static final int LOG_RECORD_SEVERITY_TEXT = 3;
    static final int LOG_RECORD_BODY = 5;
    static final int LOG_RECORD_ATTRIBUTES = 6;
    static final int LOG_RECORD_DROPPED_ATTRIBUTES_COUNT = 7;
    static final int LOG_RECORD_FLAGS = 8;
    static final int LOG_RECORD_TRACE_ID = 9;
    static final int LOG_RECORD_SPAN_ID = 10;

    // ========== METRICS ==========

    // MetricsData
    static final int METRICS_DATA_RESOURCE_METRICS = 1;

    // ResourceMetrics
    static final int RESOURCE_METRICS_RESOURCE = 1;
    static final int RESOURCE_METRICS_SCOPE_METRICS = 2;
    static final int RESOURCE_METRICS_SCHEMA_URL = 3;

    // ScopeMetrics
    static final int SCOPE_METRICS_SCOPE = 1;
    static final int SCOPE_METRICS_METRICS = 2;
    static final int SCOPE_METRICS_SCHEMA_URL = 3;

    // Metric
    static final int METRIC_NAME = 1;
    static final int METRIC_DESCRIPTION = 2;
    static final int METRIC_UNIT = 3;
    static final int METRIC_GAUGE = 5;
    static final int METRIC_SUM = 7;
    static final int METRIC_HISTOGRAM = 9;
    static final int METRIC_EXPONENTIAL_HISTOGRAM = 10;
    static final int METRIC_SUMMARY = 11;

    // Gauge
    static final int GAUGE_DATA_POINTS = 1;

    // Sum
    static final int SUM_DATA_POINTS = 1;
    static final int SUM_AGGREGATION_TEMPORALITY = 2;
    static final int SUM_IS_MONOTONIC = 3;

    // Histogram
    static final int HISTOGRAM_DATA_POINTS = 1;
    static final int HISTOGRAM_AGGREGATION_TEMPORALITY = 2;

    // NumberDataPoint
    static final int NUMBER_DATA_POINT_ATTRIBUTES = 7;
    static final int NUMBER_DATA_POINT_START_TIME_UNIX_NANO = 2;
    static final int NUMBER_DATA_POINT_TIME_UNIX_NANO = 3;
    static final int NUMBER_DATA_POINT_AS_DOUBLE = 4;
    static final int NUMBER_DATA_POINT_AS_INT = 6;
    static final int NUMBER_DATA_POINT_EXEMPLARS = 5;
    static final int NUMBER_DATA_POINT_FLAGS = 8;

    // HistogramDataPoint
    static final int HISTOGRAM_DATA_POINT_ATTRIBUTES = 9;
    static final int HISTOGRAM_DATA_POINT_START_TIME_UNIX_NANO = 2;
    static final int HISTOGRAM_DATA_POINT_TIME_UNIX_NANO = 3;
    static final int HISTOGRAM_DATA_POINT_COUNT = 4;
    static final int HISTOGRAM_DATA_POINT_SUM = 5;
    static final int HISTOGRAM_DATA_POINT_BUCKET_COUNTS = 6;
    static final int HISTOGRAM_DATA_POINT_EXPLICIT_BOUNDS = 7;
    static final int HISTOGRAM_DATA_POINT_EXEMPLARS = 8;
    static final int HISTOGRAM_DATA_POINT_FLAGS = 10;
    static final int HISTOGRAM_DATA_POINT_MIN = 11;
    static final int HISTOGRAM_DATA_POINT_MAX = 12;

}


