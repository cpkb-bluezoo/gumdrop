/*
 * IpPacketHandler.java
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

package org.bluezoo.gumdrop.http;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * The forwarding backend for an accepted RFC 9484 CONNECT-IP tunnel.
 *
 * <p>Gumdrop's core has no notion of a kernel network stack -- this
 * interface is the seam between the HTTP-level tunnel {@link
 * ConnectIpRequestHandler} accepts and whatever actually forwards the
 * decoded IP packets: a test echo, a userspace forwarder (e.g. a
 * NAT/routing table kept entirely in application code), or an
 * out-of-tree TUN or raw-socket adapter. Gumdrop deliberately does not
 * grow a native TUN dependency of its own; implement this interface to
 * supply one.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectIpRequestHandler
 * @see ConnectIpSession
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9484">RFC 9484</a>
 */
public interface IpPacketHandler {

    /**
     * Called once the tunnel is accepted.
     *
     * @param session the tunnel, for sending packets/capsules to the client
     */
    void opened(ConnectIpSession session);

    /**
     * Called for each IP packet received from the client (RFC 9484
     * section 6: an HTTP Datagram with Context ID 0).
     *
     * @param session the tunnel this packet arrived on
     * @param packet the full IP packet (from the IP Version field to
     *               the last payload byte); valid only during this call
     */
    void packetReceived(ConnectIpSession session, ByteBuffer packet);

    /**
     * Called when the client sends an {@code ADDRESS_REQUEST} capsule
     * (RFC 9484 section 4.7.2), asking to be assigned one or more
     * addresses. Respond with {@link ConnectIpSession#sendAddressAssign},
     * echoing the same Request IDs, once (if) the backend has assigned
     * them.
     *
     * @param session the tunnel the request arrived on
     * @param requested the requested addresses; each entry's {@code
     *        getAddress()}/{@code getPrefixLength()} is the client's
     *        hint (e.g. a previously-assigned address it wants renewed),
     *        which the backend may honour, replace, or ignore
     */
    void addressRequested(ConnectIpSession session, List<ConnectIpAddress> requested);

    /**
     * Called when the tunnel closes normally (the underlying request
     * stream finished).
     *
     * @param session the tunnel that closed
     */
    void closed(ConnectIpSession session);

    /**
     * Called when the tunnel fails due to a transport or protocol-level
     * error. This is the final callback for {@code session}; no more
     * events will be delivered for it.
     *
     * @param session the tunnel that failed
     * @param cause the error
     */
    void failed(ConnectIpSession session, Exception cause);
}
