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
 *
 * <h2>Limits</h2>
 * <p>A client can hold a connection, and the server's work for it, only
 * within bounds that are set on the listener:
 * <ul>
 * <li>{@link #maxConnections(int)}: the most connections open at once;
 * further ones are refused.</li>
 * <li>{@link #maxConnectionsPerIP(int)}: the most from one client address.</li>
 * <li>{@link #idleTimeoutMs(long)}, {@link #readTimeoutMs(long)} and
 * {@link #connectionTimeoutMs(long)}: how long a connection may sit idle, a
 * read may stall, and a connection may take to be established, before it is
 * closed.</li>
 * <li>{@link #maxInFlightQueries(int)}: the most queries in flight on one
 * connection; a client that pipelines more waits, unread, until a
 * response has been sent.</li>
 * </ul>
 * Each connection also holds at least 68 KiB of buffers, so the connection
 * limits matter as much as the others.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DnsTcpListener extends TcpListener {

    private static final int DEFAULT_PORT = 53;

    private int port = DEFAULT_PORT;
    private org.bluezoo.gumdrop.dns.server.DnsServer server;
    private int maxInFlightQueries = DoTProtocolHandler.DEFAULT_MAX_IN_FLIGHT;

    public DnsTcpListener() {
        secure = false;
    }

    @Override
    public int getPort() {
        return port;
    }

    public DnsTcpListener port(int port) {
        this.port = port;
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

    /**
     * Sets the most queries that may be in flight on one connection. A
     * client may pipeline any number of queries; once this many are being
     * handled, the connection is not read until a response has been sent
     * (backpressure), so no query is refused or dropped and the work a
     * client can make the server hold is bounded. The default is
     * 128; 0 for no limit.
     *
     * @param max the limit
     * @return this listener
     * @throws IllegalArgumentException if negative
     */
    public DnsTcpListener maxInFlightQueries(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("negative limit: " + max);
        }
        this.maxInFlightQueries = max;
        return this;
    }

    /**
     * Returns the most queries that may be in flight on one connection.
     *
     * @return the limit; 0 for none
     */
    int getMaxInFlightQueries() {
        return maxInFlightQueries;
    }

    @Override
    public String getDescription() {
        return "dns-tcp";
    }

    public DnsTcpListener server(org.bluezoo.gumdrop.dns.server.DnsServer server) {
        this.server = server;
        return this;
    }

    public org.bluezoo.gumdrop.dns.server.DnsServer getServer() {
        return server;
    }

    @Override
    protected ProtocolHandler createHandler() {
        return new DoTProtocolHandler(server, "tcp", maxInFlightQueries);
    }
}
