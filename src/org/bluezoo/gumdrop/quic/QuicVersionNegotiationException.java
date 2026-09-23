/*
 * QuicVersionNegotiationException.java
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


package org.bluezoo.gumdrop.quic;

import java.io.IOException;

/**
 * Signalled when a server answers a client's first flight with a Version
 * Negotiation packet (RFC 9000 section 6.2) that lists no version this
 * client supports, so the connection attempt cannot succeed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QuicVersionNegotiationException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a version negotiation exception.
     */
    public QuicVersionNegotiationException() {
        super("Server does not support a compatible QUIC version");
    }
}
