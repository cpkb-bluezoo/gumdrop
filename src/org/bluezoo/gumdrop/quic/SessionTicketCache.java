/*
 * SessionTicketCache.java
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

package org.bluezoo.gumdrop.quic;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.tls.SessionTicket;

/**
 * Process-wide, host:port-keyed cache of TLS session tickets (RFC 8446
 * section 4.6.1) received on a previous QUIC connection, available to
 * attempt PSK resumption -- and, if the ticket allows it and the server
 * accepts, 0-RTT (RFC 9001 section 4.6.1) -- on a later, separate
 * connection to the same server.
 *
 * <p>Entries hold the real {@link SessionTicket} and
 * {@link TransportParameters} objects directly rather than a serialized
 * byte form -- unlike a ticket's own opaque wire identity (which really
 * does need to round-trip through bytes, since it is presented back to a
 * possibly-different-process server), this cache exists only within one
 * gumdrop process's memory, so there is nothing to serialize for.
 *
 * <p>Structured the same way as {@link org.bluezoo.gumdrop.http.client.AltSvcCache}
 * solves the analogous problem for Alt-Svc discovery: the object that
 * learns something mid-connection ({@link QuicConnection}) isn't the
 * object a later, separate connection attempt is made through (a fresh
 * {@link QuicTransportFactory} is constructed for every connect call), so
 * the cache has to be static/process-wide rather than an instance field.
 * Placed in this package rather than {@code http.client} since it is
 * transport-generic -- both the HTTP/3 client and DoQ consult it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class SessionTicketCache {

    private static final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

    /**
     * Test-only: if non-null, invoked immediately after a ticket is stored.
     */
    public static volatile Runnable putObserver;

    private SessionTicketCache() {
    }

    /**
     * Stores a session ticket and the peer transport parameters
     * remembered alongside it, replacing any existing entry for the same
     * {@code host:port}.
     *
     * @param host the server host (SNI name, or the peer address for a
     *             connection with no SNI, e.g. DoQ)
     * @param port the server port
     * @param ticket the received session ticket
     * @param transportParameters the peer's transport parameters on the
     *                            connection that issued this ticket
     */
    public static void put(String host, int port, SessionTicket ticket, TransportParameters transportParameters) {
        long expiryTime = System.currentTimeMillis() + ticket.getLifetimeSeconds() * 1000L;
        cache.put(key(host, port), new Entry(ticket, transportParameters, expiryTime));
        Runnable observer = putObserver;
        if (observer != null) {
            observer.run();
        }
    }

    /**
     * Returns the cached session ticket entry for {@code host:port}, or
     * {@code null} if absent or expired.
     *
     * @param host the server host
     * @param port the server port
     * @return the cached entry, or {@code null}
     */
    public static Entry get(String host, int port) {
        String k = key(host, port);
        Entry entry = cache.get(k);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            cache.remove(k, entry);
            return null;
        }
        return entry;
    }

    /**
     * Clears all cached entries. Intended for tests.
     */
    public static void clear() {
        cache.clear();
    }

    private static String key(String host, int port) {
        return host.toLowerCase() + ":" + port;
    }

    /**
     * A cached session ticket and the transport parameters remembered
     * alongside it.
     */
    public static final class Entry {
        private final SessionTicket ticket;
        private final TransportParameters transportParameters;
        private final long expiryTime;

        Entry(SessionTicket ticket, TransportParameters transportParameters, long expiryTime) {
            this.ticket = ticket;
            this.transportParameters = transportParameters;
            this.expiryTime = expiryTime;
        }

        /**
         * Returns the cached session ticket.
         *
         * @return the session ticket
         */
        public SessionTicket toTicket() {
            return ticket;
        }

        /**
         * Returns the remembered transport parameters.
         *
         * @return the remembered transport parameters
         */
        public TransportParameters toTransportParameters() {
            return transportParameters;
        }

        boolean isExpired() {
            return System.currentTimeMillis() >= expiryTime;
        }
    }
}
