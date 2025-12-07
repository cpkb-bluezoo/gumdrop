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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufReader;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Serializes and deserializes HTTP session state using Protobuf format.
 *
 * <p>This provides a more secure and efficient alternative to Java object
 * serialization for cluster session replication. Primitive types (String,
 * Boolean, Long, Double) are encoded directly in protobuf format. Complex
 * objects fall back to Java serialization with a strict deserialization
 * filter that only allows classes from the webapp's classloader.
 *
 * <p>Protobuf schema (conceptual):
 * <pre>
 * message Session {
 *   bytes id = 1;                    // 16 bytes session ID
 *   int64 creationTime = 2;
 *   int64 lastAccessedTime = 3;
 *   int32 maxInactiveInterval = 4;
 *   repeated Attribute attributes = 5;
 * }
 *
 * message Attribute {
 *   string key = 1;
 *   // oneof value:
 *   string stringValue = 2;
 *   bool boolValue = 3;
 *   int64 intValue = 4;
 *   double doubleValue = 5;
 *   bytes bytesValue = 6;   // Java-serialized complex objects
 * }
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class SessionSerializer {

    // Field numbers for Session message
    private static final int FIELD_ID = 1;
    private static final int FIELD_CREATION_TIME = 2;
    private static final int FIELD_LAST_ACCESSED_TIME = 3;
    private static final int FIELD_MAX_INACTIVE_INTERVAL = 4;
    private static final int FIELD_ATTRIBUTES = 5;

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
        // Estimate initial buffer size: header + some space for attributes
        ByteBuffer buf = ByteBuffer.allocate(4096);
        ProtobufWriter writer = new ProtobufWriter(buf);

        // Field 1: Session ID (16 bytes)
        byte[] idBytes = hexToBytes(session.id);
        writer.writeBytesField(FIELD_ID, idBytes);

        // Field 2: Creation time
        writer.writeVarintField(FIELD_CREATION_TIME, session.creationTime);

        // Field 3: Last accessed time
        writer.writeVarintField(FIELD_LAST_ACCESSED_TIME, session.lastAccessedTime);

        // Field 4: Max inactive interval
        writer.writeVarintField(FIELD_MAX_INACTIVE_INTERVAL, session.maxInactiveInterval);

        // Field 5: Attributes (repeated)
        synchronized (session.attributes) {
            for (Map.Entry<String, Object> entry : session.attributes.entrySet()) {
                writer.writeMessageField(FIELD_ATTRIBUTES,
                        new AttributeWriter(entry.getKey(), entry.getValue()));

                // Check for overflow and reallocate if needed
                if (writer.isOverflow()) {
                    // Double the buffer size and retry
                    int newSize = buf.capacity() * 2;
                    ByteBuffer newBuf = ByteBuffer.allocate(newSize);
                    buf.flip();
                    newBuf.put(buf);
                    buf = newBuf;
                    writer = new ProtobufWriter(buf);

                    // Re-serialize the attribute
                    writer.writeMessageField(FIELD_ATTRIBUTES,
                            new AttributeWriter(entry.getKey(), entry.getValue()));
                }
            }
        }

        if (writer.isOverflow()) {
            throw new IOException("Session data too large to serialize");
        }

        buf.flip();
        return buf;
    }

    /**
     * Deserializes a session from a ByteBuffer.
     *
     * @param context the servlet context
     * @param buf the buffer containing the serialized session
     * @return the deserialized session
     * @throws IOException if deserialization fails
     */
    static Session deserialize(Context context, ByteBuffer buf) throws IOException {
        ProtobufReader reader = new ProtobufReader(buf);

        String id = null;
        long creationTime = 0;
        long lastAccessedTime = 0;
        int maxInactiveInterval = 0;
        Map<String, Object> attributes = new LinkedHashMap<>();

        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int fieldNumber = ProtobufReader.getFieldNumber(tag);
            int wireType = ProtobufReader.getWireType(tag);

            switch (fieldNumber) {
                case FIELD_ID:
                    byte[] idBytes = reader.readBytes();
                    id = bytesToHex(idBytes);
                    break;

                case FIELD_CREATION_TIME:
                    creationTime = reader.readVarint();
                    break;

                case FIELD_LAST_ACCESSED_TIME:
                    lastAccessedTime = reader.readVarint();
                    break;

                case FIELD_MAX_INACTIVE_INTERVAL:
                    maxInactiveInterval = (int) reader.readVarint();
                    break;

                case FIELD_ATTRIBUTES:
                    readAttribute(reader, attributes);
                    break;

                default:
                    reader.skipField(wireType);
                    break;
            }
        }

        if (id == null) {
            throw new IOException("Session ID not found in serialized data");
        }

        Session session = new Session(context, id);
        session.creationTime = creationTime;
        session.lastAccessedTime = lastAccessedTime;
        session.maxInactiveInterval = maxInactiveInterval;
        session.attributes = attributes;
        return session;
    }

    /**
     * Reads a single attribute from the protobuf stream into the map.
     */
    private static void readAttribute(ProtobufReader reader, Map<String, Object> attributes) 
            throws IOException {
        ProtobufReader attrReader = reader.readMessage();

        String key = null;
        Object value = null;

        while (attrReader.hasRemaining()) {
            int tag = attrReader.readTag();
            int fieldNumber = ProtobufReader.getFieldNumber(tag);
            int wireType = ProtobufReader.getWireType(tag);

            switch (fieldNumber) {
                case ATTR_KEY:
                    key = attrReader.readString();
                    break;

                case ATTR_STRING_VALUE:
                    value = attrReader.readString();
                    break;

                case ATTR_BOOL_VALUE:
                    value = Boolean.valueOf(attrReader.readBool());
                    break;

                case ATTR_INT_VALUE:
                    value = Long.valueOf(attrReader.readVarint());
                    break;

                case ATTR_DOUBLE_VALUE:
                    value = Double.valueOf(attrReader.readDouble());
                    break;

                case ATTR_FLOAT_VALUE:
                    value = Float.valueOf(attrReader.readFloat());
                    break;

                case ATTR_BYTES_VALUE:
                    // Java-serialized complex object with filtering
                    byte[] bytes = attrReader.readBytes();
                    value = deserializeObject(bytes);
                    break;

                default:
                    attrReader.skipField(wireType);
                    break;
            }
        }

        if (key != null) {
            attributes.put(key, value);
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
        public void writeTo(ProtobufWriter writer) {
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
            } else if (value instanceof Short) {
                writer.writeVarintField(ATTR_INT_VALUE, ((Short) value).longValue());
            } else if (value instanceof Byte) {
                writer.writeVarintField(ATTR_INT_VALUE, ((Byte) value).longValue());
            } else if (value instanceof Double) {
                writer.writeDoubleField(ATTR_DOUBLE_VALUE, (Double) value);
            } else if (value instanceof Float) {
                writer.writeFloatField(ATTR_FLOAT_VALUE, (Float) value);
            } else {
                // Complex object: serialize with Java serialization
                try {
                    byte[] bytes = serializeObject(value);
                    writer.writeBytesField(ATTR_BYTES_VALUE, bytes);
                } catch (IOException e) {
                    // Log and skip non-serializable attributes
                    Context.LOGGER.log(Level.WARNING, 
                            "Cannot serialize session attribute: " + key, e);
                }
            }
        }
    }

    /**
     * Serializes a complex object using Java serialization.
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
     *
     * <p>Uses an ObjectInputFilter to prevent deserialization attacks:
     * <ul>
     *   <li>Limits object graph depth</li>
     *   <li>Limits array sizes</li>
     *   <li>Limits total number of references</li>
     *   <li>Only allows classes from the context classloader or common JDK types</li>
     * </ul>
     */
    private static Object deserializeObject(byte[] bytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        try (FilteredObjectInputStream ois = new FilteredObjectInputStream(bais)) {
            return ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Class not found during deserialization: " + e.getMessage(), e);
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
     *
     * <p>This filter restricts what can be deserialized to prevent
     * deserialization vulnerabilities. It allows:
     * <ul>
     *   <li>Primitive types and their wrappers</li>
     *   <li>Common JDK types (String, collections, dates)</li>
     *   <li>Application classes from the context classloader</li>
     * </ul>
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
                    Context.LOGGER.warning("Blocked deserialization of dangerous class: " + className);
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
            // Classes loaded by the context classloader are considered safe
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
            Context.LOGGER.warning("Rejected deserialization of unknown class: " + className);
            return Status.REJECTED;
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

