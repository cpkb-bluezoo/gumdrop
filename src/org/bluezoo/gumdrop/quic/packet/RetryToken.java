/*
 * RetryToken.java
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

package org.bluezoo.gumdrop.quic.packet;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The opaque Retry Token a server places in a Retry packet and a client
 * echoes back in the Token field of its next Initial packet (RFC 9000
 * section 8.1.2). Unlike {@link RetryIntegrityTag}, this is not an
 * RFC-fixed format -- the token's contents and validation are entirely a
 * server's own choice (RFC 9000 section 8.1.3) -- so this class defines
 * gumdrop's own scheme: an AES-256-GCM-sealed, per-{@code QuicTransportFactory}
 * key, binding the token to the client's source address and an issue
 * timestamp, so a subsequent Initial carrying the token can be validated
 * without any server-side per-client state.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-8.1.3">RFC 9000 section 8.1.3</a>
 */
public final class RetryToken {

    private static final int NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;

    private RetryToken() {
    }

    /**
     * Seals a new Retry Token.
     *
     * @param key the sealing key (32 bytes, AES-256)
     * @param originalDestinationConnectionId the client's Initial
     *        packet's own Destination Connection ID
     * @param clientAddress the client's source address
     * @param issuedAtMillis the issue time, {@link System#currentTimeMillis()}
     * @return the opaque token bytes
     */
    public static byte[] seal(byte[] key, byte[] originalDestinationConnectionId, InetAddress clientAddress,
            long issuedAtMillis) {
        byte[] nonce = new byte[NONCE_LENGTH];
        new SecureRandom().nextBytes(nonce);

        ByteBuffer plaintext = ByteBuffer.allocate(1 + originalDestinationConnectionId.length + 8);
        plaintext.put((byte) originalDestinationConnectionId.length);
        plaintext.put(originalDestinationConnectionId);
        plaintext.putLong(issuedAtMillis);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce));
            cipher.updateAAD(clientAddress.getAddress());
            byte[] ciphertext = cipher.doFinal(plaintext.array());

            ByteBuffer token = ByteBuffer.allocate(NONCE_LENGTH + ciphertext.length);
            token.put(nonce);
            token.put(ciphertext);
            return token.array();
        } catch (GeneralSecurityException e) {
            // key is a fixed 32-byte value and the transformation is
            // always available; a real JCE provider never fails this call.
            throw new IllegalStateException("Retry token sealing failed", e);
        }
    }

    /**
     * Unseals and validates a Retry Token received in a client Initial
     * packet's Token field.
     *
     * @param key the sealing key this server used, as passed to {@link #seal}
     * @param token the received token bytes
     * @param clientAddress the source address the Initial packet carrying
     *        this token actually arrived from
     * @param maxAgeMillis the maximum age to accept, to bound replay of a
     *        captured token
     * @return the original Destination Connection ID sealed into the
     *         token, or {@code null} if the token is malformed, its tag
     *         doesn't verify, its bound address doesn't match, or it has
     *         expired
     */
    public static byte[] unseal(byte[] key, byte[] token, InetAddress clientAddress, long maxAgeMillis) {
        if (token.length < NONCE_LENGTH + GCM_TAG_LENGTH) {
            return null;
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        System.arraycopy(token, 0, nonce, 0, NONCE_LENGTH);
        byte[] ciphertext = new byte[token.length - NONCE_LENGTH];
        System.arraycopy(token, NONCE_LENGTH, ciphertext, 0, ciphertext.length);

        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce));
            cipher.updateAAD(clientAddress.getAddress());
            plaintext = cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            return null;
        } catch (GeneralSecurityException e) {
            return null;
        }

        try {
            ByteBuffer buf = ByteBuffer.wrap(plaintext);
            int dcidLength = buf.get() & 0xff;
            byte[] dcid = new byte[dcidLength];
            buf.get(dcid);
            long issuedAtMillis = buf.getLong();
            if (System.currentTimeMillis() - issuedAtMillis > maxAgeMillis) {
                return null;
            }
            return dcid;
        } catch (RuntimeException e) {
            // malformed plaintext (shouldn't happen once the AEAD tag has
            // verified, but the length-prefixed decode above is not
            // itself bounds-checked against a truncated/corrupt buffer)
            return null;
        }
    }
}
