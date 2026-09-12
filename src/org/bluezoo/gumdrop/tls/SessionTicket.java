/*
 * SessionTicket.java
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
 * A TLS 1.3 session ticket (RFC 8446 section 4.6.1) as a client stores
 * it after receiving a {@code NewSessionTicket} and later presents it in
 * {@link HandshakeConfig#setSessionTicket}: the opaque identity to
 * re-present verbatim, the wire lifetime/age fields needed to compute
 * {@code obfuscated_ticket_age}, and the resumption PSK the client
 * already derived and stored itself (via
 * {@link KeySchedule#deriveResumptionPsk}) from the connection's
 * {@code resumption_master_secret} and the ticket's nonce -- a client
 * never re-derives this from the raw ticket, since only the issuing
 * server can open one.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.6.1">RFC 8446 section 4.6.1</a>
 */
public final class SessionTicket {

    private final byte[] identity;
    private final int lifetimeSeconds;
    private final int ageAdd;
    private final long issuedAtMillis;
    private final int maxEarlyDataSize;
    private final CipherSuite cipherSuite;
    private final byte[] psk;

    /**
     * Creates a session ticket.
     *
     * @param identity the opaque ticket identity, re-presented verbatim
     *                 in a future ClientHello's {@code pre_shared_key} extension
     * @param lifetimeSeconds the ticket's advertised lifetime, seconds
     * @param ageAdd the {@code ticket_age_add} value from the NewSessionTicket
     * @param issuedAtMillis when this client received the ticket
     *                       ({@link System#currentTimeMillis()} epoch millis)
     * @param maxEarlyDataSize the maximum 0-RTT data this ticket may be
     *                         used for, or 0 if it was issued with no early data
     * @param cipherSuite the cipher suite negotiated when this ticket was issued --
     *                    a resumption PSK's binder must be computed with this
     *                    same suite's hash algorithm (RFC 8446 section 4.2.11)
     * @param psk the resumption PSK, already derived from
     *            {@code resumption_master_secret} and the ticket's nonce
     */
    public SessionTicket(byte[] identity, int lifetimeSeconds, int ageAdd, long issuedAtMillis,
            int maxEarlyDataSize, CipherSuite cipherSuite, byte[] psk) {
        this.identity = identity;
        this.lifetimeSeconds = lifetimeSeconds;
        this.ageAdd = ageAdd;
        this.issuedAtMillis = issuedAtMillis;
        this.maxEarlyDataSize = maxEarlyDataSize;
        this.cipherSuite = cipherSuite;
        this.psk = psk;
    }

    /**
     * Returns the opaque ticket identity.
     *
     * @return the ticket identity bytes
     */
    public byte[] getIdentity() {
        return identity;
    }

    /**
     * Returns the ticket's advertised lifetime.
     *
     * @return the lifetime, seconds
     */
    public int getLifetimeSeconds() {
        return lifetimeSeconds;
    }

    /**
     * Returns the {@code ticket_age_add} value.
     *
     * @return the age-add value
     */
    public int getAgeAdd() {
        return ageAdd;
    }

    /**
     * Returns when this client received the ticket.
     *
     * @return the receipt time, epoch millis
     */
    public long getIssuedAtMillis() {
        return issuedAtMillis;
    }

    /**
     * Returns the maximum 0-RTT data this ticket may be used for.
     *
     * @return the maximum early data size in bytes, or 0 for none
     */
    public int getMaxEarlyDataSize() {
        return maxEarlyDataSize;
    }

    /**
     * Returns the cipher suite negotiated when this ticket was issued.
     *
     * @return the cipher suite
     */
    public CipherSuite getCipherSuite() {
        return cipherSuite;
    }

    /**
     * Returns the resumption PSK.
     *
     * @return the PSK bytes
     */
    public byte[] getPsk() {
        return psk;
    }

    /**
     * Computes RFC 8446 section 4.2.11's {@code obfuscated_ticket_age}:
     * the real age (milliseconds since receipt) plus {@link #getAgeAdd},
     * wrapping modulo 2^32 -- hides the ticket's real age from a passive
     * observer while still letting the issuing server, which knows the
     * same {@code ageAdd}, recover it.
     *
     * @return the obfuscated ticket age to send in a ClientHello
     */
    public int obfuscatedTicketAge() {
        long ageMillis = System.currentTimeMillis() - issuedAtMillis;
        // Two's-complement int addition wraps mod 2^32, matching the RFC's own arithmetic.
        return (int) ageMillis + ageAdd;
    }

    /**
     * Returns whether this ticket is past its advertised lifetime.
     *
     * @return true if expired
     */
    public boolean isExpired() {
        long ageMillis = System.currentTimeMillis() - issuedAtMillis;
        return ageMillis > lifetimeSeconds * 1000L;
    }

}
