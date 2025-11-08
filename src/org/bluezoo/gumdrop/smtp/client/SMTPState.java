/*
 * SMTPState.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
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


