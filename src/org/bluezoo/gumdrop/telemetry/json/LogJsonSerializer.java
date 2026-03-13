/*
 * LogJsonSerializer.java
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

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.telemetry.Attribute;
import org.bluezoo.util.ByteArrays;
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.json.JSONWriter;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Map;

/**
 * Serializes log records to OTLP JSON format.
 *
 * <p>Produces {@code ExportLogsServiceRequest} JSON objects conforming to the
 * OTLP JSON Protobuf encoding.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://opentelemetry.io/docs/specs/otlp/">OTLP Specification</a>
 */
public class LogJsonSerializer {

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
    public LogJsonSerializer(String serviceName) {
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
    public LogJsonSerializer(String serviceName, String serviceVersion,
                             String serviceNamespace, Map<String, String> resourceAttributes) {
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.serviceNamespace = serviceNamespace;
        this.resourceAttributes = resourceAttributes;
    }

    /**
     * Serializes a single log record as an {@code ExportLogsServiceRequest} JSON object.
     *
     * @param record the log record to serialize
     * @param channel the channel to write to
     * @throws IOException if an I/O error occurs
     */
    public void serialize(LogRecord record, WritableByteChannel channel) throws IOException {
        JSONWriter w = new JSONWriter(channel);
        w.writeStartObject();
        w.writeKey("resourceLogs");
        w.writeStartArray();
        writeResourceLogs(w, record);
        w.writeEndArray();
        w.writeEndObject();
        w.close();
    }

    /**
     * Serializes a batch of log records as an {@code ExportLogsServiceRequest} JSON object.
     *
     * @param records the log records to serialize
     * @param channel the channel to write to
     * @throws IOException if an I/O error occurs
     */
    public void serialize(List<LogRecord> records, WritableByteChannel channel) throws IOException {
        if (records == null || records.isEmpty()) {
            return;
        }
        JSONWriter w = new JSONWriter(channel);
        w.writeStartObject();
        w.writeKey("resourceLogs");
        w.writeStartArray();
        writeResourceLogsBatch(w, records);
        w.writeEndArray();
        w.writeEndObject();
        w.close();
    }

    private void writeResourceLogs(JSONWriter w, LogRecord record) throws IOException {
        w.writeStartObject();

        w.writeKey("resource");
        writeResource(w);

        w.writeKey("scopeLogs");
        w.writeStartArray();
        writeScopeLogs(w, record);
        w.writeEndArray();

        w.writeKey("schemaUrl");
        w.writeString(SCHEMA_URL);

        w.writeEndObject();
    }

    private void writeResourceLogsBatch(JSONWriter w, List<LogRecord> records) throws IOException {
        w.writeStartObject();

        w.writeKey("resource");
        writeResource(w);

        w.writeKey("scopeLogs");
        w.writeStartArray();
        writeScopeLogsBatch(w, records);
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

    private void writeScopeLogs(JSONWriter w, LogRecord record) throws IOException {
        w.writeStartObject();

        w.writeKey("scope");
        writeInstrumentationScope(w);

        w.writeKey("logRecords");
        w.writeStartArray();
        writeLogRecord(w, record);
        w.writeEndArray();

        w.writeEndObject();
    }

    private void writeScopeLogsBatch(JSONWriter w, List<LogRecord> records) throws IOException {
        w.writeStartObject();

        w.writeKey("scope");
        writeInstrumentationScope(w);

        w.writeKey("logRecords");
        w.writeStartArray();
        for (LogRecord record : records) {
            writeLogRecord(w, record);
        }
        w.writeEndArray();

        w.writeEndObject();
    }

    private void writeInstrumentationScope(JSONWriter w) throws IOException {
        w.writeStartObject();
        w.writeKey("name");
        w.writeString("gumdrop");
        w.writeKey("version");
        w.writeString(Gumdrop.VERSION);
        w.writeEndObject();
    }

    private void writeLogRecord(JSONWriter w, LogRecord record) throws IOException {
        w.writeStartObject();

        w.writeKey("timeUnixNano");
        w.writeString(Long.toString(record.getTimeUnixNano()));

        w.writeKey("observedTimeUnixNano");
        w.writeString(Long.toString(record.getTimeUnixNano()));

        w.writeKey("severityNumber");
        w.writeNumber(Integer.valueOf(record.getSeverityNumber()));

        w.writeKey("severityText");
        w.writeString(record.getSeverityText());

        if (record.getBody() != null) {
            w.writeKey("body");
            w.writeStartObject();
            w.writeKey("stringValue");
            w.writeString(record.getBody());
            w.writeEndObject();
        }

        List<Attribute> attributes = record.getAttributes();
        if (!attributes.isEmpty()) {
            w.writeKey("attributes");
            OTLPJsonUtil.writeAttributes(w, attributes);
        }

        if (record.hasSpanContext()) {
            w.writeKey("traceId");
            w.writeString(record.getTraceId().toHexString());

            w.writeKey("spanId");
            w.writeString(record.getSpanId().toHexString());
        }

        w.writeEndObject();
    }

}
