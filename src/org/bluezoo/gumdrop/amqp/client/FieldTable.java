/*
 * FieldTable.java
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

package org.bluezoo.gumdrop.amqp.client;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AMQP 0-9-1 field-table: an ordered {@code String -> Object} map used for
 * method arguments, message headers, and connection/queue/exchange
 * declaration arguments.
 *
 * <p>Supported value types (the common RabbitMQ-compatible subset — the
 * bare AMQP 0-9-1 spec leaves some type tags vendor-defined, and this is
 * the set every broker in practice understands): {@link Boolean},
 * {@link Byte}, {@link Short}, {@link Integer}, {@link Long},
 * {@link Float}, {@link Double}, {@link BigDecimal}, {@link String}
 * (encoded as AMQP long-string), {@code byte[]}, {@link Date} (as an
 * AMQP timestamp — seconds since the epoch), nested {@link FieldTable},
 * {@link List} (AMQP field-array), and {@code null} (AMQP void).
 *
 * <p>Instances are mutable builders as well as the decoded representation
 * — construct with {@link #FieldTable()}, populate with {@code put*}, and
 * either {@link #encode()} it or hand it to a method call.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rabbitmq.com/resources/specs/amqp0-9-1.pdf">AMQP 0-9-1 specification, §4.2.5.5</a>
 */
public final class FieldTable {

    // Field value type tags (RabbitMQ-compatible subset of AMQP 0-9-1 §4.2.5.5)
    private static final byte TAG_BOOLEAN = 't';
    private static final byte TAG_SHORT_SHORT_INT = 'b';
    private static final byte TAG_SHORT_SHORT_UINT = 'B';
    private static final byte TAG_SHORT_INT = 'U';
    private static final byte TAG_SHORT_UINT = 'u';
    private static final byte TAG_LONG_INT = 'I';
    private static final byte TAG_LONG_UINT = 'i';
    private static final byte TAG_LONG_LONG_INT = 'L';
    private static final byte TAG_LONG_LONG_UINT = 'l';
    private static final byte TAG_FLOAT = 'f';
    private static final byte TAG_DOUBLE = 'd';
    private static final byte TAG_DECIMAL = 'D';
    private static final byte TAG_LONG_STRING = 'S';
    private static final byte TAG_ARRAY = 'A';
    private static final byte TAG_TIMESTAMP = 'T';
    private static final byte TAG_FIELD_TABLE = 'F';
    private static final byte TAG_BYTE_ARRAY = 'x';
    private static final byte TAG_VOID = 'V';

    private static final int MAX_SHORT_STRING_LENGTH = 255;

    private final Map<String, Object> values;

    public FieldTable() {
        this.values = new LinkedHashMap<String, Object>();
    }

    private FieldTable(Map<String, Object> values) {
        this.values = values;
    }

    public FieldTable put(String name, Object value) {
        checkValueType(value);
        values.put(name, value);
        return this;
    }

    public Object get(String name) {
        return values.get(name);
    }

    public boolean containsKey(String name) {
        return values.containsKey(name);
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(values);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    private static void checkValueType(Object value) {
        if (value == null
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigDecimal
                || value instanceof String
                || value instanceof byte[]
                || value instanceof Date
                || value instanceof FieldTable
                || value instanceof List) {
            return;
        }
        throw new IllegalArgumentException(
                "Unsupported field-table value type: " + value.getClass().getName());
    }

    // ── Encoding ──

    /**
     * Encodes this table's contents (without the leading 4-byte size
     * prefix that a containing frame/property list writes itself).
     */
    public ByteBuffer encode() {
        // Two-pass: compute exact size first so we can allocate once.
        int size = encodedContentSize();
        ByteBuffer buf = ByteBuffer.allocate(size);
        writeEntries(buf, values);
        buf.flip();
        return buf;
    }

    /** Size in bytes of {@link #encode()}'s output. */
    public int encodedContentSize() {
        int total = 0;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            total += shortStringSize(e.getKey());
            total += valueSize(e.getValue());
        }
        return total;
    }

