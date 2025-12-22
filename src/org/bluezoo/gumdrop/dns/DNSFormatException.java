/*
 * DNSFormatException.java
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

package org.bluezoo.gumdrop.dns;

/**
 * Exception thrown when a DNS message cannot be parsed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSFormatException extends Exception {

    /**
     * Creates a new DNS format exception.
     *
     * @param message the error message
     */
    public DNSFormatException(String message) {
        super(message);
    }

    /**
     * Creates a new DNS format exception with a cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public DNSFormatException(String message, Throwable cause) {
        super(message, cause);
    }

}
