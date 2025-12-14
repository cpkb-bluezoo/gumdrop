/*
 * SessionSerializer.java
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

package org.bluezoo.gumdrop.servlet.session;

import org.bluezoo.gumdrop.telemetry.protobuf.ByteBufferChannel;
import org.bluezoo.gumdrop.telemetry.protobuf.DefaultProtobufHandler;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParseException;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParser;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serializes and deserializes HTTP session state using Protobuf format.
 *
 * <p>This provides a more secure and efficient alternative to Java object
 * serialization for cluster session replication. Primitive types (String,
 * Boolean, Long, Double) are encoded directly in protobuf format. Complex
 * objects fall back to Java serialization with a strict deserialization
 * filter that only allows classes from the webapp's classloader.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class SessionSerializer {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.session.L10N");
    private static final Logger LOGGER = Logger.getLogger(SessionSerializer.class.getName());

    // Field numbers for Session message
    private static final int FIELD_ID = 1;
    private static final int FIELD_CREATION_TIME = 2;
    private static final int FIELD_LAST_ACCESSED_TIME = 3;
    private static final int FIELD_MAX_INACTIVE_INTERVAL = 4;
    private static final int FIELD_ATTRIBUTES = 5;
    private static final int FIELD_VERSION = 6;

    // Field numbers for Delta message
    private static final int DELTA_ID = 1;
    private static final int DELTA_VERSION = 2;
    private static final int DELTA_UPDATED = 3;
    private static final int DELTA_REMOVED = 4;

    // Field numbers for Attribute message
    private static final int ATTR_KEY = 1;
    private static final int ATTR_STRING_VALUE = 2;
    private static final int ATTR_BOOL_VALUE = 3;
    private static final int ATTR_INT_VALUE = 4;
    private static final int ATTR_DOUBLE_VALUE = 5;
    private static final int ATTR_BYTES_VALUE = 6;
    private static final int ATTR_FLOAT_VALUE = 7;

    // Maximum depth for Java deserialization (prevent stack overflow attacks)
    private static final int MAX_DESERIALIZATION_DEPTH = 20;
    // Maximum array size for Java deserialization
    private static final int MAX_ARRAY_LENGTH = 10000;
    // Maximum number of references (object graph size)
    private static final long MAX_REFERENCES = 10000L;

    private SessionSerializer() {
        // Utility class
    }

    /**
     * Serializes a session to a ByteBuffer using protobuf format.
     *
     * @param session the session to serialize
     * @return a ByteBuffer containing the serialized session (ready for reading)
     * @throws IOException if serialization fails
     */
    static ByteBuffer serialize(Session session) throws IOException {
        // Use auto-expanding ByteBufferChannel
        ByteBufferChannel channel = new ByteBufferChannel(4096);
        ProtobufWriter writer = new ProtobufWriter(channel);

        // Field 1: Session ID (convert hex string to 16 bytes)
        byte[] idBytes = hexToBytes(session.id);
        writer.writeBytesField(FIELD_ID, idBytes);

        // Field 2: Creation time
        writer.writeVarintField(FIELD_CREATION_TIME, session.creationTime);

        // Field 3: Last accessed time
        writer.writeVarintField(FIELD_LAST_ACCESSED_TIME, session.lastAccessedTime);

        // Field 4: Max inactive interval
        writer.writeVarintField(FIELD_MAX_INACTIVE_INTERVAL, session.maxInactiveInterval);

        // Field 5: Attributes (repeated embedded messages)
        synchronized (session.attributes) {
            for (Map.Entry<String, Object> entry : session.attributes.entrySet()) {
                writer.writeMessageField(FIELD_ATTRIBUTES,
                        new AttributeWriter(entry.getKey(), entry.getValue()));
            }
        }

        // Field 6: Version
        writer.writeVarintField(FIELD_VERSION, session.version);

        return channel.toByteBuffer();
    }

    /**
     * Deserializes a session from a ByteBuffer.
     *
     * @param context the session context
     * @param buf the buffer containing the serialized session
     * @return the deserialized session
     * @throws IOException if deserialization fails
     */
    static Session deserialize(SessionContext context, ByteBuffer buf) throws IOException {
        SessionHandler handler = new SessionHandler();
        ProtobufParser parser = new ProtobufParser(handler);

        try {
            parser.receive(buf);
            parser.close();
        } catch (ProtobufParseException e) {
            throw new IOException("Failed to parse session", e);
        }

        if (handler.id == null) {
            throw new IOException(L10N.getString("err.session_id_not_found"));
        }

        Session session = new Session(context, handler.id);
        session.creationTime = handler.creationTime;
        session.lastAccessedTime = handler.lastAccessedTime;
        session.maxInactiveInterval = handler.maxInactiveInterval;
        session.version = handler.version;
        session.attributes = handler.attributes;
        session.isNew = false; // Received sessions are not new
        return session;
    }

    /**
     * Handler for parsing Session messages.
     */
    private static class SessionHandler extends DefaultProtobufHandler {
        String id;
        long creationTime;
        long lastAccessedTime;
        int maxInactiveInterval;
        long version;
        final Map<String, Object> attributes = new LinkedHashMap<>();

        // Current attribute being parsed
        private AttributeHandler currentAttribute;

        @Override
        public boolean isMessage(int fieldNumber) {
            return fieldNumber == FIELD_ATTRIBUTES;
        }

        @Override
        public void handleVarint(int fieldNumber, long value) {
            if (currentAttribute != null) {
                currentAttribute.handleVarint(fieldNumber, value);
                return;
            }

            switch (fieldNumber) {
                case FIELD_CREATION_TIME:
                    creationTime = value;
                    break;
                case FIELD_LAST_ACCESSED_TIME:
                    lastAccessedTime = value;
                    break;
                case FIELD_MAX_INACTIVE_INTERVAL:
                    maxInactiveInterval = (int) value;
                    break;
                case FIELD_VERSION:
                    version = value;
                    break;
            }
        }

        @Override
        public void handleFixed64(int fieldNumber, long value) {
            if (currentAttribute != null) {
                currentAttribute.handleFixed64(fieldNumber, value);
            }
        }

        @Override
        public void handleFixed32(int fieldNumber, int value) {
            if (currentAttribute != null) {
                currentAttribute.handleFixed32(fieldNumber, value);
            }
        }

        @Override
        public void handleBytes(int fieldNumber, ByteBuffer data) {
            if (currentAttribute != null) {
                currentAttribute.handleBytes(fieldNumber, data);
                return;
            }

            if (fieldNumber == FIELD_ID) {
                id = bytesToHex(asBytes(data));
            }
        }

        @Override
        public void startMessage(int fieldNumber) {
            if (fieldNumber == FIELD_ATTRIBUTES) {
                currentAttribute = new AttributeHandler();
            }
        }

        @Override
        public void endMessage() {
            if (currentAttribute != null) {
                if (currentAttribute.key != null) {
                    attributes.put(currentAttribute.key, currentAttribute.value);
                }
                currentAttribute = null;
            }
        }
    }

    /**
     * Handler for parsing Attribute messages.
     */
    private static class AttributeHandler extends DefaultProtobufHandler {
        String key;
        Object value;

        @Override
        public void handleVarint(int fieldNumber, long value) {
            switch (fieldNumber) {
                case ATTR_BOOL_VALUE:
                    this.value = asBool(value);
                    break;
                case ATTR_INT_VALUE:
                    this.value = value;
                    break;
            }
        }

        @Override
        public void handleFixed64(int fieldNumber, long value) {
            if (fieldNumber == ATTR_DOUBLE_VALUE) {
                this.value = asDouble(value);
            }
        }

        @Override
        public void handleFixed32(int fieldNumber, int value) {
            if (fieldNumber == ATTR_FLOAT_VALUE) {
                this.value = asFloat(value);
            }
        }

        @Override
        public void handleBytes(int fieldNumber, ByteBuffer data) {
            switch (fieldNumber) {
                case ATTR_KEY:
                    key = asString(data);
                    break;
                case ATTR_STRING_VALUE:
                    value = asString(data);
                    break;
                case ATTR_BYTES_VALUE:
                    // Java-serialized complex object
                    try {
                        value = deserializeObject(asBytes(data));
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to deserialize attribute value", e);
                        value = null;
                    }
                    break;
            }
        }
    }

    /**
     * Writer for attribute messages.
     */
    private static class AttributeWriter implements ProtobufWriter.MessageContent {
        private final String key;
        private final Object value;

        AttributeWriter(String key, Object value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public void writeTo(ProtobufWriter writer) throws IOException {
            // Field 1: Key
            writer.writeStringField(ATTR_KEY, key);

            // Value field depends on type
            if (value == null) {
                // null values: just don't write a value field
                return;
            }

            if (value instanceof String) {
                writer.writeStringField(ATTR_STRING_VALUE, (String) value);
            } else if (value instanceof Boolean) {
                writer.writeBoolField(ATTR_BOOL_VALUE, (Boolean) value);
            } else if (value instanceof Long) {
                writer.writeVarintField(ATTR_INT_VALUE, (Long) value);
            } else if (value instanceof Integer) {
                writer.writeVarintField(ATTR_INT_VALUE, ((Integer) value).longValue());
            } else if (value instanceof Double) {
                writer.writeDoubleField(ATTR_DOUBLE_VALUE, (Double) value);
            } else if (value instanceof Float) {
                writer.writeFloatField(ATTR_FLOAT_VALUE, (Float) value);
            } else {
                // Complex object: fall back to Java serialization
                byte[] bytes = serializeObject(value);
                if (bytes != null) {
                    writer.writeBytesField(ATTR_BYTES_VALUE, bytes);
                }
            }
        }
    }

    /**
     * Serializes a complex object using Java serialization.
     * Returns null if serialization fails.
     */
    private static byte[] serializeObject(Object obj) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(obj);
        }
        return baos.toByteArray();
    }

    /**
     * Deserializes a complex object using Java serialization with filtering.
     */
    private static Object deserializeObject(byte[] bytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        try (FilteredObjectInputStream ois = new FilteredObjectInputStream(bais)) {
            return ois.readObject();
        } catch (ClassNotFoundException e) {
            String message = L10N.getString("err.class_not_found");
            message = MessageFormat.format(message, e.getMessage());
            throw new IOException(message, e);
        }
    }

    /**
     * ObjectInputStream with a strict deserialization filter.
     */
    private static class FilteredObjectInputStream extends ObjectInputStream {

        FilteredObjectInputStream(ByteArrayInputStream in) throws IOException {
            super(in);
            setObjectInputFilter(new SessionDeserializationFilter());
        }
    }

    /**
     * Deserialization filter for session attributes.
     */
    private static class SessionDeserializationFilter implements ObjectInputFilter {

        // Safe JDK packages that are commonly used in session attributes
        private static final Set<String> ALLOWED_PACKAGES = Set.of(
                "java.lang.",
                "java.util.",
                "java.math.",
                "java.time.",
                "java.io.Serializable",
                "java.net.URI",
                "java.net.URL"
        );

        // Explicitly denied classes (known gadget classes)
        private static final Set<String> DENIED_CLASSES = Set.of(
                "org.apache.commons.collections.functors.",
                "org.apache.commons.collections4.functors.",
                "org.apache.xalan.",
                "com.sun.org.apache.xalan.",
                "org.codehaus.groovy.runtime.",
                "org.springframework.beans.factory.",
                "com.mchange.v2.c3p0.",
                "com.sun.rowset.JdbcRowSetImpl",
                "java.rmi.server.UnicastRemoteObject",
                "javax.management."
        );

        @Override
        public Status checkInput(FilterInfo filterInfo) {
            Class<?> clazz = filterInfo.serialClass();

            // Check depth limit
            if (filterInfo.depth() > MAX_DESERIALIZATION_DEPTH) {
                return Status.REJECTED;
            }

            // Check array length
            if (filterInfo.arrayLength() > MAX_ARRAY_LENGTH) {
                return Status.REJECTED;
            }

            // Check reference count
            if (filterInfo.references() > MAX_REFERENCES) {
                return Status.REJECTED;
            }

            // If no class info, this is a primitive or the filter is checking metrics
            if (clazz == null) {
                return Status.UNDECIDED;
            }

            String className = clazz.getName();

            // Check denied list first
            for (String denied : DENIED_CLASSES) {
                if (className.startsWith(denied)) {
                    String message = L10N.getString("warn.blocked_class");
                    message = MessageFormat.format(message, className);
                    LOGGER.warning(message);
                    return Status.REJECTED;
                }
            }

            // Allow primitives and arrays of primitives
            if (clazz.isPrimitive()) {
                return Status.ALLOWED;
            }

            // Allow arrays (the component type will be checked separately)
            if (clazz.isArray()) {
                return Status.UNDECIDED;
            }

            // Check allowed JDK packages
            for (String allowed : ALLOWED_PACKAGES) {
                if (className.startsWith(allowed)) {
                    return Status.ALLOWED;
                }
            }

            // Allow enum types
            if (clazz.isEnum()) {
                return Status.ALLOWED;
            }

            // For other classes, check if they're from the webapp's classloader
            ClassLoader classLoader = clazz.getClassLoader();
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();

            if (classLoader == contextClassLoader) {
                return Status.ALLOWED;
            }

            // Check parent classloaders
            ClassLoader parent = contextClassLoader;
            while (parent != null) {
                if (classLoader == parent) {
                    return Status.ALLOWED;
                }
                parent = parent.getParent();
            }

            // Unknown class from unknown classloader - reject for safety
            String message = L10N.getString("warn.rejected_class");
            message = MessageFormat.format(message, className);
            LOGGER.warning(message);
            return Status.REJECTED;
        }
    }

    // -- Delta serialization for incremental updates --

    /**
     * Serializes a delta update containing only modified attributes.
     */
    static ByteBuffer serializeDelta(Session session, Set<String> dirtyAttrs,
                                     Set<String> removedAttrs) throws IOException {
        // Use auto-expanding ByteBufferChannel
        ByteBufferChannel channel = new ByteBufferChannel(4096);
        ProtobufWriter writer = new ProtobufWriter(channel);

        // Field 1: Session ID (16 bytes)
        byte[] idBytes = hexToBytes(session.id);
        writer.writeBytesField(DELTA_ID, idBytes);

        // Field 2: Version
        writer.writeVarintField(DELTA_VERSION, session.version);

        // Field 3: Updated attributes (repeated)
        synchronized (session.attributes) {
            for (String attrName : dirtyAttrs) {
                Object value = session.attributes.get(attrName);
                if (value != null) {
                    writer.writeMessageField(DELTA_UPDATED,
                            new AttributeWriter(attrName, value));
                }
            }
        }

        // Field 4: Removed attribute names (repeated strings)
        for (String attrName : removedAttrs) {
            writer.writeStringField(DELTA_REMOVED, attrName);
        }

        return channel.toByteBuffer();
    }

    /**
     * Deserializes a delta update message.
     */
    static DeltaUpdate deserializeDelta(ByteBuffer buf) throws IOException {
        DeltaHandler handler = new DeltaHandler();
        ProtobufParser parser = new ProtobufParser(handler);

        try {
            parser.receive(buf);
            parser.close();
        } catch (ProtobufParseException e) {
            throw new IOException("Failed to parse delta update", e);
        }

        if (handler.id == null) {
            throw new IOException(L10N.getString("err.delta_id_not_found"));
        }

        return new DeltaUpdate(handler.id, handler.version,
                handler.updatedAttrs, handler.removedAttrs);
    }

    /**
     * Handler for parsing Delta messages.
     */
    private static class DeltaHandler extends DefaultProtobufHandler {
        String id;
        long version;
        final Map<String, Object> updatedAttrs = new LinkedHashMap<>();
        final Set<String> removedAttrs = new HashSet<>();

        // Current attribute being parsed
        private AttributeHandler currentAttribute;

        @Override
        public boolean isMessage(int fieldNumber) {
            return fieldNumber == DELTA_UPDATED;
        }

        @Override
        public void handleVarint(int fieldNumber, long value) {
            if (currentAttribute != null) {
                currentAttribute.handleVarint(fieldNumber, value);
                return;
            }

            if (fieldNumber == DELTA_VERSION) {
                version = value;
            }
        }

        @Override
        public void handleFixed64(int fieldNumber, long value) {
            if (currentAttribute != null) {
                currentAttribute.handleFixed64(fieldNumber, value);
            }
        }

        @Override
        public void handleFixed32(int fieldNumber, int value) {
            if (currentAttribute != null) {
                currentAttribute.handleFixed32(fieldNumber, value);
            }
        }

        @Override
        public void handleBytes(int fieldNumber, ByteBuffer data) {
            if (currentAttribute != null) {
                currentAttribute.handleBytes(fieldNumber, data);
                return;
            }

            switch (fieldNumber) {
                case DELTA_ID:
                    id = bytesToHex(asBytes(data));
                    break;
                case DELTA_REMOVED:
                    removedAttrs.add(asString(data));
                    break;
            }
        }

        @Override
        public void startMessage(int fieldNumber) {
            if (fieldNumber == DELTA_UPDATED) {
                currentAttribute = new AttributeHandler();
            }
        }

        @Override
        public void endMessage() {
            if (currentAttribute != null) {
                if (currentAttribute.key != null) {
                    updatedAttrs.put(currentAttribute.key, currentAttribute.value);
                }
                currentAttribute = null;
            }
        }
    }

    /**
     * Container for delta update information.
     */
    static class DeltaUpdate {
        final String sessionId;
        final long version;
        final Map<String, Object> updatedAttributes;
        final Set<String> removedAttributes;

        DeltaUpdate(String sessionId, long version, Map<String, Object> updatedAttributes,
                    Set<String> removedAttributes) {
            this.sessionId = sessionId;
            this.version = version;
            this.updatedAttributes = updatedAttributes;
            this.removedAttributes = removedAttributes;
        }
    }

    // -- Hex conversion utilities --

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
