/*
 * SearchResultEntry.java
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

package org.bluezoo.gumdrop.ldap.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents a single entry from an LDAP search result.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SearchResultEntry {

    private final String dn;
    private final Map<String, List<byte[]>> attributes;

    /**
     * Creates a new search result entry.
     *
     * @param dn the distinguished name
     * @param attributes the entry attributes (name to list of values)
     */
    public SearchResultEntry(String dn, Map<String, List<byte[]>> attributes) {
        this.dn = dn;
        this.attributes = new LinkedHashMap<String, List<byte[]>>();
        for (Map.Entry<String, List<byte[]>> entry : attributes.entrySet()) {
            this.attributes.put(entry.getKey().toLowerCase(),
                    Collections.unmodifiableList(new ArrayList<byte[]>(entry.getValue())));
        }
    }

    /**
     * Returns the distinguished name of this entry.
     *
     * @return the DN
     */
    public String getDN() {
        return dn;
    }

    /**
     * Returns the names of all attributes in this entry.
     *
     * @return unmodifiable set of attribute names
     */
    public Set<String> getAttributeNames() {
        return Collections.unmodifiableSet(attributes.keySet());
    }

    /**
     * Returns whether this entry has the specified attribute.
     *
     * @param name the attribute name (case-insensitive)
     * @return true if the attribute exists
     */
    public boolean hasAttribute(String name) {
        return attributes.containsKey(name.toLowerCase());
    }

    /**
     * Returns all values for the specified attribute.
     *
     * @param name the attribute name (case-insensitive)
     * @return list of values as byte arrays, or empty list if not present
     */
    public List<byte[]> getAttributeValues(String name) {
        List<byte[]> values = attributes.get(name.toLowerCase());
        return values != null ? values : Collections.<byte[]>emptyList();
    }

    /**
     * Returns all string values for the specified attribute.
     *
     * @param name the attribute name (case-insensitive)
     * @return list of values as strings, or empty list if not present
     */
    public List<String> getAttributeStringValues(String name) {
        List<byte[]> values = getAttributeValues(name);
        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> strings = new ArrayList<String>(values.size());
        for (byte[] value : values) {
            strings.add(new String(value, java.nio.charset.StandardCharsets.UTF_8));
        }
        return strings;
    }

    /**
     * Returns the first value for the specified attribute.
     *
     * @param name the attribute name (case-insensitive)
     * @return the first value as a byte array, or null if not present
     */
    public byte[] getAttributeValue(String name) {
        List<byte[]> values = getAttributeValues(name);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * Returns the first string value for the specified attribute.
     *
     * @param name the attribute name (case-insensitive)
     * @return the first value as a string, or null if not present
     */
    public String getAttributeStringValue(String name) {
        byte[] value = getAttributeValue(name);
        return value != null ? new String(value, java.nio.charset.StandardCharsets.UTF_8) : null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("dn: ").append(dn).append("\n");
        for (Map.Entry<String, List<byte[]>> entry : attributes.entrySet()) {
            String name = entry.getKey();
            for (byte[] value : entry.getValue()) {
                sb.append(name).append(": ");
                // Try to display as string if printable
                boolean printable = true;
                for (byte b : value) {
                    if (b < 0x20 && b != '\t' && b != '\n' && b != '\r') {
                        printable = false;
                        break;
                    }
                }
                if (printable) {
                    sb.append(new String(value, java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    sb.append("[").append(value.length).append(" bytes]");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}

