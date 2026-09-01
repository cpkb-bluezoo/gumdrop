/*
 * ConnectUdpPolicy.java
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

import java.net.InetAddress;

/**
 * Decides which UDP targets a {@link ConnectUdpRequestHandler} is
 * allowed to relay to (RFC 9298).
 *
 * <p>An RFC 9298 CONNECT-UDP relay is, by design, a server that forwards
 * UDP datagrams to an address named by the client -- an open relay
 * unless something checks that address first. There is deliberately no
 * permissive default implementation of this interface anywhere in
 * gumdrop; a deployment must supply one, and decide for itself what
 * "allowed" means (e.g. rejecting loopback/link-local/private addresses
 * to prevent the relay being used to reach internal services, or an
 * explicit destination allowlist).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9298">RFC 9298</a>
 */
public interface ConnectUdpPolicy {

    /**
     * Returns whether relaying to {@code address:port} is allowed.
     * Called once per request, after DNS resolution (if the client's
     * requested target was a hostname), on the request's own connection
     * thread.
     *
     * @param address the resolved target address
     * @param port the target port
     * @return true if relaying to this target is allowed
     */
    boolean isTargetAllowed(InetAddress address, int port);
}
