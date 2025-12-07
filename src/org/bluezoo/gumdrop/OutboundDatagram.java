/*
 * OutboundDatagram.java
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

package org.bluezoo.gumdrop;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * Holds an outbound datagram with its destination address.
 * Used by {@link DatagramServer} to queue datagrams for transmission.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class OutboundDatagram {

    final ByteBuffer data;
    final InetSocketAddress destination;

    /**
     * Creates an outbound datagram.
     *
     * @param data the datagram payload
     * @param destination the destination address
     */
    OutboundDatagram(ByteBuffer data, InetSocketAddress destination) {
        this.data = data;
        this.destination = destination;
    }

}

