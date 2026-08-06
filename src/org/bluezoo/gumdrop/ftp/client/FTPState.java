/*
 * FTPState.java
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

package org.bluezoo.gumdrop.ftp.client;

/**
 * FTP client connection state enumeration.
 *
 * <p>These states track the internal protocol state of the FTP client
 * control connection. The stage-based interfaces ({@code ClientLoginState},
 * {@code ClientAuthenticatedState}, etc.) provide a type-safe view of what
 * operations are valid at each state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc959">RFC 959</a> (FTP)
 */
enum FTPState {

    /** Not connected to any server. */
    DISCONNECTED,

    /** Establishing TCP connection to server. */
    CONNECTING,

    /** Connected, greeting received, not yet authenticated. RFC 959 §4.1.1 */
    CONNECTED,

    /** USER command sent, waiting for response. RFC 959 §4.1.1 */
    USER_SENT,

    /** PASS command sent, waiting for response. RFC 959 §4.1.1 */
    PASS_SENT,

    /** ACCT command sent, waiting for response. RFC 959 §4.1.1 */
    ACCT_SENT,

    /** Authenticated, ready for file/directory commands. */
    AUTHENTICATED,

    /** CWD command sent, waiting for response. RFC 959 §4.1.1 */
    CWD_SENT,

    /** CDUP command sent, waiting for response. RFC 959 §4.1.1 */
    CDUP_SENT,

    /** PWD command sent, waiting for response. RFC 959 §4.1.3 */
    PWD_SENT,

    /** TYPE command sent, waiting for response. RFC 959 §4.1.2 */
    TYPE_SENT,

    /** STRU command sent, waiting for response. RFC 959 §4.1.2 */
    STRU_SENT,

    /** MODE command sent, waiting for response. RFC 959 §4.1.2 */
    MODE_SENT,

    /** DELE command sent, waiting for response. RFC 959 §4.1.3 */
    DELE_SENT,

    /** RMD command sent, waiting for response. RFC 959 §4.1.3 */
    RMD_SENT,

    /** MKD command sent, waiting for response. RFC 959 §4.1.3 */
    MKD_SENT,

    /** AUTH TLS command sent, waiting for response. RFC 4217 */
    AUTH_TLS_SENT,

    /** QUIT command sent, waiting for response. RFC 959 §4.1.1 */
    QUIT_SENT,

    /** Protocol error or connection failure occurred. */
    ERROR,

    /** Connection closed normally. */
    CLOSED
}
