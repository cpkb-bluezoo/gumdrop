/*
 * Tls12TicketPayload.java
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

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The plaintext sealed inside an opaque RFC 5077 TLS 1.2 session ticket --
 * deliberately separate from {@link TicketPayload} (the TLS 1.3 PSK/0-RTT
 * ticket format): the two protocols' ticket contents differ in kind, not
 * just detail. TLS 1.2 seals the full 48-byte {@code master_secret}
 * verbatim (RFC 5246 section 8.1) -- there is no per-resumption PSK
 * derivation the way TLS 1.3's {@code resumption_master_secret} gives a
 * fresh PSK per ticket, so a leaked TLS 1.2 ticket-encryption key or
 * ticket exposes every session resumed under it directly, with no forward
 * secrecy across resumptions. Short ticket lifetimes and real key
 * rotation ({@link TicketKeys#rotate}) are the only mitigation.
 *
 * <p>Sealed the same way {@link TicketPayload}/{@code RetryToken} are:
 * AES-128-GCM under a {@link TicketKeys} key, a random 12-byte nonce, then
 * ciphertext, then a 16-byte tag, with no key ID anywhere -- {@link #open}
 * is tried against every {@link TicketKeys#candidateKeys() candidate key}
 * in turn.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc5077#section-4">RFC 5077 section 4</a>
 */
final class Tls12TicketPayload {

    /** RFC 5077 section 3.3's default ticket lifetime advertisement, seconds (24 hours). */
    static final long TICKET_LIFETIME_SECS = 24 * 60 * 60;

    private static final int NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;
    private static final byte PAYLOAD_VERSION = 0x01;
    private static final int PLAINTEXT_LENGTH = 1 + 48 + 2 + 8 + 4;

    final byte[] masterSecret;
    final int cipherSuiteCode;
    final long issuedAtMillis;
    final long lifetimeSecs;

    Tls12TicketPayload(byte[] masterSecret, int cipherSuiteCode, long issuedAtMillis, long lifetimeSecs) {
        this.masterSecret = masterSecret;
        this.cipherSuiteCode = cipherSuiteCode;
        this.issuedAtMillis = issuedAtMillis;
        this.lifetimeSecs = lifetimeSecs;
    }

    /**
     * True once this ticket has outlived its advertised lifetime. Fails
     * closed (reports expired) if the payload's own {@link #issuedAtMillis}
     * is somehow in the future relative to now -- not expected in
     * practice, but never treated as "not yet expired, so still valid
     * forever" either.
     *
     * @return true if expired
     */
    boolean isExpired() {
        long ageMillis = System.currentTimeMillis() - issuedAtMillis;
        return ageMillis < 0 || ageMillis > lifetimeSecs * 1000L;
    }

    /**
     * Seals this payload under {@code key} -- the opaque ticket bytes sent
     * in a {@code NewSessionTicket}.
     *
     * @param key the current ticket key, 16 bytes (AES-128-GCM)
     * @return the sealed ticket bytes
     */
    byte[] seal(byte[] key) {
        ByteBuffer plaintext = ByteBuffer.allocate(PLAINTEXT_LENGTH);
        plaintext.put(PAYLOAD_VERSION);
        plaintext.put(masterSecret);
        plaintext.putShort((short) cipherSuiteCode);
        plaintext.putLong(issuedAtMillis);
        plaintext.putInt((int) lifetimeSecs);

        byte[] nonce = new byte[NONCE_LENGTH];
        new SecureRandom().nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.array());
            ByteBuffer ticket = ByteBuffer.allocate(NONCE_LENGTH + ciphertext.length);
            ticket.put(nonce);
            ticket.put(ciphertext);
            return ticket.array();
        } catch (GeneralSecurityException e) {
            // key is always exactly 16 bytes and the transformation is
            // always available; a real JCE provider never fails this call.
            throw new IllegalStateException("Session ticket sealing failed", e);
        }
    }

    /**
     * Unseals a ticket under a candidate key.
     *
     * @param key a candidate ticket key, 16 bytes
     * @param ticket the opaque ticket bytes presented in {@code SessionTicket}
     * @return the payload, or null if the tag does not verify under this
     *         key or the ticket is malformed
     */
    static Tls12TicketPayload open(byte[] key, byte[] ticket) {
        if (ticket.length < NONCE_LENGTH + GCM_TAG_LENGTH) {
            return null;
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        System.arraycopy(ticket, 0, nonce, 0, NONCE_LENGTH);
        byte[] ciphertext = new byte[ticket.length - NONCE_LENGTH];
        System.arraycopy(ticket, NONCE_LENGTH, ciphertext, 0, ciphertext.length);

        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // Decrypt side: nonce is read back from the ticket bytes above
            // (whatever seal() actually generated via SecureRandom for
            // this specific ticket), the only way to decrypt a GCM
            // ciphertext at all.
            // codeql[java/static-initialization-vector]
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce));
            plaintext = cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            return null;
        } catch (GeneralSecurityException e) {
            return null;
        }

        if (plaintext.length != PLAINTEXT_LENGTH || plaintext[0] != PAYLOAD_VERSION) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(plaintext);
        buf.get(); // version, already checked
        byte[] masterSecret = new byte[48];
        buf.get(masterSecret);
        int cipherSuiteCode = buf.getShort() & 0xffff;
        long issuedAtMillis = buf.getLong();
        long lifetimeSecs = buf.getInt() & 0xffffffffL;
        return new Tls12TicketPayload(masterSecret, cipherSuiteCode, issuedAtMillis, lifetimeSecs);
    }

}
