/*
 * ConnectIpEventHandler.java
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
import java.util.List;

import org.bluezoo.gumdrop.http.ConnectIpAddress;
import org.bluezoo.gumdrop.http.ConnectIpRoute;

/**
 * Receives events for a client-initiated RFC 9484 CONNECT-IP tunnel.
 * Implement this interface to receive events, and pass an instance to a
 * transport's CONNECT-IP entry point (e.g. {@code
 * HTTP3ClientHandler#connectIp}), or use {@link ConnectIpClient} for
 * automatic transport negotiation.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectIpClientSession
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9484">RFC 9484</a>
 */
public interface ConnectIpEventHandler {

    /**
     * Called once the proxy accepts the CONNECT-IP request.
     *
     * @param session the tunnel, for sending IP packets/capsules to the proxy
     */
    void opened(ConnectIpClientSession session);

    /**
     * Called for each IP packet received from the proxy (RFC 9484
     * section 6: an HTTP Datagram with Context ID 0).
     *
     * @param packet the full IP packet (from the IP Version field to
     *               the last payload byte); valid only during this call
     */
    void packetReceived(ByteBuffer packet);

    /**
     * Called when the proxy sends an {@code ADDRESS_ASSIGN} capsule (RFC
     * 9484 section 4.7.1), assigning one or more addresses to this
     * client.
     *
     * @param assignments the assigned addresses
     */
    void addressAssigned(List<ConnectIpAddress> assignments);

    /**
     * Called when the proxy sends a {@code ROUTE_ADVERTISEMENT} capsule
     * (RFC 9484 section 4.7.3), advertising the IP address ranges
     * reachable through this tunnel.
     *
     * @param routes the advertised ranges
     */
    void routeAdvertised(List<ConnectIpRoute> routes);

    /**
     * Called when the tunnel closes normally (the underlying request
     * stream finished).
     */
    void closed();

    /**
     * Called when the CONNECT-IP request is rejected (before {@link
     * #opened}), or when the tunnel fails after being accepted. Either
     * way, no further callbacks are invoked after this one.
     *
     * @param cause the failure
     */
    void error(Throwable cause);
}
