/*
 * ConnectIpPolicy.java
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

/**
 * Decides which RFC 9484 CONNECT-IP requests {@link
 * ConnectIpRequestHandler} may accept, before any {@link IpPacketHandler}
 * ever sees the request.
 *
 * <p>There is deliberately no permissive default implementation anywhere
 * in gumdrop: a CONNECT-IP tunnel forwards arbitrary IP traffic, so an
 * application must explicitly decide what {@link ConnectIpTarget}
 * scope-limiting values it is willing to serve (most deployments accept
 * only the wildcard/unspecified target, per RFC 9484 section 4.6, and
 * rely on the {@link IpPacketHandler}'s own forwarding backend for
 * finer-grained access control once packets actually arrive).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ConnectIpRequestHandler
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9484">RFC 9484</a>
 */
public interface ConnectIpPolicy {

    /**
     * Returns whether a CONNECT-IP request scoped to {@code target} may
     * be accepted.
     *
     * @param target the request's parsed target/ipproto scope
     * @return true if the request should be accepted
     */
    boolean isRequestAllowed(ConnectIpTarget target);
}
