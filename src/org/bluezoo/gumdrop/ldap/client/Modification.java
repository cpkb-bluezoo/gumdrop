/*
 * Modification.java
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a modification to an LDAP entry attribute.
 * 
 * <p>Modifications are used with the {@link LDAPSession#modify} operation
 * to change attribute values in directory entries.
 * 
 * <p>Each modification specifies:
 * <ul>
 * <li>An operation type (add, delete, or replace)</li>
 * <li>An attribute name</li>
 * <li>Zero or more attribute values</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see LDAPSession#modify
 */
public class Modification {

    /**
     * Modification operation types.
     */
    public enum Operation {
        /** Add values to the attribute */
        ADD(0),
        /** Delete values from the attribute */
        DELETE(1),
        /** Replace all values of the attribute */
        REPLACE(2);

        private final int value;

        Operation(int value) {
            this.value = value;
        }

        /**
         * Returns the LDAP protocol value for this operation.
         * 
         * @return the protocol value
         */
        public int getValue() {
            return value;
        }
    }

    private final Operation operation;
    private final String attributeName;
    private final List<byte[]> values;

    /**
     * Creates a modification with no values.
     * 
     * <p>This is typically used for delete operations to remove
     * all values of an attribute.
     * 
     * @param operation the modification operation
     * @param attributeName the attribute name
     */
    public Modification(Operation operation, String attributeName) {
        this.operation = operation;
        this.attributeName = attributeName;
        this.values = new ArrayList<byte[]>();
    }

    /**
     * Creates a modification with binary values.
     * 
     * @param operation the modification operation
     * @param attributeName the attribute name
     * @param values the attribute values
     */
    public Modification(Operation operation, String attributeName, List<byte[]> values) {
        this.operation = operation;
        this.attributeName = attributeName;
        this.values = new ArrayList<byte[]>(values);
    }

    /**
     * Returns the modification operation.
     * 
     * @return the operation type
     */
    public Operation getOperation() {
        return operation;
    }

    /**
     * Returns the attribute name.
     * 
     * @return the attribute name
     */
    public String getAttributeName() {
        return attributeName;
    }

    /**
     * Returns the attribute values.
     * 
     * @return the values (may be empty)
     */
    public List<byte[]> getValues() {
        return values;
    }

    /**
     * Creates an ADD modification with a single string value.
     * 
     * @param attributeName the attribute name
     * @param value the value to add
     * @return the modification
     */
    public static Modification add(String attributeName, String value) {
        List<byte[]> values = new ArrayList<byte[]>();
        values.add(value.getBytes(StandardCharsets.UTF_8));
        return new Modification(Operation.ADD, attributeName, values);
    }

    /**
     * Creates a DELETE modification for all values of an attribute.
     * 
     * @param attributeName the attribute name
     * @return the modification
     */
    public static Modification delete(String attributeName) {
        return new Modification(Operation.DELETE, attributeName);
    }

    /**
     * Creates a DELETE modification for a specific string value.
     * 
     * @param attributeName the attribute name
     * @param value the value to delete
     * @return the modification
     */
    public static Modification delete(String attributeName, String value) {
        List<byte[]> values = new ArrayList<byte[]>();
        values.add(value.getBytes(StandardCharsets.UTF_8));
        return new Modification(Operation.DELETE, attributeName, values);
    }

    /**
     * Creates a REPLACE modification with a single string value.
     * 
     * @param attributeName the attribute name
     * @param value the new value
     * @return the modification
     */
    public static Modification replace(String attributeName, String value) {
        List<byte[]> values = new ArrayList<byte[]>();
        values.add(value.getBytes(StandardCharsets.UTF_8));
        return new Modification(Operation.REPLACE, attributeName, values);
    }

    /**
     * Creates a REPLACE modification with multiple string values.
     * 
     * @param attributeName the attribute name
     * @param stringValues the new values
     * @return the modification
     */
    public static Modification replace(String attributeName, List<String> stringValues) {
        List<byte[]> values = new ArrayList<byte[]>();
        for (String s : stringValues) {
            values.add(s.getBytes(StandardCharsets.UTF_8));
        }
        return new Modification(Operation.REPLACE, attributeName, values);
    }

}

