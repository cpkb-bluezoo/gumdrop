/*
 * DerefAliases.java
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
 * LDAP alias dereferencing policy as defined in RFC 4511.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum DerefAliases {

    /**
     * Never dereference aliases.
     */
    NEVER(0),

    /**
     * Dereference aliases when searching subordinates of the base object.
     */
    IN_SEARCHING(1),

    /**
     * Dereference aliases when finding the base object.
     */
    FINDING_BASE_OBJ(2),

    /**
     * Always dereference aliases.
     */
    ALWAYS(3);

    private final int value;

    DerefAliases(int value) {
        this.value = value;
    }

    /**
     * Returns the protocol value for this policy.
     *
     * @return the deref value (0-3)
     */
    public int getValue() {
        return value;
    }

    /**
     * Returns the DerefAliases for the given protocol value.
     *
     * @param value the protocol value
     * @return the deref policy
     * @throws IllegalArgumentException if the value is invalid
     */
    public static DerefAliases fromValue(int value) {
        for (DerefAliases deref : values()) {
            if (deref.value == value) {
                return deref;
            }
        }
        throw new IllegalArgumentException("Invalid deref value: " + value);
    }
}

