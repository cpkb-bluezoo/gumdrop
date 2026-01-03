/*
 * TraceSerializer.java
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
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanEvent;
import org.bluezoo.gumdrop.telemetry.SpanLink;
import org.bluezoo.gumdrop.telemetry.SpanStatus;
import org.bluezoo.gumdrop.telemetry.Trace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Map;

/**
 * Serializes traces to OTLP protobuf format.
 *
 * <p>The serializer can write directly to a {@link WritableByteChannel} for
 * streaming output, or to a {@link ByteBuffer} for buffered output.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class TraceSerializer {

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
    public TraceSerializer(String serviceName) {
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
    public TraceSerializer(String serviceName, String serviceVersion,
                           String serviceNamespace, Map<String, String> resourceAttributes) {
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.serviceNamespace = serviceNamespace;
        this.resourceAttributes = resourceAttributes;
    }

    /**
     * Serializes a trace to OTLP TracesData format, writing to a channel.
     *
     * <p>This is the primary serialization method. Data is streamed to the
     * channel as it is serialized, making it suitable for HTTP/2 or chunked
     * HTTP/1.1 output.
     *
     * @param trace the trace to serialize
     * @param channel the channel to write to
     * @throws IOException if an I/O error occurs
     */
    public void serialize(Trace trace, WritableByteChannel channel) throws IOException {
        ProtobufWriter writer = new ProtobufWriter(channel);
        writeTracesData(writer, trace);
    }

    /**
     * Serializes a trace to OTLP TracesData format, returning a ByteBuffer.
     *
     * <p>Convenience method for callers who need buffered output.
     *
     * @param trace the trace to serialize
     * @return a ByteBuffer containing the serialized trace (ready for reading)
     * @throws IOException if serialization fails
     */
    public ByteBuffer serialize(Trace trace) throws IOException {
        ByteBufferChannel channel = new ByteBufferChannel();
        serialize(trace, channel);
        return channel.toByteBuffer();
    }

    private void writeTracesData(ProtobufWriter writer, Trace trace) throws IOException {
        // TracesData { repeated ResourceSpans resource_spans = 1; }
        writer.writeMessageField(OTLPFieldNumbers.TRACES_DATA_RESOURCE_SPANS,
                new ResourceSpansWriter(trace));
    }

    // -- Inner classes for message content --

    private class ResourceSpansWriter implements ProtobufWriter.MessageContent {
        private final Trace trace;

        ResourceSpansWriter(Trace trace) {
            this.trace = trace;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // Resource resource = 1
            writer.writeMessageField(OTLPFieldNumbers.RESOURCE_SPANS_RESOURCE,
                    new ResourceWriter());

            // repeated ScopeSpans scope_spans = 2
            writer.writeMessageField(OTLPFieldNumbers.RESOURCE_SPANS_SCOPE_SPANS,
                    new ScopeSpansWriter(trace));

            // string schema_url = 3
            writer.writeStringField(OTLPFieldNumbers.RESOURCE_SPANS_SCHEMA_URL, SCHEMA_URL);
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

    private class ScopeSpansWriter implements ProtobufWriter.MessageContent {
        private final Trace trace;

        ScopeSpansWriter(Trace trace) {
            this.trace = trace;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // InstrumentationScope scope = 1
            writer.writeMessageField(OTLPFieldNumbers.SCOPE_SPANS_SCOPE,
                    new InstrumentationScopeWriter());

            // repeated Span spans = 2
            // Write all ended spans
            for (Span span : trace.getEndedSpans()) {
                writer.writeMessageField(OTLPFieldNumbers.SCOPE_SPANS_SPANS,
                        new SpanWriter(span));
            }

            // Also write root span if it's ended and not already in endedSpans
            Span root = trace.getRootSpan();
            if (root.isEnded() && !trace.getEndedSpans().contains(root)) {
                writer.writeMessageField(OTLPFieldNumbers.SCOPE_SPANS_SPANS,
                        new SpanWriter(root));
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

    private static class SpanWriter implements ProtobufWriter.MessageContent {
        private final Span span;

        SpanWriter(Span span) {
            this.span = span;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // bytes trace_id = 1
            writer.writeBytesField(OTLPFieldNumbers.SPAN_TRACE_ID, span.getTrace().getTraceId());

            // bytes span_id = 2
            writer.writeBytesField(OTLPFieldNumbers.SPAN_SPAN_ID, span.getSpanId());

            // bytes parent_span_id = 4
            Span parent = span.getParent();
            if (parent != null) {
                writer.writeBytesField(OTLPFieldNumbers.SPAN_PARENT_SPAN_ID, parent.getSpanId());
            }

            // string name = 5
            writer.writeStringField(OTLPFieldNumbers.SPAN_NAME, span.getName());

            // SpanKind kind = 6
            writer.writeVarintField(OTLPFieldNumbers.SPAN_KIND, span.getKind().getValue());

            // fixed64 start_time_unix_nano = 7
            writer.writeFixed64Field(OTLPFieldNumbers.SPAN_START_TIME_UNIX_NANO,
                    span.getStartTimeUnixNano());

            // fixed64 end_time_unix_nano = 8
            writer.writeFixed64Field(OTLPFieldNumbers.SPAN_END_TIME_UNIX_NANO,
                    span.getEndTimeUnixNano());

            // repeated KeyValue attributes = 9
            for (Attribute attr : span.getAttributes()) {
                writer.writeMessageField(OTLPFieldNumbers.SPAN_ATTRIBUTES,
                        new AttributeWriter(attr));
            }

            // repeated Event events = 11
            for (SpanEvent event : span.getEvents()) {
                writer.writeMessageField(OTLPFieldNumbers.SPAN_EVENTS,
                        new EventWriter(event));
            }

            // repeated Link links = 13
            for (SpanLink link : span.getLinks()) {
                writer.writeMessageField(OTLPFieldNumbers.SPAN_LINKS,
                        new LinkWriter(link));
            }

            // Status status = 15
            SpanStatus status = span.getStatus();
            if (status.getCode() != SpanStatus.STATUS_CODE_UNSET) {
                writer.writeMessageField(OTLPFieldNumbers.SPAN_STATUS,
                        new StatusWriter(status));
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

    private static class EventWriter implements ProtobufWriter.MessageContent {
        private final SpanEvent event;

        EventWriter(SpanEvent event) {
            this.event = event;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // fixed64 time_unix_nano = 1
            writer.writeFixed64Field(OTLPFieldNumbers.EVENT_TIME_UNIX_NANO,
                    event.getTimeUnixNano());

            // string name = 2
            writer.writeStringField(OTLPFieldNumbers.EVENT_NAME, event.getName());

            // repeated KeyValue attributes = 3
            for (Attribute attr : event.getAttributes()) {
                writer.writeMessageField(OTLPFieldNumbers.EVENT_ATTRIBUTES,
                        new AttributeWriter(attr));
            }
        }
    }

    private static class LinkWriter implements ProtobufWriter.MessageContent {
        private final SpanLink link;

        LinkWriter(SpanLink link) {
            this.link = link;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // bytes trace_id = 1
            writer.writeBytesField(OTLPFieldNumbers.LINK_TRACE_ID,
                    link.getContext().getTraceId());

            // bytes span_id = 2
            writer.writeBytesField(OTLPFieldNumbers.LINK_SPAN_ID,
                    link.getContext().getSpanId());

            // repeated KeyValue attributes = 4
            for (Attribute attr : link.getAttributes()) {
                writer.writeMessageField(OTLPFieldNumbers.LINK_ATTRIBUTES,
                        new AttributeWriter(attr));
            }
        }
    }

    private static class StatusWriter implements ProtobufWriter.MessageContent {
        private final SpanStatus status;

        StatusWriter(SpanStatus status) {
            this.status = status;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // string message = 2
            if (status.getMessage() != null) {
                writer.writeStringField(OTLPFieldNumbers.STATUS_MESSAGE, status.getMessage());
            }

            // StatusCode code = 3
            writer.writeVarintField(OTLPFieldNumbers.STATUS_CODE, status.getCode());
        }
    }

    // -- Helper method --

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
