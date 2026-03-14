/*
 * ProtoModelSerializer.java
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

package org.bluezoo.gumdrop.grpc.proto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufWriter;

/**
 * Serializes protobuf messages from event-driven input using a Proto model.
 *
 * <p>The application drives the serializer with events (startMessage, field,
 * startField, endField, endMessage). The serializer uses the model to map
 * field names to numbers and types, writing the correct wire format.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ProtoModelSerializer {

    private final ProtoFile protoFile;
    private final Deque<MessageDescriptor> messageStack = new ArrayDeque<>();

    /**
     * Creates a serializer with the given Proto model.
     *
     * @param protoFile the Proto model
     */
    public ProtoModelSerializer(ProtoFile protoFile) {
        this.protoFile = protoFile;
    }

    /**
     * Starts a message (root or nested).
     *
     * @param writer the protobuf writer
     * @param typeName the fully qualified message type name
     * @throws IOException if an I/O error occurs
     */
    public void startMessage(ProtobufWriter writer, String typeName) throws IOException {
        MessageDescriptor msg = protoFile.getMessage(typeName);
        if (msg == null && typeName.startsWith(".")) {
            msg = protoFile.getMessage(typeName.substring(1));
        }
        if (msg == null) {
            throw new IOException("Unknown message type: " + typeName);
        }
        messageStack.push(msg);
    }

    /**
     * Ends the current message.
     */
    public void endMessage() {
        if (!messageStack.isEmpty()) {
            messageStack.pop();
        }
    }

    /**
     * Writes a scalar field.
     *
     * @param writer the protobuf writer
     * @param name the field name
     * @param value the value (String, Number, Boolean, or byte[])
     * @throws IOException if an I/O error occurs
     */
    public void field(ProtobufWriter writer, String name, Object value) throws IOException {
        if (value == null) return;

        FieldDescriptor fd = currentField(name);
        if (fd == null) return;

        int num = fd.getNumber();
        FieldType type = fd.getType();

        switch (type) {
            case DOUBLE:
                writer.writeDoubleField(num, ((Number) value).doubleValue());
                break;
            case FLOAT:
                writer.writeFloatField(num, ((Number) value).floatValue());
                break;
            case INT32:
            case UINT32:
            case FIXED32:
            case ENUM:
                writer.writeVarintField(num, ((Number) value).longValue());
                break;
            case INT64:
            case UINT64:
            case FIXED64:
                writer.writeVarintField(num, ((Number) value).longValue());
                break;
            case SINT32:
                writer.writeSVarintField(num, ((Number) value).intValue());
                break;
            case SINT64:
                writer.writeSVarintField(num, ((Number) value).longValue());
                break;
            case SFIXED32:
                writer.writeFixed32Field(num, ((Number) value).intValue());
                break;
            case SFIXED64:
                writer.writeFixed64Field(num, ((Number) value).longValue());
                break;
            case BOOL:
                writer.writeBoolField(num, (Boolean) value);
                break;
            case STRING:
                writer.writeStringField(num, value.toString());
                break;
            case BYTES:
                byte[] bytes = value instanceof byte[] ? (byte[]) value
                        : value.toString().getBytes(StandardCharsets.UTF_8);
                writer.writeBytesField(num, bytes);
                break;
            default:
                break;
        }
    }

    /**
     * Starts a nested message field context. Used with startMessage/field/endMessage.
     * Pushes the nested message descriptor for field lookups.
     *
     * @param name the field name
     * @param typeName the nested message type name
     */
    public void startField(String name, String typeName) {
        FieldDescriptor fd = currentField(name);
        if (fd == null) return;

        MessageDescriptor nested = protoFile.getMessage(typeName);
        if (nested == null && typeName != null && typeName.startsWith(".")) {
            nested = protoFile.getMessage(typeName.substring(1));
        }
        if (nested != null) {
            messageStack.push(nested);
        }
    }

    /**
     * Ends a nested message field context.
     */
    public void endField() {
        if (!messageStack.isEmpty()) {
            messageStack.pop();
        }
    }

    /**
     * Writes an embedded message. The content is written via the callback.
     *
     * @param writer the protobuf writer
     * @param name the field name
     * @param typeName the nested message type name
     * @param content callback to write the nested message content
     * @throws IOException if an I/O error occurs
     */
    public void messageField(ProtobufWriter writer, String name, String typeName,
                             MessageContent content) throws IOException {
        FieldDescriptor fd = currentField(name);
        if (fd == null || content == null) return;

        MessageDescriptor nested = protoFile.getMessage(typeName);
        if (nested == null && typeName.startsWith(".")) {
            nested = protoFile.getMessage(typeName.substring(1));
        }
        if (nested == null) return;

        messageStack.push(nested);
        try {
            writer.writeMessageField(fd.getNumber(), w -> content.writeTo(this, w));
        } finally {
            messageStack.pop();
        }
    }

    private FieldDescriptor currentField(String name) {
        MessageDescriptor msg = messageStack.peek();
        if (msg == null) return null;
        for (FieldDescriptor fd : msg.getFields()) {
            if (fd.getName().equals(name)) return fd;
        }
        return null;
    }

    /**
     * Callback for writing nested message content.
     */
    @FunctionalInterface
    public interface MessageContent {
        void writeTo(ProtoModelSerializer serializer, ProtobufWriter writer) throws IOException;
    }
}
