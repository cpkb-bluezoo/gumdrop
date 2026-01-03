/*
 * LogSerializer.java
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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.gumdrop.telemetry.LogRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Map;

/**
 * Serializes log records to OTLP protobuf format.
 *
 * <p>The serializer can write directly to a {@link WritableByteChannel} for
 * streaming output, or to a {@link ByteBuffer} for buffered output.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class LogSerializer {

    private static final String SCHEMA_URL = "https://opentelemetry.io/schemas/1.25.0";

    private final String serviceName;
    private final String serviceVersion;
    private final String serviceNamespace;
    private final Map<String, String> resourceAttributes;

    /**
     * Creates a log serializer with the given service name.
     *
     * @param serviceName the service name for the Resource
     */
    public LogSerializer(String serviceName) {
        this(serviceName, null, null, null);
    }

    /**
     * Creates a log serializer with service metadata.
     *
     * @param serviceName the service name
     * @param serviceVersion the service version
     * @param serviceNamespace the service namespace
     * @param resourceAttributes additional resource attributes
     */
    public LogSerializer(String serviceName, String serviceVersion,
                         String serviceNamespace, Map<String, String> resourceAttributes) {
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.serviceNamespace = serviceNamespace;
        this.resourceAttributes = resourceAttributes;
    }

    /**
     * Serializes log records to OTLP LogsData format, writing to a channel.
     *
     * <p>This is the primary serialization method. Data is streamed to the
     * channel as it is serialized.
     *
     * @param records the log records to serialize
     * @param channel the channel to write to
     * @throws IOException if an I/O error occurs
     */
    public void serialize(List<LogRecord> records, WritableByteChannel channel) throws IOException {
        ProtobufWriter writer = new ProtobufWriter(channel);
        writeLogsData(writer, records);
    }

    /**
     * Serializes log records to OTLP LogsData format, returning a ByteBuffer.
     *
     * <p>Convenience method for callers who need buffered output.
     *
     * @param records the log records to serialize
     * @return a ByteBuffer containing the serialized logs (ready for reading)
     * @throws IOException if serialization fails
     */
    public ByteBuffer serialize(List<LogRecord> records) throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel();
        serialize(records, channel);
        return channel.toByteBuffer();
    }

    /**
     * Serializes a single log record to OTLP LogsData format, writing to a channel.
     *
     * @param record the log record to serialize
     * @param channel the channel to write to
     * @throws IOException if an I/O error occurs
     */
    public void serialize(LogRecord record, WritableByteChannel channel) throws IOException {
        ProtobufWriter writer = new ProtobufWriter(channel);
        writer.writeMessageField(OTLPFieldNumbers.LOGS_DATA_RESOURCE_LOGS,
                new ResourceLogsWriter(record));
    }

    /**
     * Serializes a single log record to OTLP LogsData format, returning a ByteBuffer.
     *
     * @param record the log record to serialize
     * @return a ByteBuffer containing the serialized log (ready for reading)
     * @throws IOException if serialization fails
     */
    public ByteBuffer serialize(LogRecord record) throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel();
        serialize(record, channel);
        return channel.toByteBuffer();
    }

    private void writeLogsData(ProtobufWriter writer, List<LogRecord> records) throws IOException {
        // LogsData { repeated ResourceLogs resource_logs = 1; }
        writer.writeMessageField(OTLPFieldNumbers.LOGS_DATA_RESOURCE_LOGS,
                new ResourceLogsListWriter(records));
    }

    // -- Inner classes for message content --

    private class ResourceLogsWriter implements ProtobufWriter.MessageContent {
        private final LogRecord record;

        ResourceLogsWriter(LogRecord record) {
            this.record = record;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // Resource resource = 1
            writer.writeMessageField(OTLPFieldNumbers.RESOURCE_LOGS_RESOURCE,
                    new ResourceWriter());

            // repeated ScopeLogs scope_logs = 2
            writer.writeMessageField(OTLPFieldNumbers.RESOURCE_LOGS_SCOPE_LOGS,
                    new ScopeLogsWriter(record));

            // string schema_url = 3
            writer.writeStringField(OTLPFieldNumbers.RESOURCE_LOGS_SCHEMA_URL, SCHEMA_URL);
        }
    }

    private class ResourceLogsListWriter implements ProtobufWriter.MessageContent {
        private final List<LogRecord> records;

        ResourceLogsListWriter(List<LogRecord> records) {
            this.records = records;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // Resource resource = 1
            writer.writeMessageField(OTLPFieldNumbers.RESOURCE_LOGS_RESOURCE,
                    new ResourceWriter());

            // repeated ScopeLogs scope_logs = 2
            writer.writeMessageField(OTLPFieldNumbers.RESOURCE_LOGS_SCOPE_LOGS,
                    new ScopeLogsListWriter(records));

            // string schema_url = 3
            writer.writeStringField(OTLPFieldNumbers.RESOURCE_LOGS_SCHEMA_URL, SCHEMA_URL);
        }
    }

    private class ResourceWriter implements ProtobufWriter.MessageContent {
        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
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

    private class ScopeLogsWriter implements ProtobufWriter.MessageContent {
        private final LogRecord record;

        ScopeLogsWriter(LogRecord record) {
            this.record = record;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // InstrumentationScope scope = 1
            writer.writeMessageField(OTLPFieldNumbers.SCOPE_LOGS_SCOPE,
                    new InstrumentationScopeWriter());

            // repeated LogRecord log_records = 2
            writer.writeMessageField(OTLPFieldNumbers.SCOPE_LOGS_LOG_RECORDS,
                    new LogRecordWriter(record));
        }
    }

    private class ScopeLogsListWriter implements ProtobufWriter.MessageContent {
        private final List<LogRecord> records;

        ScopeLogsListWriter(List<LogRecord> records) {
            this.records = records;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // InstrumentationScope scope = 1
            writer.writeMessageField(OTLPFieldNumbers.SCOPE_LOGS_SCOPE,
                    new InstrumentationScopeWriter());

            // repeated LogRecord log_records = 2
            for (LogRecord record : records) {
                writer.writeMessageField(OTLPFieldNumbers.SCOPE_LOGS_LOG_RECORDS,
                        new LogRecordWriter(record));
            }
        }
    }

    private static class InstrumentationScopeWriter implements ProtobufWriter.MessageContent {
        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // string name = 1
            writer.writeStringField(OTLPFieldNumbers.INSTRUMENTATION_SCOPE_NAME, "gumdrop");
            // string version = 2
            writer.writeStringField(OTLPFieldNumbers.INSTRUMENTATION_SCOPE_VERSION, Gumdrop.VERSION);
        }
    }

    private static class LogRecordWriter implements ProtobufWriter.MessageContent {
        private final LogRecord record;

        LogRecordWriter(LogRecord record) {
            this.record = record;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // fixed64 time_unix_nano = 1
            writer.writeFixed64Field(OTLPFieldNumbers.LOG_RECORD_TIME_UNIX_NANO,
                    record.getTimeUnixNano());

            // fixed64 observed_time_unix_nano = 11
            writer.writeFixed64Field(OTLPFieldNumbers.LOG_RECORD_OBSERVED_TIME_UNIX_NANO,
                    record.getTimeUnixNano());

            // SeverityNumber severity_number = 2
            writer.writeVarintField(OTLPFieldNumbers.LOG_RECORD_SEVERITY_NUMBER,
                    record.getSeverityNumber());

            // string severity_text = 3
            writer.writeStringField(OTLPFieldNumbers.LOG_RECORD_SEVERITY_TEXT,
                    record.getSeverityText());

            // AnyValue body = 5
            if (record.getBody() != null) {
                writer.writeMessageField(OTLPFieldNumbers.LOG_RECORD_BODY,
                        new StringAnyValueWriter(record.getBody()));
            }

            // repeated KeyValue attributes = 6
            for (Attribute attr : record.getAttributes()) {
                writer.writeMessageField(OTLPFieldNumbers.LOG_RECORD_ATTRIBUTES,
                        new AttributeWriter(attr));
            }

            // bytes trace_id = 9
            if (record.hasSpanContext()) {
                writer.writeBytesField(OTLPFieldNumbers.LOG_RECORD_TRACE_ID,
                        record.getTraceId());

                // bytes span_id = 10
                writer.writeBytesField(OTLPFieldNumbers.LOG_RECORD_SPAN_ID,
                        record.getSpanId());
            }
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
        public void writeTo(ProtobufWriter writer) throws IOException {
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
        public void writeTo(ProtobufWriter writer) throws IOException {
            writer.writeStringField(OTLPFieldNumbers.ANY_VALUE_STRING_VALUE, value);
        }
    }
}
