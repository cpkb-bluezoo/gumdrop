/*
 * WebSocketProtocolException.java
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
