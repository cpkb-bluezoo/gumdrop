/*
 * MQTTProperties.java
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

package org.bluezoo.gumdrop.mqtt.codec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MQTT 5.0 properties (section 2.2.2).
 *
 * <p>For MQTT 3.1.1 connections, properties are always empty. The data
 * model accommodates both versions so that packet types don't need to
 * change when 5.0 support is enabled.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MQTTProperties {

    // MQTT 5.0 property identifiers (section 2.2.2.2)
    public static final int PAYLOAD_FORMAT_INDICATOR = 0x01;
    public static final int MESSAGE_EXPIRY_INTERVAL = 0x02;
    public static final int CONTENT_TYPE = 0x03;
    public static final int RESPONSE_TOPIC = 0x08;
    public static final int CORRELATION_DATA = 0x09;
    public static final int SUBSCRIPTION_IDENTIFIER = 0x0B;
    public static final int SESSION_EXPIRY_INTERVAL = 0x11;
    public static final int ASSIGNED_CLIENT_IDENTIFIER = 0x12;
    public static final int SERVER_KEEP_ALIVE = 0x13;
    public static final int AUTHENTICATION_METHOD = 0x15;
    public static final int AUTHENTICATION_DATA = 0x16;
    public static final int REQUEST_PROBLEM_INFORMATION = 0x17;
    public static final int WILL_DELAY_INTERVAL = 0x18;
    public static final int REQUEST_RESPONSE_INFORMATION = 0x19;
    public static final int RESPONSE_INFORMATION = 0x1A;
    public static final int SERVER_REFERENCE = 0x1C;
    public static final int REASON_STRING = 0x1F;
    public static final int RECEIVE_MAXIMUM = 0x21;
    public static final int TOPIC_ALIAS_MAXIMUM = 0x22;
    public static final int TOPIC_ALIAS = 0x23;
    public static final int MAXIMUM_QOS = 0x24;
    public static final int RETAIN_AVAILABLE = 0x25;
    public static final int USER_PROPERTY = 0x26;
    public static final int MAXIMUM_PACKET_SIZE = 0x27;
    public static final int WILDCARD_SUBSCRIPTION_AVAILABLE = 0x28;
    public static final int SUBSCRIPTION_IDENTIFIER_AVAILABLE = 0x29;
    public static final int SHARED_SUBSCRIPTION_AVAILABLE = 0x2A;

    /** Shared empty instance for 3.1.1 packets. */
    public static final MQTTProperties EMPTY = new MQTTProperties();

    private Map<Integer, Object> properties;

    public MQTTProperties() {
    }

    public boolean isEmpty() {
        return properties == null || properties.isEmpty();
    }

    // -- Typed accessors --

    public Integer getIntegerProperty(int id) {
        if (properties == null) return null;
        Object val = properties.get(id);
        return val instanceof Integer ? (Integer) val : null;
    }

    public String getStringProperty(int id) {
        if (properties == null) return null;
        Object val = properties.get(id);
        return val instanceof String ? (String) val : null;
    }

    public byte[] getBinaryProperty(int id) {
        if (properties == null) return null;
        Object val = properties.get(id);
        return val instanceof byte[] ? (byte[]) val : null;
    }

    @SuppressWarnings("unchecked")
    public List<String[]> getUserProperties() {
        if (properties == null) return null;
        Object val = properties.get(USER_PROPERTY);
        return val instanceof List ? (List<String[]>) val : null;
    }

    // -- Mutators --

    public void setIntegerProperty(int id, int value) {
        ensureMap();
        properties.put(id, value);
    }

    public void setStringProperty(int id, String value) {
        ensureMap();
        properties.put(id, value);
    }

    public void setBinaryProperty(int id, byte[] value) {
        ensureMap();
        properties.put(id, value);
    }

    @SuppressWarnings("unchecked")
    public void addUserProperty(String key, String value) {
        ensureMap();
        List<String[]> list = (List<String[]>) properties.get(USER_PROPERTY);
        if (list == null) {
            list = new ArrayList<>();
            properties.put(USER_PROPERTY, list);
        }
        list.add(new String[]{key, value});
    }

    private void ensureMap() {
        if (properties == null) {
            properties = new LinkedHashMap<>();
        }
    }

    // -- Wire format encoding --

    /**
     * Returns the encoded length of these properties (not including the
     * variable-length integer that encodes this length).
     */
    public int encodedLength() {
        if (isEmpty()) return 0;
        int len = 0;
        for (Map.Entry<Integer, Object> entry : properties.entrySet()) {
            int id = entry.getKey();
            Object val = entry.getValue();
            if (id == USER_PROPERTY && val instanceof List) {
                @SuppressWarnings("unchecked")
                List<String[]> pairs = (List<String[]>) val;
                for (String[] pair : pairs) {
                    len += 1; // property id
                    len += 2 + pair[0].getBytes(StandardCharsets.UTF_8).length;
                    len += 2 + pair[1].getBytes(StandardCharsets.UTF_8).length;
                }
            } else {
                len += 1; // property id
                len += encodedValueLength(id, val);
            }
        }
        return len;
    }

    /**
     * Encodes these properties into the buffer. Writes the property length
     * as a variable-length integer followed by the properties themselves.
     */
    public void encode(ByteBuffer buf) {
        int propLen = encodedLength();
        VariableLengthEncoding.encode(buf, propLen);
        if (propLen == 0) return;

        for (Map.Entry<Integer, Object> entry : properties.entrySet()) {
            int id = entry.getKey();
            Object val = entry.getValue();
            if (id == USER_PROPERTY && val instanceof List) {
                @SuppressWarnings("unchecked")
                List<String[]> pairs = (List<String[]>) val;
                for (String[] pair : pairs) {
                    buf.put((byte) id);
                    encodeUTF8String(buf, pair[0]);
                    encodeUTF8String(buf, pair[1]);
                }
            } else {
                buf.put((byte) id);
                encodeValue(buf, id, val);
            }
        }
    }

    /**
     * Decodes properties from the buffer. The buffer position should be
     * at the start of the property length field.
     *
     * @return decoded properties, or {@link #EMPTY} if length is zero
     */
    public static MQTTProperties decode(ByteBuffer buf) {
        int propLen = VariableLengthEncoding.decode(buf);
        if (propLen <= 0) return EMPTY;

        int endPos = buf.position() + propLen;
        MQTTProperties props = new MQTTProperties();

        while (buf.position() < endPos) {
            int id = buf.get() & 0xFF;
            switch (id) {
                case PAYLOAD_FORMAT_INDICATOR:
                case REQUEST_PROBLEM_INFORMATION:
                case REQUEST_RESPONSE_INFORMATION:
                case MAXIMUM_QOS:
                case RETAIN_AVAILABLE:
                case WILDCARD_SUBSCRIPTION_AVAILABLE:
                case SUBSCRIPTION_IDENTIFIER_AVAILABLE:
                case SHARED_SUBSCRIPTION_AVAILABLE:
                    props.setIntegerProperty(id, buf.get() & 0xFF);
                    break;

                case MESSAGE_EXPIRY_INTERVAL:
                case SESSION_EXPIRY_INTERVAL:
                case WILL_DELAY_INTERVAL:
                case MAXIMUM_PACKET_SIZE:
                    props.setIntegerProperty(id, buf.getInt());
                    break;

                case SERVER_KEEP_ALIVE:
                case RECEIVE_MAXIMUM:
                case TOPIC_ALIAS_MAXIMUM:
                case TOPIC_ALIAS:
                    props.setIntegerProperty(id, buf.getShort() & 0xFFFF);
                    break;

                case SUBSCRIPTION_IDENTIFIER:
                    props.setIntegerProperty(id,
                            VariableLengthEncoding.decode(buf));
                    break;

                case CONTENT_TYPE:
                case RESPONSE_TOPIC:
                case ASSIGNED_CLIENT_IDENTIFIER:
                case AUTHENTICATION_METHOD:
                case RESPONSE_INFORMATION:
                case SERVER_REFERENCE:
                case REASON_STRING:
                    props.setStringProperty(id, decodeUTF8String(buf));
                    break;

                case CORRELATION_DATA:
                case AUTHENTICATION_DATA:
                    props.setBinaryProperty(id, decodeBinaryData(buf));
                    break;

                case USER_PROPERTY:
                    String key = decodeUTF8String(buf);
                    String value = decodeUTF8String(buf);
                    props.addUserProperty(key, value);
                    break;

                default:
                    // Skip unknown property — advance to end
                    buf.position(endPos);
                    break;
            }
        }
        return props;
    }

    // -- UTF-8 string helpers --

    static void encodeUTF8String(ByteBuffer buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buf.putShort((short) bytes.length);
        buf.put(bytes);
    }

    static String decodeUTF8String(ByteBuffer buf) {
        int len = buf.getShort() & 0xFFFF;
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static byte[] decodeBinaryData(ByteBuffer buf) {
        int len = buf.getShort() & 0xFFFF;
        byte[] data = new byte[len];
        buf.get(data);
        return data;
    }

    private int encodedValueLength(int id, Object val) {
        switch (id) {
            case PAYLOAD_FORMAT_INDICATOR:
            case REQUEST_PROBLEM_INFORMATION:
            case REQUEST_RESPONSE_INFORMATION:
            case MAXIMUM_QOS:
            case RETAIN_AVAILABLE:
            case WILDCARD_SUBSCRIPTION_AVAILABLE:
            case SUBSCRIPTION_IDENTIFIER_AVAILABLE:
            case SHARED_SUBSCRIPTION_AVAILABLE:
                return 1;

            case MESSAGE_EXPIRY_INTERVAL:
            case SESSION_EXPIRY_INTERVAL:
            case WILL_DELAY_INTERVAL:
            case MAXIMUM_PACKET_SIZE:
                return 4;

            case SERVER_KEEP_ALIVE:
            case RECEIVE_MAXIMUM:
            case TOPIC_ALIAS_MAXIMUM:
            case TOPIC_ALIAS:
                return 2;

            case SUBSCRIPTION_IDENTIFIER:
                return VariableLengthEncoding.encodedLength((Integer) val);

            case CONTENT_TYPE:
            case RESPONSE_TOPIC:
            case ASSIGNED_CLIENT_IDENTIFIER:
            case AUTHENTICATION_METHOD:
            case RESPONSE_INFORMATION:
            case SERVER_REFERENCE:
            case REASON_STRING:
                return 2 + ((String) val).getBytes(StandardCharsets.UTF_8).length;

            case CORRELATION_DATA:
            case AUTHENTICATION_DATA:
                return 2 + ((byte[]) val).length;

            default:
                return 0;
        }
    }

    private void encodeValue(ByteBuffer buf, int id, Object val) {
        switch (id) {
            case PAYLOAD_FORMAT_INDICATOR:
            case REQUEST_PROBLEM_INFORMATION:
            case REQUEST_RESPONSE_INFORMATION:
            case MAXIMUM_QOS:
            case RETAIN_AVAILABLE:
            case WILDCARD_SUBSCRIPTION_AVAILABLE:
            case SUBSCRIPTION_IDENTIFIER_AVAILABLE:
            case SHARED_SUBSCRIPTION_AVAILABLE:
                buf.put((byte) ((Integer) val).intValue());
                break;

            case MESSAGE_EXPIRY_INTERVAL:
            case SESSION_EXPIRY_INTERVAL:
            case WILL_DELAY_INTERVAL:
            case MAXIMUM_PACKET_SIZE:
                buf.putInt((Integer) val);
                break;

            case SERVER_KEEP_ALIVE:
            case RECEIVE_MAXIMUM:
            case TOPIC_ALIAS_MAXIMUM:
            case TOPIC_ALIAS:
                buf.putShort((short) ((Integer) val).intValue());
                break;

            case SUBSCRIPTION_IDENTIFIER:
                VariableLengthEncoding.encode(buf, (Integer) val);
                break;

            case CONTENT_TYPE:
            case RESPONSE_TOPIC:
            case ASSIGNED_CLIENT_IDENTIFIER:
            case AUTHENTICATION_METHOD:
            case RESPONSE_INFORMATION:
            case SERVER_REFERENCE:
            case REASON_STRING:
                encodeUTF8String(buf, (String) val);
                break;

            case CORRELATION_DATA:
            case AUTHENTICATION_DATA:
                byte[] data = (byte[]) val;
                buf.putShort((short) data.length);
                buf.put(data);
                break;

            default:
                break;
        }
    }
}
