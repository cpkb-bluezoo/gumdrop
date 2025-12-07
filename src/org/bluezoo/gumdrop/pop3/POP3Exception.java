/*
 * POP3Exception.java
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

package org.bluezoo.gumdrop.pop3;

import java.io.IOException;

/**
 * Exception thrown for POP3 protocol errors.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3Exception extends IOException {

    /**
     * Creates a new POP3 exception.
     * 
     * @param message the error message
     */
    public POP3Exception(String message) {
        super(message);
    }

    /**
     * Creates a new POP3 exception with a cause.
     * 
     * @param message the error message
     * @param cause the underlying cause
     */
    public POP3Exception(String message, Throwable cause) {
        super(message, cause);
    }

}

