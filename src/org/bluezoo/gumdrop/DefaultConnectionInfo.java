/*
 * DefaultConnectionInfo.java
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
import java.net.SocketAddress;

/**
 * Default implementation of ConnectionInfo.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultConnectionInfo implements ConnectionInfo {

    private final InetSocketAddress localAddress;
    private final InetSocketAddress remoteAddress;
    private final boolean secure;
    private final TLSInfo tlsInfo;

    /**
     * Creates a ConnectionInfo from a Connection.
     * 
     * @param localAddr the local socket address
     * @param remoteAddr the remote socket address
     * @param secure whether the connection is using TLS
     * @param tlsInfo TLS information, or null if not secure
     */
    public DefaultConnectionInfo(SocketAddress localAddr, SocketAddress remoteAddr,
                                  boolean secure, TLSInfo tlsInfo) {
        this.localAddress = toInetSocketAddress(localAddr);
        this.remoteAddress = toInetSocketAddress(remoteAddr);
        this.secure = secure;
        this.tlsInfo = tlsInfo;
    }

    private static InetSocketAddress toInetSocketAddress(SocketAddress addr) {
        if (addr instanceof InetSocketAddress) {
            return (InetSocketAddress) addr;
        }
        // Fallback for unexpected address types
        return new InetSocketAddress("0.0.0.0", 0);
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public TLSInfo getTLSInfo() {
        return tlsInfo;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Connection[");
        sb.append(localAddress);
        sb.append(" -> ");
        sb.append(remoteAddress);
        if (secure) {
            sb.append(", secure");
            if (tlsInfo != null) {
                sb.append(", ");
                sb.append(tlsInfo);
            }
        }
        sb.append("]");
        return sb.toString();
    }

}

