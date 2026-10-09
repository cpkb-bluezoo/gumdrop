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
import org.bluezoo.gumdrop.telemetry.LogRecord;
import org.bluezoo.json.JSONWriter;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes log records to OTLP JSON format.
 *
 * <p>Produces {@code ExportLogsServiceRequest} JSON objects conforming to the
 * OTLP JSON Protobuf encoding. Each record is written under the
 * instrumentation scope named by its emitting class, with the record's
 * key as {@code eventName} and its attributes as they are: no
 * resource-bundle lookup is done, so a collector sees the event name
 * and the named arguments and formats them as it likes. A record with a
 * throwable carries {@code exception.type}, and the message and stack
 * trace too when the serializer is configured to include exception
 * details.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://opentelemetry.io/docs/specs/otlp/">OTLP Specification</a>
 */
public class LogJsonSerializer {

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
    public LogJsonSerializer(String serviceName) {
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
    public LogJsonSerializer(String serviceName, String serviceVersion,
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
    public LogJsonSerializer(String serviceName, String serviceVersion,
                             String serviceNamespace, Map<String, String> resourceAttributes,
                             boolean includeExceptionDetails) {
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.serviceNamespace = serviceNamespace;
        this.resourceAttributes = resourceAttributes;
        this.includeExceptionDetails = includeExceptionDetails;
    }

    /**
     * Serializes one log record as an {@code ExportLogsServiceRequest}.
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
     * Serializes a batch of log records as one {@code ExportLogsServiceRequest}.
     * Nothing is written for an empty batch.
     *
     * @param records the records
     * @param channel the channel to write to
     * @throws IOException if writing fails
     */
    public void serialize(List<LogRecord> records, WritableByteChannel channel) throws IOException {
        if (records == null || records.isEmpty()) {
            return;
        }
        JSONWriter w = new JSONWriter(channel);
        w.writeStartObject();
        w.writeKey("resourceLogs");
        w.writeStartArray();
        writeResourceLogs(w, byScope(records));
        w.writeEndArray();
        w.writeEndObject();
        w.close();
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

    private void writeResourceLogs(JSONWriter w, Map<String, List<LogRecord>> groups) throws IOException {
        w.writeStartObject();

        w.writeKey("resource");
        writeResource(w);

        w.writeKey("scopeLogs");
        w.writeStartArray();
        for (Map.Entry<String, List<LogRecord>> group : groups.entrySet()) {
            writeScopeLogs(w, group.getKey(), group.getValue());
        }
        w.writeEndArray();

        w.writeKey("schemaUrl");
        w.writeString(SCHEMA_URL);

        w.writeEndObject();
    }

    private void writeResource(JSONWriter w) throws IOException {
        w.writeStartObject();
        w.writeKey("attributes");
        w.writeStartArray();

        OtlpJsonUtil.writeStringKeyValue(w, "service.name", serviceName);

        if (serviceVersion != null) {
            OtlpJsonUtil.writeStringKeyValue(w, "service.version", serviceVersion);
        }
        if (serviceNamespace != null) {
            OtlpJsonUtil.writeStringKeyValue(w, "service.namespace", serviceNamespace);
        }
        if (resourceAttributes != null) {
            for (Map.Entry<String, String> entry : resourceAttributes.entrySet()) {
                OtlpJsonUtil.writeStringKeyValue(w, entry.getKey(), entry.getValue());
            }
        }

        w.writeEndArray();
        w.writeEndObject();
    }

    private void writeScopeLogs(JSONWriter w, String scope, List<LogRecord> records) throws IOException {
        w.writeStartObject();

        w.writeKey("scope");
        w.writeStartObject();
        w.writeKey("name");
        w.writeString(scope);
        w.writeKey("version");
        w.writeString(Gumdrop.VERSION);
        w.writeEndObject();

        w.writeKey("logRecords");
        w.writeStartArray();
        for (LogRecord record : records) {
            writeLogRecord(w, record);
        }
        w.writeEndArray();

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

        List<Attribute> attributes = record.exportAttributes(includeExceptionDetails);
        if (!attributes.isEmpty()) {
            w.writeKey("attributes");
            OtlpJsonUtil.writeAttributes(w, attributes);
        }

        if (record.hasSpanContext()) {
            w.writeKey("traceId");
            w.writeString(record.getTraceId().toHexString());

            w.writeKey("spanId");
            w.writeString(record.getSpanId().toHexString());
        }

        w.writeKey("eventName");
        w.writeString(record.getKey());

        w.writeEndObject();
    }

}
