/*
 * SMTPException.java
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


