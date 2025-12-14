/*
 * ProtobufParseException.java
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

package org.bluezoo.gumdrop.telemetry.protobuf;

/**
 * Exception thrown when protobuf parsing fails.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ProtobufParseException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new parse exception with the given message.
     *
     * @param message the error message
     */
    public ProtobufParseException(String message) {
        super(message);
    }

    /**
     * Creates a new parse exception with the given message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public ProtobufParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

