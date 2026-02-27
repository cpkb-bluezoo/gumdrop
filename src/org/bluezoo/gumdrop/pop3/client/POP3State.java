/*
 * POP3State.java
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

package org.bluezoo.gumdrop.pop3.client;

/**
 * POP3 client connection state enumeration.
 *
 * <p>These states track the internal protocol state of the POP3 client
 * connection. The stage-based interfaces ({@link
 * org.bluezoo.gumdrop.pop3.client.handler.ClientAuthorizationState},
 * {@link org.bluezoo.gumdrop.pop3.client.handler.ClientTransactionState},
 * etc.) provide a type-safe view of what operations are valid at each state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
enum POP3State {

    /** Not connected to any server. */
    DISCONNECTED,

    /** Establishing TCP connection to server. */
    CONNECTING,

    /** TCP connected, waiting for server greeting. */
    CONNECTED,

    /** AUTHORIZATION state - greeting received, can authenticate. */
    AUTHORIZATION,

    /** CAPA command sent, waiting for response. */
    CAPA_SENT,

    /** Receiving multi-line CAPA response. */
    CAPA_DATA,

    /** USER command sent, waiting for response. */
    USER_SENT,

    /** PASS command sent, waiting for response. */
    PASS_SENT,

    /** APOP command sent, waiting for response. */
    APOP_SENT,

    /** STLS command sent, waiting for response. */
    STLS_SENT,

    /** AUTH command sent, waiting for response. */
    AUTH_SENT,

    /** AUTH abort (*) sent, waiting for response. */
    AUTH_ABORT_SENT,

    /** TRANSACTION state - authenticated, can access mailbox. */
    TRANSACTION,

    /** STAT command sent, waiting for response. */
    STAT_SENT,

    /** LIST command sent, waiting for response. */
    LIST_SENT,

    /** Receiving multi-line LIST response. */
    LIST_DATA,

    /** UIDL command sent, waiting for response. */
    UIDL_SENT,

    /** Receiving multi-line UIDL response. */
    UIDL_DATA,

    /** RETR command sent, waiting for response. */
    RETR_SENT,

    /** Receiving RETR message content. */
    RETR_DATA,

    /** TOP command sent, waiting for response. */
    TOP_SENT,

    /** Receiving TOP content. */
    TOP_DATA,

    /** DELE command sent, waiting for response. */
    DELE_SENT,

    /** RSET command sent, waiting for response. */
    RSET_SENT,

    /** NOOP command sent, waiting for response. */
    NOOP_SENT,

    /** QUIT command sent, waiting for response. */
    QUIT_SENT,

    /** Protocol error or connection failure occurred. */
    ERROR,

    /** Connection closed normally. */
    CLOSED
}
