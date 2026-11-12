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
 * that answers asynchronously may keep it. The one thing that is not a
 * snapshot is the {@linkplain #getConnectionAttribute connection attributes},
 * which a handler uses to keep state for the connection.
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
     * Returns what a handler stored for the connection under {@code key} with
     * {@link #setConnectionAttribute}, or {@code null} if nothing is stored,
     * the connection has closed, or the query has no connection.
     *
     * <p>The attributes belong to the connection and are shared by every
     * context of its queries, so something worked out for the first query
     * (a parsed client identity, say) is there for the rest. They are
     * released when the connection closes. They are not synchronised: use
     * them only on the connection's selector loop, the one passed to
     * {@code handleQuery}.
     *
     * <p>Which queries have a connection: DoT and DNS over TCP, one
     * connection each. A UDP or DTLS datagram has none, so nothing is ever
     * stored and this returns {@code null}. In DoQ each query has a stream
     * of its own, so its attributes last for the query only.
     *
     * @param key the key
     * @param <T> the type of the value
     * @return the value, or {@code null}
     */
    default <T> T getConnectionAttribute(Key<T> key) {
        return null;
    }

    /**
     * Stores a value for the connection under {@code key}, replacing any
     * earlier one; {@code null} removes it. Does nothing if the query has no
     * connection (see {@link #getConnectionAttribute}) or the connection has
     * closed.
     *
     * @param key the key
     * @param value the value, or {@code null} to remove
     * @param <T> the type of the value
     */
    default <T> void setConnectionAttribute(Key<T> key, T value) {
    }

    /**
     * A typed key for a connection attribute. Keys are compared by identity,
     * so two keys never collide, whatever their names: declare each as a
     * constant.
     *
     * @param <T> the type of the values stored under it
     */
    final class Key<T> {

        private final String name;

        private Key(String name) {
            this.name = name;
        }

        /**
         * Creates a key.
         *
         * @param name a name, for diagnostics only
         * @param <T> the type of the values stored under it
         * @return a new key, equal to no other
         */
        public static <T> Key<T> of(String name) {
            return new Key<T>(name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

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
