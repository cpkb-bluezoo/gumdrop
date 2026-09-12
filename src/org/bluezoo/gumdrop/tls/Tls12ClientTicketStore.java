/*
 * Tls12ClientTicketStore.java
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A TLS 1.2 client's shared, SNI-keyed cache of RFC 5077 session tickets,
 * for offering resumption on a later connection to the same server name.
 *
 * <p>Deliberately instance-scoped, not process-wide/static like
 * {@code org.bluezoo.gumdrop.quic.SessionTicketCache} -- that cache has
 * to be static because a fresh {@code QuicTransportFactory} is
 * constructed for every QUIC connect call, with nothing else to hold a
 * cache across dials. A TCP TLS 1.2 caller wanting resumption across
 * reconnects instead constructs <strong>one</strong> store and passes it
 * to {@link Tls12HandshakeConfig#setClientTicketStore} for every
 * connection attempt to the same server -- the caller owns the sharing
 * lifetime, not this class.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5077#section-3.4">RFC 5077 section 3.4</a>
 */
public final class Tls12ClientTicketStore {

    private final Map<String, Tls12SessionTicket> tickets = new ConcurrentHashMap<String, Tls12SessionTicket>();

    /**
     * Creates an empty store.
     */
    public Tls12ClientTicketStore() {
    }

    /**
     * Inserts or replaces the ticket cached for {@code serverName}.
     *
     * @param serverName the server name the ticket was received from
     * @param ticket the ticket to cache
     */
    void put(String serverName, Tls12SessionTicket ticket) {
        tickets.put(serverName, ticket);
    }

    /**
     * Looks up a cached ticket for {@code serverName}, evicting and
     * returning null if it has expired.
     *
     * @param serverName the server name to look up
     * @return the cached ticket, or null if absent or expired
     */
    Tls12SessionTicket get(String serverName) {
        Tls12SessionTicket ticket = tickets.get(serverName);
        if (ticket == null) {
            return null;
        }
        if (ticket.isExpired()) {
            tickets.remove(serverName, ticket);
            return null;
        }
        return ticket;
    }

    /**
     * Removes any cached ticket for {@code serverName} -- e.g. after a
     * failed resumption attempt.
     *
     * @param serverName the server name to clear
     */
    void remove(String serverName) {
        tickets.remove(serverName);
    }

}
