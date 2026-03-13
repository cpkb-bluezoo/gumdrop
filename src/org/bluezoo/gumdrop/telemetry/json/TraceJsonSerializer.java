/*
 * TraceJsonSerializer.java
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
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanEvent;
import org.bluezoo.gumdrop.telemetry.SpanLink;
import org.bluezoo.gumdrop.telemetry.SpanStatus;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.json.JSONWriter;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Map;

/**
 * Serializes traces to OTLP JSON format.
 *
 * <p>Produces {@code ExportTraceServiceRequest} JSON objects conforming to the
 * OTLP JSON Protobuf encoding. Byte fields (trace ID, span ID) are encoded
 * as lowercase hexadecimal strings. Timestamps are written as string
 * representations of nanosecond values, following the protobuf JSON mapping
 * for fixed64 fields.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://opentelemetry.io/docs/specs/otlp/">OTLP Specification</a>
 */
public class TraceJsonSerializer {

    private static final String SCHEMA_URL = "https://opentelemetry.io/schemas/1.25.0";

    private final String serviceName;
    private final String serviceVersion;
    private final String serviceNamespace;
    private final Map<String, String> resourceAttributes;

    /**
     * Creates a trace serializer with the given service name.
     *
     * @param serviceName the service name for the Resource
     */
    public TraceJsonSerializer(String serviceName) {
        this(serviceName, null, null, null);
    }

    /**
     * Creates a trace serializer with service metadata.
     *
     * @param serviceName the service name
     * @param serviceVersion the service version
     * @param serviceNamespace the service namespace
     * @param resourceAttributes additional resource attributes
     */
    public TraceJsonSerializer(String serviceName, String serviceVersion,
                               String serviceNamespace, Map<String, String> resourceAttributes) {
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.serviceNamespace = serviceNamespace;
        this.resourceAttributes = resourceAttributes;
    }

    /**
     * Serializes a trace as an {@code ExportTraceServiceRequest} JSON object.
     *
     * @param trace the trace to serialize
     * @param channel the channel to write to
     * @throws IOException if an I/O error occurs
     */
    public void serialize(Trace trace, WritableByteChannel channel) throws IOException {
        JSONWriter w = new JSONWriter(channel);
        writeExportTraceServiceRequest(w, trace);
        w.close();
    }

    private void writeExportTraceServiceRequest(JSONWriter w, Trace trace) throws IOException {
        w.writeStartObject();
        w.writeKey("resourceSpans");
        w.writeStartArray();
        writeResourceSpans(w, trace);
        w.writeEndArray();
        w.writeEndObject();
    }

    private void writeResourceSpans(JSONWriter w, Trace trace) throws IOException {
        w.writeStartObject();

        w.writeKey("resource");
        writeResource(w);

        w.writeKey("scopeSpans");
        w.writeStartArray();
        writeScopeSpans(w, trace);
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

    private void writeScopeSpans(JSONWriter w, Trace trace) throws IOException {
        w.writeStartObject();

        w.writeKey("scope");
        writeInstrumentationScope(w);

        w.writeKey("spans");
        w.writeStartArray();

        for (Span span : trace.getEndedSpans()) {
            writeSpan(w, span);
        }

        Span root = trace.getRootSpan();
        if (root.isEnded() && !trace.getEndedSpans().contains(root)) {
            writeSpan(w, root);
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

    private void writeSpan(JSONWriter w, Span span) throws IOException {
        w.writeStartObject();

        w.writeKey("traceId");
        w.writeString(span.getTrace().getTraceId().toHexString());

        w.writeKey("spanId");
        w.writeString(span.getSpanId().toHexString());

        Span parent = span.getParent();
        if (parent != null) {
            w.writeKey("parentSpanId");
            w.writeString(parent.getSpanId().toHexString());
        }

        w.writeKey("name");
        w.writeString(span.getName());

        w.writeKey("kind");
        w.writeNumber(Integer.valueOf(span.getKind().getValue()));

        w.writeKey("startTimeUnixNano");
        w.writeString(Long.toString(span.getStartTimeUnixNano()));

        w.writeKey("endTimeUnixNano");
        w.writeString(Long.toString(span.getEndTimeUnixNano()));

        List<Attribute> attributes = span.getAttributes();
        if (!attributes.isEmpty()) {
            w.writeKey("attributes");
            OTLPJsonUtil.writeAttributes(w, attributes);
        }

        List<SpanEvent> events = span.getEvents();
        if (!events.isEmpty()) {
            w.writeKey("events");
            w.writeStartArray();
            for (SpanEvent event : events) {
                writeEvent(w, event);
            }
            w.writeEndArray();
        }

        List<SpanLink> links = span.getLinks();
        if (!links.isEmpty()) {
            w.writeKey("links");
            w.writeStartArray();
            for (SpanLink link : links) {
                writeLink(w, link);
            }
            w.writeEndArray();
        }

        SpanStatus status = span.getStatus();
        if (status.getCode() != SpanStatus.STATUS_CODE_UNSET) {
            w.writeKey("status");
            writeStatus(w, status);
        }

        w.writeEndObject();
    }

    private void writeEvent(JSONWriter w, SpanEvent event) throws IOException {
        w.writeStartObject();

        w.writeKey("timeUnixNano");
        w.writeString(Long.toString(event.getTimeUnixNano()));

        w.writeKey("name");
        w.writeString(event.getName());

        List<Attribute> attributes = event.getAttributes();
        if (!attributes.isEmpty()) {
            w.writeKey("attributes");
            OTLPJsonUtil.writeAttributes(w, attributes);
        }

        w.writeEndObject();
    }

    private void writeLink(JSONWriter w, SpanLink link) throws IOException {
        w.writeStartObject();

        w.writeKey("traceId");
        w.writeString(link.getContext().getTraceId().toHexString());

        w.writeKey("spanId");
        w.writeString(link.getContext().getSpanId().toHexString());

        List<Attribute> attributes = link.getAttributes();
        if (!attributes.isEmpty()) {
            w.writeKey("attributes");
            OTLPJsonUtil.writeAttributes(w, attributes);
        }

        w.writeEndObject();
    }

    private void writeStatus(JSONWriter w, SpanStatus status) throws IOException {
        w.writeStartObject();

        if (status.getMessage() != null) {
            w.writeKey("message");
            w.writeString(status.getMessage());
        }

        w.writeKey("code");
        w.writeNumber(Integer.valueOf(status.getCode()));

        w.writeEndObject();
    }

}
