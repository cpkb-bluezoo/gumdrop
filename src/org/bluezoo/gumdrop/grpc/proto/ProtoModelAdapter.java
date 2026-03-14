/*
 * ProtoModelAdapter.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufHandler;

/**
 * Adapter that bridges low-level {@link ProtobufHandler} events to
 * high-level {@link ProtoMessageHandler} events using a Proto model.
 *
 * <p>Implements ProtobufHandler and delegates to ProtobufParser. Uses
 * MessageDescriptor to map field numbers to names and types, converting
 * raw wire values to semantic events.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ProtoModelAdapter implements ProtobufHandler {

    private final ProtoFile protoFile;
    private final ProtoMessageHandler handler;
    private final Deque<MessageContext> messageStack = new ArrayDeque<>();
    private boolean rootStarted;

    /**
     * Creates an adapter with the given Proto model and handler.
     *
     * @param protoFile the Proto model (from parsed .proto file)
     * @param handler the high-level handler to receive events
     */
    public ProtoModelAdapter(ProtoFile protoFile, ProtoMessageHandler handler) {
        this.protoFile = protoFile;
        this.handler = handler;
    }

    /**
     * Initializes parsing for a root message. Must be called before feeding
     * data to the parser.
     *
     * @param messageTypeName the fully qualified message type name
     */
    public void startRootMessage(String messageTypeName) throws ProtoParseException {
        if (rootStarted) {
            throw new IllegalStateException("Root message already started");
        }
        MessageDescriptor msg = protoFile.getMessage(messageTypeName);
        if (msg == null && messageTypeName.startsWith(".")) {
            msg = protoFile.getMessage(messageTypeName.substring(1));
        }
        if (msg == null) {
            throw new ProtoParseException("Unknown message type: " + messageTypeName);
        }
        handler.startMessage(messageTypeName);
        messageStack.push(new MessageContext(msg, null));
        rootStarted = true;
    }

    @Override
    public void handleVarint(int fieldNumber, long value) {
        FieldDescriptor fd = currentField(fieldNumber);
        if (fd == null) return;

        Object val;
        switch (fd.getType()) {
            case BOOL:
                val = value != 0;
                break;
            case INT32:
            case UINT32:
            case FIXED32:
            case SFIXED32:
                val = (int) value;
                break;
            case SINT32:
                val = (int) ((value >>> 1) ^ -(value & 1));
                break;
            case SINT64:
                val = (value >>> 1) ^ -(value & 1);
                break;
            case ENUM:
            case INT64:
            case UINT64:
            case FIXED64:
            case SFIXED64:
            default:
                val = value;
                break;
        }
        try {
            handler.field(fd.getName(), val);
        } catch (ProtoParseException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void handleFixed64(int fieldNumber, long value) {
        FieldDescriptor fd = currentField(fieldNumber);
        if (fd == null) return;

        Object val;
        if (fd.getType() == FieldType.DOUBLE) {
            val = Double.longBitsToDouble(value);
        } else {
            val = value;
        }
        try {
            handler.field(fd.getName(), val);
        } catch (ProtoParseException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void handleFixed32(int fieldNumber, int value) {
        FieldDescriptor fd = currentField(fieldNumber);
        if (fd == null) return;

        Object val;
        if (fd.getType() == FieldType.FLOAT) {
            val = Float.intBitsToFloat(value);
        } else {
            val = value;
        }
        try {
            handler.field(fd.getName(), val);
        } catch (ProtoParseException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void handleBytes(int fieldNumber, ByteBuffer data) {
        FieldDescriptor fd = currentField(fieldNumber);
        if (fd == null) return;

        Object val;
        if (fd.getType() == FieldType.STRING) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            val = new String(bytes, StandardCharsets.UTF_8);
        } else {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            val = bytes;
        }
        try {
            handler.field(fd.getName(), val);
        } catch (ProtoParseException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isMessage(int fieldNumber) {
        FieldDescriptor fd = currentField(fieldNumber);
        return fd != null && fd.getType() == FieldType.MESSAGE;
    }

    @Override
    public void startMessage(int fieldNumber) {
        MessageContext ctx = messageStack.peek();
        FieldDescriptor fd = ctx != null ? ctx.message.getFieldByNumber(fieldNumber)
                : null;
        if (fd == null) return;

        String typeName = fd.getMessageTypeName();
        MessageDescriptor nested = protoFile.getMessage(typeName);
        if (nested == null) return;

        try {
            handler.startField(fd.getName(), typeName);
            handler.startMessage(typeName);
        } catch (ProtoParseException e) {
            throw new RuntimeException(e);
        }
        messageStack.push(new MessageContext(nested, fd.getName()));
    }

    @Override
    public void endMessage() {
        if (messageStack.isEmpty()) return;

        MessageContext ctx = messageStack.pop();
        try {
            handler.endMessage();
            if (ctx.fieldName != null) {
                handler.endField();
            }
        } catch (ProtoParseException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Call when the root message parsing is complete.
     */
    public void endRootMessage() throws ProtoParseException {
        if (!messageStack.isEmpty()) {
            messageStack.pop();
            handler.endMessage();
        }
    }

    private FieldDescriptor currentField(int fieldNumber) {
        MessageContext ctx = messageStack.peek();
        return ctx != null ? ctx.message.getFieldByNumber(fieldNumber) : null;
    }

    private static class MessageContext {
        final MessageDescriptor message;
        final String fieldName;

        MessageContext(MessageDescriptor message, String fieldName) {
            this.message = message;
            this.fieldName = fieldName;
        }
    }
}
