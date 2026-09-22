/*
 * SyncRequestMode.java
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
 * The {@code mode} field of a syncRequestValue (RFC 4533 §2.2): whether
 * the client wants a one-shot refresh, or a refresh followed by an
 * ongoing persistent search that keeps delivering changes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4533#section-2.2">RFC 4533 §2.2</a>
 */
public enum SyncRequestMode {

    /**
     * The server performs one refresh (delivering the current content
     * matching the search, relative to {@code cookie} if given) and then
     * sends SearchResultDone — this is a single incremental sync, not an
     * ongoing subscription.
     */
    REFRESH_ONLY(1),

    /**
     * The server performs the same refresh as {@link #REFRESH_ONLY}, but
     * never sends SearchResultDone afterwards: it keeps the search open
     * and continues delivering entries/syncInfoValue messages as the
     * directory changes, until the client abandons the search.
     */
    REFRESH_AND_PERSIST(3);

    private final int value;

    SyncRequestMode(int value) {
        this.value = value;
    }

    /**
     * Returns the protocol value for this mode.
     *
     * @return the ENUMERATED value (1 or 3 — RFC 4533 §2.2 leaves 0 and 2 unused)
     */
    public int getValue() {
        return value;
    }

    /**
     * Returns the SyncRequestMode for the given protocol value.
     *
     * @param value the protocol value
     * @return the sync request mode
     * @throws IllegalArgumentException if the value is invalid
     */
    public static SyncRequestMode fromValue(int value) {
        for (SyncRequestMode mode : values()) {
            if (mode.value == value) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Invalid sync request mode: " + value);
    }
}
