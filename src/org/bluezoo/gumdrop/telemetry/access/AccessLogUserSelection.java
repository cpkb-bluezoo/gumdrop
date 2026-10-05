/*
 * AccessLogUserSelection.java
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

package org.bluezoo.gumdrop.telemetry.access;

/**
 * Which authenticated identity to show in access log user columns.
 *
 * <p>Protocol identity comes from HTTP-layer authentication (for example
 * {@code Authorization} validated on the connection). Application identity
 * comes from servlet processing when present; it never replaces protocol
 * identity in storage, only in formatted output per this policy.
 */
public enum AccessLogUserSelection {

    PROTOCOL,
    APPLICATION,
    PROTOCOL_FIRST,
    APPLICATION_FIRST,
    /** ELFF only: emit both usernames in separate fields; CLF uses protocol-first. */
    BOTH
}
