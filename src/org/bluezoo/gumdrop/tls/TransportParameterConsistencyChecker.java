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

}
