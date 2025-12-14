/*
 * SearchScope.java
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

/**
 * LDAP search scope as defined in RFC 4511.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum SearchScope {

    /**
     * Search only the base object.
     */
    BASE(0),

    /**
     * Search the immediate children of the base object (one level).
     */
    ONE(1),

    /**
     * Search the base object and all its descendants (subtree).
     */
    SUBTREE(2);

    private final int value;

    SearchScope(int value) {
        this.value = value;
    }

    /**
     * Returns the protocol value for this scope.
     *
     * @return the scope value (0, 1, or 2)
     */
    public int getValue() {
        return value;
    }

    /**
     * Returns the SearchScope for the given protocol value.
     *
     * @param value the protocol value
     * @return the search scope
     * @throws IllegalArgumentException if the value is invalid
     */
    public static SearchScope fromValue(int value) {
        for (SearchScope scope : values()) {
            if (scope.value == value) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Invalid search scope: " + value);
    }
}

