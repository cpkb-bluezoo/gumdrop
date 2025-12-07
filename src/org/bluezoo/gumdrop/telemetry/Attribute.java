/*
 * Attribute.java
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

package org.bluezoo.gumdrop.telemetry;

/**
 * A key-value attribute for spans, events, or resources.
 * Supports string, boolean, long, and double value types.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class Attribute {

    /**
     * Attribute value types matching OTLP AnyValue.
     */
    public static final int TYPE_STRING = 1;
    public static final int TYPE_BOOL = 2;
    public static final int TYPE_INT = 3;
    public static final int TYPE_DOUBLE = 4;

    private final String key;
    private final int type;
    private final Object value;

    private Attribute(String key, int type, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null");
        }
        this.key = key;
        this.type = type;
        this.value = value;
    }

    /**
     * Creates a string attribute.
     */
    public static Attribute string(String key, String value) {
        return new Attribute(key, TYPE_STRING, value);
    }

    /**
     * Creates a boolean attribute.
     */
    public static Attribute bool(String key, boolean value) {
        return new Attribute(key, TYPE_BOOL, Boolean.valueOf(value));
    }

    /**
     * Creates an integer attribute.
     */
    public static Attribute integer(String key, long value) {
        return new Attribute(key, TYPE_INT, Long.valueOf(value));
    }

    /**
     * Creates a double attribute.
     */
    public static Attribute doubleValue(String key, double value) {
        return new Attribute(key, TYPE_DOUBLE, Double.valueOf(value));
    }

    /**
     * Returns the attribute key.
     */
    public String getKey() {
        return key;
    }

    /**
     * Returns the value type.
     */
    public int getType() {
        return type;
    }

    /**
     * Returns the string value.
     *
     * @throws IllegalStateException if the type is not TYPE_STRING
     */
    public String getStringValue() {
        if (type != TYPE_STRING) {
            throw new IllegalStateException("Not a string attribute");
        }
        return (String) value;
    }

    /**
     * Returns the boolean value.
     *
     * @throws IllegalStateException if the type is not TYPE_BOOL
     */
    public boolean getBoolValue() {
        if (type != TYPE_BOOL) {
            throw new IllegalStateException("Not a boolean attribute");
        }
        return ((Boolean) value).booleanValue();
    }

    /**
     * Returns the integer value.
     *
     * @throws IllegalStateException if the type is not TYPE_INT
     */
    public long getIntValue() {
        if (type != TYPE_INT) {
            throw new IllegalStateException("Not an integer attribute");
        }
        return ((Long) value).longValue();
    }

    /**
     * Returns the double value.
     *
     * @throws IllegalStateException if the type is not TYPE_DOUBLE
     */
    public double getDoubleValue() {
        if (type != TYPE_DOUBLE) {
            throw new IllegalStateException("Not a double attribute");
        }
        return ((Double) value).doubleValue();
    }

    /**
     * Returns the raw value object.
     * The type can be determined using {@link #getType()}.
     */
    public Object getValue() {
        return value;
    }

    @Override
    public String toString() {
        return key + "=" + value;
    }

}

