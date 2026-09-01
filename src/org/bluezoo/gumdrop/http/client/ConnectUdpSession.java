/*
 * ConnectUdpSession.java
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

package org.bluezoo.gumdrop.http.client;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A live RFC 9298 (Proxying UDP in HTTP) CONNECT-UDP tunnel, handed to
 * {@link ConnectUdpEventHandler#opened} once the server accepts the
 * request.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectUdpEventHandler
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298</a>
 */
public interface ConnectUdpSession {

    /**
     * Sends a UDP payload to the tunnel's target.
     *
     * @param payload the UDP payload; valid only during this call
     * @throws IOException if the tunnel is no longer open
     */
    void sendDatagram(ByteBuffer payload) throws IOException;

    /**
     * Closes this tunnel.
     */
    void close();
}
