/*
 * Tls12SessionTicket.java
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

/**
 * Material a TLS 1.2 client caches after receiving a {@code NewSessionTicket}
 * (RFC 5077 section 3.3), and offers back on a future connection to the
 * same server name to attempt resumption -- the client-side counterpart
 * of {@link Tls12TicketPayload}, which is what actually travels the wire
 * as the opaque ticket. Package-private: an embedder only ever constructs
 * an empty {@link Tls12ClientTicketStore} and hands it to
 * {@link Tls12HandshakeConfig#setClientTicketStore}; {@link Tls12HandshakeEngine}
 * populates and reads entries automatically.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class Tls12SessionTicket {

    private final byte[] ticket;
    private final byte[] masterSecret;
    private final Tls12CipherSuite cipherSuite;
    private final long receivedAtMillis;
    private final long lifetimeSecs;

    Tls12SessionTicket(byte[] ticket, byte[] masterSecret, Tls12CipherSuite cipherSuite,
            long receivedAtMillis, long lifetimeSecs) {
        this.ticket = ticket;
        this.masterSecret = masterSecret;
        this.cipherSuite = cipherSuite;
        this.receivedAtMillis = receivedAtMillis;
        this.lifetimeSecs = lifetimeSecs;
    }

    /** The opaque ticket bytes, replayed verbatim in a future ClientHello. */
    byte[] getTicket() {
        return ticket;
    }

    /** The master secret sealed under this ticket, needed locally to derive the resumed connection's keys. */
    byte[] getMasterSecret() {
        return masterSecret;
    }

    /** The cipher suite this ticket was issued under; a resumption offer only makes sense if the server still selects it. */
    Tls12CipherSuite getCipherSuite() {
        return cipherSuite;
    }

    /**
     * True when this ticket is past its advertised lifetime.
     *
     * @return true if expired
     */
    boolean isExpired() {
        long ageMillis = System.currentTimeMillis() - receivedAtMillis;
        return ageMillis < 0 || ageMillis > lifetimeSecs * 1000L;
    }

}
