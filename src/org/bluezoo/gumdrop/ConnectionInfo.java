/*
 * ConnectionInfo.java
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

/**
 * Provides information about an established connection.
 * 
 * <p>This interface is passed to {@link ClientHandler#onConnected} to
 * provide details about the connection, including network addresses
 * and TLS information if the connection is secure.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ClientHandler#onConnected
 * @see TLSInfo
 */
public interface ConnectionInfo {

    /**
     * Returns the local socket address.
     * 
     * <p>This includes the local IP address and the ephemeral port
     * assigned by the operating system for this connection.
     * 
     * @return the local socket address
     */
    InetSocketAddress getLocalAddress();

    /**
     * Returns the remote socket address.
     * 
     * <p>This is the address and port of the server we connected to.
     * For most connections this matches the address used to create
     * the client, but may differ if DNS returned multiple addresses
     * and one was selected.
     * 
     * @return the remote socket address
     */
    InetSocketAddress getRemoteAddress();

    /**
     * Returns whether the connection is secured with TLS.
     * 
     * <p>For connections using implicit TLS (LDAPS, SMTPS, HTTPS),
     * this will be true from the start. For connections that upgrade
     * via STARTTLS, this will be true after the upgrade completes.
     * 
     * @return true if the connection is using TLS
     */
    boolean isSecure();

    /**
     * Returns TLS information for secure connections.
     * 
     * <p>This provides details about the TLS session including
     * the protocol version, cipher suite, and certificates.
     * 
     * @return TLS information, or null if the connection is not secure
     */
    TLSInfo getTLSInfo();

}

