/*
 * TransportParameterConsistencyChecker.java
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
 * Server-role callback enforcing RFC 9000 section 7.4.1's 0-RTT rule: a
 * server must not accept early data unless its <em>current</em> transport
 * parameters are at least as permissive as the ones remembered from the
 * connection that issued the resumption ticket -- shrinking a limit (a
 * smaller {@code initial_max_data}, say) between issuance and resumption
 * would let a 0-RTT sender exceed the server's real, current limits
 * before the server has had a chance to enforce anything.
 *
 * <p>{@link HandshakeEngine} carries the remembered parameters bytes
 * inside the ticket it issued (see {@link SessionTicket}) but has no idea
 * how to interpret QUIC transport parameters TLV encoding itself --
 * exactly the same opaque-bytes principle already used for
 * {@link HandshakeConfig#getLocalTransportParameters} and
 * {@link TlsEventSink#peerTransportParameters}. A caller not using this
 * engine for QUIC (or not offering 0-RTT at all) has nothing to
 * implement here; {@link HandshakeConfig} defaults to a permissive
 * checker that always returns true.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-7.4.1">RFC 9000 section 7.4.1</a>
 */
public interface TransportParameterConsistencyChecker {

    /**
     * Checks whether {@code current} is consistent with (at least as
     * permissive as) {@code remembered}.
     *
     * @param remembered the raw transport parameters bytes sealed into
     *                   the ticket when it was issued
     * @param current the server's raw transport parameters bytes now
     * @return true if 0-RTT may proceed
     */
    boolean isConsistent(byte[] remembered, byte[] current);

    /**
     * Returns the transport parameters the server sends, given the
     * client's. Lets a QUIC server tailor a parameter to the connection --
     * the {@code version_information} of RFC 9368 depends on the client's
     * own. Called once per ClientHello, before any server flight.
     *
     * @param local the server's configured transport parameters bytes
     * @param peer the client's transport parameters bytes, or null if it sent none
     * @return the bytes to send and to seal into any ticket
     */
    default byte[] localParametersFor(byte[] local, byte[] peer) {
        return local;
    }

    /**
     * Checks whether a resumption ticket may be honoured for this
     * connection at all. A QUIC server refuses a ticket issued for a
     * different QUIC version (RFC 9369 section 5), which costs the client
     * a full handshake but no more.
     *
     * @param remembered the transport parameters bytes sealed into the ticket
     * @param current the server's transport parameters bytes for this connection
     * @return true if the ticket may be used
     */
    default boolean acceptsTicketFrom(byte[] remembered, byte[] current) {
        return true;
    }

}
