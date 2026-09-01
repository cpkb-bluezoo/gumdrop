/*
 * ConnectUdpEventHandler.java
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

import java.nio.ByteBuffer;

/**
 * Receives events for a client-initiated RFC 9298 CONNECT-UDP tunnel.
 * Implement this interface to receive events, and pass an instance to a
 * transport's CONNECT-UDP entry point (e.g. {@code
 * HTTP3ClientHandler#connectUdp}).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectUdpSession
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298</a>
 */
public interface ConnectUdpEventHandler {

    /**
     * Called once the server accepts the CONNECT-UDP request.
     *
     * @param session the tunnel, for sending UDP payloads to the target
     */
    void opened(ConnectUdpSession session);

    /**
     * Called for each UDP payload received from the target.
     *
     * @param payload the UDP payload; valid only during this call
     */
    void datagramReceived(ByteBuffer payload);

    /**
     * Called when the tunnel closes normally (the underlying request
     * stream finished).
     */
    void closed();

    /**
     * Called when the CONNECT-UDP request is rejected (before {@link
     * #opened}), or when the tunnel fails after being accepted. Either
     * way, no further callbacks are invoked after this one.
     *
     * @param cause the failure
     */
    void error(Throwable cause);
}
