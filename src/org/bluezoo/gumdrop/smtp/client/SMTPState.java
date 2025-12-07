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
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public enum SMTPState {
    
    /** Not connected to any server. */
    DISCONNECTED,
    
    /** Establishing TCP connection to server. */
    CONNECTING,
    
    /** Connected, waiting for greeting or ready for commands. */
    CONNECTED,
    
    /** HELO command sent, waiting for response. */
    HELO_SENT,
    
    /** EHLO command sent, waiting for response. */
    EHLO_SENT,
    
    /** MAIL FROM command sent, waiting for response. */
    MAIL_FROM_SENT,
    
    /** RCPT TO command sent, waiting for response. */
    RCPT_TO_SENT,
    
    /** DATA command sent, waiting for 354 response. */
    DATA_COMMAND_SENT,
    
    /** In data mode, can send message content. */
    DATA_MODE,
    
    /** End of data sent, waiting for message acceptance. */
    DATA_END_SENT,
    
    /** RSET command sent, waiting for response. */
    RSET_SENT,
    
    /** QUIT command sent, waiting for response. */
    QUIT_SENT,
    
    /** STARTTLS command sent, waiting for response. */
    STARTTLS_SENT,
    
    /** Protocol error or connection failure occurred. */
    ERROR,
    
    /** Connection closed normally. */
    CLOSED
}


