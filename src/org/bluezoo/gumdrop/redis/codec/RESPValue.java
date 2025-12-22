/*
 * RESPValue.java
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

package org.bluezoo.gumdrop.redis.codec;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Represents a decoded RESP value.
 *
 * <p>A RESP value can be one of several types: simple string, error, integer,
 * bulk string, or array. This class provides type-safe access to the value
 * based on its type.
 *
 * <p>Bulk strings and arrays can also be null, represented by the special
 * null instance returned by {@link #nullValue()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class RESPValue {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    /** Singleton null value instance. */
    private static final RESPValue NULL = new RESPValue(null, null);

    private final RESPType type;
    private final Object value;

    /**
     * Creates a new RESP value.
     *
     * @param type the RESP type
     * @param value the value (type depends on RESP type)
     */
    private RESPValue(RESPType type, Object value) {
        this.type = type;
        this.value = value;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Factory methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the null value singleton.
     *
     * @return the null RESP value
     */
    public static RESPValue nullValue() {
        return NULL;
    }

    /**
     * Creates a simple string value.
     *
     * @param value the string value
     * @return the RESP value
     */
    public static RESPValue simpleString(String value) {
        return new RESPValue(RESPType.SIMPLE_STRING, value);
    }

    /**
     * Creates an error value.
     *
     * @param message the error message
     * @return the RESP value
     */
    public static RESPValue error(String message) {
        return new RESPValue(RESPType.ERROR, message);
    }

    /**
     * Creates an integer value.
     *
     * @param value the integer value
     * @return the RESP value
     */
    public static RESPValue integer(long value) {
        return new RESPValue(RESPType.INTEGER, Long.valueOf(value));
    }

    /**
     * Creates a bulk string value.
     *
     * @param value the byte array value
     * @return the RESP value
     */
    public static RESPValue bulkString(byte[] value) {
        return new RESPValue(RESPType.BULK_STRING, value);
    }

    /**
     * Creates an array value.
     *
     * @param elements the array elements
     * @return the RESP value
     */
    public static RESPValue array(List<RESPValue> elements) {
        return new RESPValue(RESPType.ARRAY, elements);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Type checking
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the RESP type of this value.
     *
     * @return the type, or null if this is a null value
     */
    public RESPType getType() {
        return type;
    }

    /**
     * Returns whether this is a null value.
     *
     * @return true if null
     */
    public boolean isNull() {
        return type == null;
    }

    /**
     * Returns whether this is a simple string.
     *
     * @return true if simple string
     */
    public boolean isSimpleString() {
        return type == RESPType.SIMPLE_STRING;
    }

    /**
     * Returns whether this is an error.
     *
     * @return true if error
     */
    public boolean isError() {
        return type == RESPType.ERROR;
    }

    /**
     * Returns whether this is an integer.
     *
     * @return true if integer
     */
    public boolean isInteger() {
        return type == RESPType.INTEGER;
    }

    /**
     * Returns whether this is a bulk string.
     *
     * @return true if bulk string
     */
    public boolean isBulkString() {
        return type == RESPType.BULK_STRING;
    }

    /**
     * Returns whether this is an array.
     *
     * @return true if array
     */
    public boolean isArray() {
        return type == RESPType.ARRAY;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Value access
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns this value as a string.
     *
     * <p>For simple strings and errors, returns the string directly.
     * For bulk strings, decodes as UTF-8.
     * For integers, returns the string representation.
     * For arrays and null, returns null.
     *
     * @return the string value, or null
     */
    public String asString() {
        if (type == null) {
            return null;
        }
        switch (type) {
            case SIMPLE_STRING:
            case ERROR:
                return (String) value;
            case BULK_STRING:
                byte[] bytes = (byte[]) value;
                return new String(bytes, UTF_8);
            case INTEGER:
                return value.toString();
            default:
                return null;
        }
    }

    /**
     * Returns this value as a long integer.
     *
     * @return the integer value
     * @throws IllegalStateException if this is not an integer type
     */
    public long asLong() {
        if (type != RESPType.INTEGER) {
            throw new IllegalStateException("Not an integer value");
        }
        return ((Long) value).longValue();
    }

    /**
     * Returns this value as an integer.
     *
     * @return the integer value
     * @throws IllegalStateException if this is not an integer type
     */
    public int asInt() {
        return (int) asLong();
    }

    /**
     * Returns this value as a byte array.
     *
     * <p>For bulk strings, returns the raw bytes.
     * For simple strings and errors, encodes as UTF-8.
     *
     * @return the byte array, or null if null or array type
     */
    public byte[] asBytes() {
        if (type == null) {
            return null;
        }
        switch (type) {
            case BULK_STRING:
                return (byte[]) value;
            case SIMPLE_STRING:
            case ERROR:
                return ((String) value).getBytes(UTF_8);
            default:
                return null;
        }
    }

    /**
     * Returns this value as an array of RESP values.
     *
     * @return the array elements, or null if not an array
     */
    @SuppressWarnings("unchecked")
    public List<RESPValue> asArray() {
        if (type != RESPType.ARRAY) {
            return null;
        }
        return (List<RESPValue>) value;
    }

    /**
     * Returns the error message if this is an error value.
     *
     * @return the error message, or null if not an error
     */
    public String getErrorMessage() {
        if (type != RESPType.ERROR) {
            return null;
        }
        return (String) value;
    }

    /**
     * Returns the error type if this is an error value.
     *
     * <p>Redis errors typically have a prefix like "ERR", "WRONGTYPE",
     * "MOVED", etc.
     *
     * @return the error type prefix, or null if not an error
     */
    public String getErrorType() {
        if (type != RESPType.ERROR) {
            return null;
        }
        String message = (String) value;
        int space = message.indexOf(' ');
        if (space > 0) {
            return message.substring(0, space);
        }
        return message;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Object methods
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        if (type == null) {
            return "null";
        }
        switch (type) {
            case SIMPLE_STRING:
                return "+" + value;
            case ERROR:
                return "-" + value;
            case INTEGER:
                return ":" + value;
            case BULK_STRING:
                byte[] bytes = (byte[]) value;
                return "$" + bytes.length + ":" + new String(bytes, UTF_8);
            case ARRAY:
                @SuppressWarnings("unchecked")
                List<RESPValue> elements = (List<RESPValue>) value;
                return "*" + elements.size();
            default:
                return "unknown";
        }
    }

}
