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
import org.bluezoo.util.ByteArrays;
import org.bluezoo.gumdrop.telemetry.protobuf.DefaultProtobufHandler;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParseException;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufParser;
import org.bluezoo.gumdrop.telemetry.protobuf.ProtobufWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serializes and deserializes HTTP session state using Protobuf format.
 *
 * <p>This provides a more secure and efficient alternative to Java object
 * serialization for cluster session replication. Primitive types (String,
 * Boolean, Long, Double) are encoded directly in protobuf format. Complex
 * objects fall back to Java serialization with a strict class allowlist.
 * Arbitrary webapp classes are not permitted unless explicitly named via
 * {@link #configureAllowedClasses(Set)}.
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
        byte[] idBytes = ByteArrays.toByteArray(session.id);
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
                id = ByteArrays.toHexString(asBytes(data));
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
        try (ObjectInputStream ois = new ObjectInputStream(bais)) {
            ois.setObjectInputFilter(SESSION_DESERIALIZATION_FILTER);
            return ois.readObject();
        } catch (ClassNotFoundException e) {
            String message = L10N.getString("err.class_not_found");
            message = MessageFormat.format(message, e.getMessage());
            throw new IOException(message, e);
        }
    }

    // Explicitly allowed classes for Java-serialized session attributes
    private static final Set<String> ALLOWED_CLASSES;
    static {
        Set<String> allowed = new HashSet<>();
        allowed.add("java.lang.String");
        allowed.add("java.lang.Boolean");
        allowed.add("java.lang.Byte");
        allowed.add("java.lang.Character");
        allowed.add("java.lang.Short");
        allowed.add("java.lang.Integer");
        allowed.add("java.lang.Long");
        allowed.add("java.lang.Float");
        allowed.add("java.lang.Double");
        allowed.add("java.math.BigInteger");
        allowed.add("java.math.BigDecimal");
        allowed.add("java.util.ArrayList");
        allowed.add("java.util.LinkedList");
        allowed.add("java.util.Vector");
        allowed.add("java.util.HashMap");
        allowed.add("java.util.LinkedHashMap");
        allowed.add("java.util.TreeMap");
        allowed.add("java.util.Hashtable");
        allowed.add("java.util.Properties");
        allowed.add("java.util.HashSet");
        allowed.add("java.util.LinkedHashSet");
        allowed.add("java.util.TreeSet");
        allowed.add("java.util.Date");
        allowed.add("java.util.UUID");
        allowed.add("java.util.Locale");
        allowed.add("java.util.Currency");
        allowed.add("java.time.Instant");
        allowed.add("java.time.Duration");
        allowed.add("java.time.Period");
        allowed.add("java.time.LocalDate");
        allowed.add("java.time.LocalTime");
        allowed.add("java.time.LocalDateTime");
        allowed.add("java.time.ZonedDateTime");
        allowed.add("java.time.OffsetDateTime");
        allowed.add("java.time.OffsetTime");
        allowed.add("java.time.Year");
        allowed.add("java.time.YearMonth");
        allowed.add("java.time.MonthDay");
        allowed.add("java.time.ZoneId");
        allowed.add("java.time.ZoneOffset");
        ALLOWED_CLASSES = Collections.unmodifiableSet(allowed);
    }

    /** Optional deployment-specific classes (fully qualified names). */
    private static final AtomicReference<Set<String>> CONFIGURED_ALLOWED_CLASSES =
            new AtomicReference<Set<String>>(Collections.<String>emptySet());

    /**
     * Configures additional fully qualified class names permitted during
     * Java-serialized attribute deserialization. Use only for known-safe
     * application types required in replicated sessions.
     *
     * @param classNames allowed class names, or null/empty to clear extras
     */
    static void configureAllowedClasses(Set<String> classNames) {
        if (classNames == null || classNames.isEmpty()) {
            CONFIGURED_ALLOWED_CLASSES.set(Collections.<String>emptySet());
            return;
        }
        CONFIGURED_ALLOWED_CLASSES.set(Collections.unmodifiableSet(
                new HashSet<String>(classNames)));
    }

    /**
     * Returns whether a class may be deserialized as a session attribute.
     * Package-visible for unit tests.
     */
    static boolean isAllowedDeserializationClass(Class<?> clazz) {
        if (clazz == null) {
            return true;
        }
        if (clazz.isPrimitive()) {
            return true;
        }
        if (clazz.isArray()) {
            return isAllowedDeserializationClass(clazz.getComponentType());
        }
        if (clazz.isEnum()) {
            Package pkg = clazz.getPackage();
            if (pkg != null) {
                String pkgName = pkg.getName();
                if ("java.lang".equals(pkgName) || "java.util".equals(pkgName)
                        || "java.math".equals(pkgName)
                        || pkgName.startsWith("java.time")) {
                    return true;
                }
            }
            return CONFIGURED_ALLOWED_CLASSES.get().contains(clazz.getName());
        }
        String className = clazz.getName();
        if (ALLOWED_CLASSES.contains(className)
                || CONFIGURED_ALLOWED_CLASSES.get().contains(className)) {
            return true;
        }
        return isAllowedJdkInternalClass(className);
    }

    private static boolean isAllowedJdkCollectionImpl(String className) {
        return className.startsWith("java.util.Collections$")
                || className.startsWith("java.util.ImmutableCollections$")
                || className.startsWith("java.util.Arrays$");
    }

    /** JDK collection implementation types reached during readObject. */
    private static boolean isAllowedJdkInternalClass(String className) {
        if (isAllowedJdkCollectionImpl(className)) {
            return true;
        }
        return className.startsWith("java.util.HashMap$")
                || className.startsWith("java.util.HashSet$")
                || className.startsWith("java.util.LinkedHashMap$")
                || className.startsWith("java.util.LinkedHashSet$")
                || className.startsWith("java.util.TreeMap$")
                || className.startsWith("java.util.TreeSet$")
                || className.startsWith("java.util.ArrayList$")
                || className.startsWith("java.util.Vector$")
                || "java.util.Map$Entry".equals(className);
    }

    // Known gadget class prefixes (defense in depth atop the allowlist)
    private static final Set<String> DENIED_CLASSES;
    static {
        Set<String> denied = new HashSet<>();
        denied.add("org.apache.commons.collections.functors.");
        denied.add("org.apache.commons.collections4.functors.");
        denied.add("org.apache.xalan.");
        denied.add("com.sun.org.apache.xalan.");
        denied.add("org.codehaus.groovy.runtime.");
        denied.add("org.springframework.beans.factory.");
        denied.add("com.mchange.v2.c3p0.");
        denied.add("com.sun.rowset.JdbcRowSetImpl");
        denied.add("java.rmi.server.UnicastRemoteObject");
        denied.add("javax.management.");
        denied.add("java.net.URL");
        DENIED_CLASSES = Collections.unmodifiableSet(denied);
    }

    /**
     * ObjectInputFilter that restricts deserialization to {@link #ALLOWED_CLASSES}
     * plus any names configured via {@link #configureAllowedClasses(Set)}.
     */
    private static final java.io.ObjectInputFilter SESSION_DESERIALIZATION_FILTER =
            filterInfo -> {
        Class<?> clazz = filterInfo.serialClass();
        if (clazz == null) {
            return java.io.ObjectInputFilter.Status.UNDECIDED;
        }

        String className = clazz.getName();

        for (String denied : DENIED_CLASSES) {
            if (className.startsWith(denied) || className.equals(denied)) {
                String message = L10N.getString("warn.blocked_class");
                message = MessageFormat.format(message, className);
                LOGGER.warning(message);
                return java.io.ObjectInputFilter.Status.REJECTED;
            }
        }

        if (isAllowedDeserializationClass(clazz)) {
            return java.io.ObjectInputFilter.Status.ALLOWED;
        }

        String message = L10N.getString("warn.rejected_class");
        message = MessageFormat.format(message, className);
        LOGGER.warning(message);
        return java.io.ObjectInputFilter.Status.REJECTED;
    };

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
        byte[] idBytes = ByteArrays.toByteArray(session.id);
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
                    id = ByteArrays.toHexString(asBytes(data));
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


}
