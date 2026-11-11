/*
 * DnsQueryContext.java
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
 * Describes the client connection a DNS query arrived on, so that a
 * {@link org.bluezoo.gumdrop.dns.server.DnsQueryHandler} can vary its answer
 * by who is asking (split-horizon views, per-client allocation, mutual TLS
 * peer identity).
 *
 * <p>A context is an immutable snapshot taken when the query was received.
 * It remains valid after the underlying connection has closed, so a handler
 * that answers asynchronously may keep it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.dns.server.DnsQueryHandler#handleQuery(DnsMessage,
 *      org.bluezoo.gumdrop.SelectorLoop, DnsQueryContext, DnsQueryCallback)
 */
public interface DnsQueryContext {

    /**
     * Returns the address of the client that sent the query, or
     * {@code null} if it is not known.
     */
    SocketAddress getRemoteAddress();

    /**
     * Returns the local address the query arrived on, or {@code null} if it
     * is not known.
     */
    SocketAddress getLocalAddress();

    /**
     * Returns how the query arrived (datagram or framed stream).
     */
    DnsQueryTransport getTransport();

    /**
     * Returns the name of the wire transport: {@code "udp"}, {@code "tcp"},
     * {@code "dot"}, {@code "doq"} or {@code "dtls"}.
     */
    String getTransportName();

    /**
     * Returns the negotiated security parameters, including the client
     * certificate chain when mutual authentication was performed, or
     * {@code null} for plaintext transports.
     */
    SecurityInfo getSecurityInfo();

    /**
     * Creates a context.
     *
     * @param transportName see {@link #getTransportName()}
     * @param transport how the query arrived
     * @param remote the client address, or {@code null}
     * @param local the local address, or {@code null}
     * @param security the security parameters, or {@code null} if plaintext
     */
    static DnsQueryContext of(String transportName, DnsQueryTransport transport,
                              SocketAddress remote, SocketAddress local,
                              SecurityInfo security) {
        return new DefaultDnsQueryContext(transportName, transport, remote,
                local, security);
    }
}
