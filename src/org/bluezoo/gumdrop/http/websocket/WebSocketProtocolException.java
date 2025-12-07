/*
 * WebSocketProtocolException.java
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

package org.bluezoo.gumdrop.http.websocket;

import java.net.ProtocolException;

/**
 * Exception thrown when WebSocket protocol violations are detected.
 * This includes malformed frames, invalid handshakes, or other
 * protocol-level errors as defined in RFC 6455.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc6455">RFC 6455: The WebSocket Protocol</a>
 */
public class WebSocketProtocolException extends ProtocolException {

    /**
     * Constructs a WebSocketProtocolException with the specified detail message.
     *
     * @param message the detail message
     */
    public WebSocketProtocolException(String message) {
        super(message);
    }

    /**
     * Constructs a WebSocketProtocolException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public WebSocketProtocolException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
