/*
 * TicketPayload.java
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
 * The plaintext {@link HandshakeEngine} seals inside a server-issued
 * session ticket's opaque identity (RFC 8446 section 4.6.1) -- entirely
 * this server's own private format, never interpreted by a client (a
 * client only ever re-presents the sealed identity bytes verbatim).
 * AES-128-GCM under a {@link TicketKeys} key, matching the scheme
 * {@code org.bluezoo.gumdrop.quic.packet.RetryToken} already uses for
 * QUIC Retry Tokens: a random 12-byte nonce, then ciphertext, then a
 * 16-byte tag, with no key ID anywhere -- {@link #open} is tried against
 * every {@link TicketKeys#candidateKeys() candidate key} in turn.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class TicketPayload {

    private static final int NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;

    final long issuedAtMillis;
    final int lifetimeSeconds;
    final int ageAdd;
    final byte[] psk;
    final int maxEarlyDataSize;
    final CipherSuite cipherSuite;
    final byte[] rememberedTransportParameters;

    TicketPayload(long issuedAtMillis, int lifetimeSeconds, int ageAdd, byte[] psk, int maxEarlyDataSize,
            CipherSuite cipherSuite, byte[] rememberedTransportParameters) {
        this.issuedAtMillis = issuedAtMillis;
        this.lifetimeSeconds = lifetimeSeconds;
        this.ageAdd = ageAdd;
        this.psk = psk;
        this.maxEarlyDataSize = maxEarlyDataSize;
        this.cipherSuite = cipherSuite;
        this.rememberedTransportParameters = rememberedTransportParameters;
    }

    /**
     * Seals this payload under {@code key} -- the opaque ticket identity
     * sent in a NewSessionTicket.
     *
     * @param key the current ticket key, 16 bytes (AES-128-GCM)
     * @return the sealed identity bytes
     */
    byte[] seal(byte[] key) {
        byte[] tp = (rememberedTransportParameters != null) ? rememberedTransportParameters : new byte[0];
        ByteBuffer plaintext = ByteBuffer.allocate(8 + 4 + 4 + 1 + psk.length + 4 + 2 + 2 + tp.length);
        plaintext.putLong(issuedAtMillis);
        plaintext.putInt(lifetimeSeconds);
        plaintext.putInt(ageAdd);
        plaintext.put((byte) psk.length);
        plaintext.put(psk);
        plaintext.putInt(maxEarlyDataSize);
        plaintext.putShort((short) cipherSuite.getCode());
        plaintext.putShort((short) tp.length);
        plaintext.put(tp);

        byte[] nonce = new byte[NONCE_LENGTH];
        new SecureRandom().nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.array());
            ByteBuffer identity = ByteBuffer.allocate(NONCE_LENGTH + ciphertext.length);
            identity.put(nonce);
            identity.put(ciphertext);
            return identity.array();
        } catch (GeneralSecurityException e) {
            // key is always exactly 16 bytes and the transformation is
            // always available; a real JCE provider never fails this call.
            throw new IllegalStateException("Session ticket sealing failed", e);
        }
    }

    /**
     * Unseals a ticket identity under a candidate key.
     *
     * @param key a candidate ticket key, 16 bytes
     * @param identity the opaque identity bytes presented in {@code pre_shared_key}
     * @return the payload, or null if the tag does not verify under this
     *         key, the identity is malformed, or its cipher suite is unrecognised
     */
    static TicketPayload open(byte[] key, byte[] identity) {
        if (identity.length < NONCE_LENGTH + GCM_TAG_LENGTH) {
            return null;
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        System.arraycopy(identity, 0, nonce, 0, NONCE_LENGTH);
        byte[] ciphertext = new byte[identity.length - NONCE_LENGTH];
        System.arraycopy(identity, NONCE_LENGTH, ciphertext, 0, ciphertext.length);

        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // This is the decrypt side: nonce is read back from the
            // identity bytes above (whatever seal() actually generated
            // via SecureRandom for that specific ticket), which is the
            // only way to decrypt a GCM ciphertext at all.
            // codeql[java/static-initialization-vector]
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce));
            plaintext = cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            return null;
        } catch (GeneralSecurityException e) {
            return null;
        }

        try {
            ByteBuffer buf = ByteBuffer.wrap(plaintext);
            long issuedAtMillis = buf.getLong();
            int lifetimeSeconds = buf.getInt();
            int ageAdd = buf.getInt();
            int pskLength = buf.get() & 0xff;
            byte[] psk = new byte[pskLength];
            buf.get(psk);
            int maxEarlyDataSize = buf.getInt();
            int cipherSuiteCode = buf.getShort() & 0xffff;
            CipherSuite cipherSuite = CipherSuite.fromCode(cipherSuiteCode);
            int tpLength = buf.getShort() & 0xffff;
            byte[] tp = null;
            if (tpLength > 0) {
                tp = new byte[tpLength];
                buf.get(tp);
            }
            if (cipherSuite == null) {
                return null;
            }
            return new TicketPayload(issuedAtMillis, lifetimeSeconds, ageAdd, psk, maxEarlyDataSize, cipherSuite, tp);
        } catch (RuntimeException e) {
            // malformed plaintext (shouldn't happen once the AEAD tag has
            // verified, but the length-prefixed decode above is not
            // itself bounds-checked against a truncated/corrupt buffer)
            return null;
        }
    }

}
