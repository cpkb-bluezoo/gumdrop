/*
 * SMTPState.java
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

package org.bluezoo.gumdrop.smtp.client;

/**
 * SMTP client connection state enumeration.
 *
 * <p>These states track the internal protocol state of the SMTP client
 * connection. The stage-based interfaces ({@link ClientHelloState},
 * {@link ClientSession}, etc.) provide a type-safe view of what operations
 * are valid at each state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5321">RFC 5321</a> (SMTP)
 */
enum SMTPState {
    
    /** Not connected to any server. */
    DISCONNECTED,
    
    /** Establishing TCP connection to server. RFC 5321 §3.1 */
    CONNECTING,
    
    /** Connected and ready for commands. RFC 5321 §4.2 */
    CONNECTED,
    
    /** HELO command sent, waiting for response. RFC 5321 §4.1.1.1 */
    HELO_SENT,
    
    /** EHLO command sent, waiting for response. RFC 5321 §4.1.1.1 */
    EHLO_SENT,
    
    /** STARTTLS command sent, waiting for response. RFC 3207 */
    STARTTLS_SENT,
    
    /** AUTH command sent, waiting for response. RFC 4954 */
    AUTH_SENT,
    
    /** AUTH abort (*) sent, waiting for response. */
    AUTH_ABORT_SENT,
    
    /** MAIL FROM command sent, waiting for response. RFC 5321 §4.1.1.2 */
    MAIL_FROM_SENT,
    
    /** MAIL FROM accepted, can add recipients. RFC 5321 §4.1.1.2 */
    MAIL_FROM_ACCEPTED,
    
    /** RCPT TO command sent, waiting for response. RFC 5321 §4.1.1.3 */
    RCPT_TO_SENT,
    
    /** At least one RCPT TO accepted, can add more or send DATA. RFC 5321 §4.1.1.3 */
    RCPT_TO_ACCEPTED,
    
    /** DATA command sent, waiting for 354 response. RFC 5321 §4.1.1.4 */
    DATA_COMMAND_SENT,
    
    /** In data mode, can send message content. RFC 5321 §4.1.1.4 */
    DATA_MODE,
    
    /** End of data sent, waiting for message acceptance. */
    DATA_END_SENT,
    
    /** RSET command sent, waiting for response. RFC 5321 §4.1.1.5 */
    RSET_SENT,
    
    /** VRFY command sent, waiting for response. RFC 5321 §4.1.1.6 */
    VRFY_SENT,

    /** EXPN command sent, waiting for response. RFC 5321 §4.1.1.7 */
    EXPN_SENT,

    /** QUIT command sent, waiting for response. RFC 5321 §4.1.1.10 */
    QUIT_SENT,
    
    /** Protocol error or connection failure occurred. */
    ERROR,
    
    /** Connection closed normally. */
    CLOSED
}
