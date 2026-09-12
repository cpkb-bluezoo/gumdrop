/*
 * ServerCredentialsResolver.java
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
 * SNI-based server credential dispatch (server role): the client's SNI
 * hostname in, the credentials to present out. Lets one
 * {@link HandshakeEngine} acceptor serve multiple hostnames from
 * different certificates, unlike {@link HandshakeConfig#getServerCredentials}'s
 * single fixed chain -- when set on a {@link HandshakeConfig}, this takes
 * priority over that fixed value with no fallback to it, so a resolver
 * that decides "no certificate for this name" by returning null means
 * exactly that, not "fall back to the default."
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface ServerCredentialsResolver {

    /**
     * Resolves the credentials to present for a client's SNI hostname.
     *
     * @param serverName the client's SNI hostname, or null if it sent none
     * @return the credentials to present, or null if none are available
     *         for this name
     */
    ServerCredentials resolve(String serverName);

}
