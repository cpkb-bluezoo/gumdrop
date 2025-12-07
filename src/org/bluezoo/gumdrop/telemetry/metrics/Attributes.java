/*
 * Attributes.java
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

package org.bluezoo.gumdrop.telemetry.metrics;

import org.bluezoo.gumdrop.telemetry.Attribute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * An immutable collection of attributes for metric measurements.
 * Attributes are key-value pairs that identify a specific time series.
 *
 * <p>Example usage:
 * <pre>
 * Attributes attrs = Attributes.of(
 *     "http.method", "GET",
 *     "http.status_code", 200
 * );
 * counter.add(1, attrs);
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Attributes {

    private static final Attributes EMPTY = new Attributes(Collections.emptyList());

    /**
     * Comparator for sorting attributes by key.
     */
    private static final Comparator<Attribute> ATTRIBUTE_COMPARATOR = new Comparator<Attribute>() {
        @Override
        public int compare(Attribute a, Attribute b) {
            return a.getKey().compareTo(b.getKey());
        }
    };

    private final List<Attribute> attributes;
    private final int hashCode;

    private Attributes(List<Attribute> attributes) {
        this.attributes = attributes;
        this.hashCode = computeHashCode();
    }

    /**
     * Returns an empty attributes instance.
     */
    public static Attributes empty() {
        return EMPTY;
    }

    /**
     * Creates attributes from key-value pairs.
     * Keys must be strings; values can be String, Long, Double, or Boolean.
     *
     * @param keyValuePairs alternating keys and values
     * @return the attributes
     */
    public static Attributes of(Object... keyValuePairs) {
        if (keyValuePairs == null || keyValuePairs.length == 0) {
            return EMPTY;
        }
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Key-value pairs must have even length");
        }

        List<Attribute> attrs = new ArrayList<>(keyValuePairs.length / 2);
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            String key = String.valueOf(keyValuePairs[i]);
            Object value = keyValuePairs[i + 1];

            if (value instanceof String) {
                attrs.add(Attribute.string(key, (String) value));
            } else if (value instanceof Long) {
                attrs.add(Attribute.integer(key, (Long) value));
            } else if (value instanceof Integer) {
                attrs.add(Attribute.integer(key, ((Integer) value).longValue()));
            } else if (value instanceof Double) {
                attrs.add(Attribute.doubleValue(key, (Double) value));
            } else if (value instanceof Float) {
                attrs.add(Attribute.doubleValue(key, ((Float) value).doubleValue()));
            } else if (value instanceof Boolean) {
                attrs.add(Attribute.bool(key, (Boolean) value));
            } else if (value != null) {
                attrs.add(Attribute.string(key, value.toString()));
            }
        }

        // Sort by key for consistent hashing and comparison
        Collections.sort(attrs, ATTRIBUTE_COMPARATOR);
        return new Attributes(Collections.unmodifiableList(attrs));
    }

    /**
     * Creates attributes from a list of Attribute objects.
     */
    public static Attributes of(List<Attribute> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return EMPTY;
        }
        List<Attribute> sorted = new ArrayList<>(attributes);
        Collections.sort(sorted, ATTRIBUTE_COMPARATOR);
        return new Attributes(Collections.unmodifiableList(sorted));
    }

    /**
     * Returns the list of attributes.
     */
    public List<Attribute> asList() {
        return attributes;
    }

    /**
     * Returns true if this attributes collection is empty.
     */
    public boolean isEmpty() {
        return attributes.isEmpty();
    }

    /**
     * Returns the number of attributes.
     */
    public int size() {
        return attributes.size();
    }

    private int computeHashCode() {
        int result = 1;
        for (Attribute attr : attributes) {
            result = 31 * result + attr.getKey().hashCode();
            Object value = attr.getValue();
            result = 31 * result + (value != null ? value.hashCode() : 0);
        }
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Attributes other = (Attributes) obj;
        if (hashCode != other.hashCode) return false;
        if (attributes.size() != other.attributes.size()) return false;

        for (int i = 0; i < attributes.size(); i++) {
            Attribute a = attributes.get(i);
            Attribute b = other.attributes.get(i);
            if (!a.getKey().equals(b.getKey())) return false;
            if (!Objects.equals(a.getValue(), b.getValue())) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        if (attributes.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < attributes.size(); i++) {
            if (i > 0) sb.append(", ");
            Attribute attr = attributes.get(i);
            sb.append(attr.getKey()).append("=").append(attr.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

}

