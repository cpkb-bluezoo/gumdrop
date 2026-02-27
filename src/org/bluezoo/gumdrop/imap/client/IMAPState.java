/*
 * IMAPState.java
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

package org.bluezoo.gumdrop.imap.client;

/**
 * Internal state of the IMAP client protocol handler.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
enum IMAPState {

    DISCONNECTED,
    CONNECTING,

    NOT_AUTHENTICATED,
    CAPABILITY_SENT,
    LOGIN_SENT,
    AUTHENTICATE_SENT,
    AUTH_ABORT_SENT,
    STARTTLS_SENT,

    AUTHENTICATED,
    SELECT_SENT,
    EXAMINE_SENT,
    CREATE_SENT,
    DELETE_SENT,
    RENAME_SENT,
    SUBSCRIBE_SENT,
    UNSUBSCRIBE_SENT,
    LIST_SENT,
    LSUB_SENT,
    STATUS_SENT,
    NAMESPACE_SENT,
    APPEND_SENT,
    APPEND_DATA,
    IDLE_SENT,
    IDLE_ACTIVE,
    NOOP_SENT,

    SELECTED,
    CLOSE_SENT,
    UNSELECT_SENT,
    EXPUNGE_SENT,
    SEARCH_SENT,
    FETCH_SENT,
    FETCH_LITERAL,
    STORE_SENT,
    COPY_SENT,
    MOVE_SENT,

    LOGOUT_SENT,
    ERROR,
    CLOSED
}
