/*
 * LogSerializer.java
 * Copyright (C) 2025, 2026 Chris Burdess
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

import org.bluezoo.protobuf.ByteBufferChannel;
import org.bluezoo.protobuf.ProtobufWriter;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.LogRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes log records to OTLP protobuf format
 * ({@code ExportLogsServiceRequest}).
 *
 * <p>Each record is written under the instrumentation scope named by its
 * emitting class, with the record's key as {@code event_name} and its
 * attributes as they are: no resource-bundle lookup is done, so a
 * collector sees the event name and the named arguments and formats
 * them as it likes. A record with a throwable carries
 * {@code exception.type}, and the message and stack trace too when the
 * serializer is configured to include exception details.
 *
 * <p>The serializer can write directly to a {@link WritableByteChannel} for
 * streaming output, or to a {@link ByteBuffer} for buffered output.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LogSerializer {

    private static final String SCHEMA_URL = "https://opentelemetry.io/schemas/1.25.0";

    /** The scope of a record built outside an event logger. */
    static final String DEFAULT_SCOPE = "gumdrop";

    private final String serviceName;
    private final String serviceVersion;
    private final String serviceNamespace;
    private final Map<String, String> resourceAttributes;
    private final boolean includeExceptionDetails;

    /**
     * Creates a log serializer with the given service name.
     *
     * @param serviceName the service name for the Resource
     */
    public LogSerializer(String serviceName) {
        this(serviceName, null, null, null);
    }

    /**
     * Creates a log serializer that exports a throwable as its type alone.
     *
     * @param serviceName the service name
     * @param serviceVersion the service version, or null
     * @param serviceNamespace the service namespace, or null
     * @param resourceAttributes further resource attributes, or null
     */
    public LogSerializer(String serviceName, String serviceVersion,
                         String serviceNamespace, Map<String, String> resourceAttributes) {
        this(serviceName, serviceVersion, serviceNamespace, resourceAttributes, false);
    }

    /**
     * Creates a log serializer.
     *
     * @param serviceName the service name
     * @param serviceVersion the service version, or null
     * @param serviceNamespace the service namespace, or null
     * @param resourceAttributes further resource attributes, or null
     * @param includeExceptionDetails whether a record's throwable is exported with
     *        its message and stack trace, or as its type alone
     */
    public LogSerializer(String serviceName, String serviceVersion,
                         String serviceNamespace, Map<String, String> resourceAttributes,
                         boolean includeExceptionDetails) {
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.serviceNamespace = serviceNamespace;
        this.resourceAttributes = resourceAttributes;
        this.includeExceptionDetails = includeExceptionDetails;
    }

    /**
     * Serializes a batch of log records to a channel.
     *
     * @param records the records
     * @param channel the channel to write to
     * @throws IOException if writing fails
     */
    public void serialize(List<LogRecord> records, WritableByteChannel channel) throws IOException {
        ProtobufWriter writer = new ProtobufWriter(channel);
        // LogsData { repeated ResourceLogs resource_logs = 1; }
        writer.writeMessageField(OtlpFieldNumbers.LOGS_DATA_RESOURCE_LOGS,
                new ResourceLogsWriter(byScope(records)));
    }

    /**
     * Serializes a batch of log records to a buffer.
     *
     * @param records the records
     * @return the serialized bytes
     * @throws IOException if writing fails
     */
    public ByteBuffer serialize(List<LogRecord> records) throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel();
        serialize(records, channel);
        return channel.toByteBuffer();
    }

    /**
     * Serializes one log record to a channel.
     *
     * @param record the record
     * @param channel the channel to write to
     * @throws IOException if writing fails
     */
    public void serialize(LogRecord record, WritableByteChannel channel) throws IOException {
        List<LogRecord> one = new ArrayList<LogRecord>(1);
        one.add(record);
        serialize(one, channel);
    }

    /**
     * Serializes one log record to a buffer.
     *
     * @param record the record
     * @return the serialized bytes
     * @throws IOException if writing fails
     */
    public ByteBuffer serialize(LogRecord record) throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel();
        serialize(record, channel);
        return channel.toByteBuffer();
    }

    /**
     * Returns the instrumentation scope of a record: its emitting class,
     * or the server itself for a record built outside a logger.
     */
    static String scopeOf(LogRecord record) {
        String scope = record.getScope();
        return scope != null ? scope : DEFAULT_SCOPE;
    }

    /**
     * Groups records by scope, each group and the groups themselves in
     * order of first appearance.
     */
    static Map<String, List<LogRecord>> byScope(List<LogRecord> records) {
        Map<String, List<LogRecord>> groups = new LinkedHashMap<String, List<LogRecord>>();
        for (LogRecord record : records) {
            String scope = scopeOf(record);
            List<LogRecord> group = groups.get(scope);
            if (group == null) {
                group = new ArrayList<LogRecord>();
                groups.put(scope, group);
            }
            group.add(record);
        }
        return groups;
    }

    // -- Inner classes for message content --

    private class ResourceLogsWriter implements ProtobufWriter.MessageContent {
        private final Map<String, List<LogRecord>> groups;

        ResourceLogsWriter(Map<String, List<LogRecord>> groups) {
            this.groups = groups;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // Resource resource = 1
            writer.writeMessageField(OtlpFieldNumbers.RESOURCE_LOGS_RESOURCE,
                    new ResourceWriter());

            // repeated ScopeLogs scope_logs = 2, one per instrumentation scope
            for (Map.Entry<String, List<LogRecord>> group : groups.entrySet()) {
                writer.writeMessageField(OtlpFieldNumbers.RESOURCE_LOGS_SCOPE_LOGS,
                        new ScopeLogsWriter(group.getKey(), group.getValue()));
            }

            // string schema_url = 3
            writer.writeStringField(OtlpFieldNumbers.RESOURCE_LOGS_SCHEMA_URL, SCHEMA_URL);
        }
    }

    private class ResourceWriter implements ProtobufWriter.MessageContent {
        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // repeated KeyValue attributes = 1
            writeKeyValue(writer, OtlpFieldNumbers.RESOURCE_ATTRIBUTES,
                    "service.name", serviceName);

            if (serviceVersion != null) {
                writeKeyValue(writer, OtlpFieldNumbers.RESOURCE_ATTRIBUTES,
                        "service.version", serviceVersion);
            }

            if (serviceNamespace != null) {
                writeKeyValue(writer, OtlpFieldNumbers.RESOURCE_ATTRIBUTES,
                        "service.namespace", serviceNamespace);
            }

            if (resourceAttributes != null) {
                for (Map.Entry<String, String> entry : resourceAttributes.entrySet()) {
                    writeKeyValue(writer, OtlpFieldNumbers.RESOURCE_ATTRIBUTES,
                            entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private class ScopeLogsWriter implements ProtobufWriter.MessageContent {
        private final String scope;
        private final List<LogRecord> records;

        ScopeLogsWriter(String scope, List<LogRecord> records) {
            this.scope = scope;
            this.records = records;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // InstrumentationScope scope = 1
            writer.writeMessageField(OtlpFieldNumbers.SCOPE_LOGS_SCOPE,
                    new InstrumentationScopeWriter(scope));

            // repeated LogRecord log_records = 2
            for (LogRecord record : records) {
                writer.writeMessageField(OtlpFieldNumbers.SCOPE_LOGS_LOG_RECORDS,
                        new LogRecordWriter(record, includeExceptionDetails));
            }
        }
    }

    private static class InstrumentationScopeWriter implements ProtobufWriter.MessageContent {
        private final String name;

        InstrumentationScopeWriter(String name) {
            this.name = name;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // string name = 1
            writer.writeStringField(OtlpFieldNumbers.INSTRUMENTATION_SCOPE_NAME, name);
            // string version = 2
            writer.writeStringField(OtlpFieldNumbers.INSTRUMENTATION_SCOPE_VERSION, Gumdrop.VERSION);
        }
    }

    private static class LogRecordWriter implements ProtobufWriter.MessageContent {
        private final LogRecord record;
        private final boolean includeExceptionDetails;

        LogRecordWriter(LogRecord record, boolean includeExceptionDetails) {
            this.record = record;
            this.includeExceptionDetails = includeExceptionDetails;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // fixed64 time_unix_nano = 1
            writer.writeFixed64Field(OtlpFieldNumbers.LOG_RECORD_TIME_UNIX_NANO,
                    record.getTimeUnixNano());

            // fixed64 observed_time_unix_nano = 11
            writer.writeFixed64Field(OtlpFieldNumbers.LOG_RECORD_OBSERVED_TIME_UNIX_NANO,
                    record.getTimeUnixNano());

            // SeverityNumber severity_number = 2
            writer.writeVarintField(OtlpFieldNumbers.LOG_RECORD_SEVERITY_NUMBER,
                    record.getSeverityNumber());

            // string severity_text = 3
            writer.writeStringField(OtlpFieldNumbers.LOG_RECORD_SEVERITY_TEXT,
                    record.getSeverityText());

            // AnyValue body = 5
            if (record.getBody() != null) {
                writer.writeMessageField(OtlpFieldNumbers.LOG_RECORD_BODY,
                        new StringAnyValueWriter(record.getBody()));
            }

            // repeated KeyValue attributes = 6
            for (Attribute attr : record.exportAttributes(includeExceptionDetails)) {
                writer.writeMessageField(OtlpFieldNumbers.LOG_RECORD_ATTRIBUTES,
                        new AttributeWriter(attr));
            }

            // bytes trace_id = 9
            if (record.hasSpanContext()) {
                writer.writeBytesField(OtlpFieldNumbers.LOG_RECORD_TRACE_ID,
                        record.getTraceId().getBytes());

                // bytes span_id = 10
                writer.writeBytesField(OtlpFieldNumbers.LOG_RECORD_SPAN_ID,
                        record.getSpanId().getBytes());
            }

            // string event_name = 12
            writer.writeStringField(OtlpFieldNumbers.LOG_RECORD_EVENT_NAME, record.getKey());
        }
    }

    private static class AttributeWriter implements ProtobufWriter.MessageContent {
        private final Attribute attr;

        AttributeWriter(Attribute attr) {
            this.attr = attr;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // string key = 1
            writer.writeStringField(OtlpFieldNumbers.KEY_VALUE_KEY, attr.getKey());

            // AnyValue value = 2
            writer.writeMessageField(OtlpFieldNumbers.KEY_VALUE_VALUE,
                    new AnyValueWriter(attr));
        }
    }

    private static class AnyValueWriter implements ProtobufWriter.MessageContent {
        private final Attribute attr;

        AnyValueWriter(Attribute attr) {
            this.attr = attr;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            switch (attr.getType()) {
                case Attribute.TYPE_STRING:
                    writer.writeStringField(OtlpFieldNumbers.ANY_VALUE_STRING_VALUE,
                            attr.getStringValue());
                    break;
                case Attribute.TYPE_BOOL:
                    writer.writeBoolField(OtlpFieldNumbers.ANY_VALUE_BOOL_VALUE,
                            attr.getBoolValue());
                    break;
                case Attribute.TYPE_INT:
                    writer.writeVarintField(OtlpFieldNumbers.ANY_VALUE_INT_VALUE,
                            attr.getIntValue());
                    break;
                case Attribute.TYPE_DOUBLE:
                    writer.writeDoubleField(OtlpFieldNumbers.ANY_VALUE_DOUBLE_VALUE,
                            attr.getDoubleValue());
                    break;
            }
        }
    }

    // -- Helper methods --

    private static void writeKeyValue(ProtobufWriter writer, int fieldNumber,
                                       String key, String value) throws IOException {
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
        public void writeTo(ProtobufWriter writer) throws IOException {
            writer.writeStringField(OtlpFieldNumbers.KEY_VALUE_KEY, key);
            writer.writeMessageField(OtlpFieldNumbers.KEY_VALUE_VALUE,
                    new StringAnyValueWriter(value));
        }
    }

    private static class StringAnyValueWriter implements ProtobufWriter.MessageContent {
        private final String value;

        StringAnyValueWriter(String value) {
            this.value = value;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            writer.writeStringField(OtlpFieldNumbers.ANY_VALUE_STRING_VALUE, value);
        }
    }
}
