/*
 * DnsTcpListener.java
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

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;

import java.net.InetAddress;

/**
 * Cleartext DNS-over-TCP listener (RFC 1035 section 4.2.2).
 *
 * <p>Uses the same length-prefixed framing as DoT, without TLS. Suitable
 * for AXFR/IXFR and large responses.
  * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsTcpListener extends TcpListener {

    private static final int DEFAULT_PORT = 53;

    private int port = DEFAULT_PORT;
    private org.bluezoo.gumdrop.dns.server.DnsServer server;

    public DnsTcpListener() {
        secure = false;
    }

    @Override
    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public DnsTcpListener port(int port) {
        setPort(port);
        return this;
    }

    @Override
    public DnsTcpListener bindWildcard() {
        super.bindWildcard();
        return this;
    }

    @Override
    public DnsTcpListener addresses(InetAddress... addrs) {
        super.addresses(addrs);
        return this;
    }

    @Override
    public String getDescription() {
        return "dns-tcp";
    }

    public void setServer(org.bluezoo.gumdrop.dns.server.DnsServer server) {
        this.server = server;
    }

    public org.bluezoo.gumdrop.dns.server.DnsServer getServer() {
        return server;
    }

    @Override
    protected ProtocolHandler createHandler() {
        return new DoTProtocolHandler(server, "tcp");
    }
}
