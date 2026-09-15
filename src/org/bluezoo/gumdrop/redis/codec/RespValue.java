/*
 * RespValue.java
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
import java.util.Map;

/**
 * Represents a decoded RESP value (RESP spec — "RESP protocol description").
 *
 * <p>A RESP value can be one of the RESP2 types: Simple String (+),
 * Error (-), Integer (:), Bulk String ($), or Array (*), or one of
 * the RESP3 types: Map (%), Set (~), Double (,), Boolean (#), Null (_),
 * Push (&gt;), Verbatim String (=), Big Number ((), Blob Error (!).
 *
 * <p>Bulk strings and arrays can also be null ({@code $-1\r\n} and
 * {@code *-1\r\n} respectively), represented by the special null
 * instance returned by {@link #nullValue()}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://redis.io/docs/reference/protocol-spec/">RESP Protocol Specification</a>
 */
public final class RespValue {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    /** Singleton null value instance. */
    private static final RespValue NULL = new RespValue(null, null);

    private final RespType type;
    private final Object value;

    /**
     * Creates a new RESP value.
     *
     * @param type the RESP type
     * @param value the value (type depends on RESP type)
     */
    private RespValue(RespType type, Object value) {
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
    public static RespValue nullValue() {
        return NULL;
    }

    /**
     * Creates a simple string value.
     *
     * @param value the string value
     * @return the RESP value
     */
    public static RespValue simpleString(String value) {
        return new RespValue(RespType.SIMPLE_STRING, value);
    }

    /**
     * Creates an error value.
     *
     * @param message the error message
     * @return the RESP value
     */
    public static RespValue error(String message) {
        return new RespValue(RespType.ERROR, message);
    }

    /**
     * Creates an integer value.
     *
     * @param value the integer value
     * @return the RESP value
     */
    public static RespValue integer(long value) {
        return new RespValue(RespType.INTEGER, Long.valueOf(value));
    }

    /**
     * Creates a bulk string value.
     *
     * @param value the byte array value
     * @return the RESP value
     */
    public static RespValue bulkString(byte[] value) {
        return new RespValue(RespType.BULK_STRING, value);
    }

    /**
     * Creates an array value.
     *
     * @param elements the array elements
     * @return the RESP value
     */
    public static RespValue array(List<RespValue> elements) {
        return new RespValue(RespType.ARRAY, elements);
    }

    // RESP3 factory methods

    /**
     * Creates a RESP3 map value.
     *
     * @param entries the map entries (key-value pairs of RESPValues)
     * @return the RESP value
     */
    public static RespValue map(Map<RespValue, RespValue> entries) {
        return new RespValue(RespType.MAP, entries);
    }

    /**
     * Creates a RESP3 set value.
     *
     * @param elements the set elements
     * @return the RESP value
     */
    public static RespValue set(List<RespValue> elements) {
        return new RespValue(RespType.SET, elements);
    }

    /**
     * Creates a RESP3 double value.
     *
     * @param value the double value
     * @return the RESP value
     */
    public static RespValue doubleValue(double value) {
        return new RespValue(RespType.DOUBLE, Double.valueOf(value));
    }

    /**
     * Creates a RESP3 boolean value.
     *
     * @param value the boolean value
     * @return the RESP value
     */
    public static RespValue booleanValue(boolean value) {
        return new RespValue(RespType.BOOLEAN, Boolean.valueOf(value));
    }

    /**
     * Creates a RESP3 explicit null value.
     *
     * @return the RESP3 null value
     */
    public static RespValue resp3Null() {
        return new RespValue(RespType.NULL, null);
    }

    /**
     * Creates a RESP3 push value (server-initiated out-of-band data).
     *
     * @param elements the push data elements
     * @return the RESP value
     */
    public static RespValue push(List<RespValue> elements) {
        return new RespValue(RespType.PUSH, elements);
    }

    /**
     * Creates a RESP3 verbatim string value.
     *
     * @param encoding the 3-character encoding hint (e.g. "txt", "mkd")
     * @param data the string data
     * @return the RESP value
     */
    public static RespValue verbatimString(String encoding, byte[] data) {
        return new RespValue(RespType.VERBATIM_STRING, new Object[] { encoding, data });
    }

    /**
     * Creates a RESP3 big number value.
     *
     * @param value the big number as a string
     * @return the RESP value
     */
    public static RespValue bigNumber(String value) {
        return new RespValue(RespType.BIG_NUMBER, value);
    }

    /**
     * Creates a RESP3 blob error value.
     *
     * @param data the error data
     * @return the RESP value
     */
    public static RespValue blobError(byte[] data) {
        return new RespValue(RespType.BLOB_ERROR, data);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Type checking
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the RESP type of this value.
     *
     * @return the type, or null if this is a null value
     */
    public RespType getType() {
        return type;
    }

    /**
     * Returns whether this is a null value (RESP2 null or RESP3 Null type).
     *
     * @return true if null
     */
    public boolean isNull() {
        return type == null || type == RespType.NULL;
    }

    /**
     * Returns whether this is a simple string.
     *
     * @return true if simple string
     */
    public boolean isSimpleString() {
        return type == RespType.SIMPLE_STRING;
    }

    /**
     * Returns whether this is an error (simple Error or RESP3 Blob Error).
     *
     * @return true if error
     */
    public boolean isError() {
        return type == RespType.ERROR || type == RespType.BLOB_ERROR;
    }

    /**
     * Returns whether this is an integer.
     *
     * @return true if integer
     */
    public boolean isInteger() {
        return type == RespType.INTEGER;
    }

    /**
     * Returns whether this is a bulk string.
     *
     * @return true if bulk string
     */
    public boolean isBulkString() {
        return type == RespType.BULK_STRING;
    }

    /**
     * Returns whether this is an array.
     *
     * @return true if array
     */
    public boolean isArray() {
        return type == RespType.ARRAY;
    }

    // RESP3 type checks

    /** Returns whether this is a RESP3 map. */
    public boolean isMap() { return type == RespType.MAP; }

    /** Returns whether this is a RESP3 set. */
    public boolean isSet() { return type == RespType.SET; }

    /** Returns whether this is a RESP3 double. */
    public boolean isDouble() { return type == RespType.DOUBLE; }

    /** Returns whether this is a RESP3 boolean. */
    public boolean isBoolean() { return type == RespType.BOOLEAN; }

    /** Returns whether this is a RESP3 push message. */
    public boolean isPush() { return type == RespType.PUSH; }

    /** Returns whether this is a RESP3 verbatim string. */
    public boolean isVerbatimString() { return type == RespType.VERBATIM_STRING; }

    /** Returns whether this is a RESP3 big number. */
    public boolean isBigNumber() { return type == RespType.BIG_NUMBER; }

    /** Returns whether this is a RESP3 blob error. */
    public boolean isBlobError() { return type == RespType.BLOB_ERROR; }

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
            case BIG_NUMBER:
                return (String) value;
            case BULK_STRING:
                return new String((byte[]) value, UTF_8);
            case BLOB_ERROR:
                return new String((byte[]) value, UTF_8);
            case INTEGER:
            case DOUBLE:
            case BOOLEAN:
                return value.toString();
            case VERBATIM_STRING:
                Object[] vs = (Object[]) value;
                return new String((byte[]) vs[1], UTF_8);
            case NULL:
                return null;
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
        if (type != RespType.INTEGER) {
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
     * Returns this value as a list of RESP values.
     * Works for Array (*), Set (~), and Push (&gt;) types.
     *
     * @return the elements, or null if not an array-like type
     */
    // Unchecked: value's runtime type is enforced by this class's own
    // constructors/parser to match `type`, so the cast is safe whenever
    // the type check above passes, though not statically provable.
    @SuppressWarnings("unchecked")
    public List<RespValue> asArray() {
        if (type == RespType.ARRAY || type == RespType.SET || type == RespType.PUSH) {
            return (List<RespValue>) value;
        }
        return null;
    }

    /**
     * Returns the error message if this is an error value (Error or Blob Error).
     *
     * @return the error message, or null if not an error
     */
    public String getErrorMessage() {
        if (type == RespType.ERROR) {
            return (String) value;
        }
        if (type == RespType.BLOB_ERROR) {
            return new String((byte[]) value, UTF_8);
        }
        return null;
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
        if (type != RespType.ERROR && type != RespType.BLOB_ERROR) {
            return null;
        }
        String message = asString();
        if (message == null) {
            return null;
        }
        int space = message.indexOf(' ');
        if (space > 0) {
            return message.substring(0, space);
        }
        return message;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESP3 value access
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns this value as a RESP3 map.
     *
     * @return the map entries, or null if not a map
     */
    // Unchecked: value's runtime type is enforced by this class's own
    // constructors/parser to match `type` (see asArray()).
    @SuppressWarnings("unchecked")
    public Map<RespValue, RespValue> asMap() {
        if (type != RespType.MAP) {
            return null;
        }
        return (Map<RespValue, RespValue>) value;
    }

    /**
     * Returns this value as a double.
     *
     * @return the double value
     * @throws IllegalStateException if this is not a double type
     */
    public double asDouble() {
        if (type == RespType.DOUBLE) {
            return ((Double) value).doubleValue();
        }
        if (type == RespType.INTEGER) {
            return ((Long) value).doubleValue();
        }
        throw new IllegalStateException("Not a double value");
    }

    /**
     * Returns this value as a boolean.
     *
     * @return the boolean value
     * @throws IllegalStateException if this is not a boolean type
     */
    public boolean asBoolean() {
        if (type != RespType.BOOLEAN) {
            throw new IllegalStateException("Not a boolean value");
        }
        return ((Boolean) value).booleanValue();
    }

    /**
     * Returns this value as a RESP3 push data array.
     *
     * @return the push elements, or null if not a push type
     */
    // Unchecked: value's runtime type is enforced by this class's own
    // constructors/parser to match `type` (see asArray()).
    @SuppressWarnings("unchecked")
    public List<RespValue> asPush() {
        if (type != RespType.PUSH) {
            return null;
        }
        return (List<RespValue>) value;
    }

    /**
     * Returns the encoding hint of a verbatim string (e.g. "txt", "mkd").
     *
     * @return the 3-character encoding hint, or null if not a verbatim string
     */
    public String getVerbatimEncoding() {
        if (type != RespType.VERBATIM_STRING) {
            return null;
        }
        Object[] vs = (Object[]) value;
        return (String) vs[0];
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Object methods
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        if (type == null) {
            return "null";
        }
        // The unchecked casts below (ARRAY/MAP/SET/PUSH) all rely on the
        // same invariant as asArray()/asMap()/asPush(): value's runtime
        // type is enforced by this class's own constructors/parser to
        // match `type`, verified here by the enclosing switch case.
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
                List<RespValue> aElems = (List<RespValue>) value;
                return "*" + aElems.size();
            case MAP:
                @SuppressWarnings("unchecked")
                Map<RespValue, RespValue> map = (Map<RespValue, RespValue>) value;
                return "%" + map.size();
            case SET:
                @SuppressWarnings("unchecked")
                List<RespValue> sElems = (List<RespValue>) value;
                return "~" + sElems.size();
            case DOUBLE:
                return "," + value;
            case BOOLEAN:
                return "#" + (((Boolean) value).booleanValue() ? "t" : "f");
            case NULL:
                return "_";
            case PUSH:
                @SuppressWarnings("unchecked")
                List<RespValue> pElems = (List<RespValue>) value;
                return ">" + pElems.size();
            case VERBATIM_STRING:
                Object[] vs = (Object[]) value;
                byte[] vsData = (byte[]) vs[1];
                return "=" + vs[0] + ":" + new String(vsData, UTF_8);
            case BIG_NUMBER:
                return "(" + value;
            case BLOB_ERROR:
                byte[] errData = (byte[]) value;
                return "!" + new String(errData, UTF_8);
            default:
                return "unknown";
        }
    }

}
