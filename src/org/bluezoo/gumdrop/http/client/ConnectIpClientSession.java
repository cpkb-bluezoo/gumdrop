/*
 * ConnectIpClientSession.java
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
import java.util.List;

import org.bluezoo.gumdrop.http.ConnectIpAddress;

/**
 * A live RFC 9484 CONNECT-IP tunnel, handed to {@link
 * ConnectIpEventHandler#opened} once the proxy accepts the request.
 *
 * <p>Named distinctly from {@code org.bluezoo.gumdrop.http.ConnectIpSession}
 * (the server-side accepted-tunnel object {@code ConnectIpRequestHandler}
 * hands to an {@code IpPacketHandler}) to avoid two same-named classes in
 * different packages -- despite the shared name being otherwise legal
 * Java, that reads as confusing in an import list or a cross-reference.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectIpEventHandler
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9484">RFC 9484</a>
 */
public interface ConnectIpClientSession {

    /**
     * Sends an IP packet to the proxy (RFC 9484 section 6: an HTTP
     * Datagram with Context ID 0).
     *
     * @param packet the full IP packet (from the IP Version field to
     *               the last payload byte); valid only during this call
     * @throws IOException if the tunnel is no longer open
     */
    void sendPacket(ByteBuffer packet) throws IOException;

    /**
     * Sends an {@code ADDRESS_REQUEST} capsule (RFC 9484 section 4.7.2),
     * asking the proxy to assign one or more addresses.
     *
     * @param requested the requested addresses -- each entry's Request
     *        ID must be non-zero and unique within the request
     * @throws IOException if the tunnel is no longer open
     */
    void sendAddressRequest(List<ConnectIpAddress> requested) throws IOException;

    /**
     * Closes this tunnel.
     */
    void close();
}
