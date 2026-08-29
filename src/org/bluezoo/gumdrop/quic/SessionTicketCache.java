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

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import tech.kwik.agent15.NewSessionTicket;

import org.bluezoo.gumdrop.quic.packet.TransportParameters;

/**
 * Process-wide, host:port-keyed cache of TLS session tickets (RFC 8446
 * section 4.6.1) received on a previous QUIC connection, available to
 * attempt PSK resumption -- and, if the ticket allows it and the server
 * accepts, 0-RTT (RFC 9001 section 4.6.1) -- on a later, separate
 * connection to the same server.
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
 * <p>Stores the ticket's and transport parameters' serialized bytes
 * ({@link NewSessionTicket#serialize()}, {@link TransportParameters#encode()})
 * rather than the live objects, exercising the same round-trip methods
 * those classes already expose for their own wire formats.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class SessionTicketCache {

    // RFC 8446 section 4.6.1: "Clients MUST NOT cache tickets for longer
    // than 7 days, regardless of the ticket_lifetime". Agent15 itself
    // already enforces this cap and falls back to a full handshake on an
    // expired ticket, so this bound is cache hygiene (avoid presenting an
    // obviously-stale ticket, bound memory), not a correctness requirement.
    private static final long MAX_TICKET_LIFETIME_SECONDS = 7L * 24 * 60 * 60;

    private static final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

    /**
     * Test-only: if non-null, invoked immediately after a ticket is stored.
     */
    public static volatile Runnable putObserver;

    private SessionTicketCache() {
    }

    /**
     * Records a session ticket received for {@code host:port}, along
     * with the transport parameters this connection's peer advertised
     * (RFC 9000 section 7.4.1: a 0-RTT attempt must not exceed the
     * previous connection's remembered limits until new ones arrive).
     *
     * @param host the server host (SNI name, or a literal address if
     *             none was used, e.g. DoQ)
     * @param port the server port
     * @param ticket the received session ticket
     * @param rememberedTransportParameters the peer's transport
     *             parameters on the connection the ticket was received on
     */
    public static void put(String host, int port, NewSessionTicket ticket,
            TransportParameters rememberedTransportParameters) {
        long lifetimeSeconds = Math.min(ticket.getTicketLifeTime(), MAX_TICKET_LIFETIME_SECONDS);
        long expiry = System.currentTimeMillis() + Math.max(0, lifetimeSeconds) * 1000L;
        cache.put(key(host, port), new Entry(ticket.serialize(),
                rememberedTransportParameters.encode(), expiry));
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
        private final byte[] serializedTicket;
        private final byte[] encodedTransportParameters;
        private final long expiryTime;

        Entry(byte[] serializedTicket, byte[] encodedTransportParameters, long expiryTime) {
            this.serializedTicket = serializedTicket;
            this.encodedTransportParameters = encodedTransportParameters;
            this.expiryTime = expiryTime;
        }

        /**
         * Deserializes the cached ticket.
         *
         * @return the session ticket
         */
        public NewSessionTicket toTicket() {
            return NewSessionTicket.deserialize(serializedTicket);
        }

        /**
         * Decodes the cached transport parameters.
         *
         * @return the remembered transport parameters
         */
        public TransportParameters toTransportParameters() {
            return TransportParameters.decode(ByteBuffer.wrap(encodedTransportParameters));
        }

        boolean isExpired() {
            return System.currentTimeMillis() >= expiryTime;
        }
    }
}
