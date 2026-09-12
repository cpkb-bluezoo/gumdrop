/*
 * HandshakeFormatException.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.tls;

/**
 * A handshake message (or one of its extensions) is malformed: truncated,
 * an out-of-range length, or otherwise not a well-formed encoding of its
 * RFC 8446 structure. Always caused by peer input, never a programming
 * error -- {@link HandshakeEngine} catches this at its
 * {@link HandshakeEngine#processMessage} boundary and reports it via
 * {@link TlsEventSink#protocolError} rather than letting it propagate.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class HandshakeFormatException extends Exception {

    private static final long serialVersionUID = 1L;

    HandshakeFormatException(String message) {
        super(message);
    }

}
