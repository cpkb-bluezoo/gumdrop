/*
 * QuicDatagramPath.java
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
import java.net.SocketAddress;
import java.nio.ByteBuffer;

/**
 * A datagram path a {@link QuicEngine} sends and receives QUIC packets
 * over (issue #392) -- an unreliable, addressed datagram pipe, exactly
 * what RFC 9000 requires of a QUIC path, without requiring that pipe to
 * be a bound {@code DatagramChannel} kernel UDP socket.
 *
 * <p>{@link QuicTransportFactory}'s default client/server engines use a
 * {@code DatagramChannel}-backed implementation of this interface
 * internally. A caller supplying its own implementation instead -- e.g.
 * an RFC 9298 CONNECT-UDP client tunnelling this path's packets as HTTP
 * Datagrams, or an in-memory pipe for tests -- must feed received
 * packets to the engine via {@link QuicEngine#receivePathDatagram}; see
 * its own documentation for the thread-affinity requirement that method
 * itself takes care of.
 *
 * <p>Connection migration, path validation, and NAT rebinding are only
 * implemented against the default {@code DatagramChannel} path; a custom
 * path is not expected to support them in this first cut.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000">RFC 9000</a>
 */
public interface QuicDatagramPath {

    /**
     * Sends a packet to {@code address}.
     *
     * @param address the destination address
     * @param packet the packet bytes
     * @return the number of bytes sent, or 0 if the path is momentarily
     *         unable to accept it -- treated exactly like ordinary
     *         network loss (see {@link QuicEngine}'s class
     *         documentation), not retried by the caller
     * @throws IOException if sending fails
     */
    int send(SocketAddress address, ByteBuffer packet) throws IOException;

    /**
     * Returns the local address packets sent over this path are
     * considered to originate from -- used for QUIC connection state and
     * diagnostics, not to actually address anything (the path
     * implementation itself decides where bytes really go).
     *
     * @return the local address
     */
    SocketAddress getLocalAddress();

    /**
     * Returns whether this path is still usable.
     *
     * @return true if open
     */
    boolean isOpen();

    /**
     * Closes this path. Idempotent.
     *
     * @throws IOException if closing fails
     */
    void close() throws IOException;
}