    private static void writeEntries(ByteBuffer buf, Map<String, Object> map) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            writeShortString(buf, e.getKey());
            writeValue(buf, e.getValue());
        }
    }

    private static void writeValue(ByteBuffer buf, Object value) {
        if (value == null) {
            buf.put(TAG_VOID);
        } else if (value instanceof Boolean) {
            buf.put(TAG_BOOLEAN);
            buf.put((byte) (((Boolean) value) ? 1 : 0));
        } else if (value instanceof Byte) {
            buf.put(TAG_SHORT_SHORT_INT);
            buf.put((Byte) value);
        } else if (value instanceof Short) {
            buf.put(TAG_SHORT_INT);
            buf.putShort((Short) value);
        } else if (value instanceof Integer) {
            buf.put(TAG_LONG_INT);
            buf.putInt((Integer) value);
        } else if (value instanceof Long) {
            buf.put(TAG_LONG_LONG_INT);
            buf.putLong((Long) value);
        } else if (value instanceof Float) {
            buf.put(TAG_FLOAT);
            buf.putFloat((Float) value);
        } else if (value instanceof Double) {
            buf.put(TAG_DOUBLE);
            buf.putDouble((Double) value);
        } else if (value instanceof BigDecimal) {
            BigDecimal d = (BigDecimal) value;
            buf.put(TAG_DECIMAL);
            buf.put((byte) d.scale());
            buf.putInt(d.unscaledValue().intValueExact());
        } else if (value instanceof String) {
            buf.put(TAG_LONG_STRING);
            writeLongString(buf, (String) value);
        } else if (value instanceof byte[]) {
            byte[] b = (byte[]) value;
            buf.put(TAG_BYTE_ARRAY);
            buf.putInt(b.length);
            buf.put(b);
        } else if (value instanceof Date) {
            buf.put(TAG_TIMESTAMP);
            buf.putLong(((Date) value).getTime() / 1000L);
        } else if (value instanceof FieldTable) {
            FieldTable nested = (FieldTable) value;
            buf.put(TAG_FIELD_TABLE);
            buf.putInt(nested.encodedContentSize());
            writeEntries(buf, nested.values);
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            buf.put(TAG_ARRAY);
            int arraySize = 0;
            for (Object element : list) {
                arraySize += valueSize(element);
            }
            buf.putInt(arraySize);
            for (Object element : list) {
                writeValue(buf, element);
            }
        } else {
            throw new IllegalArgumentException(
                    "Unsupported field-table value type: " + value.getClass().getName());
        }
    }

    private static int valueSize(Object value) {
        if (value == null) {
            return 1;
        } else if (value instanceof Boolean) {
            return 2;
        } else if (value instanceof Byte) {
            return 2;
        } else if (value instanceof Short) {
            return 3;
        } else if (value instanceof Integer) {
            return 5;
        } else if (value instanceof Long) {
            return 9;
        } else if (value instanceof Float) {
            return 5;
        } else if (value instanceof Double) {
            return 9;
        } else if (value instanceof BigDecimal) {
            return 6; // tag + scale(1) + int(4)
        } else if (value instanceof String) {
            return 1 + 4 + utf8Length((String) value);
        } else if (value instanceof byte[]) {
            return 1 + 4 + ((byte[]) value).length;
        } else if (value instanceof Date) {
            return 9;
        } else if (value instanceof FieldTable) {
            return 1 + 4 + ((FieldTable) value).encodedContentSize();
        } else if (value instanceof List) {
            int total = 1 + 4;
            for (Object element : (List<?>) value) {
                total += valueSize(element);
            }
            return total;
        }
        throw new IllegalArgumentException(
                "Unsupported field-table value type: " + value.getClass().getName());
    }

    private static int shortStringSize(String s) {
        int len = utf8Length(s);
        if (len > MAX_SHORT_STRING_LENGTH) {
            throw new IllegalArgumentException(
                    "Field name too long for short-string (max 255 UTF-8 bytes): " + s);
        }
        return 1 + len;
    }

    private static void writeShortString(ByteBuffer buf, String s) {
        byte[] bytes = utf8Bytes(s);
        buf.put((byte) bytes.length);
        buf.put(bytes);
    }

    private static void writeLongString(ByteBuffer buf, String s) {
        byte[] bytes = utf8Bytes(s);
        buf.putInt(bytes.length);
        buf.put(bytes);
    }

    private static byte[] utf8Bytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError("UTF-8 not supported", e);
        }
    }

    private static int utf8Length(String s) {
        return utf8Bytes(s).length;
    }

    // ── Decoding ──

    /**
     * Decodes a field-table from {@code buf}, consuming exactly
     * {@code contentSize} bytes (the size the caller already read from
     * the containing frame/property list's 4-byte length prefix).
     */
    public static FieldTable decode(ByteBuffer buf, int contentSize) throws AMQPProtocolException {
        int end = buf.position() + contentSize;
        if (end > buf.limit()) {
            throw new AMQPProtocolException("Truncated field-table");
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
        while (buf.position() < end) {
            String name = readShortString(buf);
            Object value = readValue(buf);
            map.put(name, value);
        }
        if (buf.position() != end) {
            throw new AMQPProtocolException("Field-table entries overran declared size");
        }
        return new FieldTable(map);
    }

    private static Object readValue(ByteBuffer buf) throws AMQPProtocolException {
        if (!buf.hasRemaining()) {
            throw new AMQPProtocolException("Truncated field-table value");
        }
        byte tag = buf.get();
        switch (tag) {
            case TAG_VOID:
                return null;
            case TAG_BOOLEAN:
                return buf.get() != 0;
            case TAG_SHORT_SHORT_INT:
                return buf.get();
            case TAG_SHORT_SHORT_UINT:
                return (short) (buf.get() & 0xFF);
            case TAG_SHORT_INT:
                return buf.getShort();
            case TAG_SHORT_UINT:
                return buf.getShort() & 0xFFFF;
            case TAG_LONG_INT:
                return buf.getInt();
            case TAG_LONG_UINT:
                return buf.getInt() & 0xFFFFFFFFL;
            case TAG_LONG_LONG_INT:
                return buf.getLong();
            case TAG_LONG_LONG_UINT:
                return buf.getLong(); // caller must treat as unsigned if the high bit matters
            case TAG_FLOAT:
                return buf.getFloat();
            case TAG_DOUBLE:
                return buf.getDouble();
            case TAG_DECIMAL: {
                int scale = buf.get() & 0xFF;
                int unscaled = buf.getInt();
                return new BigDecimal(BigInteger.valueOf(unscaled), scale);
            }
            case TAG_LONG_STRING:
                return readLongString(buf);
            case TAG_BYTE_ARRAY: {
                int len = buf.getInt();
                byte[] b = new byte[len];
                buf.get(b);
                return b;
            }
            case TAG_TIMESTAMP:
                return new Date(buf.getLong() * 1000L);
            case TAG_FIELD_TABLE: {
                int len = buf.getInt();
                return decode(buf, len);
            }
            case TAG_ARRAY: {
                int len = buf.getInt();
                int end = buf.position() + len;
                List<Object> list = new ArrayList<Object>();
                while (buf.position() < end) {
                    list.add(readValue(buf));
                }
                return list;
            }
            default:
                throw new AMQPProtocolException(
                        "Unsupported field-table value tag: 0x" + Integer.toHexString(tag & 0xFF));
        }
    }

    private static String readShortString(ByteBuffer buf) throws AMQPProtocolException {
        if (!buf.hasRemaining()) {
            throw new AMQPProtocolException("Truncated short-string length");
        }
        int len = buf.get() & 0xFF;
        if (buf.remaining() < len) {
            throw new AMQPProtocolException("Truncated short-string content");
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readLongString(ByteBuffer buf) throws AMQPProtocolException {
        if (buf.remaining() < 4) {
            throw new AMQPProtocolException("Truncated long-string length");
        }
        int len = buf.getInt();
        if (len < 0 || buf.remaining() < len) {
            throw new AMQPProtocolException("Truncated long-string content");
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Writes a short-string (1-byte length + UTF-8 bytes) — used by callers outside a table too. */
    static void putShortString(ByteBuffer buf, String s) {
        writeShortString(buf, s);
    }

    /** Size in bytes of a short-string encoding of {@code s}. */
    static int shortStringEncodedSize(String s) {
        return shortStringSize(s);
    }

    /** Reads a short-string (1-byte length + UTF-8 bytes) — used by callers outside a table too. */
    static String getShortString(ByteBuffer buf) throws AMQPProtocolException {
        return readShortString(buf);
    }

    /** Writes a long-string (4-byte length + UTF-8 bytes) — used by callers outside a table too. */
    static void putLongString(ByteBuffer buf, String s) {
        writeLongString(buf, s);
    }

    /** Size in bytes of a long-string encoding of {@code s}. */
    static int longStringEncodedSize(String s) {
        return 4 + utf8Length(s);
    }

    /** Reads a long-string (4-byte length + UTF-8 bytes) — used by callers outside a table too. */
    static String getLongString(ByteBuffer buf) throws AMQPProtocolException {
        return readLongString(buf);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FieldTable)) {
            return false;
        }
        return values.equals(((FieldTable) o).values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
