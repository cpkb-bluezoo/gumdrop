/*
 * ConnectIpSession.java
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
 * A live RFC 9484 CONNECT-IP tunnel, handed to an {@link
 * IpPacketHandler} once {@link ConnectIpRequestHandler} accepts the
 * request. Wraps the underlying {@link HTTPResponseState} with the
 * typed send operations a forwarding backend needs: IP packets (Context
 * ID 0, RFC 9484 section 6) and the two server-to-client capsule types
 * (RFC 9484 section 4.7.1/4.7.3) -- {@code ADDRESS_REQUEST} is
 * client-to-server and arrives via {@link
 * IpPacketHandler#addressRequested} instead.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see IpPacketHandler
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9484">RFC 9484</a>
 */
public final class ConnectIpSession {

    private final HTTPResponseState state;

    ConnectIpSession(HTTPResponseState state) {
        this.state = state;
    }

    /**
     * Sends an IP packet to the client (RFC 9484 section 6: an HTTP
     * Datagram with Context ID 0, the packet as its payload).
     *
     * @param packet the full IP packet (from the IP Version field to
     *               the last payload byte); copied
     * @return true if the packet was queued
     */
    public boolean sendPacket(ByteBuffer packet) {
        return state.sendDatagram(HttpDatagramContext.REGISTERED_CONTEXT_ID, packet);
    }

    /**
     * Sends an {@code ADDRESS_ASSIGN} capsule (RFC 9484 section 4.7.1),
     * assigning one or more addresses to the client.
     *
     * @param assignments the addresses being assigned
     * @return true if the capsule was queued
     */
    public boolean sendAddressAssign(List<ConnectIpAddress> assignments) {
        return state.sendCapsule(ConnectIpAddress.TYPE_ADDRESS_ASSIGN, ConnectIpAddress.encodeList(assignments));
    }

    /**
     * Sends a {@code ROUTE_ADVERTISEMENT} capsule (RFC 9484 section
     * 4.7.3), advertising the IP address ranges reachable through this
     * tunnel.
     *
     * @param routes the advertised ranges, ordered and non-overlapping
     * @return true if the capsule was queued
     */
    public boolean sendRouteAdvertisement(List<ConnectIpRoute> routes) {
        return state.sendCapsule(ConnectIpRoute.TYPE_ROUTE_ADVERTISEMENT, ConnectIpRoute.encodeList(routes));
    }

    /**
     * Closes this tunnel.
     */
    public void close() {
        state.complete();
    }
}
