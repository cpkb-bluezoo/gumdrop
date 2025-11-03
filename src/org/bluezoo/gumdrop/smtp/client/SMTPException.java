/*
 * SMTPException.java
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
 * Exception thrown for SMTP client operations.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPException extends Exception {
    
    private final SMTPResponse response;
    
    /**
     * Creates an SMTP exception with message only.
     * 
     * @param message error description
     */
    public SMTPException(String message) {
        super(message);
        this.response = null;
    }
    
    /**
     * Creates an SMTP exception with message and cause.
     * 
     * @param message error description
     * @param cause underlying cause
     */
    public SMTPException(String message, Throwable cause) {
        super(message, cause);
        this.response = null;
    }
    
    /**
     * Creates an SMTP exception with server response.
     * 
     * @param message error description
     * @param response server response that caused the error
     */
    public SMTPException(String message, SMTPResponse response) {
        super(message + ": " + response);
        this.response = response;
    }
    
    /**
     * Gets the server response associated with this exception.
     * 
     * @return server response, or null if not available
     */
    public SMTPResponse getResponse() {
        return response;
    }
}

