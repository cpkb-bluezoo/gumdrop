/*
 * DefaultDnsQueryContext.java
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

package org.bluezoo.gumdrop.dns;

import java.net.SocketAddress;

import org.bluezoo.gumdrop.SecurityInfo;

/**
 * Immutable {@link DnsQueryContext}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class DefaultDnsQueryContext implements DnsQueryContext {

    private final String transportName;
    private final DnsQueryTransport transport;
    private final SocketAddress remote;
    private final SocketAddress local;
    private final SecurityInfo security;

    DefaultDnsQueryContext(String transportName, DnsQueryTransport transport,
                           SocketAddress remote, SocketAddress local,
                           SecurityInfo security) {
        if (transport == null) {
            throw new NullPointerException("transport");
        }
        this.transportName = transportName;
        this.transport = transport;
        this.remote = remote;
        this.local = local;
        this.security = security;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return remote;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return local;
    }

    @Override
    public DnsQueryTransport getTransport() {
        return transport;
    }

    @Override
    public String getTransportName() {
        return transportName;
    }

    @Override
    public SecurityInfo getSecurityInfo() {
        return security;
    }
}
