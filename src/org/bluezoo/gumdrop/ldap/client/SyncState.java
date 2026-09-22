/*
 * SyncState.java
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

package org.bluezoo.gumdrop.ldap.client;

/**
 * The {@code state} field of a syncStateValue control (RFC 4533 §2.3),
 * attached to a SearchResultEntry to say how this entry relates to the
 * client's last known state of the directory.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4533#section-2.3">RFC 4533 §2.3</a>
 */
public enum SyncState {

    /** The entry already existed and is unchanged; sent during a refresh to confirm it still matches. */
    PRESENT(0),

    /** The entry is new since the client's last cookie. */
    ADD(1),

    /** The entry existed before and has been modified since the client's last cookie. */
    MODIFY(2),

    /** The entry has been deleted since the client's last cookie. */
    DELETE(3);

    private final int value;

    SyncState(int value) {
        this.value = value;
    }

    /**
     * Returns the protocol value for this state.
     *
     * @return the ENUMERATED value (0-3)
     */
    public int getValue() {
        return value;
    }

    /**
     * Returns the SyncState for the given protocol value.
     *
     * @param value the protocol value
     * @return the sync state
     * @throws IllegalArgumentException if the value is invalid
     */
    public static SyncState fromValue(int value) {
        for (SyncState state : values()) {
            if (state.value == value) {
                return state;
            }
        }
        throw new IllegalArgumentException("Invalid sync state: " + value);
    }
}
